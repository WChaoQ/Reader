package com.reader.core.data

import com.reader.core.common.ReaderDispatchers
import com.reader.core.database.BookDao
import com.reader.core.model.Book
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

    suspend fun updateLastRead(bookId: String, at: Long) = withContext(dispatchers.io) {
        val existing = bookDao.getById(bookId) ?: return@withContext
        bookDao.upsert(existing.copy(lastReadAt = at))
    }

    suspend fun delete(bookId: String) = withContext(dispatchers.io) {
        bookDao.delete(bookId)
        fileStore.deleteBook(bookId)
    }
}
