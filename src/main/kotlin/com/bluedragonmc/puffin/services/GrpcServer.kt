package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.app.Env.GRPC_SERVER_PORT
import com.google.inject.Inject
import com.google.inject.Singleton
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1

@Singleton
class GrpcServer @Inject constructor(
    val maps: MapService,
    val gameServerManager: IGameServerManager,
    val queueService: IQueueService,
    val partyManager: IPartyManager,
    val playerTracker: IPlayerTracker,
    val privateMessageService: PrivateMessageService,
    val jukeboxService: JukeboxService
) : Service() {

    private lateinit var server: Server

    fun start() {
        server = ServerBuilder.forPort(GRPC_SERVER_PORT)
            .addService(maps.MapService())
            .addService(gameServerManager.serviceDiscoveryService)
            .addService(gameServerManager.instanceService)
            .addService(queueService.queueService)
            .addService(queueService.gameStateService)
            .addService(partyManager.partyService)
            .addService(playerTracker.playerTrackerService)
            .addService(privateMessageService.VelocityMessageService())
            .addService(jukeboxService.JukeboxRedirectService())
            .addService(ProtoReflectionServiceV1.newInstance())
            .build()

        server.start()
        logger.info("gRPC server started on port $GRPC_SERVER_PORT.")
    }

    fun awaitTermination() {
        server.awaitTermination()
    }
}
