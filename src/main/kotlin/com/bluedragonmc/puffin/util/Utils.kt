package com.bluedragonmc.puffin.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

object Utils {

    @PublishedApi
    internal val logger = LoggerFactory.getLogger(Utils::class.java)

    inline fun <R : Any> handleRPC(handler: () -> R): R {
        try {
            return handler()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RpcErrors.log("An error occurred in an RPC handler", e, logger)
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
