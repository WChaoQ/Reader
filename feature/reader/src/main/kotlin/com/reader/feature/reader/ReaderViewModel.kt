package com.reader.feature.reader

import android.graphics.Canvas
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reader.core.common.ReaderDispatchers
import com.reader.core.data.BookmarkRepository
import com.reader.core.data.BookRepository
import com.reader.core.data.ProgressRepository
import com.reader.core.model.Bookmark
import com.reader.core.data.ReaderPreferencesRepository
import com.reader.core.model.Block
import com.reader.core.model.BookFormat
import com.reader.core.model.Locator
import com.reader.core.model.TocEntry
import com.reader.engine.layout.LayoutConfig
import com.reader.engine.render.PageCountInfo
import com.reader.engine.render.ReaderCapability
import com.reader.engine.render.ReaderEngine
import com.reader.engine.render.ReaderSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class SearchHit(
    val locator: Locator,
    val snippet: String,
    val chapterTitle: String,
)

data class ReaderUiState(
    val title: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentPage: Int = 0,
    val pageCount: PageCountInfo = PageCountInfo(),
    val pageReady: Boolean = false,
    val renderRevision: Int = 0,
    val theme: String = "day",
    val format: BookFormat? = null,
    val toc: List<TocEntry> = emptyList(),
    val tocVisible: Boolean = false,
    val bookmarks: List<Bookmark> = emptyList(),
    val bookmarksVisible: Boolean = false,
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    val searchHits: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val snackbar: String? = null,
)

sealed interface ReaderEvent {
    data class JumpToPage(val page: Int) : ReaderEvent
    data class JumpToLocator(val locator: Locator) : ReaderEvent
    /** Jump after side-page tap in browse mode. */
    data class SelectPage(val page: Int) : ReaderEvent
    data object ToggleToc : ReaderEvent
    data object ToggleBookmarks : ReaderEvent
    data object ToggleSearch : ReaderEvent
    data object AddBookmark : ReaderEvent
    data class DeleteBookmark(val id: String) : ReaderEvent
    data class Search(val query: String) : ReaderEvent
    data object ClearSnackbar : ReaderEvent
    data object ForceSaveProgress : ReaderEvent
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    private val progressRepository: ProgressRepository,
    private val settingsRepository: ReaderPreferencesRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val sessionFactory: ReaderSessionFactory,
    private val dispatchers: ReaderDispatchers,
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var session: ReaderSession? = null
    private val engine: ReaderEngine? get() = session?.engine
    private val openGeneration = AtomicLong(0)
    private val openMutex = Mutex()
    private var saveJob: Job? = null
    private var searchJob: Job? = null
    private var openJob: Job? = null
    private var engineObserveJob: Job? = null
    private var pageJumpJob: Job? = null
    private var currentLocator: Locator = Locator(0, 0)
    private var pageWidth = 1080
    private var pageHeight = 1920
    private var fontScalePxPerSp = 1f
    private var viewportInitialized = false

    init {
        // Opening is deferred until Compose reports the actual viewport. This avoids
        // an expensive fallback-size pagination pass followed by an immediate rebuild.
        viewModelScope.launch {
            bookmarkRepository.observe(bookId).collect { list ->
                _state.update { it.copy(bookmarks = list) }
            }
        }
    }

    fun onViewportSized(
        widthPx: Int,
        heightPx: Int,
        fontScalePxPerSp: Float,
    ) {
        if (widthPx <= 0 || heightPx <= 0 || !fontScalePxPerSp.isFinite() || fontScalePxPerSp <= 0f) return
        val firstMeasurement = !viewportInitialized
        viewportInitialized = true
        val changed = firstMeasurement ||
            widthPx != pageWidth ||
            heightPx != pageHeight ||
            fontScalePxPerSp != this.fontScalePxPerSp
        if (!changed) return

        pageWidth = widthPx
        pageHeight = heightPx
        this.fontScalePxPerSp = fontScalePxPerSp
        // The first open may still be in progress when Compose reports its real
        // viewport. Restart it as well; otherwise the engine can retain the fallback
        // 1080×1920 / 1× density configuration. Preserve a saved locator when available.
        val restartLocator = currentLocator.takeIf { _state.value.format != null }
        openJob?.cancel()
        openJob = viewModelScope.launch { open(restartLocator) }
    }

