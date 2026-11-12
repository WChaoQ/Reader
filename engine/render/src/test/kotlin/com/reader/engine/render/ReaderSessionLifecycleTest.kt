package com.reader.engine.render

import android.graphics.Canvas
import com.reader.core.model.Locator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ReaderSessionLifecycleTest {

    @Test
    fun closeCancelsAndJoinsSessionTasksBeforeClosingEngine() = runBlocking {
        val engine = RecordingEngine()
        val session = ReaderSession(engine = engine, taskJob = SupervisorJob())
        val taskStarted = CompletableDeferred<Unit>()
        val taskStopped = CompletableDeferred<Unit>()

        session.launchTask {
            taskStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                taskStopped.complete(Unit)
            }
        }

        withTimeout(2_000) { taskStarted.await() }
        session.closeAndJoin()

        assertTrue(taskStopped.isCompleted)
        assertEquals(1, engine.closeAndJoinCalls.get())
    }

    @Test
    fun concurrentCloseCallsShareOneShutdown() = runBlocking {
        val engine = RecordingEngine()
        val allowEngineClose = CompletableDeferred<Unit>()
        engine.beforeClose = { allowEngineClose.await() }
        val session = ReaderSession(engine = engine, taskJob = SupervisorJob())

        session.close()
        val waiterA = async { session.closeAndJoin() }
        val waiterB = async { session.closeAndJoin() }

        assertFalse(waiterA.isCompleted)
        assertFalse(waiterB.isCompleted)
        allowEngineClose.complete(Unit)

        withTimeout(2_000) {
            waiterA.await()
            waiterB.await()
        }
        assertEquals(1, engine.closeAndJoinCalls.get())
        assertTrue(session.isClosed)
    }

    @Test
    fun closedSessionRejectsEngineAccessAndNewTasks() = runBlocking {
        val session = ReaderSession(engine = RecordingEngine(), taskJob = SupervisorJob())
        session.closeAndJoin()

        assertTrue(session.isClosed)
        try {
            session.engine
            throw AssertionError("Expected closed session access to fail")
        } catch (_: IllegalStateException) {
            // Expected.
        }

        try {
            session.launchTask { }
            throw AssertionError("Expected new task registration to fail")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    private class RecordingEngine : ReaderEngine {
        override val pageCount = MutableStateFlow(PageCountInfo(exact = 1))
        override val capabilities = emptySet<ReaderCapability>()
        override val ready = MutableStateFlow(true)
        val closeAndJoinCalls = AtomicInteger()
        var beforeClose: suspend () -> Unit = {}

        override fun locatorOf(pageIndex: Int) = Locator(0, 0)
        override fun pageIndexOf(locator: Locator) = 0
        override fun drawPage(pageIndex: Int, canvas: Canvas, textColor: Int, backgroundColor: Int) = Unit
        override fun pageText(pageIndex: Int) = ""
        override fun close() = Unit

        override suspend fun closeAndJoin() {
            closeAndJoinCalls.incrementAndGet()
            beforeClose()
        }
    }
}
