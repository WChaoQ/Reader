package com.reader.engine.layout

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import com.reader.core.model.Block
import com.reader.core.model.Span
import kotlin.math.ceil

/**
 * Converts structured document blocks into the exact styled text used by both pagination
 * and rendering. Keeping one builder guarantees that measured line breaks and rendered offsets
 * remain aligned for headings, inline styles, lists, quotes, and code blocks.
 */
object DocumentTextBuilder {

    fun build(
        blocks: List<Block>,
        paragraphSpacingPx: Float = 12f,
    ): SpannableStringBuilder {
        val paragraphSpacing = ceil(paragraphSpacingPx.coerceAtLeast(0f)).toInt()

        fun appendBlockSeparator(target: SpannableStringBuilder) {
            if (target.isEmpty()) return
            val previousEnd = target.length
            val previousChar = target[previousEnd - 1]
            val contentEnd = if (previousChar == '\n') previousEnd - 1 else previousEnd
            if (paragraphSpacing > 0 && contentEnd > 0) {
                target.setSpan(
                    ParagraphSpacingSpan(paragraphSpacing),
                    contentEnd - 1,
                    contentEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (previousChar != '\n') target.append("\n")
        }
        fun applyInlineSpans(
            target: SpannableStringBuilder,
            spans: List<Span>,
            start: Int,
            textLength: Int,
        ) {
            spans.forEach { span ->
                val from = (start + span.start).coerceIn(start, start + textLength)
                val to = (start + span.end).coerceIn(from, start + textLength)
                if (to <= from) return@forEach
                when (span) {
                    is Span.Bold -> target.setSpan(
                        StyleSpan(Typeface.BOLD), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    is Span.Italic -> target.setSpan(
                        StyleSpan(Typeface.ITALIC), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    is Span.Code -> {
                        target.setSpan(TypefaceSpan("monospace"), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        target.setSpan(BackgroundColorSpan(0x22808080), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    is Span.Link -> {
                        target.setSpan(ForegroundColorSpan(0xFF4778B8.toInt()), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        target.setSpan(UnderlineSpan(), from, to, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
        }

        fun render(items: List<Block>): SpannableStringBuilder {
            val result = SpannableStringBuilder()
            items.forEach { block ->
                when (block) {
                    is Block.Paragraph -> {
                        appendBlockSeparator(result)
                        val start = result.length
                        result.append(block.text)
                        applyInlineSpans(result, block.spans, start, block.text.length)
                    }
                    is Block.Heading -> {
                        appendBlockSeparator(result)
                        val start = result.length
                        result.append(block.text)
                        applyInlineSpans(result, block.spans, start, block.text.length)
                        if (block.text.isNotEmpty()) {
                            val factor = when (block.level) {
                                1 -> 1.55f
                                2 -> 1.38f
                                3 -> 1.22f
                                4 -> 1.12f
                                else -> 1.06f
                            }
                            result.setSpan(RelativeSizeSpan(factor), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            result.setSpan(StyleSpan(Typeface.BOLD), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    is Block.Quote -> {
                        if (result.isNotEmpty()) result.append("\n\n")
                        val start = result.length
                        result.append(render(block.children))
                        if (result.length > start) {
                            result.setSpan(LeadingMarginSpan.Standard(24, 24), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            result.setSpan(StyleSpan(Typeface.ITALIC), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    is Block.ListItem -> {
                        appendBlockSeparator(result)
                        val start = result.length
                        result.append(if (block.ordered) "${block.index}. " else "• ")
                        result.append(render(block.children))
                        if (result.length > start) {
                            result.setSpan(LeadingMarginSpan.Standard(18, 18), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    is Block.CodeBlock -> {
                        if (result.isNotEmpty()) result.append("\n\n")
                        val start = result.length
                        result.append(block.text)
                        if (result.length > start) {
                            result.setSpan(TypefaceSpan("monospace"), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            result.setSpan(BackgroundColorSpan(0x1F808080), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    is Block.Image -> {
                        if (result.isNotEmpty()) result.append("\n\n")
                        val alt = block.alt?.takeIf { it.isNotBlank() } ?: "image"
                        val start = result.length
                        result.append("[ $alt ]")
                        result.setSpan(StyleSpan(Typeface.ITALIC), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(ForegroundColorSpan(0xFF757575.toInt()), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        result.setSpan(RelativeSizeSpan(0.92f), start, result.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    Block.Divider -> {
                        if (result.isNotEmpty()) result.append("\n\n")
                        result.append("———")
                    }
                }
            }
            return result
        }

        return render(blocks)
    }

    private class ParagraphSpacingSpan(
        private val spacingPx: Int,
    ) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence,
            start: Int,
            end: Int,
            spanstartv: Int,
            v: Int,
            fm: android.graphics.Paint.FontMetricsInt,
        ) {
            fm.descent += spacingPx
            fm.bottom += spacingPx
        }
    }
}
