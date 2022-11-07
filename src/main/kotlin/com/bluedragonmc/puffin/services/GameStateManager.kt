package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.GameStateServiceGrpcKt
import com.bluedragonmc.api.grpc.ServerTracking
import com.bluedragonmc.puffin.app.Puffin
import com.google.protobuf.Empty
import java.util.*

class GameStateManager(app: Puffin) : Service(app) {

    private val emptyPlayerSlots = mutableMapOf<UUID, Int>()

    fun hasState(instanceId: UUID) = emptyPlayerSlots.contains(instanceId)
    fun getEmptySlots(instanceId: UUID) = emptyPlayerSlots[instanceId] ?: 0

    inner class GameStateService : GameStateServiceGrpcKt.GameStateServiceCoroutineImplBase() {
        override suspend fun updateGameState(request: ServerTracking.GameStateUpdateRequest): Empty {

            val queue = app.get(Queue::class)
            emptyPlayerSlots[UUID.fromString(request.instanceUuid)] = request.gameState.openSlots
            queue.update()

            return Empty.getDefaultInstance()
        }
    }
}