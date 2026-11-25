package com.tgdrive.feature.browser

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
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
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PhotosTimelineScreen(
    onOpenImage: (Long, Long) -> Unit,
    onOpenVideo: (Long, Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTimelineImage: (chatId: Long, msgId: Long, albumId: String?) -> Unit = { chatId, msgId, _ ->
        onOpenImage(chatId, msgId)
    },
    vm: PhotosTimelineViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.trash_undo)
    val selectionMode = state.selectedIds.isNotEmpty()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    var showMonthJump by remember { mutableStateOf(false) }
    var showAddToAlbumDialog by remember { mutableStateOf(false) }
    var showCreateAlbumDialog by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }
    var showSelectionMenu by remember { mutableStateOf(false) }
    var activeMemory by remember { mutableStateOf<MemoryHighlight?>(null) }

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

    val openedAlbum = state.activeAlbum
    val openedPlace = state.activePlace
    BackHandler(enabled = selectionMode || openedAlbum != null || openedPlace != null) {
        when {
            selectionMode -> vm.clearSelection()
            openedAlbum != null -> vm.openAlbum(null)
            openedPlace != null -> vm.openPlace(null)
        }
    }

    val showMemories = state.viewMode == PhotosViewMode.TIMELINE &&
        state.memories.isNotEmpty() &&
        state.filter == MediaFilter.ALL
    val sectionAnchorList = remember(state.sections, showMemories) {
        val anchors = mutableListOf<Pair<Int, String>>()
        var idx = if (showMemories) 1 else 0
        for (section in state.sections) {
            anchors += idx to section.monthLabel
            idx += 1 + section.items.size
        }
        anchors
    }
    val anchorBySectionKey = remember(state.sections, showMemories) {
        val map = LinkedHashMap<String, Int>()
        var idx = if (showMemories) 1 else 0
        for (section in state.sections) {
            map[section.key] = idx
            idx += 1 + section.items.size
        }
        map
    }

    var scrubberDragging by remember { mutableStateOf(false) }
    var scrubberMonthLabel by remember { mutableStateOf("") }
    var scrubberFraction by remember { mutableFloatStateOf(0f) }
    var gridHeightPx by remember { mutableIntStateOf(1) }

    LaunchedEffect(gridState.firstVisibleItemIndex, sectionAnchorList, scrubberDragging) {
        if (scrubberDragging || sectionAnchorList.size < 2) return@LaunchedEffect
        val visible = gridState.firstVisibleItemIndex
        val pos = sectionAnchorList.indexOfLast { it.first <= visible }.coerceAtLeast(0)
        scrubberFraction = (pos.toFloat() / sectionAnchorList.lastIndex.toFloat()).coerceIn(0f, 1f)
    }

    val openImageForCurrentView: (Long, Long) -> Unit = { chatId, msgId ->
        val customAlbumId = openedAlbum?.takeIf { it.isCustom }?.id
        onOpenTimelineImage(chatId, msgId, customAlbumId)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            when {
                selectionMode -> {
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
                            IconButton(onClick = { showAddToAlbumDialog = true }) {
                                Icon(
                                    Icons.Filled.CollectionsBookmark,
                                    contentDescription = stringResource(R.string.photos_add_to_album),
                                )
                            }
                            IconButton(onClick = vm::saveSelectedToDownloads) {
                                Icon(
                                    Icons.Filled.Download,
                                    contentDescription = stringResource(R.string.photos_action_save),
                                )
                            }
                            IconButton(onClick = { vm.shareSelected(context) }) {
                                Icon(
                                    Icons.Filled.Share,
                                    contentDescription = stringResource(R.string.photos_action_share),
                                )
                            }
                            Box {
                                IconButton(onClick = { showSelectionMenu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                                }
                                DropdownMenu(
                                    expanded = showSelectionMenu,
                                    onDismissRequest = { showSelectionMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.details_pin_offline)) },
                                        leadingIcon = { Icon(Icons.Filled.OfflinePin, contentDescription = null) },
                                        onClick = {
                                            showSelectionMenu = false
                                            vm.pinSelectedOffline()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.photos_action_move)) },
                                        onClick = {
                                            showSelectionMenu = false
                                            showMovePicker = true
                                        },
                                    )
                                    if (openedAlbum != null && openedAlbum.isCustom) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.photos_remove_from_album)) },
                                            onClick = {
                                                showSelectionMenu = false
                                                vm.removeSelectedFromAlbum(openedAlbum.id)
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.browser_action_delete),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            showSelectionMenu = false
                                            vm.trashSelected()
                                        },
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
                openedAlbum != null || openedPlace != null -> {
                    val title = openedAlbum?.title ?: openedPlace?.title.orEmpty()
                    TopAppBar(
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        title = {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    if (openedAlbum != null) vm.openAlbum(null) else vm.openPlace(null)
                                },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.browser_back))
                            }
                        },
                        actions = {
                            if (openedPlace != null) {
                                IconButton(
                                    onClick = {
                                        runCatching {
                                            val uri = Uri.parse(
                                                "geo:${openedPlace.lat},${openedPlace.lng}?q=${openedPlace.lat},${openedPlace.lng}",
                                            )
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                    },
                                ) {
                                    Icon(Icons.Filled.Map, contentDescription = stringResource(R.string.photos_open_in_maps))
                                }
                            }
                            if (openedAlbum != null && openedAlbum.isCustom) {
                                IconButton(onClick = { vm.deleteCustomAlbum(openedAlbum.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.photos_delete_album),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    vm.cycleColumns()
                                },
                            ) {
                                Icon(Icons.Filled.GridView, contentDescription = stringResource(R.string.photos_density))
                            }
                        },
                    )
                }
                else -> {
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
                        IconButton(onClick = vm::scanSimilarShots) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = stringResource(R.string.photos_similar_title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.cycleColumns()
                            },
                        ) {
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
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (openedAlbum == null && openedPlace == null) {
                PhotosSegmentedTabs(
                    activeMode = state.viewMode,
                    onSelectMode = vm::setViewMode,
                )
            }

            when {
                openedAlbum != null -> {
                    SimpleMediaGrid(
                        items = openedAlbum.items,
                        columns = state.columns,
                        selectedIds = state.selectedIds,
                        videoDurations = state.videoDurations,
                        onRequestVideoDuration = vm::ensureVideoDuration,
                        onItemClick = { item ->
                            if (selectionMode) {
                                vm.toggleSelect(item.chatId, item.messageId)
                            } else {
                                vm.openMedia(item, openImageForCurrentView, onOpenVideo)
                            }
                        },
                        onItemLongClick = { item ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.toggleSelect(item.chatId, item.messageId)
                        },
                    )
                }
                openedPlace != null -> {
                    SimpleMediaGrid(
                        items = openedPlace.items,
                        columns = state.columns,
                        selectedIds = state.selectedIds,
                        videoDurations = state.videoDurations,
                        onRequestVideoDuration = vm::ensureVideoDuration,
                        onItemClick = { item ->
                            if (selectionMode) {
                                vm.toggleSelect(item.chatId, item.messageId)
                            } else {
                                vm.openMedia(item, openImageForCurrentView, onOpenVideo)
                            }
                        },
                        onItemLongClick = { item ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.toggleSelect(item.chatId, item.messageId)
                        },
                    )
                }
                state.viewMode == PhotosViewMode.ALBUMS -> {
                    AlbumsGridView(
                        albums = state.albums,
                        onOpenAlbum = { vm.openAlbum(it) },
                        onCreateAlbum = { showCreateAlbumDialog = true },
                    )
                }
                state.viewMode == PhotosViewMode.PLACES -> {
                    PlacesGridView(
                        places = state.places,
                        scanningExif = state.scanningExif,
                        onScanExif = vm::scanLocalExifLocations,
                        onOpenPlace = { vm.openPlace(it) },
                    )
                }
                else -> {
                    // TIMELINE mode
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        if (state.sections.size >= 2) {
                            item(key = "jump_month") {
                                FilterChip(
                                    selected = false,
                                    onClick = { showMonthJump = true },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.CalendarMonth,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    label = { Text(stringResource(R.string.photos_jump_month_btn)) },
                                    shape = TgShapes.Pill,
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        labelColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    border = null,
                                )
                            }
                        }
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

                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = vm::refresh,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
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
                                    .fillMaxSize()
                                    .onSizeChanged { gridHeightPx = it.height.coerceAtLeast(1) }
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
                                    .pointerInput(selectionMode, state.columns) {
                                        if (selectionMode) {
                                            detectDragGesturesAfterLongPress { change, _ ->
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
                                                onOpenMemory = { mem -> activeMemory = mem },
                                            )
                                        }
                                    }

                                    for (section in state.sections) {
                                        item(
                                            span = { GridItemSpan(maxLineSpan) },
                                            key = "header_${section.key}",
                                        ) {
                                            val allInDaySelected = section.items.isNotEmpty() &&
                                                section.items.all { (it.chatId to it.messageId) in state.selectedIds }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = "${section.label} · ${section.items.size}",
                                                    style = TgTypography.Display.titleSmall,
                                                    color = MaterialTheme.colorScheme.onBackground,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                TextButton(
                                                    onClick = {
                                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        vm.selectSection(section)
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                ) {
                                                    Text(
                                                        text = if (allInDaySelected) {
                                                            stringResource(R.string.photos_deselect_day)
                                                        } else {
                                                            stringResource(R.string.photos_select_day)
                                                        },
                                                        style = TgTypography.Caption,
                                                    )
                                                }
                                            }
                                        }

                                        items(
                                            items = section.items,
                                            key = { "media_${it.chatId}_${it.messageId}" },
                                        ) { item ->
                                            val key = item.chatId to item.messageId
                                            val isSelected = key in state.selectedIds
                                            val isVideo = classifyFile(item.mimeType, item.fileName) == FileBucket.VIDEOS
                                            if (isVideo) {
                                                LaunchedEffect(key) {
                                                    vm.ensureVideoDuration(item)
                                                }
                                            }
                                            MediaGridTile(
                                                item = item,
                                                isSelected = isSelected,
                                                durationLabel = state.videoDurations[key],
                                                onClick = {
                                                    if (selectionMode) {
                                                        vm.toggleSelect(item.chatId, item.messageId)
                                                    } else {
                                                        vm.openMedia(item, openImageForCurrentView, onOpenVideo)
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
                                                        y = (
                                                            scrubberFraction * (gridHeightPx - 48.dp.roundToPx())
                                                                .coerceAtLeast(1)
                                                            ).roundToInt(),
                                                    )
                                                }
                                                .width(6.dp)
                                                .height(36.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(
                                                    MaterialTheme.colorScheme.primary.copy(
                                                        alpha = if (scrubberDragging) 0.9f else 0.45f,
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
                                                        y = (
                                                            scrubberFraction * (gridHeightPx - 56.dp.roundToPx())
                                                                .coerceAtLeast(1)
                                                            ).roundToInt(),
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
        }
    }

    if (showMonthJump) {
        MonthJumpSheet(
            sections = state.sections,
            anchorBySectionKey = anchorBySectionKey,
            onSelectSectionAnchor = { anchor ->
                scope.launch { gridState.scrollToItem(anchor) }
            },
            onDismiss = { showMonthJump = false },
        )
    }

    if (showAddToAlbumDialog) {
        AddToAlbumDialog(
            customAlbums = state.customAlbums,
            onPickAlbum = { albumId ->
                showAddToAlbumDialog = false
                vm.addSelectedToAlbum(albumId)
            },
            onCreateAndAdd = { name ->
                showAddToAlbumDialog = false
                vm.createAlbum(name, addSelection = true)
            },
            onDismiss = { showAddToAlbumDialog = false },
        )
    }

    if (showCreateAlbumDialog) {
        CreateAlbumDialog(
            onCreate = { name ->
                showCreateAlbumDialog = false
                vm.createAlbum(name, addSelection = false)
            },
            onDismiss = { showCreateAlbumDialog = false },
        )
    }

    if (showMovePicker) {
        MoveTargetPickerDialog(
            excludeChatId = null,
            loadFolders = { vm.listFoldersForMove() },
            onPick = { folder ->
                showMovePicker = false
                vm.moveSelectedToFolder(folder.chatId)
            },
            onDismiss = { showMovePicker = false },
        )
    }

    activeMemory?.let { mem ->
        MemoryStoryViewer(
            memory = mem,
            onOpenMedia = { lead -> vm.openMedia(lead, openImageForCurrentView, onOpenVideo) },
            onSaveAsAlbum = { vm.saveMemoryAsAlbum(mem) },
            onDismiss = { activeMemory = null },
        )
    }

    if (state.similarScanRunning || state.similarGroups != null) {
        SimilarShotsDialog(
            scanning = state.similarScanRunning,
            groups = state.similarGroups,
            onTrashDuplicates = vm::trashSimilarDuplicates,
            onDismiss = vm::dismissSimilarShots,
        )
    }
}

@Composable
private fun PhotosSegmentedTabs(
    activeMode: PhotosViewMode,
    onSelectMode: (PhotosViewMode) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PhotosViewMode.entries.forEach { mode ->
                val selected = activeMode == mode
                val label = when (mode) {
                    PhotosViewMode.TIMELINE -> stringResource(R.string.photos_tab_timeline)
                    PhotosViewMode.ALBUMS -> stringResource(R.string.photos_tab_albums)
                    PhotosViewMode.PLACES -> stringResource(R.string.photos_tab_places)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        )
                        .clickable { onSelectMode(mode) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = TgTypography.Display.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpleMediaGrid(
    items: List<FileCacheEntity>,
    columns: Int,
    selectedIds: Set<Pair<Long, Long>>,
    videoDurations: Map<Pair<Long, Long>, String>,
    onRequestVideoDuration: (FileCacheEntity) -> Unit,
    onItemClick: (FileCacheEntity) -> Unit,
    onItemLongClick: (FileCacheEntity) -> Unit,
) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EditorialEmptyState(
                icon = Icons.Filled.PhotoLibrary,
                title = stringResource(R.string.photos_empty_title),
                description = stringResource(R.string.photos_empty_desc),
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { "sub_${it.chatId}_${it.messageId}" }) { item ->
            val key = item.chatId to item.messageId
            val isVideo = classifyFile(item.mimeType, item.fileName) == FileBucket.VIDEOS
            if (isVideo) {
                LaunchedEffect(key) { onRequestVideoDuration(item) }
            }
            MediaGridTile(
                item = item,
                isSelected = key in selectedIds,
                durationLabel = videoDurations[key],
                onClick = { onItemClick(item) },
                onLongClick = { onItemLongClick(item) },
            )
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
                val leadThumbFile = remember(lead?.thumbnailPath) {
                    lead?.thumbnailPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
                }
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(172.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { onOpenMemory(mem) },
                ) {
                    if (leadThumbFile != null) {
                        AsyncImage(
                            model = leadThumbFile,
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
    durationLabel: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val isVideo = classifyFile(item.mimeType, item.fileName) == FileBucket.VIDEOS
    val thumbFile = remember(item.thumbnailPath) {
        item.thumbnailPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
    }
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
        if (thumbFile != null) {
            AsyncImage(
                model = thumbFile,
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
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = if (durationLabel != null) 6.dp else 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
                if (!durationLabel.isNullOrBlank()) {
                    Text(
                        text = durationLabel,
                        fontSize = 10.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                    )
                }
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
