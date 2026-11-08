package com.reader.engine.format.markdown

import com.reader.core.model.Block
import com.reader.core.model.Span

/** Small CommonMark-oriented parser for the reader's core inline and block syntax. */
object MarkdownParser {

    private data class ParsedInline(
        val text: String,
        val spans: List<Span>,
    )

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
                    val parsed = parseInline(heading.groupValues[2].trim())
                    if (level <= 2) {
                        flushChapter()
                        title = parsed.text
                        blocks += Block.Heading(level, parsed.text, parsed.spans)
                    } else {
                        blocks += Block.Heading(level, parsed.text, parsed.spans)
                    }
                    i++
                }
                line.trim() == "---" || line.trim() == "***" || line.trim() == "___" -> {
                    blocks += Block.Divider
                    i++
                }
                line.startsWith("```") || line.startsWith("~~~") -> {
                    val fence = line.takeWhile { it == '`' || it == '~' }
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
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
                    blocks += Block.Quote(parseInlineBlocks(quoteLines.joinToString("\n")))
                }
                Regex("""^\s*([-*+]|\d+\.)\s+""").containsMatchIn(line) -> {
                    var index = 1
                    while (i < lines.size && Regex("""^\s*([-*+]|\d+\.)\s+""").containsMatchIn(lines[i])) {
                        val ordered = Regex("""^\s*\d+\.\s+""").containsMatchIn(lines[i])
                        val content = lines[i].replace(Regex("""^\s*([-*+]|\d+\.)\s+"""), "")
                        val taskContent = content.replace(Regex("""^\[([ xX])\]\s+"""), "")
                        val parsed = parseInline(taskContent)
                        blocks += Block.ListItem(
                            ordered = ordered,
                            index = index++,
                            children = listOf(Block.Paragraph(parsed.text, parsed.spans)),
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
                        !lines[i].startsWith("~~~") &&
                        !lines[i].startsWith(">") &&
                        !Regex("""^\s*([-*+]|\d+\.)\s+""").containsMatchIn(lines[i])
                    ) {
                        para.append('\n').append(lines[i])
                        i++
                    }
                    val parsed = parseInline(para.toString().trim())
                    blocks += Block.Paragraph(parsed.text, parsed.spans)
                }
            }
        }
        flushChapter()
        if (chapters.isEmpty()) {
            val parsed = parseInline(text.trim())
            chapters += null to listOf(Block.Paragraph(parsed.text, parsed.spans))
        }
        return chapters
    }

    private fun parseInlineBlocks(text: String): List<Block> =
        text.split("\n\n").filter { it.isNotBlank() }.map {
            val parsed = parseInline(it.trim())
            Block.Paragraph(parsed.text, parsed.spans)
        }

    /** Removes inline delimiters and records spans against the resulting visible text. */
    private fun parseInline(source: String): ParsedInline {
        val output = StringBuilder()
        val spans = mutableListOf<Span>()
        var index = 0

        while (index < source.length) {
            if (source.startsWith("**", index)) {
                val end = source.indexOf("**", index + 2)
                if (end > index + 2) {
                    val start = output.length
                    output.append(source, index + 2, end)
                    spans += Span.Bold(start, output.length)
                    index = end + 2
                    continue
                }
            }

            if (source[index] == '`') {
                val end = source.indexOf('`', index + 1)
                if (end > index + 1) {
                    val start = output.length
                    output.append(source, index + 1, end)
                    spans += Span.Code(start, output.length)
                    index = end + 1
                    continue
                }
            }

            if (source[index] == '*' || source[index] == '_') {
                val marker = source[index]
                val end = source.indexOf(marker, index + 1)
                if (end > index + 1) {
                    val start = output.length
                    output.append(source, index + 1, end)
                    spans += Span.Italic(start, output.length)
                    index = end + 1
                    continue
                }
            }

            if (source[index] == '[') {
                val labelEnd = source.indexOf("](", index + 1)
                val linkEnd = if (labelEnd >= 0) source.indexOf(')', labelEnd + 2) else -1
                if (labelEnd > index + 1 && linkEnd > labelEnd + 2) {
                    val start = output.length
                    output.append(source, index + 1, labelEnd)
                    val href = source.substring(labelEnd + 2, linkEnd).trim()
                    spans += Span.Link(start, output.length, href)
                    index = linkEnd + 1
                    continue
                }
            }

            output.append(source[index])
            index++
        }

        return ParsedInline(output.toString(), spans)
    }
}
