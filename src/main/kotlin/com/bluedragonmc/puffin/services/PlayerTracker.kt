package com.bluedragonmc.puffin.services

import com.bluedragonmc.messages.PlayerLogoutMessage
import com.bluedragonmc.messages.QueryPlayerMessage
import com.bluedragonmc.messages.SendPlayerToInstanceMessage
import com.bluedragonmc.messagingsystem.message.RPCErrorMessage
import kotlinx.coroutines.runBlocking
import java.util.UUID

class PlayerTracker(app: ServiceHolder) : Service(app) {

    private val playerLocations = mutableMapOf<UUID, UUID>()

    override fun initialize() {
        val client = app.get(MessagingService::class).client

        client.subscribe(SendPlayerToInstanceMessage::class) { message ->
            playerLocations[message.player] = message.instance
        }

        client.subscribe(PlayerLogoutMessage::class) { message ->
            if (playerLocations.remove(message.player) == null) logger.warn("Player logged out without a recorded instance: uuid=${message.player}")
        }

        client.subscribeRPC(QueryPlayerMessage::class) { message ->
            return@subscribeRPC if (message.playerUUID != null) {
                val playerName = runBlocking { app.get(DatabaseConnection::class).getPlayerName(message.playerUUID!!) }
                val found = playerLocations.containsKey(message.playerUUID)
                QueryPlayerMessage.Response(found, playerName, message.playerUUID)
            } else if (message.playerName != null) {
                val uuid = runBlocking { app.get(DatabaseConnection::class).getPlayerUUID(message.playerName!!) }
                val found = playerLocations.containsKey(uuid)
                QueryPlayerMessage.Response(found, message.playerName, uuid)
            } else RPCErrorMessage("playerUUID and playerName are both null. Can't determine the target player.")
        }
    }

    fun getPlayersInInstance(instanceId: UUID) = playerLocations.filter { it.value == instanceId }.map { it.key }
    fun getInstanceOfPlayer(uuid: UUID) = playerLocations[uuid]

    override fun close() {
        playerLocations.clear()
    }
}