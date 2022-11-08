package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.*
import com.bluedragonmc.api.grpc.CommonTypes.GameType
import com.bluedragonmc.api.grpc.CommonTypes.GameType.*
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.util.Utils
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.protobuf.Empty
import io.kubernetes.client.util.Config
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject
import java.time.Duration
import java.util.*

class InstanceManager(app: Puffin) : Service(app) {

    private val lock = Any()
    private var kubernetesObjects = mutableListOf<DynamicKubernetesObject>()

    private val client = DynamicKubernetesApi("agones.dev", "v1", "gameservers", Config.defaultClient())

    /**
     * A map of game server names to a list of instance IDs
     */
    private val gameServers = mutableMapOf<String, MutableSet<UUID>>()

    /**
     * A map of instance IDs to their running game types
     */
    private val instanceTypes = mutableMapOf<UUID, GameType>()

    private val pingCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).build<String, Unit>()

    init {
        Utils.catchingTimer("Agones SD", daemon = true, initialDelay = 5_000, period = 5_000) {
            val response = client.list()
            if (response.httpStatusCode >= 400) {
                error("Kubernetes returned HTTP error code: ${response.httpStatusCode}: ${response.status?.status}, ${response.status?.message}")
            }
            synchronized(lock) {
                val objects = response.`object`.items
                val removed = kubernetesObjects.filter { !objects.any { o -> o.metadata.uid == it.metadata.uid } }
                val added = objects.filter { !kubernetesObjects.any { o -> o.metadata.uid == it.metadata.uid } }
                removed.forEach(::processServerRemoved)
                added.forEach(::processServerAdded)
                kubernetesObjects = objects
            }
        }
        Utils.catchingTimer("Game Server Ping Check", daemon = true, initialDelay = 30_000, period = 10_000) {
            ArrayList(gameServers.keys).forEach { serverName ->
                if (pingCache.getIfPresent(serverName) == null) {
                    logger.warn("Game Server $serverName stopped pinging for ~5 minutes and was removed from the list of GameServers.")
                    gameServers.remove(serverName)
                }
            }
        }
    }

    private fun processServerRemoved(`object`: DynamicKubernetesObject) {
        val gs = GameServer(`object`)
        logger.info("GameServer ${gs.name} was removed.")
        val instances = gameServers[gs.name] ?: emptySet()
        gameServers.remove(gs.name)
        instanceTypes.entries.removeAll { it.key in instances }
    }

    private fun processServerAdded(`object`: DynamicKubernetesObject) {
        val gs = GameServer(`object`)
        logger.info("New GameServer found: ${gs.name} (${gs.address}:${gs.port})")
        gameServers.putIfAbsent(gs.name, mutableSetOf())
        pingCache.put(gs.name, Unit)
    }

    /**
     * Filters the currently-running instances using the flags
     * (selectors) set in the [other] [CommonTypes.GameType] parameter.
     */
    fun filterRunningInstances(
        other: GameType
    ): Map<UUID, GameType> {

        val flags = other.selectorsList

        return instanceTypes.filter { (_, type) ->
            (!flags.contains(GameTypeFieldSelector.GAME_NAME) || type.name == other.name) &&
            (!flags.contains(GameTypeFieldSelector.GAME_MODE) || type.mode == other.mode) &&
            (!flags.contains(GameTypeFieldSelector.MAP_NAME) || type.mapName == other.mapName)
        }
    }

    /**
     * Finds the game server with the least running instances.
     * Used to create new instances on the least-strained
     * game server.
     */
    fun findGameServerWithLeastInstances() =
        gameServers.minByOrNull { (_, instances) -> instances.size }

    /**
     * Gets the game server name given one of its
     * registered instance IDs. Returns null if no
     * server was found.
     */
    fun getGameServerOf(instanceId: UUID): String? {
        return gameServers.entries.firstOrNull { it.value.contains(instanceId) }?.key
    }

    /**
     * Makes a defensive copy of the list of servers just in case it is changed while iterating.
     */
    fun getGameServers() = synchronized(lock) { ArrayList(kubernetesObjects) }.map { GameServer(it) }

    data class GameServer(val `object`: DynamicKubernetesObject) {
        private val status = `object`.raw.getAsJsonObject("status")

        val address: String by lazy {
            return@lazy status.get("address").asString
        }
        val port by lazy {
            if (status.has("ports") && status.get("ports").isJsonArray) status.get("ports").asJsonArray.first { p ->
                p.asJsonObject.get("name").asString == "minecraft"
            }.asJsonObject.get("port").asInt else null
        }
        val name = `object`.metadata.name!!
    }

    inner class ServerDiscoveryService : LobbyServiceGrpcKt.LobbyServiceCoroutineImplBase() {
        override suspend fun findLobby(request: ServiceDiscovery.FindLobbyRequest): ServiceDiscovery.FindLobbyResponse {

            // Find any running instances with the "Lobby" game type.
            val gs = getGameServers()
            val lobbies = filterRunningInstances(
                gameType {
                    name = "Lobby"
                    selectors += GameTypeFieldSelector.GAME_NAME
                }
            ).keys.associateWith { getGameServerOf(it) }.filter { it.value != null }

            return findLobbyResponse {
                found = false
                for ((instanceId, gameServer) in lobbies) {
                    if (request.includeServerNamesCount > 0 && gameServer !in request.includeServerNamesList) continue
                    if (request.excludeServerNamesCount > 0 && gameServer in request.excludeServerNamesList) continue
                    val info = gs.find { it.name == gameServer } ?: continue
                    found = true
                    serverName = gameServer!!
                    ip = info.address
                    port = info.port ?: 25565
                    instanceUuid = instanceId.toString()
                    break
                }
            }
        }
    }

    inner class ServerTrackerService : ServerTrackerServiceGrpcKt.ServerTrackerServiceCoroutineImplBase() {
        override suspend fun initGameServer(request: ServerTracking.InitGameServerRequest): Empty {
            // Called when a new game server starts up and sends a ping
            logger.info("New game server started and pinged: ${request.serverName}")
            gameServers[request.serverName] = mutableSetOf()
            pingCache.put(request.serverName, Unit)
            return Empty.getDefaultInstance()
        }

        override suspend fun serverSync(request: ServerTracking.ServerSyncRequest): Empty {
            // Called by game servers every ~30 seconds to keep in sync
            val instances = request.instancesList
            val current = gameServers[request.serverName] ?: return Empty.getDefaultInstance()
            val added = instances.filter { !current.contains(UUID.fromString(it.instanceUuid)) }
            val removed = current.filter { !instances.any { i -> UUID.fromString(i.instanceUuid) == it } }

            instances.forEach {
                instanceTypes[UUID.fromString(it.instanceUuid)] = it.gameType
            }

            added.forEach {
                logger.info("Instance added via ServerSyncMessage: $it")
                handleInstanceCreated(
                    ServerTracking.InstanceCreatedRequest.newBuilder().apply {
                        instanceUuid = it.instanceUuid
                        gameType = it.gameType
                        serverName = request.serverName
                    }.build()
                )
            }
            removed.forEach {
                logger.info("Instance removed via ServerSyncMessage: $it")
                handleInstanceRemoved(
                    ServerTracking.InstanceRemovedRequest.newBuilder().apply {
                        serverName = request.serverName
                        instanceUuid = it.toString()
                    }.build()
                )
            }

            pingCache.put(request.serverName, Unit)

            return Empty.getDefaultInstance()
        }
    }

    inner class InstanceService : InstanceServiceGrpcKt.InstanceServiceCoroutineImplBase() {
        override suspend fun createInstance(request: ServerTracking.InstanceCreatedRequest): Empty {
            // Called when an instance is created on a game server
            handleInstanceCreated(request)
            return Empty.getDefaultInstance()
        }

        override suspend fun removeInstance(request: ServerTracking.InstanceRemovedRequest): Empty {
            // Called when an instance is removed on a game server
            handleInstanceRemoved(request)
            return Empty.getDefaultInstance()
        }
    }

    private fun handleInstanceCreated(request: ServerTracking.InstanceCreatedRequest) {
        logger.info("Instance created: ${request.serverName}/${request.instanceUuid}")
        // Add to map of instances to game types
        val instanceId = UUID.fromString(request.instanceUuid)
        instanceTypes[instanceId] = request.gameType

        // Add to list of containers
        if (!gameServers.containsKey(request.serverName)) {
            logger.warn("Instance was created without a PingMessage sent first. Game server name: ${request.serverName}, Instance ID: $instanceId.")
            gameServers[request.serverName] = mutableSetOf()
        }
        gameServers[request.serverName]!!.add(instanceId)
    }

    private fun handleInstanceRemoved(request: ServerTracking.InstanceRemovedRequest) {
        logger.info("Instance removed: ${request.serverName}/${request.instanceUuid}")
        val instanceId = UUID.fromString(request.instanceUuid)
        gameServers[request.serverName]?.remove(instanceId)
        instanceTypes.remove(instanceId)
    }
}