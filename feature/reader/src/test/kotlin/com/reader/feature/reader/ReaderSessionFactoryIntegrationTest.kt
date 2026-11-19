package com.reader.feature.reader

import android.content.Context
import androidx.room.Room
import com.reader.core.common.ReaderDispatchers
import com.reader.core.data.BookRepository
import com.reader.core.data.BookSourceRepository
import com.reader.core.database.ReaderDatabase
import com.reader.core.model.Block
import com.reader.core.model.Book
import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.Locator
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import com.reader.core.storage.BookFileStore
import com.reader.engine.format.BookSource
import com.reader.engine.format.DocumentHandle
import com.reader.engine.format.FormatHandler
import com.reader.engine.format.FormatRegistry
import com.reader.engine.layout.LayoutConfig
import com.reader.engine.layout.PageCache
import com.reader.engine.layout.SimplePaginator
import com.reader.engine.render.ReaderEngineFailureKind
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderSessionFactoryIntegrationTest {

    @Test
    fun factoryTransfersReflowSourceToSessionAndCloseReleasesItOnce() = runBlocking {
        val source = RecordingBookSource()
        withFactory(source) { factory, _ ->
            val session = factory.open(
                book = testBook(),
                layoutConfig = layoutConfig(),
                initialLocator = Locator(chapter = 0, offset = 0),
                theme = "day",
                onLayoutReady = {},
                onPageRendered = {},
            )

            try {
                val failure = withTimeout(5_000) {
                    session.engine.awaitReadyOrFailure()
                }
                assertNull(failure)
                assertTrue(session.engine.ready.value)
                assertEquals(source, session.source)
            } finally {
                session.closeAndJoin()
            }

            assertEquals(1, source.closeCalls.get())
        }
    }

    @Test
    fun asynchronousSourceFailureIsTypedAndSessionStillReleasesSource() = runBlocking {
        val source = RecordingBookSource(
            loadChapterAction = {
                throw SecurityException("provider permission revoked")
            },
        )
        withFactory(source) { factory, _ ->
            val session = factory.open(
                book = testBook(),
                layoutConfig = layoutConfig(),
                initialLocator = Locator(chapter = 0, offset = 0),
                theme = "day",
                onLayoutReady = {},
                onPageRendered = {},
            )

            try {
                val failure = withTimeout(5_000) {
                    session.engine.awaitReadyOrFailure()
                }
                assertNotNull(failure)
                assertEquals(ReaderEngineFailureKind.PERMISSION_REVOKED, failure?.kind)
            } finally {
                session.closeAndJoin()
            }

            assertEquals(1, source.closeCalls.get())
        }
    }

    @Test
    fun cancellationDuringTocReadRollsBackSourceBeforeReturning() = runBlocking {
        val tocStarted = CompletableDeferred<Unit>()
        val source = RecordingBookSource(
            tocAction = {
                tocStarted.complete(Unit)
                awaitCancellation()
            },
        )

        withFactory(source) { factory, _ ->
            val opening = async(Dispatchers.Default) {
                factory.open(
                    book = testBook(),
                    layoutConfig = layoutConfig(),
                    initialLocator = Locator(chapter = 0, offset = 0),
                    theme = "day",
                    onLayoutReady = {},
                    onPageRendered = {},
                )
            }

            withTimeout(5_000) { tocStarted.await() }
            opening.cancelAndJoin()

            assertTrue(opening.isCancelled)
            assertEquals(1, source.closeCalls.get())
        }
    }

    private suspend fun withFactory(
        source: RecordingBookSource,
        block: suspend (ReaderSessionFactory, RecordingBookSource) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            ReaderDatabase::class.java,
        ).allowMainThreadQueries().build()
        val fileStore = BookFileStore(context)
        val dispatchers = TestReaderDispatchers
        val book = testBook()
        val original = fileStore.originalFile(book.id, "epub")

        try {
            original.parentFile?.mkdirs()
            original.writeText("reader-session-factory-source-fixture")
            val bookRepository = BookRepository(
                context = context,
                bookDao = database.bookDao(),
                fileStore = fileStore,
                dispatchers = dispatchers,
            )
            bookRepository.upsert(book)

            val sourceRepository = BookSourceRepository(
                context = context,
                bookRepository = bookRepository,
                formatRegistry = FormatRegistry(setOf(RecordingFormatHandler(source))),
                dispatchers = dispatchers,
            )
            val factory = ReaderSessionFactory(
                sourceRepository = sourceRepository,
                paginator = SimplePaginator(PageCache(fileStore)),
                dispatchers = dispatchers,
            )
            block(factory, source)
        } finally {
            database.close()
            fileStore.deleteBook(book.id)
        }
    }

    private fun testBook() = Book(
        id = "reader-session-factory-test",
        title = "Reader Session Factory",
        author = null,
        format = BookFormat.EPUB,
        fileSize = 36L,
        coverPath = null,
        importedAt = 1_700_000_000_000L,
        lastReadAt = null,
        sourceUri = null,
    )

    private fun layoutConfig() = LayoutConfig(
        pageWidthPx = 360,
        pageHeightPx = 640,
        textSizePx = 16f,
        paddingHorizontalPx = 24,
        paddingVerticalPx = 24,
    )

    private class RecordingFormatHandler(
        private val source: RecordingBookSource,
    ) : FormatHandler {
        override val format: BookFormat = BookFormat.EPUB

        override fun canHandle(fileName: String, mimeType: String?, magic: ByteArray): Boolean = true

        override suspend fun open(document: DocumentHandle): BookSource = source
    }

    private class RecordingBookSource(
        private val tocAction: suspend () -> List<TocEntry> = { emptyList() },
        private val loadChapterAction: suspend (Int) -> Chapter = { index ->
            Chapter(
                index = index,
                title = "Chapter",
                blocks = listOf(Block.Paragraph("Factory lifecycle fixture")),
            )
        },
    ) : BookSource {
        override val format: BookFormat = BookFormat.EPUB
        override val chapterCount: Int = 1
        val closeCalls = AtomicInteger()

        override suspend fun metadata() = BookMetadata(title = "Factory fixture")
        override suspend fun toc(): List<TocEntry> = tocAction()
        override suspend fun loadChapter(index: Int): Chapter = loadChapterAction(index)
        override suspend fun openResource(ref: ResourceRef) = ByteArrayInputStream(byteArrayOf())
        override fun close() {
            closeCalls.incrementAndGet()
        }
    }

    private object TestReaderDispatchers : ReaderDispatchers {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }
}
