package com.bluedragonmc.puffin.app

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("MainKt")

fun main() {
    logger.info("Starting Puffin...")
    val app = Puffin()
    app.initialize()
}
