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
import java.io.File
import java.io.InputStream

/** Thin BookSource for import/metadata. Reading uses FixedEngine. */
class PdfBookSource(
    private val file: File,
) : BookSource {

    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)

    override val format: BookFormat = BookFormat.PDF
    override val chapterCount: Int = renderer.pageCount

    override suspend fun metadata(): BookMetadata =
        BookMetadata(title = file.nameWithoutExtension)

    override suspend fun toc(): List<TocEntry> =
        (0 until chapterCount).map { page ->
            TocEntry(
                title = "第 ${page + 1} 页",
                locator = Locator(chapter = page, offset = 0),
            )
        }

    override suspend fun loadChapter(index: Int): Chapter =
        Chapter(index = index, title = "第 ${index + 1} 页", blocks = listOf(Block.Paragraph("")))

    override suspend fun openResource(ref: ResourceRef): InputStream {
        error("PDF has no external resources")
    }

    override fun close() {
        renderer.close()
        pfd.close()
    }

    companion object {
        fun open(file: File): PdfBookSource = PdfBookSource(file)
    }
}
