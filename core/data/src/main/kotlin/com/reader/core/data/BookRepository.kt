package com.reader.core.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.reader.core.common.ReaderDispatchers
import com.reader.core.database.BookDao
import com.reader.core.model.Book
import com.reader.core.model.BookFormat
import com.reader.core.storage.BookFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val fileStore: BookFileStore,
    private val dispatchers: ReaderDispatchers,
) {
    fun observeBooks(): Flow<List<Book>> =
        bookDao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun getBook(id: String): Book? = withContext(dispatchers.io) {
        bookDao.getById(id)?.toModel()
    }

    suspend fun findBySourceUri(uri: String): Book? = withContext(dispatchers.io) {
        bookDao.getById(uriBookId(uri))?.toModel()
    }

    suspend fun upsert(book: Book, chapterCount: Int = 0) = withContext(dispatchers.io) {
        bookDao.upsert(book.toEntity(chapterCount = chapterCount))
    }

    suspend fun updateLastRead(bookId: String, at: Long) = withContext(dispatchers.io) {
        val existing = bookDao.getById(bookId) ?: return@withContext
        bookDao.update(existing.copy(lastReadAt = at))
    }

    /** Legacy compatibility for books added by the earlier copy-based importer. */
    fun originalFile(bookId: String, format: BookFormat): File? {
        val extension = when (format) {
            BookFormat.TXT -> "txt"
            BookFormat.MD -> "md"
            BookFormat.EPUB -> "epub"
            BookFormat.PDF -> "pdf"
        }
        return fileStore.originalFile(bookId, extension).takeIf { it.isFile }
    }

    suspend fun delete(bookId: String) = withContext(dispatchers.io) {
        val existing = bookDao.getById(bookId)
        existing?.sourceUri?.let { value ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(value),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        bookDao.delete(bookId)
        fileStore.deleteBook(bookId)
    }

    private fun uriBookId(uri: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(uri.toByteArray())
            .take(16)
            .joinToString("") { "%02x".format(it) }
}
