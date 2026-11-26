package com.tgdrive.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.isTextPreviewable
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.common.toLocalShort
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.FileFilter
import com.tgdrive.core.datastore.FileSort
import com.tgdrive.core.datastore.FileViewMode
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.designsystem.editorial.EditorialEmptyState
import com.tgdrive.designsystem.theme.TgShapes
import com.tgdrive.designsystem.theme.TgTypography
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.Collections
import javax.inject.Inject

@Immutable
data class CategoryFilesUiState(
    val filter: FileFilter = FileFilter.VIDEOS,
    val items: List<FileCacheEntity> = emptyList(),
    val folderNames: Map<Long, String> = emptyMap(),
    val query: String = "",
    val sort: FileSort = FileSort.DATE_DESC,
    val viewMode: FileViewMode = FileViewMode.GRID,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoryFilesViewModel @Inject constructor(
    private val fileCacheDao: FileCacheDao,
    private val folderDao: FolderDao,
    private val telegramClient: TelegramClient,
    private val downloadManager: DownloadManager,
    private val mediaThumbnailCache: MediaThumbnailCache,
    private val preferences: AppPreferences,
    private val dispatchers: DispatchersProvider,
) : ViewModel() {

    private val _filter = MutableStateFlow(FileFilter.VIDEOS)
    private val _query = MutableStateFlow("")
    private val _sort = MutableStateFlow(FileSort.DATE_DESC)
    private val _systemChatId = MutableStateFlow<Long?>(null)
    private val _savedChatId = MutableStateFlow(0L)
    private val attemptedThumbKeys = Collections.synchronizedSet(HashSet<Pair<Long, Long>>())
    private val thumbChannel = Channel<FileCacheEntity>(capacity = 64)
    private val thumbSemaphore = Semaphore(2)

    init {
        viewModelScope.launch(dispatchers.io) {
            _systemChatId.value = runCatching { telegramClient.getOrCreateSystemChannel() }.getOrNull()
            _savedChatId.value = runCatching { telegramClient.myUserId() }.getOrDefault(0L)
        }
        viewModelScope.launch(dispatchers.io) {
            for (item in thumbChannel) {
                launch {
                    thumbSemaphore.withPermit {
                        try {
                            mediaThumbnailCache.ensureThumbnail(item)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
    }

    fun requestThumbnail(item: FileCacheEntity) {
        if (!mediaThumbnailCache.needsThumbnailUpgrade(item.thumbnailPath)) return
        if (attemptedThumbKeys.add(item.chatId to item.messageId)) {
            thumbChannel.trySend(item)
        }
    }

    private val activeCategoryFilesFlow = combine(_filter, _systemChatId) { filter, sysId ->
        filter to (sysId ?: 0L)
    }.flatMapLatest { (filter, excludedId) ->
        when (filter) {
            FileFilter.ALL -> fileCacheDao.observeActiveUnlocked(excludedId)
            FileFilter.IMAGES -> fileCacheDao.observeActiveByBuckets(listOf("IMAGES"), excludedId)
            FileFilter.VIDEOS -> fileCacheDao.observeActiveByBuckets(listOf("VIDEOS"), excludedId)
            FileFilter.AUDIO -> fileCacheDao.observeActiveByBuckets(listOf("AUDIO"), excludedId)
            FileFilter.DOCUMENTS -> fileCacheDao.observeActiveByBuckets(listOf("PDF", "DOCUMENTS"), excludedId)
            FileFilter.ARCHIVES -> fileCacheDao.observeActiveByBuckets(listOf("ARCHIVES"), excludedId)
        }
    }

    private data class CategoryControls(
        val filter: FileFilter,
        val query: String,
        val sort: FileSort,
        val viewMode: FileViewMode,
        val lockedFolderIds: Set<Long>,
    )

    val state: StateFlow<CategoryFilesUiState> = combine(
        combine(
            activeCategoryFilesFlow,
            folderDao.observeAll(),
        ) { files, folders -> files to folders },
        combine(
            _filter,
            _query,
            _sort,
            preferences.data,
        ) { filter, query, sort, prefs ->
            CategoryControls(filter, query, sort, prefs.fileViewMode, prefs.lockedFolderIdsAsLong())
        },
        _savedChatId,
    ) { (files, folders), controls, savedId ->
        val q = controls.query.trim().lowercase()
        val lockedFolders = controls.lockedFolderIds
        val filtered = files
            .asSequence()
            .filter { it.chatId !in lockedFolders }
            .filter { q.isEmpty() || it.fileName.lowercase().contains(q) }
            .toList()
            .let { list -> sortCategoryItems(list, controls.sort) }

        val names = buildMap {
            if (savedId != 0L) put(savedId, "Saved Messages")
            folders.forEach { put(it.channelId, it.title) }
        }
        CategoryFilesUiState(
            filter = controls.filter,
            items = filtered,
            folderNames = names,
            query = controls.query,
            sort = controls.sort,
            viewMode = controls.viewMode,
        )
    }.flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CategoryFilesUiState())

    fun setFilter(filter: FileFilter) {
        _filter.value = filter
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun setSort(sort: FileSort) {
        _sort.value = sort
    }

    fun toggleViewMode() {
        viewModelScope.launch(dispatchers.io) {
            val next = if (state.value.viewMode == FileViewMode.GRID) FileViewMode.LIST else FileViewMode.GRID
            preferences.update { it.copy(fileViewMode = next) }
        }
    }

    fun openFile(
        file: FileCacheEntity,
        folderTitle: String,
        onOpenVideo: (Long, Long) -> Unit,
        onOpenImage: (Long, Long) -> Unit,
        onOpenPdf: (Long, Long) -> Unit,
        onOpenZip: (Long) -> Unit,
        onOpenText: (Long, Long) -> Unit,
        onOpenFolder: (Long, String) -> Unit,
    ) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { fileCacheDao.updateLastAccessed(file.chatId, file.messageId, System.currentTimeMillis()) }
        }
        val bucket = classifyFile(file.mimeType, file.fileName)
        val needsDownload = run {
            val item = downloadManager.findItem(file.chatId, file.messageId)
                ?.takeIf { it.status == DownloadStatus.DONE }
            val path = item?.localPath
            path == null || !File(path).exists()
        }
        fun enqueueIfNeeded() {
            if (needsDownload && file.remoteId.isNotEmpty()) {
                downloadManager.enqueue(
                    DownloadItem(
                        id = file.messageId,
                        chatId = file.chatId,
                        remoteId = file.remoteId,
                        fileName = file.fileName,
                        totalBytes = file.fileSize,
                    ),
                )
            }
        }
        when {
            bucket == FileBucket.ARCHIVES && file.fileName.lowercase().endsWith(".zip") -> {
                enqueueIfNeeded()
                onOpenZip(file.messageId)
            }
            bucket == FileBucket.VIDEOS -> onOpenVideo(file.chatId, file.messageId)
            bucket == FileBucket.IMAGES -> {
                enqueueIfNeeded()
                onOpenImage(file.chatId, file.messageId)
            }
            bucket == FileBucket.PDF -> {
                enqueueIfNeeded()
                onOpenPdf(file.chatId, file.messageId)
            }
            isTextPreviewable(file.mimeType, file.fileName) -> {
                enqueueIfNeeded()
                onOpenText(file.chatId, file.messageId)
            }
            else -> onOpenFolder(file.chatId, folderTitle)
        }
    }

    private fun matchesFilter(entity: FileCacheEntity, filter: FileFilter): Boolean {
        val bucket = classifyFile(entity.mimeType, entity.fileName)
        return when (filter) {
            FileFilter.ALL -> true
            FileFilter.IMAGES -> bucket == FileBucket.IMAGES
            FileFilter.VIDEOS -> bucket == FileBucket.VIDEOS
            FileFilter.AUDIO -> bucket == FileBucket.AUDIO
            FileFilter.DOCUMENTS -> bucket == FileBucket.PDF || bucket == FileBucket.DOCUMENTS
            FileFilter.ARCHIVES -> bucket == FileBucket.ARCHIVES
        }
    }

    private fun sortCategoryItems(list: List<FileCacheEntity>, sort: FileSort): List<FileCacheEntity> =
        when (sort) {
            FileSort.DATE_DESC -> list.sortedByDescending { it.date }
            FileSort.DATE_ASC -> list.sortedBy { it.date }
            FileSort.NAME_ASC -> list.sortedBy { it.fileName.lowercase() }
            FileSort.NAME_DESC -> list.sortedByDescending { it.fileName.lowercase() }
            FileSort.SIZE_DESC -> list.sortedByDescending { it.fileSize }
            FileSort.SIZE_ASC -> list.sortedBy { it.fileSize }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryFilesScreen(
    filter: FileFilter,
    onBack: () -> Unit,
    onOpenVideo: (Long, Long) -> Unit,
    onOpenImage: (Long, Long) -> Unit,
    onOpenPdf: (Long, Long) -> Unit,
    onOpenZip: (Long) -> Unit,
    onOpenText: (Long, Long) -> Unit,
    onOpenFolder: (Long, String) -> Unit,
    vm: CategoryFilesViewModel = hiltViewModel(),
) {
    LaunchedEffect(filter) { vm.setFilter(filter) }

    val state by vm.state.collectAsStateWithLifecycle()
    var searchExpanded by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val savedTitle = stringResource(R.string.browser_saved_messages)

    val titleRes = when (state.filter) {
        FileFilter.VIDEOS -> R.string.category_title_videos
        FileFilter.DOCUMENTS -> R.string.category_title_docs
        FileFilter.AUDIO -> R.string.category_title_audio
        FileFilter.ARCHIVES -> R.string.category_title_archives
        FileFilter.IMAGES -> R.string.browser_cat_photos
        FileFilter.ALL -> R.string.browser_home
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    if (searchExpanded) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = vm::setQuery,
                            placeholder = { Text(stringResource(R.string.browser_search)) },
                            singleLine = true,
                            trailingIcon = {
                                if (state.query.isNotEmpty()) {
                                    IconButton(onClick = { vm.setQuery("") }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.browser_clear),
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            text = stringResource(titleRes),
                            style = TgTypography.Display.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (searchExpanded) {
                                searchExpanded = false
                                vm.setQuery("")
                            } else {
                                onBack()
                            }
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.browser_back),
                        )
                    }
                },
                actions = {
                    if (!searchExpanded) {
                        IconButton(onClick = { searchExpanded = true }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.browser_search),
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Sort,
                                contentDescription = stringResource(R.string.browser_action_sort),
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            listOf(
                                FileSort.DATE_DESC to R.string.browser_sort_date_desc,
                                FileSort.NAME_ASC to R.string.browser_sort_name_asc,
                                FileSort.SIZE_DESC to R.string.browser_sort_size_desc,
                            ).forEach { (sortOpt, labelId) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(labelId)) },
                                    trailingIcon = if (state.sort == sortOpt) {
                                        { Icon(Icons.Filled.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        vm.setSort(sortOpt)
                                        showSortMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = vm::toggleViewMode) {
                        Icon(
                            imageVector = if (state.viewMode == FileViewMode.GRID) {
                                Icons.AutoMirrored.Filled.ViewList
                            } else {
                                Icons.Filled.GridView
                            },
                            contentDescription = stringResource(
                                if (state.viewMode == FileViewMode.GRID) {
                                    R.string.browser_view_list
                                } else {
                                    R.string.browser_view_grid
                                },
                            ),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        if (state.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center,
            ) {
                EditorialEmptyState(
                    icon = categoryIcon(state.filter),
                    title = stringResource(R.string.category_empty_title),
                    description = stringResource(R.string.category_empty_desc),
                )
            }
        } else if (state.viewMode == FileViewMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .navigationBarsPadding(),
            ) {
                items(state.items, key = { "${it.chatId}_${it.messageId}" }, contentType = { "category_grid_item" }) { item ->
                    val folderTitle = state.folderNames[item.chatId] ?: savedTitle
                    LaunchedEffect(item.chatId, item.messageId) { vm.requestThumbnail(item) }
                    CategoryGridItem(
                        item = item,
                        folderTitle = folderTitle,
                        onClick = {
                            vm.openFile(
                                file = item,
                                folderTitle = folderTitle,
                                onOpenVideo = onOpenVideo,
                                onOpenImage = onOpenImage,
                                onOpenPdf = onOpenPdf,
                                onOpenZip = onOpenZip,
                                onOpenText = onOpenText,
                                onOpenFolder = onOpenFolder,
                            )
                        },
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .navigationBarsPadding(),
            ) {
                items(state.items, key = { "${it.chatId}_${it.messageId}" }, contentType = { "category_grid_item" }) { item ->
                    val folderTitle = state.folderNames[item.chatId] ?: savedTitle
                    LaunchedEffect(item.chatId, item.messageId) { vm.requestThumbnail(item) }
                    CategoryListItem(
                        item = item,
                        folderTitle = folderTitle,
                        onClick = {
                            vm.openFile(
                                file = item,
                                folderTitle = folderTitle,
                                onOpenVideo = onOpenVideo,
                                onOpenImage = onOpenImage,
                                onOpenPdf = onOpenPdf,
                                onOpenZip = onOpenZip,
                                onOpenText = onOpenText,
                                onOpenFolder = onOpenFolder,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryGridItem(
    item: FileCacheEntity,
    folderTitle: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val (icon, tint) = categoryVisual(item.mimeType, item.fileName)
    var loadFailed by remember(item.thumbnailPath) { mutableStateOf(false) }
    val thumbRequest = remember(item.thumbnailPath, context) {
        item.thumbnailPath
            ?.takeIf { it.isNotBlank() && !it.endsWith(".tgethumb", ignoreCase = true) }
            ?.let { path ->
                ImageRequest.Builder(context)
                    .data(File(path))
                    .size(Size(360, 360))
                    .crossfade(false)
                    .build()
            }
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(TgShapes.Large)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!loadFailed && thumbRequest != null) {
                AsyncImage(
                    model = thumbRequest,
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    onError = { loadFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.fileName,
            style = TgTypography.Display.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${item.fileSize.toHumanSize()} · $folderTitle",
            style = TgTypography.Mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CategoryListItem(
    item: FileCacheEntity,
    folderTitle: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val (icon, tint) = categoryVisual(item.mimeType, item.fileName)
    var loadFailed by remember(item.thumbnailPath) { mutableStateOf(false) }
    val thumbRequest = remember(item.thumbnailPath, context) {
        item.thumbnailPath
            ?.takeIf { it.isNotBlank() && !it.endsWith(".tgethumb", ignoreCase = true) }
            ?.let { path ->
                ImageRequest.Builder(context)
                    .data(File(path))
                    .size(Size(160, 160))
                    .crossfade(false)
                    .build()
            }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(TgShapes.Large)
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!loadFailed && thumbRequest != null) {
                AsyncImage(
                    model = thumbRequest,
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    onError = { loadFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = item.fileName,
                style = TgTypography.Display.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${item.fileSize.toHumanSize()} · ${item.date.toLocalShort()} · $folderTitle",
                style = TgTypography.Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun categoryVisual(mimeType: String?, fileName: String): Pair<ImageVector, Color> =
    when (classifyFile(mimeType, fileName)) {
        FileBucket.IMAGES -> Icons.Filled.Image to MaterialTheme.colorScheme.secondary
        FileBucket.VIDEOS -> Icons.Filled.Movie to MaterialTheme.colorScheme.primary
        FileBucket.AUDIO -> Icons.Filled.AudioFile to MaterialTheme.colorScheme.tertiary
        FileBucket.PDF -> Icons.Filled.PictureAsPdf to MaterialTheme.colorScheme.error
        FileBucket.ARCHIVES -> Icons.Filled.FolderZip to MaterialTheme.colorScheme.tertiary
        FileBucket.DOCUMENTS -> Icons.Filled.Description to MaterialTheme.colorScheme.onSurfaceVariant
        FileBucket.OTHER -> Icons.Filled.InsertDriveFile to MaterialTheme.colorScheme.primary
    }

private fun categoryIcon(filter: FileFilter): ImageVector = when (filter) {
    FileFilter.IMAGES -> Icons.Filled.Image
    FileFilter.VIDEOS -> Icons.Filled.Movie
    FileFilter.AUDIO -> Icons.Filled.AudioFile
    FileFilter.DOCUMENTS -> Icons.Filled.Description
    FileFilter.ARCHIVES -> Icons.Filled.FolderZip
    FileFilter.ALL -> Icons.Filled.InsertDriveFile
}
