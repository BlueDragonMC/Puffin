package com.bluedragonmc.puffin.app

import com.bluedragonmc.puffin.app.Env.DEV_MODE
import com.bluedragonmc.puffin.dashboard.ApiService
import com.bluedragonmc.puffin.dashboard.IApiService
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
        binder.bind(JukeboxService::class.java)
        binder.bind(IK8sServiceDiscovery::class.java).to(K8sServiceDiscovery::class.java)
        binder.bind(ServerVersionResolver::class.java).to(AgonesFleetVersionResolver::class.java)
        binder.bind(MapService::class.java)
        binder.bind(IPartyManager::class.java).to(PartyManager::class.java)
        binder.bind(IPlayerTracker::class.java).to(PlayerTracker::class.java)
        binder.bind(PrivateMessageService::class.java)
        binder.bind(IQueueService::class.java).to(QueueService::class.java)
        binder.bind(GrpcServer::class.java)
    }

    fun initialize() {
        val start = System.nanoTime()

        if (DEV_MODE) logger.warn("Starting Puffin in development mode.")

        val injector = Guice.createInjector(module)
        val applicationScope = injector.getInstance(ApplicationScope::class.java)
        Runtime.getRuntime().addShutdownHook(Thread({ applicationScope.close() }, "Puffin shutdown"))

        injector.getInstance(GrpcServer::class.java).start()
        injector.getInstance(ApiService::class.java).registerCallbacks()
        injector.getInstance(K8sServiceDiscovery::class.java).periodicSync()

        logger.info("Application fully started in ${(System.nanoTime() - start) / 1_000_000_000f}s.")
        injector.getInstance(GrpcServer::class.java).awaitTermination()
    }
}
