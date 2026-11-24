package com.tgdrive.feature.browser

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.zip.ZipEntryInfo
import com.tgdrive.designsystem.components.EmptyState as DsEmptyState
import com.tgdrive.designsystem.components.GlassPanel
import com.tgdrive.designsystem.theme.TgTypography
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface ZipBrowserItem {
    val sortKey: String

    data class FolderNode(
        val name: String,
        val fullPrefix: String,
        val descendantCount: Int,
        val encrypted: Boolean,
    ) : ZipBrowserItem {
        override val sortKey: String = name.lowercase()
    }

    data class FileNode(
        val displayName: String,
        val entry: ZipEntryInfo,
    ) : ZipBrowserItem {
        override val sortKey: String = displayName.lowercase()
    }
}

/**
 * In-app ZIP file browser with hierarchical folder navigation, entry previews,
 * in-session password memory, and FileProvider handoff.
 */
@Composable
fun ZipBrowserScreen(
    messageId: Long,
    onBack: () -> Unit,
    chatId: Long = 0L,
    initialEntryPath: String = "",
    onOpenLocalVideo: (String) -> Unit = {},
    onOpenLocalPdf: (String, String) -> Unit = { _, _ -> },
    onOpenLocalText: (String, String) -> Unit = { _, _ -> },
    onPlayLocalAudio: (String, String) -> Unit = { _, _ -> },
    vm: ZipBrowserViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val downloadState by vm.downloadManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val downloadItem =
        remember(chatId, messageId, downloadState.items) {
            downloadState.items.find {
                it.id == messageId && (chatId == 0L || it.chatId == 0L || it.chatId == chatId)
            }
        }
    val localPath =
        remember(state.resolvedLocalPath, downloadItem) {
            state.resolvedLocalPath
                ?: downloadItem?.takeIf { it.status == DownloadStatus.DONE }?.localPath
        }

    var currentPath by remember(localPath, initialEntryPath) {
        mutableStateOf(
            initialEntryPath.substringBeforeLast('/', "").let {
                if (it.isNotEmpty()) "$it/" else ""
            },
        )
    }
    var searchQuery by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var showPasswordDialog by remember { mutableStateOf<String?>(null) }
    var passwordForExtractAll by remember { mutableStateOf(false) }
    var imageViewerPath by remember { mutableStateOf<String?>(null) }

    val noViewerMsg = stringResource(R.string.zip_browser_no_viewer)

    BackHandler(enabled = (searchQuery.isNotEmpty() || currentPath.isNotEmpty()) && imageViewerPath == null) {
        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
        } else {
            currentPath = parentZipPrefix(currentPath)
        }
    }

    LaunchedEffect(chatId, messageId) {
        vm.ensureArchiveQueued(chatId, messageId)
    }

    LaunchedEffect(localPath) {
        if (localPath != null && !state.zipReady) {
            vm.load(
                zipPath = localPath,
                chatId = chatId,
                messageId = messageId,
                zipFileName = downloadItem?.fileName.orEmpty(),
            )
        }
    }

    // Show extraction failures or status messages as snackbars while keeping the entry list intact.
    LaunchedEffect(state.error, state.zipReady) {
        val err = state.error ?: return@LaunchedEffect
        if (state.zipReady) {
            vm.clearError()
            snackbarHostState.showSnackbar(err)
        }
    }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        vm.clearStatusMessage()
        snackbarHostState.showSnackbar(msg)
    }

    LaunchedEffect(state.extractedPath) {
        val path = state.extractedPath ?: return@LaunchedEffect
        vm.clearExtractedPath()
        val file = File(path)
        when (val route = routeForExtractedFile(file.name, path)) {
            is ExtractedFileRoute.ImageView -> imageViewerPath = route.localPath
            is ExtractedFileRoute.VideoView -> onOpenLocalVideo(route.localPath)
            is ExtractedFileRoute.AudioPlay -> onPlayLocalAudio(route.localPath, route.fileName)
            is ExtractedFileRoute.PdfView -> onOpenLocalPdf(route.localPath, route.fileName)
            is ExtractedFileRoute.TextView -> onOpenLocalText(route.localPath, route.fileName)
            is ExtractedFileRoute.External -> {
                if (!openExternal(context, file, route.mimeType)) {
                    scope.launch { snackbarHostState.showSnackbar(noViewerMsg) }
                }
            }
        }
    }

    val visibleItems =
        remember(state.entries, currentPath, searchQuery) {
            val q = searchQuery.trim()
            if (q.isBlank()) {
                buildDirectoryItems(state.entries, currentPath)
            } else {
                val tokens = com.tgdrive.feature.browser.search.SmartSearchEngine.tokenize(q)
                state.entries
                    .filter { !it.isDirectory }
                    .filter { entry ->
                        val lower = entry.name.lowercase()
                        lower.contains(q.lowercase()) ||
                            (tokens.isNotEmpty() && tokens.all { lower.contains(it) })
                    }
                    .map { entry ->
                        ZipBrowserItem.FileNode(
                            displayName = entry.name,
                            entry = entry,
                        )
                    }
                    .sortedBy { it.sortKey }
            }
        }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            )
        },
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.zip_browser_back),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(
                            text = stringResource(R.string.zip_browser_title),
                            style = TgTypography.Display.displayMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.zip_browser_entries_count, state.entries.size),
                            style = TgTypography.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.zipReady && state.entries.isNotEmpty()) {
                        if (state.extractingAll) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp).size(22.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = {
                                passwordForExtractAll = false
                                vm.extractAllToDownloads(
                                    onNeedPassword = {
                                        passwordForExtractAll = true
                                        showPasswordDialog = currentPath.ifBlank {
                                            downloadItem?.fileName ?: "archive.zip"
                                        }
                                    },
                                )
                            }) {
                                Icon(
                                    Icons.Filled.Download,
                                    contentDescription = stringResource(R.string.zip_browser_extract_all),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                // In-ZIP live search bar
                if (state.zipReady) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(stringResource(R.string.zip_browser_search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.browser_clear))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                // Multi-part split archive banner (.z01/.z02/.. + .zip)
                if (state.zipReady && state.splitPartsTotal > 1 && state.splitMergedPath == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.reassemblingSplit) {
                                    stringResource(
                                        R.string.zip_split_banner_progress,
                                        state.splitPartsDownloaded,
                                        state.splitPartsTotal,
                                    )
                                } else {
                                    stringResource(R.string.zip_split_banner_title, state.splitPartsTotal)
                                },
                                style = TgTypography.Display.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            if (state.reassemblingSplit && state.splitPartsTotal > 0) {
                                LinearProgressIndicator(
                                    progress = {
                                        (state.splitPartsDownloaded.toFloat() / state.splitPartsTotal.toFloat())
                                            .coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                )
                            }
                        }
                        if (!state.reassemblingSplit) {
                            TextButton(onClick = { vm.downloadAndReassembleSplitArchive() }) {
                                Text(
                                    stringResource(R.string.zip_split_banner_action),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }

                // Breadcrumbs when inside a subfolder (and not searching)
                if (state.zipReady && currentPath.isNotEmpty() && searchQuery.isBlank()) {
                    BreadcrumbBar(
                        currentPath = currentPath,
                        onSelectPrefix = { currentPath = it },
                    )
                }

                val loadError = state.error
                when {
                    state.loading -> LoadingState()
                    !state.zipReady && loadError != null ->
                        LoadErrorState(
                            error = loadError,
                            onRetry = if (localPath != null) {
                                { vm.load(localPath) }
                            } else {
                                null
                            },
                            onDismiss = {
                                vm.clearError()
                                onBack()
                            },
                        )
                    !state.zipReady && localPath == null && downloadItem?.status == DownloadStatus.FAILED ->
                        DownloadFailedState(
                            error = downloadItem.errorMessage,
                            onRetry = { vm.downloadManager.enqueue(downloadItem) },
                        )
                    !state.zipReady && localPath == null -> {
                        val clamped = downloadItem?.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                        DownloadingState(
                            fileName = downloadItem?.fileName ?: "#$messageId",
                            progress = clamped,
                            hasKnownSize = (downloadItem?.totalBytes ?: 0L) > 0L,
                        )
                    }
                    state.entries.isEmpty() && state.zipReady ->
                        ArchiveEmptyState(stringResource(R.string.zip_browser_empty))
                    visibleItems.isEmpty() && state.zipReady ->
                        ArchiveEmptyState(stringResource(R.string.zip_browser_folder_empty))
                    else ->
                        EntriesList(
                            items = visibleItems,
                            extractingEntry = state.extractingEntry,
                            zipPath = localPath,
                            unlockedPassword = state.unlockedPassword,
                            onFolderClick = { folder -> currentPath = folder.fullPrefix },
                            onFileClick = { fileNode ->
                                val entry = fileNode.entry
                                passwordForExtractAll = false
                                vm.openOrPromptEntry(entry) {
                                    showPasswordDialog = entry.name
                                }
                            },
                        )
                }
            }

            imageViewerPath?.let { path ->
                com.tgdrive.feature.player.ImageViewer(
                    imagePaths = listOf(path),
                    onDismiss = { imageViewerPath = null },
                )
            }

            showPasswordDialog?.let { entryName ->
                PasswordDialog(
                    entryName = entryName,
                    password = passwordInput,
                    hasSavedBackupPassphrase = state.hasSavedBackupPassphrase,
                    onPasswordChange = { passwordInput = it },
                    onUnlockWithVaultKey = {
                        showPasswordDialog = null
                        passwordInput = ""
                        if (passwordForExtractAll) {
                            passwordForExtractAll = false
                            vm.unlockWithVaultKey(entryName = null)
                        } else {
                            vm.unlockWithVaultKey(entryName = entryName)
                        }
                    },
                    onUnlockWithSavedPassphrase = {
                        showPasswordDialog = null
                        passwordInput = ""
                        if (passwordForExtractAll) {
                            passwordForExtractAll = false
                            vm.unlockWithVaultKey(entryName = null, useSavedBackupPassphrase = true)
                        } else {
                            vm.unlockWithVaultKey(entryName = entryName, useSavedBackupPassphrase = true)
                        }
                    },
                    onConfirm = {
                        val pwd = passwordInput.ifBlank { null }
                        passwordInput = ""
                        showPasswordDialog = null
                        if (passwordForExtractAll) {
                            passwordForExtractAll = false
                            vm.extractAllToDownloads(pwd)
                        } else {
                            vm.extractEntry(entryName, pwd)
                        }
                    },
                    onDismiss = {
                        passwordInput = ""
                        passwordForExtractAll = false
                        showPasswordDialog = null
                    },
                )
            }
        }
    }
}

