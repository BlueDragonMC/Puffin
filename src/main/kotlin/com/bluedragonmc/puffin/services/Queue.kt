package com.bluedragonmc.puffin.services

import com.bluedragonmc.messages.*
import com.bluedragonmc.puffin.config.ConfigService
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import java.io.File
import java.util.*

class Queue(app: ServiceHolder) : Service(app) {

    private val queue = mutableMapOf<UUID, GameType>()
    private val queueEntranceTimes = mutableMapOf<UUID, Long>()

    internal data class InstanceRequest(
        val svc: Queue,
        val gameType: GameType,
        val requester: UUID,
        var attempts: Int = 0,
    ) {

        private var serverWaitTimer: Timer? = null
        private var stateWaitTimer: Timer? = null

        var isWaitingForServer: Boolean = false
            set(value) {
                field = value
                // When isWaitingForServer is set to true, wait 10 seconds.
                // If the request is not honored by then, the field is set
                // back to false, assuming the server did not honor the request.
                if (value) {
                    serverWaitTimer?.cancel()
                    serverWaitTimer = Timer("server-wait", true)
                    serverWaitTimer?.schedule(object : TimerTask() {
                        override fun run() {
                            isWaitingForServer = false
                            svc.logger.info("Instance request was not honored after 10 seconds: $this")
                        }
                    }, 10_000L)
                } else {
                    serverWaitTimer?.cancel()
                    serverWaitTimer = null
                }
            }

        var instanceId: UUID? = null
        var isWaitingForState: Boolean = false
            set(value) {
                field = value
                // Wait 3 seconds for the server to respond with its game state.
                // If the server doesn't respond, the request will go back into
                // its original state.
                if (value) {
                    stateWaitTimer?.cancel()
                    stateWaitTimer = Timer("game-state-msg-wait", true)
                    stateWaitTimer?.schedule(object : TimerTask() {
                        override fun run() {
                            isWaitingForState = false
                            isWaitingForServer = false
                            svc.logger.info("Game state message was not received within 3 seconds: $this")
                        }
                    }, 3_000L)
                } else {
                    stateWaitTimer?.cancel()
                    stateWaitTimer = null
                }
            }

        fun fulfill(instanceId: UUID) {
            svc.logger.info("Instance request fulfilled with instance ID '$instanceId'.")

            isWaitingForServer = false
            isWaitingForState = true
            this.instanceId = instanceId
        }

        fun complete() {
            svc.logger.info("Game state received from instance with ID '$instanceId'.")
            svc.queue.remove(requester)
            svc.queueEntranceTimes.remove(requester)

            isWaitingForState = false

            synchronized(svc.instanceRequests) {
                svc.instanceRequests.remove(this)
            }

            if (!svc.send(requester, instanceId!!)) {
                Utils.sendChat(requester, "<red><lang:puffin.queue.not_enough_space:'<gray>$instanceId'>")
            }
        }
    }

    internal val instanceRequests = mutableListOf<InstanceRequest>()

    fun update() {

        val instanceManager = app.get(InstanceManager::class)
        val gameStateManager = app.get(GameStateManager::class)

        synchronized(instanceRequests) {
            val requests = instanceRequests.filter { request ->
                request.isWaitingForState && gameStateManager.hasState(request.instanceId!!)
            }
            requests.forEach(InstanceRequest::complete)
        }

        queue.entries.removeAll { (player, gameType) ->
            val instances = instanceManager.findInstancesOfType(gameType, matchMapName = true, matchGameMode = false)
            if (instances.isNotEmpty()) {
                logger.info("Found ${instances.size} instances of game type: $gameType")

                val party = app.get(PartyManager::class).partyOf(player)
                val playerSlotsRequired = party?.members?.size ?: 1
                val (best, _) = instances.keys.associateWith {
                    gameStateManager.getEmptySlots(it)
                }.filter { it.value > 0 }.entries.minByOrNull { it.value } ?: run {
                    logger.info("All instances of type $gameType have less than $playerSlotsRequired player slots.")
                    return@removeAll false
                }

                logger.info("Instance with least empty slots for $gameType: $best")
                if (party != null && party.leader != player) {
                    // Player is queued as a party member, not a leader
                    queueEntranceTimes.remove(player)
                    return@removeAll true
                }
                if (gameStateManager.getEmptySlots(best) >= playerSlotsRequired) {
                    // There is enough space in the instance for this player and their party (if they're in one)
                    // Send the player to this instance and remove them from the queue
                    logger.info("Sending player $player to existing instance of type $gameType: $best")
                    queueEntranceTimes.remove(player)
                    send(player, best)
                    return@removeAll true
                }
            } else logger.info("No instances found of type $gameType.")
            return@removeAll false
        }
        queue.entries.forEach { (player, gameType) ->
            Utils.sendChat(player, "<p1>Waiting for instance...", ChatType.ACTION_BAR)
            synchronized(instanceRequests) {
                // Don't make duplicate instance requests
                if (instanceRequests.any { it.gameType == gameType }) return@forEach
                logger.info("Starting a new instance for player $player with game type: $gameType.")
                // Create a new instance for the first player in the queue.
                instanceRequests.add(InstanceRequest(this, gameType, player))
            }
        }
        processInstanceRequests()
    }

