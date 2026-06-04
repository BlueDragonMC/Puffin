package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.*
import com.bluedragonmc.puffin.app.Env
import com.bluedragonmc.puffin.app.Env.DEV_MODE
import com.bluedragonmc.puffin.app.Env.K8S_NAMESPACE
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.dashboard.IApiService
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.inject.Inject
import com.google.inject.Singleton
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

interface IGameServerManager {
    fun getK8sObject(serverName: String): GameServerManager.AgonesGameServer?

    val serviceDiscoveryService: GameServerManager.ServerDiscoveryService
    val instanceService: GameServerManager.InstanceService
}

/**
 * Fetches and maintains a list of game servers using the Kubernetes API
 */
@Singleton
class GameServerManager @Inject constructor(
    val apiService: IApiService,
    val playerTracker: IPlayerTracker,
    val queueService: IQueueService,
    val k8sServiceDiscovery: IK8sServiceDiscovery,
    val mapsService: MapService
) : Service(), IGameServerManager {

    private var kubernetesObjects = mutableListOf<DynamicKubernetesObject>()

    private val client = DynamicKubernetesApi("agones.dev", "v1", "gameservers", Config.defaultClient())
    private val defaultApi = CoreV1Api(Config.defaultClient())

    private val syncAttempts = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(2))
        .build<String, Int>()

    private val readyGameServers = mutableListOf<String>()

    init {
        if (!DEV_MODE) {

            Puffin.IO.launch {
                while (true) {
                    reloadGameServers()
                    watch()
                    logger.error("There was a problem watching Agones resources. Retrying...")
                    delay(5_000)
                }
            }

            catchingTimer(
                "GameManager Periodic Sync",
                daemon = true,
                initialDelay = Env.GS_SYNC_PERIOD,
                period = Env.GS_SYNC_PERIOD
            ) {

                for ((serverName, _) in queueService.getServers()) {
                    Puffin.IO.launch {
                        syncExistingServer(serverName)
                    }
                }

                // Sync game servers periodically just in case a change isn't picked up by the watcher
                reloadGameServers()
            }
        }
    }

    @Synchronized
    fun reloadGameServers() {
        val items = client.list().`object`.items
        val previousK8sObjects = ArrayList(kubernetesObjects)
        items.forEach { server ->
            if (previousK8sObjects.none { it.metadata.uid == server.metadata.uid }) {
                // New server found!
                logger.info("New GameServer found during manual sync: ${server.metadata.name}")
                kubernetesObjects.add(server)
                val state = server.raw.get("status")?.asJsonObject?.get("state")?.asString
                if (state == "Ready" || state == "Reserved" || state == "Allocated") {
                    readyGameServers.add(server.metadata.name!!)
                    processServerAdded(server)
                }
            } else {
                // Update existing game servers
                val index = kubernetesObjects.indexOfFirst { it.metadata.uid == server.metadata.uid }
                if (index in kubernetesObjects.indices) {
                    val old = kubernetesObjects[index]
                    kubernetesObjects[index] = server
                    if (old != server) {
                        apiService.apply {
                            sendMerge(
                                "gameServer", "patch", server.metadata.name!!,
                                createJsonObjectForGameServer(AgonesGameServer(old)),
                                createJsonObjectForGameServer(AgonesGameServer(server))
                            )
                        }
                    }
                }
            }
        }
        previousK8sObjects.forEach { obj ->
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
                    if (kubernetesObjects.none { it.metadata.uid == obj.metadata.uid }) {
                        kubernetesObjects.add(obj)
                    }
                }

                "MODIFIED" -> {
                    // An existing game server had its metadata or other information change
                    val index = kubernetesObjects.indexOfFirst { it.metadata.uid == obj.metadata.uid }
                    val old = kubernetesObjects[index]
                    kubernetesObjects[index] = obj
                    if (old != obj) {
                        apiService.sendMerge(
                            "gameServer", "patch", obj.metadata.name!!,
                            apiService.createJsonObjectForGameServer(AgonesGameServer(old)),
                            apiService.createJsonObjectForGameServer(AgonesGameServer(obj))
                        )
                    }
                    logger.debug("Game server '${obj.metadata.name}' is now in state: $newState")
                    if (newState == "Ready" && !readyGameServers.contains(obj.metadata.name)) {
                        // If the server changed from any other state to ready (and it hasn't been ready before),
                        // attempt to ping it and look at its players and instances.
                        readyGameServers.add(obj.metadata.name!!)
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
        val gs = AgonesGameServer(`object`)
        logger.info("GameServer ${gs.name} was removed.")
        queueService.removeServer(gs.name)
        readyGameServers.remove(gs.name)
        Utils.closeChannel(gs.address)
        apiService.sendUpdate("gameServer", "remove", gs.name, null)
    }

    private fun processServerAdded(`object`: DynamicKubernetesObject) {
        val gs = AgonesGameServer(`object`)
        logger.info("New GameServer found: ${gs.name} (${gs.address}:${gs.port})")
        queueService.addServer(gs.name)

        // Get all running instances on this newly-added server
        Puffin.IO.launch {
            syncNewServer(gs.name)
        }
        apiService.sendUpdate(
            "gameServer", "add", gs.name,
            apiService.createJsonObjectForGameServer(gs)
        )
    }

    private suspend fun syncExistingServer(serverName: String) {
        val channel = k8sServiceDiscovery.getChannelToServer(serverName) ?: return
        val playersResponse =
            PlayerHolderGrpcKt.PlayerHolderCoroutineStub(channel).getPlayers(Empty.getDefaultInstance())
        val instancesResponse =
            GsClientServiceGrpcKt.GsClientServiceCoroutineStub(channel).getInstances(Empty.getDefaultInstance())

        playerTracker.updateGameServerPlayers(serverName, playersResponse)

        instancesResponse.instancesList.forEach { instance ->
            queueService.setGameState(instance.instanceUuid, instance.gameState)
            playerTracker.updateGamePlayers(instance.instanceUuid, instance)
        }
    }

    private suspend fun syncNewServer(serverName: String) {
        // Wait for the server's gRPC port to become available
        try {
            val channel = k8sServiceDiscovery.getChannelToServer(serverName) ?: return
            val playersResponse =
                PlayerHolderGrpcKt.PlayerHolderCoroutineStub(channel).getPlayers(Empty.getDefaultInstance())
            val instancesResponse =
                GsClientServiceGrpcKt.GsClientServiceCoroutineStub(channel).getInstances(Empty.getDefaultInstance())

            playerTracker.updateGameServerPlayers(serverName, playersResponse)
            logger.info("Found ${playersResponse.playersCount} players on server $serverName")
            instancesResponse.instancesList.forEach { instance ->
                val id = instance.instanceUuid
                handleInstanceCreated(instanceCreatedRequest {
                    this.serverName = serverName
                    this.instanceUuid = id
                    this.gameType = instance.gameType
                    this.gameState = instance.gameState
                })
                playerTracker.updateGamePlayers(id, instance)
            }
            logger.info("Found ${instancesResponse.instancesCount} instances on server $serverName.")
        } catch (e: StatusException) {
            if (DEV_MODE) return

            try {
                defaultApi.readNamespacedPod(serverName, K8S_NAMESPACE).execute()
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
            syncNewServer(serverName)
        }
    }

    interface GameServer {
        val address: String
        val name: String
        val port: Int?
    }

    data class AgonesGameServer(val `object`: DynamicKubernetesObject) : GameServer {
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

    override fun getK8sObject(serverName: String): AgonesGameServer? {
        return kubernetesObjects.map { AgonesGameServer(it) }.find { it.name == serverName }
    }

    override val serviceDiscoveryService by lazy { ServerDiscoveryService() }

    inner class ServerDiscoveryService : LobbyServiceGrpcKt.LobbyServiceCoroutineImplBase() {
        override suspend fun findLobby(request: ServiceDiscovery.FindLobbyRequest): ServiceDiscovery.FindLobbyResponse =
            handleRPC {
                val servers = queueService.getServers().filter { gs ->
                    (request.includeServerNamesCount == 0 || request.includeServerNamesList.contains(gs.name)) &&
                            (request.excludeServerNamesCount == 0 || !request.excludeServerNamesList.contains(gs.name))
                }

                for (server in servers) {
                    for (game in server.games) {
                        if (game.gameType.name == "Lobby") {
                            val info = getK8sObject(server.name) ?: continue
                            return ServiceDiscovery.FindLobbyResponse.newBuilder()
                                .setFound(true)
                                .setServerName(server.name)
                                .setIp(info.address)
                                .setPort(info.port ?: 25565)
                                .setInstanceUuid(game.id)
                                .build()
                        }
                    }
                }

                if (servers.isEmpty()) {
                    return ServiceDiscovery.FindLobbyResponse.newBuilder().setFound(false).build()
                }

                // Start up a lobby if one wasn't found
                val bestServer = servers.minBy { queueService.getServer(it.name)?.games?.size ?: Integer.MAX_VALUE }
                val info = getK8sObject(bestServer.name) ?: return ServiceDiscovery.FindLobbyResponse.newBuilder().setFound(false).build()

                val stub = k8sServiceDiscovery.getStubToServer(bestServer.name) ?: return ServiceDiscovery.FindLobbyResponse.newBuilder().setFound(false).build()
                val response = stub.createInstance(
                    GsClient.CreateInstanceRequest.newBuilder()
                        .setGame("Lobby")
                        .setMapSource(mapsService.getAvailableMaps("Lobby", null, null).random())
                        .build()
                )

                return ServiceDiscovery.FindLobbyResponse.newBuilder()
                    .setFound(true)
                    .setServerName(bestServer.name)
                    .setIp(info.address)
                    .setPort(info.port ?: 25565)
                    .setInstanceUuid(response.instanceUuid)
                    .build()
            }
    }

    override val instanceService by lazy { InstanceService() }

    inner class InstanceService : InstanceServiceGrpcKt.InstanceServiceCoroutineImplBase() {
        override suspend fun initGameServer(request: ServerTracking.InitGameServerRequest): Empty = handleRPC {
            // Called when a new game server starts up and sends a ping
            logger.info("New game server started and pinged: ${request.serverName}")
            queueService.addServer(request.serverName)
            return Empty.getDefaultInstance()
        }

        override suspend fun createInstance(request: ServerTracking.InstanceCreatedRequest): Empty = handleRPC {
            // Called when an instance is created on a game server
            handleInstanceCreated(request)
            return Empty.getDefaultInstance()
        }

        override suspend fun removeInstance(request: ServerTracking.InstanceRemovedRequest): Empty = handleRPC {
            // Called when an instance is removed on a game server
            handleInstanceRemoved(request)
            return Empty.getDefaultInstance()
        }

        override suspend fun getTotalPlayerCount(request: ServerTracking.PlayerCountRequest): ServerTracking.PlayerCountResponse =
            handleRPC {
                return playerCountResponse {
                    totalPlayers = playerTracker.getPlayerCount(request.filterGameTypeOrNull)
                }
            }
    }

    private fun handleInstanceCreated(request: ServerTracking.InstanceCreatedRequest) {
        logger.info(
            "Game created: ${request.serverName}/${request.instanceUuid} " +
                    "(${request.gameType.name}/${request.gameType.mapId}/${request.gameType.mode})"
        )
        queueService.addGame(request.serverName, request.instanceUuid, request.gameType, request.gameState)

        apiService.sendUpdate(
            "instance", "add", request.instanceUuid,
            apiService.createJsonObjectForGame(request.instanceUuid)
        )
    }

    private fun handleInstanceRemoved(request: ServerTracking.InstanceRemovedRequest) {
        logger.info("Game removed: ${request.serverName}/${request.instanceUuid}")
        queueService.removeGame(request.serverName, request.instanceUuid)
        apiService.sendUpdate("instance", "remove", request.instanceUuid, null)
    }
}
