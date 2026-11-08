package com.reader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
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
            if (state.chromeVisible) {
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
                            viewModel.onEvent(ReaderEvent.ForceSaveProgress)
                            onBack()
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
                    },
                )
            }
        },
        bottomBar = {
            if (state.chromeVisible &&
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
                else -> BoxWithConstraints(
                    modifier = Modifier.fillMaxSize().background(stageBackground),
                ) {
                    // Preview mode is visual only: pagination always uses the full viewport.
                    LaunchedEffect(maxWidth, maxHeight) {
                        val w = with(density) { maxWidth.roundToPx() }
                        val h = with(density) { maxHeight.roundToPx() }
                        viewModel.onViewportSized(w, h)
                    }
                    val previewWidth = maxWidth * 0.72f
                    val previewHeight = maxHeight * 0.72f
                    val animatedWidth by animateDpAsState(
                        targetValue = if (state.adjacentPagesVisible) previewWidth else maxWidth,
                        label = "reader-page-width",
                    )
                    val animatedHeight by animateDpAsState(
                        targetValue = if (state.adjacentPagesVisible) previewHeight else maxHeight,
                        label = "reader-page-height",
                    )
                    val current = state.currentPage
                    val total = state.pageCount.available
                    val neighbourOffset = with(density) { animatedWidth.toPx() * 0.68f + 12.dp.toPx() }
                    Box(
                        modifier = Modifier.fillMaxSize().then(
                            if (state.adjacentPagesVisible) {
                                Modifier.pointerInput(current, total) {
                                    var dragDistance = 0f
                                    detectDragGestures(
                                        onDragStart = { dragDistance = 0f },
                                        onDrag = { change, amount ->
                                            dragDistance += amount.x
                                            change.consume()
                                        },
                                        onDragEnd = {
                                            val threshold = size.width * 0.12f
                                            when {
                                                dragDistance <= -threshold -> viewModel.onEvent(ReaderEvent.TapRight)
                                                dragDistance >= threshold -> viewModel.onEvent(ReaderEvent.TapLeft)
                                            }
                                        },
                                        onDragCancel = { dragDistance = 0f },
                                    )
                                }
                            } else {
                                Modifier.pointerInput(current, total) {
                                    detectTapGestures { point ->
                                        when {
                                            point.y < size.height * 0.10f || point.y > size.height * 0.90f ->
                                                viewModel.onEvent(ReaderEvent.ToggleChrome)
                                            point.x < size.width * 0.30f -> viewModel.onEvent(ReaderEvent.TapLeft)
                                            point.x > size.width * 0.70f -> viewModel.onEvent(ReaderEvent.TapRight)
                                            else -> viewModel.onEvent(ReaderEvent.TapCenter)
                                        }
                                    }
                                }
                            },
                        ),
                    ) {
                        if (state.adjacentPagesVisible && current > 0) {
                            ReaderPageCard(
                                page = current - 1, width = animatedWidth, height = animatedHeight,
                                background = pageBackground, showFrame = true,
                                modifier = Modifier.align(Alignment.Center)
                                    .offset { IntOffset(-neighbourOffset.roundToInt(), 0) }
                                    .graphicsLayer { alpha = 0.88f; scaleX = 0.98f; scaleY = 0.98f }
                                    .clickable { viewModel.onEvent(ReaderEvent.SelectPage(current - 1)) },
                                drawPage = viewModel::drawPage,
                            )
                        }
                        if (state.adjacentPagesVisible && current + 1 < total) {
                            ReaderPageCard(
                                page = current + 1, width = animatedWidth, height = animatedHeight,
                                background = pageBackground, showFrame = true,
                                modifier = Modifier.align(Alignment.Center)
                                    .offset { IntOffset(neighbourOffset.roundToInt(), 0) }
                                    .graphicsLayer { alpha = 0.88f; scaleX = 0.98f; scaleY = 0.98f }
                                    .clickable { viewModel.onEvent(ReaderEvent.SelectPage(current + 1)) },
                                drawPage = viewModel::drawPage,
                            )
                        }
                        ReaderPageCard(
                            page = current, width = animatedWidth, height = animatedHeight,
                            background = pageBackground, showFrame = state.adjacentPagesVisible,
                            modifier = Modifier.align(Alignment.Center).then(
                                if (state.adjacentPagesVisible) Modifier.clickable {
                                    viewModel.onEvent(ReaderEvent.SelectPage(current))
                                } else Modifier,
                            ),
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
    showFrame: Boolean = false,
    drawPage: (Int, android.graphics.Canvas) -> Unit,
) {
    Surface(
        modifier = modifier
            .width(width)
            .height(height)
            .then(
                if (showFrame) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                else Modifier,
            ),
        color = background,
        tonalElevation = 0.dp,
        shadowElevation = if (showFrame) 2.dp else 0.dp,
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