    private var lastInstanceRequest: Long = 0L

    private fun processInstanceRequests() {
        // Hard throttle all instance requests combined to one per 2 seconds.
        if (System.currentTimeMillis() - lastInstanceRequest < 2000) return

        val request = synchronized(instanceRequests) {
            instanceRequests.filter { !it.isWaitingForServer && !it.isWaitingForState }.minByOrNull { it.attempts }
        }
        if (request != null) {
            lastInstanceRequest = System.currentTimeMillis()

            val instanceManager = app.get(InstanceManager::class)
            val client = app.get(MessagingService::class).client

            val (gameServer, _) = instanceManager.findGameServerWithLeastInstances() ?: return
            logger.info("Creating instance on server '$gameServer' from request $request.")

            Utils.sendChat(request.requester, "<p2>Creating instance...", ChatType.ACTION_BAR)
            client.publish(RequestCreateInstanceMessage(gameServer, request.gameType))
            request.isWaitingForServer = true
            request.attempts++
        }

        // Give up to three attempts before disregarding the request.
        instanceRequests.removeAll {
            if (it.attempts > 3) {
                logger.warn("Removed instance request $it because it failed after 3 attempts.")
                queue.remove(it.requester)
                queueEntranceTimes.remove(it.requester)
                Utils.sendChat(it.requester,
                    "<red>Failed to create instance! Please try again in a few minutes.",
                    ChatType.ACTION_BAR)
                true
            } else false
        }
    }

    private fun send(player: UUID, instanceId: UUID): Boolean {

        val client = app.get(MessagingService::class).client
        val gameStateManager = app.get(GameStateManager::class)
        val party = app.get(PartyManager::class).partyOf(player)

        val emptySlots = gameStateManager.getEmptySlots(instanceId)
        val requiredSlots = party?.members?.size ?: 1

        if (emptySlots < requiredSlots) {
            logger.info("Attempted to send $requiredSlots player(s) to instance $instanceId, but it only has $emptySlots empty slots.")
            return false
        }

        if (party != null) {
            logger.info("Sending party of player $player (${party.members.size} members) to instance $instanceId.")
            party.members.forEach { member ->
                // Warp in the player's party when they are warped into a game
                client.publish(SendPlayerToInstanceMessage(member, instanceId))
            }
        } else {
            logger.info("Sending player $player to instance $instanceId.")
            client.publish(SendPlayerToInstanceMessage(player, instanceId))
        }

        return true
    }

    override fun initialize() {

        val client = app.get(MessagingService::class).client
        val db = app.get(DatabaseConnection::class)
        val config = app.get(ConfigService::class).config

        client.subscribe(RequestAddToQueueMessage::class) { message ->
            val mapName = message.gameType.mapName
            val gameSpecificMapFolder = File(File(config.worldsFolder), message.gameType.name)
            if ((mapName != null && db.getMapInfo(mapName) == null) || // No entry for the map in the database
                (mapName == null && (!gameSpecificMapFolder.exists() || gameSpecificMapFolder.list()
                    ?.isNotEmpty() == false)) // No world folder found
            ) {
                Utils.sendChat(message.player,
                    "<red><lang:queue.adding.failed:'${message.gameType.name}':'<dark_gray><lang:queue.adding.failed.invalid_map>'>")
            } else {
                logger.info("${message.player} added to queue for ${message.gameType}")
                queue[message.player] = message.gameType
                queueEntranceTimes[message.player] = System.currentTimeMillis()
                Utils.sendChat(message.player, "<p1><lang:queue.added.game:'${message.gameType.name}'>")
                update()
            }
        }

        client.subscribe(RequestRemoveFromQueueMessage::class) { message ->
            queueEntranceTimes.remove(message.player)
            if (queue.remove(message.player) != null) {
                logger.info("${message.player} removed from queue")
                Utils.sendChat(message.player, "<red><lang:queue.removed>")
            }
        }

        catchingTimer("queue-update", daemon = true, initialDelay = 10_000, period = 5_000) {
            // Manually update the queue every 5 seconds in case of a messaging failure or unexpected delay
            update()

            // Remove players from the queue if they've been in it for a long time
            queueEntranceTimes.entries.removeAll { (uuid, time) ->
                if (System.currentTimeMillis() - time > 30_000) {
                    Utils.sendChat(uuid,
                        "<red><lang:queue.removed.reason:'<dark_gray><lang:queue.removed.reason.timeout>'>")
                    queue.remove(uuid)
                    return@removeAll true
                }
                return@removeAll false
            }
        }
    }

    override fun close() {}
}