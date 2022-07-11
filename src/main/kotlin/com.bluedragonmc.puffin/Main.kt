package com.bluedragonmc.puffin

import com.bluedragonmc.messages.polymorphicModuleBuilder
import com.bluedragonmc.messagingsystem.AMQPClient
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("MainKt")

fun main(args: Array<String>) {
    logger.info("Starting...")
    val client = AMQPClient(hostname = "127.0.0.1", port = 5672, polymorphicModuleBuilder = polymorphicModuleBuilder, connectionName = "Puffin")
    Queue.start(client)
    InstanceManager.start(client)
    GameStateManager.start(client)
    logger.info("Fully initialized.")
}