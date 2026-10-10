package com.bluedragonmc.puffin.app

import com.google.inject.Singleton
import kotlinx.coroutines.*
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
            CoroutineExceptionHandler { context, throwable ->
                val name = context[CoroutineName]?.name ?: "Puffin"
                logger.error("Uncaught exception in '$name' coroutine", throwable)
            }

    fun repeatingTask(
        name: String,
        initialDelayMillis: Long = 0L,
        periodMillis: Long,
        action: suspend CoroutineScope.() -> Unit,
    ): Job = launch(CoroutineName(name)) {
        if (initialDelayMillis > 0) delay(initialDelayMillis)
        while (isActive) {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("Error in scheduled task '$name'", e)
            }
            delay(periodMillis)
        }
    }

    /** Cancels this scope and every coroutine launched in it. */
    fun close() {
        job.cancel()
    }
}
