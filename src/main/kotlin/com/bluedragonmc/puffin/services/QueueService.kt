package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.*
import com.bluedragonmc.api.grpc.CommonTypes.EnumGameState
import com.bluedragonmc.api.grpc.Queue
import com.bluedragonmc.api.grpc.Queue.GetDestinationRequest
import com.bluedragonmc.api.grpc.Queue.GetDestinationResponse
import com.bluedragonmc.puffin.app.Puffin
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

interface IQueueService {
    fun getServers(): List<QueueService.GameServer>
    fun getServer(serverName: String): QueueService.GameServer?
    fun getGame(gameId: String): QueueService.Game?
    fun getServerOfGame(gameId: String): String?
    fun registerInstanceUpdateCallback(cb: (gameId: String) -> Unit)
    fun setGameState(gameId: String, newState: CommonTypes.GameState)
    fun addToQueue(party: QueueService.QueuedParty)
    fun removeFromQueue(player: UUID): Boolean

    suspend fun processQueue()
    suspend fun getBestAvailableInstance(
        gameState: EnumGameState,
        gameType: CommonTypes.GameType,
        partySize: Int
    ): QueueService.Game?

    fun removeServer(name: String)
    fun addServer(name: String)
    fun removeGame(serverName: String, gameId: String)
    fun getGamesMatching(gameType: CommonTypes.GameType): List<QueueService.Game>
    fun addGame(serverName: String, gameId: String, gameType: CommonTypes.GameType, gameState: CommonTypes.GameState)
    fun getGames(): List<QueueService.Game>
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
    val gameServerManager: IGameServerManager
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

        fun <R> withPartiesBlocking(block: suspend (MutableSet<QueuedParty>) -> R): R =
            runBlocking { withParties(block) }

        suspend inline fun <R> withServers(block: suspend (MutableList<GameServer>) -> R): R = serversMutex.withLock {
            block(servers)
        }

