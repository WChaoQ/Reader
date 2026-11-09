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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reader.core.ui.PageCanvas
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Continuous-position reader.
 *
 * Immersion (browseProgress = 0): current page fills the screen, no chrome.
 * Browse (browseProgress = 1): strip scaled down, neighbors visible with gap,
 * top bar + progress bar slid in.
 *
 * pagePosition is a continuous float; scale, spacing and translation are all
 * derived from (pagePosition, browseProgress). Page index is just round(pagePosition).
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

                val pagePosition = remember { Animatable(settledPage.toFloat()) }
                val browseProgress = remember { Animatable(0f) }
                var isDragging by remember { mutableStateOf(false) }
                var dragPosition by remember { mutableFloatStateOf(settledPage.toFloat()) }
                // Suppress external jump animation while a local settle is in flight.
                var suppressExternalAnim by remember { mutableStateOf(false) }
                // Stable snapshot of settled page for gesture closures (updated every frame via state).
                var settledPageRef by remember { mutableIntStateOf(settledPage) }
                settledPageRef = settledPage

                // External jumps (slider / toc / bookmark / search) only.
                LaunchedEffect(settledPage) {
                    if (isDragging || suppressExternalAnim) {
                        suppressExternalAnim = false
                        return@LaunchedEffect
                    }
                    val target = settledPage.toFloat()
                    if (abs(pagePosition.value - target) > 0.001f) {
                        pagePosition.animateTo(
                            target,
                            animationSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = 420f,
                            ),
                        )
                    }
                }

                val visualPosition = if (isDragging) dragPosition else pagePosition.value
                val progress = browseProgress.value

                val pageWidthPx = with(density) { pageWidth.toPx() }
                // Browse scale ~72% so neighbors read clearly; gap between scaled pages.
                val browseScale = 0.72f
                val browseGapPx = with(density) { 16.dp.toPx() }
                val browseStepPx = pageWidthPx * browseScale + browseGapPx
                val pageStepPx = pageWidthPx + (browseStepPx - pageWidthPx) * progress
                val scale = 1f - (1f - browseScale) * progress

                fun settleTo(targetPage: Int, releasePos: Float) {
                    val clamped = targetPage.coerceIn(0, (total - 1).coerceAtLeast(0))
                    scope.launch {
                        suppressExternalAnim = true
                        pagePosition.snapTo(releasePos)
                        isDragging = false
                        if (clamped != settledPageRef) {
                            viewModel.onEvent(ReaderEvent.JumpToPage(clamped))
                        }
                        pagePosition.animateTo(
                            clamped.toFloat(),
                            animationSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = 420f,
                            ),
                        )
                    }
                }

                fun animateToPage(targetPage: Int) {
                    val clamped = targetPage.coerceIn(0, (total - 1).coerceAtLeast(0))
                    if (clamped == settledPageRef && abs(pagePosition.value - clamped.toFloat()) < 0.001f) {
                        return
                    }
                    scope.launch {
                        suppressExternalAnim = true
                        if (clamped != settledPageRef) {
                            viewModel.onEvent(ReaderEvent.JumpToPage(clamped))
                        }
                        pagePosition.animateTo(
                            clamped.toFloat(),
                            animationSpec = spring(
                                dampingRatio = 0.86f,
                                stiffness = 420f,
                            ),
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

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center)
                        // Only clip in immersion so neighbors cannot peek; browse needs overflow.
                        .graphicsLayer { clip = progress < 0.5f }
                        // Stable keys: do NOT restart gesture detector when progress/step animates.
                        .pointerInput(total) {
                            val velocityTracker = VelocityTracker()
                            var dragStartPosition = 0f
                            var totalDragX = 0f

                            detectDragGestures(
                                onDragStart = {
                                    // Use current visual position (may still be animating).
                                    val start = if (isDragging) dragPosition else pagePosition.value
                                    dragStartPosition = start
                                    dragPosition = start
                                    totalDragX = 0f
                                    isDragging = true
                                    velocityTracker.resetTracking()
                                    scope.launch { pagePosition.stop() }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    totalDragX += amount.x
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)

                                    // Step depends on current browse progress (read live).
                                    val step = pageWidthPx +
                                        (pageWidthPx * browseScale + browseGapPx - pageWidthPx) *
                                        browseProgress.value
                                    val safeStep = step.coerceAtLeast(1f)
                                    val raw = dragStartPosition - totalDragX / safeStep
                                    val lastPage = (total - 1).coerceAtLeast(0).toFloat()
                                    dragPosition = when {
                                        raw < 0f -> raw * 0.35f
                                        raw > lastPage -> lastPage + (raw - lastPage) * 0.35f
                                        else -> raw
                                    }
                                },
                                onDragEnd = {
                                    val step = pageWidthPx +
                                        (pageWidthPx * browseScale + browseGapPx - pageWidthPx) *
                                        browseProgress.value
                                    val safeStep = step.coerceAtLeast(1f)
                                    val velocityPages =
                                        -velocityTracker.calculateVelocity().x / safeStep
                                    val current = settledPageRef
                                    val lower = (current - 1).coerceAtLeast(0)
                                    val upper = (current + 1).coerceAtMost((total - 1).coerceAtLeast(0))
                                    val target = (dragPosition + velocityPages * 0.18f)
                                        .roundToInt()
                                        .coerceIn(lower, upper)
                                    settleTo(target, dragPosition)
                                },
                                onDragCancel = {
                                    settleTo(settledPageRef, dragPosition)
                                },
                            )
                        }
                        .pointerInput(total) {
                            detectTapGestures { point ->
                                if (isDragging) return@detectTapGestures
                                val inBrowse = browseProgress.value > 0.5f
                                val current = settledPageRef
                                if (!inBrowse) {
                                    // Immersion: left / right zones turn page; center enters browse.
                                    when {
                                        point.x < size.width * 0.30f -> {
                                            if (current > 0) animateToPage(current - 1)
                                        }
                                        point.x > size.width * 0.70f -> {
                                            if (current < total - 1) animateToPage(current + 1)
                                        }
                                        else -> toggleBrowse()
                                    }
                                    return@detectTapGestures
                                }
                                // Browse: center page → immersion; side pages → jump.
                                val centerX = size.width / 2f
                                val halfScaled = pageWidthPx * scale / 2f
                                val dx = point.x - centerX
                                when {
                                    abs(dx) <= halfScaled -> toggleBrowse()
                                    dx < 0 && current > 0 -> animateToPage(current - 1)
                                    dx > 0 && current < total - 1 -> animateToPage(current + 1)
                                    else -> toggleBrowse()
                                }
                            }
                        },
                ) {
                    if (total > 0) {
                        val first = (visualPosition - 2).toInt().coerceAtLeast(0)
                        val last = (visualPosition + 2).toInt().coerceAtMost(total - 1)
                        for (page in first..last) {
                            val relative = page - visualPosition
                            ReaderPageCard(
                                page = page,
                                width = pageWidth,
                                height = pageHeight,
                                background = pageBackground,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .graphicsLayer {
                                        translationX = relative * pageStepPx
                                        scaleX = scale
                                        scaleY = scale
                                        // Soft corner only while browsing; no hard border.
                                        shape = RoundedCornerShape(6.dp * progress)
                                        clip = progress > 0.01f
                                    }
                                    .zIndex(10f - abs(relative)),
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
