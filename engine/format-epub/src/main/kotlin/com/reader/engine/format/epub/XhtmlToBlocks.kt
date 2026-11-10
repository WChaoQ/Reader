package com.reader.engine.format.epub

import com.reader.core.model.Block
import com.reader.core.model.ResourceRef
import com.reader.core.model.Span
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

object XhtmlToBlocks {

    fun parse(xhtml: String, chapterBasePath: String): List<Block> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        val cleaned = xhtml
            .replace(Regex("""<!DOCTYPE[^>]*>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<\?xml[^?]*\?>""", RegexOption.IGNORE_CASE), "")
        parser.setInput(StringReader(cleaned))

        val blocks = mutableListOf<Block>()
        val text = StringBuilder()
        val spans = mutableListOf<Span>()
        var inBody = false
        var skipScript = false
        var headingLevel = 0
        var inLink = false
        var linkHref = ""
        var linkStart = 0
        var boldDepth = 0
        var italicDepth = 0
        var boldStart = 0
        var italicStart = 0

        fun flushParagraph() {
            val (normalizedText, normalizedSpans) = normalizeTextAndSpans(text.toString(), spans)
            if (normalizedText.isNotEmpty()) {
                if (headingLevel in 1..6) {
                    blocks += Block.Heading(headingLevel, normalizedText, normalizedSpans)
                } else {
                    blocks += Block.Paragraph(normalizedText, normalizedSpans)
                }
            }
            text.clear()
            spans.clear()
            headingLevel = 0
        }

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name.lowercase().substringAfter(':')
                        when (name) {
                            "body" -> inBody = true
                            "script", "style" -> skipScript = true
                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                flushParagraph()
                                headingLevel = name.last().digitToInt()
                            }
                            "p", "div" -> if (text.isNotEmpty()) flushParagraph()
                            "br" -> text.append('\n')
                            "li" -> if (text.isNotEmpty()) flushParagraph()
                            "img" -> {
                                flushParagraph()
                                val src = parser.getAttributeValue(null, "src")
                                    ?: parser.getAttributeValue(null, "href")
                                val alt = parser.getAttributeValue(null, "alt")
                                if (!src.isNullOrEmpty()) {
                                    val ref = EpubPathResolver.resolve(chapterBasePath, src)
                                    blocks += Block.Image(ResourceRef(ref), alt)
                                }
                            }
                            "hr" -> {
                                flushParagraph()
                                blocks += Block.Divider
                            }
                            "a" -> {
                                inLink = true
                                linkHref = parser.getAttributeValue(null, "href") ?: ""
                                linkStart = text.length
                            }
                            "b", "strong" -> {
                                if (boldDepth == 0) boldStart = text.length
                                boldDepth++
                            }
                            "i", "em" -> {
                                if (italicDepth == 0) italicStart = text.length
                                italicDepth++
                            }
                            "blockquote" -> if (text.isNotEmpty()) flushParagraph()
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inBody && !skipScript) text.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name.lowercase().substringAfter(':')
                        when (name) {
                            "script", "style" -> skipScript = false
                            "h1", "h2", "h3", "h4", "h5", "h6", "p" -> flushParagraph()
                            "div" -> if (text.isNotEmpty()) flushParagraph()
                            "li" -> {
                                val (itemText, itemSpans) = normalizeTextAndSpans(text.toString(), spans)
                                if (itemText.isNotEmpty()) {
                                    blocks += Block.ListItem(
                                        ordered = false,
                                        index = 0,
                                        children = listOf(Block.Paragraph(itemText, itemSpans)),
                                    )
                                }
                                text.clear()
                                spans.clear()
                            }
                            "a" -> {
                                if (inLink && linkHref.isNotEmpty()) {
                                    val end = text.length
                                    if (end > linkStart) spans += Span.Link(linkStart, end, linkHref)
                                }
                                inLink = false
                            }
                            "b", "strong" -> {
                                boldDepth--
                                if (boldDepth == 0 && text.length > boldStart) {
                                    spans += Span.Bold(boldStart, text.length)
                                }
                            }
                            "i", "em" -> {
                                italicDepth--
                                if (italicDepth == 0 && text.length > italicStart) {
                                    spans += Span.Italic(italicStart, text.length)
                                }
                            }
                            "blockquote" -> {
                                val (quoteText, quoteSpans) = normalizeTextAndSpans(text.toString(), spans)
                                if (quoteText.isNotEmpty()) {
                                    blocks += Block.Quote(listOf(Block.Paragraph(quoteText, quoteSpans)))
                                }
                                text.clear()
                                spans.clear()
                            }
                        }
                    }
                }
                event = parser.next()
            }
            flushParagraph()
        } catch (_: Exception) {
            if (blocks.isEmpty()) blocks += Block.Paragraph("[本章解析失败]")
        }
        return blocks.ifEmpty { listOf(Block.Paragraph("")) }
    }
    /**
     * Collapse whitespace and trim it while remapping every span boundary to the
     * normalized UTF-16 string. This keeps the public Block contract independent
     * from XHTML source whitespace.
     */
    private fun normalizeTextAndSpans(
        raw: String,
        sourceSpans: List<Span>,
    ): Pair<String, List<Span>> {
        val normalized = StringBuilder(raw.length)
        val boundaries = IntArray(raw.length + 1)
        var pendingWhitespaceStart = -1
        var pendingWhitespaceEnd = -1

        for (index in raw.indices) {
            val char = raw[index]
            if (char.isWhitespace()) {
                if (normalized.isNotEmpty()) {
                    if (pendingWhitespaceStart < 0) pendingWhitespaceStart = index
                    pendingWhitespaceEnd = index + 1
                }
                boundaries[index + 1] = normalized.length
                continue
            }

            if (pendingWhitespaceStart >= 0) {
                val beforeSpace = normalized.length
                normalized.append(' ')
                val afterSpace = normalized.length
                for (boundary in pendingWhitespaceStart until pendingWhitespaceEnd) {
                    boundaries[boundary] = beforeSpace
                }
                boundaries[pendingWhitespaceEnd] = afterSpace
                pendingWhitespaceStart = -1
                pendingWhitespaceEnd = -1
            }

            boundaries[index] = normalized.length
            normalized.append(char)
            boundaries[index + 1] = normalized.length
        }

        // Trailing whitespace is trimmed and maps to the final text boundary.
        if (pendingWhitespaceStart >= 0) {
            for (boundary in pendingWhitespaceStart..pendingWhitespaceEnd) {
                boundaries[boundary] = normalized.length
            }
        }

        val mappedSpans = sourceSpans.mapNotNull { span ->
            val start = boundaries[span.start.coerceIn(0, raw.length)]
            val end = boundaries[span.end.coerceIn(0, raw.length)]
            if (end <= start) return@mapNotNull null
            when (span) {
                is Span.Bold -> Span.Bold(start, end)
                is Span.Italic -> Span.Italic(start, end)
                is Span.Link -> Span.Link(start, end, span.href)
                is Span.Code -> Span.Code(start, end)
            }
        }
        return normalized.toString() to mappedSpans
    }

}
