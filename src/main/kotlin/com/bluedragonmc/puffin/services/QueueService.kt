package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes
import com.bluedragonmc.api.grpc.CommonTypes.EnumGameState
import com.bluedragonmc.api.grpc.GsClient
import com.bluedragonmc.api.grpc.PlayerHolderGrpcKt
import com.bluedragonmc.api.grpc.sendPlayerRequest
import com.bluedragonmc.puffin.app.ApplicationScope
import com.bluedragonmc.puffin.app.PuffinConfig
import com.bluedragonmc.puffin.util.LogContext
import com.bluedragonmc.puffin.util.Utils
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.inject.Inject
import com.google.inject.Singleton
import io.grpc.Deadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
 * The maximum number of failed game creation attempts before a party is removed from the queue.
 */
private const val MAX_GAME_CREATION_ATTEMPTS = 3

/** The minimum delay before retrying a failed game creation. */
private const val GAME_CREATION_RETRY_BASE_DELAY_MS = 3_000L

/** The maximum delay before retrying a failed game creation. */
private const val GAME_CREATION_RETRY_MAX_DELAY_MS = 30_000L

/** The amount of time to wait between sending game creation requests for the same party. */
private const val GAME_CREATION_WAIT_MS = 10_000L

/** How long a party may remain in the queue before it is removed. */
private const val QUEUE_TIMEOUT_MS = 60_000L

/**
 * Chooses which game servers new games may be created on.
 *
 * Not-ready and terminating servers are never candidates. Up-to-date (non-draining) servers are
 * preferred; draining servers are only used when every usable server is draining.
 */
internal fun placementCandidates(servers: List<QueueServer>): List<QueueServer> {
    val usable = servers.filter { it.ready && !it.terminating }
    return usable.filter { !it.draining }.ifEmpty { usable }
}

interface IQueueService {
    suspend fun getServers(): List<QueueServer>
    suspend fun getServer(serverName: String): QueueServer?
    suspend fun getGame(gameId: String): Game?
    suspend fun getServerOfGame(gameId: String): String?
    fun registerInstanceUpdateCallback(cb: (gameId: String) -> Unit)
    suspend fun setGameState(gameId: String, newState: CommonTypes.GameState)
    fun addToQueue(party: QueuedParty)
    suspend fun removeFromQueue(player: UUID): Boolean

    suspend fun processQueue()

    suspend fun removeServer(name: String)
    suspend fun addServer(
        name: String,
        address: String?,
        port: Int?,
        ready: Boolean = true,
        terminating: Boolean = false,
    )
    suspend fun setServerDraining(name: String, draining: Boolean)
    suspend fun removeGame(serverName: String, gameId: String)
    suspend fun getGamesMatching(gameType: CommonTypes.GameType): List<Game>
    suspend fun addGame(serverName: String, gameId: String, gameType: CommonTypes.GameType, gameState: CommonTypes.GameState)
    suspend fun getGames(): List<Game>
    fun setDestination(player: UUID, gameId: String)

    suspend fun sendPlayerToInstance(player: UUID, gameId: String)
    fun consumeDestination(player: UUID): String?
}

