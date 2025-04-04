package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.*
import com.bluedragonmc.puffin.dashboard.ApiService
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.gson.JsonObject
import com.google.protobuf.Empty
import java.util.*
import java.util.function.Consumer

/**
 * Maintains a map of players' UUIDs to their current games, servers, and proxies.
 */
class PlayerTracker(app: ServiceHolder) : Service(app) {

    private val players = mutableMapOf<UUID, PlayerState>()

    data class PlayerState(val proxyPodName: String?, val gameServerName: String?, val gameId: String?)

    private val logoutActions = mutableListOf<Consumer<UUID>>()

    fun getPlayer(uuid: UUID) = players[uuid]

    fun getPlayers() = players.toMap()

    fun getPlayersInInstance(gameId: String) = players
        .filter { (_, state) -> state.gameId == gameId }
        .map { it.key }

    fun getPlayersOnProxy(podName: String) = players
            .filter { (_, state) -> state.proxyPodName == podName }
            .map { it.key }

    fun getPlayersInGameServer(serverName: String) = players
        .filter { (_, state) -> state.gameServerName == serverName }
        .map { it.key }

    fun removePlayer(uuid: UUID) = players.remove(uuid)

    fun setProxy(player: UUID, proxyPodName: String?) {
        players[player] = players[player]?.copy(proxyPodName = proxyPodName) ?: PlayerState(
            proxyPodName = proxyPodName,
            null,
            null
        )
    }

    fun setServer(player: UUID, gameServerName: String?) {
        players[player] = players[player]?.copy(gameServerName = gameServerName) ?: PlayerState(
            null,
            gameServerName = gameServerName,
            null
        )
    }

    fun setGameId(player: UUID, gameId: String?) {
        players[player] = players[player]?.copy(gameId = gameId) ?: PlayerState(
            null,
            null,
            gameId = gameId,
        )
    }

    override fun close() {
        players.clear()
    }

    fun onLogout(action: Consumer<UUID>) {
        logoutActions.add(action)
    }

    fun updateGameServerPlayers(serverName: String, response: PlayerHolderOuterClass.GetPlayersResponse) {
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

    fun updateGamePlayers(gameId: String, response: GsClient.GetInstancesResponse.RunningInstance) {
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

    fun updateProxyPlayers(proxyPodName: String, response: PlayerHolderOuterClass.GetPlayersResponse) {
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

    fun getPlayerCount(gameType: CommonTypes.GameType?): Int {
        return if (gameType == null) {
            players.size
        } else {
            // Get a list of matching instances
            val instances = app.get(GameManager::class).filterRunningGames(gameType).keys
            // Count the amount of players in any of these instances
            players.entries.count { (_, state) -> state.gameId != null && instances.contains(state.gameId) }
        }
    }

    fun cleanup() {
        players.entries.removeIf { (_, player) ->
            player.gameId == null && player.gameServerName == null && player.proxyPodName == null
        }
        val gm = app.get(GameManager::class)
        val gs = gm.getGameServers().map { it.name }
        val games = gm.getAllGames()
        val proxies = app.get(K8sServiceDiscovery::class).getAllProxies()
        for ((player, state) in players.entries) {
            if (state.gameServerName !in gs) {
                setServer(player, null)
            }
            if (state.gameId !in games) {
                setGameId(player, null)
            }
            if (state.proxyPodName !in proxies) {
                setProxy(player, null)
            }
        }
    }

    override fun initialize() {
        Utils.catchingTimer("PlayerTracker cleanup", true, 10_000.toLong(), 10_000.toLong()) { cleanup() }
    }

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
            logoutActions.forEach { it.accept(uuid) }

            if (oldState?.gameId == null)
                logger.warn("Player logged out without a recorded instance: uuid=$uuid")

            if (oldState?.proxyPodName == null)
                logger.warn("Player logged out without a recorded proxy server: uuid=$uuid")

            if (oldState?.gameServerName == null)
                logger.warn("Player logged out without a recorded game server: uuid=$uuid")

            app.get(ApiService::class).sendUpdate("player", "remove", request.uuid, null)

            return Empty.getDefaultInstance()
        }

        override suspend fun playerInstanceChange(request: PlayerTrackerOuterClass.PlayerInstanceChangeRequest): Empty =
            handleRPC {
                // Called when a player changes instances on the same backend server.
                val uuid = UUID.fromString(request.uuid)
                setGameId(uuid, request.instanceId)
                setServer(uuid, request.serverName)
                logger.info("Instance Change > Player ${request.uuid} switched to instance ${request.serverName}/${request.instanceId}")
                app.get(ApiService::class).apply {
                    sendUpdate("player", "update", request.uuid, createJsonObjectForPlayer(uuid, getPlayer(uuid) ?: return@apply))
                }
                return Empty.getDefaultInstance()
            }

        override suspend fun playerTransfer(request: PlayerTrackerOuterClass.PlayerTransferRequest): Empty = handleRPC {
            // Called when a player changes backend servers (including initial routing).
            val uuid = UUID.fromString(request.uuid)
            setGameId(uuid, request.newInstance)
            setServer(uuid, request.newServerName)
            logger.info("Player Transfer > Player ${request.uuid} switched to instance ${request.newServerName}/${request.newInstance}")
            app.get(ApiService::class).apply {
                sendUpdate("player", "update", request.uuid, createJsonObjectForPlayer(uuid, getPlayer(uuid) ?: return@apply))
            }
            return Empty.getDefaultInstance()
        }

        override suspend fun queryPlayer(request: PlayerTrackerOuterClass.PlayerQueryRequest): PlayerTrackerOuterClass.QueryPlayerResponse =
            handleRPC {
                when (request.identityCase) {
                    PlayerTrackerOuterClass.PlayerQueryRequest.IdentityCase.USERNAME -> {
                        return queryPlayerResponse {
                            username = request.username
                            val foundUuid = app.get(DatabaseConnection::class).getPlayerUUID(username)
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
                            val foundUsername = app.get(DatabaseConnection::class).getPlayerName(uuidIn)
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