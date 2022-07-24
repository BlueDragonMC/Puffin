package com.bluedragonmc.puffin

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("MainKt")

fun main() {
    logger.info("Starting...")
    DockerContainerManager.start()
    logger.info("Docker container manager initialized.")
    MessagingServices.init()
}
