package com.reader.engine.format.txt

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
import java.nio.charset.Charset

class TxtBookSource(
    private val file: File,
    private val charset: Charset,
    private val fullText: String,
    private val ranges: List<TxtChapterSplitter.ChapterRange>,
) : BookSource {

    override val format: BookFormat = BookFormat.TXT
    override val chapterCount: Int = ranges.size

    override suspend fun metadata(): BookMetadata {
        val title = file.nameWithoutExtension.ifBlank { "Untitled" }
        return BookMetadata(title = title)
    }

    override suspend fun toc(): List<TocEntry> =
        ranges.mapIndexed { index, range ->
            TocEntry(
                title = range.title ?: "第${index + 1}章",
                locator = Locator(chapter = index, offset = 0),
            )
        }

    override suspend fun loadChapter(index: Int): Chapter {
        require(index in ranges.indices) { "Chapter $index out of range" }
        val range = ranges[index]
        val body = fullText.substring(range.start, range.end)
        val blocks = body
            .split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { para ->
                val text = para.replace(Regex("(?<!\n)\n(?!\n)"), "")
                Block.Paragraph(text = text)
            }
        return Chapter(
            index = index,
            title = range.title,
            blocks = blocks.ifEmpty { listOf(Block.Paragraph(body.trim())) },
        )
    }

    override suspend fun openResource(ref: ResourceRef): InputStream {
        error("TXT has no external resources")
    }

    override fun close() {}

    companion object {
        fun open(file: File): TxtBookSource {
            val bytes = file.readBytes()
            val charset = EncodingDetector.detect(bytes)
            val text = bytes.toString(charset)
            val ranges = TxtChapterSplitter.split(text).ifEmpty {
                listOf(TxtChapterSplitter.ChapterRange("正文", 0, text.length))
            }
            return TxtBookSource(file, charset, text, ranges)
        }
    }
}
