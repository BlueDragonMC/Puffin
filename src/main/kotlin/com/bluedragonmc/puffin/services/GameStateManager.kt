package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes.GameState
import com.bluedragonmc.api.grpc.GameStateServiceGrpcKt
import com.bluedragonmc.api.grpc.ServerTracking
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.dashboard.ApiService
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.protobuf.Empty

/**
 * Receives messages from game servers to update the states of the servers' games.
 * Stores the states in a map for other services to access.
 */
class GameStateManager(app: Puffin) : Service(app) {

    private val states = mutableMapOf<String, GameState>()

    fun getEmptySlots(gameId: String) = states[gameId]?.let {
        if (it.joinable) it.openSlots else 0
    } ?: 0

    fun setGameState(gameId: String, state: GameState) {
        val oldState = states[gameId]
        states[gameId] = state
        if (oldState != state && oldState != null) {
            app.get(ApiService::class).sendUpdate(
                "instance", "update", gameId,
                app.get(ApiService::class).createJsonObjectForGame(gameId)
            )
        }
    }

    fun getState(gameId: String) = states[gameId]

    fun clearGameState(gameId: String) {
        // Called when a game is removed
        states.remove(gameId)
    }

    inner class GameStateService : GameStateServiceGrpcKt.GameStateServiceCoroutineImplBase() {
        override suspend fun updateGameState(request: ServerTracking.GameStateUpdateRequest): Empty = handleRPC {
            setGameState(request.instanceUuid, request.gameState)
            return Empty.getDefaultInstance()
        }
    }
}