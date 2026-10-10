package com.bluedragonmc.puffin.dashboard

import com.bluedragonmc.puffin.app.ApplicationScope
import com.bluedragonmc.puffin.app.Env
import com.bluedragonmc.puffin.services.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.fge.jsonpatch.diff.JsonDiff
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.inject.Inject
import com.google.inject.Singleton
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*

@Singleton
class ApiService @Inject constructor(
    private val databaseConnection: DatabaseConnection,
    private val playerTracker: IPlayerTracker,
    private val partyManager: IPartyManager,
    private val gameServerManager: IGameServerManager,
    private val queueService: IQueueService,
    private val applicationScope: ApplicationScope,
) : Service() {

    inner class SocketServer(addr: InetSocketAddress) : WebSocketServer(addr) {

        private val logger = LoggerFactory.getLogger(this::class.java)
        private val gson = Gson()

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            logger.info("Connection opened - ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            logger.info("Connection closed ($code / $reason) - ${conn.remoteSocketAddress}")
        }

        override fun onMessage(conn: WebSocket, message: String) {
            applicationScope.launch {
                val decoded = gson.fromJson(message, JsonObject::class.java)
                when (decoded.get("request").asString) {
                    "getGameServers" -> {
                        val gameServers = queueService.getServers().mapNotNull { gs ->
                            gameServerManager.getK8sObject(gs.name)?.let { createJsonObjectForGameServer(it) }
                        }
                        val arr = JsonArray().apply {
                            gameServers.forEach { add(it) }
                        }
                        val response = JsonObject().apply {
                            addProperty("type", "gameServers")
                            add("gameServers", arr)
                        }
                        conn.send(response.toString())
                    }

                    "getInstances" -> conn.send(JsonObject().apply {
                        addProperty("type", "instances")
                        add("instances", JsonArray().apply {
                            queueService.getGames().forEach { game ->
                                add(createJsonObjectForGame(game.id))
                            }
                        })
                    }.toString())

                    "getInstance" -> conn.send(
                        createJsonObjectForGame(decoded.get("instance").asString).toString()
                    )

                    "getParties" -> {
                        conn.send(JsonObject().apply {
                            addProperty("type", "parties")
                            add("parties", JsonArray().apply {
                                partyManager.getParties().forEach { party ->
                                    add(createJsonObjectForParty(party))
                                }
                            })
                        }.toString())
                    }

                    "getPlayers" -> {
                        conn.send(JsonObject().apply {
                            addProperty("type", "players")
                            add("players", JsonArray().apply {
                                playerTracker.getPlayers().forEach { (uuid, state) ->
                                    add(createJsonObjectForPlayer(uuid, state))
                                }
                            })
                        }.toString())
                    }
                }
            }
        }

        override fun onError(conn: WebSocket, ex: java.lang.Exception) {
            logger.error("Error handling WebSocket connection $conn")
            ex.printStackTrace()
        }

        override fun onStart() {
            logger.info("Web socket server started on $address")
        }
    }

    private val ws: WebSocketServer =
        SocketServer(InetSocketAddress(InetAddress.getByName("0.0.0.0"), Env.API_SERVICE_PORT))

    override fun start() {
        registerCallbacks()
        ws.start()
    }

    override fun close() {
        try {
            ws.stop()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun registerCallbacks() {
        playerTracker.registerInstanceChangeCallback { player, serverName, gameId ->
            applicationScope.launch {
                val state = playerTracker.getPlayer(player) ?: return@launch
                sendUpdate(
                    "player",
                    "update",
                    player.toString(),
                    createJsonObjectForPlayer(player, state)
                )
            }
        }

        playerTracker.registerLogoutCallback { uuid ->
            sendUpdate("player", "remove", uuid.toString(), null)
        }

        queueService.registerInstanceUpdateCallback { gameId ->
            applicationScope.launch {
                sendUpdate(
                    "instance", "update", gameId,
                    createJsonObjectForGame(gameId)
                )
            }
        }

        partyManager.registerPartyUpdateCallback { action, id, party ->
            sendUpdate("party", action, id, party?.let { createJsonObjectForParty(it) })
        }

        gameServerManager.registerGameServerListener { event ->
            when (event) {
                is GameServerEvent.Added ->
                    sendUpdate("gameServer", "add", event.server.name, createJsonObjectForGameServer(event.server))

                is GameServerEvent.Removed ->
                    sendUpdate("gameServer", "remove", event.name, null)

                is GameServerEvent.Merged ->
                    sendMerge(
                        "gameServer", "patch", event.new.name,
                        createJsonObjectForGameServer(event.old),
                        createJsonObjectForGameServer(event.new)
                    )

                is GameServerEvent.Updated ->
                    sendUpdate("gameServer", "patch", event.server.name, createJsonObjectForGameServer(event.server))

                is GameServerEvent.InstanceAdded ->
                    sendUpdate("instance", "add", event.gameId, createJsonObjectForGame(event.gameId))

                is GameServerEvent.InstanceRemoved ->
                    sendUpdate("instance", "remove", event.gameId, null)
            }
        }
    }

    private fun sendUpdate(resource: String, action: String, id: String, updated: JsonElement?) {
        val obj = JsonObject().apply {
            addProperty("type", "update")
            addProperty("action", action)
            addProperty("resource", resource)
            addProperty("id", id)
            add("updated", updated)
        }
        ws.broadcast(obj.toString())
    }

    private val gson = Gson()
    private val objectMapper = ObjectMapper()

    private fun sendMerge(resource: String, action: String, id: String, old: JsonObject, new: JsonObject) {
        val patch = JsonDiff.asJsonPatch(
            objectMapper.readTree(gson.toJson(old)),
            objectMapper.readTree(gson.toJson(new))
        )
        val str = objectMapper.writeValueAsString(patch)
        val json = gson.fromJson(str, JsonArray::class.java)
        sendUpdate(resource, action, id, json)
    }

    private suspend fun createJsonObjectForGameServer(gs: GameServer): JsonObject {
        return JsonObject().apply {
            if (gs is AgonesGameServer) {
                add("raw", gs.`object`.raw)
            }
            addProperty("name", gs.name)
            addProperty("address", gs.address)
            addProperty("port", gs.port)
            addProperty("draining", queueService.getServer(gs.name)?.draining ?: false)
            val instances = JsonArray().apply {
                queueService.getServer(gs.name)?.games?.forEach { game -> add(game.id) }
            }
            add("instances", instances)
        }
    }

    private suspend fun createJsonObjectForGame(gameId: String): JsonObject {
        val game = queueService.getGame(gameId)
        return JsonObject().apply {
            addProperty("type", "instance")
            addProperty("id", gameId)
            addProperty("emptySlots", game?.emptySlots)
            addProperty("gameServer", queueService.getServerOfGame(gameId))
            add("gameState", JsonObject().apply {
                addProperty("openSlots", game?.emptySlots)
                addProperty("maxSlots", game?.maxPlayers)
                addProperty("playerCount", playerTracker.getPlayersInInstance(gameId).size)
                addProperty("stateName", game?.state?.name)
            })
            val type = queueService.getGame(gameId)?.gameType
            add("gameType", JsonObject().apply {
                addProperty("name", type?.name)
                addProperty("mapName", type?.mapId)
                addProperty("mode", type?.mode)
            })
        }
    }

    private suspend fun createJsonObjectForPlayer(uuid: UUID, state: PlayerState): JsonObject {
        return JsonObject().apply {
            addProperty("uuid", uuid.toString())
            addProperty("username", databaseConnection.getPlayerName(uuid))
            addProperty("proxyPodName", state.proxyPodName)
            addProperty("gameServerName", state.gameServerName)
            addProperty("gameId", state.gameId)
        }
    }

    private fun createJsonObjectForParty(party: PartyManager.Party): JsonObject {
        return JsonObject().apply {
            addProperty("id", party.id)
            add("members", JsonArray().apply {
                party.getMembers().forEach { member -> add(member.toString()) }
            })
            addProperty("leader", party.leader.toString())
            add("invitations", JsonArray().apply { party.invitations.forEach { add(it.toString()) } })
            if (party.marathon != null) {
                add("marathon", JsonObject().apply {
                    add("points", JsonObject().apply {
                        party.marathon?.getPoints()?.forEach { (k, v) -> addProperty(k.toString(), v) }
                    })
                    addProperty("endsAt", party.marathon?.endsAt)
                })
            }
        }
    }
}