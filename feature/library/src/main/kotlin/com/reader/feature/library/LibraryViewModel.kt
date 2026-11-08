package com.reader.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reader.core.common.Result
import com.reader.core.data.BookRepository
import com.reader.core.data.ImportBookUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
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
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, errorMessage = null, infoMessage = null) }
            var ok = 0
            var fail = 0
            var lastError: String? = null
            for (uri in uris) {
                when (val result = importBook(uri)) {
                    is Result.Success -> ok++
                    is Result.Error -> {
                        fail++
                        lastError = result.message
                    }
                }
            }
            _state.update {
                it.copy(
                    isImporting = false,
                    infoMessage = if (ok > 0) "已导入 $ok 本" else null,
                    errorMessage = if (fail > 0) (lastError ?: "部分导入失败 ($fail)") else null,
                )
            }
        }
    }
}
