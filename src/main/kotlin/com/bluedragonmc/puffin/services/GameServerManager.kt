package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.GsClientServiceGrpcKt
import com.bluedragonmc.api.grpc.PlayerHolderGrpcKt
import com.bluedragonmc.api.grpc.ServerTracking
import com.bluedragonmc.api.grpc.instanceCreatedRequest
import com.bluedragonmc.puffin.app.ApplicationScope
import com.bluedragonmc.puffin.app.PuffinConfig
import com.bluedragonmc.puffin.util.GrpcChannels
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
import io.grpc.StatusException
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration

/** The first backoff applied after a failed Agones watch. */
private const val WATCH_INITIAL_BACKOFF_MS = 500L

/** The maximum backoff between Agones watch attempts. */
private const val WATCH_MAX_BACKOFF_MS = 30_000L

/** A watch that stays up at least this long is considered healthy and reconnects immediately. */
private const val WATCH_STABLE_MILLIS = 10_000L

/** Exponential backoff for the Agones watch, starting at [WATCH_INITIAL_BACKOFF_MS] and capped. */
private fun nextWatchBackoff(current: Long): Long =
    if (current == 0L) WATCH_INITIAL_BACKOFF_MS else (current * 2).coerceAtMost(WATCH_MAX_BACKOFF_MS)

interface IGameServerManager {
    suspend fun getK8sObject(serverName: String): GameServer?

    fun registerGameServerListener(listener: suspend (GameServerEvent) -> Unit)

    suspend fun handleInstanceCreated(request: ServerTracking.InstanceCreatedRequest)
    suspend fun handleInstanceRemoved(request: ServerTracking.InstanceRemovedRequest)
}

/**
 * Notifications emitted by [GameServerManager] about game servers and their instances.
 */
sealed interface GameServerEvent {
    data class Added(val server: GameServer) : GameServerEvent
    data class Removed(val name: String) : GameServerEvent
    data class Merged(val old: GameServer, val new: GameServer) : GameServerEvent
    data class Updated(val server: GameServer) : GameServerEvent
    data class InstanceAdded(val gameId: String) : GameServerEvent
    data class InstanceRemoved(val gameId: String) : GameServerEvent
}

/**
 * Fetches and maintains a list of game servers using the Kubernetes API
 */
