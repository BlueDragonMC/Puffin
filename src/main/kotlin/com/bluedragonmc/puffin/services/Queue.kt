package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes
import com.bluedragonmc.api.grpc.GsClient
import com.bluedragonmc.api.grpc.QueueServiceGrpcKt
import com.bluedragonmc.api.grpc.createInstanceRequest
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.config.ConfigService
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import com.google.protobuf.Empty
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.*

class Queue(app: ServiceHolder) : Service(app) {

    private val queue = mutableMapOf<UUID, CommonTypes.GameType>()
    private val queueEntranceTimes = mutableMapOf<UUID, Long>()

    fun update() {

        val gameStateManager = app.get(GameStateManager::class)

        queue.entries.removeAll { (player, gameType) ->
            val instances = app.get(InstanceManager::class).filterRunningInstances(gameType)
            if (instances.isNotEmpty()) {
                logger.info("Found ${instances.size} instances of game type: GameType(name=${gameType.name}, mapName=${gameType.mapName}, mode=${gameType.mode})")

                val party = app.get(PartyManager::class).partyOf(player)
                val playerSlotsRequired = party?.members?.size ?: 1
                val (best, _) = instances.keys.associateWith {
                    gameStateManager.getEmptySlots(it)
                }.filter { it.value > 0 }.entries.minByOrNull { it.value } ?: run {
                    logger.info("All instances of type GameType(name=${gameType.name}, mapName=${gameType.mapName}, mode=${gameType.mode}) have less than $playerSlotsRequired player slots.")
                    return@removeAll false
                }

                logger.info("Instance with least empty slots for GameType(name=${gameType.name}, mapName=${gameType.mapName}, mode=${gameType.mode}): $best")
                if (party != null && party.leader != player) {
                    // Player is queued as a party member, not a leader
                    queueEntranceTimes.remove(player)
                    return@removeAll true
                }
                if (gameStateManager.getEmptySlots(best) >= playerSlotsRequired) {
                    // There is enough space in the instance for this player and their party (if they're in one)
                    // Send the player to this instance and remove them from the queue
                    logger.info("Sending player $player to existing instance of type GameType(name=${gameType.name}, mapName=${gameType.mapName}, mode=${gameType.mode}): $best")
                    queueEntranceTimes.remove(player)
                    Puffin.IO.launch {
                        send(player, best)
                    }
                    return@removeAll true
                }
            } else logger.info("No instances found of type GameType(name=${gameType.name}, mapName=${gameType.mapName}, mode=${gameType.mode}).")
            return@removeAll false
        }
        queue.entries.firstOrNull()?.let { (player, gameType) ->
            logger.info("Starting a new instance for player $player with game type: GameType(name=${gameType.name}, mapName=${gameType.mapName}, mode=${gameType.mode})")
            // Create a new instance for the first player in the queue.
            // Remove them from the queue immediately to prevent them from being sent to an existing game before being sent to the new instance.
            queue.remove(player)
            queueEntranceTimes.remove(player)

            val (gameServer, _) = app.get(InstanceManager::class).findGameServerWithLeastInstances() ?: return

            Utils.sendChatAsync(player, "<p2>Creating instance...", GsClient.SendChatRequest.ChatType.ACTION_BAR)
            runBlocking {
                val startTime = System.currentTimeMillis()
                val response = Utils.getStubToServer(gameServer)
                    ?.createInstance(createInstanceRequest {
                        this.correlationId = UUID.randomUUID().toString()
                        this.gameType = gameType
                    })
                if (response?.success == true) {
                    val totalTime = System.currentTimeMillis() - startTime
                    if (!send(player, UUID.fromString(response.instanceUuid))) {
                        // Created instance successfully, but failed to send the player. It was likely full.
                        Utils.sendChat(
                            player,
                            "<red><lang:puffin.queue.not_enough_space:'<gray>${response.instanceUuid}'>"
                        )
                    } else {
                        // Success!
                        Utils.sendChatAsync(player, "<p1>Done! (${totalTime}ms)", GsClient.SendChatRequest.ChatType.ACTION_BAR)
                    }
                } else {
                    // Instance creation failed.
                    Utils.sendChatAsync(
                        player,
                        "<red>Failed to create instance! Please try again in a few minutes.",
                        GsClient.SendChatRequest.ChatType.ACTION_BAR
                    )
                }
                // Make sure the player is removed from the queue
                queue.remove(player)
                queueEntranceTimes.remove(player)
            }
        }
    }

