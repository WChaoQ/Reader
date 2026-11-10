package com.reader.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * Full-size software canvas for engine page drawing.
 *
 * [revision] forces a redraw when the engine finishes an async render (PDF bitmap,
 * reflow layout). Without it Compose may keep a blank first frame forever.
 */
@Composable
fun PageCanvas(
    background: Color,
    modifier: Modifier = Modifier,
    revision: Int = 0,
    onDrawPage: (android.graphics.Canvas) -> Unit,
) {
    key(revision) {
        Box(modifier = modifier.background(background)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                drawIntoCanvas { composeCanvas ->
                    val native = composeCanvas.nativeCanvas
                    onDrawPage(native)
                }
            }
        }
    }
}
