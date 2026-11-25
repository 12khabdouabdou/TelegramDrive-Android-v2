package com.tgdrive.feature.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.isTextPreviewable
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.designsystem.editorial.EditorialEmptyState
import com.tgdrive.designsystem.theme.TgTypography
import com.tgdrive.feature.upload.DecryptFileDialog
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(
    onBack: () -> Unit,
    onOpenZip: (Long) -> Unit = {},
    onOpenVideo: (Long, Long) -> Unit = { _, _ -> },
    onOpenImage: (Long, Long) -> Unit = { _, _ -> },
    onOpenPdf: (Long, Long) -> Unit = { _, _ -> },
    onOpenText: (Long, Long) -> Unit = { _, _ -> },
    onOpenLocalVideo: (String) -> Unit = {},
    onOpenLocalPdf: (String, String) -> Unit = { _, _ -> },
    onOpenLocalText: (String, String) -> Unit = { _, _ -> },
    onPlayLocalAudio: (String, String) -> Unit = { _, _ -> },
    vm: VaultViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarMsg by vm.snackbar.collectAsStateWithLifecycle()
    val decryptedOpenEvent by vm.decryptedOpenEvent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbarHostState = remember { SnackbarHostState() }
    val selectionMode = state.selectedIds.isNotEmpty()
    var decryptedImageViewerPath by remember { mutableStateOf<String?>(null) }
    var showBatchDecryptDialog by remember { mutableStateOf(false) }
    var promptFired by remember { mutableStateOf(false) }

    val bioTitle = stringResource(R.string.vault_biometric_title)
    val bioSubtitle = stringResource(R.string.vault_biometric_subtitle)
    val noAppMsg = stringResource(R.string.browser_no_app_error)

    DisposableEffect(context) {
        val window = context.findActivity()?.window
        val hadSecure = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (!hadSecure) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (!hadSecure) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    LaunchedEffect(state.ready, state.biometricRequired, state.isUnlocked) {
        if (state.ready && state.biometricRequired && !state.isUnlocked && !promptFired) {
            promptFired = true
            context.findFragmentActivity()?.let { activity ->
                vm.authenticate(activity, bioTitle, bioSubtitle)
            }
        }
    }

    LaunchedEffect(snackbarMsg) {
        val msg = snackbarMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
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
            isVideo -> onOpenLocalVideo(file.absolutePath)
            isAudio -> onPlayLocalAudio(file.absolutePath, ev.outcome.displayName)
            isPdf -> onOpenLocalPdf(file.absolutePath, ev.outcome.displayName)
            isText -> onOpenLocalText(file.absolutePath, ev.outcome.displayName)
            else -> {
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, ev.outcome.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (t: Throwable) {
                    Log.w("VaultScreen: failed opening decrypted file", t)
                    snackbarHostState.showSnackbar(noAppMsg)
                }
            }
        }
    }

    BackHandler(enabled = selectionMode || decryptedImageViewerPath != null) {
        when {
            decryptedImageViewerPath != null -> decryptedImageViewerPath = null
            selectionMode -> vm.clearSelection()
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
                    if (selectionMode) {
                        Text(
                            text = stringResource(R.string.browser_selected_count, state.selectedIds.size),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Column {
                            Text(
                                text = stringResource(R.string.vault_title),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.isUnlocked && state.items.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.vault_items_count, state.items.size),
                                    style = TgTypography.Caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (selectionMode) vm.clearSelection() else onBack() }) {
                        Icon(
                            imageVector = if (selectionMode) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.browser_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    if (state.isUnlocked) {
                        if (selectionMode) {
                            IconButton(onClick = vm::selectAll) {
                                Icon(
                                    Icons.Filled.SelectAll,
                                    contentDescription = stringResource(R.string.browser_action_select_all),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            val hasLocked = state.items.any {
                                it.messageId in state.selectedIds && it.isLocked
                            }
                            if (hasLocked) {
                                IconButton(onClick = vm::unhideSelected) {
                                    Icon(
                                        Icons.Filled.Visibility,
                                        contentDescription = stringResource(R.string.vault_unhide_selected),
                                        tint = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                            val hasTgenc = state.items.any {
                                it.messageId in state.selectedIds && BackupCrypto.isEncryptedFileName(it.fileName)
                            }
                            if (hasTgenc) {
                                IconButton(onClick = { showBatchDecryptDialog = true }) {
                                    Icon(
                                        Icons.Filled.LockOpen,
                                        contentDescription = stringResource(R.string.browser_batch_decrypt),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        } else if (state.biometricRequired) {
                            IconButton(onClick = { vm.lock() }) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = stringResource(R.string.vault_lock),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            state.batchProgress?.let { (done, total) ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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

            when {
                !state.ready -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                state.biometricRequired && !state.isUnlocked -> {
                    VaultLockGate(
                        error = state.error,
                        onUnlock = {
                            vm.clearError()
                            context.findFragmentActivity()?.let { activity ->
                                vm.authenticate(activity, bioTitle, bioSubtitle)
                            }
                        },
                    )
                }
                state.items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EditorialEmptyState(
                            icon = Icons.Filled.Lock,
                            title = stringResource(R.string.vault_empty_title),
                            description = stringResource(R.string.vault_empty_desc),
                        )
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.items, key = { "${it.chatId}_${it.messageId}" }) { item ->
                            val thumbBytes = state.decryptedThumbs[item.chatId to item.messageId]
                            VaultGridCard(
                                item = item,
                                decryptedThumbBytes = thumbBytes,
                                isSelected = item.messageId in state.selectedIds,
                                onClick = {
                                    if (selectionMode) {
                                        vm.toggleSelection(item.messageId)
                                    } else {
                                        vm.openUnlockedVaultItem(
                                            item = item,
                                            onOpenZip = onOpenZip,
                                            onOpenVideo = onOpenVideo,
                                            onOpenImage = onOpenImage,
                                            onOpenPdf = onOpenPdf,
                                            onOpenText = onOpenText,
                                        )
                                    }
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    vm.toggleSelection(item.messageId)
                                },
                            )
                        }
                    }
                }
            }
        }
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

    decryptedImageViewerPath?.let { path ->
        com.tgdrive.feature.player.ImageViewer(
            imagePaths = listOf(path),
            onDismiss = { decryptedImageViewerPath = null },
        )
    }
}

@Composable
private fun VaultLockGate(
    error: String?,
    onUnlock: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Fingerprint,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.vault_locked_title),
            style = TgTypography.Display.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.vault_locked_desc),
            style = TgTypography.Display.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = TgTypography.Caption, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onUnlock) {
            Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.vault_unlock))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultGridCard(
    item: FileCacheEntity,
    decryptedThumbBytes: ByteArray?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val innerName = remember(item.fileName) { BackupCrypto.stripEncryptedExtension(item.fileName) }
    val bucket = remember(innerName) { classifyFile(null, innerName) }
    val (icon, tint) = when (bucket) {
        FileBucket.IMAGES -> Icons.Filled.Image to MaterialTheme.colorScheme.secondary
        FileBucket.VIDEOS -> Icons.Filled.Movie to MaterialTheme.colorScheme.primary
        FileBucket.AUDIO -> Icons.Filled.MusicNote to MaterialTheme.colorScheme.tertiary
        FileBucket.PDF -> Icons.Filled.PictureAsPdf to MaterialTheme.colorScheme.error
        FileBucket.ARCHIVES -> Icons.Filled.FolderZip to MaterialTheme.colorScheme.tertiary
        else -> Icons.Filled.InsertDriveFile to MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = Modifier
            .aspectRatio(0.78f)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            )
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                } else {
                    Modifier
                },
            )
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
                val plainThumbFile = remember(item.thumbnailPath) {
                    item.thumbnailPath
                        ?.takeIf { !it.endsWith(".tgethumb", ignoreCase = true) }
                        ?.let(::File)
                        ?.takeIf { it.isFile && it.length() > 0L }
                }
                if (decryptedThumbBytes != null) {
                    AsyncImage(
                        model = decryptedThumbBytes,
                        contentDescription = innerName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (plainThumbFile != null) {
                    AsyncImage(
                        model = plainThumbFile,
                        contentDescription = innerName,
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
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                    }
                }
                if (item.isLocked && !item.isEncryptedVault && !BackupCrypto.isEncryptedFileName(item.fileName)) {
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = stringResource(R.string.vault_hidden_badge),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(16.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f))
                        .padding(4.dp),
                ) {
                    Icon(
                        if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = innerName,
                style = TgTypography.Display.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = item.fileSize.toHumanSize(),
                style = TgTypography.Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun BatchDecryptPassphraseDialog(
    count: Int,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = stringResource(R.string.browser_batch_decrypt_title, count),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.browser_batch_decrypt_desc),
                    style = TgTypography.Display.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.browser_batch_decrypt_passphrase_hint)) },
                    singleLine = true,
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(
                                if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(passphrase.trim().ifBlank { null }) }) {
                Text(stringResource(R.string.browser_batch_decrypt_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.browser_cancel))
            }
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
