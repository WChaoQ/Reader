package com.reader.engine.format

import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import java.io.Closeable
import java.io.InputStream

interface BookSource : Closeable {
    val format: BookFormat
    suspend fun metadata(): BookMetadata
    suspend fun toc(): List<TocEntry>
    val chapterCount: Int
    suspend fun loadChapter(index: Int): Chapter
    suspend fun openResource(ref: ResourceRef): InputStream
}

interface FormatHandler {
    val format: BookFormat
    fun canHandle(fileName: String, magic: ByteArray): Boolean
    suspend fun open(file: java.io.File): BookSource
}
