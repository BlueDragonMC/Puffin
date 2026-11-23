package com.bluedragonmc.puffin.grpc

import com.bluedragonmc.api.grpc.GsClient
import com.bluedragonmc.api.grpc.LobbyServiceGrpcKt
import com.bluedragonmc.api.grpc.ServiceDiscovery
import com.bluedragonmc.puffin.app.PuffinConfig
import com.bluedragonmc.puffin.services.IGameServerManager
import com.bluedragonmc.puffin.services.IK8sServiceDiscovery
import com.bluedragonmc.puffin.services.IMapService
import com.bluedragonmc.puffin.services.IQueueService
import com.bluedragonmc.puffin.services.placementCandidates
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.inject.Inject
import com.google.inject.Singleton
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * gRPC adapter for finding (and starting) lobby instances.
 */
@Singleton
class LobbyGrpcService @Inject constructor(
    private val gameServerManager: IGameServerManager,
    private val queueService: IQueueService,
    private val k8sServiceDiscovery: IK8sServiceDiscovery,
    private val mapService: IMapService,
    private val config: PuffinConfig,
) : LobbyServiceGrpcKt.LobbyServiceCoroutineImplBase() {

    private val recentRequestIdCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(2))
        .maximumSize(10_000)
        .build<String, ServiceDiscovery.FindLobbyResponse>()

    override suspend fun findLobby(request: ServiceDiscovery.FindLobbyRequest): ServiceDiscovery.FindLobbyResponse =
        handleRPC {
            val requestId = request.requestId.takeIf { request.hasRequestId() && it.isNotBlank() }
            if (requestId != null) {
                recentRequestIdCache.getIfPresent(requestId)?.let { return@handleRPC it }
            }

            val response = findLobby0(request)
            if (requestId != null && response.found) {
                recentRequestIdCache.put(requestId, response)
            }
            return@handleRPC response
        }

    private suspend fun findLobby0(request: ServiceDiscovery.FindLobbyRequest): ServiceDiscovery.FindLobbyResponse {
        val servers = queueService.getServers().filter { gs ->
            (request.includeServerNamesCount == 0 || request.includeServerNamesList.contains(gs.name)) &&
                    (request.excludeServerNamesCount == 0 || !request.excludeServerNamesList.contains(gs.name))
        }

        // Never use a terminating or not-ready server, and prefer up-to-date servers.
        val lobbyServers = placementCandidates(servers)

        for (server in lobbyServers) {
            for (game in server.games) {
                if (game.gameType.name == config.lobbyGameName) {
                    val info = gameServerManager.getGameServer(server.name) ?: continue
                    return ServiceDiscovery.FindLobbyResponse.newBuilder()
                        .setFound(true)
                        .setServerName(server.name)
                        .setIp(info.address)
                        .setPort(info.port ?: config.gameServerPort)
                        .setInstanceUuid(game.id)
                        .build()
                }
            }
        }

        if (lobbyServers.isEmpty()) {
            return ServiceDiscovery.FindLobbyResponse.newBuilder().setFound(false).build()
        }

        // Start up a lobby if one wasn't found
        val bestServer = lobbyServers.minBy { it.games.size }
        val info = gameServerManager.getGameServer(bestServer.name) ?: return ServiceDiscovery.FindLobbyResponse.newBuilder()
            .setFound(false).build()

        val stub = k8sServiceDiscovery.getStubToServer(bestServer.name)
            ?: return ServiceDiscovery.FindLobbyResponse.newBuilder().setFound(false).build()
        val response = stub.withDeadlineAfter(5, TimeUnit.SECONDS).createInstance(
            GsClient.CreateInstanceRequest.newBuilder()
                .setGame(config.lobbyGameName)
                .setMapSource(mapService.getAvailableMaps(config.lobbyGameName, null, null, null).random())
                .build()
        )

        return ServiceDiscovery.FindLobbyResponse.newBuilder()
            .setFound(true)
            .setServerName(bestServer.name)
            .setIp(info.address)
            .setPort(info.port ?: config.gameServerPort)
            .setInstanceUuid(response.instanceUuid)
            .build()
    }
}
