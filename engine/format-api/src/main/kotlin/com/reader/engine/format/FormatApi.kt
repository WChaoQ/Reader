package com.reader.engine.format

import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import java.io.Closeable
import java.io.File
import java.io.InputStream

/**
 * An opened document session.
 *
 * Implementations own every file descriptor, archive handle, renderer, and other resource
 * acquired while opening the document. The caller owns this instance and MUST call [close]
 * when the reading session ends.
 *
 * All indexes are zero-based. Resource streams returned by [openResource] are owned by the
 * caller and must be closed. Parsing and I/O are suspend functions; implementations must not
 * perform blocking work on the main thread.
 */
interface BookSource : Closeable {
    val format: BookFormat
    suspend fun metadata(): BookMetadata
    suspend fun toc(): List<TocEntry>
    /** Number of readable sections/pages; stable for this opened session. */
    val chapterCount: Int
    /** Loads one zero-based section/page. */
    suspend fun loadChapter(index: Int): Chapter
    /** Opens a document-relative resource. The returned stream must be closed by the caller. */
    suspend fun openResource(ref: ResourceRef): InputStream
}

/**
 * Stateless format adapter registered by the application.
 * [canHandle] must be fast, deterministic, and perform no I/O. [magic] is only a short prefix.
 * [open] transfers ownership of acquired resources to the returned [BookSource].
 */
interface FormatHandler {
    val format: BookFormat
    fun canHandle(fileName: String, magic: ByteArray): Boolean
    suspend fun open(file: File): BookSource
}
