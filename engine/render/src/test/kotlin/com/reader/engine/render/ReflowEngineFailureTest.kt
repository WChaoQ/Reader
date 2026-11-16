package com.reader.engine.render

import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.Locator
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import com.reader.engine.format.BookSource
import com.reader.engine.layout.LayoutConfig
import com.reader.engine.layout.PaginationEvent
import com.reader.engine.layout.Paginator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class ReflowEngineFailureTest {

    @Test
    fun paginationFailureCompletesOpeningWaiterAndClosesSource() = runBlocking {
        val source = RecordingBookSource()
        val engine = createEngine(source, FailingPaginator(IOException("broken source")))

        engine.start(Locator(0, 0))
        val failure = withTimeout(2_000) { engine.awaitReadyOrFailure() }

        assertEquals(ReaderEngineFailureKind.INTERNAL_FAILURE, failure?.kind)
        assertEquals("文档分页失败，请重试", failure?.message)

        engine.closeAndJoin()
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun paginationOutOfMemoryCompletesOpeningWaiterWithTypedFailure() = runBlocking {
        val source = RecordingBookSource()
        val engine = createEngine(source, FailingPaginator(OutOfMemoryError("simulated")))

        engine.start(Locator(0, 0))
        val failure = withTimeout(2_000) { engine.awaitReadyOrFailure() }

        assertEquals(ReaderEngineFailureKind.OUT_OF_MEMORY_RISK, failure?.kind)
        assertEquals("文档过大或设备内存不足，无法完成分页，请重试", failure?.message)

        engine.closeAndJoin()
        assertEquals(1, source.closeCalls)
    }

    @Test
    fun cancellationIsNotConvertedIntoAUserFacingFailure() = runBlocking {
        val source = RecordingBookSource()
        val engine = createEngine(source, FailingPaginator(CancellationException("switch document")))

        engine.start(Locator(0, 0))

        assertNull(engine.failure.value)
        assertFalse(engine.ready.value)

        engine.closeAndJoin()
        assertEquals(1, source.closeCalls)
    }

    private fun createEngine(
        source: BookSource,
        paginator: Paginator,
    ) = ReflowEngine(
        source = source,
        config = LayoutConfig(
            pageWidthPx = 720,
            pageHeightPx = 1280,
            textSizePx = 18f,
        ),
        paginator = paginator,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private class FailingPaginator(
        private val failure: Throwable,
    ) : Paginator {
        override fun paginate(
            book: BookSource,
            config: LayoutConfig,
            start: Locator,
            bookId: String?,
        ): Flow<PaginationEvent> = flow {
            throw failure
        }
    }

    private class RecordingBookSource : BookSource {
        override val format = BookFormat.TXT
        override val chapterCount = 1
        var closeCalls = 0
            private set

        override suspend fun metadata() = BookMetadata("Test")
        override suspend fun toc(): List<TocEntry> = emptyList()
        override suspend fun loadChapter(index: Int): Chapter =
            error("The failing paginator must not request a chapter")
        override suspend fun openResource(ref: ResourceRef): InputStream =
            throw UnsupportedOperationException("No resources in test source")

        override fun close() {
            closeCalls++
        }
    }
}