private fun parentZipPrefix(prefix: String): String {
    val trimmed = prefix.trimEnd('/')
    val idx = trimmed.lastIndexOf('/')
    return if (idx < 0) "" else trimmed.substring(0, idx + 1)
}

private fun buildDirectoryItems(
    entries: List<ZipEntryInfo>,
    currentPrefix: String,
): List<ZipBrowserItem> {
    val folders = linkedMapOf<String, MutableList<ZipEntryInfo>>()
    val explicitFolderMeta = mutableMapOf<String, ZipEntryInfo>()
    val files = mutableListOf<ZipBrowserItem.FileNode>()

    for (entry in entries) {
        val norm = entry.name.trimStart('/')
        if (norm.isEmpty()) continue
        if (currentPrefix.isNotEmpty() && !norm.startsWith(currentPrefix)) continue
        val rel = norm.removePrefix(currentPrefix).trimEnd('/')
        if (rel.isEmpty()) continue

        val slashIdx = rel.indexOf('/')
        if (slashIdx >= 0) {
            val childName = rel.substring(0, slashIdx)
            val childPrefix = "$currentPrefix$childName/"
            folders.getOrPut(childPrefix) { mutableListOf() }.add(entry)
        } else if (entry.isDirectory) {
            val childPrefix = "$currentPrefix$rel/"
            explicitFolderMeta[childPrefix] = entry
            folders.getOrPut(childPrefix) { mutableListOf() }
        } else {
            files.add(ZipBrowserItem.FileNode(displayName = rel, entry = entry))
        }
    }

    val folderNodes =
        folders.entries.map { (prefix, descendants) ->
            val display = prefix.trimEnd('/').substringAfterLast('/')
            val meta = explicitFolderMeta[prefix]
            val itemCount = descendants.count { !it.isDirectory }
            ZipBrowserItem.FolderNode(
                name = display,
                fullPrefix = prefix,
                descendantCount = itemCount,
                encrypted = meta?.isEncrypted == true || descendants.any { it.isEncrypted },
            )
        }.sortedBy { it.sortKey }

    val sortedFiles = files.sortedBy { it.sortKey }
    return folderNodes + sortedFiles
}

