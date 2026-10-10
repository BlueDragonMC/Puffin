package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.GsClient
import com.bluedragonmc.api.grpc.GsClient.SendChatRequest.ChatType
import com.bluedragonmc.api.grpc.GsClientServiceGrpcKt
import com.bluedragonmc.api.grpc.PlayerHolderOuterClass
import com.bluedragonmc.api.grpc.sendChatRequest
import com.bluedragonmc.puffin.app.ApplicationScope
import com.google.inject.Inject
import com.google.inject.Singleton
import io.grpc.ManagedChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.*

interface IPlayerTracker {
    fun getPlayer(uuid: UUID): PlayerTracker.PlayerState?
    fun getPlayers(): Map<UUID, PlayerTracker.PlayerState>
    fun getPlayersInInstance(gameId: String): List<UUID>
    fun getPlayersOnProxy(podName: String): List<UUID>
    fun getPlayersInGameServer(serverName: String): List<UUID>
    fun removePlayer(uuid: UUID): PlayerTracker.PlayerState?
    fun setProxy(player: UUID, proxyPodName: String?)
    fun setServer(player: UUID, gameServerName: String?)
    suspend fun setGameId(player: UUID, gameId: String?)
    fun handleLogout(uuid: UUID): PlayerTracker.PlayerState?
    suspend fun handleInstanceChange(uuid: UUID, serverName: String, gameId: String)
    fun updateGameServerPlayers(serverName: String, response: PlayerHolderOuterClass.GetPlayersResponse)
    suspend fun updateGamePlayers(gameId: String, response: GsClient.GetInstancesResponse.RunningInstance)
    fun updateProxyPlayers(proxyPodName: String, response: PlayerHolderOuterClass.GetPlayersResponse)
    fun getPlayerCount(instanceIds: Collection<String>?): Int
    suspend fun cleanup(serverNames: Set<String>, gameIds: Set<String>)
    suspend fun getChannelToPlayer(player: UUID): ManagedChannel?
    suspend fun getStubToPlayer(player: UUID): GsClientServiceGrpcKt.GsClientServiceCoroutineStub?

    suspend fun sendChat(player: UUID, message: String, chatType: ChatType = ChatType.CHAT)
    fun sendChatAsync(player: UUID, message: String, chatType: ChatType = ChatType.CHAT): Job
    fun sendChatAsync(player: UUID, chatType: ChatType = ChatType.CHAT, message: suspend () -> String): Job

    suspend fun sendChat(players: Collection<UUID>, message: String, chatType: ChatType = ChatType.CHAT)
    fun sendChatAsync(players: Collection<UUID>, message: String, chatType: ChatType = ChatType.CHAT): Job
    fun sendChatAsync(players: Collection<UUID>, chatType: ChatType = ChatType.CHAT, message: suspend () -> String): Job

    fun registerInstanceChangeCallback(cb: (player: UUID, serverName: String, gameId: String) -> Unit)
    fun registerLogoutCallback(cb: (player: UUID) -> Unit)
    fun registerGameIdChangeCallback(cb: suspend (player: UUID) -> Unit)
}

/**
 * Maintains a map of players' UUIDs to their current games, servers, and proxies.
 */
