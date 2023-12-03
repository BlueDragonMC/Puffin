package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.*
import com.bluedragonmc.puffin.dashboard.ApiService
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.gson.JsonObject
import com.google.protobuf.Empty
import kotlinx.coroutines.runBlocking
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
    fun getPlayersInInstance(gameId: String) = players.filter { (_, state) -> state.gameId == gameId }.map { it.key }
    fun getPlayersOnProxy(podName: String) =
        players.filter { (_, state) -> state.proxyPodName == podName }.map { it.key }
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

    fun updatePlayers(response: PlayerHolderOuterClass.GetPlayersResponse) {
        response.playersList.forEach { player ->
            val uuid = UUID.fromString(player.uuid)
            setServer(uuid, player.serverName)
        }
    }

    fun updatePlayers(proxyPodName: String, response: PlayerHolderOuterClass.GetPlayersResponse) {
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

            app.get(ApiService::class).sendUpdate("player", "logout", request.uuid, null)

            return Empty.getDefaultInstance()
        }

        override suspend fun playerInstanceChange(request: PlayerTrackerOuterClass.PlayerInstanceChangeRequest): Empty =
            handleRPC {
                // Called when a player changes instances on the same backend server.
                val uuid = UUID.fromString(request.uuid)
                setGameId(uuid, request.instanceId)
                setServer(uuid, request.serverName)
                logger.info("Instance Change > Player ${request.uuid} switched to instance ${request.serverName}/${request.instanceId}")
                app.get(ApiService::class).sendUpdate("player", "transfer", request.uuid, JsonObject().apply {
                    addProperty("instance", request.instanceId)
                    addProperty("serverName", request.serverName)
                })
                return Empty.getDefaultInstance()
            }

        override suspend fun playerTransfer(request: PlayerTrackerOuterClass.PlayerTransferRequest): Empty = handleRPC {
            // Called when a player changes backend servers (including initial routing).
            val uuid = UUID.fromString(request.uuid)
            setGameId(uuid, request.newInstance)
            setServer(uuid, request.newServerName)
            logger.info("Player Transfer > Player ${request.uuid} switched to instance ${request.newServerName}/${request.newInstance}")
            app.get(ApiService::class).sendUpdate("player", "transfer", request.uuid, JsonObject().apply {
                addProperty("instance", request.newInstance)
                addProperty("serverName", request.newServerName)
            })
            return Empty.getDefaultInstance()
        }

        override suspend fun queryPlayer(request: PlayerTrackerOuterClass.PlayerQueryRequest): PlayerTrackerOuterClass.QueryPlayerResponse =
            handleRPC {
                when (request.identityCase) {
                    PlayerTrackerOuterClass.PlayerQueryRequest.IdentityCase.USERNAME -> {
                        return queryPlayerResponse {
                            username = request.username
                            val foundUuid = runBlocking { app.get(DatabaseConnection::class).getPlayerUUID(username) }
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
                            val foundUsername = runBlocking { app.get(DatabaseConnection::class).getPlayerName(uuidIn) }
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