@Composable
private fun BreadcrumbBar(
    currentPath: String,
    onSelectPrefix: (String) -> Unit,
) {
    val segments = remember(currentPath) {
        currentPath.trimEnd('/').split('/').filter { it.isNotEmpty() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.zip_browser_root),
            style = TgTypography.Caption,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onSelectPrefix("") }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        var acc = ""
        segments.forEachIndexed { idx, seg ->
            acc += "$seg/"
            val targetPrefix = acc
            val isLast = idx == segments.lastIndex
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = seg,
                style = TgTypography.Caption,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isLast) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !isLast) { onSelectPrefix(targetPrefix) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

private fun openExternal(
    context: android.content.Context,
    file: File,
    mimeType: String,
): Boolean {
    return try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.w("ZipBrowser: failed to launch external viewer for ${file.name}", t)
        false
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.zip_browser_reading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun DownloadingState(
    fileName: String,
    progress: Float,
    hasKnownSize: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.zip_browser_downloading, fileName),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium,
            )
            if (hasKnownSize || progress > 0f) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth(0.7f).clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = TgTypography.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.zip_browser_download_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = TgTypography.Caption,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun DownloadFailedState(
    error: String?,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.zip_browser_download_failed),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = TgTypography.Display.bodySmall,
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.zip_browser_retry))
            }
        }
    }
}

