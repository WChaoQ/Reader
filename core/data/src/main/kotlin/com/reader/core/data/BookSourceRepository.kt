package com.reader.core.data

import com.reader.core.model.Book
import com.reader.engine.format.BookSource
import com.reader.engine.format.FormatRegistry
import javax.inject.Inject
import javax.inject.Singleton

/** Opens reflowable book sources while keeping format registration inside the data boundary. */
@Singleton
class BookSourceRepository @Inject constructor(
    private val bookRepository: BookRepository,
    private val formatRegistry: FormatRegistry,
) {
    suspend fun open(book: Book): BookSource {
        val file = requireNotNull(bookRepository.originalFile(book.id, book.format)) {
            "Original document is missing"
        }
        return formatRegistry.open(file, file.name)
    }
}
