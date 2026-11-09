package com.reader.engine.layout

import com.reader.core.model.Block
import com.reader.core.model.Locator
import com.reader.engine.format.BookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Progressive chapter-first paginator with disk cache and [PageSlicer] cut heuristics.
 */
@Singleton
class SimplePaginator @Inject constructor(
    private val pageCache: PageCache,
) : Paginator {

    override fun paginate(
        book: BookSource,
        config: LayoutConfig,
        start: Locator,
        bookId: String?,
    ): Flow<PaginationEvent> = flow {
        val total = book.chapterCount
        if (total <= 0) {
            emit(PaginationEvent.Completed)
            return@flow
        }
        // Keep chapter results in document order. Since global page indexes are dense integers,
        // publishing a later chapter before an earlier one would shift every existing index
        // when the missing chapter eventually arrives.
        val order = buildOrder(total)
        val layoutHash = config.hash
        var done = 0
        for (chapterIndex in order) {
            coroutineContext.ensureActive()
            val chapter = book.loadChapter(chapterIndex)
            val pages = resolvePages(chapter.blocks, config, bookId, layoutHash, chapterIndex)
            emit(PaginationEvent.ChapterReady(chapter, pages))
            done++
            emit(PaginationEvent.Progress(done, total))
        }
        emit(PaginationEvent.Completed)
    }.flowOn(Dispatchers.Default)

    private fun resolvePages(
        blocks: List<Block>,
        config: LayoutConfig,
        bookId: String?,
        layoutHash: String,
        chapterIndex: Int,
    ): List<PageSlice> {
        val contentHeight = (config.pageHeightPx - 2 * config.paddingVerticalPx).coerceAtLeast(1)
        val paint = StaticLayoutFactory.createPaint(config)
        val text = DocumentTextBuilder.build(blocks, config.paragraphSpacingPx)
        if (text.isEmpty()) {
            return listOf(PageSlice(0, 0, 0, 0))
        }
        val layout = StaticLayoutFactory.create(text, paint, config)

        if (bookId != null) {
            val cached = pageCache.load(bookId, layoutHash, chapterIndex)
            if (cached != null && PageSlicer.isValid(layout, cached)) {
                return cached
            }
        }

        val pages = PageSlicer.slice(layout, contentHeight)
        if (bookId != null) {
            pageCache.save(bookId, layoutHash, chapterIndex, pages)
        }
        return pages
    }

    private fun buildOrder(total: Int): List<Int> =
        if (total <= 0) emptyList() else (0 until total).toList()
}
