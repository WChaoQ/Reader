package com.reader.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.reader.core.common.ReaderDispatchers
import com.reader.core.common.Result
import com.reader.core.model.Book
import com.reader.core.model.BookFormat
import com.reader.core.model.ResourceRef
import com.reader.core.storage.BookFileStore
import com.reader.engine.format.BookSource
import com.reader.engine.format.FormatRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
        var cover: File? = null

        try {
            val displayName = queryDisplayName(uri)
                ?.substringAfterLast('/')
                ?.filterNot { it.isISOControl() }
                ?.take(240)
                ?.takeIf { it.isNotBlank() }
                ?: "未命名文档"
            val fileName = normalizeFileName(displayName, context.contentResolver.getType(uri))

            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.Error(
                    IOException("The selected document cannot be opened"),
                    "无法读取所选文件，请检查文件是否仍然存在或可访问",
                )

            val (temporaryFile, bookId) = input.use {
                fileStore.copyAndHash(it)
            }
            temp = temporaryFile

            val existing = bookRepository.getBook(bookId)
            if (existing != null) {
                val original = bookRepository.originalFile(existing.id, existing.format)
                if (original?.isFile == true && original.length() > 0L) {
                    temp.delete()
                    temp = null
                    return@withContext Result.Success(existing)
                }
                // Remove an orphaned database entry before restoring its missing source file.
                bookRepository.delete(bookId)
            }

            val magic = temporaryFile.inputStream().use { it.readNBytes(MAGIC_BYTES) }
            val handler = formatRegistry.detect(fileName, magic)
                ?: run {
                    temp?.delete()
                    temp = null
                    return@withContext Result.Error(
                        IllegalArgumentException("Unsupported document format"),
                        "不支持此文件。请选择 TXT、Markdown、EPUB 或 PDF",
                    )
                }

            val extension = when (handler.format) {
                BookFormat.TXT -> "txt"
                BookFormat.MD -> "md"
                BookFormat.EPUB -> "epub"
                BookFormat.PDF -> "pdf"
            }

            val storedFile = fileStore.commitOriginal(temporaryFile, bookId, extension)
            temp = null
            committed = storedFile

            val source = handler.open(storedFile)
            try {
                val metadata = source.metadata()
                val chapterCount = source.chapterCount
                require(chapterCount > 0) { "The document contains no readable pages or chapters" }

                val coverPath = try {
                    extractCover(
                        source = source,
                        format = handler.format,
                        file = storedFile,
                        bookId = bookId,
                        coverHref = metadata.coverHref,
                    )?.also { cover = File(it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }

                val fallbackTitle = fileName.substringBeforeLast('.', fileName)
                    .ifBlank { "未命名文档" }
                val book = Book(
                    id = bookId,
                    title = metadata.title.trim().ifBlank { fallbackTitle },
                    author = metadata.author?.trim()?.takeIf { it.isNotEmpty() },
                    format = handler.format,
                    fileSize = storedFile.length(),
                    coverPath = coverPath,
                    importedAt = System.currentTimeMillis(),
                    lastReadAt = null,
                )

                bookRepository.upsert(book, chapterCount = chapterCount)
                committed = null
                cover = null
                Result.Success(book)
            } finally {
                source.close()
            }
        } catch (cancelled: CancellationException) {
            temp?.delete()
            committed?.delete()
            cover?.delete()
            throw cancelled
        } catch (security: SecurityException) {
            temp?.delete()
            committed?.delete()
            cover?.delete()
            Result.Error(security, "没有读取此文件的权限，请重新选择文件")
        } catch (failure: Exception) {
            temp?.delete()
            committed?.delete()
            cover?.delete()
            Result.Error(failure, failure.message ?: "导入失败，请重试")
        }
    }

    private suspend fun extractCover(
        source: BookSource,
        format: BookFormat,
        file: File,
        bookId: String,
        coverHref: String?,
    ): String? {
        val destination = fileStore.coverFile(bookId)
        when (format) {
            BookFormat.EPUB -> {
                val href = coverHref ?: return null
                val bytes = source.openResource(ResourceRef(href)).use { it.readBounded(MAX_COVER_BYTES) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, COVER_MAX_DIMENSION)
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
                writeCover(bitmap, destination)
            }
            BookFormat.PDF -> {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        if (renderer.pageCount <= 0) return null
                        renderer.openPage(0).use { page ->
                            val scale = COVER_MAX_DIMENSION.toFloat() /
                                maxOf(page.width, page.height).coerceAtLeast(1)
                            val width = (page.width * scale).toInt().coerceAtLeast(1)
                            val height = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                writeCover(bitmap, destination)
                            } catch (failure: Throwable) {
                                bitmap.recycle()
                                throw failure
                            }
                        }
                    }
                }
            }
            BookFormat.TXT, BookFormat.MD -> return null
        }
        return destination.absolutePath.takeIf { destination.isFile && destination.length() > 0L }
    }

    private fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        var sample = 1
        val maxDimension = maxOf(width, height).coerceAtLeast(1)
        while (maxDimension / sample > target * 2) sample *= 2
        return sample
    }

    private fun writeCover(bitmap: Bitmap, destination: File) {
        try {
            destination.parentFile?.mkdirs()
            FileOutputStream(destination).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.WEBP, 85, output)) {
                    "Unable to encode book cover"
                }
                output.fd.sync()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    /**
     * Some document providers expose a generic name such as "document" or "download" while
     * still returning a reliable MIME type. Add a supported suffix so extension-based handlers
     * (EPUB and Markdown) can identify those documents consistently.
     */
    private fun normalizeFileName(name: String, mimeType: String?): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in setOf("txt", "md", "markdown", "epub", "pdf")) return name

        val mimeExtension = when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
            "application/epub+zip" -> "epub"
            "application/pdf" -> "pdf"
            "text/markdown", "text/x-markdown" -> "md"
            "text/plain" -> "txt"
            else -> null
        } ?: return name

        return "$name.$mimeExtension"
    }

    private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maxBytes) { "Cover image is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAGIC_BYTES = 16
        const val MAX_COVER_BYTES = 16 * 1024 * 1024
        const val COVER_MAX_DIMENSION = 512
    }
}