    private suspend fun send(player: UUID, instanceId: UUID): Boolean {

        val gameStateManager = app.get(GameStateManager::class)
        val party = app.get(PartyManager::class).partyOf(player)

        val emptySlots = gameStateManager.getEmptySlots(instanceId)
        val requiredSlots = party?.members?.size ?: 1

        if (emptySlots < requiredSlots) {
            logger.info("Attempted to send $requiredSlots player(s) to instance $instanceId, but it only has $emptySlots empty slots.")
            return false
        }

        if (party != null) {
            logger.info("Sending party of player $player (${party.members.size} members) to instance $instanceId.")
            party.members.forEach { member ->
                // Warp in the player's party when they are warped into a game
                Utils.sendPlayerToInstance(member, instanceId)
            }
        } else {
            logger.info("Sending player $player to instance $instanceId.")
            Utils.sendPlayerToInstance(player, instanceId)
        }

        return true
    }

    override fun initialize() {

        catchingTimer("queue-update", daemon = true, initialDelay = 10_000, period = 5_000) {
            // Manually update the queue every 5 seconds in case of a messaging failure or unexpected delay
            update()

            // Remove players from the queue if they've been in it for a long time
            queueEntranceTimes.entries.removeAll { (uuid, time) ->
                if (System.currentTimeMillis() - time > 30_000) {
                    Utils.sendChatAsync(
                        uuid,
                        "<red><lang:queue.removed.reason:'<dark_gray><lang:queue.removed.reason.timeout>'>"
                    )
                    queue.remove(uuid)
                    return@removeAll true
                }
                return@removeAll false
            }
        }
    }

    override fun close() {}

    inner class QueueService : QueueServiceGrpcKt.QueueServiceCoroutineImplBase() {
        override suspend fun addToQueue(request: com.bluedragonmc.api.grpc.Queue.AddToQueueRequest): Empty {

            val db = app.get(DatabaseConnection::class)
            val config = app.get(ConfigService::class).config

            val mapName = request.gameType.mapName
            val uuid = UUID.fromString(request.playerUuid)
            val gameSpecificMapFolder = File(File(config.worldsFolder), request.gameType.name)

            if (request.gameType.hasMapName()) {
                if (db.getMapInfo(mapName) == null || !gameSpecificMapFolder.exists() || gameSpecificMapFolder.list()
                        ?.isEmpty() == true
                ) {
                    Utils.sendChat(
                        uuid,
                        "<red><lang:queue.adding.failed:'${request.gameType.name}':'<dark_gray><lang:queue.adding.failed.invalid_map>'>"
                    )

                    return Empty.getDefaultInstance()
                }
            }

            logger.info("${request.playerUuid} added to queue for GameType(name=${request.gameType.name}, mapName=${request.gameType.mapName}, mode=${request.gameType.mode})")
            queue[uuid] = request.gameType
            queueEntranceTimes[uuid] = System.currentTimeMillis()
            Utils.sendChat(uuid, "<p1><lang:queue.added.game:'${request.gameType.name}'>")
            update()

            return Empty.getDefaultInstance()
        }

        override suspend fun removeFromQueue(request: com.bluedragonmc.api.grpc.Queue.RemoveFromQueueRequest): Empty {
            val uuid = UUID.fromString(request.playerUuid)
            queueEntranceTimes.remove(uuid)
            if (queue.remove(uuid) != null) {
                logger.info("$uuid was removed from the queue.")
                Utils.sendChat(uuid, "<red><lang:queue.removed>")
            }

            return Empty.getDefaultInstance()
        }
    }
}