package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.PlayerHolderGrpcKt
import com.bluedragonmc.puffin.app.Env
import com.bluedragonmc.puffin.app.Env.DEFAULT_GS_IP
import com.bluedragonmc.puffin.app.Env.DEFAULT_PROXY_IP
import com.bluedragonmc.puffin.app.Env.DEV_MODE
import com.bluedragonmc.puffin.app.Env.K8S_NAMESPACE
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.util.Utils
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.protobuf.Empty
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.util.Config
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.*

/**
 * Uses the Kubernetes API to list proxies and their cluster IP addresses
 */
class K8sServiceDiscovery(app: ServiceHolder) : Service(app) {

    private lateinit var api: CoreV1Api

    private val serverAddresses = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(60))
        .expireAfterAccess(Duration.ofMinutes(60))
        .build<String, String>()

    override fun initialize() {
        val client = Config.defaultClient()
        Configuration.setDefaultApiClient(client)

        api = CoreV1Api()

        // Kubernetes isn't expected in development mode
        if (DEV_MODE) return

        // Get players on all proxies and start tracking them when the app starts up
        periodicSync()

        Utils.catchingTimer(
            "K8sServiceDiscovery Periodic Sync",
            daemon = true,
            initialDelay = Env.K8S_SYNC_PERIOD,
            period = Env.K8S_SYNC_PERIOD
        ) {
            periodicSync()
        }
    }

    @Synchronized
    private fun periodicSync() {
        val playerTracker = app.get(PlayerTracker::class)
        val proxies = getProxies().items.mapNotNull { it.metadata?.name }

        proxies.forEach { podName ->
            Puffin.IO.launch {
                val channel = Utils.getChannelToProxy(podName)
                if (channel == null) {
                    logger.warn("Couldn't get channel to proxy $podName!")
                    return@launch
                }
                val stub = PlayerHolderGrpcKt.PlayerHolderCoroutineStub(channel)
                val response = stub.getPlayers(Empty.getDefaultInstance())
                val existingPlayers = playerTracker.getPlayersOnProxy(podName)

                // Set the proxy name of every connected player
                playerTracker.updatePlayers(podName, response)
                val newPlayerList = playerTracker.getPlayersOnProxy(podName)

                if (existingPlayers.size != newPlayerList.size) {
                    logger.info("Player count changed on proxy $podName: ${existingPlayers.size} -> ${newPlayerList.size}")
                }

                // Check if any existing players are not on the 'new players' response.
                // If so, this was a desync issue, and they should be removed.
                val playersToRemove = mutableListOf<UUID>()
                existingPlayers.forEach {
                    if (response.playersList.none { listItem -> listItem.uuid === it.toString() }) {
                        playersToRemove.add(it)
                    }
                }
                if (playersToRemove.isNotEmpty()) {
                    logger.warn("Unregistering ${playersToRemove.size} players which are no longer on proxy $podName: $playersToRemove")
                    playersToRemove.forEach {
                        playerTracker.removePlayer(it)
                    }
                }
            }
        }
    }

    private fun getProxies(): V1PodList =
        api.listNamespacedPod(K8S_NAMESPACE, null, null, null, null, "app=proxy", null, null, null, null, null)

    /**
     * Gets the pod IP address of the specified pod
     */
    fun getProxyIP(podName: String): String? {
        if (DEV_MODE) {
            return DEFAULT_PROXY_IP
        }
        return serverAddresses.get(podName) {
            val pod = api.readNamespacedPod(podName, K8S_NAMESPACE, null)
            pod.status?.podIP
        }
    }

    /**
     * Gets the pod IP address of the proxy that the player is on (null if unknown)
     */
    fun getProxyIP(player: UUID): String? {
        if (DEV_MODE) {
            return DEFAULT_PROXY_IP
        }
        val proxy = app.get(PlayerTracker::class).getPlayer(player)?.proxyPodName ?: return null
        return serverAddresses.get(proxy) {
            val pod = api.readNamespacedPod(proxy, K8S_NAMESPACE, null)
            pod.status?.podIP
        }
    }

    /**
     * Gets the pod IP address of a game server by its name
     * This should be different from the Agones-provided IP
     * address, because it is only accessible from inside the cluster.
     */
    fun getGameServerIP(serverName: String): String? {
        if (DEV_MODE) {
            return DEFAULT_GS_IP
        }
        return serverAddresses.get(serverName) {
            val pod = api.readNamespacedPod(serverName, K8S_NAMESPACE, null)
            pod.status?.podIP
        }
    }
}