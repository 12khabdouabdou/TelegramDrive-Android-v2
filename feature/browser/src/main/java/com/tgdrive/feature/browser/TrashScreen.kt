package com.tgdrive.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.db.entity.TrashEntryEntity
import com.tgdrive.designsystem.editorial.EditorialEmptyState
import com.tgdrive.designsystem.theme.TgTypography
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    vm: TrashViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showEmptyConfirm by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = stringResource(R.string.trash_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.browser_back),
                        )
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        TextButton(
                            enabled = !state.busy,
                            onClick = { showEmptyConfirm = true },
                        ) {
                            Text(
                                text = stringResource(R.string.trash_empty_all),
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (state.busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.trash_retention_hint),
                    style = TgTypography.Display.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    EditorialEmptyState(
                        icon = Icons.Filled.DeleteOutline,
                        title = stringResource(R.string.trash_empty_title),
                        description = stringResource(R.string.trash_empty_desc),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = state.items,
                        key = { "${it.chatId}_${it.messageId}" },
                    ) { entry ->
                        TrashEntryRow(
                            entry = entry,
                            daysLeft = vm.daysRemaining(entry),
                            enabled = !state.busy,
                            onRestore = { vm.restore(entry) },
                            onDeleteForever = { vm.deleteForever(entry) },
                        )
                    }
                }
            }
        }
    }

    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    stringResource(R.string.trash_empty_confirm_title),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    stringResource(R.string.trash_empty_confirm_msg, state.items.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEmptyConfirm = false
                        vm.emptyTrash()
                    },
                ) {
                    Text(
                        stringResource(R.string.trash_empty_all),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirm = false }) {
                    Text(stringResource(R.string.browser_cancel))
                }
            },
        )
    }
}

@Composable
private fun TrashEntryRow(
    entry: TrashEntryEntity,
    daysLeft: Int,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbFile = remember(entry.thumbnailPath) {
            entry.thumbnailPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbFile != null) {
                AsyncImage(
                    model = thumbFile,
                    contentDescription = entry.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = trashIconFor(entry.mimeType, entry.fileName),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                text = entry.fileName,
                style = TgTypography.Display.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entry.fileSize.toHumanSize(),
                    style = TgTypography.Mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.trash_days_left, daysLeft),
                    style = TgTypography.Caption,
                    color = if (daysLeft <= 3) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }

        IconButton(onClick = onRestore, enabled = enabled) {
            Icon(
                Icons.Filled.RestoreFromTrash,
                contentDescription = stringResource(R.string.trash_restore),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onDeleteForever, enabled = enabled) {
            Icon(
                Icons.Filled.DeleteForever,
                contentDescription = stringResource(R.string.trash_delete_forever),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun trashIconFor(mime: String?, fileName: String): ImageVector =
    when (classifyFile(mime, fileName)) {
        FileBucket.IMAGES -> Icons.Filled.Image
        FileBucket.VIDEOS -> Icons.Filled.Movie
        FileBucket.AUDIO -> Icons.Filled.AudioFile
        FileBucket.PDF -> Icons.Filled.PictureAsPdf
        FileBucket.ARCHIVES -> Icons.Filled.FolderZip
        FileBucket.DOCUMENTS -> Icons.Filled.Description
        FileBucket.OTHER -> Icons.Filled.InsertDriveFile
    }
