package com.reader.engine.layout

import android.text.StaticLayout
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
 * Progressive chapter-first paginator with disk cache, paragraph-aware cuts,
 * and widow/orphan control (architecture §7.2).
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
        val order = buildOrder(start.chapter, total)
        val layoutHash = config.hash
        var done = 0
        for (chapterIndex in order) {
            coroutineContext.ensureActive()
            val cached = if (bookId != null) {
                pageCache.load(bookId, layoutHash, chapterIndex)
            } else {
                null
            }
            val chapter = book.loadChapter(chapterIndex)
            val pages = if (cached != null && cached.isNotEmpty()) {
                cached
            } else {
                paginateChapter(chapter.blocks, config).also { slices ->
                    if (bookId != null) {
                        pageCache.save(bookId, layoutHash, chapterIndex, slices)
                    }
                }
            }
            emit(PaginationEvent.ChapterReady(chapter, pages))
            done++
            emit(PaginationEvent.Progress(done, total))
        }
        emit(PaginationEvent.Completed)
    }.flowOn(Dispatchers.Default)

    private fun buildOrder(start: Int, total: Int): List<Int> {
        val ordered = mutableListOf(start.coerceIn(0, total - 1))
        var delta = 1
        while (ordered.size < total) {
            val next = start + delta
            val prev = start - delta
            if (next < total) ordered += next
            if (prev >= 0) ordered += prev
            delta++
        }
        return ordered.distinct()
    }

    private fun paginateChapter(blocks: List<Block>, config: LayoutConfig): List<PageSlice> {
        val contentHeight = (config.pageHeightPx - 2 * config.paddingVerticalPx).coerceAtLeast(1)
        val paint = StaticLayoutFactory.createPaint(config)
        val text = DocumentTextBuilder.build(blocks)
        if (text.isEmpty()) {
            return listOf(PageSlice(0, 0, 0, 0))
        }
        val layout = StaticLayoutFactory.create(text, paint, config)
        return sliceLayout(layout, contentHeight, config)
    }

    /**
     * Split a measured [StaticLayout] into page slices.
     * Visible for unit-style verification of cut heuristics.
     */
    internal fun sliceLayout(
        layout: StaticLayout,
        contentHeight: Int,
        config: LayoutConfig,
    ): List<PageSlice> {
        val pages = mutableListOf<PageSlice>()
        var line = 0
        val lineCount = layout.lineCount
        val minLines = MIN_LINES_PER_PAGE
        while (line < lineCount) {
            val top = layout.getLineTop(line)
            var endLine = line
            while (endLine + 1 < lineCount &&
                layout.getLineBottom(endLine + 1) - top <= contentHeight
            ) {
                endLine++
            }
            if (endLine < line) endLine = line

            endLine = applyWidowControl(layout, line, endLine, lineCount, minLines)
            endLine = preferParagraphBoundary(layout, line, endLine, minLines)
            endLine = avoidOrphanHeading(layout, line, endLine, lineCount, minLines)

            while (endLine > line && layout.getLineBottom(endLine) - top > contentHeight) {
                endLine--
            }

            val startOffset = layout.getLineStart(line)
            val endOffset = layout.getLineEnd(endLine)
            pages += PageSlice(
                startLine = line,
                endLine = endLine,
                startOffset = startOffset,
                endOffset = endOffset,
            )
            line = endLine + 1
        }
        return pages.ifEmpty {
            listOf(PageSlice(0, 0, 0, layout.text.length))
        }
    }

    /** If only one line would remain on the next page, avoid a single-line page when possible. */
    private fun applyWidowControl(
        layout: StaticLayout,
        startLine: Int,
        endLine: Int,
        lineCount: Int,
        minLines: Int,
    ): Int {
        var end = endLine
        val remaining = lineCount - (end + 1)
        val pageLines = end - startLine + 1
        if (remaining == 1 && pageLines > minLines) {
            end--
        }
        return end.coerceAtLeast(startLine)
    }

    /**
     * Prefer cutting after a paragraph boundary (blank line / double newline) within the
     * lower half of the page so mid-paragraph breaks are reduced.
     */
    private fun preferParagraphBoundary(
        layout: StaticLayout,
        startLine: Int,
        endLine: Int,
        minLines: Int,
    ): Int {
        val pageLines = endLine - startLine + 1
        if (pageLines <= minLines) return endLine
        val text = layout.text
        val searchFloor = startLine + minLines - 1
        val searchFrom = maxOf(searchFloor, startLine + pageLines / 2)
        for (candidate in endLine downTo searchFrom) {
            if (isParagraphEnd(layout, text, candidate)) {
                return candidate
            }
        }
        return endLine
    }

    private fun isParagraphEnd(layout: StaticLayout, text: CharSequence, line: Int): Boolean {
        val end = layout.getLineEnd(line)
        if (end >= text.length) return true
        if (end > 0 && text[end - 1] == '\n') {
            if (end < text.length && text[end] == '\n') return true
        }
        if (line + 1 < layout.lineCount) {
            val ns = layout.getLineStart(line + 1)
            val ne = layout.getLineEnd(line + 1)
            if (ns < ne && text.subSequence(ns, ne).isBlank()) return true
        }
        return false
    }

    /**
     * Avoid leaving a short heading-like line alone at the bottom of a page when the
     * next page still has body content — pull the short line onto the following page.
     */
    private fun avoidOrphanHeading(
        layout: StaticLayout,
        startLine: Int,
        endLine: Int,
        lineCount: Int,
        minLines: Int,
    ): Int {
        if (endLine >= lineCount - 1) return endLine
        if (endLine - startLine + 1 <= minLines) return endLine
        if (!isShortLine(layout, endLine)) return endLine
        if (endLine + 1 < lineCount && isShortLine(layout, endLine + 1)) return endLine
        return endLine - 1
    }

    private fun isShortLine(layout: StaticLayout, line: Int): Boolean {
        val width = layout.width.coerceAtLeast(1)
        val lineWidth = layout.getLineWidth(line)
        return lineWidth < width * SHORT_LINE_FRACTION
    }

    companion object {
        private const val MIN_LINES_PER_PAGE = 2
        private const val SHORT_LINE_FRACTION = 0.42f
    }
}
