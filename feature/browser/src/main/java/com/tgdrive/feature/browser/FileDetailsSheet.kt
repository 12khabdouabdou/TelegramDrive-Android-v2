package com.tgdrive.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.common.toLocalShort
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.designsystem.editorial.EditorialKeyValueRow
import com.tgdrive.designsystem.theme.TgShapes
import com.tgdrive.designsystem.theme.TgTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailsSheet(
    item: FileCacheEntity,
    isDownloaded: Boolean,
    onDownload: () -> Unit,
    onCopyToDownloads: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onToggleStar: () -> Unit,
    isPinnedOffline: Boolean = item.isPinnedOffline,
    onTogglePinOffline: (() -> Unit)? = null,
    onShareLink: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val (headerIcon, headerTint) = when (classifyFile(item.mimeType, item.fileName)) {
        FileBucket.IMAGES -> Icons.Filled.Image to MaterialTheme.colorScheme.secondary
        FileBucket.VIDEOS -> Icons.Filled.Movie to MaterialTheme.colorScheme.primary
        FileBucket.AUDIO -> Icons.Filled.AudioFile to MaterialTheme.colorScheme.tertiary
        FileBucket.PDF -> Icons.Filled.PictureAsPdf to MaterialTheme.colorScheme.error
        FileBucket.ARCHIVES -> Icons.Filled.FolderZip to MaterialTheme.colorScheme.tertiary
        FileBucket.DOCUMENTS -> Icons.Filled.Description to MaterialTheme.colorScheme.onSurfaceVariant
        FileBucket.OTHER -> Icons.Filled.InsertDriveFile to MaterialTheme.colorScheme.primary
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = TgShapes.BottomSheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(headerTint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        headerIcon,
                        contentDescription = null,
                        tint = headerTint,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.fileName,
                        style = TgTypography.Display.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            if (isDownloaded) R.string.details_downloaded else R.string.details_not_downloaded,
                        ),
                        style = TgTypography.Display.bodySmall,
                        color = if (isDownloaded) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            EditorialKeyValueRow(
                label = stringResource(R.string.details_size),
                value = item.fileSize.toHumanSize(),
            )
            EditorialKeyValueRow(
                label = stringResource(R.string.details_type),
                value = item.mimeType ?: stringResource(R.string.details_type_unknown),
            )
            EditorialKeyValueRow(
                label = stringResource(R.string.details_date),
                value = item.date.toLocalShort(),
            )
            EditorialKeyValueRow(
                label = stringResource(R.string.details_message_id),
                value = item.messageId.toString(),
            )
            if (item.remoteId.isNotEmpty()) {
                EditorialKeyValueRow(
                    label = stringResource(R.string.details_remote_id),
                    value = item.remoteId.take(16) + "…",
                )
            }

            Spacer(Modifier.height(8.dp))

            ActionRow(
                icon = Icons.Filled.Download,
                label = stringResource(
                    if (isDownloaded) R.string.details_redownload else R.string.details_download,
                ),
                tint = MaterialTheme.colorScheme.secondary,
            ) {
                onDownload()
                onDismiss()
            }
            if (onTogglePinOffline != null) {
                ActionRow(
                    icon = if (isPinnedOffline) Icons.Filled.CloudDone else Icons.Filled.OfflinePin,
                    label = stringResource(
                        if (isPinnedOffline) R.string.details_unpin_offline else R.string.details_pin_offline,
                    ),
                    tint = MaterialTheme.colorScheme.secondary,
                ) {
                    onTogglePinOffline()
                    onDismiss()
                }
            }
            if (isDownloaded) {
                ActionRow(
                    icon = Icons.Filled.SaveAlt,
                    label = stringResource(R.string.details_copy_downloads),
                    tint = MaterialTheme.colorScheme.tertiary,
                ) {
                    onCopyToDownloads()
                    onDismiss()
                }
            }
            ActionRow(
                icon = Icons.Filled.DriveFileMove,
                label = stringResource(R.string.details_move),
                tint = MaterialTheme.colorScheme.primary,
            ) {
                onMove()
                onDismiss()
            }
            ActionRow(
                icon = if (item.isStarred) Icons.Filled.Star else Icons.Filled.StarBorder,
                label = stringResource(
                    if (item.isStarred) R.string.details_unstar else R.string.details_star,
                ),
                tint = MaterialTheme.colorScheme.primary,
            ) {
                onToggleStar()
                onDismiss()
            }
            if (onShareLink != null) {
                ActionRow(
                    icon = Icons.Filled.Link,
                    label = stringResource(R.string.details_share_link),
                    tint = MaterialTheme.colorScheme.secondary,
                ) {
                    onShareLink()
                    onDismiss()
                }
            }
            ActionRow(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.details_delete),
                tint = MaterialTheme.colorScheme.error,
            ) {
                onDelete()
                onDismiss()
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            style = TgTypography.Display.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
