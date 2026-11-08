package com.reader.engine.render

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.graphics.Typeface
import android.util.LruCache
import com.reader.core.model.Block
import com.reader.core.model.Locator
import com.reader.core.model.Span
import com.reader.engine.format.BookSource
import com.reader.engine.layout.LayoutConfig
import com.reader.engine.layout.PageSlice
import com.reader.engine.layout.PaginationEvent
import com.reader.engine.layout.Paginator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class ReflowEngine(
    private val source: BookSource,
    private val config: LayoutConfig,
    private val paginator: Paginator,
    private val scope: CoroutineScope,
) : ReaderEngine {

    private val _pageCount = MutableStateFlow(PageCountInfo(estimated = 0))
    override val pageCount: StateFlow<PageCountInfo> = _pageCount.asStateFlow()

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val pageIndexMap = mutableListOf<Pair<Int, Int>>()
    private val chapterPageCounts = ConcurrentHashMap<Int, Int>()
    private val layouts = LruCache<Int, ChapterLayout>(3)

    private var paginateJob: Job? = null

    fun start(start: Locator = Locator(0, 0)) {
        paginateJob?.cancel()
        pageIndexMap.clear()
        chapterPageCounts.clear()
        layouts.evictAll()
        _ready.value = false
        _pageCount.value = PageCountInfo(estimated = source.chapterCount.coerceAtLeast(1))

        paginateJob = scope.launch {
            paginator.paginate(source, config, start).collect { event ->
                when (event) {
                    is PaginationEvent.ChapterReady -> {
                        val layout = buildChapterLayout(event.chapter, event.pages)
                        layouts.put(event.chapter, layout)
                        chapterPageCounts[event.chapter] = event.pages.size.coerceAtLeast(1)
                        rebuildPageIndex()
                        if (event.chapter == start.chapter) {
                            _ready.value = true
                        }
                    }
                    is PaginationEvent.Progress -> {
                        val avg = chapterPageCounts.values.average().let {
                            if (it.isNaN()) 1.0 else it
                        }
                        val estimated = (avg * source.chapterCount).toInt().coerceAtLeast(1)
                        _pageCount.value = PageCountInfo(exact = null, estimated = estimated)
                    }
                    PaginationEvent.Completed -> {
                        rebuildPageIndex()
                        _pageCount.value = PageCountInfo(exact = pageIndexMap.size)
                        _ready.value = true
                    }
                }
            }
        }
    }

    private fun rebuildPageIndex() {
        pageIndexMap.clear()
        for (chapter in 0 until source.chapterCount) {
            val count = chapterPageCounts[chapter] ?: continue
            for (page in 0 until count) pageIndexMap += chapter to page
        }
        if (pageIndexMap.isNotEmpty()) {
            val exact = if (chapterPageCounts.size == source.chapterCount) pageIndexMap.size else null
            _pageCount.value = PageCountInfo(exact = exact, estimated = pageIndexMap.size)
        }
    }

    /** Chapter parsing and StaticLayout construction are deliberately kept off the UI thread. */
    private suspend fun buildChapterLayout(
        chapterIndex: Int,
        pages: List<PageSlice>,
    ): ChapterLayout = withContext(Dispatchers.Default) {
        val chapter = source.loadChapter(chapterIndex)
        val styled = blocksToStyledText(chapter.blocks)
        val plain = styled.toString()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = config.textSizePx
        }
        val contentWidth = (config.pageWidthPx - 2 * config.paddingHorizontalPx).coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(styled, 0, styled.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, config.lineSpacingMult)
            .setIncludePad(false)
            .build()
        ChapterLayout(
            chapterIndex = chapterIndex,
            title = chapter.title,
            plainText = plain,
            layout = layout,
            pages = pages.ifEmpty { listOf(PageSlice(0, 0, 0, plain.length)) },
        )
    }

    private fun ensureLayout(chapter: Int): ChapterLayout? = layouts.get(chapter)

    override fun locatorOf(pageIndex: Int): Locator {
        if (pageIndexMap.isEmpty() || pageIndex !in pageIndexMap.indices) return Locator(0, 0)
        val (chapter, pageInChapter) = pageIndexMap[pageIndex]
        val slice = ensureLayout(chapter)?.pages?.getOrNull(pageInChapter)
        val totalPages = pageIndexMap.size.coerceAtLeast(1)
        return Locator(
            chapter = chapter,
            offset = slice?.startOffset ?: 0,
            progression = pageIndex.toFloat() / totalPages,
        )
    }

    override fun pageIndexOf(locator: Locator): Int {
        if (pageIndexMap.isEmpty()) return 0
        val layout = ensureLayout(locator.chapter)
        if (layout != null) {
            val index = layout.pages.indexOfFirst {
                locator.offset >= it.startOffset && locator.offset < it.endOffset
            }
            if (index >= 0) {
                val base = pageIndexMap.indexOfFirst { it.first == locator.chapter && it.second == 0 }
                if (base >= 0) return base + index
            }
        }
        val base = pageIndexMap.indexOfFirst { it.first == locator.chapter && it.second == 0 }
        return base.coerceAtLeast(0)
    }

    override fun drawPage(pageIndex: Int, canvas: Canvas, textColor: Int, backgroundColor: Int) {
        canvas.drawColor(backgroundColor)
        if (pageIndexMap.isEmpty() || pageIndex !in pageIndexMap.indices) return
        val (chapter, pageInChapter) = pageIndexMap[pageIndex]
        val chapterLayout = ensureLayout(chapter) ?: return
        val slice = chapterLayout.pages.getOrNull(pageInChapter) ?: return
        val layout = chapterLayout.layout
        layout.paint.color = textColor

        val top = layout.getLineTop(slice.startLine)
        val bottom = layout.getLineBottom(slice.endLine)
        val saveCount = canvas.save()
        canvas.translate(config.paddingHorizontalPx.toFloat(), config.paddingVerticalPx - top.toFloat())
        canvas.clipRect(
            0f,
            top.toFloat(),
            (config.pageWidthPx - config.paddingHorizontalPx).toFloat(),
            bottom.toFloat(),
        )
        layout.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    override fun pageText(pageIndex: Int): String {
        if (pageIndexMap.isEmpty() || pageIndex !in pageIndexMap.indices) return ""
        val (chapter, pageInChapter) = pageIndexMap[pageIndex]
        val chapterLayout = ensureLayout(chapter) ?: return ""
        val slice = chapterLayout.pages.getOrNull(pageInChapter) ?: return ""
        val start = slice.startOffset.coerceIn(0, chapterLayout.plainText.length)
        val end = slice.endOffset.coerceIn(start, chapterLayout.plainText.length)
        return chapterLayout.plainText.substring(start, end)
    }

    override fun close() {
        paginateJob?.cancel()
        layouts.evictAll()
        source.close()
    }

    private fun blocksToStyledText(blocks: List<Block>): SpannableStringBuilder {
        val result = SpannableStringBuilder()

        fun appendSpanStyles(spans: List<Span>, start: Int, textLength: Int) {
            spans.forEach { span ->
                val from = (start + span.start).coerceIn(start, start + textLength)
                val to = (start + span.end).coerceIn(from, start + textLength)
                if (to <= from) return@forEach
                when (span) {
                    is Span.Bold -> result.setSpan(StyleSpan(Typeface.BOLD), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    is Span.Italic -> result.setSpan(StyleSpan(Typeface.ITALIC), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    is Span.Code -> {
                        result.setSpan(TypefaceSpan("monospace"), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(BackgroundColorSpan(0x22808080), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    is Span.Link -> {
                        result.setSpan(ForegroundColorSpan(0xFF4778B8.toInt()), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(UnderlineSpan(), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }

        fun appendNested(children: List<Block>, separator: String) {
            children.forEachIndexed { index, child ->
                if (index > 0 && separator.isNotEmpty()) result.append(separator)
                when (child) {
                    is Block.Paragraph -> {
                        val start = result.length
                        result.append(child.text)
                        appendSpanStyles(child.spans, start, child.text.length)
                    }
                    is Block.Heading -> {
                        val start = result.length
                        result.append(child.text)
                        appendSpanStyles(child.spans, start, child.text.length)
                        val factor = when (child.level) {
                            1 -> 1.55f
                            2 -> 1.38f
                            3 -> 1.22f
                            4 -> 1.12f
                            else -> 1.06f
                        }
                        if (child.text.isNotEmpty()) {
                            result.setSpan(RelativeSizeSpan(factor), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            result.setSpan(StyleSpan(Typeface.BOLD), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    is Block.Quote -> {
                        val start = result.length
                        appendNested(child.children, "\n\n")
                        if (result.length > start) {
                            result.setSpan(LeadingMarginSpan.Standard(24, 24), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            result.setSpan(StyleSpan(Typeface.ITALIC), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    is Block.ListItem -> {
                        if (result.isNotEmpty()) result.append("\n")
                        val start = result.length
                        result.append(if (child.ordered) "${child.index}. " else "• ")
                        appendNested(child.children, "")
                        if (result.length > start) {
                            result.setSpan(LeadingMarginSpan.Standard(18, 18), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    is Block.CodeBlock -> {
                        val start = result.length
                        result.append(child.text)
                        if (result.length > start) {
                            result.setSpan(TypefaceSpan("monospace"), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            result.setSpan(BackgroundColorSpan(0x1F808080), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    is Block.Image -> {
                        val start = result.length
                        result.append("[${child.alt?.takeIf { it.isNotBlank() } ?: "图片"}]")
                        result.setSpan(StyleSpan(Typeface.ITALIC), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    Block.Divider -> result.append("────────────")
                }
            }
        }

        blocks.forEachIndexed { index, block ->
            if (index > 0 && result.isNotEmpty()) result.append("\n\n")
            when (block) {
                is Block.Paragraph -> {
                    val start = result.length
                    result.append(block.text)
                    appendSpanStyles(block.spans, start, block.text.length)
                }
                is Block.Heading -> {
                    val start = result.length
                    result.append(block.text)
                    appendSpanStyles(block.spans, start, block.text.length)
                    val factor = when (block.level) {
                        1 -> 1.55f
                        2 -> 1.38f
                        3 -> 1.22f
                        4 -> 1.12f
                        else -> 1.06f
                    }
                    if (block.text.isNotEmpty()) {
                        result.setSpan(RelativeSizeSpan(factor), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(StyleSpan(Typeface.BOLD), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                is Block.Quote -> {
                    val start = result.length
                    appendNested(block.children, "\n\n")
                    if (result.length > start) {
                        result.setSpan(LeadingMarginSpan.Standard(24, 24), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(StyleSpan(Typeface.ITALIC), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                is Block.ListItem -> {
                    val start = result.length
                    result.append(if (block.ordered) "${block.index}. " else "• ")
                    appendNested(block.children, "")
                    if (result.length > start) {
                        result.setSpan(LeadingMarginSpan.Standard(18, 18), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                is Block.CodeBlock -> {
                    val start = result.length
                    result.append(block.text)
                    if (result.length > start) {
                        result.setSpan(TypefaceSpan("monospace"), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(BackgroundColorSpan(0x1F808080), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                is Block.Image -> {
                    val start = result.length
                    result.append("[${block.alt?.takeIf { it.isNotBlank() } ?: "图片"}]")
                    result.setSpan(StyleSpan(Typeface.ITALIC), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                Block.Divider -> result.append("────────────")
            }
        }
        return result
    }
}
