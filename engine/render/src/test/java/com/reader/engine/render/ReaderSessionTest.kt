package com.reader.engine.render

import android.graphics.Canvas
import com.reader.core.model.Locator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSessionTest {
    @Test
    fun closeIsIdempotentAndClosesEngineOnce() {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.close()
        session.close()

        assertTrue(session.isClosed)
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun closeCancelsSessionTaskJob() {
        val taskJob = Job()
        val session = ReaderSession(FakeReaderEngine(), taskJob = taskJob)

        assertTrue(taskJob.isActive)
        session.close()

        assertTrue(taskJob.isCancelled)
    }

    @Test
    fun requireOpenRejectsClosedSession() {
        val session = ReaderSession(FakeReaderEngine())

        assertFalse(session.isClosed)
        session.close()

        try {
            session.requireOpen()
            throw AssertionError("Expected closed session to be rejected")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    private class FakeReaderEngine : ReaderEngine {
        override val pageCount = MutableStateFlow(PageCountInfo(exact = 0))
        override val ready = MutableStateFlow(false)
        var closeCalls: Int = 0
            private set

        override fun locatorOf(pageIndex: Int) = Locator(0, 0)
        override fun pageIndexOf(locator: Locator) = 0
        override fun drawPage(pageIndex: Int, canvas: Canvas, textColor: Int, backgroundColor: Int) = Unit
        override fun pageText(pageIndex: Int) = ""
        override fun close() {
            closeCalls++
        }
    }
}
