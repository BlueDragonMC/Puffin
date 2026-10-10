package com.bluedragonmc.puffin.services

import com.google.inject.Inject
import com.google.inject.Singleton
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.Config
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi

/**
 * Kubernetes API clients.
 */
@Singleton
class KubernetesClients @Inject constructor() {

    private val client = Config.defaultClient()

    /** Core v1 API, used to read pods. */
    val coreV1: CoreV1Api = CoreV1Api(client)

    /** Agones game servers. */
    val gameServers: DynamicKubernetesApi = DynamicKubernetesApi("agones.dev", "v1", "gameservers", client)

    /** Agones fleets. */
    val fleets: DynamicKubernetesApi = DynamicKubernetesApi("agones.dev", "v1", "fleets", client)
}
