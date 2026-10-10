package com.bluedragonmc.puffin.grpc

import com.bluedragonmc.api.grpc.GameStateServiceGrpcKt
import com.bluedragonmc.api.grpc.ServerTracking
import com.bluedragonmc.puffin.services.IQueueService
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty

/**
 * gRPC adapter for game state updates.
 */
@Singleton
class GameStateGrpcService @Inject constructor(
    private val queueService: IQueueService,
) : GameStateServiceGrpcKt.GameStateServiceCoroutineImplBase() {

    override suspend fun updateGameState(request: ServerTracking.GameStateUpdateRequest): Empty = handleRPC {
        queueService.setGameState(request.instanceUuid, request.gameState)
        return Empty.getDefaultInstance()
    }
}
