package com.reader.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.key
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reader.core.ui.PageCanvas
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen reader with a horizontally scrollable page-preview mode.
 *
 * Immersion (browseProgress = 0): current page fills the screen, no chrome.
 * Browse (browseProgress = 1): a horizontally scrollable strip of uniformly sized
 * page previews appears, with the selected page initially centered.
 */
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
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            snackbar.showSnackbar(it)
            viewModel.onEvent(ReaderEvent.ClearSnackbar)
        }
    }

    // The page surface and the engine canvas use the same opaque colors.
    // Theme-to-render-style mapping belongs to the UI, not the ViewModel.
    val textColorArgb = when (state.theme) {
        "night" -> android.graphics.Color.rgb(224, 224, 224)
        "sepia" -> android.graphics.Color.rgb(91, 70, 54)
        else -> android.graphics.Color.rgb(26, 26, 26)
    }
    val backgroundColorArgb = when (state.theme) {
        "night" -> android.graphics.Color.rgb(18, 18, 18)
        "sepia" -> android.graphics.Color.rgb(245, 230, 200)
        else -> android.graphics.Color.rgb(250, 250, 250)
    }
    val pageBackground = Color(backgroundColorArgb)
    val stageBackground = when (state.theme) {
        "night" -> Color(0xFF121218)
        "sepia" -> Color(0xFFE8DDC5)
        else -> Color(0xFFEDEDF2)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(stageBackground),
    ) {
        when {
            state.error != null -> Text(
                text = state.error!!,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            state.isLoading || !state.pageReady -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
            else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Full-viewport page size so immersion is truly edge-to-edge.
                val pageWidth = maxWidth
                val pageHeight = maxHeight
                LaunchedEffect(pageWidth, pageHeight, density.density, density.fontScale) {
                    val w = with(density) { pageWidth.roundToPx() }
                    val h = with(density) { pageHeight.roundToPx() }
                    // StaticLayout uses physical pixels; convert the stored sp value using
                    // the same density and user font scale as the Compose viewport.
                    viewModel.onViewportSized(
                        widthPx = w,
                        heightPx = h,
                        fontScalePxPerSp = density.density * density.fontScale,
                    )
                }

                val total = state.pageCount.available
                val settledPage = state.currentPage

                val browseProgress = remember { Animatable(0f) }
                val progress = browseProgress.value
                val isBrowse = progress >= 0.5f
                val thumbnailWidth = pageWidth * 0.78f
                val thumbnailHeight = pageHeight * 0.78f
                val thumbnailListState = rememberLazyListState()

                // Keep the selected page centered whenever the thumbnail strip opens
                // or an external navigation action changes the current page.
                LaunchedEffect(isBrowse, settledPage, total, thumbnailWidth) {
                    if (isBrowse && total > 0) {
                        val centerOffset = with(density) {
                            -((pageWidth - thumbnailWidth) / 2).roundToPx()
                        }
                        thumbnailListState.animateScrollToItem(
                            index = settledPage.coerceIn(0, total - 1),
                            scrollOffset = centerOffset,
                        )
                    }
                }

                fun toggleBrowse() {
                    scope.launch {
                        val target = if (browseProgress.value < 0.5f) 1f else 0f
                        browseProgress.animateTo(
                            target,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = 380f,
                            ),
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (!isBrowse) {
                        key(settledPage, state.renderRevision) {
                            ReaderPageCard(
                                page = settledPage,
                                width = pageWidth,
                                height = pageHeight,
                                background = pageBackground,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(settledPage, total) {
                                        detectTapGestures { point ->
                                            when {
                                                point.x < size.width * 0.30f -> {
                                                    if (settledPage > 0) {
                                                        viewModel.onEvent(
                                                            ReaderEvent.JumpToPage(settledPage - 1)
                                                        )
                                                    }
                                                }
                                                point.x > size.width * 0.70f -> {
                                                    if (settledPage < total - 1) {
                                                        viewModel.onEvent(
                                                            ReaderEvent.JumpToPage(settledPage + 1)
                                                        )
                                                    }
                                                }
                                                else -> toggleBrowse()
                                            }
                                        }
                                    }
                                    .pointerInput(settledPage, total) {
                                        var dragDistance = 0f
                                        detectDragGestures(
                                            onDragStart = { dragDistance = 0f },
                                            onDrag = { change, amount ->
                                                dragDistance += amount.x
                                                change.consume()
                                            },
                                            onDragEnd = {
                                                val threshold = size.width * 0.15f
                                                when {
                                                    dragDistance < -threshold && settledPage < total - 1 ->
                                                        viewModel.onEvent(ReaderEvent.JumpToPage(settledPage + 1))
                                                    dragDistance > threshold && settledPage > 0 ->
                                                        viewModel.onEvent(ReaderEvent.JumpToPage(settledPage - 1))
                                                }
                                            },
                                        )
                                    },
                                drawPage = { page, canvas ->
                                    viewModel.drawPage(
                                        pageIndex = page,
                                        canvas = canvas,
                                        textColor = textColorArgb,
                                        backgroundColor = backgroundColorArgb,
                                    )
                                },
                            )
                        }
                    } else {
                        LazyRow(
                            state = thumbnailListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            items(
                                count = total,
                                key = { page -> page },
                            ) { page ->
                                key(page, state.renderRevision) {
                                    ReaderPageCard(
                                        page = page,
                                        width = thumbnailWidth,
                                        height = thumbnailHeight,
                                        background = pageBackground,
                                        modifier = Modifier.clickable {
                                            viewModel.onEvent(ReaderEvent.JumpToPage(page))
                                            scope.launch {
                                                browseProgress.animateTo(
                                                    0f,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                                        stiffness = 380f,
                                                    ),
                                                )
                                            }
                                        },
                                        drawPage = { pageIndex, canvas ->
                                            viewModel.drawPage(
                                                pageIndex = pageIndex,
                                                canvas = canvas,
                                                textColor = textColorArgb,
                                                backgroundColor = backgroundColorArgb,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Chrome driven by browseProgress (slide + fade).
                val chromeAlpha = progress
                val topOffsetPx = with(density) { ((1f - progress) * -64.dp.toPx()).roundToInt() }
                val bottomOffsetPx = with(density) { ((1f - progress) * 80.dp.toPx()).roundToInt() }

                if (chromeAlpha > 0.01f) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = chromeAlpha),
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .graphicsLayer { alpha = chromeAlpha }
                            .offset { IntOffset(0, topOffsetPx) }
                            .zIndex(20f),
                    ) {
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
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                            ),
                            modifier = Modifier.statusBarsPadding(),
                        )
                    }

                    if (state.pageCount.display > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = chromeAlpha),
                            tonalElevation = 2.dp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .graphicsLayer { alpha = chromeAlpha }
                                .offset { IntOffset(0, bottomOffsetPx) }
                                .zIndex(20f),
                        ) {
                            ReaderProgressBar(
                                currentPage = settledPage,
                                pageCount = state.pageCount.display,
                                availablePageCount = state.pageCount.available,
                                onPageSelected = { viewModel.onEvent(ReaderEvent.JumpToPage(it)) },
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        )
    }

    if (state.tocVisible) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.onEvent(ReaderEvent.ToggleToc) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                "目录",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.toc.isEmpty()) {
                Text("此书暂无目录", modifier = Modifier.padding(16.dp))
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(state.toc, key = { index, _ -> index }) { _, entry ->
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
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
            Text(
                "书签",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.bookmarks.isEmpty()) {
                Text(
                    "暂无书签，点击顶栏书签图标添加",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(state.bookmarks, key = { it.id }) { bm ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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
                            Text(
                                "章节 ${bm.locator.chapter + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
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
                Text(
                    "搜索",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.onEvent(ReaderEvent.Search(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("输入关键词") },
                )
                if (state.searching) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally))
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(state.searchHits, key = { "${it.locator.chapter}-${it.locator.offset}" }) { hit ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onEvent(ReaderEvent.JumpToLocator(hit.locator)) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            hit.chapterTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
            .height(height),
        color = background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
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
