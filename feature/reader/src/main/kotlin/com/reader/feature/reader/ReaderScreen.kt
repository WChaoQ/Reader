package com.reader.feature.reader

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
            if (state.chromeVisible || state.mode == ReaderMode.Overview) {
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
                            if (state.mode == ReaderMode.Overview) {
                                viewModel.onEvent(ReaderEvent.ToggleOverview)
                            } else {
                                viewModel.onEvent(ReaderEvent.ForceSaveProgress)
                                onBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.onEvent(ReaderEvent.ToggleToc) }) {
                            Icon(Icons.Default.List, contentDescription = "目录")
                        }
                        IconButton(onClick = { viewModel.onEvent(ReaderEvent.ToggleOverview) }) {
                            Icon(Icons.Default.GridView, contentDescription = "总览")
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
                state.mode == ReaderMode.Overview -> {
                    OverviewGrid(
                        state = state,
                        onPageClick = { viewModel.onEvent(ReaderEvent.JumpToPage(it)) },
                        pagePreview = viewModel::pagePreview,
                    )
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

    if (state.tocVisible) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.onEvent(ReaderEvent.ToggleToc) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                text = "目录",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(state.toc, key = { index, _ -> index }) { _, entry ->
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.onEvent(ReaderEvent.JumpToLocator(entry.locator))
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewGrid(
    state: ReaderUiState,
    onPageClick: (Int) -> Unit,
    pagePreview: (Int) -> String,
) {
    val count = state.pageCount.display
    val pages = (0 until count).toList()
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.currentPage) {
        if (count > 0) {
            gridState.scrollToItem(state.currentPage.coerceIn(0, count - 1))
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(pages, key = { it }) { page ->
            val selected = page == state.currentPage
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (selected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CardDefaults.shape)
                        } else Modifier,
                    )
                    .clickable { onPageClick(page) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "${page + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = pagePreview(page).ifBlank { "…" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp).height(64.dp),
                    )
                }
            }
        }
    }
}
