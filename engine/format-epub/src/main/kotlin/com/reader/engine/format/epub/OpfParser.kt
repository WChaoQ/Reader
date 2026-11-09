package com.reader.engine.format.epub

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class OpfData(
    val title: String?,
    val author: String?,
    val manifest: Map<String, ManifestItem>,
    val spine: List<String>,
    val coverId: String? = null,
)

data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: List<String> = emptyList(),
)

object OpfParser {
    fun parse(xml: String): OpfData {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var title: String? = null
        var author: String? = null
        var coverId: String? = null
        val manifest = linkedMapOf<String, ManifestItem>()
        val spine = mutableListOf<String>()

        var event = parser.eventType
        var inManifest = false
        var inSpine = false
        var textBuf = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    textBuf = StringBuilder()
                    when (parser.name.lowercase().substringAfter(':')) {
                        "manifest" -> inManifest = true
                        "spine" -> inSpine = true
                        "item" -> if (inManifest) {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val mt = parser.getAttributeValue(null, "media-type")
                                ?: parser.getAttributeValue(null, "mediaType")
                                ?: ""
                            val props = (parser.getAttributeValue(null, "properties") ?: "")
                                .split(Regex("\\s+"))
                                .filter { it.isNotBlank() }
                            if (id.isNotEmpty() && href.isNotEmpty()) {
                                manifest[id] = ManifestItem(id, href, mt, props)
                            }
                        }
                        "itemref" -> if (inSpine) {
                            val idref = parser.getAttributeValue(null, "idref")
                            if (!idref.isNullOrEmpty()) spine += idref
                        }
                        "meta" -> {
                            val name = parser.getAttributeValue(null, "name")
                                ?: parser.getAttributeValue(null, "property")
                            val content = parser.getAttributeValue(null, "content")
                            if (name.equals("cover", ignoreCase = true) && !content.isNullOrBlank()) {
                                coverId = content
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> textBuf.append(parser.text)
                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase().substringAfter(':')) {
                        "manifest" -> inManifest = false
                        "spine" -> inSpine = false
                        "title" -> if (title == null) {
                            val t = textBuf.toString().trim()
                            if (t.isNotEmpty()) title = t
                        }
                        "creator" -> if (author == null) {
                            val a = textBuf.toString().trim()
                            if (a.isNotEmpty()) author = a
                        }
                    }
                }
            }
            event = parser.next()
        }
        return OpfData(title, author, manifest, spine, coverId)
    }
}
