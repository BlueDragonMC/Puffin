package com.bluedragonmc.puffin

import com.bluedragonmc.messages.*
import com.bluedragonmc.messagingsystem.AMQPClient
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.concurrent.timer

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

    /**
     * A map of container IDs to the last time a ping was received from them
     */
    private val pingTimes = mutableMapOf<UUID, Long>()

    fun start(client: AMQPClient) {
        client.subscribe(PingMessage::class) { message ->
            logger.info("New container started (received confirmation from Minestom server) - ${message.containerId}")
            logger.info("Version info received: ${message.versionInfo}")
            containers[message.containerId] = mutableSetOf()
        }
        client.subscribe(NotifyInstanceCreatedMessage::class, ::handleInstanceCreated)
        client.subscribe(NotifyInstanceRemovedMessage::class, ::handleInstanceRemoved)
        client.subscribe(ServerSyncMessage::class) { message ->
            val instances = message.instances.toMutableSet()
            val current = containers[message.containerId] ?: emptySet()
            val added = instances.filter { !current.contains(it.instanceId) }
            val removed = current.filter { !instances.any { i -> i.instanceId == it } }

            instances.forEach {
                instanceTypes[it.instanceId] = it.type ?: GameType("unknown")
            }

            added.forEach {
                logger.info("Instance added via ServerSyncMessage: $it")
                handleInstanceCreated(NotifyInstanceCreatedMessage(message.containerId, it.instanceId, it.type ?: GameType("unknown")))
            }
            removed.forEach {
                logger.info("Instance removed via ServerSyncMessage: $it")
                handleInstanceRemoved(NotifyInstanceRemovedMessage(message.containerId, it))
            }

            pingTimes[message.containerId] = System.currentTimeMillis()
        }
        timer("instance-ping-timer", daemon = true, period = 10_000) {
            // Remove instances that have not sent a ping in the last 5 minutes.
            pingTimes.forEach { (instanceId, time) ->
                if(System.currentTimeMillis() - time > 300_000) {
                    val containerId = containers.entries.first { (_, instances) -> instances.contains(instanceId) }.key
                    logger.warn("Instance $instanceId on container $containerId has not sent a ping in the last 5 minutes!")
                    logger.warn("Removing instance $instanceId")
                    client.publish(NotifyInstanceRemovedMessage(containerId, instanceId))
                }
            }
        }
    }

    private fun handleInstanceCreated(message: NotifyInstanceCreatedMessage) {
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
        val q = Queue.startingInstance ?: return
        if (m.name == q.name && (q.mode == null || m.mode == q.mode) && (q.mapName == null || m.mapName == q.mapName)) {
            logger.info("The instance requested by the Queue system has started: gameType=$m, instanceId=${message.instanceId}, containerId=${message.containerId}")
            Queue.startingInstance = null
        }

        pingTimes[message.containerId] = System.currentTimeMillis()
    }

    private fun handleInstanceRemoved(message: NotifyInstanceRemovedMessage) {
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