package com.reader.engine.render

import android.graphics.Canvas
import android.util.LruCache
import com.reader.core.model.Chapter
import com.reader.core.model.Locator
import com.reader.engine.format.BookSource
import com.reader.engine.layout.DocumentTextBuilder
import com.reader.engine.layout.LayoutConfig
import com.reader.engine.layout.PageSlice
import com.reader.engine.layout.PaginationEvent
import com.reader.engine.layout.Paginator
import com.reader.engine.layout.StaticLayoutFactory
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class ReflowEngine(
    private val source: BookSource,
    private val config: LayoutConfig,
    private val paginator: Paginator,
    private val scope: CoroutineScope,
    private val bookId: String? = null,
    private val onLayoutReady: () -> Unit = {},
) : ReaderEngine {

    private val _pageCount = MutableStateFlow(PageCountInfo(estimated = 0))
    override val pageCount: StateFlow<PageCountInfo> = _pageCount.asStateFlow()

    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Immutable index snapshot, atomically replaced after each complete rebuild. */
    @Volatile private var pageIndexSnapshot: List<Pair<Int, Int>> = emptyList()
    private val chapterPageCounts = ConcurrentHashMap<Int, Int>()
    private val chapterPages = ConcurrentHashMap<Int, List<PageSlice>>()
    private val layouts = LruCache<Int, ChapterLayout>(8)
    private val layoutLock = Any()
    private val generation = AtomicLong(0)
    private val layoutJobs = ConcurrentHashMap<Int, Job>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closeCompleted = CompletableDeferred<Unit>()

    private var paginateJob: Job? = null
    @Volatile private var closed = false

    fun start(start: Locator = Locator(0, 0)) {
        if (closed) return
        val runGeneration = generation.incrementAndGet()
        paginateJob?.cancel()
        layoutJobs.values.forEach { it.cancel() }
        layoutJobs.clear()
        pageIndexSnapshot = emptyList()
        chapterPageCounts.clear()
        chapterPages.clear()
        synchronized(layoutLock) { layouts.evictAll() }
        _ready.value = false
        _pageCount.value = PageCountInfo(estimated = source.chapterCount.coerceAtLeast(1), available = 0)

        paginateJob = scope.launch {
            paginator.paginate(source, config, start, bookId).collect { event ->
                if (closed || generation.get() != runGeneration) return@collect
                when (event) {
                    is PaginationEvent.ChapterReady -> {
                        val chapter = event.chapter
                        val layout = buildChapterLayout(chapter, event.pages)
                        if (closed || generation.get() != runGeneration) return@collect
                        putLayout(chapter.index, layout)
                        chapterPages[chapter.index] = event.pages
                        chapterPageCounts[chapter.index] = event.pages.size.coerceAtLeast(1)
                        rebuildPageIndex()
                        if (chapter.index == start.chapter) {
                            _ready.value = true
                            onLayoutReady()
                        }
                    }
                    is PaginationEvent.Progress -> {
                        val avg = chapterPageCounts.values.average().let {
                            if (it.isNaN()) 1.0 else it
                        }
                        val estimated = (avg * source.chapterCount).toInt().coerceAtLeast(1)
                        val available = pageIndexSnapshot.size
                        _pageCount.value = PageCountInfo(
                            exact = null,
                            estimated = maxOf(estimated, available),
                            available = available,
                        )
                    }
                    PaginationEvent.Completed -> {
                        rebuildPageIndex()
                        val available = pageIndexSnapshot.size
                        _pageCount.value = PageCountInfo(exact = available, available = available)
                        _ready.value = true
                        onLayoutReady()
                    }
                }
            }
        }
    }

    private fun rebuildPageIndex() {
        val rebuilt = buildList {
            for (chapter in 0 until source.chapterCount) {
                val count = chapterPageCounts[chapter] ?: continue
                repeat(count) { page -> add(chapter to page) }
            }
        }
        pageIndexSnapshot = rebuilt
        if (rebuilt.isNotEmpty()) {
            val exact = if (chapterPageCounts.size == source.chapterCount) rebuilt.size else null
            val average = chapterPageCounts.values.average().let { if (it.isNaN()) 1.0 else it }
            val estimated = (average * source.chapterCount).toInt().coerceAtLeast(rebuilt.size)
            _pageCount.value = PageCountInfo(
                exact = exact,
                estimated = exact ?: estimated,
                available = rebuilt.size,
            )
        }
    }

    private suspend fun buildChapterLayout(
        chapter: Chapter,
        pages: List<PageSlice>,
    ): ChapterLayout = withContext(Dispatchers.Default) {
        val styled = DocumentTextBuilder.build(chapter.blocks, config.paragraphSpacingPx)
        val plain = styled.toString()
        val paint = StaticLayoutFactory.createPaint(config)
        val layout = StaticLayoutFactory.create(styled, paint, config)
        ChapterLayout(
            chapterIndex = chapter.index,
            title = chapter.title,
            plainText = plain,
            layout = layout,
            pages = pages.ifEmpty { listOf(PageSlice(0, 0, 0, plain.length)) },
        )
    }

    private fun ensureLayout(chapter: Int): ChapterLayout? {
        val cached = synchronized(layoutLock) { layouts.get(chapter) }
        if (cached == null) requestLayout(chapter)
        return cached
    }

    private fun requestLayout(chapter: Int) {
        if (closed || layoutJobs.containsKey(chapter)) return
        val requestedGeneration = generation.get()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val pages = chapterPages[chapter] ?: return@launch
                val document = withContext(Dispatchers.IO) { source.loadChapter(chapter) }
                val layout = buildChapterLayout(document, pages)
                if (!closed && generation.get() == requestedGeneration) {
                    putLayout(chapter, layout)
                    onLayoutReady()
                }
            } finally {
                coroutineContext[Job]?.let { layoutJobs.remove(chapter, it) }
            }
        }
        val existing = layoutJobs.putIfAbsent(chapter, job)
        if (existing == null && !closed && generation.get() == requestedGeneration) job.start()
        else {
            if (existing == null) layoutJobs.remove(chapter, job)
            job.cancel()
        }
    }

    private fun putLayout(chapter: Int, layout: ChapterLayout) {
        synchronized(layoutLock) { layouts.put(chapter, layout) }
    }

    override fun locatorOf(pageIndex: Int): Locator {
        val index = pageIndexSnapshot
        if (pageIndex !in index.indices) return Locator(0, 0)
        val (chapter, pageInChapter) = index[pageIndex]
        val slice = chapterPages[chapter]?.getOrNull(pageInChapter)
        val totalPages = index.size.coerceAtLeast(1)
        return Locator(
            chapter = chapter,
            offset = slice?.startOffset ?: 0,
            progression = pageIndex.toFloat() / totalPages,
        )
    }

    override fun pageIndexOf(locator: Locator): Int {
        val index = pageIndexSnapshot
        if (index.isEmpty()) return 0

        val chapter = locator.chapter.coerceIn(0, (source.chapterCount - 1).coerceAtLeast(0))
        val pages = chapterPages[chapter]
        if (pages != null && pages.isNotEmpty()) {
            val base = index.indexOfFirst { it.first == chapter && it.second == 0 }
            if (base >= 0) {
                val offset = locator.offset.coerceAtLeast(0)
                val pageInChapter = pages.indexOfFirst { slice ->
                    if (slice.startOffset == slice.endOffset) {
                        offset == slice.startOffset
                    } else {
                        offset >= slice.startOffset && offset < slice.endOffset
                    }
                }

                if (pageInChapter >= 0) return base + pageInChapter

                // A saved locator may point exactly past the final character (EOF).
                // Keep it on the chapter's final page instead of incorrectly jumping
                // back to the beginning of the book.
                if (offset >= pages.last().endOffset) return base + pages.lastIndex
                if (offset < pages.first().startOffset) return base
            }
        }

        // Pagination is progressive: the requested chapter may not have arrived yet.
        // Prefer the first available page after it; otherwise use the nearest prior page.
        val nextChapterPage = index.indexOfFirst { it.first > chapter }
        if (nextChapterPage >= 0) return nextChapterPage

        val previousChapterPage = index.indexOfLast { it.first < chapter }
        if (previousChapterPage >= 0) return previousChapterPage

        return 0
    }

    override fun drawPage(pageIndex: Int, canvas: Canvas, textColor: Int, backgroundColor: Int) {
        canvas.drawColor(backgroundColor)
        val index = pageIndexSnapshot
        if (pageIndex !in index.indices) return
        val (chapter, pageInChapter) = index[pageIndex]
        // Warm adjacent chapters so swipe-ahead pages are less likely to flash blank.
        if (chapter > 0) ensureLayout(chapter - 1)
        if (chapter + 1 < source.chapterCount) ensureLayout(chapter + 1)
        val chapterLayout = ensureLayout(chapter) ?: return
        val slice = chapterLayout.pages.getOrNull(pageInChapter) ?: return
        val layout = chapterLayout.layout
        if (layout.lineCount <= 0) return
        layout.paint.color = textColor

        val startLine = slice.startLine.coerceIn(0, layout.lineCount - 1)
        val endLine = slice.endLine.coerceIn(startLine, layout.lineCount - 1)
        val top = layout.getLineTop(startLine)
        val bottom = layout.getLineBottom(endLine)
        val contentHeight = (bottom - top).coerceAtLeast(1)

        val scale = minOf(
            canvas.width.toFloat() / config.pageWidthPx.coerceAtLeast(1),
            canvas.height.toFloat() / config.pageHeightPx.coerceAtLeast(1),
        )
        val scaledWidth = config.pageWidthPx * scale
        val scaledHeight = config.pageHeightPx * scale
        val offsetX = (canvas.width - scaledWidth) / 2f
        val offsetY = (canvas.height - scaledHeight) / 2f
        val saveCount = canvas.save()
        try {
            canvas.translate(offsetX, offsetY)
            canvas.scale(scale, scale)
            // Move into the padded content box, then shift so the page's first line sits at y=0.
            // Clip in the *post-shift* coordinate space so only this page's lines are visible.
            canvas.translate(
                config.paddingHorizontalPx.toFloat(),
                config.paddingVerticalPx.toFloat(),
            )
            canvas.clipRect(
                0f,
                0f,
                layout.width.toFloat(),
                contentHeight.toFloat(),
            )
            canvas.translate(0f, -top.toFloat())
            layout.draw(canvas)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    override fun pageText(pageIndex: Int): String {
        val index = pageIndexSnapshot
        if (pageIndex !in index.indices) return ""
        val (chapter, pageInChapter) = index[pageIndex]
        val chapterLayout = ensureLayout(chapter) ?: return ""
        val slice = chapterLayout.pages.getOrNull(pageInChapter) ?: return ""
        val start = slice.startOffset.coerceIn(0, chapterLayout.plainText.length)
        val end = slice.endOffset.coerceIn(start, chapterLayout.plainText.length)
        return chapterLayout.plainText.substring(start, end)
    }

    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        closed = true
        val activeJobs = mutableListOf<Job>()
        paginateJob?.let { activeJobs.add(it) }
        activeJobs.addAll(layoutJobs.values)
        activeJobs.forEach { it.cancel() }
        layoutJobs.clear()
        synchronized(layoutLock) { layouts.evictAll() }
        chapterPages.clear()
        cleanupScope.launch {
            try {
                activeJobs.joinAll()
                source.close()
                closeCompleted.complete(Unit)
            } catch (failure: Throwable) {
                closeCompleted.completeExceptionally(failure)
            } finally {
                cleanupScope.cancel()
            }
        }
    }

    override suspend fun closeAndJoin() {
        close()
        closeCompleted.await()
    }
}
