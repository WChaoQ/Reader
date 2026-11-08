package com.reader.core.model

sealed interface Block {
    data class Paragraph(val text: String, val spans: List<Span> = emptyList()) : Block
    data class Heading(val level: Int, val text: String, val spans: List<Span> = emptyList()) : Block
    data class Quote(val children: List<Block>) : Block
    data class ListItem(val ordered: Boolean, val index: Int, val children: List<Block>) : Block
    data class CodeBlock(val text: String) : Block
    data class Image(val ref: ResourceRef, val alt: String?) : Block
    data object Divider : Block
}

sealed interface Span {
    val start: Int
    val end: Int

    data class Bold(override val start: Int, override val end: Int) : Span
    data class Italic(override val start: Int, override val end: Int) : Span
    data class Link(override val start: Int, override val end: Int, val href: String) : Span
    data class Code(override val start: Int, override val end: Int) : Span
}

data class Chapter(
    val index: Int,
    val title: String?,
    val blocks: List<Block>,
)
