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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
                    descriptor = sourceRepository.openPdfDescriptor(book)
                    val fixed = FixedEngine(
                        pfd = descriptor,
                        pageWidthPx = layoutConfig.pageWidthPx,
                        theme = theme,
                        initialPageIndex = initialLocator.chapter,
                        ioDispatcher = dispatchers.io,
                        onPageRendered = onPageRendered,
                    )
                    descriptor = null // Ownership transferred to FixedEngine.
                    engine = fixed
                    toc = (0 until fixed.pageCount.value.display).map { page ->
                        TocEntry("第 ${page + 1} 页", Locator(page, 0))
                    }
                }

                BookFormat.EPUB, BookFormat.MD, BookFormat.TXT -> {
                    source = sourceRepository.open(book)
                    sessionSource = source
                    toc = try {
                        source.toc()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val reflow = ReflowEngine(
                        source = source,
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
                activeEngine.close()
            } else {
                runCatching { source?.close() }
                runCatching { descriptor?.close() }
            }
            throw failure
        }
    }
}
