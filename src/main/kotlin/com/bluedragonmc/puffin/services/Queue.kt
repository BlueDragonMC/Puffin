package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes.GameType
import com.bluedragonmc.api.grpc.QueueServiceGrpcKt
import com.bluedragonmc.puffin.config.ConfigService
import com.bluedragonmc.puffin.util.Utils
import com.google.protobuf.Empty
import kotlinx.coroutines.runBlocking
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

class Queue(app: ServiceHolder) : Service(app) {

    /**
     * Called when a player is added to the queue. If an instance matching their
     * desired game type is available, they will be sent to it immediately.
     */
    private fun sendPlayerToGame(player: UUID, gameType: GameType) {
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

        Utils.sendChatAsync(player, "<red>No instances were found for you to join! Please try again in a few minutes.")
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
        sendPlayerToGame(player, gameType)
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
            val folder = app.get(ConfigService::class).getWorldsFolder().resolve(game)

            return folder.exists() &&
                    folder.listDirectoryEntries().isNotEmpty() &&
                    app.get(DatabaseConnection::class).getMapInfo(mapName) != null
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
            return Empty.getDefaultInstance()
        }
    }
}