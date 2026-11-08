package com.reader.engine.layout

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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

@Singleton
class SimplePaginator @Inject constructor() : Paginator {

    override fun paginate(
        book: BookSource,
        config: LayoutConfig,
        start: Locator,
    ): Flow<PaginationEvent> = flow {
        val total = book.chapterCount
        if (total <= 0) {
            emit(PaginationEvent.Completed)
            return@flow
        }
        val order = buildOrder(start.chapter, total)
        var done = 0
        for (chapterIndex in order) {
            coroutineContext.ensureActive()
            val chapter = book.loadChapter(chapterIndex)
            val pages = paginateChapter(chapter.blocks, config)
            emit(PaginationEvent.ChapterReady(chapterIndex, pages))
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
        val contentWidth = (config.pageWidthPx - 2 * config.paddingHorizontalPx).coerceAtLeast(1)
        val contentHeight = (config.pageHeightPx - 2 * config.paddingVerticalPx).coerceAtLeast(1)
        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = config.textSizePx
        }
        val text = DocumentTextBuilder.build(blocks)
        if (text.isEmpty()) {
            return listOf(PageSlice(0, 0, 0, 0))
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, config.lineSpacingMult)
            .setIncludePad(false)
            .build()

        val pages = mutableListOf<PageSlice>()
        var line = 0
        val lineCount = layout.lineCount
        while (line < lineCount) {
            val top = layout.getLineTop(line)
            var endLine = line
            while (endLine + 1 < lineCount &&
                layout.getLineBottom(endLine + 1) - top <= contentHeight
            ) {
                endLine++
            }
            if (endLine == line && endLine + 1 < lineCount) {
                endLine = line + 1
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
        return pages.ifEmpty { listOf(PageSlice(0, 0, 0, text.length)) }
    }

}
