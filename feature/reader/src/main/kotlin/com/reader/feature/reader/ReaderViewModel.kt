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
import com.reader.engine.layout.DocumentTextBuilder
import com.reader.core.model.BookFormat
import com.reader.core.model.Locator
import com.reader.core.model.TocEntry
import com.reader.engine.format.BookSource
import com.reader.engine.layout.LayoutConfig
import com.reader.engine.render.PageCountInfo
import com.reader.engine.render.ReaderCapability
import com.reader.engine.render.ReaderEngine
import com.reader.engine.render.ReaderEngineFailure
import com.reader.engine.render.ReaderSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

private const val SEARCH_DEBOUNCE_MILLIS = 250L

data class SearchHit(
    val locator: Locator,
    val snippet: String,
    val chapterTitle: String,
)

/**
 * Searches every chapter as one operation. Source access failures must reach the caller;
 * returning partial hits would incorrectly present an incomplete search as successful.
 */
internal suspend fun searchBookSource(source: BookSource, query: String): List<SearchHit> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return emptyList()

    val context = currentCoroutineContext()
    val hits = mutableListOf<SearchHit>()
    for (chapterIndex in 0 until source.chapterCount) {
        context.ensureActive()
        val chapter = source.loadChapter(chapterIndex)
        context.ensureActive()
        // Use the exact text built for pagination so search offsets map to rendered content.
        val plainText = DocumentTextBuilder.build(chapter.blocks).toString()
        var from = 0
        while (true) {
            val index = plainText.indexOf(
                normalizedQuery,
                startIndex = from,
                ignoreCase = true,
            )
            if (index < 0) break
            val start = (index - 20).coerceAtLeast(0)
            val end = (index + normalizedQuery.length + 40).coerceAtMost(plainText.length)
            hits += SearchHit(
                locator = Locator(chapter = chapterIndex, offset = index),
                snippet = plainText.substring(start, end).replace('\n', ' '),
                chapterTitle = chapter.title ?: "第${chapterIndex + 1}章",
            )
            if (hits.size >= 50) return hits
            from = index + normalizedQuery.length
        }
    }
    return hits
}

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
    val searchError: String? = null,
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
    data object RetryOpen : ReaderEvent
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
            ReaderEvent.ForceSaveProgress -> viewModelScope.launch { saveProgressAndWait() }
            ReaderEvent.RetryOpen -> retryOpen()
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
        searchJob = null
        _state.update {
            it.copy(
                searchQuery = query,
                searching = query.isNotBlank(),
                searchHits = emptyList(),
                searchError = null,
            )
        }
        if (query.isBlank()) return

        val activeSession = session
        if (activeSession == null ||
            ReaderCapability.FULL_TEXT_SEARCH !in activeSession.engine.capabilities
        ) {
            val message = "当前格式暂不支持全文搜索"
            _state.update { it.copy(searching = false, searchError = message, snackbar = message) }
            return
        }
        val source = activeSession.source ?: run {
            val message = "当前格式暂不支持全文搜索"
            _state.update { it.copy(searching = false, searchError = message, snackbar = message) }
            return
        }
        val searchGeneration = openGeneration.get()
        searchJob = activeSession.launchTask(dispatchers.io) {
            try {
                delay(SEARCH_DEBOUNCE_MILLIS)
                currentCoroutineContext().ensureActive()
                val hits = searchBookSource(source, query)
                currentCoroutineContext().ensureActive()
                if (session === activeSession && openGeneration.get() == searchGeneration) {
                    _state.update { current ->
                        if (current.searchQuery != query) current
                        else current.copy(searching = false, searchHits = hits, searchError = null)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val message = ReaderEngineFailure.fromException(
                    failure,
                    "全文搜索失败，请重试",
                ).message
                if (session === activeSession && openGeneration.get() == searchGeneration) {
                    _state.update { current ->
                        if (current.searchQuery != query) current
                        else current.copy(
                            searching = false,
                            searchHits = emptyList(),
                            searchError = message,
                            snackbar = message,
                        )
                    }
                }
            }
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
            if (engine == null) return@launch
            persistProgress(currentLocator)
        }
    }

    private suspend fun persistProgress(locator: Locator) {
        progressRepository.save(bookId, locator)
        bookRepository.updateLastRead(bookId, System.currentTimeMillis())
    }

    /**
     * Persist the last fully selected page before leaving the reader.
     * A pending page render is cancelled so it cannot race the final save.
     */
    suspend fun saveProgressAndWait() {
        withContext(NonCancellable) {
            pageJumpJob?.cancelAndJoin()
            saveJob?.cancelAndJoin()
            if (engine != null) persistProgress(currentLocator)
        }
    }

    /**
     * Best-effort fallback for ViewModel teardown. viewModelScope is already cancelled
     * when onCleared runs, so this final write needs a short independent IO scope.
     * Normal navigation still uses saveProgressAndWait() and waits for confirmation.
     */
    private fun saveProgressNow() {
        if (engine == null) return
        val locator = currentLocator
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            try {
                persistProgress(locator)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // There is no longer a UI to report a best-effort teardown failure to.
            }
        }
    }

    private fun retryOpen() {
        if (_state.value.isLoading) return
        openJob?.cancel()
        openJob = viewModelScope.launch { open(currentLocator) }
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
        searchJob?.cancelAndJoin()
        searchJob = null
        _state.update {
            it.copy(
                isLoading = true,
                error = null,
                pageReady = false,
                searchVisible = false,
                searching = false,
                searchHits = emptyList(),
                searchError = null,
            )
        }
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
            val openingFailure = eng.awaitReadyOrFailure()
            if (openingFailure != null) {
                withContext(NonCancellable) {
                    try {
                        openedSession.closeAndJoin()
                    } catch (_: Exception) {
                        // Preserve the user-facing opening failure; cleanup remains best effort.
                    }
                }
                if (session === openedSession) session = null
                _state.update {
                    it.copy(
                        isLoading = false,
                        pageReady = false,
                        error = openingFailure.message,
                    )
                }
                return
            }
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
                combine(eng.pageCount, eng.failure) { info, failure -> info to failure }
                    .collect { (info, failure) ->
                        if (openGeneration.get() != generation || session?.engine !== eng) return@collect
                        if (failure != null) {
                            _state.update {
                                it.copy(
                                    isLoading = false,
                                    pageReady = false,
                                    error = failure.message,
                                )
                            }
                        } else {
                            val resolvedPage = eng.pageIndexOf(currentLocator)
                            _state.update { s -> s.copy(pageCount = info, currentPage = resolvedPage) }
                        }
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
        } catch (failure: OutOfMemoryError) {
            // Engine implementations report asynchronous OOM through their typed failure
            // flow. This boundary handles OOM raised before a Session can be returned
            // (for example, while constructing PdfRenderer or building the initial TOC).
            if (openGeneration.get() == generation) {
                withContext(NonCancellable) {
                    try {
                        session?.closeAndJoin()
                    } catch (_: Exception) {
                        // Keep the recoverable opening error visible; the factory already
                        // rolls back resources when it fails before returning a Session.
                    }
                }
                session = null
                _state.update {
                    it.copy(
                        isLoading = false,
                        pageReady = false,
                        error = ReaderEngineFailure.fromOutOfMemoryError(
                            "文档过大或设备内存不足，无法打开，请关闭其他应用后重试",
                        ).message,
                    )
                }
            }
        } catch (failure: Error) {
            if (openGeneration.get() == generation) {
                withContext(NonCancellable) { session?.closeAndJoin() }
                session = null
            }
            throw failure
        } catch (failure: Exception) {
            if (openGeneration.get() == generation) {
                withContext(NonCancellable) { session?.closeAndJoin() }
                session = null
                _state.update { it.copy(isLoading = false, error = failure.message ?: "打开失败") }
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
