package com.reader.engine.format.epub

import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.Locator
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import com.reader.engine.format.BookSource
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException

class EpubBookSource(
    private val file: File,
) : BookSource {

    private val zip: ZipFile = ZipSafe.open(file)
    private val opfPath: String
    private val opfDir: String
    private val opf: OpfData
    private val spineHrefs: List<String>
    private val tocEntries: List<TocEntry>
    private val coverPath: String?

    init {
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
    }

    override val format: BookFormat = BookFormat.EPUB
    override val chapterCount: Int = spineHrefs.size

    fun coverEntryPath(): String? = coverPath

    override suspend fun metadata(): BookMetadata =
        BookMetadata(
            title = opf.title ?: file.nameWithoutExtension,
            author = opf.author,
            coverHref = coverPath,
        )

    override suspend fun toc(): List<TocEntry> = tocEntries

    override suspend fun loadChapter(index: Int): Chapter {
        require(index in spineHrefs.indices) { "Chapter out of range" }
        val href = spineHrefs[index]
        return try {
            val bytes = ZipSafe.readEntry(zip, href)
            val xhtml = bytes.toString(Charsets.UTF_8)
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
        zip.close()
    }

    private fun parseRootfile(containerXml: String): String? {
        val regex = Regex("full-path\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        return regex.find(containerXml)?.groupValues?.get(1)
    }

    companion object {
        fun open(file: File): EpubBookSource = EpubBookSource(file)
    }
}
