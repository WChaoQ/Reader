package com.reader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reader.core.ui.PageCanvas
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderRoute(
    bookId: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            snackbar.showSnackbar(it)
            viewModel.onEvent(ReaderEvent.ClearSnackbar)
        }
    }

    val pageBackground = when (state.theme) {
        "night" -> Color(0xFF000000)
        "sepia" -> Color(0xFFF5EBD5)
        else -> Color(0xFFFFFFFF)
    }
    val stageBackground = when (state.theme) {
        "night" -> Color(0xFF121218)
        "sepia" -> Color(0xFFE8DDC5)
        else -> Color(0xFFEDEDF2)
    }

    Scaffold(
        containerColor = stageBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.chromeVisible || state.mode == ReaderMode.Overview) {
                TopAppBar(
                    title = {
                        Text(
                            text = state.title.ifBlank { "阅读" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                        IconButton(onClick = { viewModel.onEvent(ReaderEvent.AddBookmark) }) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = "添加书签")
                        }
                        IconButton(onClick = { viewModel.onEvent(ReaderEvent.ToggleBookmarks) }) {
                            Icon(Icons.Default.Bookmark, contentDescription = "书签列表")
                        }
                        IconButton(onClick = { viewModel.onEvent(ReaderEvent.ToggleSearch) }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
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
        bottomBar = {
            if (state.chromeVisible && state.mode == ReaderMode.Reading &&
                state.pageCount.display > 0
            ) {
                ReaderProgressBar(
                    currentPage = state.currentPage,
                    pageCount = state.pageCount.display,
                    availablePageCount = state.pageCount.available,
                    onPageSelected = { viewModel.onEvent(ReaderEvent.JumpToPage(it)) },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null -> Text(
                    text = state.error!!,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                state.isLoading || !state.pageReady -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
                state.mode == ReaderMode.Overview -> OverviewGrid(
                    state = state,
                    onPageClick = { viewModel.onEvent(ReaderEvent.JumpToPage(it)) },
                    pagePreview = viewModel::pagePreview,
                )
                else -> BoxWithConstraints(
                    modifier = Modifier.fillMaxSize().background(stageBackground),
                ) {
                    val pageWidth = maxWidth * 0.79f
                    val pageHeight = maxHeight * 0.94f
                    LaunchedEffect(pageWidth, pageHeight) {
                        val w = with(density) { pageWidth.roundToPx() }
                        val h = with(density) { pageHeight.roundToPx() }
                        viewModel.onViewportSized(w, h)
                    }
                    val pageWidthPx = with(density) { pageWidth.toPx() }
                    val current = state.currentPage
                    val total = state.pageCount.available

                    // Three lightweight page surfaces create the Google Books / Overview
                    // spatial relationship. Only the current page and its immediate neighbours
                    // are drawn; no book-cover thumbnails or full-document snapshots are used.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(current, total) {
                                var dragDistance = 0f
                                detectDragGestures(
                                    onDragStart = { dragDistance = 0f },
                                    onDrag = { change, amount ->
                                        dragDistance += amount.x
                                        change.consume()
                                    },
                                    onDragEnd = {
                                        val threshold = size.width * 0.16f
                                        when {
                                            dragDistance <= -threshold -> viewModel.onEvent(ReaderEvent.TapRight)
                                            dragDistance >= threshold -> viewModel.onEvent(ReaderEvent.TapLeft)
                                        }
                                    },
                                    onDragCancel = { dragDistance = 0f },
                                )
                            }
                            .pointerInput(current, total) {
                                detectTapGestures { point ->
                                    when {
                                        point.x < size.width * 0.30f -> viewModel.onEvent(ReaderEvent.TapLeft)
                                        point.x > size.width * 0.70f -> viewModel.onEvent(ReaderEvent.TapRight)
                                        else -> viewModel.onEvent(ReaderEvent.TapCenter)
                                    }
                                }
                            },
                    ) {
                        if (current > 0) {
                            ReaderPageCard(
                                page = current - 1,
                                width = pageWidth,
                                height = pageHeight,
                                background = pageBackground,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset { IntOffset((-pageWidthPx - with(density) { 14.dp.toPx() }).roundToInt(), 0) }
                                    .graphicsLayer { alpha = 0.72f; scaleX = 0.96f; scaleY = 0.96f },
                                drawPage = viewModel::drawPage,
                            )
                        }
                        if (current + 1 < total) {
                            ReaderPageCard(
                                page = current + 1,
                                width = pageWidth,
                                height = pageHeight,
                                background = pageBackground,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset { IntOffset((pageWidthPx + with(density) { 14.dp.toPx() }).roundToInt(), 0) }
                                    .graphicsLayer { alpha = 0.72f; scaleX = 0.96f; scaleY = 0.96f },
                                drawPage = viewModel::drawPage,
                            )
                        }
                        ReaderPageCard(
                            page = current,
                            width = pageWidth,
                            height = pageHeight,
                            background = pageBackground,
                            modifier = Modifier.align(Alignment.Center),
                            drawPage = viewModel::drawPage,
                        )
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
            Text("目录", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (state.toc.isEmpty()) {
                Text("此书暂无目录", modifier = Modifier.padding(16.dp))
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(state.toc, key = { index, _ -> index }) { _, entry ->
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { viewModel.onEvent(ReaderEvent.JumpToLocator(entry.locator)) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    if (state.bookmarksVisible) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.onEvent(ReaderEvent.ToggleBookmarks) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text("书签", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (state.bookmarks.isEmpty()) {
                Text("暂无书签，点击顶栏书签图标添加",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(state.bookmarks, key = { it.id }) { bm ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                viewModel.onEvent(ReaderEvent.JumpToLocator(bm.locator))
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bm.snippet.ifBlank { "第 ${bm.locator.chapter + 1} 章" },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("章节 ${bm.locator.chapter + 1}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { viewModel.onEvent(ReaderEvent.DeleteBookmark(bm.id)) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
        }
    }

    if (state.searchVisible) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.onEvent(ReaderEvent.ToggleSearch) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("搜索", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.onEvent(ReaderEvent.Search(it)) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    singleLine = true,
                    placeholder = { Text("输入关键词") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                )
                if (state.searching) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(state.searchHits.size) { index ->
                    val hit = state.searchHits[index]
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { viewModel.onEvent(ReaderEvent.JumpToLocator(hit.locator)) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(hit.chapterTitle, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(hit.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderPageCard(
    page: Int,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    background: Color,
    modifier: Modifier = Modifier,
    drawPage: (Int, android.graphics.Canvas) -> Unit,
) {
    Surface(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
        color = background,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
    ) {
        PageCanvas(background = background, modifier = Modifier.fillMaxSize()) { canvas ->
            drawPage(page, canvas)
        }
    }
}

@Composable
private fun ReaderProgressBar(
    currentPage: Int,
    pageCount: Int,
    availablePageCount: Int,
    onPageSelected: (Int) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Slider(
                value = currentPage.toFloat(),
                onValueChange = { onPageSelected(it.roundToInt()) },
                valueRange = 0f..(availablePageCount - 1).coerceAtLeast(1).toFloat(),
                enabled = availablePageCount > 1,
                steps = 0,
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("第 ${currentPage + 1} 页", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${currentPage + 1} / $pageCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    val count = state.pageCount.available
    val pages = (0 until count).toList()
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.currentPage) {
        if (count > 0) gridState.scrollToItem(state.currentPage.coerceIn(0, count - 1))
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
                modifier = Modifier.fillMaxWidth()
                    .then(
                        if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CardDefaults.shape)
                        else Modifier,
                    )
                    .clickable { onPageClick(page) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("${page + 1}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
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
