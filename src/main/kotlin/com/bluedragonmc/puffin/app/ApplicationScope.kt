package com.bluedragonmc.puffin.app

import com.google.inject.Singleton
import kotlinx.coroutines.*
import com.bluedragonmc.puffin.util.LogContext
import com.bluedragonmc.puffin.util.RpcErrors
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext

/**
 * The application-wide [CoroutineScope] used for fire-and-forget work.
 */
@Singleton
class ApplicationScope : CoroutineScope {

    private val logger = LoggerFactory.getLogger(ApplicationScope::class.java)
    private val job = SupervisorJob()

    override val coroutineContext: CoroutineContext =
        Dispatchers.IO + job + CoroutineName("Puffin I/O") +
            RpcErrors.handler("Puffin I/O", logger)

    fun repeatingTask(
        name: String,
        initialDelayMillis: Long = 0L,
        periodMillis: Long,
        action: suspend CoroutineScope.() -> Unit,
    ): Job = launch(CoroutineName(name) + MDCContext(LogContext.task(name))) {
        if (initialDelayMillis > 0) delay(initialDelayMillis)
        while (isActive) {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                RpcErrors.log("Error in scheduled task '$name'", e, logger)
            }
            delay(periodMillis)
        }
    }

    /** Cancels this scope and every coroutine launched in it. */
    fun close() {
        job.cancel()
    }
}
