package com.bluedragonmc.puffin.grpc

import com.bluedragonmc.api.grpc.Queue
import com.bluedragonmc.api.grpc.Queue.GetDestinationRequest
import com.bluedragonmc.api.grpc.Queue.GetDestinationResponse
import com.bluedragonmc.api.grpc.QueueServiceGrpcKt
import com.bluedragonmc.puffin.app.Env
import com.bluedragonmc.puffin.services.IPartyManager
import com.bluedragonmc.puffin.services.IPlayerTracker
import com.bluedragonmc.puffin.services.IQueueService
import com.bluedragonmc.puffin.services.QueuedParty
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
import java.util.UUID

/**
 * gRPC adapter for the queue.
 */
@Singleton
class QueueGrpcService @Inject constructor(
    private val queueService: IQueueService,
    private val partyManager: IPartyManager,
    private val playerTracker: IPlayerTracker,
) : QueueServiceGrpcKt.QueueServiceCoroutineImplBase() {

    override suspend fun addToQueue(request: Queue.AddToQueueRequest): Empty = handleRPC {
        val playerUuid = UUID.fromString(request.playerUuid)
        val party = partyManager.partyOf(playerUuid)
        val isLobby = request.gameType.name == Env.LOBBY_GAME_NAME
        if (party != null && party.leader != playerUuid && !isLobby) {
            playerTracker.sendChat(playerUuid, "<red><lang:puffin.party.game_join_disallowed.not_leader>")
            return@handleRPC Empty.getDefaultInstance()
        }

        val queuedPlayers = if (party != null && party.leader != playerUuid) {
            // Non-leader party members may only queue themselves (e.g. to go to the lobby)
            listOf(playerUuid)
        } else {
            party?.getMembers() ?: listOf(playerUuid)
        }
        queueService.addToQueue(QueuedParty(queuedPlayers, request.gameType))

        return Empty.getDefaultInstance()
    }

    override suspend fun bulkAddToQueue(request: Queue.BulkAddToQueueRequest): Empty {
        for (request in request.requestsList) {
            val uuid = UUID.fromString(request.playerUuid)
            val party = partyManager.partyOf(uuid)
            if (party == null || party.leader == uuid || request.gameType.name == Env.LOBBY_GAME_NAME) {
                addToQueue(request)
            }
        }
        return Empty.getDefaultInstance()
    }

    override suspend fun getDestinationGame(request: GetDestinationRequest): GetDestinationResponse = handleRPC {
        val player = UUID.fromString(request.playerUuid)
        val destination = queueService.consumeDestination(player)
        return if (destination != null) {
            GetDestinationResponse.newBuilder()
                .setGameId(destination)
                .build()
        } else {
            GetDestinationResponse.getDefaultInstance()
        }
    }

    override suspend fun removeFromQueue(request: Queue.RemoveFromQueueRequest): Empty = handleRPC {
        val playerUuid = UUID.fromString(request.playerUuid)
        val party = partyManager.partyOf(playerUuid)
        if (party != null && party.leader != playerUuid) {
            playerTracker.sendChat(playerUuid, "<red><lang:puffin.party.game_join_disallowed.not_leader>")
            return@handleRPC Empty.getDefaultInstance()
        }

        queueService.removeFromQueue(playerUuid)
        return Empty.getDefaultInstance()
    }
}
