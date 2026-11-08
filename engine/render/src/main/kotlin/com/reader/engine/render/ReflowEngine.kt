package com.reader.engine.render

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.reader.core.model.Block
import com.reader.core.model.Locator
import com.reader.engine.format.BookSource
import com.reader.engine.layout.LayoutConfig
import com.reader.engine.layout.PageSlice
import com.reader.engine.layout.PaginationEvent
import com.reader.engine.layout.Paginator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = config.textSizePx
    }

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
        for (ch in 0 until source.chapterCount) {
            val count = chapterPageCounts[ch] ?: continue
            for (p in 0 until count) {
                pageIndexMap += ch to p
            }
        }
        if (pageIndexMap.isNotEmpty()) {
            val exact = if (chapterPageCounts.size == source.chapterCount) pageIndexMap.size else null
            _pageCount.value = PageCountInfo(exact = exact, estimated = pageIndexMap.size)
        }
    }

    private suspend fun buildChapterLayout(chapterIndex: Int, pages: List<PageSlice>): ChapterLayout {
        val chapter = source.loadChapter(chapterIndex)
        val plain = blocksToPlain(chapter.blocks)
        val contentWidth = (config.pageWidthPx - 2 * config.paddingHorizontalPx).coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(plain, 0, plain.length, textPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, config.lineSpacingMult)
            .setIncludePad(false)
            .build()
        return ChapterLayout(
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
        val (ch, pageInCh) = pageIndexMap[pageIndex]
        val layout = ensureLayout(ch)
        val slice = layout?.pages?.getOrNull(pageInCh)
        val totalPages = pageIndexMap.size.coerceAtLeast(1)
        return Locator(
            chapter = ch,
            offset = slice?.startOffset ?: 0,
            progression = pageIndex.toFloat() / totalPages,
        )
    }

    override fun pageIndexOf(locator: Locator): Int {
        if (pageIndexMap.isEmpty()) return 0
        val layout = ensureLayout(locator.chapter)
        if (layout != null) {
            val idx = layout.pages.indexOfFirst {
                locator.offset >= it.startOffset && locator.offset < it.endOffset
            }
            if (idx >= 0) {
                val base = pageIndexMap.indexOfFirst { it.first == locator.chapter && it.second == 0 }
                if (base >= 0) return base + idx
            }
        }
        val base = pageIndexMap.indexOfFirst { it.first == locator.chapter && it.second == 0 }
        return base.coerceAtLeast(0)
    }

    override fun drawPage(pageIndex: Int, canvas: Canvas, textColor: Int, backgroundColor: Int) {
        canvas.drawColor(backgroundColor)
        if (pageIndexMap.isEmpty() || pageIndex !in pageIndexMap.indices) return
        val (ch, pageInCh) = pageIndexMap[pageIndex]
        val chapterLayout = ensureLayout(ch) ?: return
        val slice = chapterLayout.pages.getOrNull(pageInCh) ?: return
        val layout = chapterLayout.layout
        textPaint.color = textColor

        val padH = config.paddingHorizontalPx.toFloat()
        val padV = config.paddingVerticalPx.toFloat()
        val top = layout.getLineTop(slice.startLine)
        val bottom = layout.getLineBottom(slice.endLine)

        canvas.save()
        canvas.translate(padH, padV - top)
        canvas.clipRect(
            0f,
            top.toFloat(),
            (config.pageWidthPx - config.paddingHorizontalPx).toFloat(),
            bottom.toFloat(),
        )
        layout.draw(canvas)
        canvas.restore()
    }

    override fun pageText(pageIndex: Int): String {
        if (pageIndexMap.isEmpty() || pageIndex !in pageIndexMap.indices) return ""
        val (ch, pageInCh) = pageIndexMap[pageIndex]
        val chapterLayout = ensureLayout(ch) ?: return ""
        val slice = chapterLayout.pages.getOrNull(pageInCh) ?: return ""
        val start = slice.startOffset.coerceIn(0, chapterLayout.plainText.length)
        val end = slice.endOffset.coerceIn(start, chapterLayout.plainText.length)
        return chapterLayout.plainText.substring(start, end)
    }

    override fun close() {
        paginateJob?.cancel()
        layouts.evictAll()
        source.close()
    }

    private fun blocksToPlain(blocks: List<Block>): String = buildString {
        for (block in blocks) {
            when (block) {
                is Block.Paragraph -> {
                    if (isNotEmpty()) append("\n\n")
                    append(block.text)
                }
                is Block.Heading -> {
                    if (isNotEmpty()) append("\n\n")
                    append(block.text)
                }
                is Block.Quote -> {
                    if (isNotEmpty()) append("\n\n")
                    append(blocksToPlain(block.children))
                }
                is Block.ListItem -> {
                    if (isNotEmpty()) append("\n")
                    append(if (block.ordered) "${block.index}. " else "• ")
                    append(blocksToPlain(block.children))
                }
                is Block.CodeBlock -> {
                    if (isNotEmpty()) append("\n\n")
                    append(block.text)
                }
                is Block.Image -> {
                    if (isNotEmpty()) append("\n\n")
                    append("[${block.alt ?: "image"}]")
                }
                is Block.Divider -> {
                    if (isNotEmpty()) append("\n\n")
                    append("———")
                }
            }
        }
    }
}
