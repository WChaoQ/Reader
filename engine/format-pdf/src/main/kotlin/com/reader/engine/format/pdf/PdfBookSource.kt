package com.reader.engine.format.pdf

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.reader.core.model.Block
import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.Locator
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import com.reader.engine.format.BookSource
import com.reader.engine.format.DocumentHandle
import java.io.InputStream

/** PDF metadata session backed by the original provider descriptor. */
class PdfBookSource(private val document: DocumentHandle) : BookSource {
    private val descriptor: ParcelFileDescriptor = document.openFileDescriptor()
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (failure: Throwable) {
        descriptor.close()
        throw failure
    }

    override val format = BookFormat.PDF
    override val chapterCount get() = renderer.pageCount

    override suspend fun metadata() = BookMetadata(
        title = document.displayName.substringBeforeLast('.').ifBlank { "PDF" },
    )

    override suspend fun toc(): List<TocEntry> = (0 until chapterCount).map { page ->
        TocEntry("第 ${page + 1} 页", Locator(page, 0))
    }

    override suspend fun loadChapter(index: Int) =
        Chapter(index, "第 ${index + 1} 页", listOf(Block.Paragraph("")))

    override suspend fun openResource(ref: ResourceRef): InputStream =
        error("PDF has no external resources")

    override fun close() = renderer.close()

    companion object {
        fun open(document: DocumentHandle) = PdfBookSource(document)
    }
}
