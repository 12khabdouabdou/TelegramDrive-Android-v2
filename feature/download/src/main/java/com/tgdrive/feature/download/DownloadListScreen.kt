package com.tgdrive.feature.download

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.isTextPreviewable
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.download.DecryptOutcome
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.UploadItem
import com.tgdrive.core.download.UploadManager
import com.tgdrive.core.download.UploadStatus
import com.tgdrive.core.download.UploadTracker
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.EncryptedFileFormat
import com.tgdrive.designsystem.components.EmptyState
import com.tgdrive.designsystem.components.GlassPanel
import com.tgdrive.designsystem.theme.TgTypography
import com.tgdrive.feature.upload.DecryptFileDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DownloadListViewModel @Inject constructor(
    val downloadManager: DownloadManager,
    val uploadTracker: UploadTracker,
    val uploadManager: UploadManager,
    private val backupCrypto: BackupCrypto,
) : ViewModel() {

    val hasSavedBackupPassphrase: Boolean
        get() = backupCrypto.hasCustomBackupPassphrase()

    fun detectEncryptedFormat(file: File): EncryptedFileFormat =
        uploadManager.detectEncryptedFormat(file)

    suspend fun decryptLocalFile(
        encryptedFile: File,
        originalDisplayName: String,
        passphrase: String?,
        saveToDownloads: Boolean,
    ): DecryptOutcome = uploadManager.decryptLocalFile(
        encryptedFile = encryptedFile,
        originalDisplayName = originalDisplayName,
        passphrase = passphrase,
        saveToDownloads = saveToDownloads,
    )
}

private sealed interface TransferRowUi {
    val stableKey: String
    val fileName: String
    val totalBytes: Long
    val transferredBytes: Long
    val progress: Float
    val speedBytesPerSec: Long
    val etaSeconds: Long?
    val isActive: Boolean
    val isFailed: Boolean

    data class Download(val item: DownloadItem) : TransferRowUi {
        override val stableKey: String = "dl_${item.chatId}_${item.id}"
        override val fileName: String = item.fileName
        override val totalBytes: Long = item.totalBytes
        override val transferredBytes: Long = item.downloadedBytes
        override val progress: Float = item.progress
        override val speedBytesPerSec: Long = item.speedBytesPerSec
        override val etaSeconds: Long? = item.etaSeconds
        override val isActive: Boolean =
            item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.QUEUED
        override val isFailed: Boolean = item.status == DownloadStatus.FAILED
    }

    data class Upload(val item: UploadItem) : TransferRowUi {
        override val stableKey: String = "ul_${item.id}"
        override val fileName: String = item.fileName
        override val totalBytes: Long = item.totalBytes
        override val transferredBytes: Long = item.uploadedBytes
        override val progress: Float = item.progress
        override val speedBytesPerSec: Long = item.speedBytesPerSec
        override val etaSeconds: Long? = item.etaSeconds
        override val isActive: Boolean = item.status.isInQueue
        override val isFailed: Boolean = item.status == UploadStatus.FAILED
    }
}

/**
 * Unified Transfers screen displaying active and completed uploads + downloads
 * with phase status, live transfer speed/ETA, cancel, retry, clear-completed,
 * `.tgenc` decryption, and tap-to-open via FileProvider.
 */
