package com.bluedragonmc.puffin.services

import com.bluedragonmc.messages.GameStateUpdateMessage
import com.bluedragonmc.puffin.app.Puffin
import java.util.UUID

class GameStateManager(app: Puffin) : Service(app) {

    private val emptyPlayerSlots = mutableMapOf<UUID, Int>()

    fun hasState(instanceId: UUID) = emptyPlayerSlots.contains(instanceId)
    fun getEmptySlots(instanceId: UUID) = emptyPlayerSlots[instanceId] ?: 0

    override fun initialize() {
        val client = app.get(MessagingService::class).client
        val queue = app.get(Queue::class)

        client.subscribe(GameStateUpdateMessage::class) { message ->
            emptyPlayerSlots[message.instanceId] = message.emptyPlayerSlots
            queue.update()
        }
    }

    override fun close() { }
}