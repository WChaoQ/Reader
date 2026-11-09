package com.reader.engine.format.markdown

import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.Locator
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import com.reader.engine.format.BookSource
import com.reader.engine.format.DocumentHandle
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Markdown is parsed from the provider stream; no original file is copied to app storage. */
class MarkdownBookSource(
    private val document: DocumentHandle,
    private val chapters: List<Pair<String?, List<com.reader.core.model.Block>>>,
) : BookSource {
    override val format = BookFormat.MD
    override val chapterCount get() = chapters.size

    override suspend fun metadata(): BookMetadata = BookMetadata(
        title = chapters.firstOrNull()?.first
            ?: document.displayName.substringBeforeLast('.').ifBlank { "Markdown" },
    )

    override suspend fun toc(): List<TocEntry> = chapters.mapIndexed { index, (title, _) ->
        TocEntry(title ?: "第${index + 1}章", Locator(index, 0))
    }

    override suspend fun loadChapter(index: Int): Chapter {
        require(index in chapters.indices)
        val (title, blocks) = chapters[index]
        return Chapter(index = index, title = title, blocks = blocks)
    }

    override suspend fun openResource(ref: ResourceRef): InputStream =
        throw UnsupportedOperationException("External Markdown resources are not available for standalone document URIs")

    override fun close() = Unit

    companion object {
        fun open(document: DocumentHandle): MarkdownBookSource {
            val size = document.size
            require(size == null || size <= MAX_MARKDOWN_BYTES) {
                "Markdown document is too large to parse safely"
            }
            val text = document.openInputStream().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                .removePrefix("\uFEFF")
            require(text.length <= MAX_MARKDOWN_CHARS) { "Markdown document is too large to parse safely" }
            val chapters = MarkdownParser.parseDocument(text)
            return MarkdownBookSource(document, chapters)
        }

        private const val MAX_MARKDOWN_BYTES = 32L * 1024 * 1024
        private const val MAX_MARKDOWN_CHARS = 32 * 1024 * 1024
    }
}
