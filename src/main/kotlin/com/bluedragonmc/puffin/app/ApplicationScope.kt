package com.bluedragonmc.puffin.app

import com.google.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** Cancels this scope and every coroutine launched in it. */
    fun close() {
        job.cancel()
    }
}
