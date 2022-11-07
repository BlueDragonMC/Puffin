package com.bluedragonmc.puffin.services

import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.Config
import java.util.UUID

/**
 * Uses the Kubernetes API to list proxies and their IP cluster addresses
 */
class ProxyServiceDiscovery(app: ServiceHolder) : Service(app) {

    private lateinit var api: CoreV1Api

    override fun initialize() {
        val client = Config.defaultClient()
        Configuration.setDefaultApiClient(client)

        api = CoreV1Api()
    }

    /**
     * Gets the pod IP address of the proxy that the player is on (null if unknown)
     */
    fun getProxyIP(player: UUID): String? {
        val proxy = app.get(PlayerTracker::class).getProxyOfPlayer(player) ?: return null
        val pod = api.readNamespacedPod(proxy, "default", null)
        return pod.status?.podIP
    }
}