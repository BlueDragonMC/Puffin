package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes
import com.bluedragonmc.api.grpc.Map
import com.bluedragonmc.api.grpc.MapServiceGrpcKt
import com.bluedragonmc.puffin.app.Env
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.util.Utils
import com.google.inject.Inject
import com.google.inject.Singleton
import com.google.protobuf.Empty
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.objectmapping.ConfigSerializable
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import java.io.File
import java.net.InetSocketAddress
import java.util.*

@Singleton
class MapService @Inject constructor(val db: DatabaseConnection) : Service() {
    private data class MapWithConfig(
        val mapSource: CommonTypes.MapSource,
        val config: ConfigurationNode
    ) {

        val games: List<GameEntry> by lazy { config.node("world", "games").getList(GameEntry::class.java)!! }
        val whitelist: List<UUID>? by lazy {
            if (!config.node("world").hasChild("whitelist")) return@lazy null
            config.node("world", "whitelist").getList(UUID::class.java)
        }
    }

    @ConfigSerializable
    private data class GameEntry(
        val name: String,
        val mode: String?,
    ) {
        // for configurate
        constructor() : this("", null)
    }

    private val anvilFileMaps by lazy {
        File(Env.WORLDS_FOLDER).listFiles()
            .flatMap { file: File -> file.listFiles().toList() }
            .mapNotNull { file: File ->
                val configFile = file.resolve("config.yml")
                if (!configFile.exists()) {
                    logger.warn("Map directory ${file.absolutePath} does not have a config file. It will not be available.")
                    return@mapNotNull null
                }
                val mapSource = CommonTypes.MapSource.newBuilder()
                    .setMapId(file.name)
                    .setMapConfig(configFile.readText())
                    .setMapFormat(CommonTypes.MapFormat.ANVIL)
                    .setMapUrl(file.toURI().toString())
                    .build()
                MapWithConfig(
                    mapSource = mapSource,
                    config = YamlConfigurationLoader.builder().buildAndLoadString(mapSource.mapConfig)
                )
            }
    }

    private fun filterAnvilMaps(
        gameName: String?,
        mode: String?,
        mapId: String?,
        whitelistedPlayers: Collection<UUID>?
    ): List<MapWithConfig> {
        return anvilFileMaps.filter { map ->
            (mapId == null || map.mapSource.mapId == mapId)
                    && map.games.any {
                (gameName == null || it.name == gameName) && (mode == null || it.mode == null || it.mode == mode)
            } && (map.whitelist == null || (whitelistedPlayers != null && map.whitelist!!.containsAll(whitelistedPlayers)))
        }
    }

    suspend fun getAvailableMaps(
        gameName: String?,
        mode: String?,
        mapId: String?,
        whitelistedPlayers: Collection<UUID>?
    ): List<CommonTypes.MapSource> {
        val filteredDbMaps = db.getAvailableMaps(gameName, mode, mapId, whitelistedPlayers)
        val filteredAnvilFileMaps = filterAnvilMaps(gameName, mode, mapId, whitelistedPlayers).map { it.mapSource }
        return filteredAnvilFileMaps + filteredDbMaps
    }

    inner class MapService : MapServiceGrpcKt.MapServiceCoroutineImplBase() {
        override suspend fun getAvailableMaps(request: Map.GetAvailableMapsRequest): Map.MapList = Utils.handleRPC {
            return Map.MapList.newBuilder().addAllMaps(
                getAvailableMaps(
                    gameName = if (request.hasGameName()) request.gameName else null,
                    mode = if (request.hasGameMode()) request.gameMode else null,
                    mapId = null,
                    if (request.hasWhitelist()) request.whitelist.playersList.map { UUID.fromString(it) } else null,
                )
            ).build()
        }

        override suspend fun updateMapConfig(request: Map.UpdateMapConfigRequest): Empty {
            db.putMapConfig(request.mapId, request.configJson)
            return Empty.getDefaultInstance()
        }
    }

    init {
        val server = HttpServer.create(InetSocketAddress("0.0.0.0", Env.MAP_SERVICE_PORT), 0)
        server.createContext("/map/") { exchange ->
            Puffin.IO.launch {
                exchange.use { exchange ->
                    handleRequest(exchange)
                }
            }
        }
        server.start()
    }

    private suspend fun handleRequest(exchange: HttpExchange) {
        val method = exchange.requestMethod
        logger.info("Handling map request: $method ${exchange.requestURI}")
        val tokens = exchange.requestURI.path.split("/")
        val resourceType = tokens[tokens.lastIndex]
        val mapId = tokens[tokens.lastIndex - 1]
        when (method) {
            "GET" -> {
                when (resourceType) {
                    "data" -> {
                        val data = db.getMapData(mapId)
                        if (data == null) {
                            exchange.sendResponseHeaders(404, -1)
                            return
                        }
                        if (data.isEmpty()) {
                            exchange.sendResponseHeaders(200, -1)
                            return
                        }
                        exchange.sendResponseHeaders(200, data.size.toLong())
                        exchange.responseBody.use { it.write(data) }
                    }

                    else -> {
                        exchange.sendResponseHeaders(404, -1)
                    }
                }
            }

            "POST" -> {
                when (resourceType) {
                    "data" -> {
                        // TODO it might be bad to use readAllBytes for a large amount of data
                        val bytes = withContext(Dispatchers.IO) {
                            exchange.requestBody.readAllBytes()
                        }
                        val result = db.putMapData(mapId, bytes)
                        if (result.wasAcknowledged()) {
                            exchange.sendResponseHeaders(200, -1)
                        } else {
                            exchange.sendResponseHeaders(500, -1)
                        }
                    }

                    else -> {
                        exchange.sendResponseHeaders(404, -1)
                    }
                }
            }

            else -> {
                exchange.sendResponseHeaders(405, -1)
            }
        }
    }
}