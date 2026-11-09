package com.reader.core.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.reader.core.common.ReaderDispatchers
import com.reader.core.common.Result
import com.reader.core.model.Book
import com.reader.engine.format.DocumentHandle
import com.reader.engine.format.FormatRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers a user-selected document in the library. The original is never copied or modified.
 * The persisted URI grant is the source of truth; Room stores only its reference and metadata.
 */
@Singleton
class ImportBookUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val formatRegistry: FormatRegistry,
    private val dispatchers: ReaderDispatchers,
) {
    suspend operator fun invoke(uri: Uri): Result<Book> = withContext(dispatchers.io) {
        try {
            val resolver = context.contentResolver
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (security: SecurityException) {
                // ACTION_OPEN_DOCUMENT should return a persistable read grant.
                throw SecurityException("文件提供程序未授予持久读取权限，请从系统文件选择器重新选择", security)
            }

            val existing = bookRepository.findBySourceUri(uri.toString())
            if (existing != null) {
                // Re-check access rather than silently returning a stale shelf item.
                resolver.openInputStream(uri)?.use { it.read() }
                    ?: throw IOException("Document is no longer available")
                return@withContext Result.Success(existing)
            }

            var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "未命名文档"
            var size: Long? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
            displayName = displayName.substringAfterLast('/').filterNot { it.isISOControl() }
                .take(240).ifBlank { "未命名文档" }
            val mimeType = resolver.getType(uri)
            val document = DocumentHandle.fromUri(resolver, uri, displayName, mimeType, size)

            val source = formatRegistry.open(document)
            val metadata: com.reader.core.model.BookMetadata
            val chapterCount: Int
            try {
                metadata = source.metadata()
                chapterCount = source.chapterCount
                require(chapterCount > 0) { "文档没有可读取的章节或页面" }
            } finally {
                source.close()
            }

            val id = MessageDigest.getInstance("SHA-256")
                .digest(uri.toString().toByteArray())
                .take(16)
                .joinToString("") { "%02x".format(it) }
            val book = Book(
                id = id,
                title = metadata.title.trim().ifBlank { displayName.substringBeforeLast('.') },
                author = metadata.author?.trim()?.takeIf { it.isNotEmpty() },
                format = source.format,
                fileSize = size ?: 0L,
                coverPath = null,
                importedAt = System.currentTimeMillis(),
                lastReadAt = null,
                sourceUri = uri.toString(),
            )
            bookRepository.upsert(book, chapterCount)
            Result.Success(book)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (security: SecurityException) {
            Result.Error(security, security.message ?: "文件读取权限已失效，请重新选择文件")
        } catch (failure: Exception) {
            Result.Error(failure, failure.message ?: "无法打开此文档")
        }
    }
}
