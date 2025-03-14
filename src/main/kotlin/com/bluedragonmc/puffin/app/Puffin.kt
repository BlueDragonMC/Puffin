package com.bluedragonmc.puffin.app

import com.bluedragonmc.puffin.app.Env.DEV_MODE
import com.bluedragonmc.puffin.app.Env.GRPC_SERVER_PORT
import com.bluedragonmc.puffin.dashboard.ApiService
import com.bluedragonmc.puffin.services.*
import com.bluedragonmc.puffin.util.Utils
import io.grpc.ServerBuilder
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.system.exitProcess

class Puffin : ServiceHolder {

    private val logger = LoggerFactory.getLogger(Puffin::class.java)

    private val services = mutableListOf<Service>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Service> get(type: KClass<out T>): T {
        services.forEach {
            if (type.isInstance(it)) return it as T
        }
        error("No service found of type $type")
    }

    override fun has(type: KClass<out Service>): Boolean {
        return services.any { type.isInstance(it) }
    }

    override fun <T : Service> register(service: T): T {
        val start = System.nanoTime()
        try {
            services.forEach { it.onServiceInit(service) }
            service.initialize()
        } catch (e: Throwable) {
            logger.error("Error initializing service: ${service::class.qualifiedName}: ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }
        services.add(service)
        logger.info("Registered service ${service::class.simpleName ?: service::class.qualifiedName} (${(System.nanoTime() - start) / 1_000_000}ms)")
        return service
    }

    override fun unregister(type: KClass<out Service>) = services.removeIf { type.isInstance(it) }

    fun initialize() {

        val app = this
        val start = System.nanoTime()

        if (DEV_MODE) logger.warn("Starting Puffin in development mode.")

        val gameManager = GameManager(app)
        val queue = Queue(app)
        val gameStateManager = GameStateManager(app)
        val partyManager = PartyManager(app)
        val playerTracker = PlayerTracker(app)
        val privateMessageService = PrivateMessageService(app)
        val jukeboxService = JukeboxService(app)

        val grpcServer = ServerBuilder.forPort(GRPC_SERVER_PORT)
            .addService(gameManager.ServerDiscoveryService())
            .addService(gameManager.InstanceService())
            .addService(queue.QueueService())
            .addService(gameStateManager.GameStateService())
            .addService(partyManager.PartyService())
            .addService(playerTracker.PlayerTrackerService())
            .addService(privateMessageService.VelocityMessageService())
            .addService(jukeboxService.JukeboxRedirectService())
            .addService(ProtoReflectionServiceV1.newInstance())
            .build()

        grpcServer.start()
        logger.info("gRPC server started on port $GRPC_SERVER_PORT.")

        register(Utils.UtilsService(app))
        register(playerTracker)
        register(DatabaseConnection(app))
        register(K8sServiceDiscovery(app))
        register(gameManager)
        register(queue)
        register(gameStateManager)
        register(partyManager)
        register(privateMessageService)
        register(MinInstanceService(app))
        register(ApiService(app))

        logger.info("Application fully started in ${(System.nanoTime() - start) / 1_000_000_000f}s.")
        grpcServer.awaitTermination()
    }

    companion object {
        internal val IO = object : CoroutineScope {
            override val coroutineContext: CoroutineContext =
                Dispatchers.IO + SupervisorJob() + CoroutineName("Puffin I/O")
        }
    }
}