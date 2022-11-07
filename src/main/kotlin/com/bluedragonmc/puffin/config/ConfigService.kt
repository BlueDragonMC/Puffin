package com.bluedragonmc.puffin.config

import com.bluedragonmc.puffin.services.Service
import com.bluedragonmc.puffin.services.ServiceHolder

class ConfigService(app: ServiceHolder) : Service(app) {

    lateinit var config: PuffinConfig

    private fun readConfig() = PuffinConfig(
        System.getenv("PUFFIN_WORLD_FOLDER"),
        System.getenv("PUFFIN_MONGO_HOSTNAME") ?: "mongo",
        System.getenv("PUFFIN_MONGO_PORT")?.toInt() ?: 27017
    )

    override fun initialize() {
        runCatching {
            config = readConfig()
        }.onFailure { e ->
            logger.error("Config failed to load: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun close() {}

}