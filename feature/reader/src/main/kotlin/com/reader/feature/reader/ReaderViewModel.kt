package com.reader.feature.reader

import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reader.core.data.BookRepository
import com.reader.core.data.ProgressRepository
import com.reader.core.datastore.SettingsRepository
import com.reader.core.model.BookFormat
import com.reader.core.model.Locator
import com.reader.core.model.TocEntry
import com.reader.core.storage.BookFileStore
import com.reader.engine.format.FormatRegistry
import com.reader.engine.layout.LayoutConfig
import com.reader.engine.layout.SimplePaginator
import com.reader.engine.render.FixedEngine
import com.reader.engine.render.PageCountInfo
import com.reader.engine.render.ReaderEngine
import com.reader.engine.render.ReflowEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ReaderMode { Reading, Overview }

data class ReaderUiState(
    val title: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentPage: Int = 0,
    val pageCount: PageCountInfo = PageCountInfo(),
    val chromeVisible: Boolean = true,
    val pageReady: Boolean = false,
    val theme: String = "day",
    val format: BookFormat? = null,
    val mode: ReaderMode = ReaderMode.Reading,
    val toc: List<TocEntry> = emptyList(),
    val tocVisible: Boolean = false,
)

sealed interface ReaderEvent {
    data object TapLeft : ReaderEvent
    data object TapRight : ReaderEvent
    data object TapCenter : ReaderEvent
    data class JumpToPage(val page: Int) : ReaderEvent
    data class JumpToLocator(val locator: Locator) : ReaderEvent
    data object ToggleOverview : ReaderEvent
    data object ToggleToc : ReaderEvent
    data object ForceSaveProgress : ReaderEvent
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    private val progressRepository: ProgressRepository,
    private val settingsRepository: SettingsRepository,
    private val fileStore: BookFileStore,
    private val formatRegistry: FormatRegistry,
    private val paginator: SimplePaginator,
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var engine: ReaderEngine? = null
    private var saveJob: Job? = null
    private var pageWidth = 1080
    private var pageHeight = 1920

    val textColorArgb: Int
        get() = when (_state.value.theme) {
            "night" -> Color(0xFFE0E0E0).toArgb()
            "sepia" -> Color(0xFF5B4636).toArgb()
            else -> Color(0xFF1A1A1A).toArgb()
        }
    val backgroundColorArgb: Int
        get() = when (_state.value.theme) {
            "night" -> Color(0xFF121212).toArgb()
            "sepia" -> Color(0xFFF5E6C8).toArgb()
            else -> Color(0xFFFAFAFA).toArgb()
        }

    init {
        viewModelScope.launch { open() }
    }

    fun onViewportSized(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (widthPx == pageWidth && heightPx == pageHeight) return
        pageWidth = widthPx
        pageHeight = heightPx
        if (engine != null && _state.value.format != BookFormat.PDF) {
            viewModelScope.launch { open() }
        }
    }

    fun onEvent(event: ReaderEvent) {
        when (event) {
            ReaderEvent.TapLeft -> turn(-1)
            ReaderEvent.TapRight -> turn(1)
            ReaderEvent.TapCenter -> _state.update {
                it.copy(chromeVisible = !it.chromeVisible, tocVisible = false)
            }
            is ReaderEvent.JumpToPage -> jump(event.page)
            is ReaderEvent.JumpToLocator -> {
                val page = engine?.pageIndexOf(event.locator) ?: 0
                jump(page)
                _state.update {
                    it.copy(mode = ReaderMode.Reading, tocVisible = false, chromeVisible = false)
                }
            }
            ReaderEvent.ToggleOverview -> _state.update {
                val next = if (it.mode == ReaderMode.Overview) ReaderMode.Reading else ReaderMode.Overview
                it.copy(mode = next, chromeVisible = next == ReaderMode.Overview, tocVisible = false)
            }
            ReaderEvent.ToggleToc -> _state.update {
                it.copy(tocVisible = !it.tocVisible, chromeVisible = true)
            }
            ReaderEvent.ForceSaveProgress -> saveProgressNow()
        }
    }

