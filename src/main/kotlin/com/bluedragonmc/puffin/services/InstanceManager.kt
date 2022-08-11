package com.bluedragonmc.puffin.services

import com.bluedragonmc.messages.*
import java.util.*

class InstanceManager(app: ServiceHolder) : Service(app) {

    /**
     * A map of instance IDs to their running game types
     */
    private val instanceTypes = mutableMapOf<UUID, GameType>()

    /**
     * A map of container IDs to a list of instance IDs
     */
    private val containers = mutableMapOf<UUID, MutableSet<UUID>>()

    private fun handleInstanceCreated(message: NotifyInstanceCreatedMessage) {
        val queue = app.get(Queue::class)
        // Add to map of instances to game types
        instanceTypes[message.instanceId] = message.gameType

        // Add to list of containers
        if (!containers.containsKey(message.containerId)) {
            logger.warn("Instance was created without a PingMessage sent first. containerId=${message.containerId}, instanceId=${message.instanceId}")
            containers[message.containerId] = mutableSetOf()
        }
        containers[message.containerId]!!.add(message.instanceId)

        queue.update()

        // Check if this was the instance requested to start by the Queue system
        val m = message.gameType
        val q = queue.startingInstance ?: return
        if (m.name == q.name && (q.mode == null || m.mode == q.mode) && (q.mapName == null || m.mapName == q.mapName)) {
            logger.info("The instance requested by the Queue system has started: gameType=$m, instanceId=${message.instanceId}, containerId=${message.containerId}")
            queue.startingInstance = null
        }
    }

    private fun handleInstanceRemoved(message: NotifyInstanceRemovedMessage) {
        containers[message.containerId]?.remove(message.instanceId)
        instanceTypes.remove(message.instanceId)
    }

    fun findInstancesOfType(
        gameType: GameType, matchMapName: Boolean = false, matchGameMode: Boolean = false,
    ): Map<UUID, GameType> {
        return instanceTypes.filter { (_, type) ->
            type.name == gameType.name &&
                    (!matchMapName || (gameType.mapName == null || type.mapName == gameType.mapName)) &&
                    (!matchGameMode || (gameType.mode == null || type.mode == gameType.mode))
        }
    }

    fun findGameServerWithLeastInstances() = containers.minByOrNull { (_, instances) -> instances.size }

    override fun initialize() {

        val client = app.get(MessagingService::class).client

        val sd = app.get(ServiceDiscovery::class)
        sd.onServerAdded { server ->
            logger.info("New GameServer found with name ${server.name} and UID ${server.uid}")
            containers.putIfAbsent(server.uid, mutableSetOf())
        }
        sd.onServerRemoved { server ->
            logger.info("GameServer with name ${server.name} and UID ${server.uid} was removed.")
            val instances = containers[server.uid] ?: emptySet()
            containers.remove(server.uid)
            instanceTypes.entries.removeAll { it.key in instances }
        }

        client.subscribe(PingMessage::class) { message ->
            logger.info("New container started (received ping from Minestom server) - ${message.containerId}")
            containers[message.containerId] = mutableSetOf()
        }
        client.subscribe(NotifyInstanceCreatedMessage::class, ::handleInstanceCreated)
        client.subscribe(NotifyInstanceRemovedMessage::class, ::handleInstanceRemoved)
        client.subscribe(ServerSyncMessage::class) { message ->
            val instances = message.instances.toMutableSet()
            val current = containers[message.containerId] ?: return@subscribe
            val added = instances.filter { !current.contains(it.instanceId) }
            val removed = current.filter { !instances.any { i -> i.instanceId == it } }

            instances.forEach {
                instanceTypes[it.instanceId] = it.type ?: GameType("unknown")
            }

            added.forEach {
                logger.info("Instance added via ServerSyncMessage: $it")
                handleInstanceCreated(NotifyInstanceCreatedMessage(message.containerId,
                    it.instanceId,
                    it.type ?: GameType("unknown")))
            }
            removed.forEach {
                logger.info("Instance removed via ServerSyncMessage: $it")
                handleInstanceRemoved(NotifyInstanceRemovedMessage(message.containerId, it))
            }
        }
    }

    override fun close() {}
}