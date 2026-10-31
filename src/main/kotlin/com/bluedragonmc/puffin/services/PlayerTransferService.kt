package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.Transfer
import com.bluedragonmc.puffin.app.PuffinConfig
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.inject.Inject
import com.google.inject.Singleton
import java.time.Duration
import java.util.*

/**
 * Handles transferring players from a draining proxy to a replacement proxy.
 */
interface IPlayerTransferService {
    /** Used to store the destination game server before a player switches proxies */
    suspend fun prepareTransfer(request: Transfer.PrepareTransferRequest): Transfer.PrepareTransferResponse

    /** Retrieves the destination game server after a player switches proxies */
    suspend fun consumeTransfer(request: Transfer.ConsumeTransferRequest): Transfer.ConsumeTransferResponse
}

@Singleton
class PlayerTransferService @Inject constructor(
    private val queueService: IQueueService,
    private val k8sServiceDiscovery: IK8sServiceDiscovery,
    private val config: PuffinConfig,
) : Service(), IPlayerTransferService {

    private data class PendingTransfer(
        val serverName: String,
        val gameId: String,
        val proxyPodName: String,
    )

    private val pendingTransfers = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(config.transferTtlSeconds.coerceAtLeast(1)))
        .build<UUID, PendingTransfer>()

    override suspend fun prepareTransfer(request: Transfer.PrepareTransferRequest): Transfer.PrepareTransferResponse {
        val player = runCatching { UUID.fromString(request.playerUuid) }.getOrNull()
            ?: return rejectWithMessage(request.playerUuid, "invalid player uuid")
        if (request.serverName.isBlank()) {
            return rejectWithMessage(request.playerUuid, "missing server name")
        }
        if (request.gameId.isBlank()) {
            return rejectWithMessage(request.playerUuid, "missing game id")
        }

        val server = queueService.getServer(request.serverName)
            ?: return rejectWithMessage(request.playerUuid, "server '${request.serverName}' is not known")
        if (!server.ready || server.draining || server.terminating) {
            return rejectWithMessage(request.playerUuid, "server '${request.serverName}' is not available")
        }
        val address = server.address
            ?: return rejectWithMessage(request.playerUuid, "server '${request.serverName}' has no address")
        val port = server.port
            ?: return rejectWithMessage(request.playerUuid, "server '${request.serverName}' has no port")

        // Only transfer once this proxy has been observed as not ready and a
        // different proxy is ready. Otherwise, the client could reconnect to this
        // proxy (or to another draining proxy) and gain nothing.
        if (request.proxyPodName.isNotBlank() && k8sServiceDiscovery.isProxyReady(request.proxyPodName)) {
            return rejectWithMessage(request.playerUuid, "proxy '${request.proxyPodName}' is still ready")
        }
        if (k8sServiceDiscovery.getReadyProxies().none { it != request.proxyPodName }) {
            return rejectWithMessage(request.playerUuid, "no replacement proxy is ready")
        }

        pendingTransfers.put(
            player,
            PendingTransfer(
                serverName = request.serverName,
                gameId = request.gameId,
                proxyPodName = request.proxyPodName,
            )
        )
        queueService.setDestination(player, request.gameId) // Reset the cache TTL

        logger.info(
            "Prepared transfer for $player from proxy '${request.proxyPodName}' " +
                    "to ${request.serverName} (${address}:${port}) / ${request.gameId}"
        )
        return Transfer.PrepareTransferResponse.newBuilder()
            .setAccepted(true)
            .build()
    }

    override suspend fun consumeTransfer(request: Transfer.ConsumeTransferRequest): Transfer.ConsumeTransferResponse {
        val player = runCatching { UUID.fromString(request.playerUuid) }.getOrNull()
            ?: return Transfer.ConsumeTransferResponse.getDefaultInstance()

        val pending = pendingTransfers.asMap().remove(player)
            ?: return Transfer.ConsumeTransferResponse.getDefaultInstance()

        val server = queueService.getServer(pending.serverName)
        if (server == null || !server.ready || server.draining || server.terminating) {
            logger.warn("Pending transfer for $player to '${pending.serverName}' is no longer available")
            return Transfer.ConsumeTransferResponse.getDefaultInstance()
        }
        val address = server.address
            ?: return Transfer.ConsumeTransferResponse.getDefaultInstance()
        val port = server.port
            ?: return Transfer.ConsumeTransferResponse.getDefaultInstance()

        queueService.setDestination(player, pending.gameId) // Reset the cache TTL

        logger.info(
            "Consumed transfer for $player to ${pending.serverName} " +
                    "($address:$port) / ${pending.gameId} (prepared by '${pending.proxyPodName}')"
        )
        return Transfer.ConsumeTransferResponse.newBuilder()
            .setFound(true)
            .setServerName(pending.serverName)
            .setGameServerIp(address)
            .setGameServerPort(port)
            .setGameId(pending.gameId)
            .build()
    }

    private fun rejectWithMessage(playerUuid: String, reason: String): Transfer.PrepareTransferResponse {
        logger.warn("Rejected proxy transfer for player $playerUuid: $reason")
        return Transfer.PrepareTransferResponse.newBuilder()
            .setAccepted(false)
            .setRejectionReason(reason)
            .build()
    }
}
