package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.GsClientServiceGrpcKt
import com.bluedragonmc.api.grpc.PlayerHolderGrpcKt
import com.bluedragonmc.api.grpc.PlayerHolderOuterClass
import com.bluedragonmc.puffin.app.ApplicationScope
import com.bluedragonmc.puffin.app.PuffinConfig
import com.bluedragonmc.puffin.util.GrpcChannels
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
import io.grpc.ManagedChannel
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.models.V1PodList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration

interface IK8sServiceDiscovery {
    /**
     * Gets the pod IP address of the specified pod
     */
    suspend fun getProxyIP(podName: String): String?

    /**
     * Gets the pod IP address of a game server by its name
     * This should be different from the Agones-provided IP
     * address, because it is only accessible from inside the cluster.
     */
    suspend fun getGameServerIP(serverName: String): String?
    fun getAllProxies(): List<String>
    suspend fun getStubToServer(serverName: String): GsClientServiceGrpcKt.GsClientServiceCoroutineStub?
    suspend fun getChannelToServer(serverName: String): ManagedChannel?
    suspend fun getChannelToProxy(proxyPodName: String): ManagedChannel?

    suspend fun periodicSync()

    /**
     * Registers a listener that is notified with each proxy's full player list
     * every time [periodicSync] runs.
     */
    fun registerProxyPlayerListener(
        listener: suspend (podName: String, response: PlayerHolderOuterClass.GetPlayersResponse) -> Unit
    )
}

/**
 * Uses the Kubernetes API to list proxies and their cluster IP addresses
 */
@Singleton
class K8sServiceDiscovery @Inject constructor(
    private val applicationScope: ApplicationScope,
    private val config: PuffinConfig,
    private val grpcChannels: GrpcChannels,
    private val kubernetesClients: KubernetesClients,
) : Service(), IK8sServiceDiscovery {

    private val serverAddresses = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(60))
        .expireAfterAccess(Duration.ofMinutes(60))
        .build<String, String?>()

    override fun start() {
        // Perform an initial sync immediately, then keep it up to date periodically.
        applicationScope.launch { periodicSync() }
        applicationScope.repeatingTask(
            name = "K8sServiceDiscovery Periodic Sync",
            initialDelayMillis = config.k8sSyncPeriod,
            periodMillis = config.k8sSyncPeriod
        ) {
            periodicSync()
        }
    }

    @Volatile
    private var proxyPodNames = listOf<String>()

    private val proxyPlayerListeners =
        mutableListOf<suspend (podName: String, response: PlayerHolderOuterClass.GetPlayersResponse) -> Unit>()

    override fun registerProxyPlayerListener(
        listener: suspend (podName: String, response: PlayerHolderOuterClass.GetPlayersResponse) -> Unit
    ) {
        proxyPlayerListeners.add(listener)
    }

    override suspend fun periodicSync() {
        val podList = withContext(Dispatchers.IO) { getProxies() }
        val proxies = podList.items.mapNotNull { it.metadata?.name }
        proxyPodNames = proxies

        proxies.forEach { podName ->
            applicationScope.launch {
                val channel = getChannelToProxy(podName)
                if (channel == null) {
                    logger.warn("Couldn't get channel to proxy $podName!")
                    return@launch
                }
                val stub = PlayerHolderGrpcKt.PlayerHolderCoroutineStub(channel)
                val response = stub.getPlayers(Empty.getDefaultInstance())
                proxyPlayerListeners.forEach { it(podName, response) }
            }
        }
    }

    private fun getProxies(): V1PodList {
        try {
            return kubernetesClients.coreV1.listNamespacedPod(config.k8sNamespace).labelSelector("app=proxy").execute()
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
    override suspend fun getProxyIP(podName: String): String? {
        return withContext(Dispatchers.IO) {
            serverAddresses.get(podName) {
                val pod = kubernetesClients.coreV1.readNamespacedPod(podName, config.k8sNamespace).execute()
                pod.status?.podIP
            }
        }
    }

    /**
     * Gets the pod IP address of a game server by its name
     * This should be different from the Agones-provided IP
     * address, because it is only accessible from inside the cluster.
     */
    override suspend fun getGameServerIP(serverName: String): String? {
        return withContext(Dispatchers.IO) {
            serverAddresses.get(serverName) {
                val pod = kubernetesClients.coreV1.readNamespacedPod(serverName, config.k8sNamespace).execute()
                pod.status?.podIP
            }
        }
    }

    override fun getAllProxies(): List<String> = proxyPodNames


    override suspend fun getStubToServer(serverName: String): GsClientServiceGrpcKt.GsClientServiceCoroutineStub? {
        return GsClientServiceGrpcKt.GsClientServiceCoroutineStub(
            getChannelToServer(serverName) ?: return null
        )
    }


    override suspend fun getChannelToServer(serverName: String): ManagedChannel? {
        logger.debug("Getting gRPC channel to game server with name: '$serverName'")
        val addr = getGameServerIP(serverName) ?: run {
            logger.warn("Failed to get server address for game server '$serverName' (Can't get gRPC channel to the server)")
            return null
        }
        return grpcChannels.channelTo(addr, config.gsGrpcPort)
    }

    override suspend fun getChannelToProxy(proxyPodName: String): ManagedChannel? {
        logger.debug("Getting gRPC channel to proxy with name: '$proxyPodName'")
        val addr = getProxyIP(proxyPodName) ?: run {
            logger.warn("Failed to get server address for proxy '$proxyPodName' (Can't get gRPC channel to the server)")
            return null
        }
        return grpcChannels.channelTo(addr, config.proxyGrpcPort)
    }
}
