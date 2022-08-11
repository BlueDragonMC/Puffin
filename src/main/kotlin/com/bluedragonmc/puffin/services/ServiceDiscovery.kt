package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.util.Utils.catchingTimer
import io.kubernetes.client.util.Config
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesApi
import io.kubernetes.client.util.generic.dynamic.DynamicKubernetesObject
import java.util.UUID
import java.util.function.Consumer

class ServiceDiscovery(app: ServiceHolder) : Service(app) {

    private val lock = Any()
    private var kubernetesObjects = mutableListOf<DynamicKubernetesObject>()

    private val serverRemovedActions = mutableListOf<Consumer<GameServer>>()
    private val serverAddedActions = mutableListOf<Consumer<GameServer>>()

    fun onServerRemoved(consumer: Consumer<GameServer>) = serverRemovedActions.add(consumer)
    fun onServerAdded(consumer: Consumer<GameServer>) = serverAddedActions.add(consumer)

    val client = DynamicKubernetesApi("agones.dev", "v1", "gameservers", Config.defaultClient())

    override fun initialize() {
        catchingTimer("agones-gameserver-update", daemon = true, initialDelay = 5_000, period = 5_000) {
            val response = client.list()
            if (response.httpStatusCode >= 400) {
                error("Kubernetes returned HTTP error code: ${response.httpStatusCode}: ${response.status?.status}, ${response.status?.message}")
            }
            synchronized(lock) {
                val objects = response.`object`.items
                val removed = kubernetesObjects.filter { !objects.any { o -> o.metadata.uid == it.metadata.uid } }
                val added = objects.filter { !kubernetesObjects.any { o -> o.metadata.uid == it.metadata.uid } }
                removed.forEach(::processServerRemoved)
                added.forEach(::processServerAdded)
                kubernetesObjects = objects
            }
        }
    }

    private fun processServerRemoved(`object`: DynamicKubernetesObject) {
        val gs = GameServer(`object`)
        serverRemovedActions.forEach { it.accept(gs) }
    }

    private fun processServerAdded(`object`: DynamicKubernetesObject) {
        val gs = GameServer(`object`)
        serverAddedActions.forEach { it.accept(gs) }
    }

    /**
     * Makes a defensive copy of the list of servers just in case it is changed while iterating.
     */
    fun getGameServers() = synchronized(lock) { ArrayList(kubernetesObjects) }.map { GameServer(it) }

    override fun close() {}

    data class GameServer(val `object`: DynamicKubernetesObject) {
        private val status = `object`.raw.getAsJsonObject("status")

        val uid: UUID = UUID.fromString(`object`.metadata.uid)
        val address: String = status.get("address").asString
        val port = status.get("ports").asJsonArray.first { p ->
            p.asJsonObject.get("name").asString == "minecraft"
        }.asJsonObject.get("port").asInt
        val name = `object`.metadata.name
    }

}
