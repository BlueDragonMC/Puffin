package com.bluedragonmc.puffin.dashboard

import com.bluedragonmc.api.grpc.CommonTypes.GameType
import com.bluedragonmc.puffin.services.*
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tananaev.jsonpatch.JsonPatchFactory
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*

class ApiService(app: ServiceHolder) : Service(app) {

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
            val decoded = gson.fromJson(message, JsonObject::class.java)
            when (decoded.get("request").asString) {
                "getGameServers" -> {
                    val im = app.get(GameManager::class)
                    val gameServers = im.getGameServers().map { gs ->
                        createJsonObjectForGameServer(gs)
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
                        app.get(GameManager::class).getAllGames().forEach { gameId ->
                            add(createJsonObjectForGame(gameId))
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
                            app.get(PartyManager::class).getParties().forEach { party ->
                                add(createJsonObjectForParty(party))
                            }
                        })
                    }.toString())
                }

                "getPlayers" -> {
                    conn.send(JsonObject().apply {
                        addProperty("type", "players")
                        add("players", JsonArray().apply {
                            app.get(PlayerTracker::class).getPlayers().forEach { (uuid, state) ->
                                add(createJsonObjectForPlayer(uuid, state))
                            }
                        })
                    }.toString())
                }

                "getGameTypes" -> {
                    conn.send(getGameTypes().toString())
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

    private lateinit var ws: WebSocketServer

    override fun initialize() {
        ws = SocketServer(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 8080))
        ws.start()
    }

    fun sendUpdate(resource: String, action: String, id: String, updated: JsonElement?) {
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
    private val pf = JsonPatchFactory()

    fun sendMerge(resource: String, action: String, id: String, old: JsonObject, new: JsonObject) {
        val patch = pf.create(old, new)
        val json = gson.toJsonTree(patch)
        for ((index, item) in json.asJsonArray.withIndex()) {
            // Convert the JSON paths to strings. By default, they're serialized as arrays, which doesn't comply with the JSON patch spec
            val obj = item.asJsonObject
            obj.addProperty("path", patch[index].path.toString())
        }
        sendUpdate(resource, action, id, json)
    }

    fun createJsonObjectForGameServer(gs: GameManager.GameServer): JsonObject {
        return JsonObject().apply {
            if (gs is GameManager.AgonesGameServer) {
                add("raw", gs.`object`.raw)
            }
            addProperty("name", gs.name)
            addProperty("address", gs.address)
            addProperty("port", gs.port)
            val instances = JsonArray().apply {
                app.get(GameManager::class).getInstancesInServer(gs.name)
                    .forEach { add(it) }
            }
            add("instances", instances)
        }
    }

    fun createJsonObjectForGame(gameId: String): JsonObject {
        val gameManager = app.get(GameManager::class)
        val stateManager = app.get(GameStateManager::class)
        val playerTracker = app.get(PlayerTracker::class)
        return JsonObject().apply {
            addProperty("type", "instance")
            addProperty("id", gameId)
            addProperty("emptySlots", stateManager.getEmptySlots(gameId))
            addProperty("gameServer", gameManager.getGameServerOf(gameId))
            val state = stateManager.getState(gameId)
            add("gameState", JsonObject().apply {
                addProperty("joinable", state?.joinable)
                addProperty("openSlots", state?.openSlots)
                addProperty("maxSlots", state?.maxSlots)
                addProperty("playerCount", playerTracker.getPlayersInInstance(gameId).size)
                addProperty("stateName", state?.gameState?.name)
            })
            val type = gameManager.getGameType(gameId)
            add("gameType", JsonObject().apply {
                addProperty("name", type?.name)
                addProperty("mapName", type?.mapName)
                addProperty("mode", type?.mode)
            })
        }
    }

    fun createJsonObjectForGameType(type: GameType): JsonObject {
        return JsonObject().apply {
            addProperty("name", type.name)
            addProperty("mapName", type.mapName)
            addProperty("mode", type.mode)
            add("instances", JsonArray().apply {
                for (instance in app.get(GameManager::class).filterRunningGames(type).keys) {
                    add(instance)
                }
            })
        }
    }

    fun createJsonObjectForParty(party: PartyManager.Party): JsonObject {
        return JsonObject().apply {
            addProperty("id", party.id)
            add("members", JsonArray().apply {
                party.getMembers().forEach { member -> add(member.toString()) }
            })
            addProperty("leader", party.leader.toString())
            add("invitations", JsonArray().apply { party.invitations.forEach { it -> add(it.toString()) } })
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

    fun createJsonObjectForPlayer(uuid: UUID, state: PlayerTracker.PlayerState): JsonObject {
        return JsonObject().apply {
            addProperty("uuid", uuid.toString())
            addProperty("username", app.get(DatabaseConnection::class).getPlayerName(uuid))
            addProperty("proxyPodName", state.proxyPodName)
            addProperty("gameServerName", state.gameServerName)
            addProperty("gameId", state.gameId)
        }
    }

    private fun getGameTypes(): JsonObject {
        return JsonObject().apply {
            addProperty("type", "gameTypes")
            add("types", JsonArray().apply {
                for (type in app.get(MinInstanceService::class).getGameTypes()) {
                    add(createJsonObjectForGameType(type))
                }
            })
        }
    }
}