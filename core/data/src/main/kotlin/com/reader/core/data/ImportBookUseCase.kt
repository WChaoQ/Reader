package com.reader.core.data

import android.content.Context
import android.net.Uri
import com.reader.core.common.ReaderDispatchers
import com.reader.core.common.Result
import com.reader.core.model.Book
import com.reader.core.model.BookFormat
import com.reader.core.storage.BookFileStore
import com.reader.engine.format.FormatRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportBookUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileStore: BookFileStore,
    private val bookRepository: BookRepository,
    private val formatRegistry: FormatRegistry,
    private val dispatchers: ReaderDispatchers,
) {
    suspend operator fun invoke(uri: Uri): Result<Book> = withContext(dispatchers.io) {
        var temp: File? = null
        try {
            val name = queryDisplayName(uri) ?: "book.bin"
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.Error(
                    IllegalStateException("Cannot open uri"),
                    "无法打开文件",
                )
            val (tmp, bookId) = input.use { fileStore.copyAndHash(it, name) }
            temp = tmp

            val existing = bookRepository.getBook(bookId)
            if (existing != null) {
                temp.delete()
                return@withContext Result.Success(existing)
            }

            val magic = tmp.inputStream().use { it.readNBytes(16) }
            val handler = formatRegistry.detect(name, magic)
                ?: return@withContext Result.Error(
                    IllegalArgumentException("Unsupported"),
                    "不支持的文件格式",
                )

            val ext = when (handler.format) {
                BookFormat.TXT -> "txt"
                BookFormat.MD -> "md"
                BookFormat.EPUB -> "epub"
                BookFormat.PDF -> "pdf"
            }
            val committed = fileStore.commitOriginal(tmp, bookId, ext)
            temp = null

            val source = handler.open(committed)
            val meta = source.metadata()
            val chapterCount = source.chapterCount
            source.close()

            val book = Book(
                id = bookId,
                title = meta.title.ifBlank { name.substringBeforeLast('.') },
                author = meta.author,
                format = handler.format,
                fileSize = committed.length(),
                coverPath = null,
                importedAt = System.currentTimeMillis(),
                lastReadAt = null,
            )
            bookRepository.upsert(book, chapterCount = chapterCount)
            Result.Success(book)
        } catch (t: Throwable) {
            temp?.delete()
            Result.Error(t, t.message ?: "导入失败")
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx < 0) return null
            return if (it.moveToFirst()) it.getString(idx) else null
        }
    }
}
