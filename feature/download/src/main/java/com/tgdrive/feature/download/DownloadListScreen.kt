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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
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
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.designsystem.components.EmptyState
import com.tgdrive.designsystem.components.GlassPanel
import com.tgdrive.designsystem.theme.TgTypography
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DownloadListViewModel @Inject constructor(
    val downloadManager: DownloadManager,
) : ViewModel()

/**
 * Transfers screen displaying active and completed downloads with cancel,
 * retry, clear-completed, and tap-to-open via FileProvider.
 */
@Composable
fun DownloadListScreen(
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    vm: DownloadListViewModel = hiltViewModel(),
) {
    val state by vm.downloadManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showCompleted by remember { mutableStateOf(false) }

    val active = remember(state.items) {
        state.items.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
    }
    val completed = remember(state.items) {
        state.items.filter { it.status != DownloadStatus.DOWNLOADING && it.status != DownloadStatus.QUEUED }
    }
    val list = if (showCompleted) completed else active
    val openFailedMsg = stringResource(R.string.transfers_open_failed)
    val bottomNavPad = if (showBack) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    } else {
        0.dp
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
            // Header — statusBarsPadding only when outside MainBottomNav's Scaffold
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
                if (completed.isNotEmpty()) {
                    TextButton(onClick = { vm.downloadManager.clearCompleted() }) {
                        Text(
                            text = stringResource(R.string.transfers_clear_completed),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
            }

            // Accessible tabs
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
                    items(list, key = { it.id }) { item ->
                        TransferRow(
                            item = item,
                            onOpen = {
                                val path = item.localPath ?: return@TransferRow
                                val file = File(path)
                                if (!file.exists()) {
                                    scope.launch { snackbarHostState.showSnackbar(openFailedMsg) }
                                    return@TransferRow
                                }
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val ext = file.extension.lowercase()
                                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                } catch (t: Throwable) {
                                    Log.w("DownloadListScreen: open failed for ${file.name}", t)
                                    scope.launch { snackbarHostState.showSnackbar(openFailedMsg) }
                                }
                            },
                            onCancel = { vm.downloadManager.cancel(item.id) },
                            onRetry = { vm.downloadManager.enqueue(item) },
                        )
                    }
                }
            }
        }
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
private fun TransferRow(
    item: DownloadItem,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val canOpen = item.status == DownloadStatus.DONE && !item.localPath.isNullOrBlank()
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
                    Text(
                        text = item.fileName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                    DownloadStatus.DONE -> Unit
                }
            }

            if (item.status == DownloadStatus.DOWNLOADING) {
                val clamped = item.progress.coerceIn(0f, 1f)
                Spacer(Modifier.height(10.dp))
                if (item.totalBytes > 0L || clamped > 0f) {
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
                    Text(
                        text = "${item.downloadedBytes.toHumanSize()} / ${item.totalBytes.toHumanSize()} · ${(clamped * 100).toInt()}%",
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
        }
    }
}