    fun drawPage(pageIndex: Int, canvas: Canvas) {
        engine?.drawPage(pageIndex, canvas, textColorArgb, backgroundColorArgb)
    }

    fun pagePreview(pageIndex: Int): String =
        engine?.pageText(pageIndex)?.take(120)?.replace('\n', ' ') ?: ""

    private fun turn(delta: Int) {
        if (_state.value.mode == ReaderMode.Overview) return
        val eng = engine ?: return
        val count = eng.pageCount.value.display
        if (count <= 0) return
        val next = (_state.value.currentPage + delta).coerceIn(0, count - 1)
        if (next == _state.value.currentPage) return
        _state.update { it.copy(currentPage = next, chromeVisible = false) }
        scheduleSaveProgress()
    }

    private fun jump(page: Int) {
        val count = engine?.pageCount?.value?.display ?: return
        val next = page.coerceIn(0, (count - 1).coerceAtLeast(0))
        _state.update { it.copy(currentPage = next, mode = ReaderMode.Reading) }
        scheduleSaveProgress()
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
        val locator = eng.locatorOf(_state.value.currentPage)
        viewModelScope.launch {
            progressRepository.save(bookId, locator)
            bookRepository.updateLastRead(bookId, System.currentTimeMillis())
        }
    }

    private suspend fun open() {
        _state.update { it.copy(isLoading = true, error = null, pageReady = false) }
        engine?.close()
        engine = null
        try {
            val book = bookRepository.getBook(bookId)
                ?: run {
                    _state.update { it.copy(isLoading = false, error = "书籍不存在") }
                    return
                }
            val settings = settingsRepository.settings.first()
            val ext = when (book.format) {
                BookFormat.TXT -> "txt"
                BookFormat.MD -> "md"
                BookFormat.EPUB -> "epub"
                BookFormat.PDF -> "pdf"
            }
            val file = fileStore.originalFile(bookId, ext)
            if (!file.exists()) {
                _state.update { it.copy(isLoading = false, error = "文件缺失") }
                return
            }

            val saved = progressRepository.get(bookId) ?: Locator(0, 0)
            var toc: List<TocEntry> = emptyList()
            val eng: ReaderEngine = if (book.format == BookFormat.PDF) {
                FixedEngine(
                    file = file,
                    pageWidthPx = pageWidth,
                    nightMode = settings.theme == "night",
                ).also {
                    toc = (0 until it.pageCount.value.display).map { page ->
                        TocEntry("第 ${page + 1} 页", Locator(page, 0))
                    }
                }
            } else {
                val source = formatRegistry.open(file, file.name)
                toc = runCatching { source.toc() }.getOrDefault(emptyList())
                val config = LayoutConfig(
                    pageWidthPx = pageWidth,
                    pageHeightPx = pageHeight,
                    textSizePx = settings.fontSizeSp * 3f,
                    lineSpacingMult = settings.lineSpacingMult,
                    paragraphSpacingPx = settings.paragraphSpacingPx,
                    justify = settings.justify,
                )
                ReflowEngine(source, config, paginator, viewModelScope).also { it.start(saved) }
            }
            engine = eng

            if (eng is ReflowEngine) {
                eng.ready.first { it }
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
            viewModelScope.launch {
                eng.pageCount.collect { info ->
                    _state.update { s -> s.copy(pageCount = info) }
                }
            }
            bookRepository.updateLastRead(bookId, System.currentTimeMillis())
        } catch (t: Throwable) {
            _state.update {
                it.copy(isLoading = false, error = t.message ?: "打开失败")
            }
        }
    }

    override fun onCleared() {
        saveProgressNow()
        engine?.close()
        super.onCleared()
    }
}