@Singleton
class QueueService @Inject constructor(
    private val mapService: IMapService,
    private val playerTracker: IPlayerTracker,
    private val k8sServiceDiscovery: IK8sServiceDiscovery,
    private val config: PuffinConfig,
    private val applicationScope: ApplicationScope
) : Service(), IQueueService {

    // The actual data is kept separate from its usages to require that the locking methods be used when accessing it
    private class Data {
        /**
         * A set of parties in the queue, sorted first by decreasing party size
         * and then by how specific their queue request is.
         */
        private val queuedParties =
            TreeSet(Comparator.comparingInt<QueuedParty> { -it.players.size }.thenComparingInt {
                var i = 0
                if (it.gameType.hasMapId()) i++
                if (it.gameType.hasMode()) i++
                i
            })

        private val servers = mutableListOf<QueueServer>()

        private val queuedPartiesMutex = Mutex()
        private val serversMutex = Mutex()

        suspend inline fun <R> withParties(block: suspend (MutableSet<QueuedParty>) -> R): R =
            queuedPartiesMutex.withLock {
                block(queuedParties)
            }

        suspend inline fun <R> withServers(block: suspend (MutableList<QueueServer>) -> R): R = serversMutex.withLock {
            block(servers)
        }
    }

    private val data = Data()

    override fun start() {
        playerTracker.registerGameIdChangeCallback { removeFromQueue(it) }
        playerTracker.registerLogoutCallback { applicationScope.launch { removeFromQueue(it) } }

        // Reconcile tracked player state against the servers and instances we know about.
        applicationScope.repeatingTask(
            name = "PlayerTracker cleanup",
            initialDelayMillis = 10_000L,
            periodMillis = 10_000L
        ) {
            val servers = getServers()
            playerTracker.cleanup(
                serverNames = servers.mapTo(mutableSetOf()) { it.name },
                gameIds = servers.flatMapTo(mutableSetOf()) { server -> server.games.map { it.id } }
            )
        }

        applicationScope.repeatingTask(
            name = "Queue processing",
            initialDelayMillis = QUEUE_LOOP_RATE_LIMIT_PERIOD_MS,
            periodMillis = QUEUE_LOOP_RATE_LIMIT_PERIOD_MS
        ) {
            processQueue()
        }
    }

    override suspend fun getServers(): List<QueueServer> = data.withServers { servers -> ArrayList(servers) }
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
        val elementsToRemove = mutableSetOf<QueuedParty>()
        for (party in collection) {
            if (shouldRemove(party)) {
                elementsToRemove += party
            }
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
            val servers: List<QueueServer> = data.withServers { servers -> servers.toList() }
            val placementServers = placementCandidates(servers)
            val jobs = mutableListOf<Job>()
            val games = placementServers.flatMap { it.games }
            // game id -> effective player count
            val effectivePlayerCounts = mutableMapOf<String, Int>()
            val effectiveGames = ArrayList(games)
            val mapSources = mutableListOf<CommonTypes.MapSource>()
            // Map of (Game that is going to be created) -> (List of parties that will join that game once it's created)
            val newGameParties = mutableListOf<Pair<Game, MutableList<QueuedParty>>>()
            val now = System.currentTimeMillis()

            fun sendPartyToInstance(party: QueuedParty, game: Game) {
                val player = party.players.singleOrNull()
                LogContext.with(
                    LogContext.game(gameId = game.id, game = game.gameType.name) +
                            LogContext.party(
                                partyId = party.partyId,
                                size = party.players.size,
                                player = player,
                                username = player?.let { playerTracker.getPlayer(it)?.username },
                            )
                ) {
                    logger.info("Sending queued party $party to game ${game.id}.")
                }
                effectivePlayerCounts[game.id] =
                    effectivePlayerCounts.getOrDefault(game.id, game.playerCount) + party.players.size
                party.players.forEach { player ->
                    jobs += applicationScope.launch {
                        sendPlayerToInstance(player, game.id)
                    }
                }
            }

            removeAllParties { party ->
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

                if (party.failedCreationAttempts >= MAX_GAME_CREATION_ATTEMPTS) {
                    logger.warn(
                        "Removing party ${party.players} from the queue after " +
                                "${party.failedCreationAttempts} failed creation attempts."
                    )
                    playerTracker.sendChatAsync(
                        party.players,
                        "<red><lang:queue.adding.failed:'${party.gameType.name}':'<lang:queue.adding.failed.instance_creation>'>"
                    )
                    return@removeAllParties true
                }

                if (now - party.queuedAt >= QUEUE_TIMEOUT_MS) {
                    playerTracker.sendChatAsync(
                        party.players,
                        "<red><lang:queue.removed.reason:'<lang:queue.removed.reason.timeout>'>"
                    )
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
                    newGameParties.firstOrNull { it.first === initializingGame }?.second?.add(party)
                    return@removeAllParties false
                }

                if (now < party.nextCreationAttemptAt) {
                    // We need to wait longer before creating another new game for this party
                    return@removeAllParties false
                }

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

                val newGame = Game("", party.gameType, party.players.size, 8, EnumGameState.INITIALIZING)
                effectiveGames += newGame
                mapSources += mapSource
                newGameParties += newGame to mutableListOf(party)
                return@removeAllParties false
            }

            val newGames =
                effectiveGames.takeLast(effectiveGames.size - games.size).take(MAX_GAMES_PER_CYCLE)
            // server id -> number of games on it
            val effectiveGameCounts = mutableMapOf<String, Int>()
            placementServers.forEach { server -> effectiveGameCounts[server.name] = server.games.size }
            newGames.forEachIndexed { i, game ->
                val mapSource = mapSources[i]
                val waitingParties = newGameParties.firstOrNull { it.first === game }?.second.orEmpty()
                val requestTime = System.currentTimeMillis()
                val id = effectiveGameCounts.minByOrNull { it.value }?.key
                if (id == null) {
                    logger.warn("No game servers are available to create an instance for ${game.gameType}; will retry.")
                    waitingParties.forEach {
                        it.nextCreationAttemptAt = requestTime + GAME_CREATION_RETRY_BASE_DELAY_MS
                    }
                    return@forEachIndexed
                }
                effectiveGameCounts[id] = effectiveGameCounts[id]!! + 1
                waitingParties.forEach {
                    it.nextCreationAttemptAt = requestTime + GAME_CREATION_WAIT_MS
                }
                jobs += applicationScope.launch {
                    LogContext.with(
                        LogContext.game(
                            gameId = game.id,
                            game = game.gameType.name,
                            map = mapSource.mapId,
                            server = id,
                        )
                    ) {
                        logger.info("Creating instance with game type ${game.gameType} on server $id.")
                    }
                    val stub = k8sServiceDiscovery.getStubToServer(id) ?: run {
                        logger.warn("Couldn't get a channel to server $id to create an instance; will retry.")
                        recordCreationFailure(waitingParties)
                        return@launch
                    }
                    try {
                        val response = stub.withDeadline(Deadline.after(5, TimeUnit.SECONDS))
                            .createInstance(
                                GsClient.CreateInstanceRequest.newBuilder()
                                    .setGame(game.gameType.name)
                                    .setMapSource(mapSource)
                                    .apply { if (game.gameType.hasMode()) setMode(game.gameType.mode) }
                                    .build()
                            )
                        if (response.success) {
                            recordCreationSuccess(waitingParties)
                        } else {
                            logger.warn(
                                "Server $id rejected instance creation for game type ${game.gameType}; will retry."
                            )
                            recordCreationFailure(waitingParties)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(
                            "Failed to create instance with game type ${game.gameType} on server $id; will retry.",
                            e
                        )
                        recordCreationFailure(waitingParties)
                    }
                }
            }
            jobs.joinAll()
        }
    }

    private fun recordCreationFailure(parties: Collection<QueuedParty>) {
        val now = System.currentTimeMillis()
        parties.forEach { party ->
            party.failedCreationAttempts++
            party.nextCreationAttemptAt = now + creationRetryDelay(party.failedCreationAttempts)
        }
    }

    private fun recordCreationSuccess(parties: Collection<QueuedParty>) {
        val now = System.currentTimeMillis()
        parties.forEach { party ->
            party.failedCreationAttempts = 0
            // This method is called right after the game creation request RPC returns successfully, but there might still be
            // some time before Puffin receives the InstanceService.CreateInstance RPC. In that time, the game isn't joinable,
            // so prevent new games from being created for this party for a few seconds while we wait.
            party.nextCreationAttemptAt = now + GAME_CREATION_WAIT_MS
        }
    }

    private fun creationRetryDelay(failedAttempts: Int): Long {
        val exponent = (failedAttempts - 1).coerceAtLeast(0)
        return (GAME_CREATION_RETRY_BASE_DELAY_MS shl exponent)
            .coerceAtMost(GAME_CREATION_RETRY_MAX_DELAY_MS)
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

    private infix fun CommonTypes.GameType.matches(other: CommonTypes.GameType) =
        name == other.name && (!other.hasMode() || mode == other.mode) && (!other.hasMapId() || mapId == other.mapId)

    override suspend fun removeServer(name: String) {
        data.withServers { servers ->
            servers.removeIf { it.name == name }
        }
        applicationScope.launch { processQueue() }
    }

    override suspend fun addServer(
        name: String,
        address: String?,
        port: Int?,
        ready: Boolean,
        terminating: Boolean,
    ) {
        val changed = data.withServers { servers ->
            val index = servers.indexOfFirst { it.name == name }
            if (index == -1) {
                servers.add(
                    QueueServer(
                        name = name,
                        games = emptyList(),
                        address = address,
                        port = port,
                        ready = ready,
                        terminating = terminating,
                    )
                )
                true
            } else {
                // A server may be discovered before its address/port are known (or vice versa);
                // keep whichever values we have.
                val old = servers[index]
                val updated = old.copy(
                    address = address ?: old.address,
                    port = port ?: old.port,
                    ready = ready,
                    terminating = terminating,
                )
                if (updated != old) {
                    servers[index] = updated
                    true
                } else {
                    false
                }
            }
        }
        if (changed) {
            applicationScope.launch { processQueue() }
        }
    }

    override suspend fun setServerDraining(name: String, draining: Boolean) {
        val changed = data.withServers { servers ->
            var changed = false
            for ((i, server) in servers.withIndex()) {
                if (server.name == name && server.draining != draining) {
                    servers[i] = server.copy(draining = draining)
                    changed = true
                }
            }
            changed
        }
        if (changed) {
            applicationScope.launch { processQueue() }
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
                    val newGame = Game(
                        gameId,
                        gameType,
                        gameState.maxSlots - gameState.openSlots,
                        gameState.maxSlots,
                        gameState.gameState
                    )
                    val newList = server.games.toMutableList()
                    val existingIndex = newList.indexOfFirst { it.id == gameId }
                    if (existingIndex == -1) {
                        newList.add(newGame)
                    } else {
                        newList[existingIndex] = newGame
                    }
                    servers[i] = server.copy(games = newList)
                }
            }
        }
        applicationScope.launch { processQueue() }
    }

    override suspend fun getGames() = data.withServers { servers -> servers.flatMap { it.games } }

    private val destinationCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(config.destinationTtlSeconds.coerceAtLeast(1)))
        .build<UUID, String>()

    override fun setDestination(player: UUID, gameId: String) {
        destinationCache.put(player, gameId)
    }

    override fun consumeDestination(player: UUID): String? {
        val destination = destinationCache.getIfPresent(player) ?: return null
        destinationCache.invalidate(player)
        return destination
    }

    override suspend fun sendPlayerToInstance(player: UUID, gameId: String) {
        val playerState = playerTracker.getPlayer(player)
        val currentGameServer = playerState?.gameServerName
        val currentProxy = playerState?.proxyPodName

        val serverName = getServerOfGame(gameId)!!

        // Route same-server instance changes through the proxy when the proxy is
        // draining, so that it can force the player to reconnect via a transfer packet.
        val routeViaProxy = currentGameServer != serverName ||
                (currentProxy != null && !k8sServiceDiscovery.isProxyReady(currentProxy))

        val channel = if (routeViaProxy) {
            LogContext.with(LogContext.player(player, playerTracker.getPlayer(player)?.username) + LogContext.game(gameId)) {
                logger.info("Setting destination of player '$player' to game '$gameId'")
            }
            setDestination(player, gameId)
            currentProxy?.let { k8sServiceDiscovery.getChannelToProxy(it) }
        } else {
            playerTracker.getChannelToPlayer(player) // Send directly to the game server if we're routing the player between instances on the same server
        } ?: run {
            LogContext.with(LogContext.player(player, playerTracker.getPlayer(player)?.username) + LogContext.game(gameId)) {
                logger.warn("Failed to initialize the correct channel to send player $player from $currentGameServer to $serverName/$gameId!")
            }
            return
        }

        val server = getServer(serverName)
        val address = server?.address
        val port = server?.port
        if (address == null) {
            logger.warn("No IP/Port was found for server name $serverName! Sending players to this server may not be possible.")
            return
        }
        if (port == null) {
            logger.warn("Game server with name $serverName was found, but it has no port! Sending players to this server may not be possible.")
            return
        }
        val stub = PlayerHolderGrpcKt.PlayerHolderCoroutineStub(channel)
        stub.sendPlayer(sendPlayerRequest {
            this.playerUuid = player.toString()
            this.serverName = serverName
            this.gameServerIp = address
            this.gameServerPort = port
            this.gameServerGrpcPort = config.gsGrpcPort
            this.instanceId = gameId
        })
    }
}