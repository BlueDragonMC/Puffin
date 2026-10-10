package com.bluedragonmc.puffin.grpc

import com.bluedragonmc.api.grpc.PlayerTrackerGrpcKt
import com.bluedragonmc.api.grpc.PlayerTrackerOuterClass
import com.bluedragonmc.api.grpc.queryPlayerResponse
import com.bluedragonmc.puffin.services.DatabaseConnection
import com.bluedragonmc.puffin.services.IPlayerTracker
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * gRPC adapter for player tracking.
 */
@Singleton
class PlayerTrackerGrpcService @Inject constructor(
    private val playerTracker: IPlayerTracker,
    private val databaseConnection: DatabaseConnection,
) : PlayerTrackerGrpcKt.PlayerTrackerCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun playerLogin(request: PlayerTrackerOuterClass.PlayerLoginRequest): Empty = handleRPC {
        // Called when a player logs into a proxy.
        logger.info("Login > ${request.username} (${request.uuid})")
        playerTracker.setProxy(UUID.fromString(request.uuid), request.proxyPodName)
        return Empty.getDefaultInstance()
    }

    override suspend fun playerLogout(request: PlayerTrackerOuterClass.PlayerLogoutRequest): Empty = handleRPC {
        // Called when a player logs out of or otherwise disconnects from a proxy.
        val uuid = UUID.fromString(request.uuid)
        val oldState = playerTracker.handleLogout(uuid)
        logger.info("Logout > ${request.username} $oldState")
        databaseConnection.evictCachesForPlayer(uuid)

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
            playerTracker.handleInstanceChange(uuid, request.serverName, request.instanceId)
            logger.info("Instance Change > Player ${request.uuid} switched to instance ${request.serverName}/${request.instanceId}")
            return Empty.getDefaultInstance()
        }

    override suspend fun playerTransfer(request: PlayerTrackerOuterClass.PlayerTransferRequest): Empty = handleRPC {
        // Called when a player changes backend servers (including initial routing).
        val uuid = UUID.fromString(request.uuid)
        playerTracker.handleInstanceChange(uuid, request.newServerName, request.newInstance)
        logger.info("Player Transfer > Player ${request.uuid} switched to instance ${request.newServerName}/${request.newInstance}")
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
                            isOnline = playerTracker.getPlayer(it) != null
                        }
                    }
                }

                PlayerTrackerOuterClass.PlayerQueryRequest.IdentityCase.UUID -> {
                    val uuidIn = UUID.fromString(request.uuid)
                    return queryPlayerResponse {
                        uuid = request.uuid
                        isOnline = playerTracker.getPlayer(uuidIn) != null
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
