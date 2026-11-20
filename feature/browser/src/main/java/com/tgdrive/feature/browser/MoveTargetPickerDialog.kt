package com.tgdrive.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tgdrive.core.common.Log
import com.tgdrive.core.telegram.TelegramFolder
import com.tgdrive.designsystem.theme.TgTypography
import kotlinx.coroutines.CancellationException

@Composable
fun MoveTargetPickerDialog(
    excludeChatId: Long? = null,
    loadFolders: suspend () -> List<TelegramFolder>,
    onPick: (TelegramFolder) -> Unit,
    onDismiss: () -> Unit,
) {
    var folders by remember { mutableStateOf<List<TelegramFolder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(excludeChatId) {
        try {
            folders = loadFolders().filter { excludeChatId == null || it.chatId != excludeChatId }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("MoveTargetPicker: loadFolders failed", t)
            error = t.message ?: "Failed"
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Text(
                stringResource(R.string.move_picker_title),
                style = TgTypography.Display.titleMedium,
            )
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth()) {
                when {
                    loading ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                stringResource(R.string.move_picker_loading),
                                style = TgTypography.Display.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    error != null ->
                        Text(error!!, style = TgTypography.Display.bodySmall, color = MaterialTheme.colorScheme.error)
                    folders.isEmpty() ->
                        Text(
                            stringResource(R.string.move_picker_empty),
                            style = TgTypography.Display.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else ->
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(folders, key = { it.chatId }) { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .clickable { onPick(folder) }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            if (folder.isSelf) Icons.Filled.CloudSync else Icons.Filled.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 12.dp),
                                    ) {
                                        Text(
                                            folder.title,
                                            style = TgTypography.Display.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            stringResource(
                                                when {
                                                    folder.isSelf -> R.string.move_picker_type_saved
                                                    folder.isChannel -> R.string.move_picker_type_channel
                                                    else -> R.string.move_picker_type_chat
                                                },
                                            ),
                                            style = TgTypography.Display.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.browser_cancel))
            }
        },
    )
}
