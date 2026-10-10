package com.bluedragonmc.puffin.app

import com.bluedragonmc.puffin.app.Env.DEV_MODE
import com.bluedragonmc.puffin.dashboard.ApiService
import com.bluedragonmc.puffin.dashboard.IApiService
import com.bluedragonmc.puffin.grpc.GameStateGrpcService
import com.bluedragonmc.puffin.grpc.InstanceGrpcService
import com.bluedragonmc.puffin.grpc.JukeboxGrpcService
import com.bluedragonmc.puffin.grpc.LobbyGrpcService
import com.bluedragonmc.puffin.grpc.MapGrpcService
import com.bluedragonmc.puffin.grpc.PartyGrpcService
import com.bluedragonmc.puffin.grpc.PlayerTrackerGrpcService
import com.bluedragonmc.puffin.grpc.QueueGrpcService
import com.bluedragonmc.puffin.grpc.VelocityMessageGrpcService
import com.bluedragonmc.puffin.services.*
import com.google.inject.Guice
import com.google.inject.Module
import org.slf4j.LoggerFactory

class Puffin {

    private val logger = LoggerFactory.getLogger(Puffin::class.java)

    val module = Module { binder ->
        binder.bind(ApplicationScope::class.java)
        binder.bind(IApiService::class.java).to(ApiService::class.java)
        binder.bind(DatabaseConnection::class.java)
        binder.bind(IGameServerManager::class.java).to(GameServerManager::class.java)
        binder.bind(IK8sServiceDiscovery::class.java).to(K8sServiceDiscovery::class.java)
        binder.bind(ServerVersionResolver::class.java).to(AgonesFleetVersionResolver::class.java)
        binder.bind(MapService::class.java)
        binder.bind(IPartyManager::class.java).to(PartyManager::class.java)
        binder.bind(IPlayerTracker::class.java).to(PlayerTracker::class.java)
        binder.bind(PrivateMessageService::class.java)
        binder.bind(IQueueService::class.java).to(QueueService::class.java)
        binder.bind(GrpcServer::class.java)

        // gRPC adapters
        binder.bind(MapGrpcService::class.java)
        binder.bind(LobbyGrpcService::class.java)
        binder.bind(InstanceGrpcService::class.java)
        binder.bind(QueueGrpcService::class.java)
        binder.bind(GameStateGrpcService::class.java)
        binder.bind(PartyGrpcService::class.java)
        binder.bind(PlayerTrackerGrpcService::class.java)
        binder.bind(VelocityMessageGrpcService::class.java)
        binder.bind(JukeboxGrpcService::class.java)
    }

    fun initialize() {
        val start = System.nanoTime()

        if (DEV_MODE) logger.warn("Starting Puffin in development mode.")

        val injector = Guice.createInjector(module)
        val applicationScope = injector.getInstance(ApplicationScope::class.java)
        Runtime.getRuntime().addShutdownHook(Thread({ applicationScope.close() }, "Puffin shutdown"))

        // Register callbacks/listeners
        injector.getInstance(PlayerTracker::class.java).start()
        injector.getInstance(PartyManager::class.java).start()
        injector.getInstance(QueueService::class.java).start()
        injector.getInstance(ApiService::class.java).start()

        // Start background sync and servers.
        injector.getInstance(K8sServiceDiscovery::class.java).start()
        injector.getInstance(GameServerManager::class.java).start()
        injector.getInstance(MapService::class.java).start()

        injector.getInstance(GrpcServer::class.java).start()

        logger.info("Application fully started in ${(System.nanoTime() - start) / 1_000_000_000f}s.")
        injector.getInstance(GrpcServer::class.java).awaitTermination()
    }
}