@Singleton
class PlayerTracker @Inject constructor(
    val databaseConnection: DatabaseConnection,
    val k8sServiceDiscovery: IK8sServiceDiscovery,
    val applicationScope: ApplicationScope
) : Service(), IPlayerTracker {

    private val playersLock = Any()
    private val players = mutableMapOf<UUID, PlayerState>()

    /** Runs [block] while holding [playersLock]; the lock guards every access to [players]. */
    private inline fun <R> withPlayers(block: () -> R): R = synchronized(playersLock) { block() }

    data class PlayerState(val proxyPodName: String?, val gameServerName: String?, val gameId: String?)

    override fun getPlayer(uuid: UUID) = withPlayers { players[uuid] }

    override fun getPlayers() = withPlayers { players.toMap() }

    override fun getPlayersInInstance(gameId: String) = withPlayers {
        players.filter { (_, state) -> state.gameId == gameId }
            .map { it.key }
    }

    override fun getPlayersOnProxy(podName: String) = withPlayers {
        players.filter { (_, state) -> state.proxyPodName == podName }
            .map { it.key }
    }

    override fun getPlayersInGameServer(serverName: String) = withPlayers {
        players.filter { (_, state) -> state.gameServerName == serverName }
            .map { it.key }
    }

    override fun removePlayer(uuid: UUID) = withPlayers { players.remove(uuid) }

    override fun setProxy(player: UUID, proxyPodName: String?) = withPlayers {
        players[player] = players[player]?.copy(proxyPodName = proxyPodName) ?: PlayerState(
            proxyPodName = proxyPodName,
            null,
            null
        )
    }

    override fun setServer(player: UUID, gameServerName: String?) = withPlayers {
        players[player] = players[player]?.copy(gameServerName = gameServerName) ?: PlayerState(
            null,
            gameServerName = gameServerName,
            null
        )
    }

    override suspend fun setGameId(player: UUID, gameId: String?) {
        val changed = withPlayers {
            val old = players[player]?.gameId
            players[player] = players[player]?.copy(gameId = gameId) ?: PlayerState(
                null,
                null,
                gameId = gameId,
            )
            gameId != old && gameId != null
        }
        if (changed) {
            gameIdChangeCallbacks.forEach { it(player) }
        }
    }

    override fun close() = withPlayers { players.clear() }

    override fun updateGameServerPlayers(serverName: String, response: PlayerHolderOuterClass.GetPlayersResponse) {
        val existingPlayers = getPlayersInGameServer(serverName)
        val newPlayers = response.playersList.map { UUID.fromString(it.uuid) }
        for (player in existingPlayers) {
            if (player !in newPlayers) {
                setServer(player, null)
            }
        }
        for (player in newPlayers) {
            setServer(player, serverName)
        }
    }

    override suspend fun updateGamePlayers(gameId: String, response: GsClient.GetInstancesResponse.RunningInstance) {
        val existingPlayers = getPlayersInInstance(gameId)
        val newPlayers = response.playerUuidsList.map(UUID::fromString)
        for (player in existingPlayers) {
            if (player !in newPlayers) {
                setGameId(player, null)
            }
        }
        for (player in newPlayers) {
            setGameId(player, gameId)
        }
    }

    override fun updateProxyPlayers(proxyPodName: String, response: PlayerHolderOuterClass.GetPlayersResponse) {
        val existingPlayers = getPlayersOnProxy(proxyPodName)
        val newPlayers = response.playersList.map { UUID.fromString(it.uuid) }
        for (player in existingPlayers) {
            if (player !in newPlayers) {
                setServer(player, null)
                setProxy(player, null)
            }
        }
        response.playersList.forEach { player ->
            val uuid = UUID.fromString(player.uuid)
            setServer(uuid, player.serverName)
            setProxy(uuid, proxyPodName)
        }
    }

    override fun getPlayerCount(instanceIds: Collection<String>?): Int {
        return if (instanceIds == null) {
            withPlayers { players.size }
        } else {
            // Count the amount of players in any of the given instances
            withPlayers { players.entries.count { (_, state) -> state.gameId != null && instanceIds.contains(state.gameId) } }
        }
    }

    /**
     * Reconciles tracked player state against the currently known [serverNames] and [gameIds],
     * clearing references to servers, instances, or proxies that no longer exist.
     */
    override suspend fun cleanup(serverNames: Set<String>, gameIds: Set<String>) {
        withPlayers {
            players.entries.removeIf { (_, player) ->
                player.gameId == null && player.gameServerName == null && player.proxyPodName == null
            }
        }
        val proxies = k8sServiceDiscovery.getAllProxies()
        for ((player, state) in getPlayers()) {
            if (state.gameServerName !in serverNames) {
                setServer(player, null)
            }
            if (state.gameId !in gameIds) {
                setGameId(player, null)
            }
            if (state.proxyPodName !in proxies) {
                setProxy(player, null)
            }
        }
    }

    override suspend fun getChannelToPlayer(player: UUID): ManagedChannel? {
        val serverName = getPlayer(player)?.gameServerName ?: run {
            logger.warn("Failed to get server name of player $player (Can't get gRPC channel to the player's server)")
            return null
        }
        return k8sServiceDiscovery.getChannelToServer(serverName)
    }

    override suspend fun getStubToPlayer(player: UUID): GsClientServiceGrpcKt.GsClientServiceCoroutineStub? {
        return GsClientServiceGrpcKt.GsClientServiceCoroutineStub(
            getChannelToPlayer(player) ?: return null
        )
    }

    override suspend fun sendChat(player: UUID, message: String, chatType: ChatType) {
        logger.info("Sending chat message (type {}) to player {}: '{}'", chatType, player, message)
        val stub = getStubToPlayer(player)
        stub?.sendChat(sendChatRequest {
            this.playerUuid = player.toString()
            this.message = message
            this.chatType = chatType
        }) ?: run {
            logger.warn("Failed to send chat message '$message' to player '$player'.")
        }
    }

    override fun sendChatAsync(player: UUID, message: String, chatType: ChatType) = applicationScope.launch {
        sendChat(player, message, chatType)
    }

    override fun sendChatAsync(player: UUID, chatType: ChatType, message: suspend () -> String) =
        applicationScope.launch {
            sendChat(player, message(), chatType)
        }

    override suspend fun sendChat(players: Collection<UUID>, message: String, chatType: ChatType) {
        for (player in players) sendChat(player, message, chatType)
    }

    override fun sendChatAsync(players: Collection<UUID>, message: String, chatType: ChatType) =
        applicationScope.launch {
            sendChat(players, message, chatType)
        }

    override fun sendChatAsync(players: Collection<UUID>, chatType: ChatType, message: suspend () -> String) =
        applicationScope.launch {
            sendChat(players, message(), chatType)
        }

    override fun start() {
        k8sServiceDiscovery.registerProxyPlayerListener { podName, response ->
            updateProxyPlayers(podName, response)
        }
    }

    private val instanceChangeCallbacks = mutableListOf<(player: UUID, serverName: String, gameId: String) -> Unit>()

    override fun registerInstanceChangeCallback(cb: (player: UUID, serverName: String, gameId: String) -> Unit) {
        instanceChangeCallbacks.add(cb)
    }

    private val logoutCallbacks = mutableListOf<(player: UUID) -> Unit>()

    override fun registerLogoutCallback(cb: (player: UUID) -> Unit) {
        logoutCallbacks.add(cb)
    }

    private val gameIdChangeCallbacks = mutableListOf<suspend (player: UUID) -> Unit>()

    override fun registerGameIdChangeCallback(cb: suspend (player: UUID) -> Unit) {
        gameIdChangeCallbacks.add(cb)
    }

    override fun handleLogout(uuid: UUID): PlayerState? {
        val oldState = removePlayer(uuid)
        logoutCallbacks.forEach { it(uuid) }
        return oldState
    }

    override suspend fun handleInstanceChange(uuid: UUID, serverName: String, gameId: String) {
        setGameId(uuid, gameId)
        setServer(uuid, serverName)
        instanceChangeCallbacks.forEach { it(uuid, serverName, gameId) }
    }
}