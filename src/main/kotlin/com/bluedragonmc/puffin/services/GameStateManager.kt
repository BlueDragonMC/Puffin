package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes.GameState
import com.bluedragonmc.api.grpc.GameStateServiceGrpcKt
import com.bluedragonmc.api.grpc.ServerTracking
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.dashboard.ApiService
import com.google.protobuf.Empty
import java.util.*

class GameStateManager(app: Puffin) : Service(app) {

    private val emptyPlayerSlots = mutableMapOf<UUID, Int>()
    private val states = mutableMapOf<UUID, GameState>()

    fun hasState(instanceId: UUID) = emptyPlayerSlots.contains(instanceId)
    fun getEmptySlots(instanceId: UUID) = emptyPlayerSlots[instanceId] ?: 0

    fun setGameState(instanceId: UUID, state: GameState) {
        emptyPlayerSlots[instanceId] = if (state.joinable) state.openSlots else 0
        states[instanceId] = state
        app.get(ApiService::class).sendUpdate(
            "instance", "update", instanceId.toString(),
            app.get(ApiService::class).createJsonObjectForInstance(instanceId.toString())
        )
    }

    fun getState(instanceId: UUID) = states[instanceId]

    inner class GameStateService : GameStateServiceGrpcKt.GameStateServiceCoroutineImplBase() {
        override suspend fun updateGameState(request: ServerTracking.GameStateUpdateRequest): Empty {
            setGameState(UUID.fromString(request.instanceUuid), request.gameState)
            return Empty.getDefaultInstance()
        }
    }
}