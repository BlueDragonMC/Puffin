package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes.GameState
import com.bluedragonmc.api.grpc.GameStateServiceGrpcKt
import com.bluedragonmc.api.grpc.ServerTracking
import com.bluedragonmc.puffin.app.Puffin
import com.google.protobuf.Empty
import java.util.*

class GameStateManager(app: Puffin) : Service(app) {

    private val emptyPlayerSlots = mutableMapOf<UUID, Int>()

    fun hasState(instanceId: UUID) = emptyPlayerSlots.contains(instanceId)
    fun getEmptySlots(instanceId: UUID) = emptyPlayerSlots[instanceId] ?: 0
    fun setGameState(instanceId: UUID, state: GameState) {
        emptyPlayerSlots[instanceId] = if (state.joinable) state.openSlots else 0
    }

    inner class GameStateService : GameStateServiceGrpcKt.GameStateServiceCoroutineImplBase() {
        override suspend fun updateGameState(request: ServerTracking.GameStateUpdateRequest): Empty {
            emptyPlayerSlots[UUID.fromString(request.instanceUuid)] =
                if (request.gameState.joinable) request.gameState.openSlots else 0
            return Empty.getDefaultInstance()
        }
    }
}