package com.reader.engine.layout

import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Single StaticLayout construction path shared by pagination and rendering so that
 * measured line breaks and drawn page slices always stay aligned.
 */
object StaticLayoutFactory {

    fun create(
        text: CharSequence,
        paint: TextPaint,
        config: LayoutConfig,
    ): StaticLayout {
        val contentWidth = (config.pageWidthPx - 2 * config.paddingHorizontalPx).coerceAtLeast(1)
        val builder = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, config.lineSpacingMult.coerceAtLeast(0f))
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setJustificationMode(
                if (config.justify) {
                    Layout.JUSTIFICATION_MODE_INTER_WORD
                } else {
                    Layout.JUSTIFICATION_MODE_NONE
                },
            )

        // Fallback fonts can have taller glyph metrics than the primary font.
        // Include those metrics so scripts such as Burmese and Tibetan are not clipped.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUseLineSpacingFromFallbacks(true)
        }

        return builder.build()
    }

    fun createPaint(config: LayoutConfig): TextPaint =
        TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = config.textSizePx.coerceAtLeast(1f)
        }
}
