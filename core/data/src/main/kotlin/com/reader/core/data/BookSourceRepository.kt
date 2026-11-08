package com.reader.core.data

import com.reader.core.common.ReaderDispatchers
import com.reader.core.model.Book
import com.reader.engine.format.BookSource
import com.reader.engine.format.FormatRegistry
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Opens a document off the main thread and keeps format registration inside the data boundary. */
@Singleton
class BookSourceRepository @Inject constructor(
    private val bookRepository: BookRepository,
    private val formatRegistry: FormatRegistry,
    private val dispatchers: ReaderDispatchers,
) {
    suspend fun open(book: Book): BookSource = withContext(dispatchers.io) {
        val file = requireNotNull(bookRepository.originalFile(book.id, book.format)) {
            "Original document is missing"
        }
        val source = formatRegistry.open(file, file.name)
        if (source.format != book.format) {
            source.close()
            error("Stored format ${book.format} does not match parsed format ${source.format}")
        }
        source
    }
}
