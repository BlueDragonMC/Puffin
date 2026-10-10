package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.*
import com.bluedragonmc.api.grpc.CommonTypes.EnumGameState
import com.bluedragonmc.api.grpc.Queue
import com.bluedragonmc.api.grpc.Queue.GetDestinationRequest
import com.bluedragonmc.api.grpc.Queue.GetDestinationResponse
import com.bluedragonmc.puffin.app.ApplicationScope
import com.bluedragonmc.puffin.app.Env
import com.bluedragonmc.puffin.util.Utils
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
import io.grpc.Deadline
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * The maximum number of games that can be created in one invocation of [IQueueService.processQueue]
 */
private const val MAX_GAMES_PER_CYCLE = 5

/**
 * The number of times [IQueueService.processQueue] can be called within [QUEUE_LOOP_RATE_LIMIT_PERIOD_MS].
 * Calls beyond this rate will wait until a previous call has exited the window before running.
 */
private const val QUEUE_LOOP_RATE_LIMIT = 1

/**
 * The length of the window that the rate limit is enforced on
 */
private const val QUEUE_LOOP_RATE_LIMIT_PERIOD_MS: Long = 1_000

/**
 * The maximum number of queue cycles that a party will remain in the queue for before being removed.
 */
private const val QUEUE_PARTY_MAX_ATTEMPTS = 5

/**
 * Chooses which game servers new games may be created on. Up-to-date (non-draining) servers are
 * preferred; draining servers are only used when every server is draining.
 */
internal fun placementCandidates(servers: List<QueueService.GameServer>): List<QueueService.GameServer> =
    servers.filter { !it.draining }.ifEmpty { servers }

interface IQueueService {
    suspend fun getServers(): List<QueueService.GameServer>
    suspend fun getServer(serverName: String): QueueService.GameServer?
    suspend fun getGame(gameId: String): QueueService.Game?
    suspend fun getServerOfGame(gameId: String): String?
    fun registerInstanceUpdateCallback(cb: (gameId: String) -> Unit)
    suspend fun setGameState(gameId: String, newState: CommonTypes.GameState)
    fun addToQueue(party: QueueService.QueuedParty)
    suspend fun removeFromQueue(player: UUID): Boolean

    suspend fun processQueue()
    suspend fun getBestAvailableInstance(
        gameState: EnumGameState,
        gameType: CommonTypes.GameType,
        partySize: Int
    ): QueueService.Game?

    suspend fun removeServer(name: String)
    suspend fun addServer(name: String)
    suspend fun setServerDraining(name: String, draining: Boolean)
    suspend fun removeGame(serverName: String, gameId: String)
    suspend fun getGamesMatching(gameType: CommonTypes.GameType): List<QueueService.Game>
    suspend fun addGame(serverName: String, gameId: String, gameType: CommonTypes.GameType, gameState: CommonTypes.GameState)
    suspend fun getGames(): List<QueueService.Game>
    fun setDestination(player: UUID, gameId: String)

    suspend fun sendPlayerToInstance(player: UUID, gameId: String)
    val queueService: QueueService.QueueService
    val gameStateService: QueueService.GameStateService
}

