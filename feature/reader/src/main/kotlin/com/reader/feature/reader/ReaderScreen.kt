package com.reader.feature.reader

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reader.core.model.Locator
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
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            snackbar.showSnackbar(it)
            viewModel.onEvent(ReaderEvent.ClearSnackbar)
        }
    }

    val bg = when (state.theme) {
        "night" -> Color(0xFF121212)
        "sepia" -> Color(0xFFF5E6C8)
        else -> Color(0xFFFAFAFA)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null -> Text(
                    text = state.error!!,
                    modifier = Modifier.align(Alignment.Center),
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
                else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    LaunchedEffect(maxWidth, maxHeight) {
                        val w = with(density) { maxWidth.roundToPx() }
                        val h = with(density) { maxHeight.roundToPx() }
                        viewModel.onViewportSized(w, h)
                    }
                    val page = state.currentPage
                    PageCanvas(
                        background = bg,
                        modifier = Modifier.fillMaxSize().pointerInput(page) {
                            detectTapGestures { offset ->
                                val w = size.width
                                when {
                                    offset.x < w * 0.3f -> viewModel.onEvent(ReaderEvent.TapLeft)
                                    offset.x > w * 0.7f -> viewModel.onEvent(ReaderEvent.TapRight)
                                    else -> viewModel.onEvent(ReaderEvent.TapCenter)
                                }
                            }
                        },
                    ) { canvas -> viewModel.drawPage(page, canvas) }
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
                                viewModel.onEvent(ReaderEvent.JumpToLocator(Locator(bm.chapter, bm.offset)))
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bm.snippet.ifBlank { "第 ${bm.chapter + 1} 章" },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("章节 ${bm.chapter + 1}", style = MaterialTheme.typography.labelSmall,
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
private fun OverviewGrid(
    state: ReaderUiState,
    onPageClick: (Int) -> Unit,
    pagePreview: (Int) -> String,
) {
    val count = state.pageCount.display
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
