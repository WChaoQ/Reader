package com.reader.engine.format.epub

import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.Locator
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import com.reader.engine.format.BookSource
import com.reader.engine.format.DocumentHandle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException

class EpubBookSource(
    private val document: DocumentHandle,
) : BookSource {

    private val descriptor: ParcelFileDescriptor = document.openSeekableFileDescriptor()
    private val zip: ZipFile = try {
        ZipSafe.open(File("/proc/self/fd/${descriptor.fd}"))
    } catch (failure: Throwable) {
        descriptor.close()
        throw failure
    }
    private val opfPath: String
    private val opfDir: String
    private val opf: OpfData
    private val spineHrefs: List<String>
    private val tocEntries: List<TocEntry>
    private val coverPath: String?

    init {
        try {
            val containerXml = ZipSafe.readEntry(zip, "META-INF/container.xml").toString(Charsets.UTF_8)
            opfPath = parseRootfile(containerXml)
                ?: error("No rootfile in container.xml")
            opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
            val opfXml = ZipSafe.readEntry(zip, opfPath).toString(Charsets.UTF_8)
            opf = OpfParser.parse(opfXml)
            spineHrefs = opf.spine.mapNotNull { id -> opf.manifest[id]?.href }.map { href ->
                if (opfDir.isEmpty()) href else ZipSafe.resolveHref(opfPath, href)
            }
            require(spineHrefs.isNotEmpty()) { "EPUB spine is empty or invalid" }
            tocEntries = EpubTocParser.parse(zip, opf, opfPath, spineHrefs)
            coverPath = EpubTocParser.coverHref(opf)?.let { href ->
                if (opfDir.isEmpty()) href else ZipSafe.resolveHref(opfPath, href)
            }
        } catch (failure: Throwable) {
            runCatching { zip.close() }
            runCatching { descriptor.close() }
            throw failure
        }
    }

    override val format: BookFormat = BookFormat.EPUB
    override val chapterCount: Int = spineHrefs.size

    fun coverEntryPath(): String? = coverPath

    override suspend fun metadata(): BookMetadata =
        BookMetadata(
            title = opf.title ?: document.displayName.substringBeforeLast('.'),
            author = opf.author,
            coverHref = coverPath,
        )

    override suspend fun toc(): List<TocEntry> = tocEntries

    override suspend fun loadChapter(index: Int): Chapter {
        require(index in spineHrefs.indices) { "Chapter out of range" }
        val href = spineHrefs[index]
        return try {
            val bytes = ZipSafe.readEntry(zip, href)
            val xhtml = decodeXhtml(bytes)
            val blocks = XhtmlToBlocks.parse(xhtml, href)
            val title = tocEntries.firstOrNull { it.locator.chapter == index }?.title
                ?: href.substringAfterLast('/').substringBeforeLast('.')
            Chapter(
                index = index,
                title = title,
                blocks = blocks,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            Chapter(
                index = index,
                title = "章节 ${index + 1}",
                blocks = listOf(com.reader.core.model.Block.Paragraph("[无法加载: ${t.message}]")),
            )
        }
    }

    override suspend fun openResource(ref: ResourceRef): InputStream {
        val path = if (opfDir.isEmpty()) ref.href else ZipSafe.resolveHref("$opfDir/", ref.href)
        return ZipSafe.openStream(zip, path)
    }

    override fun close() {
        try { zip.close() } finally { descriptor.close() }
    }

    private fun parseRootfile(containerXml: String): String? {
        val regex = Regex("full-path\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        return regex.find(containerXml)?.groupValues?.get(1)
    }

    private fun decodeXhtml(bytes: ByteArray): String {
        // XML declarations use ordinary whitespace around '='; this raw regex must
        // contain single backslashes so \s is interpreted by the regex engine.
        val head = bytes.copyOfRange(0, minOf(bytes.size, 256)).toString(Charsets.ISO_8859_1)
        val charsetName = Regex(
            """encoding\s*=\s*['"]([A-Za-z0-9_-]+)['"]""",
            RegexOption.IGNORE_CASE,
        ).find(head)?.groupValues?.get(1)
        val charset = runCatching {
            if (charsetName != null) java.nio.charset.Charset.forName(charsetName) else Charsets.UTF_8
        }.getOrDefault(Charsets.UTF_8)
        return bytes.toString(charset).removePrefix("\uFEFF")
    }

    companion object {
        fun open(document: DocumentHandle): EpubBookSource = EpubBookSource(document)
    }
}
