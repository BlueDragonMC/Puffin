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
 * Maintains lists that map each player to the game, server, and proxy they're currently in.
 */
class PlayerTracker(app: ServiceHolder) : Service(app) {

    /**
     * A map of player UUIDs to game IDs
     */
    private val playerInstances = mutableMapOf<UUID, String>()

    /**
     * A map of player UUIDs to server names.
     */
    private val playerServers = mutableMapOf<UUID, String>()

    /**
     * A map of player UUIDs to the k8s pod name of the proxy they're on.
     */
    private val playerProxies = mutableMapOf<UUID, String>()

    private val logoutActions = mutableListOf<Consumer<UUID>>()

    fun getPlayersInInstance(gameId: String) = playerInstances.filter { it.value == gameId }.map { it.key }
    fun getPlayerCountOfInstance(gameId: String) = playerInstances.count { it.value == gameId }
    fun getProxyOfPlayer(player: UUID) = playerProxies[player]
    fun getInstanceOfPlayer(uuid: UUID) = playerInstances[uuid]
    fun getServerOfPlayer(player: UUID) =
        playerServers[player] ?: playerInstances[player]?.let { app.get(GameManager::class).getGameServerOf(it) }

    override fun close() {
        playerInstances.clear()
    }

    fun onLogout(action: Consumer<UUID>) {
        logoutActions.add(action)
    }

    fun updatePlayers(response: PlayerHolderOuterClass.GetPlayersResponse) {
        response.playersList.forEach { player ->
            val uuid = UUID.fromString(player.uuid)
            playerServers[uuid] = player.serverName
        }
    }

    fun updatePlayers(proxyPodName: String, response: PlayerHolderOuterClass.GetPlayersResponse) {
        response.playersList.forEach { player ->
            val uuid = UUID.fromString(player.uuid)
            playerServers[uuid] = player.serverName
            playerProxies[uuid] = proxyPodName
        }
    }

    fun getPlayerCount(gameType: CommonTypes.GameType?): Int {
        return if (gameType == null) {
            playerInstances.size
        } else {
            // Get a list of matching instances
            val instances = app.get(GameManager::class).filterRunningGames(gameType).keys
            // Count the amount of players in any of these instances
            playerInstances.keys.count { playerUuid ->
                instances.contains(playerInstances[playerUuid])
            }
        }
    }

    inner class PlayerTrackerService : PlayerTrackerGrpcKt.PlayerTrackerCoroutineImplBase() {
        override suspend fun playerLogin(request: PlayerTrackerOuterClass.PlayerLoginRequest): Empty = handleRPC {
            // Called when a player logs into a proxy.
            logger.info("Login > ${request.username} (${request.uuid})")
            playerProxies[UUID.fromString(request.uuid)] = request.proxyPodName
            return Empty.getDefaultInstance()
        }

        override suspend fun playerLogout(request: PlayerTrackerOuterClass.PlayerLogoutRequest): Empty = handleRPC {
            // Called when a player logs out of or otherwise disconnects from a proxy.
            val uuid = UUID.fromString(request.uuid)
            logger.info("Logout > ${request.username} ($uuid)")
            logoutActions.forEach { it.accept(uuid) }

            if (playerInstances.remove(uuid) == null)
                logger.warn("Player logged out without a recorded instance: uuid=$uuid")

            if (playerProxies.remove(uuid) == null)
                logger.warn("Player logged out without a recorded proxy server: uuid=$uuid")

            if (playerServers.remove(uuid) == null)
                logger.warn("Player logged out without a recorded proxy server: uuid=$uuid")

            app.get(ApiService::class).sendUpdate("player", "logout", request.uuid, null)

            return Empty.getDefaultInstance()
        }

        override suspend fun playerInstanceChange(request: PlayerTrackerOuterClass.PlayerInstanceChangeRequest): Empty =
            handleRPC {
                // Called when a player changes instances on the same backend server.
                playerInstances[UUID.fromString(request.uuid)] = request.instanceId
                playerServers[UUID.fromString(request.uuid)] = request.serverName
                logger.info("Instance Change > Player ${request.uuid} switched to instance ${request.serverName}/${request.instanceId}")
                app.get(ApiService::class).sendUpdate("player", "transfer", request.uuid, JsonObject().apply {
                    addProperty("instance", request.instanceId)
                    addProperty("serverName", request.serverName)
                })
                return Empty.getDefaultInstance()
            }

        override suspend fun playerTransfer(request: PlayerTrackerOuterClass.PlayerTransferRequest): Empty = handleRPC {
            // Called when a player changes backend servers (including initial routing).
            playerInstances[UUID.fromString(request.uuid)] = request.newInstance
            playerServers[UUID.fromString(request.uuid)] = request.newServerName
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
                                isOnline = playerInstances.containsKey(it)
                            }
                        }
                    }

                    PlayerTrackerOuterClass.PlayerQueryRequest.IdentityCase.UUID -> {
                        val uuidIn = UUID.fromString(request.uuid)
                        return queryPlayerResponse {
                            uuid = request.uuid
                            isOnline = playerInstances.containsKey(uuidIn)
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