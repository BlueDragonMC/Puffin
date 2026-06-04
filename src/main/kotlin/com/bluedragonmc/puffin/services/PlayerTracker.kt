package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.*
import com.bluedragonmc.api.grpc.GsClient.SendChatRequest.ChatType
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
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
    fun setGameId(player: UUID, gameId: String?)
    fun updateGameServerPlayers(serverName: String, response: PlayerHolderOuterClass.GetPlayersResponse)
    fun updateGamePlayers(gameId: String, response: GsClient.GetInstancesResponse.RunningInstance)
    fun updateProxyPlayers(proxyPodName: String, response: PlayerHolderOuterClass.GetPlayersResponse)
    fun getPlayerCount(gameType: CommonTypes.GameType?): Int
    fun getChannelToPlayer(player: UUID): ManagedChannel?
    fun getStubToPlayer(player: UUID): GsClientServiceGrpcKt.GsClientServiceCoroutineStub?

    suspend fun sendChat(player: UUID, message: String, chatType: ChatType = ChatType.CHAT)
    fun sendChatAsync(player: UUID, message: String, chatType: ChatType = ChatType.CHAT): Job

    suspend fun sendChat(players: Collection<UUID>, message: String, chatType: ChatType = ChatType.CHAT)
    fun sendChatAsync(players: Collection<UUID>, message: String, chatType: ChatType = ChatType.CHAT): Job
    fun registerInstanceChangeCallback(cb: (player: UUID, serverName: String, gameId: String) -> Unit)
    fun registerLogoutCallback(cb: (player: UUID) -> Unit)
    val playerTrackerService: PlayerTracker.PlayerTrackerService
}

/**
 * Maintains a map of players' UUIDs to their current games, servers, and proxies.
 */
