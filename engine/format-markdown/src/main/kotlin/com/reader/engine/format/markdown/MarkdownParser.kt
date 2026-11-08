package com.reader.engine.format.markdown

import com.reader.core.model.Block
import com.reader.core.model.Span

/** Minimal CommonMark subset (architecture §6). */
object MarkdownParser {

    fun parseDocument(text: String): List<Pair<String?, List<Block>>> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val chapters = mutableListOf<Pair<String?, List<Block>>>()
        var title: String? = null
        val blocks = mutableListOf<Block>()
        var i = 0

        fun flushChapter() {
            if (blocks.isNotEmpty() || title != null) {
                chapters += title to blocks.toList()
                blocks.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val heading = Regex("""^(#{1,6})\s+(.+)$""").find(line)
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    val textH = heading.groupValues[2].trim()
                    if (level <= 2) {
                        flushChapter()
                        title = textH
                        blocks += Block.Heading(level, textH)
                    } else {
                        blocks += Block.Heading(level, textH)
                    }
                    i++
                }
                line.trim() == "---" || line.trim() == "***" || line.trim() == "___" -> {
                    blocks += Block.Divider
                    i++
                }
                line.startsWith("```") -> {
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    if (i < lines.size) i++
                    blocks += Block.CodeBlock(code.toString())
                }
                line.startsWith(">") -> {
                    val quoteLines = mutableListOf<String>()
                    while (i < lines.size && lines[i].startsWith(">")) {
                        quoteLines += lines[i].removePrefix(">").trimStart()
                        i++
                    }
                    val inner = parseInlineBlocks(quoteLines.joinToString("\n"))
                    blocks += Block.Quote(inner)
                }
                Regex("""^\s*([-*+]|\d+\.)\s+""").containsMatchIn(line) -> {
                    var index = 1
                    while (i < lines.size && Regex("""^\s*([-*+]|\d+\.)\s+""").containsMatchIn(lines[i])) {
                        val ordered = Regex("""^\s*\d+\.\s+""").containsMatchIn(lines[i])
                        val content = lines[i].replace(Regex("""^\s*([-*+]|\d+\.)\s+"""), "")
                        blocks += Block.ListItem(
                            ordered = ordered,
                            index = index++,
                            children = listOf(Block.Paragraph(content, parseSpans(content))),
                        )
                        i++
                    }
                }
                line.isBlank() -> i++
                else -> {
                    val para = StringBuilder(line)
                    i++
                    while (i < lines.size && lines[i].isNotBlank() &&
                        !lines[i].startsWith("#") &&
                        !lines[i].startsWith("```") &&
                        !lines[i].startsWith(">") &&
                        !Regex("""^\s*([-*+]|\d+\.)\s+""").containsMatchIn(lines[i])
                    ) {
                        para.append('\n').append(lines[i])
                        i++
                    }
                    val textP = para.toString().trim()
                    blocks += Block.Paragraph(textP, parseSpans(textP))
                }
            }
        }
        flushChapter()
        if (chapters.isEmpty()) {
            chapters += null to listOf(Block.Paragraph(text.trim()))
        }
        return chapters
    }

    private fun parseInlineBlocks(text: String): List<Block> =
        text.split("\n\n").filter { it.isNotBlank() }.map {
            Block.Paragraph(it.trim(), parseSpans(it.trim()))
        }

    fun parseSpans(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        Regex("""\*\*(.+?)\*\*""").findAll(text).forEach {
            spans += Span.Bold(it.range.first, it.range.last + 1)
        }
        Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)""").findAll(text).forEach {
            spans += Span.Italic(it.range.first, it.range.last + 1)
        }
        Regex("""`([^`]+)`""").findAll(text).forEach {
            spans += Span.Code(it.range.first, it.range.last + 1)
        }
        Regex("""\[([^\]]+)\]\(([^)]+)\)""").findAll(text).forEach {
            spans += Span.Link(it.range.first, it.range.last + 1, it.groupValues[2])
        }
        return spans
    }
}
