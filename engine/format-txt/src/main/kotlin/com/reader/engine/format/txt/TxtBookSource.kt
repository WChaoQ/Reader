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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** URI-backed TXT reader. Chapter indexing is streaming and stores only byte ranges. */
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
        TocEntry(range.title ?: "第\${index + 1}章", Locator(index, 0))
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
        require(length <= MAX_CHAPTER_BYTES + MAX_SPLIT_OVERHEAD) { "Chapter exceeds safe read limit" }
        return document.openInputStream().use { input ->
            skipFully(input, start)
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
        private const val MAX_SPLIT_OVERHEAD = 64 * 1024L
        private const val MAX_LINE_BYTES = 128 * 1024

        fun open(document: DocumentHandle): TxtBookSource {
            val sample = document.openInputStream().use { it.readNBytes(64 * 1024) }
            val charset = EncodingDetector.detect(sample)
            val ranges = indexChapters(document, charset)
            return TxtBookSource(document, charset, ranges)
        }

        private fun indexChapters(document: DocumentHandle, charset: Charset): List<ByteChapterRange> {
            val newline = "\n".toByteArray(charset)
            val input = document.openInputStream().buffered(64 * 1024)
            val line = ByteArrayOutputStream()
            val tail = ArrayDeque<Byte>()
            val matches = mutableListOf<Pair<String, Long>>()
            var position = 0L
            var lineStart = 0L
            var overlong = false

            fun finishLine(terminatorBytes: Int) {
                if (!overlong) {
                    val bytes = line.toByteArray()
                    val contentLength = (bytes.size - terminatorBytes).coerceAtLeast(0)
                    val decoded = bytes.copyOf(contentLength).toString(charset)
                        .removePrefix("\uFEFF").trimEnd('\r').trim()
                    if (decoded.isNotEmpty() && TxtChapterSplitter.CHAPTER_REGEX.matches(decoded)) {
                        matches += decoded.take(80) to lineStart
                    }
                }
                line.reset()
                tail.clear()
                overlong = false
                lineStart = position
            }

            try {
                while (true) {
                    val value = input.read()
                    if (value < 0) {
                        finishLine(0)
                        break
                    }
                    position++
                    if (!overlong) {
                        line.write(value)
                        if (line.size() > MAX_LINE_BYTES + newline.size) {
                            line.reset()
                            overlong = true
                        }
                    }
                    tail.addLast(value.toByte())
                    while (tail.size > newline.size) tail.removeFirst()
                    val matchesNewline = tail.size == newline.size &&
                        newline.indices.all { tail.elementAt(it) == newline[it] }
                    if (matchesNewline) finishLine(newline.size)
                }
            } finally {
                input.close()
            }

            val length = position
            if (matches.isEmpty()) return splitRange(document, charset, 0L, length, null)
            val ranges = mutableListOf<ByteChapterRange>()
            if (matches.first().second > 0L) {
                ranges += ByteChapterRange("前言", 0L, matches.first().second)
            }
            matches.forEachIndexed { index, match ->
                val end = matches.getOrNull(index + 1)?.second ?: length
                ranges += ByteChapterRange(match.first, match.second, end)
            }
            return ranges.flatMap { range ->
                if (range.byteEnd - range.byteStart <= MAX_CHAPTER_BYTES) listOf(range)
                else splitRange(document, charset, range.byteStart, range.byteEnd, range.title)
            }
        }

        private fun splitRange(
            document: DocumentHandle,
            charset: Charset,
            start: Long,
            end: Long,
            title: String?,
        ): List<ByteChapterRange> {
            if (end <= start) return listOf(ByteChapterRange(title ?: "正文", start, end))
            val newline = "\n".toByteArray(charset)
            val result = mutableListOf<ByteChapterRange>()
            var cursor = start
            var part = 1
            while (cursor < end) {
                val target = (cursor + MAX_CHAPTER_BYTES).coerceAtMost(end)
                val next = if (target < end) {
                    findNextLineBoundary(document, target, end, newline) ?: target
                } else target
                val label = when {
                    title == null -> "第\${part}部分"
                    part == 1 -> title
                    else -> "$title ($part)"
                }
                result += ByteChapterRange(label, cursor, next)
                cursor = next
                part++
            }
            return result
        }

        private fun findNextLineBoundary(
            document: DocumentHandle,
            from: Long,
            end: Long,
            newline: ByteArray,
        ): Long? = document.openInputStream().use { input ->
            skipFully(input, from)
            var position = from
            var matched = 0
            while (position < end && position - from <= MAX_SPLIT_OVERHEAD) {
                val value = input.read()
                if (value < 0) break
                position++
                if (value == (newline[matched].toInt() and 0xff)) {
                    matched++
                    if (matched == newline.size) return@use position
                } else {
                    matched = if (value == (newline[0].toInt() and 0xff)) 1 else 0
                }
            }
            null
        }

        private fun skipFully(input: InputStream, count: Long) {
            var remaining = count
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped > 0L) remaining -= skipped
                else {
                    if (input.read() < 0) throw java.io.EOFException("Unexpected end of document")
                    remaining--
                }
            }
        }
    }
}
