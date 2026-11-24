package com.tgdrive.feature.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.isTextPreviewable
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.common.toLocalShort
import com.tgdrive.core.datastore.FileFilter
import com.tgdrive.core.datastore.FileSort
import com.tgdrive.core.datastore.FileViewMode
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.NormalUploadOptions
import com.tgdrive.core.download.UploadConflictStrategy
import com.tgdrive.core.download.UploadEncryptionSpec
import com.tgdrive.core.download.UploadItem
import com.tgdrive.core.download.UploadStatus
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.designsystem.editorial.EditorialEmptyState
import com.tgdrive.designsystem.theme.TgShapes
import com.tgdrive.designsystem.theme.TgTypography
import com.tgdrive.feature.upload.DecryptFileDialog
import com.tgdrive.feature.upload.FileUploadReviewDialog
import com.tgdrive.feature.upload.ZipUploadOptionsDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderScreen(
    chatId: Long,
    title: String = "",
    initialFilter: FileFilter? = null,
    onOpenVideo: (Long, Long) -> Unit,
    onOpenLocalVideo: (String) -> Unit = {},
    onOpenLocalPdf: (String, String) -> Unit = { _, _ -> },
    onOpenLocalText: (String, String) -> Unit = { _, _ -> },
    onPlayLocalAudio: (String, String) -> Unit = { _, _ -> },
    onOpenImage: (Long, Long) -> Unit = { _, _ -> },
    onOpenPdf: (Long) -> Unit = {},
    onOpenZip: (Long) -> Unit = {},
    onOpenText: (Long, Long) -> Unit = { _, _ -> },
    onBack: () -> Unit,
    vm: FolderViewModel = hiltViewModel(),
) {
    LaunchedEffect(chatId) { vm.bind(chatId) }
    LaunchedEffect(chatId, initialFilter) {
        if (initialFilter != null) vm.setFileFilter(initialFilter)
    }

    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarEvent by vm.snackbarEvent.collectAsStateWithLifecycle()
    val decryptedOpenEvent by vm.decryptedOpenEvent.collectAsStateWithLifecycle()
    val isSavedMessages by vm.isSavedMessages.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.trash_undo)
    val noAppMsg = stringResource(R.string.browser_no_app_error)

    val selectionMode = state.selectedIds.isNotEmpty()
    var searchOpen by remember { mutableStateOf(false) }
    var detailsItem by remember { mutableStateOf<FileCacheEntity?>(null) }
    var showUploadMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }
    var moveFromDetails by remember { mutableStateOf(false) }
    var pendingNormalFolder by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var zipExcludesDraft by remember { mutableStateOf("") }
    var zipPartCapDraft by remember { mutableStateOf<Long?>(null) }
    var decryptedImageViewerPath by remember { mutableStateOf<String?>(null) }
    var showBatchDecryptDialog by remember { mutableStateOf(false) }

    LaunchedEffect(snackbarEvent?.id) {
        val ev = snackbarEvent ?: return@LaunchedEffect
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

    LaunchedEffect(decryptedOpenEvent?.id) {
        val ev = decryptedOpenEvent ?: return@LaunchedEffect
        vm.clearDecryptedOpenEvent()
        val file = ev.outcome.file
        val ext = file.extension.lowercase()
        val isVideo = ev.outcome.mimeType.startsWith("video/", ignoreCase = true) ||
            ext in setOf("mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp", "ts", "flv")
        val isAudio = ev.outcome.mimeType.startsWith("audio/", ignoreCase = true) ||
            ext in setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "aiff")
        val isPdf = ev.outcome.mimeType.equals("application/pdf", ignoreCase = true) || ext == "pdf"
        val isText = isTextPreviewable(ev.outcome.mimeType, ev.outcome.displayName)
        when {
            ev.outcome.mimeType.startsWith("image/", ignoreCase = true) -> {
                decryptedImageViewerPath = file.absolutePath
            }
            isVideo -> {
                onOpenLocalVideo(file.absolutePath)
            }
            isAudio -> {
                onPlayLocalAudio(file.absolutePath, ev.outcome.displayName)
            }
            isPdf -> {
                onOpenLocalPdf(file.absolutePath, ev.outcome.displayName)
            }
            isText -> {
                onOpenLocalText(file.absolutePath, ev.outcome.displayName)
            }
            else -> {
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, ev.outcome.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (t: Throwable) {
                    Log.w("FolderScreen: failed opening decrypted file", t)
                    vm.showSnackbar(noAppMsg)
                }
            }
        }
    }

    BackHandler(enabled = selectionMode || searchOpen) {
        when {
            selectionMode -> vm.clearSelection()
            searchOpen -> {
                searchOpen = false
                vm.setSearch("")
            }
        }
    }

    val pickFilesLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                uris.forEach { uri ->
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (_: Throwable) {}
                }
                vm.prepareFileUploadReview(uris)
            }
        }

    val pickNormalFolderLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Throwable) {}
                val folderName = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                    ?: Uri.decode(uri.toString()).substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
                pendingNormalFolder = uri to folderName
            }
        }

    val pickZipFolderLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Throwable) {}
                val folderName = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
                zipExcludesDraft = ""
                zipPartCapDraft = null
                vm.onZipFolderPicked(uri, folderName)
            }
        }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    when {
                        selectionMode -> {
                            Text(
                                text = stringResource(R.string.browser_selected_count, state.selectedIds.size),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        searchOpen -> {
                            OutlinedTextField(
                                value = state.search,
                                onValueChange = vm::setSearch,
                                placeholder = { Text(stringResource(R.string.browser_search)) },
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (state.search.isNotEmpty()) {
                                        IconButton(onClick = { vm.setSearch("") }) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = stringResource(R.string.browser_clear),
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        else -> {
                            Text(
                                text = title.ifBlank { stringResource(R.string.browser_folders) },
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                selectionMode -> vm.clearSelection()
                                searchOpen -> {
                                    searchOpen = false
                                    vm.setSearch("")
                                }
                                else -> onBack()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (selectionMode) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                if (selectionMode) R.string.browser_action_clear_selection else R.string.browser_back,
                            ),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = { vm.selectAll() }) {
                            Icon(
                                Icons.Filled.SelectAll,
                                contentDescription = stringResource(R.string.browser_action_select_all),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = { vm.downloadAllSelected() }) {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = stringResource(R.string.browser_action_download),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        val hasSelectedEncrypted = state.items.any {
                            it.messageId in state.selectedIds && BackupCrypto.isEncryptedFileName(it.fileName)
                        }
                        if (hasSelectedEncrypted) {
                            IconButton(onClick = { showBatchDecryptDialog = true }) {
                                Icon(
                                    Icons.Filled.LockOpen,
                                    contentDescription = stringResource(R.string.browser_batch_decrypt),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        IconButton(onClick = {
                            moveFromDetails = false
                            showMovePicker = true
                        }) {
                            Icon(
                                Icons.Filled.DriveFileMove,
                                contentDescription = stringResource(R.string.browser_action_move),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = { vm.deleteSelected() }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.browser_action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        if (!searchOpen) {
                            IconButton(onClick = { searchOpen = true }) {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.browser_search),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    Icons.Filled.Sort,
                                    contentDescription = stringResource(R.string.browser_action_sort),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                FileSort.entries.forEach { sort ->
                                    DropdownMenuItem(
                                        text = { Text(sortLabel(sort)) },
                                        trailingIcon = {
                                            if (state.fileSort == sort) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        },
                                        onClick = {
                                            vm.setFileSort(sort)
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { vm.setViewMode(other(state.viewMode)) }) {
                            Icon(
                                imageVector = when (state.viewMode) {
                                    FileViewMode.GRID -> Icons.Filled.List
                                    FileViewMode.LIST -> Icons.Filled.GridView
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
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        floatingActionButton = {
            if (!selectionMode) {
                Box {
                    FloatingActionButton(
                        onClick = { showUploadMenu = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.browser_action_upload))
                    }
                    DropdownMenu(expanded = showUploadMenu, onDismissRequest = { showUploadMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_upload_files)) },
                            onClick = {
                                showUploadMenu = false
                                pickFilesLauncher.launch(arrayOf("*/*"))
                            },
                            leadingIcon = { Icon(Icons.Filled.InsertDriveFile, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_upload_folder)) },
                            onClick = {
                                showUploadMenu = false
                                pickNormalFolderLauncher.launch(null)
                            },
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_upload_zip)) },
                            onClick = {
                                showUploadMenu = false
                                pickZipFolderLauncher.launch(null)
                            },
                            leadingIcon = { Icon(Icons.Filled.FolderZip, contentDescription = null) },
                        )
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
            if (state.uploads.isNotEmpty()) {
                UploadsStrip(
                    uploads = state.uploads,
                    onPause = vm::pauseUpload,
                    onResume = vm::resumeUpload,
                    onCancel = vm::cancelUpload,
                    onRetry = vm::retryUpload,
                    onClear = vm::clearFinishedUploads,
                )
            }

            state.batchDecryptProgress?.let { (done, total) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.browser_batch_decrypt_progress, done, total),
                        style = TgTypography.Caption,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LinearProgressIndicator(
                        progress = { if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    )
                }
            }

            state.error?.let { err ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
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
                            .padding(horizontal = 8.dp),
                    )
                    IconButton(onClick = { vm.clearError() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.browser_dismiss),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (!selectionMode) {
                FilterChipsRow(current = state.fileFilter, onPick = vm::setFileFilter)
            }

            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.weight(1f),
            ) {
                if (state.items.isEmpty() && !state.refreshing) {
                    val hasFilter = state.search.isNotBlank() || state.fileFilter != FileFilter.ALL
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (hasFilter) {
                            EditorialEmptyState(
                                icon = Icons.Filled.Search,
                                title = stringResource(R.string.browser_empty_filtered),
                                description = stringResource(R.string.browser_empty_filtered_desc),
                                actionLabel = stringResource(R.string.browser_clear_filters),
                                onAction = {
                                    searchOpen = false
                                    vm.setSearch("")
                                    vm.setFileFilter(FileFilter.ALL)
                                },
                            )
                        } else {
                            EditorialEmptyState(
                                icon = Icons.Filled.InsertDriveFile,
                                title = stringResource(R.string.browser_empty_folder),
                                description = stringResource(R.string.browser_empty_folder_desc),
                            )
                        }
                    }
                } else {
                    when (state.viewMode) {
                        FileViewMode.GRID ->
                            FileGrid(
                                items = state.items,
                                pdfThumbnails = state.pdfThumbnails,
                                selectedIds = state.selectedIds,
                                onLongClick = { id ->
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.toggleSelection(id)
                                },
                                onClick = { id ->
                                    if (selectionMode) {
                                        vm.toggleSelection(id)
                                    } else {
                                        handleFileTap(
                                            messageId = id,
                                            items = state.items,
                                            vm = vm,
                                            onOpenVideo = onOpenVideo,
                                            onOpenImage = onOpenImage,
                                            onOpenPdf = onOpenPdf,
                                            onOpenZip = onOpenZip,
                                            onOpenText = onOpenText,
                                            context = context,
                                            noAppMessage = noAppMsg,
                                        )
                                    }
                                },
                                onMoreClick = { id ->
                                    state.items.find { it.messageId == id }?.let { detailsItem = it }
                                },
                                onLoadMore = { vm.loadMore() },
                                isLoadingMore = state.loading,
                            )
                        FileViewMode.LIST ->
                            FileList(
                                items = state.items,
                                pdfThumbnails = state.pdfThumbnails,
                                selectedIds = state.selectedIds,
                                onLongClick = { id ->
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.toggleSelection(id)
                                },
                                onClick = { id ->
                                    if (selectionMode) {
                                        vm.toggleSelection(id)
                                    } else {
                                        handleFileTap(
                                            messageId = id,
                                            items = state.items,
                                            vm = vm,
                                            onOpenVideo = onOpenVideo,
                                            onOpenImage = onOpenImage,
                                            onOpenPdf = onOpenPdf,
                                            onOpenZip = onOpenZip,
                                            onOpenText = onOpenText,
                                            context = context,
                                            noAppMessage = noAppMsg,
                                        )
                                    }
                                },
                                onMoreClick = { id ->
                                    state.items.find { it.messageId == id }?.let { detailsItem = it }
                                },
                                onLoadMore = { vm.loadMore() },
                                isLoadingMore = state.loading,
                            )
                    }
                }
            }
        }
    }

    if (state.pendingReviewFiles.isNotEmpty()) {
        FileUploadReviewDialog(
            initialFiles = state.pendingReviewFiles,
            onDismiss = vm::dismissFileUploadReview,
            onConfirm = vm::confirmFileReview,
            defaultWifiOnly = state.manualUploadWifiOnly,
        )
    }

    pendingNormalFolder?.let { (folderUri, folderName) ->
        FolderNormalUploadDialog(
            folderName = folderName,
            defaultWifiOnly = state.manualUploadWifiOnly,
            onDismiss = { pendingNormalFolder = null },
            onConfirm = { options ->
                pendingNormalFolder = null
                vm.uploadFolderAsNormal(folderUri, options)
            },
        )
    }

    state.pendingZipTreeUri?.let { treeUri ->
        ZipUploadOptionsDialog(
            initialName = state.pendingZipFolderName,
            summary = state.zipPreviewSummary,
            summaryLoading = state.zipPreviewLoading,
            defaultWifiOnly = state.manualUploadWifiOnly,
            onExcludePatternsChanged = { pat ->
                zipExcludesDraft = pat
                vm.inspectZipFolder(treeUri, pat, zipPartCapDraft)
            },
            onMaxPartSizeChanged = { cap ->
                zipPartCapDraft = cap
                vm.inspectZipFolder(treeUri, zipExcludesDraft, cap)
            },
            onDismiss = vm::dismissZipDialog,
            onConfirm = vm::confirmZipFolderUpload,
        )
    }

    if (showBatchDecryptDialog) {
        val tgencCount = state.items.count {
            it.messageId in state.selectedIds && BackupCrypto.isEncryptedFileName(it.fileName)
        }
        BatchDecryptPassphraseDialog(
            count = tgencCount,
            onConfirm = { pwd ->
                showBatchDecryptDialog = false
                vm.batchDecryptSelected(pwd)
            },
            onDismiss = { showBatchDecryptDialog = false },
        )
    }

    state.decryptDialog?.let { dlg ->
        DecryptFileDialog(
            fileName = dlg.item.fileName,
            detectedFormat = dlg.format,
            hasSavedBackupPassphrase = state.hasSavedBackupPassphrase,
            inProgress = dlg.downloading || dlg.decrypting,
            progressMessage = if (dlg.downloading) {
                stringResource(R.string.browser_downloading_file, dlg.item.fileName)
            } else {
                null
            },
            errorMessage = dlg.error,
            onDecryptAndOpen = { pwd -> vm.decryptTarget(pwd, saveToDownloads = false) },
            onDecryptAndSave = { pwd -> vm.decryptTarget(pwd, saveToDownloads = true) },
            onDismiss = vm::dismissDecryptDialog,
        )
    }

    decryptedImageViewerPath?.let { path ->
        com.tgdrive.feature.player.ImageViewer(
            imagePaths = listOf(path),
            onDismiss = { decryptedImageViewerPath = null },
        )
    }

    detailsItem?.let { item ->
        val isEncryptedTgenc = BackupCrypto.isEncryptedFileName(item.fileName)
        FileDetailsSheet(
            item = item,
            isDownloaded = vm.getLocalPath(item.messageId) != null,
            isPinnedOffline = item.isPinnedOffline,
            onDownload = { vm.downloadFile(item) },
            onCopyToDownloads = { vm.copyToDownloads(item.messageId) },
            onMove = {
                vm.clearSelection()
                vm.toggleSelection(item.messageId)
                moveFromDetails = true
                showMovePicker = true
            },
            onDelete = { vm.deleteSingle(item.messageId) },
            onToggleStar = { vm.toggleStar(item.messageId) },
            onTogglePinOffline = { vm.togglePinOffline(item) },
            onShareLink = if (isSavedMessages) null else ({ vm.shareLink(item) }),
            onDecrypt = if (isEncryptedTgenc) ({ vm.startDecryptFlow(item) }) else null,
            onDismiss = { detailsItem = null },
        )
    }

    if (showMovePicker) {
        MoveTargetPickerDialog(
            excludeChatId = chatId,
            loadFolders = { vm.listFoldersForMove() },
            onPick = { folder ->
                showMovePicker = false
                moveFromDetails = false
                vm.moveSelected(folder.chatId)
            },
            onDismiss = {
                showMovePicker = false
                if (moveFromDetails) {
                    vm.clearSelection()
                    moveFromDetails = false
                }
            },
        )
    }
}

@Composable
private fun UploadsStrip(
    uploads: List<UploadItem>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onClear: () -> Unit,
) {
    val ordered = remember(uploads) {
        uploads.sortedBy { if (it.status.isInQueue) 0 else 1 }
    }
    val hasFinished = uploads.any { !it.status.isActive }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ordered.take(3).forEach { u ->
            val tint = when (u.status) {
                UploadStatus.DONE -> MaterialTheme.colorScheme.tertiary
                UploadStatus.FAILED -> MaterialTheme.colorScheme.error
                UploadStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.secondary
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (u.status.isActive) {
                    if (u.status == UploadStatus.QUEUED) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = tint,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        CircularProgressIndicator(
                            progress = { u.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.size(20.dp),
                            color = tint,
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(tint.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            when (u.status) {
                                UploadStatus.DONE -> Icons.Filled.CheckCircle
                                UploadStatus.FAILED -> Icons.Filled.Warning
                                UploadStatus.CANCELLED -> Icons.Filled.Close
                                else -> Icons.Filled.Upload
                            },
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = u.fileName,
                            style = TgTypography.Display.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (u.isEncrypted) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = stringResource(R.string.browser_encrypted_badge),
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    val sub = u.errorMessage ?: u.statusDetail ?: if (u.speedBytesPerSec > 0L) {
                        "${u.speedBytesPerSec.toHumanSize()}/s"
                    } else {
                        null
                    }
                    sub?.let { msg ->
                        Text(
                            text = msg,
                            style = TgTypography.Caption,
                            color = if (u.status == UploadStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = when (u.status) {
                        UploadStatus.QUEUED -> stringResource(R.string.browser_upload_queued)
                        UploadStatus.PREPARING -> stringResource(R.string.browser_upload_preparing)
                        UploadStatus.PACKING -> stringResource(R.string.browser_upload_packing)
                        UploadStatus.ENCRYPTING -> stringResource(R.string.browser_upload_encrypting)
                        UploadStatus.UPLOADING -> "${(u.progress * 100).toInt()}%"
                        UploadStatus.PAUSED -> stringResource(R.string.browser_upload_paused)
                        UploadStatus.DONE -> stringResource(R.string.browser_upload_done)
                        UploadStatus.FAILED -> stringResource(R.string.browser_upload_failed)
                        UploadStatus.CANCELLED -> stringResource(R.string.browser_upload_cancelled)
                    },
                    style = TgTypography.Mono,
                    color = tint,
                    fontWeight = FontWeight.Bold,
                )
                if (u.status.isActive) {
                    IconButton(onClick = { onPause(u.id) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Pause,
                            contentDescription = stringResource(R.string.browser_upload_pause),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(onClick = { onCancel(u.id) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.browser_upload_stop),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else if (u.status == UploadStatus.PAUSED) {
                    IconButton(onClick = { onResume(u.id) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.browser_upload_resume),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(onClick = { onCancel(u.id) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.browser_upload_stop),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else if ((u.status == UploadStatus.FAILED || u.status == UploadStatus.CANCELLED) && u.canRetry) {
                    IconButton(onClick = { onRetry(u.id) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.browser_upload_retry),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        if (hasFinished) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.browser_clear), style = TgTypography.Display.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun FolderNormalUploadDialog(
    folderName: String,
    defaultWifiOnly: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (NormalUploadOptions) -> Unit,
) {
    var conflict by remember { mutableStateOf(UploadConflictStrategy.KEEP_BOTH) }
    var encrypt by remember { mutableStateOf(false) }
    var useVaultKey by remember { mutableStateOf(true) }
    var wifiOnly by remember(defaultWifiOnly) { mutableStateOf(defaultWifiOnly) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPwd by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Text(
                stringResource(R.string.browser_folder_upload_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    stringResource(R.string.browser_folder_upload_desc, folderName),
                    style = TgTypography.Display.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = conflict == UploadConflictStrategy.KEEP_BOTH,
                        onClick = { conflict = UploadConflictStrategy.KEEP_BOTH },
                        label = { Text("Keep both") },
                    )
                    FilterChip(
                        selected = conflict == UploadConflictStrategy.SKIP,
                        onClick = { conflict = UploadConflictStrategy.SKIP },
                        label = { Text("Skip duplicates") },
                    )
                    FilterChip(
                        selected = conflict == UploadConflictStrategy.REPLACE,
                        onClick = { conflict = UploadConflictStrategy.REPLACE },
                        label = { Text("Replace") },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(value = encrypt, role = Role.Switch, onValueChange = {
                            encrypt = it
                            error = null
                        })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.browser_folder_encrypt_label),
                        style = TgTypography.Display.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = encrypt, onCheckedChange = null)
                }
                if (encrypt) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = useVaultKey,
                            onClick = {
                                useVaultKey = true
                                error = null
                            },
                            label = { Text("Device Vault Key") },
                        )
                        FilterChip(
                            selected = !useVaultKey,
                            onClick = {
                                useVaultKey = false
                                error = null
                            },
                            label = { Text("Custom password") },
                        )
                    }
                    if (!useVaultKey) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                error = null
                            },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = if (showPwd) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showPwd = !showPwd }) {
                                    Icon(
                                        if (showPwd) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = null,
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = confirm,
                            onValueChange = {
                                confirm = it
                                error = null
                            },
                            label = { Text("Confirm password") },
                            singleLine = true,
                            visualTransformation = if (showPwd) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(value = wifiOnly, role = Role.Switch, onValueChange = { wifiOnly = it })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.browser_upload_wifi_only),
                        style = TgTypography.Display.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = wifiOnly, onCheckedChange = null)
                }
                error?.let {
                    Text(it, style = TgTypography.Caption, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    encrypt && !useVaultKey && password.length < 4 -> error = "Use at least 4 characters"
                    encrypt && !useVaultKey && password != confirm -> error = "Passwords don't match"
                    else -> onConfirm(
                        NormalUploadOptions(
                            conflictStrategy = conflict,
                            encryption = UploadEncryptionSpec(
                                enabled = encrypt,
                                useVaultKey = useVaultKey,
                                customPassphrase = if (encrypt && !useVaultKey) password else null,
                            ),
                            wifiOnly = wifiOnly,
                        ),
                    )
                }
            }) {
                Text(stringResource(R.string.browser_action_upload), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.browser_cancel)) }
        },
    )
}

private fun other(mode: FileViewMode) = if (mode == FileViewMode.GRID) FileViewMode.LIST else FileViewMode.GRID

private val PART_ZIP_REGEX = Regex("_part(\\d+)\\.zip$", RegexOption.IGNORE_CASE)

@Composable
private fun FileGrid(
    items: List<FileCacheEntity>,
    pdfThumbnails: Map<Long, String>,
    selectedIds: Set<Long>,
    onClick: (Long) -> Unit,
    onLongClick: (Long) -> Unit,
    onMoreClick: ((Long) -> Unit)? = null,
    onLoadMore: () -> Unit = {},
    isLoadingMore: Boolean = false,
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState, items.size, isLoadingMore) {
        snapshotFlow {
            val total = gridState.layoutInfo.totalItemsCount
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 4
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                if (!isLoadingMore) onLoadMore()
            }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.messageId }) { item ->
            FileGridCell(
                item = item,
                pdfThumbnailPath = pdfThumbnails[item.messageId],
                isSelected = item.messageId in selectedIds,
                onClick = { onClick(item.messageId) },
                onLongClick = { onLongClick(item.messageId) },
                onMoreClick = onMoreClick?.let { { it(item.messageId) } },
            )
        }
        if (isLoadingMore) {
            item(span = { GridItemSpan(2) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.tertiary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCell(
    item: FileCacheEntity,
    pdfThumbnailPath: String? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null,
) {
    val tint = tintFor(item.mimeType, item.fileName)
    val thumbModel = item.thumbnailPath ?: pdfThumbnailPath
    val thumbFile = remember(thumbModel, item.isEncryptedVault) {
        if (item.isEncryptedVault || thumbModel?.endsWith(".tgethumb", ignoreCase = true) == true) {
            null
        } else {
            thumbModel?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
        }
    }
    val partBadge = remember(item.fileName) {
        PART_ZIP_REGEX.find(item.fileName)?.groupValues?.getOrNull(1)?.let { "P$it" }
    }
    Box(
        modifier = Modifier
            .aspectRatio(0.78f)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            )
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                },
            )
            .semantics { selected = isSelected }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbFile != null) {
                    AsyncImage(
                        model = thumbFile,
                        contentDescription = item.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(tint.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = iconFor(item.mimeType, item.fileName),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                if (item.isStarred) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
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
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                if (item.isPinnedOffline) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.OfflinePin,
                            contentDescription = stringResource(R.string.details_pin_offline),
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                } else if (item.isEncryptedVault || BackupCrypto.isEncryptedFileName(item.fileName)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.browser_encrypted_badge),
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                if (partBadge != null) {
                    Text(
                        text = partBadge,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
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
                            .size(22.dp),
                    )
                } else if (onMoreClick != null) {
                    IconButton(
                        onClick = onMoreClick,
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.browser_more),
                            tint = if (thumbFile != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Text(
                text = item.fileName,
                style = TgTypography.Display.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = item.fileSize.toHumanSize(),
                style = TgTypography.Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FileList(
    items: List<FileCacheEntity>,
    pdfThumbnails: Map<Long, String>,
    selectedIds: Set<Long>,
    onClick: (Long) -> Unit,
    onLongClick: (Long) -> Unit,
    onMoreClick: ((Long) -> Unit)? = null,
    onLoadMore: () -> Unit = {},
    isLoadingMore: Boolean = false,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(listState, items.size, isLoadingMore) {
        snapshotFlow {
            val total = listState.layoutInfo.totalItemsCount
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 5
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                if (!isLoadingMore) onLoadMore()
            }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items, key = { it.messageId }) { item ->
            FileListRow(
                item = item,
                pdfThumbnailPath = pdfThumbnails[item.messageId],
                isSelected = item.messageId in selectedIds,
                onClick = { onClick(item.messageId) },
                onLongClick = { onLongClick(item.messageId) },
                onMoreClick = onMoreClick?.let { { it(item.messageId) } },
            )
        }
        if (isLoadingMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.tertiary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListRow(
    item: FileCacheEntity,
    pdfThumbnailPath: String? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null,
) {
    val tint = tintFor(item.mimeType, item.fileName)
    val thumbModel = item.thumbnailPath ?: pdfThumbnailPath
    val thumbFile = remember(thumbModel, item.isEncryptedVault) {
        if (item.isEncryptedVault || thumbModel?.endsWith(".tgethumb", ignoreCase = true) == true) {
            null
        } else {
            thumbModel?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
        }
    }
    val partBadge = remember(item.fileName) {
        PART_ZIP_REGEX.find(item.fileName)?.groupValues?.getOrNull(1)?.let { "P$it" }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            )
            .then(
                if (isSelected) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                } else {
                    Modifier
                },
            )
            .semantics { selected = isSelected }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            } else if (thumbFile != null) {
                AsyncImage(
                    model = thumbFile,
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Icon(
                    imageVector = iconFor(item.mimeType, item.fileName),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
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
                text = "${item.fileSize.toHumanSize()} · ${item.date.toLocalShort()}",
                style = TgTypography.Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (partBadge != null) {
            Text(
                text = partBadge,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (item.isEncryptedVault || BackupCrypto.isEncryptedFileName(item.fileName)) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = stringResource(R.string.browser_encrypted_badge),
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(16.dp),
            )
        }
        if (item.isPinnedOffline) {
            Icon(
                Icons.Filled.OfflinePin,
                contentDescription = stringResource(R.string.details_pin_offline),
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(16.dp),
            )
        }
        if (item.isStarred) {
            Icon(
                Icons.Filled.Star,
                contentDescription = stringResource(R.string.browser_starred_badge),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        if (onMoreClick != null) {
            IconButton(onClick = onMoreClick) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.browser_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun iconFor(mime: String?, fileName: String): ImageVector =
    when (classifyFile(mime, fileName)) {
        FileBucket.IMAGES -> Icons.Filled.Image
        FileBucket.VIDEOS -> Icons.Filled.VideoFile
        FileBucket.AUDIO -> Icons.Filled.MusicNote
        FileBucket.PDF -> Icons.Filled.PictureAsPdf
        FileBucket.ARCHIVES -> Icons.Filled.FolderZip
        else -> Icons.Filled.InsertDriveFile
    }

@Composable
private fun tintFor(mime: String?, fileName: String): Color =
    when (classifyFile(mime, fileName)) {
        FileBucket.IMAGES -> MaterialTheme.colorScheme.secondary
        FileBucket.VIDEOS -> MaterialTheme.colorScheme.primary
        FileBucket.AUDIO -> MaterialTheme.colorScheme.tertiary
        FileBucket.PDF -> MaterialTheme.colorScheme.error
        FileBucket.ARCHIVES -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun handleFileTap(
    messageId: Long,
    items: List<FileCacheEntity>,
    vm: FolderViewModel,
    onOpenVideo: (Long, Long) -> Unit,
    onOpenImage: (Long, Long) -> Unit,
    onOpenPdf: (Long) -> Unit,
    onOpenZip: (Long) -> Unit,
    onOpenText: (Long, Long) -> Unit,
    context: Context,
    noAppMessage: String,
) {
    val item = items.find { it.messageId == messageId } ?: return
    val localPath = vm.getLocalPath(messageId)
    vm.markAccessed(messageId)
    val bucket = classifyFile(item.mimeType, item.fileName)
    when {
        BackupCrypto.isEncryptedFileName(item.fileName) -> {
            vm.startDecryptFlow(item)
        }
        bucket == FileBucket.ARCHIVES && item.fileName.lowercase().endsWith(".zip") -> {
            if (localPath == null) vm.downloadFile(item)
            onOpenZip(messageId)
        }
        bucket == FileBucket.VIDEOS -> {
            onOpenVideo(item.chatId, messageId)
        }
        bucket == FileBucket.IMAGES -> {
            if (localPath == null) vm.downloadFile(item)
            onOpenImage(item.chatId, messageId)
        }
        bucket == FileBucket.PDF -> {
            if (localPath == null) vm.downloadFile(item)
            onOpenPdf(messageId)
        }
        isTextPreviewable(item.mimeType, item.fileName) -> {
            if (localPath == null) vm.downloadFile(item)
            onOpenText(item.chatId, messageId)
        }
        bucket == FileBucket.AUDIO -> {
            if (localPath == null) {
                vm.downloadAndPlayAudio(item)
            } else {
                vm.audioPlayerController.playAudio(localPath, item.fileName)
            }
        }
        localPath != null -> {
            try {
                val file = File(localPath)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, item.mimeType ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (t: Throwable) {
                Log.w("handleFileTap: no app to open file", t)
                vm.showSnackbar(noAppMessage)
            }
        }
        else -> {
            vm.downloadFile(item)
            vm.showSnackbar(context.getString(R.string.browser_downloading_file, item.fileName))
        }
    }
}

@Composable
private fun sortLabel(sort: FileSort): String =
    when (sort) {
        FileSort.NAME_ASC -> stringResource(R.string.browser_sort_name_asc)
        FileSort.NAME_DESC -> stringResource(R.string.browser_sort_name_desc)
        FileSort.SIZE_ASC -> stringResource(R.string.browser_sort_size_asc)
        FileSort.SIZE_DESC -> stringResource(R.string.browser_sort_size_desc)
        FileSort.DATE_ASC -> stringResource(R.string.browser_sort_date_asc)
        FileSort.DATE_DESC -> stringResource(R.string.browser_sort_date_desc)
    }

@Composable
private fun FilterChipsRow(
    current: FileFilter,
    onPick: (FileFilter) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(FileFilter.entries, key = { it.name }) { filter ->
            FilterChip(
                selected = current == filter,
                onClick = { onPick(filter) },
                label = {
                    Text(
                        stringResource(
                            when (filter) {
                                FileFilter.ALL -> R.string.browser_filter_all
                                FileFilter.IMAGES -> R.string.browser_filter_images
                                FileFilter.VIDEOS -> R.string.browser_filter_videos
                                FileFilter.AUDIO -> R.string.browser_filter_audio
                                FileFilter.DOCUMENTS -> R.string.browser_filter_docs
                                FileFilter.ARCHIVES -> R.string.browser_filter_archives
                            },
                        ),
                    )
                },
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
}
