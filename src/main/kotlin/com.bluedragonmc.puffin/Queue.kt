package com.bluedragonmc.puffin

import com.bluedragonmc.messages.*
import com.bluedragonmc.messagingsystem.AMQPClient
import org.slf4j.LoggerFactory
import java.util.UUID

object Queue {

    private val logger = LoggerFactory.getLogger(Queue::class.java)
    private val queue = mutableMapOf<UUID, GameType>()
    private lateinit var client: AMQPClient
    internal var startingInstance: GameType? = null

    fun start(client: AMQPClient) {
        this.client = client
        client.subscribe(RequestAddToQueueMessage::class) { message ->
            logger.info("${message.player} added to queue for ${message.gameType}")
            queue[message.player] = message.gameType
            client.publish(SendChatMessage(message.player, "<green>You are now queued for ${message.gameType.name}."))
            update()
        }

        client.subscribe(RequestRemoveFromQueueMessage::class) { message ->
            logger.info("${message.player} removed from queue")
            queue.remove(message.player)
            client.publish(SendChatMessage(message.player, "<red>You have been removed from the queue."))
        }
    }

    fun update() {
        queue.entries.removeAll { (player, gameType) ->
            val instances = InstanceManager.findInstancesOfType(gameType, matchMapName = false, matchGameMode = false)
            logger.info("Found ${instances.size} instances matching the $player's requested game type: $gameType")
            if(instances.isNotEmpty()) {

                val (best, _) = instances.keys.associateWith {
                    GameStateManager.getEmptySlots(it)
                }.filter { it.value > 0 }.entries.minByOrNull { it.value } ?: run {
                    logger.info("All instances of type $gameType have no empty player slots.")
                    return@removeAll false
                }

                logger.info("Instance with least empty slots for $gameType: $best")
                if(GameStateManager.getEmptySlots(best) > 0) {
                    // Send the player to this instance and remove them from the queue
                    logger.info("Found instance for player $player: instanceId=$best, gameType=$gameType")
                    client.publish(SendPlayerToInstanceMessage(player, best))
                    return@removeAll true
                }
            }
            return@removeAll false
        }
        if(startingInstance != null) return
        queue.entries.firstOrNull()?.let { (player, gameType) ->
            logger.info("Starting a new instance for player $player because they could not find any instances running $gameType.")
            // Create a new instance for the first player in the queue.
            startingInstance = gameType
            val (container, instances) = InstanceManager.findContainerWithLeastInstances() ?: return@let
            logger.info("The container with the least instances is $container with ${instances.size} instances running.")
            client.publish(RequestCreateInstanceMessage(container, gameType))
            client.publish(SendChatMessage(player, "<aqua>Creating a new instance..."))
        }
    }
}