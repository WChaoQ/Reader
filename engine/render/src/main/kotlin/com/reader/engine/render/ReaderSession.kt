package com.reader.engine.render

import com.reader.core.model.TocEntry
import com.reader.engine.format.BookSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns every task and backend resource for one opened document.
 *
 * Closing first rejects new work and cancels the session task tree. Backend
 * resources are released only after all session tasks have stopped, preventing
 * search/layout work from racing a source close.
 */
class ReaderSession(
    engine: ReaderEngine,
    source: BookSource? = null,
    val toc: List<TocEntry> = emptyList(),
    private val taskJob: Job = SupervisorJob(),
) : Closeable {
    private val backend = engine
    private val sourceRef = source
    private val closed = AtomicBoolean(false)
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shutdownCompleted = CompletableDeferred<Unit>()

    val isClosed: Boolean
        get() = closed.get()

    val engine: ReaderEngine
        get() {
            requireOpen()
            return backend
        }

    val source: BookSource?
        get() {
            requireOpen()
            return sourceRef
        }

    fun requireOpen() {
        check(!closed.get()) { "Reader session is already closed" }
    }

    /** Launch work as a child of this session; it is cancelled and joined on close. */
    fun launchTask(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        requireOpen()
        return CoroutineScope(taskJob + context).launch(block = block)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        taskJob.cancel()
        shutdownScope.launch {
            try {
                taskJob.join()
                backend.closeAndJoin()
                shutdownCompleted.complete(Unit)
            } catch (failure: Throwable) {
                shutdownCompleted.completeExceptionally(failure)
            } finally {
                shutdownScope.cancel()
            }
        }
    }

    suspend fun closeAndJoin() {
        close()
        shutdownCompleted.await()
    }
}