@Composable
fun DownloadListScreen(
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    onOpenLocalVideo: ((String) -> Unit)? = null,
    onOpenLocalPdf: ((String, String) -> Unit)? = null,
    onOpenLocalText: ((String, String) -> Unit)? = null,
    onPlayLocalAudio: ((String, String) -> Unit)? = null,
    vm: DownloadListViewModel = hiltViewModel(),
) {
    val downloadState by vm.downloadManager.state.collectAsStateWithLifecycle()
    val uploadState by vm.uploadTracker.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showCompleted by remember { mutableStateOf(false) }

    var decryptTarget by remember { mutableStateOf<DownloadItem?>(null) }
    var decryptInProgress by remember { mutableStateOf(false) }
    var decryptError by remember { mutableStateOf<String?>(null) }

    val allRows = remember(uploadState.items, downloadState.items) {
        uploadState.items.map { TransferRowUi.Upload(it) } +
            downloadState.items.map { TransferRowUi.Download(it) }
    }
    val active = remember(allRows) { allRows.filter { it.isActive } }
    val completed = remember(allRows) { allRows.filter { !it.isActive } }
    val hasRetryableFailedUploads = remember(completed) {
        completed.any { it is TransferRowUi.Upload && it.isFailed && it.item.canRetry }
    }
    val hasRunningUploads = remember(active) {
        active.any { it is TransferRowUi.Upload && it.item.status.isActive }
    }
    val hasPausedUploads = remember(active) {
        active.any { it is TransferRowUi.Upload && it.item.status == UploadStatus.PAUSED }
    }
    val list = if (showCompleted) completed else active
    val openFailedMsg = stringResource(R.string.transfers_open_failed)
    val savedDecryptedMsg = stringResource(R.string.transfers_decrypted_saved)
    val bottomNavPad = if (showBack) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    } else {
        0.dp
    }

    fun openLocalFile(file: File, mimeType: String?) {
        if (!file.exists()) {
            scope.launch { snackbarHostState.showSnackbar(openFailedMsg) }
            return
        }
        val ext = file.extension.lowercase()
        val resolvedMime = mimeType?.takeIf { it.isNotBlank() }
            ?: runCatching { MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext) }.getOrNull()
            ?: "*/*"
        val isVideo = resolvedMime.startsWith("video/", ignoreCase = true) ||
            ext in setOf("mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp", "ts", "flv")
        val isAudio = resolvedMime.startsWith("audio/", ignoreCase = true) ||
            ext in setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "aiff")
        val isPdf = resolvedMime.equals("application/pdf", ignoreCase = true) || ext == "pdf"
        val isText = isTextPreviewable(resolvedMime, file.name)
        when {
            isVideo && onOpenLocalVideo != null -> {
                onOpenLocalVideo(file.absolutePath)
                return
            }
            isAudio && onPlayLocalAudio != null -> {
                onPlayLocalAudio(file.absolutePath, file.name)
                return
            }
            isPdf && onOpenLocalPdf != null -> {
                onOpenLocalPdf(file.absolutePath, file.name)
                return
            }
            isText && onOpenLocalText != null -> {
                onOpenLocalText(file.absolutePath, file.name)
                return
            }
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w("DownloadListScreen: open failed for ${file.name}", t)
            scope.launch { snackbarHostState.showSnackbar(openFailedMsg) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = bottomNavPad),
            )
        },
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (showBack) Modifier.statusBarsPadding() else Modifier)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBack) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.transfers_back),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.transfers_title),
                    style = TgTypography.Display.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = if (showBack) 8.dp else 4.dp),
                )
                if (hasRunningUploads) {
                    TextButton(onClick = { vm.uploadManager.pauseAll() }) {
                        Text(stringResource(R.string.transfers_pause_all), maxLines = 1)
                    }
                    TextButton(onClick = { vm.uploadManager.cancelAll() }) {
                        Text(
                            text = stringResource(R.string.transfers_stop_all),
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    }
                } else if (hasPausedUploads) {
                    TextButton(onClick = { vm.uploadManager.resumeAll() }) {
                        Text(stringResource(R.string.transfers_resume_all), maxLines = 1)
                    }
                    TextButton(onClick = { vm.uploadManager.cancelAll() }) {
                        Text(
                            text = stringResource(R.string.transfers_stop_all),
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    }
                }
                if (hasRetryableFailedUploads) {
                    TextButton(onClick = { vm.uploadManager.retryAllFailed() }) {
                        Text(
                            text = stringResource(R.string.transfers_retry_failed),
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                        )
                    }
                }
                if (completed.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            vm.downloadManager.clearCompleted()
                            vm.uploadTracker.clearCompleted()
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.transfers_clear_completed),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabButton(
                    label = stringResource(R.string.transfers_tab_active_count, active.size),
                    selected = !showCompleted,
                    onClick = { showCompleted = false },
                    modifier = Modifier.weight(1f),
                )
                TabButton(
                    label = stringResource(R.string.transfers_tab_completed_count, completed.size),
                    selected = showCompleted,
                    onClick = { showCompleted = true },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))

            if (list.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.DownloadDone,
                    title = stringResource(
                        if (showCompleted) R.string.transfers_empty_completed else R.string.transfers_empty_active,
                    ),
                    description = stringResource(R.string.transfers_empty_hint),
                    modifier = Modifier.padding(bottom = bottomNavPad),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 4.dp,
                        end = 16.dp,
                        bottom = bottomNavPad + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(list, key = { it.stableKey }) { row ->
                        when (row) {
                            is TransferRowUi.Download -> {
                                val item = row.item
                                DownloadTransferRow(
                                    item = item,
                                    onOpen = {
                                        val path = item.localPath ?: return@DownloadTransferRow
                                        val file = File(path)
                                        if (!file.exists()) {
                                            scope.launch { snackbarHostState.showSnackbar(openFailedMsg) }
                                            return@DownloadTransferRow
                                        }
                                        if (BackupCrypto.isEncryptedFileName(item.fileName)) {
                                            decryptError = null
                                            decryptTarget = item
                                        } else {
                                            openLocalFile(file, null)
                                        }
                                    },
                                    onDecrypt = {
                                        decryptError = null
                                        decryptTarget = item
                                    },
                                    onCancel = { vm.downloadManager.cancel(item.chatId, item.id) },
                                    onRetry = { vm.downloadManager.enqueue(item) },
                                )
                            }
                            is TransferRowUi.Upload -> {
                                val item = row.item
                                UploadTransferRow(
                                    item = item,
                                    onPause = { vm.uploadManager.pause(item.id) },
                                    onResume = { vm.uploadManager.resume(item.id) },
                                    onCancel = { vm.uploadManager.cancel(item.id) },
                                    onRetry = { vm.uploadManager.retry(item.id) },
                                    onDismiss = { vm.uploadTracker.dismiss(item.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    decryptTarget?.let { target ->
        val localPath = target.localPath
        val localFile = localPath?.let(::File)
        val detectedFmt = localFile?.let { vm.detectEncryptedFormat(it) }
        DecryptFileDialog(
            fileName = target.fileName,
            detectedFormat = detectedFmt,
            hasSavedBackupPassphrase = vm.hasSavedBackupPassphrase,
            inProgress = decryptInProgress,
            errorMessage = decryptError,
            onDecryptAndOpen = { passphrase ->
                if (localFile == null || !localFile.isFile) {
                    decryptError = openFailedMsg
                    return@DecryptFileDialog
                }
                scope.launch {
                    decryptInProgress = true
                    decryptError = null
                    when (val outcome = vm.decryptLocalFile(localFile, target.fileName, passphrase, false)) {
                        is DecryptOutcome.Success -> {
                            decryptInProgress = false
                            decryptTarget = null
                            openLocalFile(outcome.file, outcome.mimeType)
                        }
                        is DecryptOutcome.WrongPassphrase -> {
                            decryptInProgress = false
                            decryptError = outcome.message
                        }
                        is DecryptOutcome.Failed -> {
                            decryptInProgress = false
                            decryptError = outcome.message
                        }
                    }
                }
            },
            onDecryptAndSave = { passphrase ->
                if (localFile == null || !localFile.isFile) {
                    decryptError = openFailedMsg
                    return@DecryptFileDialog
                }
                scope.launch {
                    decryptInProgress = true
                    decryptError = null
                    when (val outcome = vm.decryptLocalFile(localFile, target.fileName, passphrase, true)) {
                        is DecryptOutcome.Success -> {
                            decryptInProgress = false
                            decryptTarget = null
                            snackbarHostState.showSnackbar("$savedDecryptedMsg: ${outcome.displayName}")
                        }
                        is DecryptOutcome.WrongPassphrase -> {
                            decryptInProgress = false
                            decryptError = outcome.message
                        }
                        is DecryptOutcome.Failed -> {
                            decryptInProgress = false
                            decryptError = outcome.message
                        }
                    }
                }
            },
            onDismiss = {
                if (!decryptInProgress) {
                    decryptTarget = null
                    decryptError = null
                }
            },
        )
    }
}

@Composable
private fun TabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            )
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DownloadTransferRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onDecrypt: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val canOpen = item.status == DownloadStatus.DONE && !item.localPath.isNullOrBlank()
    val isEncrypted = BackupCrypto.isEncryptedFileName(item.fileName)
    val iconTint = when (item.status) {
        DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
        DownloadStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
        DownloadStatus.DONE -> MaterialTheme.colorScheme.secondary
        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
        DownloadStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusIcon = when (item.status) {
        DownloadStatus.DOWNLOADING -> Icons.Filled.Download
        DownloadStatus.QUEUED -> Icons.Filled.Schedule
        DownloadStatus.DONE -> Icons.Filled.CheckCircle
        DownloadStatus.FAILED -> Icons.Filled.ErrorOutline
        DownloadStatus.CANCELLED -> Icons.Filled.Cancel
    }
    val statusText = when (item.status) {
        DownloadStatus.QUEUED -> stringResource(R.string.transfers_status_queued)
        DownloadStatus.DOWNLOADING -> stringResource(R.string.transfers_status_downloading)
        DownloadStatus.DONE -> stringResource(R.string.transfers_status_done)
        DownloadStatus.FAILED -> item.errorMessage?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.transfers_status_failed)
        DownloadStatus.CANCELLED -> stringResource(R.string.transfers_status_cancelled)
    }

    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canOpen) Modifier.clickable(onClick = onOpen) else Modifier),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconTint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = item.fileName,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isEncrypted) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = stringResource(R.string.transfers_encrypted),
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    val meta = buildString {
                        append(statusText)
                        if (item.totalBytes > 0L) {
                            append(" · ")
                            append(item.totalBytes.toHumanSize())
                        }
                    }
                    Text(
                        text = meta,
                        style = TgTypography.Caption,
                        color = if (item.status == DownloadStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                when (item.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                        IconButton(onClick = onCancel) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.transfers_cancel),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                        IconButton(onClick = onRetry) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.transfers_retry),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    DownloadStatus.DONE -> {
                        if (isEncrypted && canOpen) {
                            IconButton(onClick = onDecrypt) {
                                Icon(
                                    Icons.Filled.LockOpen,
                                    contentDescription = stringResource(R.string.transfers_decrypt),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            if (item.status == DownloadStatus.DOWNLOADING) {
                TransferProgressBar(
                    progress = item.progress,
                    transferredBytes = item.downloadedBytes,
                    totalBytes = item.totalBytes,
                    speedBytesPerSec = item.speedBytesPerSec,
                    etaSeconds = item.etaSeconds,
                )
            }
        }
    }
}

@Composable
private fun UploadTransferRow(
    item: UploadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val iconTint = when (item.status) {
        UploadStatus.QUEUED,
        UploadStatus.PREPARING,
        UploadStatus.PACKING,
        UploadStatus.ENCRYPTING,
        UploadStatus.UPLOADING,
        -> MaterialTheme.colorScheme.primary
        UploadStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
        UploadStatus.DONE -> MaterialTheme.colorScheme.secondary
        UploadStatus.FAILED -> MaterialTheme.colorScheme.error
        UploadStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusIcon = when (item.status) {
        UploadStatus.QUEUED -> Icons.Filled.Schedule
        UploadStatus.PREPARING,
        UploadStatus.PACKING,
        UploadStatus.UPLOADING,
        -> Icons.Filled.CloudUpload
        UploadStatus.PAUSED -> Icons.Filled.Pause
        UploadStatus.ENCRYPTING -> Icons.Filled.Lock
        UploadStatus.DONE -> Icons.Filled.CloudDone
        UploadStatus.FAILED -> Icons.Filled.ErrorOutline
        UploadStatus.CANCELLED -> Icons.Filled.Cancel
    }
    val defaultStatusText = when (item.status) {
        UploadStatus.QUEUED -> stringResource(R.string.transfers_status_queued)
        UploadStatus.PREPARING -> stringResource(R.string.transfers_status_preparing)
        UploadStatus.PACKING -> stringResource(R.string.transfers_status_packing)
        UploadStatus.ENCRYPTING -> stringResource(R.string.transfers_status_encrypting)
        UploadStatus.UPLOADING -> stringResource(R.string.transfers_status_uploading)
        UploadStatus.PAUSED -> stringResource(R.string.transfers_status_paused)
        UploadStatus.DONE -> stringResource(R.string.transfers_upload_done)
        UploadStatus.FAILED -> item.errorMessage?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.transfers_status_failed)
        UploadStatus.CANCELLED -> stringResource(R.string.transfers_status_cancelled)
    }
    val statusText = when {
        item.status == UploadStatus.FAILED -> item.errorMessage?.takeIf { it.isNotBlank() } ?: defaultStatusText
        !item.statusDetail.isNullOrBlank() -> item.statusDetail!!
        else -> defaultStatusText
    }

    GlassPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconTint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = item.fileName,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (item.isEncrypted) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = stringResource(R.string.transfers_encrypted),
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    val meta = buildString {
                        append(statusText)
                        if (item.totalBytes > 0L) {
                            append(" · ")
                            append(item.totalBytes.toHumanSize())
                        }
                    }
                    Text(
                        text = meta,
                        style = TgTypography.Caption,
                        color = if (item.status == UploadStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (item.status.isActive) {
                    IconButton(onClick = onPause) {
                        Icon(
                            Icons.Filled.Pause,
                            contentDescription = stringResource(R.string.transfers_pause_upload),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.transfers_cancel_upload),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (item.status == UploadStatus.PAUSED) {
                    IconButton(onClick = onResume) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.transfers_resume_upload),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.transfers_cancel_upload),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    if ((item.status == UploadStatus.FAILED || item.status == UploadStatus.CANCELLED) && item.canRetry) {
                        IconButton(onClick = onRetry) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.transfers_retry),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.transfers_dismiss),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (item.status.isActive && item.status != UploadStatus.QUEUED) {
                TransferProgressBar(
                    progress = item.progress,
                    transferredBytes = item.uploadedBytes,
                    totalBytes = item.totalBytes,
                    speedBytesPerSec = item.speedBytesPerSec,
                    etaSeconds = item.etaSeconds,
                )
            }
        }
    }
}

@Composable
private fun TransferProgressBar(
    progress: Float,
    transferredBytes: Long,
    totalBytes: Long,
    speedBytesPerSec: Long,
    etaSeconds: Long?,
) {
    val clamped = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    Spacer(Modifier.height(10.dp))
    if (totalBytes > 0L || clamped > 0f) {
        LinearProgressIndicator(
            progress = { clamped },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.height(4.dp))
        val text = buildString {
            append("${transferredBytes.toHumanSize()} / ${totalBytes.toHumanSize()} · ${(clamped * 100).toInt()}%")
            if (speedBytesPerSec > 0L) {
                append(" · ${speedBytesPerSec.toHumanSize()}/s")
            }
            if (etaSeconds != null && etaSeconds > 0L) {
                append(" · ETA ${formatTransferEta(etaSeconds)}")
            }
        }
        Text(
            text = text,
            style = TgTypography.Mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        LinearProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
        )
    }
}

private fun formatTransferEta(seconds: Long): String {
    val hrs = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return when {
        hrs > 0L -> "${hrs}h ${mins}m"
        mins > 0L -> "${mins}m ${secs}s"
        else -> "${secs}s"
    }
}
