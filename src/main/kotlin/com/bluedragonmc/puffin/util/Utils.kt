package com.bluedragonmc.puffin.util

import com.bluedragonmc.api.grpc.GsClient.SendChatRequest.ChatType
import com.bluedragonmc.api.grpc.GsClientServiceGrpcKt
import com.bluedragonmc.api.grpc.PlayerHolderGrpcKt
import com.bluedragonmc.api.grpc.sendChatRequest
import com.bluedragonmc.api.grpc.sendPlayerRequest
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.services.*
import com.github.benmanes.caffeine.cache.Caffeine
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*
import kotlin.concurrent.fixedRateTimer

object Utils {
    lateinit var app: ServiceHolder
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val channels = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(5))
        .expireAfterWrite(Duration.ofMinutes(10))
        .build<String, ManagedChannel>()

    fun getChannelToPlayer(player: UUID): ManagedChannel {
        logger.debug("Getting gRPC channel for player $player")
        val instance = app.get(PlayerTracker::class).getInstanceOfPlayer(player)!!
        val serverName = app.get(InstanceManager::class).getGameServerOf(instance)!!
        logger.debug("Player $player is in instance '$instance' on server '$serverName'.")
        return getChannelToServer(serverName)
    }

    fun getChannelToServer(serverName: String): ManagedChannel {
        logger.debug("Getting gRPC channel to game server with name: '$serverName'")
        val addr = app.get(K8sServiceDiscovery::class).getGameServerIP(serverName)
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

    fun getStubToServer(serverName: String): GsClientServiceGrpcKt.GsClientServiceCoroutineStub {
        return GsClientServiceGrpcKt.GsClientServiceCoroutineStub(
            getChannelToServer(serverName)
        )
    }

    fun getStubToPlayer(player: UUID): GsClientServiceGrpcKt.GsClientServiceCoroutineStub {
        return GsClientServiceGrpcKt.GsClientServiceCoroutineStub(
            getChannelToPlayer(player)
        )
    }

    suspend fun sendChat(player: UUID, message: String, chatType: ChatType = ChatType.CHAT) {
        logger.debug("Sending chat message (type $chatType) to player $player: '$message'")
        val stub = getStubToPlayer(player)
        stub.sendChat(sendChatRequest {
            this.playerUuid = player.toString()
            this.message = message
            this.chatType = chatType
        })
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
        val channel = getChannelToProxyOf(player) ?: run {
            logger.warn("Proxy address not found for player $player!")
            return
        }
        val im = app.get(InstanceManager::class)
        val servers = im.getGameServers()
        val serverName = im.getGameServerOf(instanceId)
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