@Singleton
class GameServerManager @Inject constructor(
    private val playerTracker: IPlayerTracker,
    private val queueService: IQueueService,
    private val k8sServiceDiscovery: IK8sServiceDiscovery,
    private val versionResolver: ServerVersionResolver,
    private val applicationScope: ApplicationScope,
    private val config: PuffinConfig,
    private val grpcChannels: GrpcChannels,
    private val kubernetesClients: KubernetesClients,
) : Service(), IGameServerManager {

    private val syncAttempts = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(2))
        .build<String, Int>()

    /** Guards all access to [kubernetesObjects] and [readyGameServers]. */
    private val stateMutex = Mutex()

    private var kubernetesObjects = mutableListOf<DynamicKubernetesObject>()
    private val readyGameServers = mutableListOf<String>()

    private val gameServerListeners = mutableListOf<suspend (GameServerEvent) -> Unit>()

    override fun registerGameServerListener(listener: suspend (GameServerEvent) -> Unit) {
        gameServerListeners.add(listener)
    }

    private suspend fun notifyListeners(event: GameServerEvent) {
        gameServerListeners.forEach { it(event) }
    }

    override fun start() {
        applicationScope.launch {
            var backoffMillis = 0L
            while (true) {
                try {
                    reloadGameServers()
                    val watchStart = System.nanoTime()
                    watch()
                    val ranMillis = (System.nanoTime() - watchStart) / 1_000_000
                    if (ranMillis >= WATCH_STABLE_MILLIS) {
                        // If the watch stayed active for >= 10 seconds, retry immediately
                        backoffMillis = 0L
                        logger.warn("Agones watch ended; reconnecting...")
                    } else {
                        // If the watch wasn't active for very long, consider it an error and retry with backoff
                        backoffMillis = nextWatchBackoff(backoffMillis)
                        logger.warn("Agones watch ended after only ${ranMillis}ms; retrying in ${backoffMillis}ms...")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    backoffMillis = nextWatchBackoff(backoffMillis)
                    logger.error("Error watching Agones resources; retrying in ${backoffMillis}ms...", e)
                }
                if (backoffMillis > 0) delay(backoffMillis)
            }
        }

        applicationScope.repeatingTask(
            name = "GameManager Periodic Sync",
            initialDelayMillis = config.gsSyncPeriod,
            periodMillis = config.gsSyncPeriod
        ) {
            for ((serverName, _) in queueService.getServers()) {
                applicationScope.launch {
                    syncExistingServer(serverName)
                }
            }

            // Sync game servers periodically just in case a change isn't picked up by the watcher
            reloadGameServers()
        }
    }

    suspend fun reloadGameServers() = stateMutex.withLock {
        withContext(Dispatchers.IO) { refreshFleetVersions() }
        val items = withContext(Dispatchers.IO) { kubernetesClients.gameServers.list().`object`.items }
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
                        notifyListeners(GameServerEvent.Merged(AgonesGameServer(old), AgonesGameServer(server)))
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

        updateDraining()
    }

    private suspend fun watch() = withContext(Dispatchers.IO) {
        val watch = kubernetesClients.gameServers.watch()
        watch.forEach { event ->
            stateMutex.withLock {
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
                            notifyListeners(GameServerEvent.Merged(AgonesGameServer(old), AgonesGameServer(obj)))
                        }
                        val serverName = obj.metadata.name!!
                        logger.debug("Game server '$serverName' is now in state: $newState")
                        if (newState == "Ready" && !readyGameServers.contains(serverName)) {
                            // If the server changed from any other state to ready (and it hasn't been ready before),
                            // attempt to ping it and look at its players and instances.
                            readyGameServers.add(serverName)
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
    }

    private suspend fun processServerRemoved(`object`: DynamicKubernetesObject) {
        val gs = AgonesGameServer(`object`)
        logger.info("GameServer ${gs.name} was removed.")
        queueService.removeServer(gs.name)
        readyGameServers.remove(gs.name)
        grpcChannels.close(gs.address)
        notifyListeners(GameServerEvent.Removed(gs.name))
    }

    private suspend fun processServerAdded(`object`: DynamicKubernetesObject) {
        val gs = AgonesGameServer(`object`)
        logger.info("New GameServer found: ${gs.name} (${gs.address}:${gs.port})")
        queueService.addServer(gs.name, gs.address, gs.port)

        // Get all running instances on this newly-added server
        applicationScope.launch {
            syncNewServer(gs.name)
        }
        notifyListeners(GameServerEvent.Added(gs))
        updateDraining(`object`)
    }

    private fun refreshFleetVersions() {
        if (!config.drainOutdatedServers) return
        versionResolver.refresh()
    }

    private suspend fun updateDraining() {
        if (!config.drainOutdatedServers) return
        kubernetesObjects.forEach { updateDraining(it) }
    }

    /**
     * Recomputes whether [object] is running an outdated version.
     */
    private suspend fun updateDraining(`object`: DynamicKubernetesObject) {
        if (!config.drainOutdatedServers) return
        val gs = AgonesGameServer(`object`)
        val draining = isOutdated(`object`)
        val server = queueService.getServer(gs.name) ?: return
        if (server.draining == draining) return
        queueService.setServerDraining(gs.name, draining)
        logger.info("GameServer ${gs.name} is now ${if (draining) "draining (outdated)" else "up to date"}.")
        notifyListeners(GameServerEvent.Updated(gs))
    }

    private fun isOutdated(`object`: DynamicKubernetesObject): Boolean {
        val running = versionResolver.runningGeneration(`object`) ?: return false
        val desired = versionResolver.desiredGeneration(`object`) ?: return false
        return running != desired
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
            try {
                withContext(Dispatchers.IO) { kubernetesClients.coreV1.readNamespacedPod(serverName, config.k8sNamespace).execute() }
            } catch (e: ApiException) {
                // If there was an error looking up the pod, it likely no longer exists.
                // This means there was some sort of desync between our watch and the reality in the cluster.
                logger.warn("Tried to sync server $serverName, but it doesn't exist! Starting a manual sync...", e)
                reloadGameServers()
                return
            }

            val attempts = syncAttempts.getIfPresent(serverName) ?: 0
            syncAttempts.put(serverName, attempts + 1)

            if (attempts > 10) {
                logger.warn("Failed to sync players and instances with server $serverName after 10 attempts.", e)
                return
            }

            logger.info("Failed to sync players and instances with server $serverName, retrying...")
            delay(5000)
            syncNewServer(serverName)
        }
    }

    override suspend fun getK8sObject(serverName: String): GameServer? = stateMutex.withLock {
        kubernetesObjects.map { AgonesGameServer(it) }.find { it.name == serverName }
    }

    override suspend fun handleInstanceCreated(request: ServerTracking.InstanceCreatedRequest) {
        logger.info(
            "Game created: ${request.serverName}/${request.instanceUuid} " +
                    "(${request.gameType.name}/${request.gameType.mapId}/${request.gameType.mode})"
        )
        queueService.addGame(request.serverName, request.instanceUuid, request.gameType, request.gameState)

        notifyListeners(GameServerEvent.InstanceAdded(request.instanceUuid))
    }

    override suspend fun handleInstanceRemoved(request: ServerTracking.InstanceRemovedRequest) {
        logger.info("Game removed: ${request.serverName}/${request.instanceUuid}")
        queueService.removeGame(request.serverName, request.instanceUuid)
        notifyListeners(GameServerEvent.InstanceRemoved(request.instanceUuid))
    }
}
