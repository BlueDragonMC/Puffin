package com.bluedragonmc.puffin.services

import com.bluedragonmc.puffin.app.PuffinConfig
import com.google.inject.Inject
import com.google.inject.Singleton
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Serves the Kubernetes liveness, readiness, and startup probes. */
@Singleton
class HealthService @Inject constructor(
    private val config: PuffinConfig,
) : Service() {

    @Volatile
    private var ready = false

    @Volatile
    private var shuttingDown = false

    private lateinit var httpServer: HttpServer
    private lateinit var executor: ExecutorService

    fun markReady() {
        if (shuttingDown || ready) return
        ready = true
        logger.info("Puffin is ready.")
    }

    fun markNotReady() {
        ready = false
    }

    override fun start() {
        executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("puffin-health-", 0).factory()
        )

        httpServer = HttpServer.create(InetSocketAddress("0.0.0.0", config.healthPort), 0)
        httpServer.executor = executor
        httpServer.createContext("/health") { exchange -> handleHealth(exchange) }
        httpServer.createContext("/ready") { exchange -> handleReady(exchange) }
        httpServer.createContext("/drain") { exchange -> handleDrain(exchange) }
        httpServer.start()
        logger.info("Health server started on port ${config.healthPort}.")
    }

    override fun close() {
        shuttingDown = true
        ready = false
        if (::httpServer.isInitialized) {
            httpServer.stop(0)
        }
        if (::executor.isInitialized) {
            executor.shutdownNow()
        }
    }

    private fun handleHealth(exchange: HttpExchange) = exchange.use {
        respond(it, 200, "ok")
    }

    private fun handleReady(exchange: HttpExchange) = exchange.use {
        when {
            shuttingDown -> respond(it, 503, "draining")
            ready -> respond(it, 200, "ready")
            else -> respond(it, 503, "starting")
        }
    }

    private fun handleDrain(exchange: HttpExchange) = exchange.use {
        if (it.requestMethod != "GET") {
            respond(it, 405, "method not allowed")
            return@use
        }

        shuttingDown = true
        ready = false
        logger.info("Drain requested; refusing new traffic for ${config.drainDelayMillis}ms before shutdown.")
        try {
            Thread.sleep(config.drainDelayMillis.coerceAtLeast(0))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        respond(it, 200, "draining")
    }

    private fun respond(exchange: HttpExchange, status: Int, message: String) {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
