package com.bluedragonmc.puffin.services

import com.bluedragonmc.messages.polymorphicModuleBuilder
import com.bluedragonmc.messagingsystem.AMQPClient
import com.bluedragonmc.puffin.app.Puffin
import com.bluedragonmc.puffin.config.ConfigService
import com.bluedragonmc.puffin.util.Utils.catchingTimer
import java.net.Socket

class MessagingService(app: Puffin) : Service(app) {

    internal lateinit var client: AMQPClient

    private val connectedActions = mutableListOf<(AMQPClient) -> Unit>()

    internal fun onConnected(block: (AMQPClient) -> Unit) {
        if (::client.isInitialized) {
            block(client) // If already connected, run the function immediately
        } else {
            connectedActions.add(block)
        }
    }

    private fun onRabbitMQStart() {
        val config = app.get(ConfigService::class).config
        if (::client.isInitialized) {
            logger.warn("Client initialized before it was set in onRabbitMQStart method! Was it initialized twice?")
            return
        }
        client = AMQPClient(config.amqpHostname,
            config.amqpPort,
            polymorphicModuleBuilder = polymorphicModuleBuilder,
            connectionName = "Puffin{${System.currentTimeMillis()}}")
        client.preInitialize()
        logger.info("Messaging services fully initialized.")
        connectedActions.forEach { it.invoke(client) }
    }

    override fun initialize() {
        val config = app.get(ConfigService::class).config
        logger.info("Waiting for RabbitMQ to start up to receive messages.")
        catchingTimer("amqp-connection-test", daemon = false, period = 5_000) {
            try {
                // Check if RabbitMQ is ready for requests
                Socket(config.amqpHostname,
                    config.amqpPort).close() // Check if the port is open first; this is faster and doesn't require the creation of a whole client
                AMQPClient(config.amqpHostname,
                    config.amqpPort,
                    polymorphicModuleBuilder = {},
                    connectionName = "Puffin Connection Test{${System.currentTimeMillis()}}").close() // Create a client to verify that RabbitMQ is fully started and running on this port
                logger.info("RabbitMQ started successfully! Initializing messaging support.")
            } catch (ignored: Throwable) {
                logger.debug("Waiting 5 seconds to retry connection to RabbitMQ.")
                return@catchingTimer
            }
            onRabbitMQStart()
            this.cancel()
        }
    }

    override fun close() {
        if (::client.isInitialized) {
            client.close()
        } else {
            logger.warn("Closed MessagingService when client was not initialized.")
        }
    }
}
