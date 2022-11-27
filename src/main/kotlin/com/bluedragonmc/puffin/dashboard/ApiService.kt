package com.bluedragonmc.puffin.dashboard

import com.bluedragonmc.api.grpc.CommonTypes.GameType
import com.bluedragonmc.puffin.services.*
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
                    val im = app.get(InstanceManager::class)
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
                        app.get(InstanceManager::class).getAllInstances().forEach { instanceId ->
                            add(createJsonObjectForInstance(instanceId.toString()))
                        }
                    })
                }.toString())

                "getInstance" -> conn.send(
                    createJsonObjectForInstance(decoded.get("instance").asString).toString()
                )
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

    fun sendUpdate(resource: String, action: String, id: String, updated: JsonObject?) {
        val obj = JsonObject().apply {
            addProperty("type", "update")
            addProperty("action", action)
            addProperty("resource", resource)
            addProperty("id", id)
            add("updated", updated)
        }
        ws.broadcast(obj.toString())
    }

    fun createJsonObjectForGameServer(gs: InstanceManager.GameServer): JsonObject {
        return JsonObject().apply {
            add("raw", gs.`object`.raw)
            addProperty("name", gs.name)
            addProperty("address", gs.address)
            addProperty("port", gs.port)
            val instances = JsonArray().apply {
                app.get(InstanceManager::class).getInstancesInServer(gs.name)
                    .forEach { add(it.toString()) }
            }
            add("instances", instances)
        }
    }

    fun createJsonObjectForInstance(instanceId: String): JsonObject {
        val im = app.get(InstanceManager::class)
        val sm = app.get(GameStateManager::class)
        return JsonObject().apply {
            addProperty("type", "instance")
            addProperty("id", instanceId)
            val uuid = UUID.fromString(instanceId)
            addProperty("emptySlots", sm.getEmptySlots(uuid))
            addProperty("gameServer", im.getGameServerOf(uuid))
            val state = sm.getState(uuid)
            add("gameState", JsonObject().apply {
                addProperty("joinable", state?.joinable)
                addProperty("openSlots", state?.openSlots)
                addProperty("stateName", state?.gameState?.name)
            })
            val type = im.getGameType(uuid)
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
                for (instance in app.get(InstanceManager::class).filterRunningInstances(type).keys) {
                    add(instance.toString())
                }
            })
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