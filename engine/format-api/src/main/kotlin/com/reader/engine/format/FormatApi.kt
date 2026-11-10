package com.reader.engine.format

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * A read-only handle to a user-selected document. It never materializes a private copy.
 * Every stream or descriptor returned is newly opened and owned by the caller.
 */
class DocumentHandle private constructor(
    private val resolver: ContentResolver?,
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val size: Long?,
) {
    fun openInputStream(): InputStream =
        if (uri.scheme == "file") FileInputStream(requireNotNull(uri.path))
        else requireNotNull(resolver).openInputStream(uri)
            ?: throw java.io.FileNotFoundException("Document provider returned no stream: $uri")

    fun openFileDescriptor(): ParcelFileDescriptor =
        if (uri.scheme == "file") ParcelFileDescriptor.open(
            File(requireNotNull(uri.path)), ParcelFileDescriptor.MODE_READ_ONLY,
        )
        else requireNotNull(resolver).openFileDescriptor(uri, "r")
            ?: throw java.io.FileNotFoundException("Document provider returned no descriptor: $uri")

    /** PdfRenderer and ZIP central-directory access require random access, not a pipe. */
    fun openSeekableFileDescriptor(): ParcelFileDescriptor {
        val descriptor = openFileDescriptor()
        if (descriptor.statSize < 0L) {
            descriptor.close()
            throw UnsupportedOperationException(
                "该文件提供程序不支持随机访问，请将文档下载到本地后再打开",
            )
        }
        return descriptor
    }

    companion object {
        fun fromUri(
            resolver: ContentResolver,
            uri: Uri,
            displayName: String,
            mimeType: String?,
            size: Long?,
        ) = DocumentHandle(resolver, uri, displayName, mimeType, size)

        fun fromFile(file: File) = DocumentHandle(
            resolver = null,
            uri = Uri.fromFile(file),
            displayName = file.name,
            mimeType = null,
            size = file.length(),
        )
    }
}

/**
 * One independently opened document source. The owner MUST close it exactly once.
 * Implementations must make close idempotent and protect non-thread-safe backend
 * objects internally because source operations may be called concurrently.
 * Resource streams returned by openResource are owned by the caller.
 */
interface BookSource : Closeable {
    val format: BookFormat
    suspend fun metadata(): BookMetadata
    suspend fun toc(): List<TocEntry>
    /** Non-negative count; valid chapter indexes are zero-based. */
    val chapterCount: Int
    /** Returns the requested zero-based chapter; invalid indexes must fail explicitly. */
    suspend fun loadChapter(index: Int): Chapter
    suspend fun openResource(ref: ResourceRef): InputStream
}

/**
 * Stateless format adapter. On success, open transfers acquired resource ownership
 * to the returned BookSource. On failure or cancellation, it must release partial
 * resources before propagating the original failure.
 */
interface FormatHandler {
    val format: BookFormat
    /** Fast, deterministic, side-effect-free check over bounded metadata and magic bytes. */
    fun canHandle(fileName: String, mimeType: String?, magic: ByteArray): Boolean
    suspend fun open(document: DocumentHandle): BookSource
}
