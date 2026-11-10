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
            val t = text.toString().replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty()) {
                if (headingLevel in 1..6) {
                    blocks += Block.Heading(headingLevel, t, spans.toList())
                } else {
                    blocks += Block.Paragraph(t, spans.toList())
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
                                val t = text.toString().replace(Regex("\\s+"), " ").trim()
                                if (t.isNotEmpty()) {
                                    blocks += Block.ListItem(
                                        ordered = false,
                                        index = 0,
                                        children = listOf(Block.Paragraph(t, spans.toList())),
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
                                val t = text.toString().trim()
                                if (t.isNotEmpty()) {
                                    blocks += Block.Quote(listOf(Block.Paragraph(t, spans.toList())))
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
}
