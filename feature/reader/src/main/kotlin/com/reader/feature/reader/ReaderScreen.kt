package com.reader.feature.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reader.core.ui.PageCanvas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderRoute(
    bookId: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current

    val bg = when (state.theme) {
        "night" -> Color(0xFF121212)
        "sepia" -> Color(0xFFF5E6C8)
        else -> Color(0xFFFAFAFA)
    }

    Scaffold(
        topBar = {
            if (state.chromeVisible) {
                TopAppBar(
                    title = {
                        Text(
                            text = buildString {
                                append(state.title.ifBlank { "阅读" })
                                val pc = state.pageCount
                                if (pc.display > 0) {
                                    append("  ${state.currentPage + 1}/${pc.display}")
                                    if (!pc.isExact) append("~")
                                }
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            viewModel.onEvent(ReaderEvent.ForceSaveProgress)
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.error != null -> {
                    Text(
                        text = state.error!!,
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                state.isLoading || !state.pageReady -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        LaunchedEffect(maxWidth, maxHeight) {
                            val w = with(density) { maxWidth.roundToPx() }
                            val h = with(density) { maxHeight.roundToPx() }
                            viewModel.onViewportSized(w, h)
                        }
                        val page = state.currentPage
                        PageCanvas(
                            background = bg,
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(page) {
                                    detectTapGestures { offset ->
                                        val w = size.width
                                        when {
                                            offset.x < w * 0.3f ->
                                                viewModel.onEvent(ReaderEvent.TapLeft)
                                            offset.x > w * 0.7f ->
                                                viewModel.onEvent(ReaderEvent.TapRight)
                                            else ->
                                                viewModel.onEvent(ReaderEvent.TapCenter)
                                        }
                                    }
                                },
                        ) { canvas ->
                            viewModel.drawPage(page, canvas)
                        }
                    }
                }
            }
        }
    }
}
