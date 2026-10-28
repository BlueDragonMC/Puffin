package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.GsClientServiceGrpcKt
import com.bluedragonmc.api.grpc.PlayerHolderGrpcKt
import com.bluedragonmc.api.grpc.ServerTracking
import com.bluedragonmc.api.grpc.instanceCreatedRequest
import com.bluedragonmc.puffin.app.ApplicationScope
import com.bluedragonmc.puffin.app.PuffinConfig
import com.bluedragonmc.puffin.util.GrpcChannels
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.reflect.TypeToken
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
import io.grpc.StatusException
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.util.Watch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration

/** The first backoff applied after a failed pod watch. */
private const val WATCH_INITIAL_BACKOFF_MS = 500L

/** The maximum backoff between pod watch attempts. */
private const val WATCH_MAX_BACKOFF_MS = 30_000L

/** A watch that stays up at least this long is considered healthy and reconnects immediately. */
private const val WATCH_STABLE_MILLIS = 10_000L

/** Exponential backoff for the pod watch, starting at [WATCH_INITIAL_BACKOFF_MS] and capped. */
private fun nextWatchBackoff(current: Long): Long =
    if (current == 0L) WATCH_INITIAL_BACKOFF_MS else (current * 2).coerceAtMost(WATCH_MAX_BACKOFF_MS)

interface IGameServerManager {
    suspend fun getGameServer(serverName: String): GameServer?

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
 * Fetches and maintains the list of game server pods using the Kubernetes API.
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

    /** Guards all access to [pods] and [readyGameServers]. */
    private val stateMutex = Mutex()

