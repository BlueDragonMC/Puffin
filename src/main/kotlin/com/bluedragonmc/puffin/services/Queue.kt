package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes.GameType
import com.bluedragonmc.api.grpc.GsClient
import com.bluedragonmc.api.grpc.QueueServiceGrpcKt
import com.bluedragonmc.api.grpc.createInstanceRequest
import com.bluedragonmc.puffin.config.ConfigService
import com.bluedragonmc.puffin.util.Utils
import com.google.protobuf.Empty
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.*
import java.util.concurrent.locks.ReentrantLock

class Queue(app: ServiceHolder) : Service(app) {

    private val queue = mutableMapOf<UUID, GameType>()
    private val queueEntranceTimes = mutableMapOf<UUID, Long>()

    fun onInstanceCreated(instanceId: UUID, gameType: GameType, players: List<UUID> = emptyList()) {
        // The amount of players that have already been sent to the instance.
        // Used to prevent overfilling.
        var sentPlayers = 0
        val openSlots = app.get(GameStateManager::class).getEmptySlots(instanceId)

        players.forEach { player ->
            val party = app.get(PartyManager::class).partyOf(player)
            val partySize = party?.members?.size ?: 1
            if (sentPlayers + partySize <= openSlots) {
                sentPlayers++
                runBlocking { send(player, instanceId) }
            }
        }

        // A list of players which have exited the queue.
        val toRemove = mutableListOf<UUID>()

        // Look through the queue and see if any players could join this newly-created instance immediately.
        for ((player, wants) in queue) {
            val party = app.get(PartyManager::class).partyOf(player)
            val partySize = party?.members?.size ?: 1
            if (matchGameType(wants, gameType) && sentPlayers + partySize < openSlots) {
                // This newly-created instance matches the game type that the player wants.
                runBlocking {
                    sentPlayers++
                    if (send(player, instanceId)) {
                        Utils.sendChatAsync(player, "<green>Done!", GsClient.SendChatRequest.ChatType.ACTION_BAR)
                    } else {
                        Utils.sendChat(player, "<red><lang:puffin.queue.not_enough_space:'<gray>$instanceId'>")
                    }
                    toRemove.add(player)
                }
            }
        }

        queue.keys.removeAll(toRemove.toSet())
    }

    @Volatile
    private var startingInstanceType: GameType? = null

    private val startingInstanceSubscribers = mutableListOf<UUID>()

    private val instanceCreationLock = ReentrantLock()

    /**
     * Called when a player is added to the queue. If an instance matching their
     * desired game type is available, they will be sent to it immediately.
     * If not, an instance will be created matching the game type, and they will
     * be sent to it along with other players that joined the queue after them.
     */
    private fun onPlayerAddedToQueue(player: UUID, gameType: GameType) {
        val partySize = app.get(PartyManager::class).partyOf(player)?.members?.size ?: 1
        val instances = app.get(InstanceManager::class).filterRunningInstances(gameType)
        for (instance in instances.keys) {
            val emptySlots = app.get(GameStateManager::class).getEmptySlots(instance)
            if (emptySlots >= partySize) {
                runBlocking {
                    send(player, instance)
                }
                return
            }
        }

        // If the player couldn't immediately be sent to a new instance,
        // check if a matching instance is currently being created.

        if (startingInstanceType != null && matchGameType(gameType, startingInstanceType!!)) {
            Utils.sendChatAsync(player, "<p2>Waiting for instance...", GsClient.SendChatRequest.ChatType.ACTION_BAR)
            startingInstanceSubscribers.add(player) // The list of subscribers will be sent to the instance when it is fully created.
            queue.remove(player)
            queueEntranceTimes.remove(player)
            return
        }

        // If the instance that's currently being created doesn't match, try
        // to create a new one using the requested game type.
        try {
            instanceCreationLock.lock()
            startingInstanceSubscribers.clear()
            startingInstanceType = gameType
            val (gameServer, _) = app.get(InstanceManager::class).findGameServerWithLeastInstances() ?: return

            Utils.sendChatAsync(player, "<p2>Creating instance...", GsClient.SendChatRequest.ChatType.ACTION_BAR)
            val response = runBlocking {
                Utils.getStubToServer(gameServer)?.createInstance(createInstanceRequest {
                    this.correlationId = UUID.randomUUID().toString()
                    this.gameType = gameType
                })
            }
            if (response?.success == true) {
                // The instance was created successfully. Update the game state and send all matching players to it.
                val uuid = UUID.fromString(response.instanceUuid)
                app.get(GameStateManager::class).setGameState(uuid, response.gameState)
                onInstanceCreated(uuid, gameType, startingInstanceSubscribers)
            } else {
                Utils.sendChatAsync(
                    player,
                    "<red>Failed to create instance! Please try again in a few minutes.",
                    GsClient.SendChatRequest.ChatType.ACTION_BAR
                )
            }
            startingInstanceSubscribers.clear()
            startingInstanceType = null

            // Make sure the player is removed from the queue
            queue.remove(player)
            queueEntranceTimes.remove(player)
        } finally {
            instanceCreationLock.unlock()
        }
    }

    /**
     * Matches the [first] game type against the [other] game type,
     * returning true if they match. The flags of the [first]
     * game type are considered when matching against the [other] game type.
     */
    fun matchGameType(first: GameType, other: GameType): Boolean {
        return (!first.selectorsList.contains(GameType.GameTypeFieldSelector.GAME_NAME) || first.name == other.name) &&
                (!first.selectorsList.contains(GameType.GameTypeFieldSelector.MAP_NAME) || first.mapName == other.mapName) &&
                (!first.selectorsList.contains(GameType.GameTypeFieldSelector.GAME_MODE) || first.mode == other.mode)
    }

    /**
     * Add a player to the Queue for the specified [gameType].
     */
    fun queuePlayer(player: UUID, gameType: GameType) {
        logger.info("$player added to queue for GameType(name=${gameType.name}, mapName=${gameType.mapName}, mode=${gameType.mode})")
        queue[player] = gameType
        queueEntranceTimes[player] = System.currentTimeMillis()
        onPlayerAddedToQueue(player, gameType)
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

    inner class QueueService : QueueServiceGrpcKt.QueueServiceCoroutineImplBase() {

        private fun isValidMap(game: String, mapName: String): Boolean {
            val folder = File(File(app.get(ConfigService::class).config.worldsFolder), game)

            return folder.exists() && folder.list()?.isNotEmpty() == true && app.get(DatabaseConnection::class)
                .getMapInfo(mapName) != null
        }

        override suspend fun addToQueue(request: com.bluedragonmc.api.grpc.Queue.AddToQueueRequest): Empty {

            val uuid = UUID.fromString(request.playerUuid)

            if (request.gameType.hasMapName() && !isValidMap(request.gameType.name, request.gameType.mapName)) {
                Utils.sendChat(
                    uuid,
                    "<red><lang:queue.adding.failed:'${request.gameType.name}':'<dark_gray><lang:queue.adding.failed.invalid_map>'>"
                )
                return Empty.getDefaultInstance()
            }

            queuePlayer(uuid, request.gameType)
            Utils.sendChat(uuid, "<p1><lang:queue.added.game:'${request.gameType.name}'>")

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