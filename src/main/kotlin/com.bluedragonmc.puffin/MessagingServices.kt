package com.bluedragonmc.puffin

import com.bluedragonmc.messages.polymorphicModuleBuilder
import com.bluedragonmc.messagingsystem.AMQPClient
import org.slf4j.LoggerFactory
import kotlin.concurrent.timer

object MessagingServices {

    private val logger = LoggerFactory.getLogger(MessagingServices::class.java)

    fun init() {
        logger.info("Waiting for RabbitMQ to start up to receive messages.")
        timer("RabbitMQ Connection Check", daemon = false, period = 2_000) {
            try {
                // Check if RabbitMQ is ready for requests
                AMQPClient(
                    hostname = "127.0.0.1",
                    port = 5672,
                    polymorphicModuleBuilder = polymorphicModuleBuilder,
                    connectionName = "Puffin"
                ).preInitialize()
                logger.info("RabbitMQ started successfully! Initializing messaging support.")
                onRabbitMQStart()
                this.cancel()
            } catch (ignored: Throwable) {

            }
        }
    }

    private var started = false

    private fun onRabbitMQStart() {
        if (started) return
        started = true

        val client = AMQPClient(
            hostname = "127.0.0.1",
            port = 5672,
            polymorphicModuleBuilder = polymorphicModuleBuilder,
            connectionName = "Puffin"
        )

        Utils.instance = client
        DockerContainerManager.consume(client)
        Queue.start(client)
        InstanceManager.start(client)
        GameStateManager.start(client)
        PartyManager.start(client)
        logger.info("Messaging services fully initialized.")
    }
}
