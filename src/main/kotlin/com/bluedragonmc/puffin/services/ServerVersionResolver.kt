package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.app.PuffinConfig
import com.google.inject.Inject
import com.google.inject.Singleton
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodSpec
import org.slf4j.LoggerFactory

/**
 * Resolves the version that a game server is currently running and the most up-to-date version.
 *
 * Game servers whose running generation differs from their desired generation are considered
 * out of date and are drained so that they can eventually be replaced.
 */
interface ServerVersionResolver {

    /**
     * (Re)loads the currently desired state from the Kubernetes API.
     */
    fun refresh()

    /**
     * The generation that [server] is currently running, or `null` if it is unknown.
     */
    fun runningGeneration(server: V1Pod): String?

    /**
     * The generation that game servers are expected to run, or `null` if it is unknown.
     */
    fun desiredGeneration(): String?
}

/**
 * A [ServerVersionResolver] backed by the game server Deployment.
 *
 * A game server's generation is the image of its game server container, and the desired
 * generation is the image configured on the Deployment that owns it.
 */
@Singleton
class DeploymentVersionResolver @Inject constructor(
    private val kubernetesClients: KubernetesClients,
    private val config: PuffinConfig,
) : ServerVersionResolver {

    private val logger = LoggerFactory.getLogger(DeploymentVersionResolver::class.java)

    @Volatile
    private var desiredImage: String? = null

    override fun refresh() {
        try {
            val deployment = kubernetesClients.appsV1
                .readNamespacedDeployment(config.gameServerDeploymentName, config.k8sNamespace)
                .execute()
            desiredImage = firstContainerImage(deployment.spec?.template?.spec)
            logger.debug("Refreshed desired game server image: {}", desiredImage)
        } catch (e: Exception) {
            logger.error(
                "Failed to read Deployment '{}'; outdated servers will not be drained.",
                config.gameServerDeploymentName,
                e,
            )
        }
    }

    override fun runningGeneration(server: V1Pod): String? =
        firstContainerImage(server.spec)

    override fun desiredGeneration(): String? = desiredImage

    private fun firstContainerImage(podSpec: V1PodSpec?): String? {
        val containers = podSpec?.containers ?: return null
        val container = containers.firstOrNull { it.name == config.gameServerContainerName }
            ?: containers.firstOrNull()
        return container?.image
    }
}
