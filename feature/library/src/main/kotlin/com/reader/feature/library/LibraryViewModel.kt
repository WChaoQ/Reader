package com.reader.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reader.core.common.Result
import com.reader.core.data.BookRepository
import com.reader.core.data.OpenDocumentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val openDocument: OpenDocumentUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private val _navigateToReader = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToReader: SharedFlow<String> = _navigateToReader.asSharedFlow()

    init {
        viewModelScope.launch {
            bookRepository.observeBooks().collect { books ->
                _state.update {
                    it.copy(books = books, isLoading = false)
                }
            }
        }
    }

    fun onEvent(event: LibraryEvent) {
        when (event) {
            LibraryEvent.Refresh -> Unit
            is LibraryEvent.OpenDocuments -> openDocuments(event.uris)
            is LibraryEvent.OpenBook -> viewModelScope.launch {
                _navigateToReader.emit(event.bookId)
            }
            is LibraryEvent.DeleteBook -> viewModelScope.launch {
                bookRepository.delete(event.bookId)
            }
            LibraryEvent.ClearMessage -> _state.update {
                it.copy(errorMessage = null, infoMessage = null)
            }
        }
    }

    private fun openDocuments(uris: List<android.net.Uri>) {
        if (uris.isEmpty() || _state.value.isOpening) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isOpening = true,
                    openingCompleted = 0,
                    openingTotal = uris.size,
                    errorMessage = null,
                    infoMessage = null,
                )
            }

            var succeeded = 0
            var failed = 0
            var lastError: String? = null
            var firstOpenedBookId: String? = null

            try {
                for (uri in uris) {
                    when (val result = openDocument(uri)) {
                        is Result.Success -> {
                            succeeded++
                            if (firstOpenedBookId == null) firstOpenedBookId = result.data.id
                        }
                        is Result.Error -> {
                            failed++
                            lastError = result.message
                        }
                    }
                    _state.update { it.copy(openingCompleted = it.openingCompleted + 1) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                _state.update { it.copy(isOpening = false) }
            }

            _state.update {
                it.copy(
                    infoMessage = when {
                        failed > 0 && succeeded > 0 ->
                            "打开完成：成功 $succeeded 本，失败 $failed 个。原因：${lastError ?: "未知错误"}"
                        failed > 0 ->
                            "打开失败：$failed 个文件。原因：${lastError ?: "未知错误"}"
                        succeeded > 0 -> "已加入书架 $succeeded 本"
                        else -> null
                    },
                    errorMessage = null,
                    openingCompleted = 0,
                    openingTotal = 0,
                )
            }
            firstOpenedBookId?.let { _navigateToReader.emit(it) }
        }
    }
}
