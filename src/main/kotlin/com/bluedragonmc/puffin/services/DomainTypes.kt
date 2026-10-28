package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes
import com.bluedragonmc.api.grpc.CommonTypes.EnumGameState
import com.google.gson.JsonObject
import io.kubernetes.client.openapi.models.V1Pod
import kotlinx.coroutines.Job
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Connection info for a game server.
 */
interface GameServer {
    val address: String
    val name: String
    val port: Int?

    /** Whether the pod is Ready and has received an IP address. */
    val ready: Boolean

    /** Whether the pod is scheduled to be deleted. */
    val terminating: Boolean
}

/**
 * A [GameServer] backed by a game server pod.
 */
class PodGameServer(
    val pod: V1Pod,
    override val port: Int?,
    val raw: JsonObject = JsonObject(),
) : GameServer {

    override val name = pod.metadata?.name ?: error("Game server pod has no name")

    override val address: String
        get() = pod.status?.podIP.orEmpty()

    override val ready: Boolean
        get() = pod.metadata?.deletionTimestamp == null &&
                pod.status?.podIP != null &&
                pod.status?.conditions?.any { it.type == "Ready" && it.status == "True" } == true

    override val terminating: Boolean
        get() = pod.metadata?.deletionTimestamp != null
}

/**
 * Where a player currently is: their proxy, game server, and instance.
 */
data class PlayerState(val proxyPodName: String?, val gameServerName: String?, val gameId: String?)

/**
 * A game server as tracked by the queue: its name, running games, and connection info.
 */
data class QueueServer(
    val name: String,
    val games: List<Game>,
    val address: String? = null,
    val port: Int? = null,
    val ready: Boolean = true,
    val draining: Boolean = false,
    val terminating: Boolean = false,
)

/**
 * A running instance of a game.
 */
data class Game(
    val id: String,
    val gameType: CommonTypes.GameType,
    val playerCount: Int,
    val maxPlayers: Int,
    val state: EnumGameState,
) {
    val emptySlots get() = maxPlayers - playerCount
}

/**
 * A party waiting in the queue.
 */
data class QueuedParty(
    val players: List<UUID>, val gameType: CommonTypes.GameType
) {
    var attempts = 0
}

/**
 * A party's mutable state.
 */
class Party(
    internal val members: MutableList<UUID>,
    internal val invitations: MutableMap<UUID, Job>,
    internal var leader: UUID,
    internal var marathon: Marathon? = null,
    val id: String = UUID.randomUUID().toString(),
)

/**
 * A marathon's mutable state.
 */
class Marathon(
    val endsAt: Long,
    internal val points: ConcurrentHashMap<UUID, Int>,
) {
    internal var cancelJob: Job? = null

    fun getPoints() = points.toMap()
}
