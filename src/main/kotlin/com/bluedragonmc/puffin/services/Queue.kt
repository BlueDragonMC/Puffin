package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes.GameType
import com.bluedragonmc.api.grpc.Queue
import com.bluedragonmc.api.grpc.Queue.GetDestinationRequest
import com.bluedragonmc.api.grpc.Queue.GetDestinationResponse
import com.bluedragonmc.api.grpc.QueueServiceGrpcKt
import com.bluedragonmc.puffin.app.Env.WORLDS_FOLDER
import com.bluedragonmc.puffin.util.Utils
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.protobuf.Empty
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths
import java.time.Duration
import java.util.*
import kotlin.io.path.exists

class Queue(app: ServiceHolder) : Service(app) {

    /**
     * Called when a player is added to the queue. If an instance matching their
     * desired game type is available, they will be sent to it immediately.
     */
    private fun sendPlayerToGame(player: UUID, gameType: GameType) {
        val party = app.get(PartyManager::class).partyOf(player)
        val partySize = party?.members?.size ?: 1
        val instances = app.get(GameManager::class).filterRunningGames(gameType)
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
     * Add a player to the Queue for the specified [gameType].
     */
    fun queuePlayer(player: UUID, gameType: GameType) {
        sendPlayerToGame(player, gameType)
    }

    private suspend fun send(player: UUID, gameId: String): Boolean {

        val gameStateManager = app.get(GameStateManager::class)
        val party = app.get(PartyManager::class).partyOf(player)

        val emptySlots = gameStateManager.getEmptySlots(gameId)
        val requiredSlots = party?.members?.size ?: 1

        if (emptySlots < requiredSlots) {
            logger.info("Attempted to send $requiredSlots player(s) to instance $gameId, but it only has $emptySlots empty slots.")
            return false
        }

        if (party != null && player == party.leader) {
            logger.info("Sending party of player $player (${party.members.size} members) to instance $gameId.")
            party.members.forEach { member ->
                // Warp in the player's party when they are warped into a game
                Utils.sendPlayerToInstance(member, gameId)
            }
        } else {
            logger.info("Sending player $player to instance $gameId.")
            Utils.sendPlayerToInstance(player, gameId)
        }

        return true
    }

    private val destinationCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(10))
        .build<UUID, String>()

    fun setDestination(player: UUID, gameId: String) {
        destinationCache.put(player, gameId)
    }

    inner class QueueService : QueueServiceGrpcKt.QueueServiceCoroutineImplBase() {

        private fun isValidMap(game: String, mapName: String): Boolean {
            return Paths.get(WORLDS_FOLDER).resolve(game).resolve(mapName).exists()
        }

        override suspend fun addToQueue(request: Queue.AddToQueueRequest): Empty =
            Utils.handleRPC {

                val uuid = UUID.fromString(request.playerUuid)

                if (request.gameType.hasMapName() && !isValidMap(request.gameType.name, request.gameType.mapName)) {
                    Utils.sendChat(
                        uuid,
                        "<red><lang:queue.adding.failed:'${request.gameType.name}':'<dark_gray><lang:queue.adding.failed.invalid_map>'>"
                    )
                    return Empty.getDefaultInstance()
                }

                val party = app.get(PartyManager::class).partyOf(uuid)
                if (party != null && uuid != party.leader) {
                    Utils.sendChat(uuid, "<red><lang:puffin.party.game_join_disallowed.not_leader>")
                    return Empty.getDefaultInstance()
                }

                queuePlayer(uuid, request.gameType)
                Utils.sendChat(uuid, "<p1><lang:queue.added.game:'${request.gameType.name}'>")

                return Empty.getDefaultInstance()
            }

        override suspend fun removeFromQueue(request: Queue.RemoveFromQueueRequest): Empty =
            Utils.handleRPC {
                return Empty.getDefaultInstance()
            }

        override suspend fun getDestinationGame(request: GetDestinationRequest): GetDestinationResponse = Utils.handleRPC {
            val player = UUID.fromString(request.playerUuid)
            val destination = destinationCache.getIfPresent(player)
            return if (destination != null) {
                destinationCache.invalidate(player)
                GetDestinationResponse.newBuilder()
                    .setGameId(destination)
                    .build()
            } else {
                GetDestinationResponse.getDefaultInstance()
            }
        }
    }
}