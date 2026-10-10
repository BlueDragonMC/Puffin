package com.bluedragonmc.puffin.util

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*
import kotlin.concurrent.fixedRateTimer

object Utils {
    private val channels: Cache<String, ManagedChannel> = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(5))
        .expireAfterWrite(Duration.ofMinutes(10))
        .evictionListener { addr: String?, channel: ManagedChannel?, _ ->
            // Shut down channels when they are removed from the cache.
            if (channel != null && !channel.isShutdown) {
                channel.shutdown()
            }
        }
        .build()

    private val logger = LoggerFactory.getLogger(this::class.java)

    fun channelTo(addr: String, port: Int): ManagedChannel {
        return channels.get(addr) {
            logger.debug("Building managed channel with address '$addr' and port '$port'.")
            ManagedChannelBuilder.forAddress(addr, port).usePlaintext().build()
        }
    }

    fun closeChannel(addr: String) {
        val channel = channels.getIfPresent(addr)
        channel?.shutdown()
        channels.invalidate(addr)
    }

    /**
     * @param period The period, in milliseconds
     */
    inline fun catchingTimer(
        name: String? = null,
        daemon: Boolean = false,
        initialDelay: Long = 0.toLong(),
        period: Long,
        crossinline action: TimerTask.() -> Unit,
    ) =
        fixedRateTimer(name, daemon, initialDelay, period) {
            try {
                action()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

    inline fun <R : Any> handleRPC(handler: () -> R): R {
        try {
            return handler()
        } catch (e: Throwable) {
            LoggerFactory.getLogger(this::class.java).error("An error occurred in an RPC handler:")
            e.printStackTrace()
            throw e
        }
    }

    fun surroundWithSeparators(message: String): String {
        val separator = "<white><strikethrough>=================================</strikethrough></white>"
        return "${separator}\n${message}\n${separator}"
    }

    class RollingWindowRateLimiter(
        private val maxRequests: Int,
        private val windowMillis: Long
    ) {
        private val mutex = Mutex()
        private val timestamps = ArrayDeque<Long>(maxRequests)

        suspend fun rateLimit() {
            mutex.withLock {
                var now = monotonicMillis()
                val windowStart = now - windowMillis

                while (timestamps.isNotEmpty() && timestamps.first() < windowStart) {
                    timestamps.removeFirst()
                }

                if (timestamps.size >= maxRequests) {
                    val oldestTimestamp = timestamps.first()
                    val sleepTime = oldestTimestamp + windowMillis - now

                    delay(sleepTime)

                    now = monotonicMillis()

                    timestamps.removeFirst()
                }

                timestamps.addLast(now)
            }
        }

        private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
    }
}
