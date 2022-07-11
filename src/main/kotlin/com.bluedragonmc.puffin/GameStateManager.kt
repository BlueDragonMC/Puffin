package com.bluedragonmc.puffin

import com.bluedragonmc.messages.GameStateUpdateMessage
import com.bluedragonmc.messagingsystem.AMQPClient
import java.util.UUID

object GameStateManager {

    private val emptyPlayerSlots = mutableMapOf<UUID, Int>()

    fun start(client: AMQPClient) {
        client.subscribe(GameStateUpdateMessage::class) { message ->
            emptyPlayerSlots[message.instanceId] = message.emptyPlayerSlots
            Queue.update()
        }
    }

    fun getEmptySlots(instanceId: UUID) = emptyPlayerSlots[instanceId] ?: 0
}