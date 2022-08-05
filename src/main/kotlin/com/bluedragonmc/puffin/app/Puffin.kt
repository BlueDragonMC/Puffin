package com.bluedragonmc.puffin.app

import com.bluedragonmc.puffin.config.ConfigService
import com.bluedragonmc.puffin.services.*
import com.bluedragonmc.puffin.util.Utils
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import kotlin.system.exitProcess

class Puffin : ServiceHolder {

    private val logger = LoggerFactory.getLogger(Puffin::class.java)

    private val services = mutableListOf<Service>()

    override fun <T : Service> get(type: KClass<out T>): T {
        services.forEach {
            if (type.isInstance(it)) return it as T
        }
        error("No service found of type $type")
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
        INSTANCE = this
        val app = this
        val start = System.nanoTime()
        register(ConfigService(Paths.get("assets/puffin.json"), Paths.get("assets/secrets.json"), app))
        register(ConfigWatcherService(app))
        register(DatabaseConnection(app))
        register(MessagingService(app)).onConnected {
            register(InstanceManager(app))
            register(Queue(app))
            register(GameStateManager(app))
            register(PlayerTracker(app))
            register(PartyManager(app))
            register(Utils.UtilsService(app))
            logger.info("Application fully started in ${(System.nanoTime() - start) / 1_000_000_000f}s.")
        }
        register(DockerContainerManager(app))
    }

    companion object {

        /**
         * Provides static access to Puffin. This should be avoided wherever possible.
         */
        lateinit var INSTANCE: Puffin

        internal val IO = object : CoroutineScope {
            override val coroutineContext: CoroutineContext =
                Dispatchers.IO + SupervisorJob() + CoroutineName("Puffin I/O")
        }
    }
}