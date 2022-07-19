package com.bluedragonmc.puffin

import com.bluedragonmc.messages.GameType
import com.bluedragonmc.messages.NotifyInstanceCreatedMessage
import com.bluedragonmc.messages.NotifyInstanceRemovedMessage
import com.bluedragonmc.messages.PingMessage
import com.bluedragonmc.messagingsystem.AMQPClient
import org.slf4j.LoggerFactory
import java.util.*

object InstanceManager {

    private val logger = LoggerFactory.getLogger(InstanceManager::class.java)

    /**
     * A map of instance IDs to their running game types
     */
    private val instanceTypes = mutableMapOf<UUID, GameType>()

    /**
     * A map of container IDs to a list of instance IDs
     */
    private val containers = mutableMapOf<UUID, MutableSet<UUID>>()

    fun start(client: AMQPClient) {
        client.subscribe(PingMessage::class) { message ->
            logger.info("New container started (received confirmation from Minestom server) - ${message.containerId}")
            logger.info("Version info received: ${message.versionInfo}")
            containers[message.containerId] = mutableSetOf()
        }
        client.subscribe(NotifyInstanceCreatedMessage::class) { message ->
            // Add to map of instances to game types
            instanceTypes[message.instanceId] = message.gameType

            // Add to list of containers
            if (!containers.containsKey(message.containerId)) {
                logger.warn("Instance was created without a PingMessage sent first. containerId=${message.containerId}, instanceId=${message.instanceId}")
                containers[message.containerId] = mutableSetOf()
            }
            containers[message.containerId]!!.add(message.instanceId)

            Queue.update()

            // Check if this was the instance requested to start by the Queue system
            val m = message.gameType
            val q = Queue.startingInstance ?: return@subscribe
            if (m.name == q.name && (q.mode == null || m.mode == q.mode) && (q.mapName == null || m.mapName == q.mapName)) {
                logger.info("The instance requested by the Queue system has started: gameType=$m, instanceId=${message.instanceId}, containerId=${message.containerId}")
                Queue.startingInstance = null
            }
        }
        client.subscribe(NotifyInstanceRemovedMessage::class) { message ->
            containers[message.containerId]?.remove(message.instanceId)
            instanceTypes.remove(message.instanceId)

            if(containers[message.containerId]!!.isEmpty()) {
                // The removed instance was the container's last instance; it is currently not running any instances.
                // Check if the container can be updated to a more recent version.
                DockerContainerManager.getRunningContainer(message.containerId)?.let { container ->
                    if(!DockerContainerManager.isLatestVersion(container, "Server")) {
                        logger.info("Removing container ${container.names.first()} because it is running an outdated version.")
                        DockerContainerManager.removeContainer(container)
                    }
                }
            }
        }
    }

    fun getGameType(instanceId: UUID) = instanceTypes[instanceId]
    fun findInstancesOfType(
        gameType: GameType, matchMapName: Boolean = false, matchGameMode: Boolean = false
    ): Map<UUID, GameType> {
        return instanceTypes.filter { (_, type) ->
            type.name == gameType.name &&
                    (!matchMapName || (gameType.mapName == null || type.mapName == gameType.mapName)) &&
                    (!matchGameMode || (gameType.mode == null || type.mode == gameType.mode))
        }
    }

    fun findContainerWithLeastInstances() = containers.minByOrNull { (_, instances) -> instances.size }
    fun onContainerRemoved(name: String) {
        val uuid = UUID.fromString(name)
        val localInstances = containers[uuid]
        containers.remove(UUID.fromString(name))
        localInstances?.forEach { instanceTypes.remove(it) }
    }
}