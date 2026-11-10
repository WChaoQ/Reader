package com.reader.engine.render

import com.reader.engine.format.BookSource
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one opened reader engine and exposes the format source for non-rendering
 * operations (for example text search).
 *
 * The engine is the resource owner: ReflowEngine closes its BookSource and
 * FixedEngine closes its PDF descriptor. The session must therefore never close
 * [source] separately. This keeps cleanup single-owner and idempotent.
 */
class ReaderSession(
    val engine: ReaderEngine,
    val source: BookSource? = null,
) : Closeable {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    fun requireOpen() {
        check(!closed.get()) { "Reader session is already closed" }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            engine.close()
        }
    }
}
