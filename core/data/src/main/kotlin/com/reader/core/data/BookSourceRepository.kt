package com.reader.core.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.reader.core.common.ReaderDispatchers
import com.reader.core.model.Book
import com.reader.engine.format.BookSource
import com.reader.engine.format.DocumentHandle
import com.reader.engine.format.FormatRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookSourceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val formatRegistry: FormatRegistry,
    private val dispatchers: ReaderDispatchers,
) {
    suspend fun open(book: Book): BookSource = withContext(dispatchers.io) {
        val document = documentHandle(book)
        val source = formatRegistry.open(document)
        if (source.format != book.format) {
            source.close()
            error("Stored format ${book.format} does not match parsed format ${source.format}")
        }
        source
    }

    suspend fun openPdfDescriptor(book: Book): ParcelFileDescriptor = withContext(dispatchers.io) {
        require(book.format.name == "PDF") { "Document is not a PDF" }
        documentHandle(book).openFileDescriptor()
    }

    private suspend fun documentHandle(book: Book): DocumentHandle {
        val sourceUri = book.sourceUri
        if (sourceUri.isNullOrBlank()) {
            val legacy = requireNotNull(bookRepository.originalFile(book.id, book.format)) {
                "原文件不存在，请重新选择文档"
            }
            return DocumentHandle.fromFile(legacy)
        }

        val uri = Uri.parse(sourceUri)
        val resolver = context.contentResolver
        var displayName = book.title
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
        return DocumentHandle.fromUri(
            resolver = resolver,
            uri = uri,
            displayName = displayName,
            mimeType = resolver.getType(uri),
            size = size,
        )
    }
}
