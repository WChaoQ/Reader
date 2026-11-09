package com.reader.engine.layout

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
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, config.lineSpacingMult)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    fun createPaint(config: LayoutConfig): TextPaint =
        TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = config.textSizePx
        }
}
