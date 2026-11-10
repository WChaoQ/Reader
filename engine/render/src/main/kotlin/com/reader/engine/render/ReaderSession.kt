package com.reader.engine.render

import com.reader.core.model.TocEntry
import com.reader.engine.format.BookSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns all resources for one opened document.
 *
 * The engine owns its format-specific source/descriptor. The session owns the
 * coroutine job used to create and coordinate reflow work. Closing the session
 * invalidates it first, cancels session work, then delegates backend cleanup.
 */
class ReaderSession(
    engine: ReaderEngine,
    source: BookSource? = null,
    val toc: List<TocEntry> = emptyList(),
    private val taskJob: Job? = null,
) : Closeable {
    private val backend = engine
    private val sourceRef = source
    private val closed = AtomicBoolean(false)

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

    val isClosed: Boolean
        get() = closed.get()

    fun requireOpen() {
        check(!closed.get()) { "Reader session is already closed" }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            taskJob?.cancel()
            backend.close()
        }
    }

    suspend fun closeAndJoin() {
        close()
        taskJob?.cancelAndJoin()
        backend.closeAndJoin()
    }
}
