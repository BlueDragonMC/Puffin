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
import java.time.Duration
import java.util.*
import kotlin.concurrent.fixedRateTimer

object Utils {
    lateinit var app: ServiceHolder

    private val channels = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(5))
        .expireAfterWrite(Duration.ofMinutes(10))
        .build<String, ManagedChannel>()

    fun getChannelToPlayer(player: UUID): ManagedChannel {
        val instance = app.get(PlayerTracker::class).getInstanceOfPlayer(player)!!
        val serverName = app.get(InstanceManager::class).getGameServerOf(instance)!!
        return getChannelToServer(serverName)
    }

    fun getChannelToServer(serverName: String): ManagedChannel {
        val addr = app.get(InstanceManager::class).getGameServers().firstOrNull {
            it.name == serverName
        }?.address
        return channels.get(addr) { addr ->
            ManagedChannelBuilder.forAddress(addr, 50051).usePlaintext().build()
        }
    }

    fun getChannelToProxyOf(player: UUID): ManagedChannel? =
        app.get(ProxyServiceDiscovery::class).getProxyIP(player)?.let {
            ManagedChannelBuilder.forAddress(it, 50051).usePlaintext().build()
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
        val channel = getChannelToProxyOf(player) ?: return
        val server = app.get(InstanceManager::class).getGameServerOf(instanceId)
        val stub = PlayerHolderGrpcKt.PlayerHolderCoroutineStub(channel)
        stub.sendPlayer(sendPlayerRequest {
            playerUuid = player.toString()
            serverName = server!!
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