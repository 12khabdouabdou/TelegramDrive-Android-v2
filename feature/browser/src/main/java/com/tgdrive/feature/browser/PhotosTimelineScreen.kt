package com.tgdrive.feature.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.designsystem.editorial.EditorialEmptyState
import com.tgdrive.designsystem.theme.TgShapes
import com.tgdrive.designsystem.theme.TgTypography
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhotosTimelineScreen(
    onOpenImage: (Long, Long) -> Unit,
    onOpenVideo: (Long, Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: PhotosTimelineViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.trash_undo)
    val selectionMode = state.selectedIds.isNotEmpty()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.snackbarEvent?.id) {
        val ev = state.snackbarEvent ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = ev.message,
            actionLabel = if (ev.canUndoTrash) undoLabel else null,
            duration = if (ev.canUndoTrash) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed && ev.canUndoTrash) {
            vm.undoLastTrash()
        }
        vm.clearSnackbar()
    }

    BackHandler(enabled = selectionMode) {
        vm.clearSelection()
    }

    val showMemories = state.memories.isNotEmpty() && state.filter == MediaFilter.ALL
    val sectionAnchorList = remember(state.sections, showMemories) {
        val anchors = mutableListOf<Pair<Int, String>>()
        var idx = if (showMemories) 1 else 0
        for (section in state.sections) {
            anchors += idx to section.monthLabel
            idx += 1 + section.items.size
        }
        anchors
    }

    var scrubberDragging by remember { mutableStateOf(false) }
    var scrubberMonthLabel by remember { mutableStateOf("") }
    var scrubberFraction by remember { mutableFloatStateOf(0f) }
    var gridHeightPx by remember { mutableIntStateOf(1) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        Text(
                            text = stringResource(R.string.photos_selected_count, state.selectedIds.size),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.browser_action_clear_selection),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::starSelected) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = stringResource(R.string.details_star),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = vm::pinSelectedOffline) {
                            Icon(
                                Icons.Filled.OfflinePin,
                                contentDescription = stringResource(R.string.details_pin_offline),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        IconButton(onClick = vm::trashSelected) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.browser_action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.photos_title),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.cycleColumns()
                    }) {
                        Icon(
                            Icons.Filled.GridView,
                            contentDescription = stringResource(R.string.photos_density),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.browser_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.cloudSyncing) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Icon(
                                    Icons.Filled.CloudDone,
                                    contentDescription = stringResource(R.string.home_sync_settings),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(MediaFilter.entries, key = { it.name }) { filter ->
                    val labelRes = when (filter) {
                        MediaFilter.ALL -> R.string.photos_filter_all
                        MediaFilter.PHOTOS -> R.string.photos_filter_photos
                        MediaFilter.VIDEOS -> R.string.photos_filter_videos
                        MediaFilter.FAVORITES -> R.string.photos_filter_favorites
                        MediaFilter.OFFLINE -> R.string.photos_filter_offline
                    }
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { vm.setFilter(filter) },
                        label = { Text(stringResource(labelRes)) },
                        shape = TgShapes.Pill,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        border = null,
                    )
                }
            }

            if (state.sections.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EditorialEmptyState(
                        icon = Icons.Filled.PhotoLibrary,
                        title = stringResource(R.string.photos_empty_title),
                        description = stringResource(R.string.photos_empty_desc),
                    )
                }
            } else {
                var currentCols by remember { mutableIntStateOf(state.columns) }
                LaunchedEffect(state.columns) { currentCols = state.columns }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged { gridHeightPx = it.height.coerceAtLeast(1) }
                        // Pinch-to-zoom between 2..5 columns
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var cumulativeZoom = 1f
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed >= 2) {
                                        cumulativeZoom *= event.calculateZoom()
                                        if (cumulativeZoom >= 1.25f) {
                                            val next = (currentCols - 1).coerceAtLeast(2)
                                            if (next != currentCols) {
                                                currentCols = next
                                                vm.setColumns(next)
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            cumulativeZoom = 1f
                                        } else if (cumulativeZoom <= 0.78f) {
                                            val next = (currentCols + 1).coerceAtMost(5)
                                            if (next != currentCols) {
                                                currentCols = next
                                                vm.setColumns(next)
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            cumulativeZoom = 1f
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        // Drag-to-select across grid tiles while in selection mode
                        .pointerInput(selectionMode, state.columns) {
                            if (selectionMode) {
                                detectDragGestures { change, _ ->
                                    val pos = change.position
                                    val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                        val k = info.key as? String
                                        k != null && k.startsWith("media_") &&
                                            pos.x >= info.offset.x &&
                                            pos.x <= info.offset.x + info.size.width &&
                                            pos.y >= info.offset.y &&
                                            pos.y <= info.offset.y + info.size.height
                                    }
                                    val keyStr = hit?.key as? String
                                    if (keyStr != null) {
                                        val raw = keyStr.removePrefix("media_")
                                        val splitIdx = raw.lastIndexOf('_')
                                        if (splitIdx > 0) {
                                            val cId = raw.substring(0, splitIdx).toLongOrNull()
                                            val mId = raw.substring(splitIdx + 1).toLongOrNull()
                                            if (cId != null && mId != null) {
                                                vm.selectItem(cId, mId)
                                            }
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        },
                ) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(state.columns),
                        contentPadding = PaddingValues(
                            start = 4.dp,
                            end = 4.dp,
                            top = 4.dp,
                            bottom = 24.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (showMemories) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "memories_strip") {
                                MemoriesStrip(
                                    memories = state.memories,
                                    onOpenMemory = { mem ->
                                        mem.items.firstOrNull()?.let { lead ->
                                            vm.openMedia(lead, onOpenImage, onOpenVideo)
                                        }
                                    },
                                )
                            }
                        }

                        for (section in state.sections) {
                            item(
                                span = { GridItemSpan(maxLineSpan) },
                                key = "header_${section.key}",
                            ) {
                                Text(
                                    text = section.label,
                                    style = TgTypography.Display.titleSmall,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                )
                            }

                            items(
                                items = section.items,
                                key = { "media_${it.chatId}_${it.messageId}" },
                            ) { item ->
                                val isSelected = (item.chatId to item.messageId) in state.selectedIds
                                MediaGridTile(
                                    item = item,
                                    isSelected = isSelected,
                                    onClick = {
                                        if (selectionMode) {
                                            vm.toggleSelect(item.chatId, item.messageId)
                                        } else {
                                            vm.openMedia(item, onOpenImage, onOpenVideo)
                                        }
                                    },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        vm.toggleSelect(item.chatId, item.messageId)
                                    },
                                )
                            }
                        }
                    }

                    // Right-edge fast month scrubber when timeline has 3+ date groups
                    if (sectionAnchorList.size >= 3) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(28.dp)
                                .pointerInput(sectionAnchorList, gridHeightPx) {
                                    detectVerticalDragGestures(
                                        onDragStart = { offset ->
                                            scrubberDragging = true
                                            val f = (offset.y / gridHeightPx.toFloat()).coerceIn(0f, 1f)
                                            scrubberFraction = f
                                            val idx = (f * sectionAnchorList.lastIndex).roundToInt()
                                                .coerceIn(0, sectionAnchorList.lastIndex)
                                            val anchor = sectionAnchorList[idx]
                                            scrubberMonthLabel = anchor.second
                                            scope.launch { gridState.scrollToItem(anchor.first) }
                                        },
                                        onVerticalDrag = { change, _ ->
                                            val f = (change.position.y / gridHeightPx.toFloat()).coerceIn(0f, 1f)
                                            scrubberFraction = f
                                            val idx = (f * sectionAnchorList.lastIndex).roundToInt()
                                                .coerceIn(0, sectionAnchorList.lastIndex)
                                            val anchor = sectionAnchorList[idx]
                                            scrubberMonthLabel = anchor.second
                                            scope.launch { gridState.scrollToItem(anchor.first) }
                                            change.consume()
                                        },
                                        onDragEnd = { scrubberDragging = false },
                                        onDragCancel = { scrubberDragging = false },
                                    )
                                },
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset {
                                        IntOffset(
                                            x = (-4).dp.roundToPx(),
                                            y = (scrubberFraction * (gridHeightPx - 48.dp.roundToPx()).coerceAtLeast(1)).roundToInt(),
                                        )
                                    }
                                    .width(6.dp)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(
                                            alpha = if (scrubberDragging) 0.9f else 0.4f,
                                        ),
                                    ),
                            )
                        }

                        if (scrubberDragging && scrubberMonthLabel.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset {
                                        IntOffset(
                                            x = (-44).dp.roundToPx(),
                                            y = (scrubberFraction * (gridHeightPx - 56.dp.roundToPx()).coerceAtLeast(1)).roundToInt(),
                                        )
                                    }
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = scrubberMonthLabel,
                                    style = TgTypography.Display.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoriesStrip(
    memories: List<MemoryHighlight>,
    onOpenMemory: (MemoryHighlight) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.photos_memories_title),
            style = TgTypography.Display.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(memories, key = { it.title }) { mem ->
                val lead = mem.items.firstOrNull()
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(172.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onOpenMemory(mem) },
                ) {
                    if (lead?.thumbnailPath != null) {
                        AsyncImage(
                            model = lead.thumbnailPath,
                            contentDescription = mem.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Filled.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(36.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp),
                    ) {
                        Text(
                            text = mem.title,
                            style = TgTypography.Display.bodyMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.photos_memories_sub, mem.subtitle),
                            style = TgTypography.Caption,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGridTile(
    item: FileCacheEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isVideo = classifyFile(item.mimeType, item.fileName) == FileBucket.VIDEOS
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(if (isSelected) 12.dp else 4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (item.thumbnailPath != null) {
            AsyncImage(
                model = item.thumbnailPath,
                contentDescription = item.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (isSelected) 4.dp else 0.dp),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = item.fileName,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        if (item.isStarred) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .size(16.dp),
            )
        }

        if (item.isPinnedOffline) {
            Icon(
                Icons.Filled.OfflinePin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .size(16.dp),
            )
        }

        if (isSelected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}
