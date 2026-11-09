package com.reader.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import java.io.File
import java.io.FileOutputStream
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

            val source = handler.open(storedFile)
            try {
                val meta = source.metadata()
                val chapterCount = source.chapterCount
                val coverPath = try {
                    extractCover(source, handler.format, storedFile, bookId, meta.coverHref)
                } catch (_: Throwable) {
                    null
                }

                val book = Book(
                    id = bookId,
                    title = meta.title.ifBlank { name.substringBeforeLast('.') },
                    author = meta.author,
                    format = handler.format,
                    fileSize = storedFile.length(),
                    coverPath = coverPath,
                    importedAt = System.currentTimeMillis(),
                    lastReadAt = null,
                )
                bookRepository.upsert(book, chapterCount = chapterCount)
                committed = null
                Result.Success(book)
            } finally {
                source.close()
            }
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

    private suspend fun extractCover(
        source: BookSource,
        format: BookFormat,
        file: File,
        bookId: String,
        coverHref: String?,
    ): String? {
        val dest = fileStore.coverFile(bookId)
        return when (format) {
            BookFormat.EPUB -> {
                val href = coverHref ?: return null
                source.openResource(ResourceRef(href)).use { input ->
                    val peeked = input.readBytes()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(peeked, 0, peeked.size, bounds)
                    val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, 512)
                    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bitmap = BitmapFactory.decodeByteArray(peeked, 0, peeked.size, decodeOpts)
                        ?: return null
                    writeCover(bitmap, dest)
                }
                dest.absolutePath
            }
            BookFormat.PDF -> {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (renderer.pageCount <= 0) return null
                        renderer.openPage(0).use { page ->
                            val scale = 512f / page.width.coerceAtLeast(1)
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            writeCover(bitmap, dest)
                        }
                    }
                }
                dest.absolutePath
            }
            BookFormat.TXT, BookFormat.MD -> null
        }
    }

    private fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        var sample = 1
        val maxDim = maxOf(width, height).coerceAtLeast(1)
        while (maxDim / sample > target * 2) sample *= 2
        return sample.coerceAtLeast(1)
    }

    private fun writeCover(bitmap: Bitmap, dest: File) {
        dest.parentFile?.mkdirs()
        FileOutputStream(dest).use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP, 85, out)
        }
        bitmap.recycle()
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
