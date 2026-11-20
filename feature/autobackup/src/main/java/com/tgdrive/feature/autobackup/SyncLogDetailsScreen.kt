package com.tgdrive.feature.autobackup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.tgdrive.core.common.toLocalShort
import com.tgdrive.core.db.entity.SyncLogEntity
import com.tgdrive.designsystem.editorial.EditorialCard
import com.tgdrive.designsystem.theme.TgTypography

private val DetailsAmber = Color(0xFFF5A623)

/**
 * Detailed view of a single [SyncLogEntity] run pushed over [RootNavHost].
 */
@Composable
fun SyncLogDetailsScreen(
    jobId: String,
    onBack: () -> Unit,
    vm: AutoBackupViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val entry = state.history.find { it.jobId == jobId }
    val loading = !state.historyLoaded

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.backup_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = stringResource(R.string.backup_history_details),
                style = TgTypography.Display.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
        }

        when {
            loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            entry == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.backup_log_not_found),
                        style = TgTypography.Display.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                SyncLogDetails(entry = entry)
            }
        }
    }
}

@Composable
private fun SyncLogDetails(entry: SyncLogEntity) {
    val (statusTint, statusIcon, statusLabel) = when (entry.status) {
        "SUCCESS" -> Triple(
            MaterialTheme.colorScheme.secondary,
            Icons.Filled.CheckCircle,
            stringResource(R.string.backup_status_success),
        )
        "PARTIAL" -> Triple(
            DetailsAmber,
            Icons.Filled.Warning,
            stringResource(R.string.backup_status_partial),
        )
        "RUNNING" -> Triple(
            MaterialTheme.colorScheme.primary,
            Icons.Filled.Sync,
            stringResource(R.string.backup_status_running),
        )
        "CANCELLED" -> Triple(
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Cancel,
            stringResource(R.string.backup_status_cancelled),
        )
        else -> Triple(
            MaterialTheme.colorScheme.error,
            Icons.Filled.Error,
            stringResource(R.string.backup_status_failure),
        )
    }

    val durationText = entry.completedAt?.let { end ->
        val durationMs = (end - entry.startedAt).coerceAtLeast(0L)
        if (durationMs < 1000L) {
            stringResource(R.string.backup_duration_lt1s)
        } else {
            formatDuration(durationMs)
        }
    }

    val failed = (entry.filesScanned - entry.filesUploaded - entry.filesSkipped).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(statusTint.copy(alpha = 0.14f))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusTint,
                    modifier = Modifier.size(32.dp),
                )
                Column(modifier = Modifier.padding(start = 14.dp)) {
                    Text(
                        text = statusLabel,
                        style = TgTypography.Display.headlineMedium,
                        color = statusTint,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = entry.startedAt.toLocalShort(),
                        style = TgTypography.Mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        EditorialCard {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailRow(
                    icon = Icons.Filled.Schedule,
                    label = stringResource(R.string.backup_started_at),
                    value = entry.startedAt.toLocalShort(),
                )
                entry.completedAt?.let { completedAt ->
                    DetailRow(
                        icon = Icons.Filled.Schedule,
                        label = stringResource(R.string.backup_completed_at),
                        value = completedAt.toLocalShort(),
                    )
                }
                if (durationText != null) {
                    DetailRow(
                        icon = Icons.Filled.Schedule,
                        label = stringResource(R.string.backup_duration),
                        value = durationText,
                    )
                }
                DetailRow(
                    icon = Icons.Filled.UploadFile,
                    label = stringResource(R.string.backup_files_scanned),
                    value = "${entry.filesScanned}",
                )
                DetailRow(
                    icon = Icons.Filled.CheckCircle,
                    label = stringResource(R.string.backup_files_uploaded),
                    value = "${entry.filesUploaded}",
                )
                DetailRow(
                    icon = Icons.Filled.SkipNext,
                    label = stringResource(R.string.backup_files_skipped),
                    value = "${entry.filesSkipped}",
                )
                if (failed > 0 || entry.status == "FAILURE") {
                    val failedDisplay = if (failed > 0) "$failed" else "1+"
                    DetailRow(
                        icon = Icons.Filled.Error,
                        label = stringResource(R.string.backup_files_failed),
                        value = failedDisplay,
                        valueTint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (!entry.errorMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.backup_error_label),
                        style = TgTypography.Display.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = entry.errorMessage!!,
                        style = TgTypography.Mono,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueTint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = TgTypography.Display.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        Text(
            text = value,
            style = TgTypography.Mono,
            color = valueTint,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    val remSec = seconds % 60
    if (minutes < 60) return "${minutes}m ${remSec}s"
    val hours = minutes / 60
    val remMin = minutes % 60
    return "${hours}h ${remMin}m"
}
