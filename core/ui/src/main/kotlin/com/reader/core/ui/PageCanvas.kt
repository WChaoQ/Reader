package com.reader.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

@Composable
fun PageCanvas(
    background: Color,
    modifier: Modifier = Modifier,
    onDrawPage: (android.graphics.Canvas) -> Unit,
) {
    Box(modifier = modifier.background(background)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { composeCanvas ->
                val native = composeCanvas.nativeCanvas
                onDrawPage(native)
            }
        }
    }
}