    fun onEvent(event: ReaderEvent) {
        when (event) {
            is ReaderEvent.JumpToPage -> jump(event.page)
            is ReaderEvent.SelectPage -> jump(event.page)
            is ReaderEvent.JumpToLocator -> {
                val page = engine?.pageIndexOf(event.locator) ?: 0
                jump(page, targetLocator = event.locator)
                _state.update {
                    it.copy(
                        tocVisible = false,
                        bookmarksVisible = false,
                        searchVisible = false,
                    )
                }
            }
            ReaderEvent.ToggleToc -> _state.update {
                it.copy(
                    tocVisible = !it.tocVisible,
                    bookmarksVisible = false,
                    searchVisible = false,
                )
            }
            ReaderEvent.ToggleBookmarks -> _state.update {
                it.copy(
                    bookmarksVisible = !it.bookmarksVisible,
                    tocVisible = false,
                    searchVisible = false,
                )
            }
            ReaderEvent.ToggleSearch -> _state.update {
                it.copy(
                    searchVisible = !it.searchVisible,
                    tocVisible = false,
                    bookmarksVisible = false,
                )
            }
            ReaderEvent.AddBookmark -> addBookmark()
            is ReaderEvent.DeleteBookmark -> viewModelScope.launch { bookmarkRepository.delete(event.id) }
            is ReaderEvent.Search -> runSearch(event.query)
            ReaderEvent.ClearSnackbar -> _state.update { it.copy(snackbar = null) }
            ReaderEvent.ForceSaveProgress -> saveProgressNow()
        }
    }

    fun drawPage(
        pageIndex: Int,
        canvas: Canvas,
        textColor: Int,
        backgroundColor: Int,
    ) {
        engine?.drawPage(pageIndex, canvas, textColor, backgroundColor)
    }

    private fun addBookmark() {
        val eng = engine ?: return
        val locator = currentLocator
        val snippet = if (ReaderCapability.PAGE_TEXT in eng.capabilities) {
            eng.pageText(_state.value.currentPage).take(120)
        } else {
            ""
        }
        viewModelScope.launch {
            bookmarkRepository.add(bookId, locator, snippet)
            _state.update { it.copy(snackbar = "已添加书签") }
        }
    }

    private fun runSearch(query: String) {
        searchJob?.cancel()
        _state.update { it.copy(searchQuery = query, searching = true, searchHits = emptyList()) }
        if (query.isBlank()) {
            _state.update { it.copy(searching = false) }
            return
        }
        val activeSession = session
        if (activeSession == null ||
            ReaderCapability.FULL_TEXT_SEARCH !in activeSession.engine.capabilities
        ) {
            _state.update { it.copy(searching = false, snackbar = "当前格式暂不支持全文搜索") }
            return
        }
        val source = activeSession.source ?: run {
            _state.update { it.copy(searching = false, snackbar = "当前格式暂不支持全文搜索") }
            return
        }
        searchJob = activeSession.launchTask(dispatchers.io) {
            val hits = mutableListOf<SearchHit>()
            val q = query.trim()
            for (ch in 0 until source.chapterCount) {
                val chapter = try {
                    source.loadChapter(ch)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    continue
                }
                val plain = chapter.blocks.joinToString("\n") {
                    when (it) {
                        is Block.Paragraph -> it.text
                        is Block.Heading -> it.text
                        is Block.CodeBlock -> it.text
                        else -> ""
                    }
                }
                var from = 0
                while (true) {
                    val idx = plain.indexOf(q, startIndex = from, ignoreCase = true)
                    if (idx < 0) break
                    val start = (idx - 20).coerceAtLeast(0)
                    val end = (idx + q.length + 40).coerceAtMost(plain.length)
                    val snippet = plain.substring(start, end).replace('\n', ' ')
                    hits += SearchHit(
                        locator = Locator(chapter = ch, offset = idx),
                        snippet = snippet,
                        chapterTitle = chapter.title ?: "第${ch + 1}章",
                    )
                    from = idx + q.length
                    if (hits.size >= 50) break
                }
                if (hits.size >= 50) break
            }
            _state.update { it.copy(searching = false, searchHits = hits) }
        }
    }

    private fun jump(page: Int, targetLocator: Locator? = null) {
        val activeEngine = engine ?: return
        val count = activeEngine.pageCount.value.available
        val next = page.coerceIn(0, (count - 1).coerceAtLeast(0))

        // Do not switch the visible page until its bitmap is ready. This keeps the
        // previous PDF page on screen instead of briefly painting an empty canvas.
        pageJumpJob?.cancel()
        pageJumpJob = viewModelScope.launch {
            if (!activeEngine.awaitPage(next)) return@launch
            if (engine !== activeEngine) return@launch
            currentLocator = targetLocator ?: activeEngine.locatorOf(next)
            _state.update { it.copy(currentPage = next) }
            scheduleSaveProgress()
        }
    }

