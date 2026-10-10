package com.bluedragonmc.puffin.grpc

import com.bluedragonmc.api.grpc.InstanceServiceGrpcKt
import com.bluedragonmc.api.grpc.ServerTracking
import com.bluedragonmc.api.grpc.filterGameTypeOrNull
import com.bluedragonmc.api.grpc.playerCountResponse
import com.bluedragonmc.puffin.services.IGameServerManager
import com.bluedragonmc.puffin.services.IPlayerTracker
import com.bluedragonmc.puffin.services.IQueueService
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
import org.slf4j.LoggerFactory

/**
 * gRPC adapter for game server lifecycle and instance tracking.
 */
@Singleton
class InstanceGrpcService @Inject constructor(
    private val gameServerManager: IGameServerManager,
    private val queueService: IQueueService,
    private val playerTracker: IPlayerTracker,
) : InstanceServiceGrpcKt.InstanceServiceCoroutineImplBase() {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override suspend fun initGameServer(request: ServerTracking.InitGameServerRequest): Empty = handleRPC {
        // Called when a new game server starts up and sends a ping
        logger.info("New game server started and pinged: ${request.serverName}")
        queueService.addServer(request.serverName)
        return Empty.getDefaultInstance()
    }

    override suspend fun createInstance(request: ServerTracking.InstanceCreatedRequest): Empty = handleRPC {
        // Called when an instance is created on a game server
        gameServerManager.handleInstanceCreated(request)
        return Empty.getDefaultInstance()
    }

    override suspend fun removeInstance(request: ServerTracking.InstanceRemovedRequest): Empty = handleRPC {
        // Called when an instance is removed on a game server
        gameServerManager.handleInstanceRemoved(request)
        return Empty.getDefaultInstance()
    }

    override suspend fun getTotalPlayerCount(request: ServerTracking.PlayerCountRequest): ServerTracking.PlayerCountResponse =
        handleRPC {
            return playerCountResponse {
                val matchingInstanceIds = request.filterGameTypeOrNull
                    ?.let { gameType -> queueService.getGamesMatching(gameType).map { it.id } }
                totalPlayers = playerTracker.getPlayerCount(matchingInstanceIds)
            }
        }
}
