package com.reader.core.data

import com.reader.core.common.ReaderDispatchers
import com.reader.core.database.BookDao
import com.reader.core.model.Book
import com.reader.core.model.BookFormat
import java.io.File
import com.reader.core.storage.BookFileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val fileStore: BookFileStore,
    private val dispatchers: ReaderDispatchers,
) {
    fun observeBooks(): Flow<List<Book>> =
        bookDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun getBook(id: String): Book? = withContext(dispatchers.io) {
        bookDao.getById(id)?.toModel()
    }

    suspend fun upsert(book: Book, chapterCount: Int = 0) = withContext(dispatchers.io) {
        bookDao.upsert(book.toEntity(chapterCount = chapterCount))
    }

    /**
     * Restores a missing original file without replacing the existing Room row.
     * Updating in place preserves progress, bookmarks, and highlights linked by foreign keys.
     */
    suspend fun restoreMissingSource(book: Book, chapterCount: Int) = withContext(dispatchers.io) {
        val existing = bookDao.getById(book.id)
        if (existing == null) {
            bookDao.upsert(book.toEntity(chapterCount = chapterCount))
        } else {
            bookDao.update(
                existing.copy(
                    title = book.title,
                    author = book.author,
                    format = book.format.name,
                    fileSize = book.fileSize,
                    coverPath = book.coverPath,
                    chapterCount = chapterCount,
                    state = "ready",
                ),
            )
        }
    }

    suspend fun updateLastRead(bookId: String, at: Long) = withContext(dispatchers.io) {
        val existing = bookDao.getById(bookId) ?: return@withContext
        bookDao.upsert(existing.copy(lastReadAt = at))
    }

    fun originalFile(bookId: String, format: BookFormat): File? {
        val extension = when (format) {
            BookFormat.TXT -> "txt"
            BookFormat.MD -> "md"
            BookFormat.EPUB -> "epub"
            BookFormat.PDF -> "pdf"
        }
        return fileStore.originalFile(bookId, extension).takeIf { it.exists() }
    }

    suspend fun delete(bookId: String) = withContext(dispatchers.io) {
        bookDao.delete(bookId)
        fileStore.deleteBook(bookId)
    }
}
