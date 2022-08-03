package com.bluedragonmc.puffin.config

import com.bluedragonmc.puffin.services.Service
import com.bluedragonmc.puffin.services.ServiceHolder
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ConfigService(internal val file: Path, internal val secretsFile: Path, app: ServiceHolder): Service(app) {

    lateinit var secrets: SecretsConfig
    lateinit var config: PuffinConfig

    private fun readConfig(): PuffinConfig {
        val text = file.readText(StandardCharsets.UTF_8)
        return Json.decodeFromString(text)
    }

    private fun readSecretsConfig(): SecretsConfig {
        val text = secretsFile.readText(StandardCharsets.UTF_8)
        return Json.decodeFromString(text)
    }

    fun save() {
        file.writeText(Json.encodeToString(config), StandardCharsets.UTF_8, StandardOpenOption.WRITE)
        logger.info("Saved config file to ${file.absolutePathString()}.")
    }

    override fun initialize() {
        config = readConfig()
        secrets = readSecretsConfig()
    }

    override fun close() {}

}