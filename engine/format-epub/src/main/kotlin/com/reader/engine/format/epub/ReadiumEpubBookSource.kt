package com.reader.engine.format.epub

import android.content.Context
import android.net.Uri
import com.reader.core.model.BookFormat
import com.reader.core.model.BookMetadata
import com.reader.core.model.Chapter
import com.reader.core.model.Locator
import com.reader.core.model.ResourceRef
import com.reader.core.model.TocEntry
import com.reader.engine.format.BookSource
import com.reader.engine.format.DocumentHandle
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/**
 * Readium-backed EPUB session.
 *
 * Readium models are deliberately contained in this file. The rest of Reader only sees
 * the project-owned BookSource / Chapter / Locator contracts.
 */
class ReadiumEpubBookSource private constructor(
    private val publication: Publication,
    private val displayName: String,
) : BookSource {

    private val readingOrder = publication.readingOrder
    private val tocEntries = publication.tableOfContents.mapNotNull { it.toTocEntry() }

    override val format: BookFormat = BookFormat.EPUB
    override val chapterCount: Int = readingOrder.size

    override suspend fun metadata(): BookMetadata {
        val metadata = publication.metadata
        return BookMetadata(
            title = metadata.title?.takeIf(String::isNotBlank)
                ?: displayName.substringBeforeLast('.'),
            author = null,
            language = metadata.languages.firstOrNull(),
            coverHref = publication.linkWithRel("cover")?.href?.toString(),
        )
    }

    override suspend fun toc(): List<TocEntry> = tocEntries

    override suspend fun loadChapter(index: Int): Chapter {
        require(index in readingOrder.indices) { "Chapter out of range: $index" }
        val link = readingOrder[index]
        val href = link.href.toString()
        val resource = publication.get(link)
            ?: throw IOException("Readium cannot resolve EPUB chapter: $href")
        val bytes = resource.use { chapterResource ->
            chapterResource.read().getOrElse { error ->
                throw IOException("Unable to read EPUB chapter $href: $error")
            }
        }
        val xhtml = decodeXhtml(bytes)
        val blocks = XhtmlToBlocks.parse(xhtml, href)
        val title = tocEntries.firstOrNull { it.locator.chapter == index }?.title
            ?: href.substringAfterLast('/').substringBeforeLast('.')
        return Chapter(index = index, title = title, blocks = blocks)
    }

    override suspend fun openResource(ref: ResourceRef): InputStream {
        val target = normalizeHref(ref.href)
        val link = (readingOrder + publication.resources + publication.links)
            .firstOrNull { normalizeHref(it.href.toString()) == target }
            ?: throw IOException("EPUB resource not found: ${ref.href}")
        val resource = publication.get(link)
            ?: throw IOException("Readium cannot resolve EPUB resource: " + ref.href)
        val bytes = resource.use { contentResource ->
            contentResource.read().getOrElse { error ->
                throw IOException("Unable to read EPUB resource " + ref.href + ": $error")
            }
        }
        return ByteArrayInputStream(bytes)
    }

    override fun close() {
        publication.close()
    }

    private fun Link.toTocEntry(): TocEntry? {
        val chapterIndex = readingOrder.indexOfFirst {
            normalizeHref(it.href.toString()) == normalizeHref(href.toString())
        }
        val children = children.mapNotNull { it.toTocEntry() }
        if (chapterIndex < 0 && children.isEmpty()) return null
        return TocEntry(
            title = href.toString().substringAfterLast('/'),
            locator = Locator(chapter = chapterIndex.coerceAtLeast(0), offset = 0),
            children = children,
        )
    }

    private fun decodeXhtml(bytes: ByteArray): String {
        val head = bytes.copyOfRange(0, minOf(bytes.size, 256)).toString(Charsets.ISO_8859_1)
        val charsetName = Regex(
            """encoding\s*=\s*['"]([A-Za-z0-9_\-]+)['"]""",
            RegexOption.IGNORE_CASE,
        ).find(head)?.groupValues?.get(1)
        val charset = runCatching {
            if (charsetName != null) java.nio.charset.Charset.forName(charsetName) else Charsets.UTF_8
        }.getOrDefault(Charsets.UTF_8)
        return bytes.toString(charset).removePrefix("\uFEFF")
    }

    private fun normalizeHref(value: String): String =
        Uri.decode(value.substringBefore('#').substringBefore('?').removePrefix("/"))

    companion object {
        suspend fun open(context: Context, document: DocumentHandle): ReadiumEpubBookSource {
            val httpClient = DefaultHttpClient()
            val assetRetriever = AssetRetriever(
                contentResolver = context.contentResolver,
                httpClient = httpClient,
            )
            val parser = DefaultPublicationParser(
                context = context,
                httpClient = httpClient,
                assetRetriever = assetRetriever,
                pdfFactory = null,
            )
            val opener = PublicationOpener(publicationParser = parser)
            val sourceUrl = AbsoluteUrl(document.uri.toString())
                ?: throw IOException("无效的文档 URI：${document.uri}")
            val asset = assetRetriever.retrieve(sourceUrl).getOrElse { error ->
                throw IOException("无法读取 EPUB 文件：$error")
            }
            val openResult = try {
                opener.open(asset, allowUserInteraction = false)
            } catch (failure: Throwable) {
                asset.close()
                throw failure
            }
            val publication = try {
                openResult.getOrElse { error ->
                    throw IOException("EPUB 解析失败：$error")
                }
            } catch (failure: Throwable) {
                asset.close()
                throw IOException("EPUB 解析失败：${failure.message}", failure)
            }

            if (publication.readingOrder.isEmpty()) {
                publication.close()
                throw IOException("EPUB 没有可读取的正文内容（reading order 为空）")
            }
            return ReadiumEpubBookSource(publication, document.displayName)
        }
    }
}
