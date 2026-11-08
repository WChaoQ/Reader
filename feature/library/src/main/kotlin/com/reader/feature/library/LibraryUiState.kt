package com.reader.feature.library

import com.reader.core.model.Book

data class LibraryUiState(
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = true,
    val isImporting: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
)

sealed interface LibraryEvent {
    data object Refresh : LibraryEvent
    data class ImportUris(val uris: List<android.net.Uri>) : LibraryEvent
    data class OpenBook(val bookId: String) : LibraryEvent
    data class DeleteBook(val bookId: String) : LibraryEvent
    data object ClearMessage : LibraryEvent
}
