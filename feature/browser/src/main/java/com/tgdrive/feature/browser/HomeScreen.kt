package com.tgdrive.feature.browser

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.common.toLocalShort
import com.tgdrive.core.datastore.FileFilter
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.designsystem.components.GlassPanel
import com.tgdrive.designsystem.components.TintedGlassPanel
import com.tgdrive.designsystem.theme.TgShapes
import com.tgdrive.designsystem.theme.TgTypography
import com.tgdrive.feature.folders.CreateFolderSheet
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenFolder: (Long, String) -> Unit = { _, _ -> },
    onOpenVideo: (Long, Long) -> Unit = { _, _ -> },
    onOpenImage: (Long, Long) -> Unit = { _, _ -> },
    onOpenPdf: (Long) -> Unit = {},
    onOpenZip: (Long) -> Unit = {},
    onOpenText: (Long, Long) -> Unit = { _, _ -> },
    onOpenAnalytics: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenCategory: (FileFilter) -> Unit = {},
    onOpenCategoryFolder: (Long, String, FileFilter) -> Unit = { _, _, _ -> },
    onOpenSettings: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenFreeUpSpace: () -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarMsg by vm.snackbar.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showQuickCreate by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var selectedTargetChatId by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.savedMessagesChatId) {
        if (selectedTargetChatId == 0L && state.savedMessagesChatId != 0L) {
            selectedTargetChatId = state.savedMessagesChatId
        }
    }

    LaunchedEffect(snackbarMsg) {
        val msg = snackbarMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.clearSnackbar()
    }

    val multiFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val target = selectedTargetChatId.takeIf { it != 0L } ?: state.savedMessagesChatId
            vm.uploadFilesToChat(target, uris)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshDashboard()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val savedTitle = stringResource(R.string.browser_saved_messages)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (selectedTargetChatId == 0L) {
                        selectedTargetChatId = state.savedMessagesChatId
                    }
                    showQuickCreate = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.home_quick_create_title),
                )
            }
        },
    ) { scaffoldPad ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { vm.refreshFolders() },
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPad)
                .background(MaterialTheme.colorScheme.background),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
            // Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CloudQueue,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        stringResource(R.string.browser_home),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .weight(1f),
                    )
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

            // Dismissible error banner
            state.error?.let { err ->
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(TgShapes.Large)
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = err,
                            style = TgTypography.Display.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp),
                        )
                        IconButton(onClick = vm::clearError) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.browser_dismiss),
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Storage status bento
            item { StorageStatusBento(state) }

            // Quick utility pills (Free up space + Trash)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickActionPill(
                        label = stringResource(R.string.home_free_up_space),
                        icon = Icons.Filled.CleaningServices,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenFreeUpSpace,
                    )
                    QuickActionPill(
                        label = if (state.trashCount > 0) {
                            "${stringResource(R.string.home_trash)} (${state.trashCount})"
                        } else {
                            stringResource(R.string.home_trash)
                        },
                        icon = Icons.Filled.DeleteOutline,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenTrash,
                    )
                }
            }

            // Categories grid (interactive)
            item {
                CategoriesGrid(
                    counts = state.categoryCounts,
                    onPickCategory = { filter ->
                        onOpenCategory(filter)
                    },
                )
            }

            // Starred horizontal strip
            if (state.starredFiles.isNotEmpty()) {
                item { SectionHeader(title = stringResource(R.string.browser_starred_files)) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.starredFiles.take(10), key = { "star_${it.messageId}_${it.chatId}" }) { file ->
                            StarredCard(file) {
                                vm.openOrPreviewFile(
                                    file = file,
                                    onOpenVideo = onOpenVideo,
                                    onOpenImage = onOpenImage,
                                    onOpenPdf = onOpenPdf,
                                    onOpenZip = onOpenZip,
                                    onOpenText = onOpenText,
                                    onOpenFolder = onOpenFolder,
                                )
                            }
                        }
                    }
                }
            }

            // Recent files
            if (state.recentFiles.isNotEmpty()) {
                item { SectionHeader(title = stringResource(R.string.browser_recent_files)) }
                items(state.recentFiles.take(5), key = { "recent_${it.messageId}_${it.chatId}" }) { file ->
                    FileRow(file) {
                        vm.openOrPreviewFile(
                            file = file,
                            onOpenVideo = onOpenVideo,
                            onOpenImage = onOpenImage,
                            onOpenPdf = onOpenPdf,
                            onOpenZip = onOpenZip,
                            onOpenText = onOpenText,
                            onOpenFolder = onOpenFolder,
                        )
                    }
                }
            }

            // Folders
            item { SectionHeader(title = stringResource(R.string.browser_folders)) }
            if (state.folders.isNotEmpty()) {
                items(state.folders, key = { it.id }) { folder ->
                    val size = state.folderSizes[folder.channelId] ?: 0L
                    FolderRow(
                        title = folder.title,
                        subtitle = if (size > 0) {
                            stringResource(R.string.browser_channel_size_subtitle, size.toHumanSize())
                        } else {
                            stringResource(R.string.browser_channel_subtitle)
                        },
                        icon = Icons.Filled.Folder,
                        tint = Color(folder.color),
                        onClick = { onOpenFolder(folder.channelId, folder.title) },
                    )
                }
            }

            // Saved Messages
            item {
                val ready = state.savedMessagesChatId != 0L
                val savedSize = if (ready) state.folderSizes[state.savedMessagesChatId] ?: 0L else 0L
                FolderRow(
                    title = savedTitle,
                    subtitle = when {
                        !ready -> stringResource(R.string.browser_connecting)
                        savedSize > 0 -> "${savedSize.toHumanSize()} · ${stringResource(R.string.browser_saved_messages_subtitle)}"
                        else -> stringResource(R.string.browser_saved_messages_subtitle)
                    },
                    icon = Icons.Filled.Bookmark,
                    tint = MaterialTheme.colorScheme.primary,
                    enabled = ready,
                    onClick = { onOpenFolder(state.savedMessagesChatId, savedTitle) },
                )
            }

            // Analytics callout
            item {
                GlassPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(TgShapes.XLarge)
                        .clickable(onClick = onOpenAnalytics),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(TgShapes.Large)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.BarChart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        ) {
                            Text(
                                stringResource(R.string.analytics_entry_title),
                                style = TgTypography.Display.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.analytics_entry_subtitle),
                                style = TgTypography.Mono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
    }

    if (showQuickCreate) {
        ModalBottomSheet(
            onDismissRequest = { showQuickCreate = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_quick_create_title),
                    style = TgTypography.Display.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.home_quick_target_label),
                    style = TgTypography.Display.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.savedMessagesChatId != 0L) {
                        FilterChip(
                            selected = selectedTargetChatId == state.savedMessagesChatId || selectedTargetChatId == 0L,
                            onClick = { selectedTargetChatId = state.savedMessagesChatId },
                            label = { Text(savedTitle) },
                        )
                    }
                    state.folders.forEach { folder ->
                        FilterChip(
                            selected = selectedTargetChatId == folder.channelId,
                            onClick = { selectedTargetChatId = folder.channelId },
                            label = { Text(folder.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                QuickSheetActionRow(
                    icon = Icons.Filled.UploadFile,
                    title = stringResource(R.string.home_quick_upload_files),
                    subtitle = stringResource(R.string.home_quick_upload_files_desc),
                    onClick = {
                        showQuickCreate = false
                        multiFilePicker.launch(arrayOf("*/*"))
                    },
                )
                QuickSheetActionRow(
                    icon = Icons.Filled.FolderZip,
                    title = stringResource(R.string.home_quick_upload_zip),
                    subtitle = stringResource(R.string.home_quick_upload_zip_desc),
                    onClick = {
                        val target = selectedTargetChatId.takeIf { it != 0L } ?: state.savedMessagesChatId
                        val title = state.folders.firstOrNull { it.channelId == target }?.title ?: savedTitle
                        showQuickCreate = false
                        if (target != 0L) {
                            onOpenFolder(target, title)
                        }
                    },
                )
                QuickSheetActionRow(
                    icon = Icons.Filled.CreateNewFolder,
                    title = stringResource(R.string.home_quick_new_folder),
                    subtitle = stringResource(R.string.home_quick_new_folder_desc),
                    onClick = {
                        showQuickCreate = false
                        showCreateFolder = true
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showCreateFolder) {
        CreateFolderSheet(
            onDismiss = {
                showCreateFolder = false
                vm.refreshFolders()
            },
        )
    }
}

@Composable
private fun QuickSheetActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(TgShapes.Large)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TgTypography.Display.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = TgTypography.Display.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StorageStatusBento(state: HomeUiState) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.browser_storage_status),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.5.sp,
                    )
                    Text(
                        if (state.totalFiles > 0) {
                            state.totalBytes.toHumanSize()
                        } else {
                            stringResource(R.string.browser_unlimited_storage)
                        },
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Icon(
                    Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                if (state.totalFiles > 0) {
                    stringResource(
                        R.string.browser_storage_summary,
                        state.totalBytes.toHumanSize(),
                        state.totalFiles,
                    )
                } else {
                    stringResource(R.string.browser_storage_tagline)
                },
                style = TgTypography.Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun CategoriesGrid(
    counts: Map<FileFilter, Int>,
    onPickCategory: (FileFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CategoryCard(
            label = stringResource(R.string.browser_cat_photos),
            count = (counts[FileFilter.IMAGES] ?: 0).toString(),
            icon = Icons.Filled.Image,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
            onClick = { onPickCategory(FileFilter.IMAGES) },
        )
        CategoryCard(
            label = stringResource(R.string.browser_cat_videos),
            count = (counts[FileFilter.VIDEOS] ?: 0).toString(),
            icon = Icons.Filled.Movie,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
            onClick = { onPickCategory(FileFilter.VIDEOS) },
        )
        CategoryCard(
            label = stringResource(R.string.browser_cat_docs),
            count = (counts[FileFilter.DOCUMENTS] ?: 0).toString(),
            icon = Icons.Filled.Description,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
            onClick = { onPickCategory(FileFilter.DOCUMENTS) },
        )
        CategoryCard(
            label = stringResource(R.string.browser_cat_audio),
            count = (counts[FileFilter.AUDIO] ?: 0).toString(),
            icon = Icons.Filled.AudioFile,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            onClick = { onPickCategory(FileFilter.AUDIO) },
        )
    }
}

@Composable
private fun CategoryCard(
    label: String,
    count: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TintedGlassPanel(
        tint = tint,
        modifier = modifier
            .clip(TgShapes.XLarge)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(TgShapes.Large)
                    .background(tint.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Text(
                count,
                style = TgTypography.Mono,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StarredCard(
    file: FileCacheEntity,
    onClick: () -> Unit,
) {
    val (icon, tint) = fileTypeVisual(file)
    val thumbFile = remember(file.thumbnailPath) {
        file.thumbnailPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
    }
    GlassPanel(
        modifier = Modifier
            .width(156.dp)
            .clip(TgShapes.XLarge)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (thumbFile != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clip(TgShapes.Large)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    AsyncImage(
                        model = thumbFile,
                        contentDescription = file.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(R.string.browser_starred_badge),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(TgShapes.Large)
                            .background(tint.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    }
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.browser_starred_badge),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                file.fileName,
                style = TgTypography.Display.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                file.fileSize.toHumanSize(),
                style = TgTypography.Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun FileRow(
    file: FileCacheEntity,
    onClick: () -> Unit,
) {
    val (icon, tint) = fileTypeVisual(file)
    val thumbFile = remember(file.thumbnailPath) {
        file.thumbnailPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
    }
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(TgShapes.XLarge)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(TgShapes.Large)
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbFile != null) {
                    AsyncImage(
                        model = thumbFile,
                        contentDescription = file.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    file.fileName,
                    style = TgTypography.Display.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${file.fileSize.toHumanSize()} · ${file.date.toLocalShort()}",
                    style = TgTypography.Mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun FolderRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(TgShapes.XLarge)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(TgShapes.Large)
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    title,
                    style = TgTypography.Display.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = TgTypography.Mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun fileTypeVisual(file: FileCacheEntity): Pair<ImageVector, Color> =
    when (classifyFile(file.mimeType, file.fileName)) {
        FileBucket.IMAGES -> Icons.Filled.Image to MaterialTheme.colorScheme.secondary
        FileBucket.VIDEOS -> Icons.Filled.Movie to MaterialTheme.colorScheme.primary
        FileBucket.AUDIO -> Icons.Filled.AudioFile to MaterialTheme.colorScheme.tertiary
        FileBucket.PDF -> Icons.Filled.PictureAsPdf to MaterialTheme.colorScheme.error
        FileBucket.ARCHIVES -> Icons.Filled.FolderZip to MaterialTheme.colorScheme.tertiary
        FileBucket.DOCUMENTS -> Icons.Filled.Description to MaterialTheme.colorScheme.onSurfaceVariant
        FileBucket.OTHER -> Icons.Filled.InsertDriveFile to MaterialTheme.colorScheme.primary
    }

@Composable
private fun QuickActionPill(
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TintedGlassPanel(
        tint = tint,
        modifier = modifier
            .clip(TgShapes.XLarge)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(TgShapes.Large)
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(
                text = label,
                style = TgTypography.Display.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
