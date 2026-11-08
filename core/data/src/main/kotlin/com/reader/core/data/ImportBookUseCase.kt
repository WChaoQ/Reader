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
import kotlinx.coroutines.CancellationException
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
        var committed: File? = null
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
                temp = null
                return@withContext Result.Success(existing)
            }

            val magic = tmp.inputStream().use { it.readNBytes(16) }
            val handler = formatRegistry.detect(name, magic)
                ?: run {
                    temp?.delete()
                    temp = null
                    return@withContext Result.Error(
                        IllegalArgumentException("Unsupported"),
                        "不支持的文件格式",
                    )
                }

            val ext = when (handler.format) {
                BookFormat.TXT -> "txt"
                BookFormat.MD -> "md"
                BookFormat.EPUB -> "epub"
                BookFormat.PDF -> "pdf"
            }
            val storedFile = fileStore.commitOriginal(tmp, bookId, ext)
            temp = null
            committed = storedFile

            val metadata = handler.open(storedFile).use { source ->
                source.metadata() to source.chapterCount
            }
            val (meta, chapterCount) = metadata

            val book = Book(
                id = bookId,
                title = meta.title.ifBlank { name.substringBeforeLast('.') },
                author = meta.author,
                format = handler.format,
                fileSize = storedFile.length(),
                coverPath = null,
                importedAt = System.currentTimeMillis(),
                lastReadAt = null,
            )
            bookRepository.upsert(book, chapterCount = chapterCount)
            committed = null
            Result.Success(book)
        } catch (cancelled: CancellationException) {
            temp?.delete()
            committed?.delete()
            throw cancelled
        } catch (t: Throwable) {
            temp?.delete()
            committed?.delete()
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
