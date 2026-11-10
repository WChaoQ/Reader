package com.reader.engine.render

import android.graphics.Canvas
import com.reader.core.model.Locator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSessionTest {
    @Test
    fun closeIsIdempotentAndClosesEngineOnce() = runBlocking {
        val engine = FakeReaderEngine()
        val session = ReaderSession(engine)

        session.close()
        session.close()
        session.closeAndJoin()

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
    fun closeAndJoinWaitsForEngineShutdownAndCancelsTaskJob() = runBlocking {
        val engine = FakeReaderEngine()
        val taskJob = Job()
        val session = ReaderSession(engine, taskJob = taskJob)

        session.closeAndJoin()

        assertTrue(session.isClosed)
        assertTrue(taskJob.isCancelled)
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun closeWaitsForSessionTasksBeforeClosingEngine() = runBlocking {
        val taskFinished = CompletableDeferred<Unit>()
        val engine = FakeReaderEngine {
            assertTrue("Session task must finish before engine cleanup", taskFinished.isCompleted)
        }
        val session = ReaderSession(engine)

        session.launchTask {
            try {
                awaitCancellation()
            } finally {
                taskFinished.complete(Unit)
            }
        }

        session.closeAndJoin()

        assertTrue(taskFinished.isCompleted)
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun launchTaskRejectsClosedSession() {
        val session = ReaderSession(FakeReaderEngine())
        session.close()

        try {
            session.launchTask { }
            throw AssertionError("Expected task launch to be rejected")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    @Test
    fun engineCannotBeAccessedAfterClose() {
        val session = ReaderSession(FakeReaderEngine())
        session.close()

        try {
            session.engine
            throw AssertionError("Expected engine access to be rejected")
        } catch (_: IllegalStateException) {
            // Expected.
        }
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

    private class FakeReaderEngine(
        private val onClose: () -> Unit = {},
    ) : ReaderEngine {
        override val pageCount = MutableStateFlow(PageCountInfo(exact = 0))
        override val ready = MutableStateFlow(false)
        override val capabilities = emptySet<ReaderCapability>()
        var closeCalls: Int = 0
            private set

        override fun locatorOf(pageIndex: Int) = Locator(0, 0)
        override fun pageIndexOf(locator: Locator) = 0
        override fun drawPage(pageIndex: Int, canvas: Canvas, textColor: Int, backgroundColor: Int) = Unit
        override fun pageText(pageIndex: Int) = ""
        override fun close() {
            closeCalls++
            onClose()
        }
    }
}
