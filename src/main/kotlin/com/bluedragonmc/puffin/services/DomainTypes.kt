package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes
import com.bluedragonmc.api.grpc.CommonTypes.EnumGameState
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject
import java.util.*

/**
 * Connection info for a game server.
 */
interface GameServer {
    val address: String
    val name: String
    val port: Int?
}

/**
 * A [GameServer] backed by an Agones `GameServer` Kubernetes object.
 */
class AgonesGameServer(val `object`: DynamicKubernetesObject) : GameServer {
    private val status = `object`.raw.getAsJsonObject("status")

    override val address: String by lazy {
        return@lazy status.get("address").asString
    }
    override val port by lazy {
        if (status.has("ports") && status.get("ports").isJsonArray) status.get("ports").asJsonArray.first { p ->
            p.asJsonObject.get("name").asString == "minecraft"
        }.asJsonObject.get("port").asInt else null
    }
    override val name = `object`.metadata.name!!
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
    val draining: Boolean = false,
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
