package com.bluedragonmc.puffin

import com.bluedragonmc.messages.*
import com.bluedragonmc.messagingsystem.AMQPClient
import java.util.UUID

object Queue {

    private val queue = mutableMapOf<UUID, GameType>()
    private lateinit var client: AMQPClient
    internal var startingInstance: GameType? = null

    fun start(client: AMQPClient) {
        this.client = client
        client.subscribe(RequestAddToQueueMessage::class) { message ->
            queue[message.player] = message.gameType
            client.publish(SendChatMessage(message.player, "<green>You are now queued for ${message.gameType.name}."))
            update()
        }

        client.subscribe(RequestRemoveFromQueueMessage::class) { message ->
            queue.remove(message.player)
            client.publish(SendChatMessage(message.player, "<red>You have been removed from the queue."))
        }
    }

    fun update() {
        queue.entries.removeAll { (player, gameType) ->
            val instances = InstanceManager.findInstancesOfType(gameType, matchGameMode = true)
            if(instances.isNotEmpty()) {
                val (best, _) = instances.minBy { GameStateManager.getEmptySlots(it.key) }
                if(GameStateManager.getEmptySlots(best) > 0) {
                    // Send the player to this instance and remove them from the queue
                    client.publish(SendPlayerToInstanceMessage(player, best))
                    return@removeAll true
                }
            }
            return@removeAll false
        }
        if(startingInstance != null) return
        queue.entries.firstOrNull()?.let { (player, gameType) ->
            // Create a new instance for the first player in the queue.
            startingInstance = gameType
            val container = InstanceManager.findContainerWithLeastInstances()?.key ?: return@let
            client.publish(RequestCreateInstanceMessage(container, gameType))
            client.publish(SendChatMessage(player, "<aqua>Creating a new instance..."))
        }
    }
}