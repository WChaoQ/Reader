package com.reader.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reader.core.common.Result
import com.reader.core.data.BookRepository
import com.reader.core.data.ImportBookUseCase
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
    private val importBook: ImportBookUseCase,
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
            is LibraryEvent.ImportUris -> import(event.uris)
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

    private fun import(uris: List<android.net.Uri>) {
        if (uris.isEmpty() || _state.value.isImporting) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isImporting = true,
                    importCompleted = 0,
                    importTotal = uris.size,
                    errorMessage = null,
                    infoMessage = null,
                )
            }

            var succeeded = 0
            var failed = 0
            var lastError: String? = null

            try {
                for (uri in uris) {
                    when (val result = importBook(uri)) {
                        is Result.Success -> succeeded++
                        is Result.Error -> {
                            failed++
                            lastError = result.message
                        }
                    }
                    _state.update { it.copy(importCompleted = it.importCompleted + 1) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                _state.update { it.copy(isImporting = false) }
            }

            _state.update {
                it.copy(
                    infoMessage = if (succeeded > 0) "成功导入 $succeeded 本" else null,
                    errorMessage = if (failed > 0) {
                        "有 $failed 个文件导入失败：${lastError ?: "未知错误"}"
                    } else {
                        null
                    },
                    importCompleted = 0,
                    importTotal = 0,
                )
            }
        }
    }
}
