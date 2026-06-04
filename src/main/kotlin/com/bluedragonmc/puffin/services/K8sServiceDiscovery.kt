package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.GsClientServiceGrpcKt
import com.bluedragonmc.api.grpc.PlayerHolderGrpcKt
import com.bluedragonmc.puffin.app.Env
import com.bluedragonmc.puffin.app.Env.DEFAULT_GS_IP
import com.bluedragonmc.puffin.app.Env.DEFAULT_PROXY_IP
import com.bluedragonmc.puffin.app.Env.DEV_MODE
import com.bluedragonmc.puffin.app.Env.GS_GRPC_PORT
import com.bluedragonmc.puffin.app.Env.K8S_NAMESPACE
import com.bluedragonmc.puffin.app.Env.PROXY_GRPC_PORT
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.channelTo
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1PodList
import io.kubernetes.client.util.Config
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.*

interface IK8sServiceDiscovery {
    /**
     * Gets the pod IP address of the specified pod
     */
    fun getProxyIP(podName: String): String?

    /**
     * Gets the pod IP address of the proxy that the player is on (null if unknown)
     */
    fun getProxyIP(player: UUID): String?

    /**
     * Gets the pod IP address of a game server by its name
     * This should be different from the Agones-provided IP
     * address, because it is only accessible from inside the cluster.
     */
    fun getGameServerIP(serverName: String): String?
    fun getAllProxies(): List<String>
    fun getStubToServer(serverName: String): GsClientServiceGrpcKt.GsClientServiceCoroutineStub?
    fun getChannelToServer(serverName: String): ManagedChannel?
    fun getChannelToProxyOf(player: UUID): ManagedChannel?
    fun getChannelToProxy(proxyPodName: String): ManagedChannel?

    fun periodicSync()
}

/**
 * Uses the Kubernetes API to list proxies and their cluster IP addresses
 */
@Singleton
class K8sServiceDiscovery @Inject constructor(val playerTracker: IPlayerTracker) : Service(), IK8sServiceDiscovery {

    private val api: CoreV1Api

    private val serverAddresses = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(60))
        .expireAfterAccess(Duration.ofMinutes(60))
        .build<String, String?>()

    init {
        val client = Config.defaultClient()
        Configuration.setDefaultApiClient(client)

        api = CoreV1Api()

        // Kubernetes isn't expected in development mode
        if (!DEV_MODE) {
            Utils.catchingTimer(
                "K8sServiceDiscovery Periodic Sync",
                daemon = true,
                initialDelay = Env.K8S_SYNC_PERIOD,
                period = Env.K8S_SYNC_PERIOD
            ) {
                periodicSync()
            }
        }
    }

    private var proxyPodNames = listOf<String>()

    @Synchronized
    override fun periodicSync() {
        val playerTracker = playerTracker
        proxyPodNames = getProxies().items.mapNotNull { it.metadata?.name }

        proxyPodNames.forEach { podName ->
            Puffin.IO.launch {
                val channel = getChannelToProxy(podName)
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
            return api.listNamespacedPod(K8S_NAMESPACE).labelSelector("app=proxy").execute()
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
    override fun getProxyIP(podName: String): String? {
        if (DEV_MODE) {
            return DEFAULT_PROXY_IP
        }
        return serverAddresses.get(podName) {
            val pod = api.readNamespacedPod(podName, K8S_NAMESPACE).execute()
            pod.status?.podIP
        }
    }

    /**
     * Gets the pod IP address of the proxy that the player is on (null if unknown)
     */
    override fun getProxyIP(player: UUID): String? {
        if (DEV_MODE) {
            return DEFAULT_PROXY_IP
        }
        val proxy = playerTracker.getPlayer(player)?.proxyPodName ?: return null
        return serverAddresses.get(proxy) {
            val pod = api.readNamespacedPod(proxy, K8S_NAMESPACE).execute()
            pod.status?.podIP
        }
    }

    /**
     * Gets the pod IP address of a game server by its name
     * This should be different from the Agones-provided IP
     * address, because it is only accessible from inside the cluster.
     */
    override fun getGameServerIP(serverName: String): String? {
        if (DEV_MODE) {
            return DEFAULT_GS_IP
        }
        return serverAddresses.get(serverName) {
            val pod = api.readNamespacedPod(serverName, K8S_NAMESPACE).execute()
            pod.status?.podIP
        }
    }

    override fun getAllProxies(): List<String> = proxyPodNames


    override fun getStubToServer(serverName: String): GsClientServiceGrpcKt.GsClientServiceCoroutineStub? {
        return GsClientServiceGrpcKt.GsClientServiceCoroutineStub(
            getChannelToServer(serverName) ?: return null
        )
    }


    override fun getChannelToServer(serverName: String): ManagedChannel? {
        logger.debug("Getting gRPC channel to game server with name: '$serverName'")
        val addr = getGameServerIP(serverName) ?: run {
            logger.warn("Failed to get server address for game server '$serverName' (Can't get gRPC channel to the server)")
            return null
        }
        return Utils.channelTo(addr, Env.GS_GRPC_PORT)
    }

    override fun getChannelToProxyOf(player: UUID): ManagedChannel? =
        getProxyIP(player)?.let { address ->
            return Utils.channelTo(address, PROXY_GRPC_PORT)
        }

    override fun getChannelToProxy(proxyPodName: String): ManagedChannel? {
        logger.debug("Getting gRPC channel to proxy with name: '$proxyPodName'")
        val addr = getProxyIP(proxyPodName) ?: run {
            logger.warn("Failed to get server address for proxy '$proxyPodName' (Can't get gRPC channel to the server)")
            return null
        }
        return Utils.channelTo(addr, PROXY_GRPC_PORT)
    }
}
