package com.bluedragonmc.puffin

import com.bluedragonmc.messages.GameType
import com.bluedragonmc.messages.NotifyInstanceCreatedMessage
import com.bluedragonmc.messages.NotifyInstanceRemovedMessage
import com.bluedragonmc.messagingsystem.AMQPClient
import java.util.*

object InstanceManager {
    /**
     * A map of instance IDs to their running game types
     */
    private val instanceTypes = mutableMapOf<UUID, GameType>()

    /**
     * A map of container IDs to a list of instance IDs
     */
    private val containers = mutableMapOf<UUID, MutableSet<UUID>>()

    fun start(client: AMQPClient) {
        client.subscribe(NotifyInstanceCreatedMessage::class) { message ->
            // Add to map of instances to game types
            instanceTypes[message.instanceId] = message.gameType

            // Add to list of containers
            if(!containers.containsKey(message.containerId)) {
                containers[message.containerId] = mutableSetOf()
            }
            containers[message.containerId]!!.add(message.instanceId)

            Queue.update()

            // Check if this was the instance requested to start by the Queue system
            val m = message.gameType
            val q = Queue.startingInstance ?: return@subscribe
            if(m.name == q.name && (q.mode == null || m.mode == q.mode) && (q.mapName == null || m.mapName == q.mapName)) {
                Queue.startingInstance = null
            }
        }
        client.subscribe(NotifyInstanceRemovedMessage::class) { message ->
            containers[message.containerId]!!.remove(message.instanceId)
        }
    }

    fun getGameType(instanceId: UUID) = instanceTypes[instanceId]
    fun findInstancesOfType(gameType: GameType, matchMapName: Boolean = false, matchGameMode: Boolean = true): Map<UUID, GameType> {
        return instanceTypes.filter { (_, type) ->
            type.name == gameType.name && (!matchMapName || type.mapName == gameType.mapName) && (!matchGameMode || type.mode == gameType.mode)
        }
    }
    fun findContainerWithLeastInstances() = containers.minByOrNull { (_, instances) -> instances.size }
}