    private val pods = mutableListOf<V1Pod>()
    private val readyGameServers = mutableSetOf<String>()

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
                        logger.warn("Game server pod watch ended; reconnecting...")
                    } else {
                        // If the watch wasn't active for very long, consider it an error and retry with backoff
                        backoffMillis = nextWatchBackoff(backoffMillis)
                        logger.warn("Game server pod watch ended after only ${ranMillis}ms; retrying in ${backoffMillis}ms...")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    backoffMillis = nextWatchBackoff(backoffMillis)
                    logger.error("Error watching game server pods; retrying in ${backoffMillis}ms...", e)
                }
                if (backoffMillis > 0) delay(backoffMillis)
            }
        }

        applicationScope.repeatingTask(
            name = "GameManager Periodic Sync",
            initialDelayMillis = config.gsSyncPeriod,
            periodMillis = config.gsSyncPeriod
        ) {
            for (server in queueService.getServers()) {
                if (!server.ready || server.terminating) continue
                applicationScope.launch {
                    runCatching { syncExistingServer(server.name) }
                        .onFailure { logger.warn("Failed to sync existing game server ${server.name}", it) }
                }
            }

            // Sync game servers periodically just in case a change isn't picked up by the watcher
            reloadGameServers()
        }
    }

    suspend fun reloadGameServers() = stateMutex.withLock {
        withContext(Dispatchers.IO) { refreshDesiredVersion() }

        val items = withContext(Dispatchers.IO) {
            kubernetesClients.coreV1.listNamespacedPod(config.k8sNamespace)
                .labelSelector(config.gameServerLabelSelector)
                .execute()
                .items
                ?: emptyList()
        }

        val previousPods = ArrayList(pods)
        items.forEach { pod -> upsertPod(pod) }

        previousPods.forEach { pod ->
            val name = pod.metadata?.name ?: return@forEach
            if (items.none { it.metadata?.name == name }) {
                logger.info("Game server pod $name was removed during a manual sync.")
                processPodRemoved(pod)
                pods.removeIf { it.metadata?.name == name }
            }
        }

        updateDraining()
    }

    private suspend fun watch() = withContext(Dispatchers.IO) {
        val call = kubernetesClients.coreV1.listNamespacedPod(config.k8sNamespace)
            .labelSelector(config.gameServerLabelSelector)
            .watch(true)
            .buildCall(null)

        val watchType = object : TypeToken<Watch.Response<V1Pod>>() {}.type
        val watch: Watch<V1Pod> = Watch.createWatch(kubernetesClients.client, call, watchType)
        watch.use { activeWatch ->
            for (event in activeWatch) {
                val pod = event.`object`
                if (pod == null) {
                    logger.warn("Kubernetes pod watch returned an error: ${event.status}")
                    continue
                }

                stateMutex.withLock {
                    when (event.type) {
                        "ADDED", "MODIFIED" -> upsertPod(pod)

                        "DELETED" -> {
                            val name = pod.metadata?.name
                            val tracked = name?.let { podName ->
                                pods.find { it.metadata?.name == podName }
                            }
                            val removed = name != null && pods.removeIf { it.metadata?.name == name }
                            if (removed) {
                                processPodRemoved(tracked ?: pod)
                            } else {
                                logger.warn("Unknown game server pod was deleted: $name")
                            }
                        }

                        else -> logger.warn("Unknown Kubernetes API event type: ${event.type}")
                    }
                }
            }
        }
    }

    private suspend fun upsertPod(pod: V1Pod) {
        val name = pod.metadata?.name ?: return
        val index = pods.indexOfFirst { it.metadata?.name == name }

        if (index == -1) {
            pods.add(pod)
            processPodAdded(pod)
            return
        }

        val old = pods[index]
        if (old.metadata?.uid != pod.metadata?.uid) {
            // The pod was replaced with a new one that happens to reuse the same name.
            processPodRemoved(old)
            pods[index] = pod
            processPodAdded(pod)
        } else {
            pods[index] = pod
            processPodModified(old, pod)
        }
    }

    private suspend fun processPodAdded(pod: V1Pod) {
        val gs = toGameServer(pod)
        logger.info("New game server pod found: ${gs.name} (${gs.address}:${gs.port})")
        queueService.addServer(gs.name, pod.status?.podIP, gs.port, gs.ready, gs.terminating)

        notifyListeners(GameServerEvent.Added(gs))

        if (gs.ready) {
            readyGameServers.add(gs.name)
            applicationScope.launch { syncNewServer(gs.name) }
        }

        updateDraining(pod)
    }

    private suspend fun processPodModified(oldPod: V1Pod, pod: V1Pod) {
        val gs = toGameServer(pod)
        queueService.addServer(gs.name, pod.status?.podIP, gs.port, gs.ready, gs.terminating)

        if (oldPod != pod) {
            notifyListeners(GameServerEvent.Merged(toGameServer(oldPod), gs))
        }

        if (gs.ready) {
            if (readyGameServers.add(gs.name)) {
                logger.info("Game server pod ${gs.name} is now ready.")
                applicationScope.launch { syncNewServer(gs.name) }
            }
        } else {
            readyGameServers.remove(gs.name)
        }

        updateDraining(pod)
    }

    private suspend fun processPodRemoved(pod: V1Pod) {
        val name = pod.metadata?.name ?: return
        val address = pod.status?.podIP.orEmpty()

        logger.info("Game server pod $name was removed.")
        queueService.removeServer(name)
        readyGameServers.remove(name)
        if (address.isNotEmpty()) {
            grpcChannels.close(address)
        }
        notifyListeners(GameServerEvent.Removed(name))
    }

    private fun refreshDesiredVersion() {
        if (!config.drainOutdatedServers) return
        versionResolver.refresh()
    }

    private suspend fun updateDraining() {
        if (!config.drainOutdatedServers) return
        pods.forEach { updateDraining(it) }
    }

    /**
     * Recomputes whether [pod] is running an outdated version.
     */
    private suspend fun updateDraining(pod: V1Pod) {
        if (!config.drainOutdatedServers) return
        val gs = toGameServer(pod)
        val server = queueService.getServer(gs.name) ?: return
        val draining = isOutdated(pod)
        if (server.draining == draining) return
        queueService.setServerDraining(gs.name, draining)
        logger.info("Game server pod ${gs.name} is now ${if (draining) "draining (outdated)" else "up to date"}.")
        notifyListeners(GameServerEvent.Updated(gs))
    }

    private fun isOutdated(pod: V1Pod): Boolean {
        val running = versionResolver.runningGeneration(pod) ?: return false
        val desired = versionResolver.desiredGeneration() ?: return false
        return running != desired
    }

    private fun toGameServer(pod: V1Pod): PodGameServer =
        PodGameServer(pod, config.gameServerPort, kubernetesClients.podJson(pod))

    private suspend fun syncExistingServer(serverName: String) {
        val channel = k8sServiceDiscovery.getChannelToServer(serverName) ?: return
        val playersResponse =
            PlayerHolderGrpcKt.PlayerHolderCoroutineStub(channel).getPlayers(Empty.getDefaultInstance())
        val instancesResponse =
            GsClientServiceGrpcKt.GsClientServiceCoroutineStub(channel).getInstances(Empty.getDefaultInstance())

        playerTracker.updateGameServerPlayers(serverName, playersResponse)

        instancesResponse.instancesList.forEach { instance ->
            val id = instance.instanceUuid
            if (queueService.getGame(id) == null) {
                handleInstanceCreated(instanceCreatedRequest {
                    this.serverName = serverName
                    this.instanceUuid = id
                    this.gameType = instance.gameType
                    this.gameState = instance.gameState
                })
            } else {
                queueService.setGameState(id, instance.gameState)
            }
            playerTracker.updateGamePlayers(id, instance)
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
                if (queueService.getGame(id) == null) {
                    handleInstanceCreated(instanceCreatedRequest {
                        this.serverName = serverName
                        this.instanceUuid = id
                        this.gameType = instance.gameType
                        this.gameState = instance.gameState
                    })
                } else {
                    queueService.setGameState(id, instance.gameState)
                }
                playerTracker.updateGamePlayers(id, instance)
            }
            logger.info("Found ${instancesResponse.instancesCount} instances on server $serverName.")
        } catch (e: StatusException) {
            try {
                withContext(Dispatchers.IO) {
                    kubernetesClients.coreV1.readNamespacedPod(serverName, config.k8sNamespace).execute()
                }
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

    override suspend fun getGameServer(serverName: String): GameServer? = stateMutex.withLock {
        pods.find { it.metadata?.name == serverName }?.let(::toGameServer)
    }

    override suspend fun handleInstanceCreated(request: ServerTracking.InstanceCreatedRequest) {
        logger.info(
            "Game created: ${request.serverName}/${request.instanceUuid} " +
                    "(${request.gameType.name}/${request.gameType.mapId}/${request.gameType.mode})"
        )
        if (queueService.getGame(request.instanceUuid) == null) {
            queueService.addGame(request.serverName, request.instanceUuid, request.gameType, request.gameState)
            notifyListeners(GameServerEvent.InstanceAdded(request.instanceUuid))
        } else {
            queueService.setGameState(request.instanceUuid, request.gameState)
        }
    }

    override suspend fun handleInstanceRemoved(request: ServerTracking.InstanceRemovedRequest) {
        logger.info("Game removed: ${request.serverName}/${request.instanceUuid}")
        queueService.removeGame(request.serverName, request.instanceUuid)
        notifyListeners(GameServerEvent.InstanceRemoved(request.instanceUuid))
    }
}
