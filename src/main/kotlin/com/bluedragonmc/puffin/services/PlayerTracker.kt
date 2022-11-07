package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.PlayerTrackerGrpcKt
import com.bluedragonmc.api.grpc.PlayerTrackerOuterClass
import com.bluedragonmc.api.grpc.queryPlayerResponse
import com.google.protobuf.Empty
import kotlinx.coroutines.runBlocking
import java.util.*
import java.util.function.Consumer

class PlayerTracker(app: ServiceHolder) : Service(app) {

    /**
     * A map of player UUIDs to instance UUIDs
     */
    private val playerInstances = mutableMapOf<UUID, UUID>()

    /**
     * A map of player UUIDs to the k8s pod name of the proxy they're on.
     */
    private val playerProxies = mutableMapOf<UUID, String>()

    private val logoutActions = mutableListOf<Consumer<UUID>>()

    fun getPlayersInInstance(instanceId: UUID) = playerInstances.filter { it.value == instanceId }.map { it.key }
    fun getProxyOfPlayer(player: UUID) = playerProxies[player]
    fun getInstanceOfPlayer(uuid: UUID) = playerInstances[uuid]

    override fun close() {
        playerInstances.clear()
    }

    fun onLogout(action: Consumer<UUID>) {
        logoutActions.add(action)
    }

    inner class PlayerTrackerService : PlayerTrackerGrpcKt.PlayerTrackerCoroutineImplBase() {
        override suspend fun playerLogin(request: PlayerTrackerOuterClass.PlayerLoginRequest): Empty {
            // Called when a player logs into a proxy.
            logger.info("Login > ${request.username} (${request.uuid})")
            playerProxies[UUID.fromString(request.uuid)] = request.proxyPodName
            return Empty.getDefaultInstance()
        }

        override suspend fun playerLogout(request: PlayerTrackerOuterClass.PlayerLogoutRequest): Empty {
            // Called when a player logs out of or otherwise disconnects from a proxy.
            logger.info("Logout > ${request.username} (${request.uuid})")
            logoutActions.forEach { it.accept(UUID.fromString(request.uuid)) }

            if (playerInstances.remove(UUID.fromString(request.uuid)) == null)
                logger.warn("Player logged out without a recorded instance: uuid=${request.uuid}")

            if (playerProxies.remove(UUID.fromString(request.uuid)) == null)
                logger.warn("Player logged out without a recorded proxy server: uuid=${request.uuid}")

            return Empty.getDefaultInstance()
        }

        override suspend fun playerInstanceChange(request: PlayerTrackerOuterClass.PlayerInstanceChangeRequest): Empty {
            // Called when a player changes instances on the same backend server.
            playerInstances[UUID.fromString(request.uuid)] = UUID.fromString(request.instanceId)
            return Empty.getDefaultInstance()
        }

        override suspend fun playerTransfer(request: PlayerTrackerOuterClass.PlayerTransferRequest): Empty {
            // Called when a player changes backend servers (including initial routing).
            return Empty.getDefaultInstance()
        }

        override suspend fun queryPlayer(request: PlayerTrackerOuterClass.PlayerQueryRequest): PlayerTrackerOuterClass.QueryPlayerResponse {
            when(request.identityCase) {
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