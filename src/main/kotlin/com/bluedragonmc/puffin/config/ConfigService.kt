package com.bluedragonmc.puffin.config

import com.bluedragonmc.puffin.services.Service
import com.bluedragonmc.puffin.services.ServiceHolder
import java.nio.file.Path
import java.nio.file.Paths

class ConfigService(app: ServiceHolder) : Service(app) {

    lateinit var config: PuffinConfig

    private fun readConfig() = PuffinConfig(
        System.getenv("PUFFIN_WORLD_FOLDER"),
        System.getenv("PUFFIN_MONGO_HOSTNAME") ?: "mongo",
        System.getenv("PUFFIN_MONGO_PORT")?.toInt() ?: 27017,
        System.getenv("PUFFIN_LUCKPERMS_URL") ?: "http://luckperms:8080",
    )

    override fun initialize() {
        runCatching {
            config = readConfig()
        }.onFailure { e ->
            logger.error("Config failed to load: ${e.message}")
            e.printStackTrace()
        }
    }

    fun getWorldsFolder(): Path = Paths.get(config.worldsFolder)

    override fun close() {}

}