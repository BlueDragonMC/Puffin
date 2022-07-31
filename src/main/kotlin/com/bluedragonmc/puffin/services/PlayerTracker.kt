package com.bluedragonmc.puffin.services

import com.bluedragonmc.messages.SendPlayerToInstanceMessage
import java.util.UUID

class PlayerTracker(app: ServiceHolder) : Service(app) {

    private val playerLocations = mutableMapOf<UUID, UUID>()

    override fun initialize() {
        val client = app.get(MessagingService::class).client

        client.subscribe(SendPlayerToInstanceMessage::class) { message ->
            playerLocations[message.player] = message.instance
        }
    }

    fun getPlayersInInstance(instanceId: UUID) = playerLocations.filter { it.value == instanceId }.map { it.key }
    fun getInstanceOfPlayer(uuid: UUID) = playerLocations[uuid]

    override fun close() {
        playerLocations.clear()
    }
}