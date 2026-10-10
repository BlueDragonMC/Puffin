package com.bluedragonmc.puffin.services

import com.google.inject.Inject
import com.google.inject.Singleton
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject
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
    fun runningGeneration(server: DynamicKubernetesObject): String?

    /**
     * The generation that [server] is expected to be running, or `null` if it is unknown.
     */
    fun desiredGeneration(server: DynamicKubernetesObject): String?
}

/**
 * A [ServerVersionResolver] backed by Agones Fleets.
 *
 * A game server's generation is the image of its game server container, and the desired generation
 * is the image configured on the Fleet that owns it.
 */
@Singleton
class AgonesFleetVersionResolver @Inject constructor(
    private val kubernetesClients: KubernetesClients,
) : ServerVersionResolver {

    private val logger = LoggerFactory.getLogger(AgonesFleetVersionResolver::class.java)

    /**
     * Map of Fleet name to the image that each Fleet currently expects its GameServers to run.
     */
    @Volatile
    private var fleetImages: Map<String, String> = emptyMap()

    override fun refresh() {
        try {
            fleetImages = kubernetesClients.fleets.list().`object`.items
                .mapNotNull { fleet ->
                    val name = fleet.metadata.name ?: return@mapNotNull null
                    val image = desiredImage(fleet) ?: return@mapNotNull null
                    name to image
                }
                .toMap()
            logger.debug("Refreshed desired Fleet images: {}", fleetImages)
        } catch (e: Exception) {
            logger.error("Failed to list Agones Fleets; outdated servers will not be drained.", e)
        }
    }

    override fun runningGeneration(server: DynamicKubernetesObject): String? =
        firstContainerImage(
            server.raw.getAsJsonObject("spec")
                ?.getAsJsonObject("template") // GameServer spec.template (PodTemplateSpec)
                ?.getAsJsonObject("spec")
        )

    override fun desiredGeneration(server: DynamicKubernetesObject): String? {
        val fleetName = server.metadata.labels?.get(FLEET_LABEL) ?: return null
        return fleetImages[fleetName]
    }

    private fun desiredImage(fleet: DynamicKubernetesObject): String? =
        firstContainerImage(
            fleet.raw.getAsJsonObject("spec")
                ?.getAsJsonObject("template") // Fleet spec.template (GameServerTemplateSpec)
                ?.getAsJsonObject("spec")
                ?.getAsJsonObject("template") // GameServer spec.template (PodTemplateSpec)
                ?.getAsJsonObject("spec")
        )

    private fun firstContainerImage(podSpec: com.google.gson.JsonObject?): String? {
        val containers = podSpec?.getAsJsonArray("containers") ?: return null
        val container = containers.firstOrNull { it.asJsonObject.get("name")?.asString == GAME_SERVER_CONTAINER }
            ?: containers.firstOrNull()
        return container?.asJsonObject?.get("image")?.asString
    }

    private companion object {
        const val FLEET_LABEL = "agones.dev/fleet"

        /** The name of the game server container in the Fleet manifest (`minecraft.fleet.yml`). */
        const val GAME_SERVER_CONTAINER = "server"
    }
}