@Singleton
class PlayerTracker @Inject constructor(
    val databaseConnection: DatabaseConnection,
    val queueService: IQueueService,
    val k8sServiceDiscovery: IK8sServiceDiscovery,
    val partyManager: IPartyManager
) : Service(), IPlayerTracker {

    private val players = mutableMapOf<UUID, PlayerState>()

    data class PlayerState(val proxyPodName: String?, val gameServerName: String?, val gameId: String?)

    override fun getPlayer(uuid: UUID) = players[uuid]

    override fun getPlayers() = players.toMap()

    override fun getPlayersInInstance(gameId: String) = players
        .filter { (_, state) -> state.gameId == gameId }
        .map { it.key }

    override fun getPlayersOnProxy(podName: String) = players
            .filter { (_, state) -> state.proxyPodName == podName }
            .map { it.key }

    override fun getPlayersInGameServer(serverName: String) = players
        .filter { (_, state) -> state.gameServerName == serverName }
        .map { it.key }

    override fun removePlayer(uuid: UUID) = players.remove(uuid)

    override fun setProxy(player: UUID, proxyPodName: String?) {
        players[player] = players[player]?.copy(proxyPodName = proxyPodName) ?: PlayerState(
            proxyPodName = proxyPodName,
            null,
            null
        )
    }

    override fun setServer(player: UUID, gameServerName: String?) {
        players[player] = players[player]?.copy(gameServerName = gameServerName) ?: PlayerState(
            null,
            gameServerName = gameServerName,
            null
        )
    }

    override fun setGameId(player: UUID, gameId: String?) {
        val old = players[player]?.gameId
        players[player] = players[player]?.copy(gameId = gameId) ?: PlayerState(
            null,
            null,
            gameId = gameId,
        )
        if (gameId != old) {
            queueService.removeFromQueue(player)
        }
    }

    override fun close() {
        players.clear()
    }

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

    override fun updateGamePlayers(gameId: String, response: GsClient.GetInstancesResponse.RunningInstance) {
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

    override fun getPlayerCount(gameType: CommonTypes.GameType?): Int {
        return if (gameType == null) {
            players.size
        } else {
            // Get a list of matching instances
            val instances = queueService.getGamesMatching(gameType).map { it.id }
            // Count the amount of players in any of these instances
            players.entries.count { (_, state) -> state.gameId != null && instances.contains(state.gameId) }
        }
    }

    fun cleanup() {
        players.entries.removeIf { (_, player) ->
            player.gameId == null && player.gameServerName == null && player.proxyPodName == null
        }
        val servers = queueService.getServers()
        val proxies = k8sServiceDiscovery.getAllProxies()
        for ((player, state) in players.entries) {
            if (servers.none { it.name == state.gameServerName }) {
                setServer(player, null)
            }
            if (servers.none { it.games.any { game -> game.id == state.gameId } }) {
                setGameId(player, null)
            }
            if (state.proxyPodName !in proxies) {
                setProxy(player, null)
            }
        }
    }

    override fun getChannelToPlayer(player: UUID): ManagedChannel? {
        val serverName = getPlayer(player)?.gameServerName ?: run {
            logger.warn("Failed to get server name of player $player (Can't get gRPC channel to the player's server)")
            return null
        }
        return k8sServiceDiscovery.getChannelToServer(serverName)
    }

    override fun getStubToPlayer(player: UUID): GsClientServiceGrpcKt.GsClientServiceCoroutineStub? {
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

    override fun sendChatAsync(player: UUID, message: String, chatType: ChatType) = Puffin.IO.launch {
        sendChat(player, message, chatType)
    }

    override suspend fun sendChat(players: Collection<UUID>, message: String, chatType: ChatType) {
        for (player in players) sendChat(player, message, chatType)
    }

    override fun sendChatAsync(players: Collection<UUID>, message: String, chatType: ChatType) =
        Puffin.IO.launch {
            sendChat(players, message, chatType)
        }

    init {
        Utils.catchingTimer("PlayerTracker cleanup", true, 10_000.toLong(), 10_000.toLong()) { cleanup() }
    }

    private val instanceChangeCallbacks = mutableListOf<(player: UUID, serverName: String, gameId: String) -> Unit>()

    override fun registerInstanceChangeCallback(cb: (player: UUID, serverName: String, gameId: String) -> Unit) {
        instanceChangeCallbacks.add(cb)
    }

    private val logoutCallbacks = mutableListOf<(player: UUID) -> Unit>()

    override fun registerLogoutCallback(cb: (player: UUID) -> Unit) {
        logoutCallbacks.add(cb)
    }

    override val playerTrackerService by lazy { PlayerTrackerService() }

    inner class PlayerTrackerService : PlayerTrackerGrpcKt.PlayerTrackerCoroutineImplBase() {
        override suspend fun playerLogin(request: PlayerTrackerOuterClass.PlayerLoginRequest): Empty = handleRPC {
            // Called when a player logs into a proxy.
            logger.info("Login > ${request.username} (${request.uuid})")
            setProxy(UUID.fromString(request.uuid), request.proxyPodName)
            return Empty.getDefaultInstance()
        }

        override suspend fun playerLogout(request: PlayerTrackerOuterClass.PlayerLogoutRequest): Empty = handleRPC {
            // Called when a player logs out of or otherwise disconnects from a proxy.
            val uuid = UUID.fromString(request.uuid)
            val oldState = players.remove(uuid)
            logger.info("Logout > ${request.username} $oldState")
            logoutCallbacks.forEach { it(uuid) }
            databaseConnection.evictCachesForPlayer(uuid)
            partyManager.onLogout(uuid)
            queueService.removeFromQueue(uuid)

            if (oldState?.gameId == null)
                logger.warn("Player logged out without a recorded instance: uuid=$uuid")

            if (oldState?.proxyPodName == null)
                logger.warn("Player logged out without a recorded proxy server: uuid=$uuid")

            if (oldState?.gameServerName == null)
                logger.warn("Player logged out without a recorded game server: uuid=$uuid")


            return Empty.getDefaultInstance()
        }

        override suspend fun playerInstanceChange(request: PlayerTrackerOuterClass.PlayerInstanceChangeRequest): Empty =
            handleRPC {
                // Called when a player changes instances on the same backend server.
                val uuid = UUID.fromString(request.uuid)
                setGameId(uuid, request.instanceId)
                setServer(uuid, request.serverName)
                logger.info("Instance Change > Player ${request.uuid} switched to instance ${request.serverName}/${request.instanceId}")
                instanceChangeCallbacks.forEach { it(uuid, request.serverName, request.instanceId) }
                return Empty.getDefaultInstance()
            }

        override suspend fun playerTransfer(request: PlayerTrackerOuterClass.PlayerTransferRequest): Empty = handleRPC {
            // Called when a player changes backend servers (including initial routing).
            val uuid = UUID.fromString(request.uuid)
            setGameId(uuid, request.newInstance)
            setServer(uuid, request.newServerName)
            logger.info("Player Transfer > Player ${request.uuid} switched to instance ${request.newServerName}/${request.newInstance}")
            instanceChangeCallbacks.forEach { it(uuid, request.newServerName, request.newInstance) }
            return Empty.getDefaultInstance()
        }

        override suspend fun queryPlayer(request: PlayerTrackerOuterClass.PlayerQueryRequest): PlayerTrackerOuterClass.QueryPlayerResponse =
            handleRPC {
                when (request.identityCase) {
                    PlayerTrackerOuterClass.PlayerQueryRequest.IdentityCase.USERNAME -> {
                        return queryPlayerResponse {
                            username = request.username
                            val foundUuid = databaseConnection.getPlayerUUID(username)
                            foundUuid?.let {
                                uuid = it.toString()
                                isOnline = players.containsKey(it)
                            }
                        }
                    }

                    PlayerTrackerOuterClass.PlayerQueryRequest.IdentityCase.UUID -> {
                        val uuidIn = UUID.fromString(request.uuid)
                        return queryPlayerResponse {
                            uuid = request.uuid
                            isOnline = players.containsKey(uuidIn)
                            val foundUsername = databaseConnection.getPlayerName(uuidIn)
                            foundUsername?.let {
                                username = it
                            }
                        }
                    }

                    PlayerTrackerOuterClass.PlayerQueryRequest.IdentityCase.IDENTITY_NOT_SET -> error("No identity given!")
                    null -> error("No identity given!")
                }
            }
    }
}