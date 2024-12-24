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
import io.kubernetes.client.openapi.ApiException
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

    private var proxyPodNames = listOf<String>()

    @Synchronized
    private fun periodicSync() {
        val playerTracker = app.get(PlayerTracker::class)
        proxyPodNames = getProxies().items.mapNotNull { it.metadata?.name }

        proxyPodNames.forEach { podName ->
            Puffin.IO.launch {
                val channel = Utils.getChannelToProxy(podName)
                if (channel == null) {
                    logger.warn("Couldn't get channel to proxy $podName!")
                    return@launch
                }
                val stub = PlayerHolderGrpcKt.PlayerHolderCoroutineStub(channel)
                val response = stub.getPlayers(Empty.getDefaultInstance())

                // Set the proxy name of every connected player
                playerTracker.updateProxyPlayers(podName, response)
            }
        }
    }

    private fun getProxies(): V1PodList {
        try {
            return api.listNamespacedPod(
                K8S_NAMESPACE,
                null,
                null,
                null,
                null,
                "app=proxy",
                null,
                null,
                null,
                null,
                null
            )
        } catch (e: ApiException) {
            logger.error("There was an error while listing proxy pods!")
            logger.error("HTTP status code: ${e.code}")
            logger.error("HTTP response body:\n${e.responseBody}")
            throw e
        }
    }

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

    fun getAllProxies(): List<String> = proxyPodNames
}