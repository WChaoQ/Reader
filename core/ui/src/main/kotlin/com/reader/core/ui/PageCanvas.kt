package com.reader.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier.Modifier

/** Placeholder for page rendering canvas. Full implementation in engine:render. */
@Composable
fun PageCanvas(
    modifier: Modifier = Modifier,
    onDraw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {},
) {
    Canvas(modifier = modifier, onDraw = onDraw)
}
