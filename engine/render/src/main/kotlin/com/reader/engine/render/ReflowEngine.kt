package com.reader.engine.render

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.reader.core.model.Chapter
import com.reader.core.model.Locator
import com.reader.engine.format.BookSource
import com.reader.engine.layout.DocumentTextBuilder
import com.reader.engine.layout.LayoutConfig
import com.reader.engine.layout.PageSlice
import com.reader.engine.layout.PaginationEvent
import com.reader.engine.layout.Paginator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
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
    private val onLayoutReady: () -> Unit = {},
) : ReaderEngine {

    private val _pageCount = MutableStateFlow(PageCountInfo(estimated = 0))
    override val pageCount: StateFlow<PageCountInfo> = _pageCount.asStateFlow()

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val pageIndexMap = mutableListOf<Pair<Int, Int>>()
    private val chapterPageCounts = ConcurrentHashMap<Int, Int>()
    private val chapterPages = ConcurrentHashMap<Int, List<PageSlice>>()
    private val layouts = LruCache<Int, ChapterLayout>(3)
    private val layoutJobs = ConcurrentHashMap<Int, Job>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var paginateJob: Job? = null
    @Volatile private var closed = false

    fun start(start: Locator = Locator(0, 0)) {
        paginateJob?.cancel()
        pageIndexMap.clear()
        chapterPageCounts.clear()
        layouts.evictAll()
        _ready.value = false
        _pageCount.value = PageCountInfo(estimated = source.chapterCount.coerceAtLeast(1), available = 0)

        paginateJob = scope.launch {
            paginator.paginate(source, config, start).collect { event ->
                when (event) {
                    is PaginationEvent.ChapterReady -> {
                        val chapter = event.chapter
                        val layout = buildChapterLayout(chapter, event.pages)
                        layouts.put(chapter.index, layout)
                        chapterPages[chapter.index] = event.pages
                        chapterPageCounts[chapter.index] = event.pages.size.coerceAtLeast(1)
                        rebuildPageIndex()
                        if (chapter.index == start.chapter) {
                            _ready.value = true
                        }
                    }
                    is PaginationEvent.Progress -> {
                        val avg = chapterPageCounts.values.average().let {
                            if (it.isNaN()) 1.0 else it
                        }
                        val estimated = (avg * source.chapterCount).toInt().coerceAtLeast(1)
                        _pageCount.value = PageCountInfo(exact = null, estimated = maxOf(estimated, pageIndexMap.size), available = pageIndexMap.size)
                    }
                    PaginationEvent.Completed -> {
                        rebuildPageIndex()
                        _pageCount.value = PageCountInfo(exact = pageIndexMap.size, available = pageIndexMap.size)
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
            _pageCount.value = PageCountInfo(exact = exact, estimated = pageIndexMap.size, available = pageIndexMap.size)
        }
    }

    /** Chapter parsing and StaticLayout construction are deliberately kept off the UI thread. */
    private suspend fun buildChapterLayout(
        chapter: Chapter,
        pages: List<PageSlice>,
    ): ChapterLayout = withContext(Dispatchers.Default) {
        val styled = DocumentTextBuilder.build(chapter.blocks)
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
            chapterIndex = chapter.index,
            title = chapter.title,
            plainText = plain,
            layout = layout,
            pages = pages.ifEmpty { listOf(PageSlice(0, 0, 0, plain.length)) },
        )
    }

    private fun ensureLayout(chapter: Int): ChapterLayout? {
        val cached = layouts.get(chapter)
        if (cached == null) requestLayout(chapter)
        return cached
    }

    /** Rebuilds an evicted chapter layout lazily instead of leaving older pages permanently blank. */
    private fun requestLayout(chapter: Int) {
        if (closed || layoutJobs.containsKey(chapter)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val pages = chapterPages[chapter] ?: return@launch
                val document = withContext(Dispatchers.IO) { source.loadChapter(chapter) }
                val layout = buildChapterLayout(document, pages)
                if (!closed) {
                    layouts.put(chapter, layout)
                    onLayoutReady()
                }
            } finally {
                layoutJobs.remove(chapter)
            }
        }
        val existing = layoutJobs.putIfAbsent(chapter, job)
        if (existing == null) job.start() else job.cancel()
    }

    override fun locatorOf(pageIndex: Int): Locator {
        if (pageIndexMap.isEmpty() || pageIndex !in pageIndexMap.indices) return Locator(0, 0)
        val (chapter, pageInChapter) = pageIndexMap[pageIndex]
        val slice = chapterPages[chapter]?.getOrNull(pageInChapter)
        val totalPages = pageIndexMap.size.coerceAtLeast(1)
        return Locator(
            chapter = chapter,
            offset = slice?.startOffset ?: 0,
            progression = pageIndex.toFloat() / totalPages,
        )
    }

    override fun pageIndexOf(locator: Locator): Int {
        if (pageIndexMap.isEmpty()) return 0
        val pages = chapterPages[locator.chapter]
        if (pages != null) {
            val index = pages.indexOfFirst {
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
        if (closed) return
        closed = true
        val activeJobs = mutableListOf<Job>()
        paginateJob?.let { activeJobs.add(it) }
        activeJobs.addAll(layoutJobs.values)
        activeJobs.forEach { it.cancel() }
        layoutJobs.clear()
        layouts.evictAll()
        chapterPages.clear()
        cleanupScope.launch {
            activeJobs.joinAll()
            try {
                source.close()
            } finally {
                cleanupScope.cancel()
            }
        }
    }

}
