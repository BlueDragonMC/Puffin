package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.app.Env.GRPC_SERVER_PORT
import com.bluedragonmc.puffin.grpc.*
import com.google.inject.Inject
import com.google.inject.Singleton
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import java.util.concurrent.TimeUnit

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

    override fun start() {
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

    override fun close() {
        if (::server.isInitialized) {
            server.shutdown()
            try {
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow()
                }
            } catch (_: InterruptedException) {
                server.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
    }
}
