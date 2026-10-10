package com.bluedragonmc.puffin.app

import com.google.inject.Singleton
import java.net.Inet4Address

/**
 * Application configuration, read from environment variables.
 */
@Singleton
class PuffinConfig {

    val k8sNamespace = System.getenv("PUFFIN_K8S_NAMESPACE") ?: "default"

    val worldsFolder = System.getenv("PUFFIN_WORLD_FOLDER") ?: "/puffin/worlds/"

    val mongoConnectionString = System.getenv("PUFFIN_MONGO_CONNECTION_STRING") ?: "mongodb://mongo:27017"

    val luckpermsApiUrl = System.getenv("PUFFIN_LUCKPERMS_URL") ?: "http://luckperms:8080"

    val devMode = System.getenv("PUFFIN_DEV_MODE")?.toBoolean() ?: false

    // When true, game servers that are running an outdated version are drained: no new games are
    // created on them until they are replaced by an up-to-date server.
    val drainOutdatedServers = System.getenv("PUFFIN_DRAIN_OUTDATED_SERVERS")?.toBoolean() ?: true

    val lobbyGameName = System.getenv("PUFFIN_LOBBY_GAME_NAME") ?: "Lobby"

    val defaultGsIp = System.getenv("PUFFIN_DEFAULT_GAMESERVER_IP") ?: "minecraft"
    val defaultProxyIp = System.getenv("PUFFIN_DEFAULT_PROXY_IP") ?: "velocity"

    // The amount of milliseconds in between game server syncs
    val gsSyncPeriod = System.getenv("PUFFIN_GS_SYNC_PERIOD_MS")?.toLongOrNull() ?: 10_000L

    // The amount of milliseconds in between proxy syncs
    val k8sSyncPeriod = System.getenv("PUFFIN_K8S_SYNC_PERIOD_MS")?.toLongOrNull() ?: 10_000L

    val grpcServerPort = System.getenv("PUFFIN_GRPC_PORT")?.toIntOrNull() ?: 50051
    val gsGrpcPort = System.getenv("PUFFIN_GAMESERVER_GRPC_PORT")?.toIntOrNull() ?: 50051
    val proxyGrpcPort = System.getenv("PUFFIN_PROXY_GRPC_PORT")?.toIntOrNull() ?: 50051

    val apiServicePort = System.getenv("PUFFIN_API_PORT")?.toIntOrNull() ?: 8080

    val mapServiceHost = System.getenv("PUFFIN_SERVICE_HOST") ?: Inet4Address.getLocalHost().hostName
    val mapServicePort =
        System.getenv("PUFFIN_SERVICE_PORT_MAP_SERVICE")?.toIntOrNull()
            ?: System.getenv("PUFFIN_MAPS_PORT")?.toIntOrNull()
            ?: 8082
}
