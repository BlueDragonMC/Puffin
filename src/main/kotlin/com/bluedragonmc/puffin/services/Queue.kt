package com.bluedragonmc.puffin.services

import com.bluedragonmc.messages.*
import com.bluedragonmc.puffin.config.ConfigService
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.*

class Queue(app: ServiceHolder) : Service(app) {

    private val queue = mutableMapOf<UUID, GameType>()
    private val queueEntranceTimes = mutableMapOf<UUID, Long>()

    private var startingInstanceTimer: Timer? = null
    internal var startingInstance: GameType? = null
        set(value) {
            field = value
            startingInstanceTimer?.cancel()
            if (value != null) {
                startingInstanceTimer =
                    catchingTimer("instance-creation-timeout", daemon = true, initialDelay = 10_000, period = Long.MAX_VALUE) {
                        this.cancel()
                        logger.warn("Instance of game type $field was not created within the timeout period of 10 seconds!")
                        field = null
                    }
            }
        }

    fun update() {

        val instanceManager = app.get(InstanceManager::class)
        val gameStateManager = app.get(GameStateManager::class)
        val client = app.get(MessagingService::class).client

        queue.entries.removeAll { (player, gameType) ->
            val instances = instanceManager.findInstancesOfType(gameType, matchMapName = false, matchGameMode = false)
            logger.info("Found ${instances.size} instances matching the $player's requested game type: $gameType")
            if (instances.isNotEmpty()) {

                val (best, _) = instances.keys.associateWith {
                    gameStateManager.getEmptySlots(it)
                }.filter { it.value > 0 }.entries.minByOrNull { it.value } ?: run {
                    logger.info("All instances of type $gameType have no empty player slots.")
                    return@removeAll false
                }

                logger.info("Instance with least empty slots for $gameType: $best")
                if (gameStateManager.getEmptySlots(best) > 0) {
                    // Send the player to this instance and remove them from the queue
                    logger.info("Found instance for player $player: instanceId=$best, gameType=$gameType")
                    client.publish(SendPlayerToInstanceMessage(player, best))
                    queueEntranceTimes.remove(player)
                    return@removeAll true
                }
            }
            return@removeAll false
        }
        if (startingInstance != null) return
        queue.entries.firstOrNull()?.let { (player, gameType) ->
            logger.info("Starting a new instance for player $player because they could not find any instances running $gameType.")
            // Create a new instance for the first player in the queue.
            startingInstance = gameType
            val (gameServer, instances) = instanceManager.findGameServerWithLeastInstances() ?: return@let
            logger.info("The GameServer with the least instances is $gameServer with ${instances.size} instances running.")
            client.publish(RequestCreateInstanceMessage(gameServer, gameType))
            Utils.sendChat(player, "<aqua>Creating a new instance...", ChatType.ACTION_BAR)
        }
    }

    override fun initialize() {

        val client = app.get(MessagingService::class).client
        val db = app.get(DatabaseConnection::class)
        val config = app.get(ConfigService::class).config

        client.subscribe(RequestAddToQueueMessage::class) { message ->
            runBlocking {
                val mapName = message.gameType.mapName
                val gameSpecificMapFolder = File(File(config.worldsFolder), message.gameType.name)
                if ((mapName != null && db.getMapInfo(mapName) == null) || // No entry for the map in the database
                    (mapName == null && (!gameSpecificMapFolder.exists() || gameSpecificMapFolder.list()
                        ?.isNotEmpty() == false)) // No world folder found
                ) {
                    Utils.sendChat(message.player,
                        "<red>You couldn't be added to the queue for ${message.gameType.name}. <dark_gray>(Invalid map name)")
                } else {
                    logger.info("${message.player} added to queue for ${message.gameType}")
                    queue[message.player] = message.gameType
                    queueEntranceTimes[message.player] = System.currentTimeMillis()
                    Utils.sendChat(message.player, "<green>You are now queued for ${message.gameType.name}.")
                    update()
                }
            }
        }

        client.subscribe(RequestRemoveFromQueueMessage::class) { message ->
            logger.info("${message.player} removed from queue")
            queue.remove(message.player)
            queueEntranceTimes.remove(message.player)
            Utils.sendChat(message.player, "<red>You have been removed from the queue.")
        }

        catchingTimer("queue-update", daemon = true, initialDelay = 10_000, period = 5_000) {
            // Manually update the queue every 5 seconds in case of a messaging failure or unexpected delay
            update()

            // Remove players from the queue if they've been in it for a long time
            queueEntranceTimes.entries.removeAll { (uuid, time) ->
                if (System.currentTimeMillis() - time > 30_000) {
                    Utils.sendChat(uuid, "<red>You have been removed from the queue! <dark_gray>(Queue timeout)")
                    queue.remove(uuid)
                    return@removeAll true
                }
                return@removeAll false
            }
        }
    }

    override fun close() {}
}