@Composable
private fun LoadErrorState(
    error: String,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.zip_browser_error_title),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = error,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.zip_browser_dismiss_button))
                }
                if (onRetry != null) {
                    Button(onClick = onRetry) {
                        Text(stringResource(R.string.zip_browser_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchiveEmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        DsEmptyState(
            icon = Icons.Filled.FolderOpen,
            title = message,
            description = "",
        )
    }
}

@Composable
private fun EntriesList(
    items: List<ZipBrowserItem>,
    extractingEntry: String?,
    zipPath: String?,
    unlockedPassword: String?,
    onFolderClick: (ZipBrowserItem.FolderNode) -> Unit,
    onFileClick: (ZipBrowserItem.FileNode) -> Unit,
) {
    val context = LocalContext.current
    val thumbnailGenerator = remember(context) { ZipThumbnailGenerator(cacheDir = context.cacheDir) }
    val thumbSemaphore = remember { Semaphore(3) }
    val bottomNavPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    DisposableEffect(thumbnailGenerator) {
        onDispose { thumbnailGenerator.clear() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = bottomNavPad + 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { item ->
            when (item) {
                is ZipBrowserItem.FolderNode -> "dir:${item.fullPrefix}"
                is ZipBrowserItem.FileNode -> "file:${item.entry.name}"
            }
        }) { item ->
            when (item) {
                is ZipBrowserItem.FolderNode ->
                    ZipFolderRow(
                        folder = item,
                        onClick = { onFolderClick(item) },
                    )
                is ZipBrowserItem.FileNode ->
                    ZipEntryRow(
                        displayName = item.displayName,
                        entry = item.entry,
                        isExtracting = extractingEntry == item.entry.name,
                        zipPath = zipPath,
                        password = unlockedPassword,
                        showThumbnail = !item.entry.isEncrypted || unlockedPassword != null,
                        thumbnailGenerator = thumbnailGenerator,
                        thumbSemaphore = thumbSemaphore,
                        onClick = { onFileClick(item) },
                    )
            }
        }
    }
}

@Composable
private fun ZipFolderRow(
    folder: ZipBrowserItem.FolderNode,
    onClick: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.zip_browser_folder_items, folder.descendantCount),
                    style = TgTypography.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (folder.encrypted) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.zip_browser_encrypted_icon),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ZipEntryRow(
    displayName: String,
    entry: ZipEntryInfo,
    isExtracting: Boolean,
    zipPath: String?,
    password: String?,
    showThumbnail: Boolean,
    thumbnailGenerator: ZipThumbnailGenerator,
    thumbSemaphore: Semaphore,
    onClick: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EntryLeading(
                entry = entry,
                zipPath = zipPath,
                password = password,
                showThumbnail = showThumbnail,
                thumbnailGenerator = thumbnailGenerator,
                thumbSemaphore = thumbSemaphore,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = buildString {
                    append(entry.uncompressedSize.toHumanSize())
                    if (entry.compressedSize > 0 && entry.uncompressedSize > 0) {
                        val ratio = 100 - (entry.compressedSize * 100 / entry.uncompressedSize).toInt()
                        if (ratio in 1..99) append(" · −$ratio%")
                    }
                }
                Text(
                    text = subtitle,
                    style = TgTypography.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (entry.isEncrypted) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.zip_browser_encrypted_icon),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (isExtracting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun EntryLeading(
    entry: ZipEntryInfo,
    zipPath: String?,
    password: String?,
    showThumbnail: Boolean,
    thumbnailGenerator: ZipThumbnailGenerator,
    thumbSemaphore: Semaphore,
) {
    var thumbnail by remember(entry.name, zipPath, password) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var thumbnailTried by remember(entry.name, zipPath, password) { mutableStateOf(false) }

    LaunchedEffect(entry.name, zipPath, showThumbnail, password) {
        if (!showThumbnail || zipPath == null || thumbnailTried) return@LaunchedEffect
        thumbnailTried = true
        val bmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                thumbSemaphore.withPermit {
                    thumbnailGenerator.getThumbnail(zipPath, entry.name, password)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("ZipBrowser: thumbnail decode failed", t)
                null
            }
        }
        thumbnail = bmp
    }

    val isVideo = remember(entry.name) {
        entry.name.substringAfterLast('.', "").lowercase() in ZipThumbnailGenerator.VIDEO_EXTS
    }

    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = thumbnail
        when {
            bmp != null -> {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (isVideo) {
                    Box(
                        modifier = Modifier
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
            }
            else ->
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
        }
    }
}

@Composable
private fun PasswordDialog(
    entryName: String,
    password: String,
    hasSavedBackupPassphrase: Boolean = false,
    onPasswordChange: (String) -> Unit,
    onUnlockWithVaultKey: () -> Unit = {},
    onUnlockWithSavedPassphrase: () -> Unit = {},
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                stringResource(R.string.zip_browser_password_required_title),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(
                        R.string.zip_browser_password_required_desc,
                        entryName.substringAfterLast('/'),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = TgTypography.Display.bodySmall,
                )
                OutlinedButton(
                    onClick = onUnlockWithVaultKey,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Filled.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.zip_browser_unlock_vault))
                }
                if (hasSavedBackupPassphrase) {
                    OutlinedButton(
                        onClick = onUnlockWithSavedPassphrase,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.zip_browser_use_saved_passphrase))
                    }
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.zip_browser_password_label)) },
                    singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                imageVector = if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (reveal) R.string.zip_browser_password_hide else R.string.zip_browser_password_show,
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.zip_browser_extract_button)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.zip_browser_cancel_button)) }
        },
    )
}
