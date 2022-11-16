package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.PlayerHolderGrpcKt
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.protobuf.Empty
import io.grpc.ManagedChannelBuilder
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.util.Config
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID

/**
 * Uses the Kubernetes API to list proxies and their IP cluster addresses
 */
class K8sServiceDiscovery(app: ServiceHolder) : Service(app) {

    companion object {
        val NAMESPACE = System.getProperty("PUFFIN_K8S_NAMESPACE") ?: "default"
    }

    private lateinit var api: CoreV1Api

    private val serverAddresses = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(30))
        .expireAfterAccess(Duration.ofMinutes(30))
        .build<String, String>()

    override fun initialize() {
        val client = Config.defaultClient()
        Configuration.setDefaultApiClient(client)

        api = CoreV1Api()

        // Get players on all proxies and start tracking them when the app starts up
        val proxies = getProxies()
        proxies.items.forEach { pod ->
            val ip = pod.status?.podIP ?: return@forEach
            val name = pod.metadata?.name ?: return@forEach
            val channel = ManagedChannelBuilder.forAddress(ip, 50051).usePlaintext().build()
            val stub = PlayerHolderGrpcKt.PlayerHolderCoroutineStub(channel)
            val response = runBlocking { stub.getPlayers(Empty.getDefaultInstance()) }
            channel.shutdown()
            app.get(PlayerTracker::class).updatePlayers(name, response)
            logger.info("Found ${response.playersCount} players on proxy $name")
        }
    }

    private fun getProxies(): V1PodList =
        api.listNamespacedPod(NAMESPACE, null, null, null, null, "app=proxy", null, null, null, null, null)

    /**
     * Gets the pod IP address of the proxy that the player is on (null if unknown)
     */
    fun getProxyIP(player: UUID): String? {
        val proxy = app.get(PlayerTracker::class).getProxyOfPlayer(player) ?: return null
        return serverAddresses.get(proxy) {
            val pod = api.readNamespacedPod(proxy, NAMESPACE, null)
            pod.status?.podIP
        }
    }

    /**
     * Gets the pod IP address of a game server by its name
     * This should be different from the Agones-provided IP
     * address, because it is only accessible from inside the cluster.
     */
    fun getGameServerIP(serverName: String): String? {
        return serverAddresses.get(serverName) {
            val pod = api.readNamespacedPod(serverName, NAMESPACE, null)
            pod.status?.podIP
        }
    }
}