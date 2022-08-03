package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.config.ConfigService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import kotlin.io.path.name

class ConfigWatcherService(app: ServiceHolder) : Service(app) {

    private lateinit var watchService: WatchService
    private lateinit var pathKey: WatchKey

    override fun initialize() {
        val configService = app.get(ConfigService::class)
        val path = configService.file.parent
        val validFileNames = listOf(configService.file, configService.secretsFile).map(Path::name)

        watchService = FileSystems.getDefault().newWatchService()
        pathKey = path.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)

        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val key = withContext(Dispatchers.IO) { watchService.take() }
                for (event in key.pollEvents()) {
                    val updated = event.context() as Path
                    if (updated.name !in validFileNames) continue
                    logger.info("Reloaded config file because '${updated.name}' was updated.")
                    app.get(ConfigService::class).initialize()
                    break
                }

                if (!key.reset()) {
                    withContext(Dispatchers.IO) {
                        key.cancel()
                        watchService.close()
                    }
                    break
                }
            }
            pathKey.cancel()
        }
    }

    override fun close() {
        watchService.close()
        pathKey.cancel()
    }

}