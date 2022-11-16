package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.*
import com.bluedragonmc.api.grpc.CommonTypes.GameType
import com.bluedragonmc.api.grpc.CommonTypes.GameType.GameTypeFieldSelector
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.util.Utils
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.protobuf.Empty
import io.grpc.StatusException
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.Config
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.*

class InstanceManager(app: Puffin) : Service(app) {

    private val lock = Any()
    private var kubernetesObjects = mutableListOf<DynamicKubernetesObject>()

    private val client = DynamicKubernetesApi("agones.dev", "v1", "gameservers", Config.defaultClient())
    private val defaultApi = CoreV1Api(Config.defaultClient())

    private val syncAttempts = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(2))
        .build<String, Int>()

    /**
     * A map of game server names to a list of instance IDs
     */
    private val gameServers = mutableMapOf<String, MutableSet<UUID>>()

    private val readyGameServers = mutableListOf<String>()

    /**
     * A map of instance IDs to their running game types
     */
    private val instanceTypes = mutableMapOf<UUID, GameType>()

    override fun initialize() {
        Puffin.IO.launch {
            while (true) {
                reloadGameServers()
                watch()
                logger.error("There was a problem watching Agones resources. Retrying...")
                delay(5_000)
            }
        }
    }

    private fun reloadGameServers() {
        val items = client.list().`object`.items
        items.forEach { server ->
            if (kubernetesObjects.none { it.metadata.uid == server.metadata.uid }) {
                // New server found!
                logger.info("New GameServer found during manual sync: ${server.metadata.name}")
                kubernetesObjects.add(server)
                val state = server.raw.get("status")?.asJsonObject?.get("state")?.asString
                if (state == "Ready") {
                    readyGameServers.add(server.metadata.uid!!)
                    processServerAdded(server)
                }
            }
        }
        ArrayList(kubernetesObjects).forEach { obj ->
            if (items.none { it.metadata.uid == obj.metadata.uid }) {
                // A game server was removed during the sync!
                logger.info("A GameServer was removed during manual sync: ${obj.metadata.name}")
                processServerRemoved(obj)
                kubernetesObjects.remove(obj)
            }
        }
    }

    private fun watch() {
        val watch = client.watch()
        watch.forEach { event ->
            val obj = event.`object`
            val newState = obj.raw.get("status")?.asJsonObject?.get("state")?.asString
            when (event.type) {
                "ADDED" -> {
                    // A new game server was added
                    kubernetesObjects.add(obj)
                }

                "MODIFIED" -> {
                    // An existing game server had its metadata or other information change
                    val index = kubernetesObjects.indexOfFirst { it.metadata.uid == obj.metadata.uid }
                    kubernetesObjects[index] = obj
                    logger.debug("Game server '${obj.metadata.name}' is now in state: $newState")
                    if (newState == "Ready" && !readyGameServers.contains(obj.metadata.uid)) {
                        // If the server changed from any other state to ready (and it hasn't been ready before),
                        // attempt to ping it and look at its players and instances.
                        readyGameServers.add(obj.metadata.uid!!)
                        processServerAdded(obj)
                    }
                }

                "DELETED" -> {
                    // A game server was removed
                    val removed = kubernetesObjects.removeIf { it.metadata.uid == obj.metadata.uid }
                    if (removed) {
                        processServerRemoved(obj)
                    } else {
                        logger.warn("Unknown GameServer was deleted: ${obj.metadata.name}")
                    }
                }

                else -> logger.warn("Unknown Kubernetes API event type: ${event.type}")
            }
        }
    }

    private fun processServerRemoved(`object`: DynamicKubernetesObject) {
        val gs = GameServer(`object`)
        logger.info("GameServer ${gs.name} was removed.")
        val instances = gameServers[gs.name] ?: emptySet()
        gameServers.remove(gs.name)
        instanceTypes.entries.removeAll { it.key in instances }
        Utils.cleanupChannelsForServer(gs.name)
    }

    private fun processServerAdded(`object`: DynamicKubernetesObject) {
        val gs = GameServer(`object`)
        logger.info("New GameServer found: ${gs.name} (${gs.address}:${gs.port})")
        gameServers.putIfAbsent(gs.name, mutableSetOf())

        // Get all running instances on this newly-added server
        Puffin.IO.launch {
            sync(gs.name)
        }
    }

    private suspend fun sync(serverName: String) {
        // Wait for the server's gRPC port to become available
        try {
            val channel = Utils.getChannelToServer(serverName) ?: return
            val playersResponse =
                PlayerHolderGrpcKt.PlayerHolderCoroutineStub(channel).getPlayers(Empty.getDefaultInstance())
            val instancesResponse =
                GsClientServiceGrpcKt.GsClientServiceCoroutineStub(channel).getInstances(Empty.getDefaultInstance())

            app.get(PlayerTracker::class).updatePlayers(playersResponse)
            logger.info("Found ${playersResponse.playersCount} players on server $serverName")
            instancesResponse.instancesList.forEach { instance ->
                val uuid = UUID.fromString(instance.instanceUuid)
                instanceTypes[uuid] = instance.gameType
                gameServers[serverName]!!.add(uuid)
                handleInstanceCreated(instanceCreatedRequest {
                    this.serverName = serverName
                    this.instanceUuid = instance.instanceUuid
                    this.gameType = instance.gameType
                })
            }
            logger.info("Found ${instancesResponse.instancesCount} instances on server $serverName.")
        } catch (e: StatusException) {

            try {
                defaultApi.readNamespacedPod(serverName, K8sServiceDiscovery.NAMESPACE, null)
            } catch (e: ApiException) {
                // If there was an error looking up the pod, it likely no longer exists.
                // This means there was some sort of desync between our watch and the reality in the cluster.
                e.printStackTrace()
                logger.warn("Tried to sync server $serverName, but it doesn't exist! Starting a manual sync...")
                reloadGameServers()
                return
            }

            val attempts = syncAttempts.getIfPresent(serverName) ?: 0
            syncAttempts.put(serverName, attempts + 1)

            if (attempts > 10) {
                logger.warn("Failed to sync players and instances with server $serverName after 10 attempts.")
                e.printStackTrace()
                return
            }

            logger.info("Failed to sync players and instances with server $serverName, retrying...")
            delay(5000)
            sync(serverName)
        }
    }

    /**
     * Filters the currently-running instances using the flags
     * (selectors) set in the [other] [CommonTypes.GameType] parameter.
     */
    fun filterRunningInstances(
        other: GameType,
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

    inner class InstanceService : InstanceServiceGrpcKt.InstanceServiceCoroutineImplBase() {
        override suspend fun initGameServer(request: ServerTracking.InitGameServerRequest): Empty {
            // Called when a new game server starts up and sends a ping
            logger.info("New game server started and pinged: ${request.serverName}")
            gameServers[request.serverName] = mutableSetOf()
            return Empty.getDefaultInstance()
        }

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

        override suspend fun getTotalPlayerCount(request: ServerTracking.PlayerCountRequest): ServerTracking.PlayerCountResponse {
            return playerCountResponse {
                totalPlayers = app.get(PlayerTracker::class).getPlayerCount(request.filterGameTypeOrNull)
            }
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