@Singleton
class QueueService @Inject constructor(
    val mapService: MapService,
    val partyManager: IPartyManager,
    val playerTracker: IPlayerTracker,
    val k8sServiceDiscovery: IK8sServiceDiscovery,
    val gameServerManager: IGameServerManager,
    val applicationScope: ApplicationScope
) : Service(), IQueueService {

    // The actual data is kept separate from its usages to require that the locking methods be used when accessing it
    private class Data {
        /**
         * A set of parties in the queue, sorted first by decreasing party size
         * and then by how specific their queue request is.
         */
        private val queuedParties =
            TreeSet<QueuedParty>(Comparator.comparingInt<QueuedParty> { -it.players.size }.thenComparingInt {
                var i = 0
                if (it.gameType.hasMapId()) i++
                if (it.gameType.hasMode()) i++
                i
            })

        private val servers = mutableListOf<GameServer>()

        private val queuedPartiesMutex = Mutex()
        private val serversMutex = Mutex()

        suspend inline fun <R> withParties(block: suspend (MutableSet<QueuedParty>) -> R): R =
            queuedPartiesMutex.withLock {
                block(queuedParties)
            }

        suspend inline fun <R> withServers(block: suspend (MutableList<GameServer>) -> R): R = serversMutex.withLock {
            block(servers)
        }
    }

    private val data = Data()

    override suspend fun getServers(): List<GameServer> = data.withServers { servers -> ArrayList(servers) }
    override suspend fun getServer(serverName: String) =
        data.withServers { servers -> servers.find { it.name == serverName } }

    override suspend fun getGame(gameId: String): Game? = data.withServers { servers ->
        for (server in servers) {
            for (game in server.games) {
                if (game.id == gameId) return@withServers game
            }
        }
        null
    }

    override suspend fun getServerOfGame(gameId: String): String? = data.withServers { servers ->
        for (server in servers) {
            for (game in server.games) {
                if (game.id == gameId) return@withServers server.name
            }
        }
        null
    }

    private val instanceUpdateCallbacks = mutableListOf<(gameId: String) -> Unit>()

    override fun registerInstanceUpdateCallback(cb: (gameId: String) -> Unit) {
        instanceUpdateCallbacks.add(cb)
    }

    override suspend fun setGameState(gameId: String, newState: CommonTypes.GameState) {
        var old: Game? = null
        var new: Game? = null
        data.withServers { servers ->
            outer@ for ((i, server) in servers.withIndex()) {
                for (game in server.games) {
                    if (game.id == gameId) {
                        servers[i] =
                            server.copy(games = server.games.map { g ->
                                if (g.id == gameId) {
                                    old = g
                                    new = g.copy(
                                        playerCount = newState.maxSlots - newState.openSlots,
                                        maxPlayers = newState.maxSlots,
                                        state = newState.gameState
                                    )
                                    new
                                } else g
                            })
                        break@outer
                    }
                }
            }
        }
        if (old != new) {
            instanceUpdateCallbacks.forEach { it(gameId) }
            applicationScope.launch { processQueue() }
        }
    }

    override fun addToQueue(party: QueuedParty) {
        applicationScope.launch {
            val anyPlayersAlreadyInTheQueue = data.withParties { parties ->
                parties.any { p ->
                    p.players.any { player ->
                        player in party.players
                    }
                }
            }
            if (anyPlayersAlreadyInTheQueue) {
                return@launch
            }
            val maps = mapService.getAvailableMaps(
                party.gameType.name,
                if (party.gameType.hasMode()) party.gameType.mode else null,
                if (party.gameType.hasMapId()) party.gameType.mapId else null,
                party.players
            )

            if (maps.isEmpty()) {
                // No suitable maps exist for the party; no point in adding them to the queue
                playerTracker.sendChatAsync(
                    party.players,
                    "<red><lang:queue.adding.failed:'${party.gameType.name}':'<lang:queue.adding.failed.invalid_map>'>"
                )
                return@launch
            }

            data.withParties { queuedParties ->
                queuedParties.add(party)
            }

            processQueue()
        }
    }

    override suspend fun removeFromQueue(player: UUID) =
        data.withParties { queuedParties -> queuedParties.removeIf { player in it.players } }

    private suspend fun removeAllParties(shouldRemove: suspend (QueuedParty) -> Boolean) {
        val collection = data.withParties { parties -> ArrayList(parties) }
        val elementsToRemove = coroutineScope {
            collection.map { async { if (shouldRemove(it)) it else null } }
                .awaitAll()
                .filterNotNullTo(mutableSetOf())
        }
        data.withParties { parties ->
            parties.removeAll(elementsToRemove)
        }
    }

    private val processQueueMutex = Mutex()
    private val processQueueRateLimiter = Utils.RollingWindowRateLimiter(
        maxRequests = QUEUE_LOOP_RATE_LIMIT,
        windowMillis = QUEUE_LOOP_RATE_LIMIT_PERIOD_MS
    )

    override suspend fun processQueue() {
        processQueueRateLimiter.rateLimit()
        processQueueMutex.withLock {
            val isEmpty = data.withParties { parties ->
                parties.isEmpty()
            }
            if (isEmpty) {
                return@withLock
            }
            val servers: List<GameServer> = data.withServers { servers -> servers.toList() }
            val jobs = mutableListOf<Job>()
            val games = servers.flatMap { it.games }
            // game id -> effective player count
            val effectivePlayerCounts = mutableMapOf<String, Int>()
            val effectiveGames = ArrayList(games)
            val mapSources = mutableListOf<CommonTypes.MapSource>()

            fun sendPartyToInstance(party: QueuedParty, game: Game) {
                logger.info("Sending queued party $party to game ${game.id}.")
                effectivePlayerCounts[game.id] =
                    effectivePlayerCounts.getOrDefault(game.id, game.playerCount) + party.players.size
                party.players.forEach { player ->
                    jobs += applicationScope.launch {
                        sendPlayerToInstance(player, game.id)
                    }
                }
            }

            removeAllParties { party ->
                party.attempts++
                if (party.attempts > QUEUE_PARTY_MAX_ATTEMPTS) {
                    playerTracker.sendChatAsync(
                        party.players,
                        "<red><lang:queue.removed.reason:'<lang:queue.removed.reason.timeout>'>"
                    )
                    return@removeAllParties true
                }
                val startingGame = effectiveGames.firstOrNull { game ->
                    gameMatches(
                        game,
                        effectivePlayerCounts.getOrDefault(game.id, game.playerCount),
                        EnumGameState.STARTING,
                        party
                    )
                }
                if (startingGame != null) {
                    sendPartyToInstance(party, startingGame)
                    return@removeAllParties true
                }

                val waitingGame = effectiveGames.firstOrNull { game ->
                    gameMatches(
                        game,
                        effectivePlayerCounts.getOrDefault(game.id, game.playerCount),
                        EnumGameState.WAITING,
                        party
                    )
                }
                if (waitingGame != null) {
                    sendPartyToInstance(party, waitingGame)
                    return@removeAllParties true
                }

                val initializingGame = effectiveGames.firstOrNull { game ->
                    gameMatches(
                        game,
                        effectivePlayerCounts.getOrDefault(game.id, game.playerCount),
                        EnumGameState.INITIALIZING,
                        party
                    )
                }
                if (initializingGame != null) {
                    effectivePlayerCounts[initializingGame.id] = effectivePlayerCounts.getOrDefault(
                        initializingGame.id, initializingGame.playerCount
                    ) + party.players.size
                } else {
                    // Find a map that satisfies the party's requests and has all of its players whitelisted
                    val mapSource = mapService.getAvailableMaps(
                        party.gameType.name,
                        if (party.gameType.hasMode()) party.gameType.mode else null,
                        if (party.gameType.hasMapId()) party.gameType.mapId else null,
                        party.players
                    ).randomOrNull()

                    if (mapSource == null) {
                        playerTracker.sendChatAsync(
                            party.players,
                            "<red><lang:queue.removed.reason:'<lang:queue.adding.failed.invalid_map>'>"
                        )
                        return@removeAllParties true
                    }

                    effectiveGames += Game("", party.gameType, party.players.size, 8, EnumGameState.INITIALIZING)
                    mapSources += mapSource
                }
                return@removeAllParties false
            }

            val newGames =
                effectiveGames.takeLast(effectiveGames.size - games.size).take(MAX_GAMES_PER_CYCLE)
            // Prefer servers that are up to date. Only use a draining server when all servers are draining.
            val placementServers = placementCandidates(servers)
            // server id -> number of games on it
            val effectiveGameCounts = mutableMapOf<String, Int>()
            placementServers.forEach { server -> effectiveGameCounts[server.name] = server.games.size }
            newGames.forEachIndexed { i, game ->
                val mapSource = mapSources[i]
                jobs += applicationScope.launch {
                    val id = effectiveGameCounts.minByOrNull { it.value }?.key
                    if (id == null) {
                        logger.warn("No game servers are available to create an instance for ${game.gameType}; will retry.")
                        return@launch
                    }
                    effectiveGameCounts[id] = effectiveGameCounts[id]!! + 1
                    logger.info("Creating instance with game type ${game.gameType} on server $id.")
                    k8sServiceDiscovery.getStubToServer(id)!!
                        .withDeadline(Deadline.after(5, TimeUnit.SECONDS))
                        .createInstance(
                            GsClient.CreateInstanceRequest.newBuilder()
                                .setGame(game.gameType.name)
                                .setMapSource(mapSource)
                                .apply { if (game.gameType.hasMode()) setMode(game.gameType.mode) }
                                .build()
                        )
                }
            }
            jobs.joinAll()
        }
    }

    private suspend fun gameMatches(
        game: Game, effectivePlayerCount: Int, state: EnumGameState, party: QueuedParty
    ): Boolean {
        return game.state == state
                && game.gameType matches party.gameType
                && (game.maxPlayers - effectivePlayerCount) >= party.players.size
                && allPlayersWhitelistedForMap(party.players, game.gameType)
    }

    private suspend fun allPlayersWhitelistedForMap(
        players: Collection<UUID>,
        gameType: CommonTypes.GameType
    ): Boolean {
        val maps = mapService.getAvailableMaps(
            gameType.name,
            if (gameType.hasMode()) gameType.mode else null,
            if (gameType.hasMapId()) gameType.mapId else null,
            players
        )

        return maps.isNotEmpty()
    }

    data class GameServer(
        val name: String, val games: List<Game>, val draining: Boolean = false
    )

    data class Game(
        val id: String,
        val gameType: CommonTypes.GameType,
        val playerCount: Int,
        val maxPlayers: Int,
        val state: EnumGameState,
    ) {
        val emptySlots get() = maxPlayers - playerCount
    }

    data class QueuedParty(
        val players: List<UUID>, val gameType: CommonTypes.GameType
    ) {
        var attempts = 0
    }

    override suspend fun getBestAvailableInstance(
        gameState: EnumGameState,
        gameType: CommonTypes.GameType,
        partySize: Int
    ) =
        data.withServers { servers ->
            servers.flatMap { it.games }
                .filter { game -> game.state == gameState && game.gameType matches gameType && game.emptySlots >= partySize }
                .minByOrNull { it.emptySlots }
        }

    private infix fun CommonTypes.GameType.matches(other: CommonTypes.GameType) =
        name == other.name && (!other.hasMode() || mode == other.mode) && (!other.hasMapId() || mapId == other.mapId)

    override suspend fun removeServer(name: String) {
        data.withServers { servers ->
            servers.removeIf { it.name == name }
        }
        applicationScope.launch { processQueue() }
    }

    override suspend fun addServer(name: String) {
        data.withServers { servers ->
            if (servers.none { it.name == name }) {
                servers.add(GameServer(name, emptyList()))
            }
        }
        applicationScope.launch { processQueue() }
    }

    override suspend fun setServerDraining(name: String, draining: Boolean) {
        data.withServers { servers ->
            for ((i, server) in servers.withIndex()) {
                if (server.name == name && server.draining != draining) {
                    servers[i] = server.copy(draining = draining)
                }
            }
        }
    }

    override suspend fun removeGame(serverName: String, gameId: String) {
        data.withServers { servers ->
            for ((i, server) in servers.withIndex()) {
                if (server.name == serverName) {
                    servers[i] = server.copy(games = server.games.filter { it.id != gameId })
                }
            }
        }
        applicationScope.launch { processQueue() }
    }

    override suspend fun getGamesMatching(gameType: CommonTypes.GameType) =
        data.withServers { servers -> servers.flatMap { it.games.filter { game -> game.gameType matches gameType } } }

    override suspend fun addGame(
        serverName: String,
        gameId: String,
        gameType: CommonTypes.GameType,
        gameState: CommonTypes.GameState
    ) {
        data.withServers { servers ->
            for ((i, server) in servers.withIndex()) {
                if (server.name == serverName) {
                    val newList = server.games.toMutableList()
                    newList.add(
                        Game(
                            gameId,
                            gameType,
                            gameState.maxSlots - gameState.openSlots,
                            gameState.maxSlots,
                            gameState.gameState
                        )
                    )
                    servers[i] = server.copy(games = newList)
                }
            }
        }
        applicationScope.launch { processQueue() }
    }

    override suspend fun getGames() = data.withServers { servers -> servers.flatMap { it.games } }

    override val gameStateService by lazy { GameStateService() }

    inner class GameStateService : GameStateServiceGrpcKt.GameStateServiceCoroutineImplBase() {
        override suspend fun updateGameState(request: ServerTracking.GameStateUpdateRequest): Empty = handleRPC {
            setGameState(request.instanceUuid, request.gameState)
            return Empty.getDefaultInstance()
        }
    }

    private val destinationCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(10))
        .build<UUID, String>()

    override fun setDestination(player: UUID, gameId: String) {
        destinationCache.put(player, gameId)
    }

    override suspend fun sendPlayerToInstance(player: UUID, gameId: String) {
        val currentGameServer = playerTracker.getPlayer(player)?.gameServerName

        val serverName = getServerOfGame(gameId)!!

        val channel = if (currentGameServer != serverName) {
            logger.info("Setting destination of player '$player' to game '$gameId'")
            setDestination(player, gameId)
            k8sServiceDiscovery.getChannelToProxyOf(player) // Send to the proxy if we're routing the player between game servers
        } else {
            playerTracker.getChannelToPlayer(player) // Send directly to the game server if we're routing the player between instances on the same server
        } ?: run {
            logger.warn("Failed to initialize the correct channel to send player $player from $currentGameServer to $serverName/$gameId!")
            return
        }

        val gameServerObj = gameServerManager.getK8sObject(serverName) ?: run {
            logger.warn("No IP/Port was found for server name $serverName! Sending players to this server may not be possible.")
            return
        }
        if (gameServerObj.port == null) {
            logger.warn("Game server with name $serverName was found, but it has no port! Sending players to this server may not be possible.")
            return
        }
        val stub = PlayerHolderGrpcKt.PlayerHolderCoroutineStub(channel)
        stub.sendPlayer(sendPlayerRequest {
            this.playerUuid = player.toString()
            this.serverName = serverName
            this.gameServerIp = gameServerObj.address
            this.gameServerPort = gameServerObj.port!!
            this.instanceId = gameId
        })
    }

    override val queueService by lazy { QueueService() }

    inner class QueueService : QueueServiceGrpcKt.QueueServiceCoroutineImplBase() {
        override suspend fun addToQueue(request: Queue.AddToQueueRequest): Empty = handleRPC {
            val playerUuid = UUID.fromString(request.playerUuid)
            val party = partyManager.partyOf(playerUuid)
            val isLobby = request.gameType.name == Env.LOBBY_GAME_NAME
            if (party != null && party.leader != playerUuid && !isLobby) {
                playerTracker.sendChat(playerUuid, "<red><lang:puffin.party.game_join_disallowed.not_leader>")
                return@handleRPC Empty.getDefaultInstance()
            }

            val queuedPlayers = if (party != null && party.leader != playerUuid) {
                // Non-leader party members may only queue themselves (e.g. to go to the lobby)
                listOf(playerUuid)
            } else {
                party?.getMembers() ?: listOf(playerUuid)
            }
            addToQueue(QueuedParty(queuedPlayers, request.gameType))

            return Empty.getDefaultInstance()
        }

        override suspend fun bulkAddToQueue(request: Queue.BulkAddToQueueRequest): Empty {
            for (request in request.requestsList) {
                val uuid = UUID.fromString(request.playerUuid)
                val party = partyManager.partyOf(uuid)
                if (party == null || party.leader == uuid || request.gameType.name == Env.LOBBY_GAME_NAME) {
                    addToQueue(request)
                }
            }
            return Empty.getDefaultInstance()
        }

        override suspend fun getDestinationGame(request: GetDestinationRequest): GetDestinationResponse = handleRPC {
            val player = UUID.fromString(request.playerUuid)
            val destination = destinationCache.getIfPresent(player)
            return if (destination != null) {
                destinationCache.invalidate(player)
                GetDestinationResponse.newBuilder()
                    .setGameId(destination)
                    .build()
            } else {
                GetDestinationResponse.getDefaultInstance()
            }
        }

        override suspend fun removeFromQueue(request: Queue.RemoveFromQueueRequest): Empty = handleRPC {
            val playerUuid = UUID.fromString(request.playerUuid)
            val party = partyManager.partyOf(playerUuid)
            if (party != null && party.leader != playerUuid) {
                playerTracker.sendChat(playerUuid, "<red><lang:puffin.party.game_join_disallowed.not_leader>")
                return@handleRPC Empty.getDefaultInstance()
            }

            removeFromQueue(playerUuid)
            return Empty.getDefaultInstance()
        }
    }
}