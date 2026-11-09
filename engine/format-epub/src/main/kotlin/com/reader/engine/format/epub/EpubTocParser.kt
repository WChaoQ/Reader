package com.reader.engine.format.epub

import com.reader.core.model.Locator
import com.reader.core.model.TocEntry
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.zip.ZipFile

/**
 * Resolves the human-readable table of contents from EPUB 3 nav document or EPUB 2 NCX.
 * Falls back to spine-derived titles when neither is present.
 */
object EpubTocParser {

    fun parse(
        zip: ZipFile,
        opf: OpfData,
        opfPath: String,
        spineHrefs: List<String>,
    ): List<TocEntry> {
        val navHref = findNavHref(opf)
        if (navHref != null) {
            val resolved = ZipSafe.resolveHref(opfPath, navHref)
            runCatching {
                val xml = ZipSafe.readEntry(zip, resolved).toString(Charsets.UTF_8)
                val entries = parseNav(xml, resolved, spineHrefs)
                if (entries.isNotEmpty()) return entries
            }
        }
        val ncxHref = findNcxHref(opf)
        if (ncxHref != null) {
            val resolved = ZipSafe.resolveHref(opfPath, ncxHref)
            runCatching {
                val xml = ZipSafe.readEntry(zip, resolved).toString(Charsets.UTF_8)
                val entries = parseNcx(xml, resolved, spineHrefs)
                if (entries.isNotEmpty()) return entries
            }
        }
        return spineHrefs.mapIndexed { index, href ->
            TocEntry(
                title = href.substringAfterLast('/').substringBeforeLast('.').ifBlank { "第 ${index + 1} 章" },
                locator = Locator(chapter = index, offset = 0),
            )
        }
    }

    fun coverHref(opf: OpfData): String? {
        opf.manifest.values.firstOrNull { item ->
            item.properties.any { it.equals("cover-image", ignoreCase = true) }
        }?.href?.let { return it }
        val coverId = opf.coverId
        if (!coverId.isNullOrBlank()) {
            opf.manifest[coverId]?.href?.let { return it }
        }
        return opf.manifest.values.firstOrNull { item ->
            item.mediaType.startsWith("image/") &&
                (item.id.contains("cover", ignoreCase = true) ||
                    item.href.contains("cover", ignoreCase = true))
        }?.href
    }

    private fun findNavHref(opf: OpfData): String? =
        opf.manifest.values.firstOrNull { item ->
            item.properties.any { it.equals("nav", ignoreCase = true) } ||
                item.mediaType.equals("application/xhtml+xml", ignoreCase = true) &&
                item.href.contains("nav", ignoreCase = true)
        }?.href

    private fun findNcxHref(opf: OpfData): String? =
        opf.manifest.values.firstOrNull {
            it.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true)
        }?.href

    private fun parseNav(xml: String, navPath: String, spineHrefs: List<String>): List<TocEntry> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        val cleaned = xml
            .replace(Regex("""<!DOCTYPE[^>]*>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<\\?xml[^?]*\\?>""", RegexOption.IGNORE_CASE), "")
        parser.setInput(StringReader(cleaned))

        val entries = mutableListOf<TocEntry>()
        var inTocNav = false
        var pendingText = StringBuilder()
        var pendingHref: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase().substringAfter(':')
                    when (name) {
                        "nav" -> {
                            val type = parser.getAttributeValue(null, "type")
                                ?: parser.getAttributeValue("http://www.idpf.org/2007/ops", "type")
                                ?: ""
                            if (type.equals("toc", ignoreCase = true) || type.isEmpty()) {
                                inTocNav = true
                            }
                        }
                        "a" -> if (inTocNav) {
                            pendingHref = parser.getAttributeValue(null, "href")
                            pendingText = StringBuilder()
                        }
                    }
                }
                XmlPullParser.TEXT -> if (inTocNav) pendingText.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase().substringAfter(':')
                    when (name) {
                        "nav" -> inTocNav = false
                        "a" -> if (inTocNav && pendingHref != null) {
                            val title = pendingText.toString().replace(Regex("\\s+"), " ").trim()
                            if (title.isNotEmpty()) {
                                val target = ZipSafe.resolveHref(navPath, pendingHref!!.substringBefore('#'))
                                val chapter = spineHrefs.indexOfFirst { it == target || it.endsWith(target) }
                                    .takeIf { it >= 0 }
                                    ?: spineHrefs.indexOfFirst { target.endsWith(it.substringAfterLast('/')) }
                                        .takeIf { it >= 0 }
                                    ?: entries.size.coerceAtMost(spineHrefs.lastIndex.coerceAtLeast(0))
                                entries += TocEntry(title = title, locator = Locator(chapter = chapter, offset = 0))
                            }
                            pendingHref = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        return entries.distinctBy { it.locator.chapter to it.title }
    }

    private fun parseNcx(xml: String, ncxPath: String, spineHrefs: List<String>): List<TocEntry> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val entries = mutableListOf<TocEntry>()
        var inNavPoint = false
        var textBuf = StringBuilder()
        var contentSrc: String? = null
        var capturingText = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase().substringAfter(':')) {
                        "navpoint" -> {
                            inNavPoint = true
                            textBuf = StringBuilder()
                            contentSrc = null
                        }
                        "text" -> if (inNavPoint) {
                            capturingText = true
                            textBuf = StringBuilder()
                        }
                        "content" -> if (inNavPoint) {
                            contentSrc = parser.getAttributeValue(null, "src")
                        }
                    }
                }
                XmlPullParser.TEXT -> if (capturingText) textBuf.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase().substringAfter(':')) {
                        "text" -> capturingText = false
                        "navpoint" -> if (inNavPoint) {
                            val title = textBuf.toString().replace(Regex("\\s+"), " ").trim()
                            val src = contentSrc
                            if (title.isNotEmpty() && src != null) {
                                val target = ZipSafe.resolveHref(ncxPath, src.substringBefore('#'))
                                val chapter = spineHrefs.indexOfFirst { it == target || it.endsWith(target) }
                                    .takeIf { it >= 0 }
                                    ?: spineHrefs.indexOfFirst { target.endsWith(it.substringAfterLast('/')) }
                                        .takeIf { it >= 0 }
                                    ?: entries.size.coerceAtMost(spineHrefs.lastIndex.coerceAtLeast(0))
                                entries += TocEntry(title = title, locator = Locator(chapter = chapter, offset = 0))
                            }
                            inNavPoint = false
                        }
                    }
                }
            }
            event = parser.next()
        }
        return entries.distinctBy { it.locator.chapter to it.title }
    }
}
