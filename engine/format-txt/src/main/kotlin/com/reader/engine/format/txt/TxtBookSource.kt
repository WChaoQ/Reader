package com.reader.engine.format.txt

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
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** URI-backed TXT reader. It indexes byte ranges and reopens the provider stream per chapter. */
class TxtBookSource(
    private val document: DocumentHandle,
    private val charset: Charset,
    private val ranges: List<ByteChapterRange>,
) : BookSource {
    override val format = BookFormat.TXT
    override val chapterCount get() = ranges.size

    override suspend fun metadata() = BookMetadata(
        title = document.displayName.substringBeforeLast('.').ifBlank { "Untitled" },
    )

    override suspend fun toc(): List<TocEntry> = ranges.mapIndexed { index, range ->
        TocEntry(range.title ?: "第${index + 1}章", Locator(index, 0))
    }

    override suspend fun loadChapter(index: Int): Chapter {
        require(index in ranges.indices)
        val range = ranges[index]
        val body = readSlice(range.byteStart, range.byteEnd)
        val blocks = body.split(Regex("\\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Block.Paragraph(it.replace(Regex("(?<!\\n)\\n(?!\\n)"), "")) }
        return Chapter(index, range.title, blocks.ifEmpty { listOf(Block.Paragraph(body.trim())) })
    }

    override suspend fun openResource(ref: ResourceRef): InputStream =
        error("TXT has no external resources")

    override fun close() = Unit

    private fun readSlice(start: Long, end: Long): String {
        val length = (end - start).coerceAtLeast(0L)
        if (length == 0L) return ""
        require(length <= MAX_CHAPTER_BYTES * 2) { "Chapter exceeds safe read limit" }
        return document.openInputStream().use { input ->
            var remaining = start
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) {
                    if (input.read() < 0) return@use ""
                    remaining--
                } else remaining -= skipped
            }
            val bytes = input.readNBytes(length.toInt())
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        }
    }

    data class ByteChapterRange(val title: String?, val byteStart: Long, val byteEnd: Long)

    companion object {
        private const val MAX_CHAPTER_BYTES = 240_000L

        fun open(document: DocumentHandle): TxtBookSource {
            val sample = document.openInputStream().use { it.readNBytes(64 * 1024) }
            val charset = EncodingDetector.detect(sample)
            val ranges = indexChapters(document, charset)
            return TxtBookSource(document, charset, ranges)
        }

        private fun indexChapters(document: DocumentHandle, charset: Charset): List<ByteChapterRange> {
            val counter = CountingInputStream(document.openInputStream().buffered(64 * 1024))
            val reader = counter.reader(charset)
            val matches = mutableListOf<Pair<String, Long>>()
            val line = StringBuilder()
            var lineStart = 0L
            var pendingStart = 0L
            while (true) {
                val value = reader.read()
                if (value < 0 || value.toChar() == '\n') {
                    val text = line.toString().trimEnd('\r').trim()
                    if (text.isNotEmpty() && TxtChapterSplitter.CHAPTER_REGEX.matches(text)) {
                        matches += text.take(80) to lineStart
                    }
                    line.clear()
                    pendingStart = counter.bytesRead
                    lineStart = pendingStart
                    if (value < 0) break
                } else {
                    if (line.isEmpty()) lineStart = pendingStart
                    line.append(value.toChar())
                }
            }
            reader.close()

            val length = document.size ?: document.openInputStream().use { stream ->
                var total = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = stream.read(buffer); if (n < 0) break; total += n }
                total
            }
            if (matches.isEmpty()) return splitRange(0L, length, null)
            val ranges = mutableListOf<ByteChapterRange>()
            if (matches.first().second > 0) ranges += ByteChapterRange("前言", 0, matches.first().second)
            matches.forEachIndexed { index, match ->
                val end = matches.getOrNull(index + 1)?.second ?: length
                ranges += ByteChapterRange(match.first, match.second, end)
            }
            return ranges.flatMap { range ->
                if (range.byteEnd - range.byteStart <= MAX_CHAPTER_BYTES) listOf(range)
                else splitRange(range.byteStart, range.byteEnd, range.title)
            }
        }

        private fun splitRange(start: Long, end: Long, title: String?): List<ByteChapterRange> {
            if (end <= start) return listOf(ByteChapterRange(title ?: "正文", start, end))
            val result = mutableListOf<ByteChapterRange>()
            var cursor = start
            var part = 1
            while (cursor < end) {
                val next = (cursor + MAX_CHAPTER_BYTES).coerceAtMost(end)
                val label = when {
                    title == null -> "第${part}部分"
                    part == 1 -> title
                    else -> "$title ($part)"
                }
                result += ByteChapterRange(label, cursor, next)
                cursor = next
                part++
            }
            return result
        }
    }
}

private class CountingInputStream(private val inner: InputStream) : InputStream() {
    var bytesRead = 0L
        private set
    override fun read(): Int {
        val value = inner.read()
        if (value >= 0) bytesRead++
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = inner.read(buffer, offset, length)
        if (count > 0) bytesRead += count
        return count
    }
    override fun close() = inner.close()
    fun reader(charset: Charset): java.io.Reader = java.io.InputStreamReader(this, charset)
}
