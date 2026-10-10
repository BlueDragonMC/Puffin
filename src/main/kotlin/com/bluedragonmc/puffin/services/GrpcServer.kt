package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.app.Env.GRPC_SERVER_PORT
import com.bluedragonmc.puffin.grpc.GameStateGrpcService
import com.bluedragonmc.puffin.grpc.InstanceGrpcService
import com.bluedragonmc.puffin.grpc.JukeboxGrpcService
import com.bluedragonmc.puffin.grpc.LobbyGrpcService
import com.bluedragonmc.puffin.grpc.MapGrpcService
import com.bluedragonmc.puffin.grpc.PartyGrpcService
import com.bluedragonmc.puffin.grpc.PlayerTrackerGrpcService
import com.bluedragonmc.puffin.grpc.QueueGrpcService
import com.bluedragonmc.puffin.grpc.VelocityMessageGrpcService
import com.google.inject.Inject
import com.google.inject.Singleton
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1

@Singleton
class GrpcServer @Inject constructor(
    private val mapGrpcService: MapGrpcService,
    private val lobbyGrpcService: LobbyGrpcService,
    private val instanceGrpcService: InstanceGrpcService,
    private val queueGrpcService: QueueGrpcService,
    private val gameStateGrpcService: GameStateGrpcService,
    private val partyGrpcService: PartyGrpcService,
    private val playerTrackerGrpcService: PlayerTrackerGrpcService,
    private val velocityMessageGrpcService: VelocityMessageGrpcService,
    private val jukeboxGrpcService: JukeboxGrpcService,
) : Service() {

    private lateinit var server: Server

    fun start() {
        server = ServerBuilder.forPort(GRPC_SERVER_PORT)
            .addService(mapGrpcService)
            .addService(lobbyGrpcService)
            .addService(instanceGrpcService)
            .addService(queueGrpcService)
            .addService(gameStateGrpcService)
            .addService(partyGrpcService)
            .addService(playerTrackerGrpcService)
            .addService(velocityMessageGrpcService)
            .addService(jukeboxGrpcService)
            .addService(ProtoReflectionServiceV1.newInstance())
            .build()

        server.start()
        logger.info("gRPC server started on port $GRPC_SERVER_PORT.")
    }

    fun awaitTermination() {
        server.awaitTermination()
    }
}
