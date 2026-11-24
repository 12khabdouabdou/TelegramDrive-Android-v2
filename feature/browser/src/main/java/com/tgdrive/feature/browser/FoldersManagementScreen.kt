package com.tgdrive.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.db.entity.FolderEntity
import com.tgdrive.designsystem.editorial.EditorialEmptyState
import com.tgdrive.designsystem.editorial.EditorialHeader
import com.tgdrive.designsystem.editorial.SectionLabel
import com.tgdrive.designsystem.theme.TgShapes
import com.tgdrive.designsystem.theme.TgTypography

private val FOLDER_COLOR_PALETTE = listOf(
    0xFF2D8CF0.toInt(),
    0xFF00C9B1.toInt(),
    0xFFF5A623.toInt(),
    0xFFE05252.toInt(),
    0xFF8B5CF6.toInt(),
    0xFF4CAF7D.toInt(),
    0xFFEC4899.toInt(),
    0xFF06B6D4.toInt(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersManagementScreen(
    onOpenFolder: (Long, String) -> Unit,
    onOpenTrash: () -> Unit = {},
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showCreateSheet by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FolderEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<FolderEntity?>(null) }
    var colorTarget by remember { mutableStateOf<FolderEntity?>(null) }
    var menuForFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

    val savedTitle = stringResource(R.string.browser_saved_messages)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.folders_create))
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { vm.refreshFolders() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            EditorialHeader(title = stringResource(R.string.folders_title))
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    Icons.Filled.Sort,
                                    contentDescription = stringResource(R.string.folders_sort),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                listOf(
                                    FolderListSort.DEFAULT to R.string.folders_sort_default,
                                    FolderListSort.NAME_ASC to R.string.folders_sort_name,
                                    FolderListSort.SIZE_DESC to R.string.folders_sort_size,
                                    FolderListSort.FILES_DESC to R.string.folders_sort_files,
                                ).forEach { (sortOpt, labelId) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(labelId)) },
                                        trailingIcon = {
                                            if (state.folderSort == sortOpt) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        },
                                        onClick = {
                                            vm.setFolderSort(sortOpt)
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                state.error?.let { err ->
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
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

                // Saved Messages (always visible even when custom channels list is empty)
                item {
                    val ready = state.savedMessagesChatId != 0L
                    SectionLabel(
                        text = savedTitle,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    FolderManagementRow(
                        title = savedTitle,
                        subtitle = if (ready) {
                            stringResource(R.string.browser_saved_messages_subtitle)
                        } else {
                            stringResource(R.string.browser_connecting)
                        },
                        icon = Icons.Filled.Bookmark,
                        tint = MaterialTheme.colorScheme.tertiary,
                        enabled = ready,
                        onClick = { onOpenFolder(state.savedMessagesChatId, savedTitle) },
                    )
                }

                // 30-day Trash bin shortcut
                item {
                    FolderManagementRow(
                        title = stringResource(R.string.folders_trash_title),
                        subtitle = if (state.trashCount > 0) {
                            stringResource(R.string.folders_trash_count, state.trashCount)
                        } else {
                            stringResource(R.string.folders_trash_empty)
                        },
                        icon = Icons.Filled.Delete,
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onOpenTrash,
                    )
                }

                item {
                    SectionLabel(text = stringResource(R.string.browser_your_folders))
                }

                if (state.folders.isEmpty() && !state.loading) {
                    item {
                        EditorialEmptyState(
                            icon = Icons.Filled.Folder,
                            title = stringResource(R.string.browser_empty_folders),
                            description = stringResource(R.string.browser_empty_folders_desc),
                            actionLabel = stringResource(R.string.folders_create),
                            onAction = { showCreateSheet = true },
                        )
                    }
                } else {
                    items(state.folders, key = { it.id }) { folder ->
                        val size = state.folderSizes[folder.channelId] ?: 0L
                        val isPinned = folder.channelId in state.pinnedFolderChatIds
                        Box {
                            FolderManagementRow(
                                title = folder.title,
                                subtitle = if (size > 0) {
                                    stringResource(R.string.browser_channel_size_subtitle, size.toHumanSize())
                                } else {
                                    stringResource(R.string.browser_channel_subtitle)
                                },
                                icon = Icons.Filled.Folder,
                                tint = Color(folder.color),
                                isPinned = isPinned,
                                onClick = { onOpenFolder(folder.channelId, folder.title) },
                                onMoreClick = { menuForFolder = folder },
                            )
                            DropdownMenu(
                                expanded = menuForFolder == folder,
                                onDismissRequest = { menuForFolder = null },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (isPinned) R.string.folders_unpin else R.string.folders_pin,
                                            ),
                                        )
                                    },
                                    onClick = {
                                        menuForFolder = null
                                        vm.togglePinFolder(folder.channelId)
                                    },
                                    leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.folders_rename)) },
                                    onClick = {
                                        menuForFolder = null
                                        renameTarget = folder
                                    },
                                    leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.folders_change_color)) },
                                    onClick = {
                                        menuForFolder = null
                                        colorTarget = folder
                                    },
                                    leadingIcon = {
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(folder.color)),
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.folders_delete)) },
                                    onClick = {
                                        menuForFolder = null
                                        deleteTarget = folder
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        com.tgdrive.feature.folders.CreateFolderSheet(onDismiss = { showCreateSheet = false })
    }

    colorTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { colorTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    stringResource(R.string.folders_pick_color_title),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FOLDER_COLOR_PALETTE.chunked(4).forEach { rowColors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            rowColors.forEach { hex ->
                                val selected = folder.color == hex
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color(hex))
                                        .then(
                                            if (selected) {
                                                Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .clickable {
                                            vm.setFolderColor(folder, hex)
                                            colorTarget = null
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (selected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { colorTarget = null }) {
                    Text(stringResource(R.string.browser_cancel))
                }
            },
        )
    }

    renameTarget?.let { folder ->
        var newTitle by remember(folder.id) { mutableStateOf(folder.title) }
        val trimmed = newTitle.trim()
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    stringResource(R.string.folders_rename_title),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text(stringResource(R.string.folders_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = trimmed.isNotEmpty() && trimmed != folder.title,
                    onClick = {
                        vm.renameFolder(folder, trimmed)
                        renameTarget = null
                    },
                ) { Text(stringResource(R.string.folders_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.browser_cancel))
                }
            },
        )
    }

    deleteTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    stringResource(R.string.folders_delete_title),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    stringResource(R.string.folders_delete_body, folder.title),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    vm.deleteFolder(folder)
                }) { Text(stringResource(R.string.folders_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.browser_cancel))
                }
            },
        )
    }
}

@Composable
private fun FolderManagementRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    isPinned: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
        ) {
            Text(
                text = title,
                style = TgTypography.Display.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = TgTypography.Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isPinned) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = null,
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
                )
            }
        }
    }
}