    private fun scheduleSaveProgress() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            saveProgressNow()
        }
    }

    private fun saveProgressNow() {
        val eng = engine ?: return
        val locator = currentLocator
        viewModelScope.launch {
            progressRepository.save(bookId, locator)
            bookRepository.updateLastRead(bookId, System.currentTimeMillis())
        }
    }

    private suspend fun open(startLocator: Locator? = null) {
        openMutex.withLock { openInternal(startLocator) }
    }

    private suspend fun openInternal(startLocator: Locator?) {
        val generation = openGeneration.incrementAndGet()
        pageJumpJob?.cancel()
        pageJumpJob = null
        engineObserveJob?.cancel()
        engineObserveJob = null
        _state.update { it.copy(isLoading = true, error = null, pageReady = false) }
        searchJob?.cancel()
        searchJob?.join()
        val previousSession = session
        session = null
        try {
            withContext(NonCancellable) { previousSession?.closeAndJoin() }
            val book = bookRepository.getBook(bookId)
                ?: run {
                    _state.update { it.copy(isLoading = false, error = "书籍不存在") }
                    return
                }
            val settings = settingsRepository.settings.first()
            val saved = startLocator ?: progressRepository.get(bookId) ?: Locator(0, 0)
            currentLocator = saved
            val config = LayoutConfig(
                pageWidthPx = pageWidth,
                pageHeightPx = pageHeight,
                textSizePx = settings.fontSizeSp * fontScalePxPerSp,
                lineSpacingMult = settings.lineSpacingMult,
                paragraphSpacingPx = settings.paragraphSpacingPx,
                justify = settings.justify,
            )
            val openedSession = sessionFactory.open(
                book = book,
                layoutConfig = config,
                initialLocator = saved,
                theme = settings.theme,
                onLayoutReady = {
                    if (openGeneration.get() == generation) {
                        _state.update { state -> state.copy(renderRevision = state.renderRevision + 1) }
                    }
                },
                onPageRendered = {
                    if (openGeneration.get() == generation) {
                        _state.update { state -> state.copy(renderRevision = state.renderRevision + 1) }
                    }
                },
            )
            if (openGeneration.get() != generation) {
                withContext(NonCancellable) { openedSession.closeAndJoin() }
                return
            }
            session = openedSession
            val eng = openedSession.engine
            val toc = openedSession.toc
            // Both reflow and fixed-layout engines become ready only when the opening
            // page is actually drawable. In particular, PDF construction alone is not
            // sufficient because its first bitmap is rendered asynchronously.
            eng.ready.first { it }
            val startPage = eng.pageIndexOf(saved)
            _state.update {
                it.copy(
                    isLoading = false,
                    title = book.title,
                    currentPage = startPage,
                    pageCount = eng.pageCount.value,
                    pageReady = true,
                    theme = settings.theme,
                    format = book.format,
                    toc = toc,
                )
            }
            engineObserveJob = viewModelScope.launch {
                eng.pageCount.collect { info ->
                    if (openGeneration.get() != generation || session?.engine !== eng) return@collect
                    val resolvedPage = eng.pageIndexOf(currentLocator)
                    _state.update { s -> s.copy(pageCount = info, currentPage = resolvedPage) }
                }
            }
            bookRepository.updateLastRead(bookId, System.currentTimeMillis())
        } catch (cancelled: CancellationException) {
            if (openGeneration.get() == generation) {
                withContext(NonCancellable) { session?.closeAndJoin() }
                session = null
            }
            throw cancelled
        } catch (security: SecurityException) {
            if (openGeneration.get() == generation) {
                withContext(NonCancellable) { session?.closeAndJoin() }
                session = null
                _state.update {
                    it.copy(isLoading = false, error = "无法访问原文件，请重新从系统文件选择器打开")
                }
            }
        } catch (missing: java.io.FileNotFoundException) {
            if (openGeneration.get() == generation) {
                withContext(NonCancellable) { session?.closeAndJoin() }
                session = null
                _state.update {
                    it.copy(isLoading = false, error = "原文件已移动或删除，请重新选择文档")
                }
            }
        } catch (t: Throwable) {
            if (openGeneration.get() == generation) {
                withContext(NonCancellable) { session?.closeAndJoin() }
                session = null
                _state.update { it.copy(isLoading = false, error = t.message ?: "打开失败") }
            }
        }
    }

    override fun onCleared() {
        saveProgressNow()
        engineObserveJob?.cancel()
        searchJob?.cancel()
        openGeneration.incrementAndGet()
        session?.close()
        session = null
        super.onCleared()
    }
}