        fun <R> withServersBlocking(block: suspend (MutableList<GameServer>) -> R): R =
            runBlocking { withServers(block) }
    }

    private val data = Data()

    override fun getServers(): List<GameServer> = data.withServersBlocking { servers -> ArrayList(servers) }
    override fun getServer(serverName: String) =
        data.withServersBlocking { servers -> servers.find { it.name == serverName } }

    override fun getGame(gameId: String): Game? = data.withServersBlocking { servers ->
        for (server in servers) {
            for (game in server.games) {
                if (game.id == gameId) return@withServersBlocking game
            }
        }
        null
    }

    override fun getServerOfGame(gameId: String): String? = data.withServersBlocking { servers ->
        for (server in servers) {
            for (game in server.games) {
                if (game.id == gameId) return@withServersBlocking server.name
            }
        }
        null
    }

    private val instanceUpdateCallbacks = mutableListOf<(gameId: String) -> Unit>()

    override fun registerInstanceUpdateCallback(cb: (gameId: String) -> Unit) {
        instanceUpdateCallbacks.add(cb)
    }

    override fun setGameState(gameId: String, newState: CommonTypes.GameState) {
        data.withServersBlocking { servers ->
            outer@ for ((i, server) in servers.withIndex()) {
                for (game in server.games) {
                    if (game.id == gameId) {
                        servers[i] =
                            server.copy(games = server.games.map { g ->
                                if (g.id == gameId) g.copy(
                                    playerCount = newState.maxSlots - newState.openSlots,
                                    maxPlayers = newState.maxSlots,
                                    state = newState.gameState
                                ) else g
                            })
                        break@outer
                    }
                }
            }
        }
        instanceUpdateCallbacks.forEach { it(gameId) }
        Puffin.IO.launch { processQueue() }
    }

    override fun addToQueue(party: QueuedParty) {
        Puffin.IO.launch {
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

    override fun removeFromQueue(player: UUID) =
        data.withPartiesBlocking { queuedParties -> queuedParties.removeIf { player in it.players } }

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
    private val processQueueRateLimiter = Utils.RollingWindowRateLimiter(maxRequests = 5, windowMillis = 1_000)
    override suspend fun processQueue() {
        processQueueRateLimiter.rateLimit()
        processQueueMutex.withLock {
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
                    jobs += Puffin.IO.launch {
                        sendPlayerToInstance(player, game.id)
                    }
                }
            }

            removeAllParties { party ->
                party.attempts++
                if (party.attempts > 5) {
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
                effectiveGames.takeLast(effectiveGames.size - games.size).take(5) // 5 games can start per cycle
            // server id -> number of games on it
            val effectiveGameCounts = mutableMapOf<String, Int>()
            servers.forEach { server -> effectiveGameCounts[server.name] = server.games.size }
            newGames.forEachIndexed { i, game ->
                val mapSource = mapSources[i]
                jobs += Puffin.IO.launch {
                    val id = effectiveGameCounts.minBy { it.value }.key
                    effectiveGameCounts[id] = effectiveGameCounts[id]!! + 1
                    logger.info("Creating instance with game type ${game.gameType} on server $id.")
                    k8sServiceDiscovery.getStubToServer(id)!!
                        .withDeadline(Deadline.after(5, TimeUnit.SECONDS))
                        .createInstance(
                            GsClient.CreateInstanceRequest.newBuilder()
                                .setGame(game.gameType.name)
                                .setMapSource(mapSource)
                                .setMode(game.gameType.mode)
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
        val name: String, val games: List<Game>
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

    override fun removeServer(name: String) {
        data.withServersBlocking { servers ->
            servers.removeIf { it.name == name }
        }
        Puffin.IO.launch { processQueue() }
    }

    override fun addServer(name: String) {
        data.withServersBlocking { servers ->
            servers.add(GameServer(name, emptyList()))
        }
        Puffin.IO.launch { processQueue() }
    }

    override fun removeGame(serverName: String, gameId: String) {
        data.withServersBlocking { servers ->
            for ((i, server) in servers.withIndex()) {
                if (server.name == serverName) {
                    servers[i] = server.copy(games = server.games.filter { it.id != gameId })
                }
            }
        }
        Puffin.IO.launch { processQueue() }
    }

    override fun getGamesMatching(gameType: CommonTypes.GameType) =
        data.withServersBlocking { servers -> servers.flatMap { it.games.filter { game -> game.gameType matches gameType } } }

    override fun addGame(
        serverName: String,
        gameId: String,
        gameType: CommonTypes.GameType,
        gameState: CommonTypes.GameState
    ) {
        data.withServersBlocking { servers ->
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
        Puffin.IO.launch { processQueue() }
    }

    override fun getGames() = data.withServersBlocking { servers -> servers.flatMap { it.games } }

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
        override suspend fun addToQueue(request: Queue.AddToQueueRequest): Empty = Utils.handleRPC {
            val playerUuid = UUID.fromString(request.playerUuid)
            val party = partyManager.partyOf(playerUuid)?.getMembers() ?: listOf(playerUuid)
            // check waiting instances
            val waitingInstance =
                getBestAvailableInstance(CommonTypes.EnumGameState.WAITING, request.gameType, party.size)
            if (waitingInstance != null) {
                party.forEach { player ->
                    sendPlayerToInstance(player, waitingInstance.id)
                }
                return Empty.getDefaultInstance()
            }

            addToQueue(QueuedParty(party, request.gameType))

            return Empty.getDefaultInstance()
        }

        override suspend fun getDestinationGame(request: GetDestinationRequest): GetDestinationResponse =
            Utils.handleRPC {
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

        override suspend fun removeFromQueue(request: Queue.RemoveFromQueueRequest): Empty = Utils.handleRPC {
            // TODO make sure you're the party leader?
            removeFromQueue(UUID.fromString(request.playerUuid))
            return Empty.getDefaultInstance()
        }
    }
}