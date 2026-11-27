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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.DisposableEffect
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
import coil.request.ImageRequest
import coil.size.Size
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
import com.tgdrive.core.download.EncryptedThumbRequest
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
import com.tgdrive.feature.upload.DocumentScannerDialog
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
    onOpenPdf: (Long, Long) -> Unit = { _, _ -> },
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
    var showScannerDialog by remember { mutableStateOf(false) }
    var showNewSubfolderDialog by remember { mutableStateOf(false) }
    var renameFileTarget by remember { mutableStateOf<FileCacheEntity?>(null) }
    var deleteSubfolderTarget by remember { mutableStateOf<SubfolderDeleteStats?>(null) }
    var renameSubfolderTarget by remember { mutableStateOf<String?>(null) }
    var showMoveChoiceDialog by remember { mutableStateOf(false) }
    var showSubfolderMovePicker by remember { mutableStateOf(false) }
    var showSetFolderLockDialog by remember { mutableStateOf(false) }
    var showRemoveFolderLockDialog by remember { mutableStateOf(false) }
    var removeFolderLockError by remember { mutableStateOf<String?>(null) }
    var lockPromptFired by remember(chatId) { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val folderLockTitle = stringResource(R.string.folders_lock_gate_title)
    val folderLockSubtitle = stringResource(R.string.folders_lock_gate_subtitle, title.ifBlank { stringResource(R.string.browser_folders) })

    DisposableEffect(context, state.isFolderLockedByPref) {
        val window = context.findFolderActivity()?.window
        val hadSecure = ((window?.attributes?.flags ?: 0) and android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (state.isFolderLockedByPref && !hadSecure) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (state.isFolderLockedByPref && !hadSecure) {
                window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    DisposableEffect(lifecycleOwner, state.isFolderLockRequired) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && state.isFolderLockRequired) {
                lockPromptFired = false
                vm.lockFolderView()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.folderLockReady, state.isFolderLockRequired, state.isFolderUnlocked, state.canUseBiometricLock) {
        if (state.folderLockReady && state.isFolderLockRequired && !state.isFolderUnlocked && state.canUseBiometricLock && !lockPromptFired) {
            lockPromptFired = true
            context.findFolderFragmentActivity()?.let { activity ->
                vm.authenticateFolder(activity, folderLockTitle, folderLockSubtitle)
            }
        }
    }

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

    BackHandler(enabled = selectionMode || searchOpen || state.currentVirtualPath.isNotEmpty()) {
        when {
            selectionMode -> vm.clearSelection()
            searchOpen -> {
                searchOpen = false
                vm.setSearch("")
            }
            state.currentVirtualPath.isNotEmpty() -> {
                vm.navigateUpVirtualFolder()
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
                        IconButton(onClick = { vm.exportSelectedAsLocalZip() }) {
                            Icon(
                                Icons.Filled.FolderZip,
                                contentDescription = stringResource(R.string.browser_export_zip),
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        IconButton(onClick = {
                            moveFromDetails = false
                            showMoveChoiceDialog = true
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
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.browser_action_refresh),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
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
                        if (state.folderLockReady) {
                            IconButton(
                                onClick = {
                                    if (state.isFolderLockedByPref) {
                                        removeFolderLockError = null
                                        showRemoveFolderLockDialog = true
                                    } else if (state.hasAccessPassword || (state.accessLockBiometricEnabled && state.biometricAvailable)) {
                                        vm.setCurrentFolderLocked(true)
                                    } else {
                                        showSetFolderLockDialog = true
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (state.isFolderLockedByPref) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                    contentDescription = stringResource(
                                        if (state.isFolderLockedByPref) R.string.folders_unlock_remove else R.string.folders_lock,
                                    ),
                                    tint = if (state.isFolderLockedByPref) {
                                        MaterialTheme.colorScheme.tertiary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
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
            if (!selectionMode && (!state.isFolderLockRequired || state.isFolderUnlocked)) {
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
                            text = { Text(stringResource(R.string.browser_scan_document)) },
                            onClick = {
                                showUploadMenu = false
                                showScannerDialog = true
                            },
                            leadingIcon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
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
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_new_subfolder)) },
                            onClick = {
                                showUploadMenu = false
                                showNewSubfolderDialog = true
                            },
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (state.isFolderLockRequired && !state.isFolderUnlocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                AccessLockGate(
                    title = folderLockTitle,
                    subtitle = folderLockSubtitle,
                    hasPassword = state.hasAccessPassword,
                    canUseBiometric = state.canUseBiometricLock,
                    error = state.lockError,
                    onVerifyPassword = vm::unlockFolderWithPassword,
                    onUseBiometric = {
                        vm.clearLockError()
                        context.findFolderFragmentActivity()?.let { activity ->
                            vm.authenticateFolder(activity, folderLockTitle, folderLockSubtitle)
                        }
                    },
                )
            }
        } else {
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

            if (state.currentVirtualPath.isNotEmpty()) {
                VirtualBreadcrumbBar(
                    virtualPath = state.currentVirtualPath,
                    onNavigate = vm::openVirtualFolder,
                    onRefresh = vm::refresh,
                    onRenameCurrent = { renameSubfolderTarget = state.currentVirtualPath },
                    onDeleteCurrent = {
                        deleteSubfolderTarget = vm.computeSubfolderStats(state.currentVirtualPath)
                    },
                )
            }

            if (state.childVirtualFolders.isNotEmpty()) {
                VirtualSubfoldersRow(
                    currentVirtualPath = state.currentVirtualPath,
                    subfolders = state.childVirtualFolders,
                    thumbItemsByFolder = state.childVirtualFolderThumbs,
                    encryptedPreviewFingerprint = state.encryptedPreviewFingerprint,
                    thumbRefreshNonce = state.thumbRefreshNonce,
                    onOpenSubfolder = vm::openVirtualFolder,
                    onRenameSubfolder = { path -> renameSubfolderTarget = path },
                    onDeleteSubfolder = { path -> deleteSubfolderTarget = vm.computeSubfolderStats(path) },
                )
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
                        } else if (state.childVirtualFolders.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.browser_subfolder_empty_files_hint),
                                style = TgTypography.Display.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp),
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
                                encryptedPreviewFingerprint = state.encryptedPreviewFingerprint,
                                thumbRefreshNonce = state.thumbRefreshNonce,
                                selectedIds = state.selectedIds,
                                onRequestThumbnail = vm::requestThumbnail,
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
                                encryptedPreviewFingerprint = state.encryptedPreviewFingerprint,
                                thumbRefreshNonce = state.thumbRefreshNonce,
                                selectedIds = state.selectedIds,
                                onRequestThumbnail = vm::requestThumbnail,
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
    }

    if (showScannerDialog) {
        DocumentScannerDialog(
            onDismiss = { showScannerDialog = false },
            onPdfReady = { pdfUri ->
                showScannerDialog = false
                vm.prepareFileUploadReview(listOf(pdfUri))
            },
        )
    }

    if (state.pendingReviewFiles.isNotEmpty()) {
        FileUploadReviewDialog(
            initialFiles = state.pendingReviewFiles,
            onDismiss = vm::dismissFileUploadReview,
            onConfirm = vm::confirmFileReview,
            defaultWifiOnly = state.manualUploadWifiOnly,
            hasSavedPassphrase = state.hasSavedBackupPassphrase,
            onSavePassphrase = vm::saveVaultPassphrase,
        )
    }

    pendingNormalFolder?.let { (folderUri, folderName) ->
        FolderNormalUploadDialog(
            folderName = folderName,
            defaultWifiOnly = state.manualUploadWifiOnly,
            hasSavedPassphrase = state.hasSavedBackupPassphrase,
            onSavePassphrase = vm::saveVaultPassphrase,
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
            hasSavedPassphrase = state.hasSavedBackupPassphrase,
            onSavePassphrase = vm::saveVaultPassphrase,
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
            onSaveAsVaultPassphrase = vm::saveVaultPassphrase,
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
            onDownload = {
                vm.downloadFile(item)
                vm.showSnackbar(context.getString(R.string.browser_downloading_file, item.fileName))
            },
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
            onSaveNote = { note -> vm.updateUserNote(item, note) },
            onToggleLock = { vm.setItemLocked(item, !item.isLocked) },
            onRename = { renameFileTarget = item },
            onMoveSubfolder = {
                vm.clearSelection()
                vm.toggleSelection(item.messageId)
                moveFromDetails = true
                showSubfolderMovePicker = true
            },
            onDismiss = { detailsItem = null },
        )
    }

    if (showMoveChoiceDialog) {
        AlertDialog(
            onDismissRequest = {
                showMoveChoiceDialog = false
                if (moveFromDetails) {
                    vm.clearSelection()
                    moveFromDetails = false
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.browser_move_target_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showMoveChoiceDialog = false
                            showSubfolderMovePicker = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.browser_move_to_subfolder),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(
                        onClick = {
                            showMoveChoiceDialog = false
                            showMovePicker = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.browser_move_to_telegram_folder),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showMoveChoiceDialog = false
                    if (moveFromDetails) {
                        vm.clearSelection()
                        moveFromDetails = false
                    }
                }) {
                    Text(stringResource(R.string.browser_cancel))
                }
            },
        )
    }

    if (showSubfolderMovePicker) {
        var customPath by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showSubfolderMovePicker = false
                if (moveFromDetails) {
                    vm.clearSelection()
                    moveFromDetails = false
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.details_move_subfolder)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customPath,
                        onValueChange = { customPath = it },
                        label = { Text(stringResource(R.string.browser_new_subfolder_hint)) },
                        placeholder = { Text("Photos/2026") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            onClick = {
                                showSubfolderMovePicker = false
                                moveFromDetails = false
                                vm.moveSelectedToVirtualPath("")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.browser_root_folder),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        for (vp in state.allVirtualPaths) {
                            TextButton(
                                onClick = {
                                    showSubfolderMovePicker = false
                                    moveFromDetails = false
                                    vm.moveSelectedToVirtualPath(vp)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(vp, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = customPath.isNotBlank(),
                    onClick = {
                        showSubfolderMovePicker = false
                        moveFromDetails = false
                        vm.moveSelectedToVirtualPath(customPath)
                    },
                ) {
                    Text(stringResource(R.string.browser_action_move))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSubfolderMovePicker = false
                    if (moveFromDetails) {
                        vm.clearSelection()
                        moveFromDetails = false
                    }
                }) {
                    Text(stringResource(R.string.browser_cancel))
                }
            },
        )
    }

    if (showNewSubfolderDialog) {
        var folderNameDraft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewSubfolderDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.browser_new_subfolder)) },
            text = {
                OutlinedTextField(
                    value = folderNameDraft,
                    onValueChange = { folderNameDraft = it },
                    label = { Text(stringResource(R.string.browser_new_subfolder_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = folderNameDraft.isNotBlank(),
                    onClick = {
                        vm.createVirtualFolder(folderNameDraft)
                        showNewSubfolderDialog = false
                    },
                ) {
                    Text(stringResource(R.string.folders_create_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewSubfolderDialog = false }) {
                    Text(stringResource(R.string.browser_cancel))
                }
            },
        )
    }

    renameSubfolderTarget?.let { oldPath ->
        val oldLeaf = oldPath.substringAfterLast('/')
        var draft by remember(oldPath) { mutableStateOf(oldLeaf) }
        val trimmed = draft.trim()
        AlertDialog(
            onDismissRequest = { renameSubfolderTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.browser_subfolder_rename_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.browser_new_subfolder_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = trimmed.isNotEmpty() && trimmed != oldLeaf,
                    onClick = {
                        vm.renameVirtualFolder(oldPath, trimmed)
                        renameSubfolderTarget = null
                    },
                ) {
                    Text(stringResource(R.string.folders_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameSubfolderTarget = null }) {
                    Text(stringResource(R.string.browser_cancel))
                }
            },
        )
    }

    deleteSubfolderTarget?.let { stats ->
        AlertDialog(
            onDismissRequest = { deleteSubfolderTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.browser_subfolder_delete_title, stats.displayName)) },
            text = {
                Text(
                    if (stats.fileCount == 0 && stats.nestedSubfolderCount == 0) {
                        stringResource(R.string.browser_subfolder_delete_empty_desc)
                    } else {
                        stringResource(
                            R.string.browser_subfolder_delete_desc,
                            stats.fileCount,
                            stats.nestedSubfolderCount,
                        )
                    },
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (stats.fileCount > 0) {
                        TextButton(
                            onClick = {
                                val targetPath = stats.virtualPath
                                deleteSubfolderTarget = null
                                vm.deleteVirtualFolder(targetPath, permanent = false)
                            },
                        ) {
                            Text(stringResource(R.string.browser_subfolder_move_trash))
                        }
                        TextButton(
                            onClick = {
                                val targetPath = stats.virtualPath
                                deleteSubfolderTarget = null
                                vm.deleteVirtualFolder(targetPath, permanent = true)
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.browser_subfolder_delete_forever),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        TextButton(
                            onClick = {
                                val targetPath = stats.virtualPath
                                deleteSubfolderTarget = null
                                vm.deleteVirtualFolder(targetPath, permanent = false)
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.browser_subfolder_remove_empty),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteSubfolderTarget = null }) {
                    Text(stringResource(R.string.browser_cancel))
                }
            },
        )
    }

    renameFileTarget?.let { target ->
        var draft by remember(target.messageId) { mutableStateOf(target.fileName) }
        val trimmed = draft.trim()
        AlertDialog(
            onDismissRequest = { renameFileTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.details_rename_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = trimmed.isNotEmpty() && trimmed != target.fileName,
                    onClick = {
                        vm.renameFile(target.messageId, trimmed)
                        renameFileTarget = null
                    },
                ) {
                    Text(stringResource(R.string.folders_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameFileTarget = null }) {
                    Text(stringResource(R.string.browser_cancel))
                }
            },
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

    if (showSetFolderLockDialog) {
        SetAccessPasswordDialog(
            title = stringResource(R.string.access_lock_setup_title),
            subtitle = stringResource(R.string.access_lock_setup_subtitle),
            hasExistingPassword = state.hasAccessPassword,
            biometricAvailable = state.biometricAvailable,
            biometricEnabled = state.accessLockBiometricEnabled,
            onSave = { newPass, bioEnabled ->
                vm.configureFolderAccessLock(
                    newPassword = newPass,
                    biometricEnabled = bioEnabled,
                    lockCurrentFolder = true,
                )
                showSetFolderLockDialog = false
            },
            onDismiss = { showSetFolderLockDialog = false },
        )
    }

    if (showRemoveFolderLockDialog) {
        val folderDisplayTitle = title.ifBlank { stringResource(R.string.browser_folders) }
        val wrongPwdMsg = stringResource(R.string.access_lock_wrong_password)
        ConfirmAccessUnlockDialog(
            title = stringResource(R.string.folders_lock_remove_title),
            subtitle = stringResource(R.string.folders_lock_remove_subtitle, folderDisplayTitle),
            hasPassword = state.hasAccessPassword,
            canUseBiometric = state.canUseBiometricLock,
            error = removeFolderLockError,
            onVerifyPassword = { candidate ->
                if (vm.verifyAccessPasswordSync(candidate)) {
                    vm.setCurrentFolderLocked(false)
                    showRemoveFolderLockDialog = false
                    removeFolderLockError = null
                } else {
                    removeFolderLockError = wrongPwdMsg
                }
            },
            onUseBiometric = {
                context.findFolderFragmentActivity()?.let { act ->
                    vm.authenticateRemoveLock(
                        activity = act,
                        title = context.getString(R.string.folders_lock_remove_title),
                        subtitle = context.getString(R.string.folders_lock_remove_subtitle, folderDisplayTitle),
                        onSuccess = {
                            vm.setCurrentFolderLocked(false)
                            showRemoveFolderLockDialog = false
                            removeFolderLockError = null
                        },
                        onError = { msg -> removeFolderLockError = msg },
                    )
                }
            },
            onDismiss = {
                showRemoveFolderLockDialog = false
                removeFolderLockError = null
            },
        )
    }
}

private tailrec fun Context.findFolderActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findFolderActivity()
    else -> null
}

private tailrec fun Context.findFolderFragmentActivity(): androidx.fragment.app.FragmentActivity? = when (this) {
    is androidx.fragment.app.FragmentActivity -> this
    is android.content.ContextWrapper -> baseContext.findFolderFragmentActivity()
    else -> null
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
    hasSavedPassphrase: Boolean = false,
    onSavePassphrase: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (NormalUploadOptions) -> Unit,
) {
    var conflict by remember { mutableStateOf(UploadConflictStrategy.KEEP_BOTH) }
    var encrypt by remember { mutableStateOf(false) }
    var useSavedPassphrase by remember(hasSavedPassphrase) { mutableStateOf(hasSavedPassphrase) }
    var saveAsPassVault by remember { mutableStateOf(false) }
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
                            selected = useSavedPassphrase,
                            enabled = hasSavedPassphrase,
                            onClick = {
                                if (hasSavedPassphrase) {
                                    useSavedPassphrase = true
                                    error = null
                                }
                            },
                            label = { Text("PassVault passphrase") },
                        )
                        FilterChip(
                            selected = !useSavedPassphrase,
                            onClick = {
                                useSavedPassphrase = false
                                error = null
                            },
                            label = { Text("Custom password") },
                        )
                    }
                    if (!useSavedPassphrase) {
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
                        if (!hasSavedPassphrase && onSavePassphrase != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .toggleable(
                                        value = saveAsPassVault,
                                        role = Role.Checkbox,
                                        onValueChange = {
                                            saveAsPassVault = it
                                            error = null
                                        },
                                    ),
                            ) {
                                Checkbox(checked = saveAsPassVault, onCheckedChange = null)
                                Text(
                                    "Save as my PassVault passphrase",
                                    style = TgTypography.Caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
                    encrypt && !useSavedPassphrase && password.length < 4 -> error = "Use at least 4 characters"
                    encrypt && !useSavedPassphrase && password != confirm -> error = "Passwords don't match"
                    encrypt && !useSavedPassphrase && saveAsPassVault && password.trim().length < 8 ->
                        error = "Use at least 8 characters to save as PassVault passphrase"
                    else -> {
                        if (encrypt && !useSavedPassphrase && saveAsPassVault) {
                            onSavePassphrase?.invoke(password.trim())
                        }
                        onConfirm(
                            NormalUploadOptions(
                                conflictStrategy = conflict,
                                encryption = UploadEncryptionSpec(
                                    enabled = encrypt,
                                    useSavedPassphrase = useSavedPassphrase,
                                    customPassphrase = if (encrypt && !useSavedPassphrase) password else null,
                                ),
                                wifiOnly = wifiOnly,
                            ),
                        )
                    }
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
    encryptedPreviewFingerprint: String? = null,
    thumbRefreshNonce: Int = 0,
    selectedIds: Set<Long>,
    onRequestThumbnail: (FileCacheEntity, Boolean) -> Unit = { _, _ -> },
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
        modifier = Modifier.fillMaxSize(),
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.messageId }, contentType = { "file_grid_cell" }) { item ->
            FileGridCell(
                item = item,
                encryptedPreviewFingerprint = encryptedPreviewFingerprint,
                thumbRefreshNonce = thumbRefreshNonce,
                isSelected = item.messageId in selectedIds,
                onRequestThumbnail = { force -> onRequestThumbnail(item, force) },
                onClick = { onClick(item.messageId) },
                onLongClick = { onLongClick(item.messageId) },
                onMoreClick = onMoreClick?.let { { it(item.messageId) } },
            )
        }
        if (isLoadingMore) {
            item(span = { GridItemSpan(2) }, contentType = "loading_footer") {
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
    encryptedPreviewFingerprint: String? = null,
    thumbRefreshNonce: Int = 0,
    isSelected: Boolean,
    onRequestThumbnail: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val tint = tintFor(item.mimeType, item.fileName)
    val isEnc = remember(item) {
        item.isEncryptedVault ||
            BackupCrypto.isEncryptedFileName(item.fileName) ||
            item.thumbnailPath?.endsWith(".tgethumb", ignoreCase = true) == true
    }
    val needsInitialThumb = remember(item.thumbnailPath, isEnc, encryptedPreviewFingerprint) {
        val p = item.thumbnailPath
        if (isEnc) {
            encryptedPreviewFingerprint != null &&
                (p.isNullOrBlank() || !p.endsWith(".tgethumb", ignoreCase = true))
        } else {
            p.isNullOrBlank() || p.contains("/minithumbs/")
        }
    }
    LaunchedEffect(
        item.chatId,
        item.messageId,
        item.thumbnailPath,
        encryptedPreviewFingerprint,
        thumbRefreshNonce,
        needsInitialThumb,
    ) {
        if (needsInitialThumb || (thumbRefreshNonce > 0 && (!isEnc || encryptedPreviewFingerprint != null))) {
            onRequestThumbnail(thumbRefreshNonce > 0 && isEnc)
        }
    }
    var loadFailed by remember(
        item.chatId,
        item.messageId,
        item.thumbnailPath,
        encryptedPreviewFingerprint,
        thumbRefreshNonce,
    ) { mutableStateOf(false) }
    val encThumbRequest =
        remember(item.chatId, item.messageId, item.thumbnailPath, isEnc, encryptedPreviewFingerprint) {
        if (isEnc && encryptedPreviewFingerprint != null) {
            EncryptedThumbRequest(
                chatId = item.chatId,
                messageId = item.messageId,
                localPath = item.thumbnailPath,
                passphraseFingerprint = encryptedPreviewFingerprint,
            )
        } else {
            null
        }
    }
    val thumbRequest = remember(item.thumbnailPath, isEnc, context) {
        if (isEnc) {
            null
        } else {
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
    }
    val hasImage = !loadFailed && (encThumbRequest != null || thumbRequest != null)
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
                val model: Any? = if (loadFailed) null else (encThumbRequest ?: thumbRequest)
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = item.fileName,
                        contentScale = ContentScale.Crop,
                        onError = {
                            loadFailed = true
                            onRequestThumbnail(true)
                        },
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
                            tint = if (hasImage) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
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
    encryptedPreviewFingerprint: String? = null,
    thumbRefreshNonce: Int = 0,
    selectedIds: Set<Long>,
    onRequestThumbnail: (FileCacheEntity, Boolean) -> Unit = { _, _ -> },
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items, key = { it.messageId }, contentType = { "file_list_row" }) { item ->
            FileListRow(
                item = item,
                encryptedPreviewFingerprint = encryptedPreviewFingerprint,
                thumbRefreshNonce = thumbRefreshNonce,
                isSelected = item.messageId in selectedIds,
                onRequestThumbnail = { force -> onRequestThumbnail(item, force) },
                onClick = { onClick(item.messageId) },
                onLongClick = { onLongClick(item.messageId) },
                onMoreClick = onMoreClick?.let { { it(item.messageId) } },
            )
        }
        if (isLoadingMore) {
            item(contentType = "loading_footer") {
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
    encryptedPreviewFingerprint: String? = null,
    thumbRefreshNonce: Int = 0,
    isSelected: Boolean,
    onRequestThumbnail: (Boolean) -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val tint = tintFor(item.mimeType, item.fileName)
    val isEnc = remember(item) {
        item.isEncryptedVault ||
            BackupCrypto.isEncryptedFileName(item.fileName) ||
            item.thumbnailPath?.endsWith(".tgethumb", ignoreCase = true) == true
    }
    val needsInitialThumb = remember(item.thumbnailPath, isEnc, encryptedPreviewFingerprint) {
        val p = item.thumbnailPath
        if (isEnc) {
            encryptedPreviewFingerprint != null &&
                (p.isNullOrBlank() || !p.endsWith(".tgethumb", ignoreCase = true))
        } else {
            p.isNullOrBlank() || p.contains("/minithumbs/")
        }
    }
    LaunchedEffect(
        item.chatId,
        item.messageId,
        item.thumbnailPath,
        encryptedPreviewFingerprint,
        thumbRefreshNonce,
        needsInitialThumb,
    ) {
        if (needsInitialThumb || (thumbRefreshNonce > 0 && (!isEnc || encryptedPreviewFingerprint != null))) {
            onRequestThumbnail(thumbRefreshNonce > 0 && isEnc)
        }
    }
    var loadFailed by remember(
        item.chatId,
        item.messageId,
        item.thumbnailPath,
        encryptedPreviewFingerprint,
        thumbRefreshNonce,
    ) { mutableStateOf(false) }
    val encThumbRequest =
        remember(item.chatId, item.messageId, item.thumbnailPath, isEnc, encryptedPreviewFingerprint) {
        if (isEnc && encryptedPreviewFingerprint != null) {
            EncryptedThumbRequest(
                chatId = item.chatId,
                messageId = item.messageId,
                localPath = item.thumbnailPath,
                passphraseFingerprint = encryptedPreviewFingerprint,
            )
        } else {
            null
        }
    }
    val thumbRequest = remember(item.thumbnailPath, isEnc, context) {
        if (isEnc) {
            null
        } else {
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
            val rowThumbModel: Any? = if (loadFailed) null else (encThumbRequest ?: thumbRequest)
            if (isSelected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            } else if (rowThumbModel != null) {
                AsyncImage(
                    model = rowThumbModel,
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    onError = {
                        loadFailed = true
                        onRequestThumbnail(true)
                    },
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
    onOpenPdf: (Long, Long) -> Unit,
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
            onOpenPdf(item.chatId, messageId)
        }
        isTextPreviewable(item.mimeType, item.fileName) -> {
            if (localPath == null) vm.downloadFile(item)
            onOpenText(item.chatId, messageId)
        }
        bucket == FileBucket.AUDIO -> {
            vm.playAudioFromFolder(item)
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

@Composable
private fun VirtualBreadcrumbBar(
    virtualPath: String,
    onNavigate: (String) -> Unit,
    onRefresh: () -> Unit = {},
    onRenameCurrent: () -> Unit = {},
    onDeleteCurrent: () -> Unit = {},
) {
    val segments = virtualPath.split('/').filter { it.isNotEmpty() }
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { onNavigate("") },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(stringResource(R.string.browser_root_folder), fontWeight = FontWeight.SemiBold)
            }
            var cumulative = ""
            for ((idx, seg) in segments.withIndex()) {
                cumulative = if (cumulative.isEmpty()) seg else "$cumulative/$seg"
                val targetPath = cumulative
                val isLast = idx == segments.lastIndex
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                TextButton(
                    onClick = { if (!isLast) onNavigate(targetPath) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = seg,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                        color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_action_refresh)) },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRefresh()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.browser_subfolder_rename)) },
                    leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRenameCurrent()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.browser_subfolder_delete),
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
                        menuOpen = false
                        onDeleteCurrent()
                    },
                )
            }
        }
    }
}

@Composable
private fun SubfolderCoverTile(
    thumb: SubfolderThumbItem,
    encryptedPreviewFingerprint: String?,
    thumbRefreshNonce: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var loadFailed by remember(thumb, encryptedPreviewFingerprint, thumbRefreshNonce) { mutableStateOf(false) }
    val model = remember(thumb, encryptedPreviewFingerprint, loadFailed, context) {
        if (loadFailed) {
            null
        } else if (thumb.isEncrypted) {
            if (encryptedPreviewFingerprint != null) {
                val req = EncryptedThumbRequest(
                    chatId = thumb.chatId,
                    messageId = thumb.messageId,
                    localPath = thumb.localPath,
                    passphraseFingerprint = encryptedPreviewFingerprint,
                )
                ImageRequest.Builder(context)
                    .data(req)
                    .size(Size(120, 120))
                    .crossfade(false)
                    .build()
            } else {
                null
            }
        } else {
            thumb.localPath?.takeIf { it.isNotBlank() }?.let { path ->
                ImageRequest.Builder(context)
                    .data(File(path))
                    .size(Size(120, 120))
                    .crossfade(false)
                    .build()
            }
        }
    }
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { loadFailed = true },
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VirtualSubfoldersRow(
    currentVirtualPath: String,
    subfolders: List<Pair<String, Int>>,
    thumbItemsByFolder: Map<String, List<SubfolderThumbItem>> = emptyMap(),
    encryptedPreviewFingerprint: String? = null,
    thumbRefreshNonce: Int = 0,
    onOpenSubfolder: (String) -> Unit,
    onRenameSubfolder: (String) -> Unit = {},
    onDeleteSubfolder: (String) -> Unit = {},
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(subfolders, key = { it.first }, contentType = { "virtual_subfolder_chip" }) { (name, count) ->
            val target = if (currentVirtualPath.isEmpty()) name else "$currentVirtualPath/$name"
            val thumbItems = thumbItemsByFolder[name].orEmpty()
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .combinedClickable(
                            onClick = { onOpenSubfolder(target) },
                            onLongClick = { menuOpen = true },
                        )
                        .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (thumbItems.size >= 2) {
                        Column(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                thumbItems.getOrNull(0)?.let { item ->
                                    SubfolderCoverTile(
                                        thumb = item,
                                        encryptedPreviewFingerprint = encryptedPreviewFingerprint,
                                        thumbRefreshNonce = thumbRefreshNonce,
                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                    )
                                }
                                thumbItems.getOrNull(1)?.let { item ->
                                    SubfolderCoverTile(
                                        thumb = item,
                                        encryptedPreviewFingerprint = encryptedPreviewFingerprint,
                                        thumbRefreshNonce = thumbRefreshNonce,
                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                    )
                                }
                            }
                            if (thumbItems.size > 2) {
                                Row(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                                ) {
                                    thumbItems.getOrNull(2)?.let { item ->
                                        SubfolderCoverTile(
                                            thumb = item,
                                            encryptedPreviewFingerprint = encryptedPreviewFingerprint,
                                            thumbRefreshNonce = thumbRefreshNonce,
                                            modifier = Modifier.weight(1f).fillMaxSize(),
                                        )
                                    }
                                    thumbItems.getOrNull(3)?.let { item ->
                                        SubfolderCoverTile(
                                            thumb = item,
                                            encryptedPreviewFingerprint = encryptedPreviewFingerprint,
                                            thumbRefreshNonce = thumbRefreshNonce,
                                            modifier = Modifier.weight(1f).fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }
                    } else if (thumbItems.size == 1) {
                        SubfolderCoverTile(
                            thumb = thumbItems[0],
                            encryptedPreviewFingerprint = encryptedPreviewFingerprint,
                            thumbRefreshNonce = thumbRefreshNonce,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column {
                        Text(
                            text = name,
                            style = TgTypography.Display.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.browser_subfolder_items, count),
                            style = TgTypography.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_subfolder_rename)) },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRenameSubfolder(target)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.browser_subfolder_delete),
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
                            menuOpen = false
                            onDeleteSubfolder(target)
                        },
                    )
                }
            }
        }
    }
}
