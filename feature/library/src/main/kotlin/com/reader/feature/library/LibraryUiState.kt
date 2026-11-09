package com.reader.feature.library

import com.reader.core.model.Book

data class LibraryUiState(
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = true,
    val isOpening: Boolean = false,
    val openingCompleted: Int = 0,
    val openingTotal: Int = 0,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
)

sealed interface LibraryEvent {
    data object Refresh : LibraryEvent
    data class OpenDocuments(val uris: List<android.net.Uri>) : LibraryEvent
    data class OpenBook(val bookId: String) : LibraryEvent
    data class DeleteBook(val bookId: String) : LibraryEvent
    data object ClearMessage : LibraryEvent
}
