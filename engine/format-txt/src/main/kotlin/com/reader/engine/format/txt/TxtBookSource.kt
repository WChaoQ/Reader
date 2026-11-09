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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Memory-conscious TXT source (architecture §6.2).
 *
 * Chapter boundaries are discovered once with a streaming decode. Ranges are stored as
 * **byte offsets** so [loadChapter] only decodes the requested slice. The full document is
 * never retained as a single String.
 */
class TxtBookSource(
    private val file: File,
    private val charset: Charset,
    private val ranges: List<ByteChapterRange>,
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
        val body = readSlice(range.byteStart, range.byteEnd)
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

    private fun readSlice(byteStart: Long, byteEnd: Long): String {
        val length = (byteEnd - byteStart).toInt().coerceAtLeast(0)
        if (length == 0) return ""
        val bytes = ByteArray(length)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(byteStart)
            var offset = 0
            while (offset < length) {
                val n = raf.read(bytes, offset, length - offset)
                if (n < 0) break
                offset += n
            }
        }
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }

    data class ByteChapterRange(
        val title: String?,
        val byteStart: Long,
        val byteEnd: Long,
    )

    companion object {
        private const val MAX_CHAPTER_BYTES = 240_000L

        fun open(file: File): TxtBookSource {
            val sample = file.inputStream().use { it.readNBytes(64 * 1024) }
            val charset = EncodingDetector.detect(sample)
            val ranges = indexChapters(file, charset).ifEmpty {
                listOf(ByteChapterRange("正文", 0L, file.length()))
            }
            return TxtBookSource(file, charset, ranges)
        }

        private fun indexChapters(file: File, charset: Charset): List<ByteChapterRange> {
            val counting = CountingInputStream(file.inputStream().buffered(64 * 1024))
            val reader = counting.reader(charset)
            val chapterRegex = TxtChapterSplitter.CHAPTER_REGEX
            val matches = mutableListOf<Pair<String, Long>>()
            val lineBuf = StringBuilder()
            var lineByteStart = 0L
            var pendingLineStart = 0L
            var ch: Int
            while (true) {
                ch = reader.read()
                if (ch < 0) {
                    val line = lineBuf.toString().trimEnd('\r')
                    if (line.isNotEmpty() && chapterRegex.matches(line.trim())) {
                        matches += line.trim().take(80) to lineByteStart
                    }
                    break
                }
                val c = ch.toChar()
                if (c == '\n') {
                    val line = lineBuf.toString().trimEnd('\r')
                    if (line.isNotEmpty() && chapterRegex.matches(line.trim())) {
                        matches += line.trim().take(80) to lineByteStart
                    }
                    lineBuf.clear()
                    pendingLineStart = counting.bytesRead
                    lineByteStart = pendingLineStart
                } else {
                    if (lineBuf.isEmpty()) {
                        lineByteStart = pendingLineStart
                    }
                    lineBuf.append(c)
                }
            }
            reader.close()

            val fileLen = file.length()
            if (matches.isEmpty()) {
                return virtualSplit(0L, fileLen, null, file)
            }
            val ranges = mutableListOf<ByteChapterRange>()
            val firstStart = matches.first().second
            if (firstStart > 0L) {
                ranges += ByteChapterRange("前言", 0L, firstStart)
            }
            for (i in matches.indices) {
                val start = matches[i].second
                val end = if (i + 1 < matches.size) matches[i + 1].second else fileLen
                ranges += ByteChapterRange(matches[i].first, start, end)
            }
            return ranges.flatMap { range ->
                val len = range.byteEnd - range.byteStart
                if (len <= MAX_CHAPTER_BYTES) listOf(range)
                else virtualSplit(range.byteStart, range.byteEnd, range.title, file)
            }
        }

        private fun virtualSplit(
            byteStart: Long,
            byteEnd: Long,
            baseTitle: String?,
            file: File,
        ): List<ByteChapterRange> {
            val result = mutableListOf<ByteChapterRange>()
            var i = byteStart
            var part = 1
            RandomAccessFile(file, "r").use { raf ->
                while (i < byteEnd) {
                    var end = (i + MAX_CHAPTER_BYTES).coerceAtMost(byteEnd)
                    if (end < byteEnd) {
                        val floor = i + MAX_CHAPTER_BYTES / 2
                        end = scanBackForNewline(raf, end, floor) ?: end
                    }
                    val title = when {
                        baseTitle != null && part == 1 -> baseTitle
                        baseTitle != null -> "$baseTitle ($part)"
                        else -> "第${part}部分"
                    }
                    result += ByteChapterRange(title, i, end)
                    i = end
                    part++
                }
            }
            return result
        }

        private fun scanBackForNewline(raf: RandomAccessFile, from: Long, floor: Long): Long? {
            val buf = ByteArray(4096)
            var pos = from
            while (pos > floor) {
                val readStart = (pos - buf.size).coerceAtLeast(floor)
                val len = (pos - readStart).toInt()
                raf.seek(readStart)
                raf.readFully(buf, 0, len)
                for (idx in len - 1 downTo 0) {
                    if (buf[idx] == '\n'.code.toByte()) {
                        return readStart + idx + 1
                    }
                }
                pos = readStart
            }
            return null
        }
    }
}

private class CountingInputStream(private val inner: java.io.InputStream) : java.io.InputStream() {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        val b = inner.read()
        if (b >= 0) bytesRead++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = inner.read(b, off, len)
        if (n > 0) bytesRead += n
        return n
    }

    override fun close() = inner.close()

    fun reader(charset: Charset): java.io.Reader =
        java.io.InputStreamReader(this, charset)
}
