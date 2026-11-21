package com.tgdrive.feature.browser.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PieChartOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.common.toLocalShort
import com.tgdrive.designsystem.editorial.EditorialEmptyState
import com.tgdrive.designsystem.theme.TgTypography
import com.tgdrive.feature.browser.R

/**
 * Storage Analytics Screen — editorial magazine layout with pull-to-refresh,
 * accessible type distribution donut, peekable folder pager, deduplication
 * summary, and backup coverage card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyticsScreen(
    onBack: () -> Unit = {},
    onOpenFolder: (Long, String) -> Unit = { _, _ -> },
    vm: StorageAnalyticsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dupDialog by vm.duplicateDialog.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val s = state) {
            is StorageAnalyticsViewModel.UiState.Loading -> LoadingState(onBack = onBack)
            is StorageAnalyticsViewModel.UiState.Error ->
                ErrorState(
                    message = s.message,
                    onBack = onBack,
                    onRetry = { vm.refresh() },
                )
            is StorageAnalyticsViewModel.UiState.Ready ->
                PullToRefreshBox(
                    isRefreshing = false,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    EditorialDashboard(
                        report = s.report,
                        onBack = onBack,
                        onRefresh = { vm.refresh() },
                        onOpenFolder = onOpenFolder,
                        onInspectDuplicate = { fileName, fileSize -> vm.inspectDuplicate(fileName, fileSize) },
                    )
                }
        }
    }

    dupDialog?.let { dlg ->
        DuplicateCleanupDialog(
            state = dlg,
            onSelectKeep = { chatId, msgId -> vm.selectKeepCopy(chatId, msgId) },
            onDeleteDuplicates = { vm.confirmDeleteDuplicates() },
            onDismiss = { vm.dismissDuplicateDialog() },
        )
    }
}

@Composable
private fun AnalyticsHeader(
    onBack: () -> Unit,
    onRefresh: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.analytics_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = stringResource(R.string.analytics_title),
            style = TgTypography.Display.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        if (onRefresh != null) {
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.analytics_refresh),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun LoadingState(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        AnalyticsHeader(onBack = onBack, onRefresh = null)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AnalyticsHeader(onBack = onBack, onRefresh = onRetry)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.analytics_error_title),
                    style = TgTypography.Display.headlineMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = TgTypography.Display.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                IconButton(onClick = onRetry) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.analytics_retry),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorialDashboard(
    report: StorageAnalytics.Report,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenFolder: (Long, String) -> Unit,
    onInspectDuplicate: (String, Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            AnalyticsHeader(onBack = onBack, onRefresh = onRefresh)
        }

        if (report.totalFiles == 0) {
            item {
                EditorialEmptyState(
                    icon = Icons.Filled.PieChartOutline,
                    title = stringResource(R.string.analytics_empty_title),
                    description = stringResource(R.string.analytics_empty_desc),
                )
            }
            return@LazyColumn
        }

        // ── 1. Hero Metric Callout ──
        item {
            Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                Column {
                    Text(
                        text = stringResource(R.string.analytics_total_stored),
                        style = TgTypography.Display.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = report.totalBytes.toHumanSize(),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onBackground,
                        lineHeight = 52.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${report.totalFiles} files · ${report.totalStarred} starred · ${report.perFolder.size} folders",
                        style = TgTypography.Display.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── 2. Category Donut Breakdown ──
        if (report.perType.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        text = stringResource(R.string.analytics_by_type),
                        style = TgTypography.Display.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        DonutRing(segments = report.perType, totalBytes = report.totalBytes)
                    }
                    Spacer(Modifier.height(20.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (t in report.perType) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(t.bucket.tint()),
                                    )
                                    Text(
                                        text = t.label,
                                        style = TgTypography.Display.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.analytics_files_bytes, t.fileCount, t.totalBytes.toHumanSize()),
                                    style = TgTypography.Display.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 3. Per-Folder Pager with Peek & Indicator Dots ──
        if (report.perFolder.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.analytics_by_folder),
                        style = TgTypography.Display.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )

                    val pagerState = rememberPagerState(pageCount = { report.perFolder.size })
                    HorizontalPager(
                        state = pagerState,
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        pageSpacing = 12.dp,
                    ) { page ->
                        val stat = report.perFolder[page]
                        FolderPagerCard(
                            stat = stat,
                            onClick = { onOpenFolder(stat.chatId, stat.label) },
                        )
                    }

                    if (report.perFolder.size > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            val maxDots = report.perFolder.size.coerceAtMost(10)
                            for (i in 0 until maxDots) {
                                val selected = i == pagerState.currentPage.coerceAtMost(maxDots - 1)
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 3.dp)
                                        .size(width = if (selected) 16.dp else 6.dp, height = 6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            },
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 4. Deduplication Callout ──
        item {
            Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                DedupCallout(
                    dedup = report.dedup,
                    onInspectDuplicate = onInspectDuplicate,
                )
            }
        }

        // ── 5. Backup Coverage Callout ──
        item {
            Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                BackupCoverageCallout(report.backup)
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DonutRing(
    segments: List<StorageAnalytics.TypeStat>,
    totalBytes: Long,
) {
    val active = segments.filter { it.totalBytes > 0 }
    val sweepFractions = if (totalBytes > 0L) {
        active.map { it.totalBytes.toFloat() / totalBytes.toFloat() }
    } else {
        emptyList()
    }
    val segmentColors = active.map { it.bucket.tint() }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val donutDesc = stringResource(R.string.analytics_donut_desc)

    Box(
        modifier = Modifier
            .size(168.dp)
            .semantics { contentDescription = donutDesc },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 28.dp.toPx(), cap = StrokeCap.Butt)
            val diameter = minOf(size.width, size.height) - stroke.width
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )

            var start = -90f
            for (i in active.indices) {
                val sweep = sweepFractions[i] * 360f
                drawArc(
                    color = segmentColors[i],
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
                start += sweep
            }
        }
    }
}

@Composable
private fun FolderPagerCard(
    stat: StorageAnalytics.FolderStat,
    onClick: () -> Unit,
) {
    val tint = if (stat.isSavedMessages) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Text(
            text = stat.label,
            style = TgTypography.Display.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stat.totalBytes.toHumanSize(),
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            color = tint,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${stat.fileCount} files",
                style = TgTypography.Display.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stat.lastActivityMs > 0L) {
                Text(
                    text = stat.lastActivityMs.toLocalShort(),
                    style = TgTypography.Display.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DedupCallout(
    dedup: StorageAnalytics.DedupStat,
    onInspectDuplicate: (String, Long) -> Unit,
) {
    if (dedup.duplicateGroups == 0) {
        EditorialCalloutCard(
            title = "✓ ${stringResource(R.string.analytics_dedup_none_title)}",
            accent = MaterialTheme.colorScheme.secondary,
        ) {
            Text(
                text = stringResource(R.string.analytics_dedup_title),
                style = TgTypography.Display.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    EditorialCalloutCard(
        title = stringResource(R.string.analytics_dedup_title),
        accent = Color(0xFFF5A623),
        icon = {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = null,
                tint = Color(0xFFF5A623),
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = dedup.wastedBytes.toHumanSize(),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.analytics_dedup_summary, dedup.duplicateGroups, dedup.duplicateCopies),
                style = TgTypography.Display.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (dedup.topOffenders.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.analytics_dedup_top_offenders),
                    style = TgTypography.Display.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.analytics_dedup_cleanup_hint),
                    style = TgTypography.Caption,
                    color = MaterialTheme.colorScheme.primary,
                )
                for (o in dedup.topOffenders.take(5)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onInspectDuplicate(o.fileName, o.fileSize) }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${o.fileName} (×${o.copies})",
                            style = TgTypography.Display.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        Text(
                            text = o.wastedBytes.toHumanSize(),
                            style = TgTypography.Mono,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DuplicateCleanupDialog(
    state: StorageAnalyticsViewModel.DuplicateCleanupState,
    onSelectKeep: (Long, Long) -> Unit,
    onDeleteDuplicates: () -> Unit,
    onDismiss: () -> Unit,
) {
    val deleteCount = (state.copies.size - 1).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = { if (!state.deleting) onDismiss() },
        title = { Text(stringResource(R.string.analytics_dedup_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${state.fileName} (${state.fileSize.toHumanSize()})",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                for (copy in state.copies) {
                    val selected =
                        copy.entity.chatId == state.keepChatId &&
                            copy.entity.messageId == state.keepMessageId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !state.deleting) {
                                onSelectKeep(copy.entity.chatId, copy.entity.messageId)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            enabled = !state.deleting,
                            onClick = { onSelectKeep(copy.entity.chatId, copy.entity.messageId) },
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                            Text(
                                text = copy.folderName,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = copy.entity.date.toLocalShort(),
                                style = TgTypography.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Text(
                                text = stringResource(R.string.analytics_dedup_dialog_keep),
                                style = TgTypography.Caption,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !state.deleting, onClick = onDismiss) {
                Text(stringResource(R.string.analytics_dedup_cancel))
            }
        },
        confirmButton = {
            Button(
                enabled = !state.deleting && deleteCount > 0,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                onClick = onDeleteDuplicates,
            ) {
                Text(stringResource(R.string.analytics_dedup_dialog_delete_n, deleteCount))
            }
        },
    )
}

@Composable
private fun BackupCoverageCallout(backup: StorageAnalytics.BackupStat) {
    EditorialCalloutCard(
        title = stringResource(R.string.analytics_backup_title),
        accent = MaterialTheme.colorScheme.tertiary,
        icon = {
            Icon(
                Icons.Filled.CloudDone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        },
    ) {
        val lastSyncSuffix = if (backup.lastSyncMs > 0L) {
            stringResource(R.string.analytics_backup_last_sync, backup.lastSyncMs.toLocalShort())
        } else {
            ""
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = backup.syncedBytes.toHumanSize(),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.analytics_backup_summary, backup.syncedFiles) + lastSyncSuffix,
                style = TgTypography.Display.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditorialCalloutCard(
    title: String,
    accent: Color,
    icon: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) icon()
            Text(
                text = title,
                style = TgTypography.Display.labelLarge,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
        }
        content()
    }
}

@Composable
private fun StorageAnalytics.FileBucket.tint(): Color =
    when (this) {
        StorageAnalytics.FileBucket.IMAGES -> MaterialTheme.colorScheme.primary
        StorageAnalytics.FileBucket.VIDEOS -> MaterialTheme.colorScheme.tertiary
        StorageAnalytics.FileBucket.AUDIO -> MaterialTheme.colorScheme.secondary
        StorageAnalytics.FileBucket.PDF -> MaterialTheme.colorScheme.error
        StorageAnalytics.FileBucket.DOCUMENTS -> Color(0xFF9C7CF4)
        StorageAnalytics.FileBucket.ARCHIVES -> MaterialTheme.colorScheme.onSurfaceVariant
        StorageAnalytics.FileBucket.OTHER -> MaterialTheme.colorScheme.outline
    }
