package com.reader.feature.reader

import com.reader.core.common.ReaderDispatchers
import com.reader.core.data.BookSourceRepository
import com.reader.core.model.Book
import com.reader.core.model.BookFormat
import com.reader.core.model.Locator
import com.reader.core.model.TocEntry
import com.reader.engine.layout.LayoutConfig
import com.reader.engine.layout.SimplePaginator
import com.reader.engine.render.FixedEngine
import com.reader.engine.render.ReaderEngine
import com.reader.engine.render.ReaderSession
import com.reader.engine.render.ReflowEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import android.os.ParcelFileDescriptor

/**
 * The only composition point for a fully opened reader session.
 *
 * Format-specific resources are acquired here and transferred to the engine
 * exactly once. If construction fails before transfer, this factory rolls back
 * every resource it still owns.
 */
class ReaderSessionFactory @Inject constructor(
    private val sourceRepository: BookSourceRepository,
    private val paginator: SimplePaginator,
    private val dispatchers: ReaderDispatchers,
) {
    suspend fun open(
        book: Book,
        layoutConfig: LayoutConfig,
        initialLocator: Locator,
        theme: String,
        onLayoutReady: () -> Unit,
        onPageRendered: () -> Unit,
    ): ReaderSession {
        val taskJob = SupervisorJob()
        val taskScope = CoroutineScope(taskJob + dispatchers.default)
        var source: com.reader.engine.format.BookSource? = null
        var sessionSource: com.reader.engine.format.BookSource? = null
        var descriptor: ParcelFileDescriptor? = null
        var engine: ReaderEngine? = null

        try {
            val toc: List<TocEntry>
            when (book.format) {
                BookFormat.PDF -> {
                    val openedDescriptor = sourceRepository.openPdfDescriptor(book)
                    descriptor = openedDescriptor
                    // PdfRenderer construction can parse document metadata and must not
                    // run on the UI thread. Keep construction non-cancellable until the
                    // newly created engine is handed to this factory for rollback/cleanup.
                    val fixed = createAndTransferOwnership(
                        dispatcher = dispatchers.io,
                        create = {
                            // FixedEngine.create takes ownership and guarantees rollback.
                            // Clear the factory reference before construction so a failure
                            // cannot close the same descriptor twice.
                            descriptor = null
                            FixedEngine.create(
                                pfd = openedDescriptor,
                                pageWidthPx = layoutConfig.pageWidthPx,
                                theme = theme,
                                initialPageIndex = initialLocator.chapter,
                                ioDispatcher = dispatchers.io,
                                onPageRendered = onPageRendered,
                            )
                        },
                        transfer = { created ->
                            // Record ownership before starting asynchronous page work. If the
                            // caller was cancelled during construction, catch below can now
                            // close the engine instead of losing an unreferenced renderer.
                            engine = created
                            created.start()
                        },
                    )
                    require(fixed.pageCount.value.exact?.let { it > 0 } == true) {
                        "PDF 文档没有可读取的页面"
                    }
                    val pageCount = fixed.pageCount.value.display
                    toc = withContext(dispatchers.default) {
                        val context = currentCoroutineContext()
                        buildList(pageCount) {
                            for (page in 0 until pageCount) {
                                context.ensureActive()
                                add(TocEntry("第 ${page + 1} 页", Locator(page, 0)))
                            }
                        }
                    }
                }

                BookFormat.EPUB, BookFormat.MD, BookFormat.TXT -> {
                    val openedSource = sourceRepository.open(book)
                    source = openedSource
                    sessionSource = openedSource
                    toc = try {
                        // A document may contain thousands of chapters. Build the TOC
                        // away from Main so opening the reader cannot block first frame.
                        withContext(dispatchers.default) { openedSource.toc() }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val reflow = ReflowEngine(
                        source = openedSource,
                        config = layoutConfig,
                        paginator = paginator,
                        scope = taskScope,
                        bookId = book.id,
                        onLayoutReady = onLayoutReady,
                    )
                    engine = reflow
                    reflow.start(initialLocator)
                    source = null // Ownership transferred to ReflowEngine.
                }
            }

            return ReaderSession(
                engine = requireNotNull(engine),
                source = sessionSource,
                toc = toc,
                taskJob = taskJob,
            )
        } catch (failure: Throwable) {
            taskJob.cancel()
            val activeEngine = engine
            if (activeEngine != null) {
                withContext(NonCancellable) {
                    taskJob.cancelAndJoin()
                    activeEngine.closeAndJoin()
                }
            } else {
                runCatching { source?.close() }
                runCatching { descriptor?.close() }
            }
            throw failure
        }
    }
}


/**
 * Completes synchronous resource construction and records ownership before cancellation
 * can be observed by the caller on return from the selected dispatcher.
 */
internal suspend fun <T> createAndTransferOwnership(
    dispatcher: CoroutineDispatcher,
    create: () -> T,
    transfer: (T) -> Unit,
): T = withContext(NonCancellable + dispatcher) {
    create().also(transfer)
}
