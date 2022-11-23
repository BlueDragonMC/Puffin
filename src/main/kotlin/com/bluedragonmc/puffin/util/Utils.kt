package com.bluedragonmc.puffin.util

import com.bluedragonmc.api.grpc.GsClient.SendChatRequest.ChatType
import com.bluedragonmc.api.grpc.GsClientServiceGrpcKt
import com.bluedragonmc.api.grpc.PlayerHolderGrpcKt
import com.bluedragonmc.api.grpc.sendChatRequest
import com.bluedragonmc.api.grpc.sendPlayerRequest
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.services.*
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.fixedRateTimer

object Utils {
    lateinit var app: ServiceHolder

    private val channels: Cache<String, ManagedChannel> = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(5))
        .expireAfterWrite(Duration.ofMinutes(10))
        .evictionListener { addr: String?, channel: ManagedChannel?, _ ->
            // Shut down all channels when they are removed from the cache for any reason.
            if (channel != null && !channel.isShutdown) {
                channel.shutdown()
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("Failed to shutdown gRPC channel to address $addr within 5 seconds!")
                }
            }
        }
        .build()

    private val logger = LoggerFactory.getLogger(this::class.java)

    fun getChannelToPlayer(player: UUID): ManagedChannel? {
        val serverName = app.get(PlayerTracker::class).getServerOfPlayer(player) ?: run {
            logger.warn("Failed to get server name of player $player (Can't get gRPC channel to the player's server)")
            return null
        }
        return getChannelToServer(serverName)
    }

    fun getChannelToServer(serverName: String): ManagedChannel? {
        logger.debug("Getting gRPC channel to game server with name: '$serverName'")
        val addr = app.get(K8sServiceDiscovery::class).getGameServerIP(serverName) ?: run {
            logger.warn("Failed to get server address for game server '$serverName' (Can't get gRPC channel to the server)")
            return null
        }
        return channels.get(addr) { addr ->
            logger.debug("Building managed channel with address '$addr' and port '50051'.")
            ManagedChannelBuilder.forAddress(addr, 50051).usePlaintext().build()
        }
    }

    fun getChannelToProxyOf(player: UUID): ManagedChannel? =
        app.get(K8sServiceDiscovery::class).getProxyIP(player)?.let {
            return channels.get(it) { addr ->
                logger.debug("Building managed channel with address '$addr' and port '50051'.")
                ManagedChannelBuilder.forAddress(addr, 50051).usePlaintext().build()
            }
        }

    fun getStubToServer(serverName: String): GsClientServiceGrpcKt.GsClientServiceCoroutineStub? {
        return GsClientServiceGrpcKt.GsClientServiceCoroutineStub(
            getChannelToServer(serverName) ?: return null
        )
    }

    fun getStubToPlayer(player: UUID): GsClientServiceGrpcKt.GsClientServiceCoroutineStub? {
        return GsClientServiceGrpcKt.GsClientServiceCoroutineStub(
            getChannelToPlayer(player) ?: return null
        )
    }

    fun cleanupChannelsForServer(serverName: String) {
        val addr = try {
            app.get(K8sServiceDiscovery::class).getGameServerIP(serverName)
        } catch (e: Throwable) {
            return // If the server address isn't cached, there likely isn't an established channel anyway
        }
        addr?.let {
            val channel = channels.getIfPresent(it)
            channel?.shutdown()
            channels.invalidate(it)
        }
    }

    suspend fun sendChat(player: UUID, message: String, chatType: ChatType = ChatType.CHAT) {
        logger.debug("Sending chat message (type $chatType) to player $player: '$message'")
        val stub = getStubToPlayer(player)
        stub?.sendChat(sendChatRequest {
            this.playerUuid = player.toString()
            this.message = message
            this.chatType = chatType
        }) ?: run {
            logger.warn("Failed to send chat message '$message' to player '$player'.")
        }
    }

    fun sendChatBlocking(player: UUID, message: String, chatType: ChatType = ChatType.CHAT) = runBlocking {
        sendChat(player, message, chatType)
    }

    fun sendChatAsync(player: UUID, message: String, chatType: ChatType = ChatType.CHAT) = Puffin.IO.launch {
        sendChat(player, message, chatType)
    }

    suspend fun sendChat(players: Collection<UUID>, message: String, chatType: ChatType = ChatType.CHAT) {
        for (player in players) sendChat(player, message, chatType)
    }

    fun sendChatBlocking(players: Collection<UUID>, message: String, chatType: ChatType = ChatType.CHAT) = runBlocking {
        for (player in players) sendChat(player, message, chatType)
    }

    fun sendChatAsync(players: Collection<UUID>, message: String, chatType: ChatType = ChatType.CHAT) =
        Puffin.IO.launch {
            sendChat(players, message, chatType)
        }

    suspend fun sendPlayerToInstance(player: UUID, instanceId: UUID) {
        val currentGameServer = app.get(PlayerTracker::class).getServerOfPlayer(player)

        val im = app.get(InstanceManager::class)
        val servers = im.getGameServers()
        val serverName = im.getGameServerOf(instanceId)

        val channel = if (currentGameServer != serverName) {
            getChannelToProxyOf(player) // Send to the proxy if we're routing the player between game servers
        } else {
            getChannelToPlayer(player) // Send directly to the game server if we're routing the player between instances on the same server
        }?: run {
            logger.warn("Failed to initialize the correct channel to send player $player from $currentGameServer to $serverName/$instanceId!")
            return
        }

        val gameServerObj = servers.find { it.name == serverName } ?: run {
            logger.warn("No IP/Port was found for server name $serverName! Sending players to this server may not be possible.")
            return
        }
        if (gameServerObj.port == null) {
            logger.warn("Game server with name $serverName was found, but it has no port! Sending players to this server may not be possible.")
            return
        }
        val stub = PlayerHolderGrpcKt.PlayerHolderCoroutineStub(channel)
        stub.sendPlayer(sendPlayerRequest {
            this.playerUuid = player.toString()
            this.serverName = serverName!!
            this.gameServerIp = gameServerObj.address
            this.gameServerPort = gameServerObj.port!!
            this.instanceId = instanceId.toString()
        })
    }

    inline fun catchingTimer(
        name: String? = null,
        daemon: Boolean = false,
        initialDelay: Long = 0.toLong(),
        period: Long,
        crossinline action: TimerTask.() -> Unit,
    ) =
        fixedRateTimer(name, daemon, initialDelay, period) {
            try {
                action()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

    class UtilsService(app: ServiceHolder) : Service(app) {
        override fun initialize() {
            Utils.app = app
        }

        override fun close() {}

    }
}