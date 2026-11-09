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
import java.io.ByteArrayOutputStream
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
            val bytes = document.openInputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_MARKDOWN_BYTES) {
                        "Markdown document is too large to parse safely"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val text = bytes.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")
            val chapters = MarkdownParser.parseDocument(text)
            return MarkdownBookSource(document, chapters)
        }

        private const val MAX_MARKDOWN_BYTES = 32L * 1024 * 1024
    }
}
