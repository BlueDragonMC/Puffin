package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes.GameType
import com.bluedragonmc.api.grpc.CommonTypes.GameType.GameTypeFieldSelector
import com.bluedragonmc.api.grpc.GsClient
import com.bluedragonmc.puffin.util.Utils
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths
import java.time.Duration
import java.util.*
import kotlin.io.path.inputStream

/**
 * Requires a minimum number of joinable instances for each game type.
 */
class MinInstanceService(app: ServiceHolder) : Service(app) {

    private val recentlyStarted = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(15))
        .build<GameType, Unit>()

    override fun initialize() {
        logger.info("Found ${types.size} different game types: ${types.joinToString { "${it.name}/${it.mapName}" }}")
        Utils.catchingTimer("Min Instance Req", true, 0L, 5_000L) {
            ensureMinimumInstances()
        }
    }

    private fun getAvailableGameTypes(): List<GameType> = properties.keys.mapNotNull {
        val split = it.toString().split("_")
        when (split.size) {
            2 -> GameType.newBuilder() // "<gameType>_<mapName>"
                .setName(split[0])
                .setMapName(split[1])
                .addAllSelectors(listOf(GameTypeFieldSelector.GAME_NAME, GameTypeFieldSelector.MAP_NAME))
                .build()

            3 -> GameType.newBuilder() // "<gameType>_<mapName>_<mode>"
                .setName(split[0])
                .setMapName(split[1])
                .setMode(split[2])
                .addAllSelectors(
                    listOf(
                        GameTypeFieldSelector.GAME_NAME,
                        GameTypeFieldSelector.MAP_NAME,
                        GameTypeFieldSelector.GAME_MODE
                    )
                )
                .build()

            else -> {
                logger.warn("Invalid config key in buffer-config.properties: \"$it\"")
                null
            }
        }
    }

    private val properties = Properties().apply {
        load(Paths.get("/service/config/buffer-config.properties").inputStream())
    }

    private val types = getAvailableGameTypes()

    private fun isSufficient(gameType: GameType): Boolean {
        val joinableInstances = app.get(InstanceManager::class)
            .getJoinableInstances(gameType) // The amount of instances which players can join
        val totalInstances =
            app.get(InstanceManager::class).filterRunningInstances(gameType).size // The current amount of instances

        val value = properties.getProperty(gameType.name + "_" + gameType.mapName + "_" + gameType.mode)
            ?: properties.getProperty(gameType.name + "_" + gameType.mapName)
            ?: return true

        // Modifiers:
        // "+": adds the number of join-able instances to the total
        //      (no "+" means the amount of servers will stay constant)
        // "/": creates one server for every x players logged in to the network
        // Quantifier strings can have multiple terms, in which case they are all considered.

        return value.split(" ").all { term ->
            if (term.startsWith('+')) {
                // We must have this amount of instances that are currently joinable
                joinableInstances >= term.substring(1).toInt()
            } else if (term.startsWith('/')) {
                // We must have one instance for this amount of players
                val totalPlayers = app.get(PlayerTracker::class).getPlayerCount(null)
                totalInstances >= (totalPlayers / term.substring(1).toInt())
            } else {
                totalInstances >= term.toInt()
            }
        }
    }

    private fun ensureMinimumInstances() {
        val im = app.get(InstanceManager::class)
        val gameType = types.firstOrNull {
            !isSufficient(it) && recentlyStarted.getIfPresent(it) == null
        } ?: return
        recentlyStarted.put(gameType, Unit)

        logger.info("Starting new instance for game type ${gameType.name}/${gameType.mapName}...")

        val gs = im.findGameServerWithLeastInstances()?.key ?: return
        runBlocking {
            val response = Utils.getStubToServer(gs)?.createInstance(
                GsClient.CreateInstanceRequest.newBuilder()
                    .setGameType(gameType)
                    .build()
            )
            if (response?.success == true) {
                recentlyStarted.invalidate(gameType)
            }
        }
    }

    fun getGameTypes(): List<GameType> {
        return types
    }
}