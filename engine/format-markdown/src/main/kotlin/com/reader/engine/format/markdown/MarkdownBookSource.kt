package com.reader.engine.format.markdown

import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.Locator
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import com.reader.engine.format.BookSource
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

class MarkdownBookSource(
    private val file: File,
    private val chapters: List<Pair<String?, List<com.reader.core.model.Block>>>,
) : BookSource {

    override val format: BookFormat = BookFormat.MD
    override val chapterCount: Int = chapters.size

    override suspend fun metadata(): BookMetadata {
        val title = chapters.firstOrNull()?.first
            ?: file.nameWithoutExtension.ifBlank { "Markdown" }
        return BookMetadata(title = title)
    }

    override suspend fun toc(): List<TocEntry> =
        chapters.mapIndexed { index, (title, _) ->
            TocEntry(
                title = title ?: "第${index + 1}章",
                locator = Locator(chapter = index, offset = 0),
            )
        }

    override suspend fun loadChapter(index: Int): Chapter {
        require(index in chapters.indices)
        val (title, blocks) = chapters[index]
        return Chapter(index = index, title = title, blocks = blocks)
    }

    override suspend fun openResource(ref: ResourceRef): InputStream {
        error("Local image resources not yet supported")
    }

    override fun close() {}

    companion object {
        fun open(file: File): MarkdownBookSource {
            val text = file.readText(StandardCharsets.UTF_8).removePrefix("\uFEFF")
            val chapters = MarkdownParser.parseDocument(text)
            return MarkdownBookSource(file, chapters)
        }
    }
}
