package com.bluedragonmc.puffin.app

import com.bluedragonmc.puffin.dashboard.ApiService
import com.bluedragonmc.puffin.services.*
import com.bluedragonmc.puffin.util.GrpcChannels
import com.google.inject.Guice
import com.google.inject.Module
import org.slf4j.LoggerFactory

class Puffin {

    private val logger = LoggerFactory.getLogger(Puffin::class.java)

    val module = Module { binder ->
        binder.bind(IGameServerManager::class.java).to(GameServerManager::class.java)
        binder.bind(IK8sServiceDiscovery::class.java).to(K8sServiceDiscovery::class.java)
        binder.bind(IMapService::class.java).to(MapService::class.java)
        binder.bind(ServerVersionResolver::class.java).to(AgonesFleetVersionResolver::class.java)
        binder.bind(IPartyManager::class.java).to(PartyManager::class.java)
        binder.bind(IPlayerTracker::class.java).to(PlayerTracker::class.java)
        binder.bind(IQueueService::class.java).to(QueueService::class.java)
    }

    fun initialize() {
        val start = System.nanoTime()

        val injector = Guice.createInjector(module)
        val config = injector.getInstance(PuffinConfig::class.java)
        if (config.devMode) logger.warn("Starting Puffin in development mode.")

        val applicationScope = injector.getInstance(ApplicationScope::class.java)
        val grpcChannels = injector.getInstance(GrpcChannels::class.java)
        val databaseConnection = injector.getInstance(DatabaseConnection::class.java)
        val playerTracker = injector.getInstance(PlayerTracker::class.java)
        val partyManager = injector.getInstance(PartyManager::class.java)
        val queueService = injector.getInstance(QueueService::class.java)
        val apiService = injector.getInstance(ApiService::class.java)
        val k8sServiceDiscovery = injector.getInstance(K8sServiceDiscovery::class.java)
        val gameServerManager = injector.getInstance(GameServerManager::class.java)
        val mapService = injector.getInstance(MapService::class.java)
        val grpcServer = injector.getInstance(GrpcServer::class.java)

        // Register callbacks/listeners
        playerTracker.start()
        partyManager.start()
        queueService.start()
        apiService.start()

        // Start background sync and servers
        k8sServiceDiscovery.start()
        gameServerManager.start()
        mapService.start()

        grpcServer.start()

        Runtime.getRuntime().addShutdownHook(Thread({
            logger.info("Shutting down Puffin...")
            // Cancel background tasks and tear down services
            grpcServer.close()
            mapService.close()
            apiService.close()
            applicationScope.close()
            gameServerManager.close()
            k8sServiceDiscovery.close()
            queueService.close()
            partyManager.close()
            playerTracker.close()
            databaseConnection.close()
            grpcChannels.closeAll()
        }, "Puffin shutdown"))

        logger.info("Application fully started in ${(System.nanoTime() - start) / 1_000_000_000f}s.")
        grpcServer.awaitTermination()
    }
}
