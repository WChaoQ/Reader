package com.reader.core.model

/**
 * Format-neutral semantic content. Text offsets in [Span] are UTF-16 code-unit
 * offsets into the exact text field of the containing Paragraph or Heading.
 * Format adapters must convert their native offsets before constructing Blocks.
 */
sealed interface Block {
    data class Paragraph(val text: String, val spans: List<Span> = emptyList()) : Block {
        init { validateSpans(text, spans) }
    }

    data class Heading(val level: Int, val text: String, val spans: List<Span> = emptyList()) : Block {
        init {
            require(level in 1..6) { "Heading level must be between 1 and 6" }
            validateSpans(text, spans)
        }
    }

    data class Quote(val children: List<Block>) : Block
    data class ListItem(val ordered: Boolean, val index: Int, val children: List<Block>) : Block
    data class CodeBlock(val text: String) : Block
    data class Image(val ref: ResourceRef, val alt: String?) : Block
    data object Divider : Block
}

/**
 * Inline formatting range using UTF-16 code-unit offsets into the final text.
 * Ranges are half-open: [start, end).
 */
sealed interface Span {
    val start: Int
    val end: Int

    data class Bold(override val start: Int, override val end: Int) : Span {
        init { requireValidRange(start, end) }
    }

    data class Italic(override val start: Int, override val end: Int) : Span {
        init { requireValidRange(start, end) }
    }

    data class Link(override val start: Int, override val end: Int, val href: String) : Span {
        init { requireValidRange(start, end) }
    }

    data class Code(override val start: Int, override val end: Int) : Span {
        init { requireValidRange(start, end) }
    }
}

data class Chapter(
    val index: Int,
    val title: String?,
    val blocks: List<Block>,
) {
    init {
        require(index >= 0) { "Chapter index must be zero-based and non-negative" }
    }
}

private fun validateSpans(text: String, spans: List<Span>) {
    require(spans.all { it.start >= 0 && it.end >= it.start && it.end <= text.length }) {
        "Span ranges must fit the final text using UTF-16 offsets"
    }
}

private fun requireValidRange(start: Int, end: Int) {
    require(start >= 0 && end >= start) { "Span range must be non-negative and half-open" }
}
