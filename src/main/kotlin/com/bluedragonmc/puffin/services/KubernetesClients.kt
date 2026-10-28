package com.bluedragonmc.puffin.services

import com.google.gson.JsonObject
import com.google.inject.Inject
import com.google.inject.Singleton
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.JSON
import io.kubernetes.client.openapi.apis.AppsV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.util.Config

/**
 * Kubernetes API clients.
 */
@Singleton
class KubernetesClients @Inject constructor() {

    val client: ApiClient = Config.defaultClient()

    /** Core v1 API, used to watch and read pods. */
    val coreV1: CoreV1Api = CoreV1Api(client)

    /** Apps v1 API, used to read the game server Deployment. */
    val appsV1: AppsV1Api = AppsV1Api(client)

    /**
     * Serializes a pod using the Kubernetes client's Gson configuration.
     */
    fun podJson(pod: V1Pod): JsonObject = JSON.getGson().toJsonTree(pod).asJsonObject
}
