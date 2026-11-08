package com.reader.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reader.core.data.BookRepository
import com.reader.core.data.ProgressRepository
import com.reader.core.model.Block
import com.reader.core.model.BookFormat
import com.reader.core.model.Locator
import com.reader.core.storage.BookFileStore
import com.reader.engine.format.FormatRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReaderUiState(
    val title: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val chapterCount: Int = 0,
    val currentChapter: Int = 0,
    val previewText: String = "",
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    private val progressRepository: ProgressRepository,
    private val fileStore: BookFileStore,
    private val formatRegistry: FormatRegistry,
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"])

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { open() }
    }

    private suspend fun open() {
        try {
            val book = bookRepository.getBook(bookId)
                ?: run {
                    _state.update { it.copy(isLoading = false, error = "书籍不存在") }
                    return
                }
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
            val source = formatRegistry.open(file, file.name)
            val chapter = source.loadChapter(0)
            val preview = chapter.blocks.joinToString("\n\n") {
                when (it) {
                    is Block.Paragraph -> it.text
                    is Block.Heading -> it.text
                    else -> ""
                }
            }
            bookRepository.updateLastRead(bookId, System.currentTimeMillis())
            progressRepository.save(bookId, Locator(0, 0, 0f))
            _state.update {
                it.copy(
                    isLoading = false,
                    title = book.title,
                    chapterCount = source.chapterCount,
                    currentChapter = 0,
                    previewText = preview,
                )
            }
            source.close()
        } catch (t: Throwable) {
            _state.update {
                it.copy(isLoading = false, error = t.message ?: "打开失败")
            }
        }
    }
}
