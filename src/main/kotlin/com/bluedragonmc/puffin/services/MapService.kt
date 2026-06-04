package com.bluedragonmc.puffin.services

import com.bluedragonmc.api.grpc.CommonTypes
import com.bluedragonmc.api.grpc.Map
import com.bluedragonmc.api.grpc.MapServiceGrpcKt
import com.bluedragonmc.puffin.app.Env
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.util.Utils
import com.google.inject.Inject
import com.google.inject.Singleton
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.launch
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
            .map { file: File ->
                val mapSource = CommonTypes.MapSource.newBuilder()
                    .setMapId(file.name)
                    .setMapConfig(file.resolve("config.yml").readText())
                    .setMapFormat(CommonTypes.MapFormat.ANVIL)
                    .setMapUrl(file.toURI().toString())
                    .build()
                MapWithConfig(
                    mapSource = mapSource,
                    config = YamlConfigurationLoader.builder().buildAndLoadString(mapSource.mapConfig)
                )
            }
    }

    suspend fun getAvailableMaps(
        gameName: String?,
        mode: String?,
        whitelistedPlayers: Collection<UUID>?
    ): List<CommonTypes.MapSource> {
        val filteredDbMaps = db.getAvailableMaps(gameName, mode, whitelistedPlayers)
        val filteredAnvilFileMaps = anvilFileMaps.filter { map ->
            map.games.any {
                (gameName == null || it.name == gameName) && (mode == null || it.mode == null || it.mode == mode)
            } && if (whitelistedPlayers == null) (map.whitelist == null)
            else (map.whitelist?.containsAll(whitelistedPlayers) == true)
        }.map { it.mapSource }
        return filteredAnvilFileMaps + filteredDbMaps
    }

    inner class MapService : MapServiceGrpcKt.MapServiceCoroutineImplBase() {
        override suspend fun getAvailableMaps(request: Map.GetAvailableMapsRequest): Map.MapList = Utils.handleRPC {
            return Map.MapList.newBuilder().addAllMaps(
                getAvailableMaps(
                    gameName = if (request.hasGameName()) request.gameName else null,
                    mode = if (request.hasGameMode()) request.gameMode else null,
                    if (request.hasWhitelist()) request.whitelist.playersList.map { UUID.fromString(it) } else null,
                )
            ).build()
        }
    }

    init {
        val server = HttpServer.create(InetSocketAddress("0.0.0.0", Env.MAP_SERVICE_PORT), 0)
        server.createContext("/map/", object : HttpHandler {
            override fun handle(exchange: HttpExchange) {
                val mapId = exchange.requestURI.path.split("/").last()
                Puffin.IO.launch {
                    val data = db.getMapData(mapId)
                    if (data == null) {
                        exchange.sendResponseHeaders(404, 0)
                        exchange.responseBody.close()
                        return@launch
                    }
                    exchange.sendResponseHeaders(200, data.size.toLong())
                    exchange.responseBody.write(data)
                    exchange.responseBody.close()
                }
            }

        })
        server.start()
    }
}