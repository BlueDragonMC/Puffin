package com.bluedragonmc.puffin.grpc

import com.bluedragonmc.api.grpc.GsClient
import com.bluedragonmc.api.grpc.LobbyServiceGrpcKt
import com.bluedragonmc.api.grpc.ServiceDiscovery
import com.bluedragonmc.puffin.app.PuffinConfig
import com.bluedragonmc.puffin.services.IGameServerManager
import com.bluedragonmc.puffin.services.IK8sServiceDiscovery
import com.bluedragonmc.puffin.services.IMapService
import com.bluedragonmc.puffin.services.IQueueService
import com.bluedragonmc.puffin.util.Utils.handleRPC
import com.google.inject.Inject
import com.google.inject.Singleton

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

    override suspend fun findLobby(request: ServiceDiscovery.FindLobbyRequest): ServiceDiscovery.FindLobbyResponse =
        handleRPC {
            val servers = queueService.getServers().filter { gs ->
                (request.includeServerNamesCount == 0 || request.includeServerNamesList.contains(gs.name)) &&
                        (request.excludeServerNamesCount == 0 || !request.excludeServerNamesList.contains(gs.name))
            }

            // Prefer servers that are up to date (not draining) if available.
            val lobbyServers = servers.filter { !it.draining }.ifEmpty { servers }

            for (server in lobbyServers) {
                for (game in server.games) {
                    if (game.gameType.name == config.lobbyGameName) {
                        val info = gameServerManager.getK8sObject(server.name) ?: continue
                        return ServiceDiscovery.FindLobbyResponse.newBuilder()
                            .setFound(true)
                            .setServerName(server.name)
                            .setIp(info.address)
                            .setPort(info.port ?: 25565)
                            .setInstanceUuid(game.id)
                            .build()
                    }
                }
            }

            if (lobbyServers.isEmpty()) {
                return ServiceDiscovery.FindLobbyResponse.newBuilder().setFound(false).build()
            }

            // Start up a lobby if one wasn't found
            val bestServer =
                lobbyServers.minBy { queueService.getServer(it.name)?.games?.size ?: Integer.MAX_VALUE }
            val info = gameServerManager.getK8sObject(bestServer.name) ?: return ServiceDiscovery.FindLobbyResponse.newBuilder()
                .setFound(false).build()

            val stub = k8sServiceDiscovery.getStubToServer(bestServer.name)
                ?: return ServiceDiscovery.FindLobbyResponse.newBuilder().setFound(false).build()
            val response = stub.createInstance(
                GsClient.CreateInstanceRequest.newBuilder()
                    .setGame(config.lobbyGameName)
                    .setMapSource(mapService.getAvailableMaps(config.lobbyGameName, null, null, null).random())
                    .build()
            )

            return ServiceDiscovery.FindLobbyResponse.newBuilder()
                .setFound(true)
                .setServerName(bestServer.name)
                .setIp(info.address)
                .setPort(info.port ?: 25565)
                .setInstanceUuid(response.instanceUuid)
                .build()
        }
}
