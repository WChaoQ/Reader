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
 * Streams and descriptors are newly opened for each call and owned by the caller.
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
 * An opened document session. The caller owns it and MUST close it when the session ends.
 * Resource streams returned by openResource are owned by the caller.
 */
interface BookSource : Closeable {
    val format: BookFormat
    suspend fun metadata(): BookMetadata
    suspend fun toc(): List<TocEntry>
    val chapterCount: Int
    suspend fun loadChapter(index: Int): Chapter
    suspend fun openResource(ref: ResourceRef): InputStream
}

/** Stateless format adapter. open transfers acquired resource ownership to BookSource. */
interface FormatHandler {
    val format: BookFormat
    fun canHandle(fileName: String, mimeType: String?, magic: ByteArray): Boolean
    suspend fun open(document: DocumentHandle): BookSource
}
