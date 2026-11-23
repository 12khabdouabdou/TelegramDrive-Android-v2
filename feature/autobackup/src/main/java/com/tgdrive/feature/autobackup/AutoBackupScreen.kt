package com.tgdrive.feature.autobackup

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.common.toLocalShort
import com.tgdrive.core.datastore.BackupMediaFilter
import com.tgdrive.core.datastore.BackupQuality
import com.tgdrive.core.datastore.BackupSchedule
import com.tgdrive.core.datastore.BackupUploadOrder
import com.tgdrive.designsystem.animation.ConcentricPulse
import com.tgdrive.designsystem.editorial.EditorialHeader
import com.tgdrive.designsystem.editorial.EditorialRadioRow
import com.tgdrive.designsystem.editorial.EditorialSwitchRow
import com.tgdrive.designsystem.editorial.SectionLabel
import com.tgdrive.designsystem.theme.TgTypography
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.Locale

private val WarningAmber = Color(0xFFF5A623)

private val FOLDER_PRESETS = listOf(
    "Camera" to "primary:DCIM/Camera",
    "Screenshots" to "primary:Pictures/Screenshots",
    "Downloads" to "primary:Download",
    "Documents" to "primary:Documents",
)

@Composable
fun AutoBackupScreen(
    onOpenHistory: () -> Unit = {},
    onOpenFreeUpSpace: () -> Unit = {},
    vm: AutoBackupViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activeJob by vm.activeJob.collectAsStateWithLifecycle()
    val prefs = state.prefs
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showTimePickerDialog by remember { mutableStateOf(false) }
    var targetOverrideUri by remember { mutableStateOf<String?>(null) }
    var permissionVersion by remember { mutableIntStateOf(0) }
    var dismissBatteryBanner by remember { mutableStateOf(false) }
    var ignoringBatteryOpt by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBatteryOpt = isIgnoringBatteryOptimizations(context)
                permissionVersion++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Throwable) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Throwable) {}
            }
            permissionVersion++
            vm.addFolderUri(it.toString())
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.backup_delete_confirm_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    stringResource(R.string.backup_delete_confirm_msg),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        vm.setDeleteAfter(true)
                    },
                ) {
                    Text(stringResource(R.string.backup_delete_confirm_yes), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.backup_delete_confirm_no))
                }
            },
        )
    }

    if (showTimePickerDialog) {
        ScheduleTimePickerDialog(
            initialHour = prefs.backupScheduleHour,
            initialMinute = prefs.backupScheduleMinute,
            onDismiss = { showTimePickerDialog = false },
            onConfirm = { h, m ->
                showTimePickerDialog = false
                vm.setScheduleTime(h, m)
            },
        )
    }

    targetOverrideUri?.let { folderUri ->
        val customTargetId = prefs.customTargetFor(folderUri)
        AlertDialog(
            onDismissRequest = { targetOverrideUri = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.backup_folder_target_dialog_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditorialRadioRow(
                        title = stringResource(R.string.backup_folder_target_default),
                        selected = customTargetId == null,
                        onClick = {
                            vm.setFolderTarget(folderUri, null)
                            targetOverrideUri = null
                        },
                    )
                    EditorialRadioRow(
                        title = stringResource(R.string.backup_target_saved),
                        selected = customTargetId == -1L,
                        onClick = {
                            vm.setFolderTarget(folderUri, -1L)
                            targetOverrideUri = null
                        },
                    )
                    state.folders.forEach { folder ->
                        EditorialRadioRow(
                            title = folder.title,
                            selected = customTargetId == folder.id,
                            onClick = {
                                vm.setFolderTarget(folderUri, folder.id)
                                targetOverrideUri = null
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { targetOverrideUri = null }) {
                    Text(stringResource(R.string.backup_dismiss))
                }
            },
        )
    }

    val persistedPermissions = remember(prefs.backupFolderUris, permissionVersion) {
        try {
            context.contentResolver.persistedUriPermissions.associateBy { it.uri.toString() }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    val lastLog = state.history.firstOrNull()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            EditorialHeader(title = stringResource(R.string.backup_title))
        }

        // ---- Status callout
        item {
            val isRunning = prefs.backupEnabled && !prefs.backupPaused
            val isPaused = prefs.backupEnabled && prefs.backupPaused
            val cardBg = when {
                isRunning -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
                isPaused -> WarningAmber.copy(alpha = 0.14f)
                else -> MaterialTheme.colorScheme.surfaceContainer
            }
            val statusTitle = when {
                isRunning -> stringResource(R.string.backup_enabled)
                isPaused -> stringResource(R.string.backup_paused)
                else -> stringResource(R.string.backup_disabled)
            }
            val titleTint = when {
                isRunning -> MaterialTheme.colorScheme.tertiary
                isPaused -> WarningAmber
                else -> MaterialTheme.colorScheme.onSurface
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardBg)
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isRunning) {
                            ConcentricPulse(
                                color = MaterialTheme.colorScheme.tertiary,
                                maxRadius = 24.dp,
                                coreRadius = 6.dp,
                                rings = 3,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (isPaused) Icons.Filled.Pause else Icons.Filled.Folder,
                                    contentDescription = null,
                                    tint = if (isPaused) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(
                            text = statusTitle,
                            style = TgTypography.Display.titleMedium,
                            color = titleTint,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.backup_subtitle),
                            style = TgTypography.Display.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (lastLog != null) {
                            Spacer(Modifier.height(4.dp))
                            val lastStatusLabel = when (lastLog.status) {
                                "SUCCESS" -> stringResource(R.string.backup_status_success)
                                "PARTIAL" -> stringResource(R.string.backup_status_partial)
                                "RUNNING" -> stringResource(R.string.backup_status_running)
                                "CANCELLED" -> stringResource(R.string.backup_status_cancelled)
                                else -> stringResource(R.string.backup_status_failure)
                            }
                            Text(
                                text = stringResource(
                                    R.string.backup_last_summary,
                                    lastLog.startedAt.toLocalShort(),
                                    lastStatusLabel,
                                ),
                                style = TgTypography.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Switch(checked = prefs.backupEnabled, onCheckedChange = vm::toggleEnabled)
                }
            }
        }

        // ---- OEM Background battery optimization warning banner
        if (prefs.backupEnabled && !ignoringBatteryOpt && !dismissBatteryBanner) {
            item {
                BatteryOptimizationBanner(
                    onAllowBackground = {
                        val pkgUri = Uri.parse("package:${context.packageName}")
                        val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
                        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                        runCatching { context.startActivity(directIntent) }
                            .onFailure {
                                runCatching { context.startActivity(fallbackIntent) }
                                    .onFailure { runCatching { context.startActivity(detailsIntent) } }
                            }
                    },
                    onDismiss = { dismissBatteryBanner = true },
                )
            }
        }

        // ---- Sync history shortcut
        item {
            ShortcutCard(
                icon = Icons.Filled.History,
                tint = MaterialTheme.colorScheme.tertiary,
                title = stringResource(R.string.backup_history_view),
                subtitle = null,
                onClick = onOpenHistory,
            )
        }

        // ---- Free up device space shortcut
        item {
            ShortcutCard(
                icon = Icons.Filled.CleaningServices,
                tint = MaterialTheme.colorScheme.primary,
                title = stringResource(R.string.free_up_space_card_title),
                subtitle = stringResource(R.string.free_up_space_card_desc),
                onClick = onOpenFreeUpSpace,
            )
        }

        if (prefs.backupEnabled) {
            // ---- Run / Pause / Cancel controls
            item {
                val hasFolders = prefs.backupFolderUris.isNotEmpty()
                val isActive = activeJob?.phase?.isActive == true
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (prefs.backupPaused) {
                            OutlinedButton(
                                onClick = { vm.setPaused(false) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text(" " + stringResource(R.string.backup_resume), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { vm.setPaused(true) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Pause, contentDescription = null)
                                Text(" " + stringResource(R.string.backup_pause), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        if (isActive) {
                            OutlinedButton(
                                onClick = { vm.cancelActive() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Cancel, contentDescription = null)
                                Text(" " + stringResource(R.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Button(
                                onClick = { vm.runNow() },
                                enabled = hasFolders,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Bolt, contentDescription = null)
                                Text(" " + stringResource(R.string.backup_run_now), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    if (!hasFolders) {
                        Text(
                            text = stringResource(R.string.backup_no_folders_hint),
                            style = TgTypography.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- Live progress card (with speed, ETA, overall progress & current file thumbnail)
            activeJob?.let { job ->
                if (job.phase !is BackupPhase.Idle) {
                    item {
                        ProgressCard(
                            phase = job.phase,
                            onCancel = { vm.cancelActive() },
                            onDismiss = { vm.dismissTerminal() },
                        )
                    }
                }
            }

            // ---- Watched Folders + Quick Presets + Pending Preview + Per-Folder Encryption
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionLabel(text = stringResource(R.string.backup_pick_folders))
                        if (prefs.backupFolderUris.isNotEmpty()) {
                            TextButton(
                                onClick = vm::scanPendingPreview,
                                enabled = !state.scanningPending,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                if (state.scanningPending) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                } else {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(stringResource(R.string.backup_check_pending))
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(18.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val sortedUris = remember(prefs.backupFolderUris) { prefs.backupFolderUris.sorted() }
                            sortedUris.forEach { uriStr ->
                                val parsed = remember(uriStr) { Uri.parse(uriStr) }
                                val displayName = remember(uriStr) {
                                    runCatching { DocumentFile.fromTreeUri(context, parsed)?.name }.getOrNull()
                                        ?: Uri.decode(uriStr).substringAfterLast(':').substringAfterLast('/').ifBlank { uriStr }
                                }
                                val perm = persistedPermissions[uriStr]
                                val hasRead = perm?.isReadPermission == true
                                val missingWrite = prefs.backupDeleteAfterUpload && perm?.isWritePermission != true
                                val summary = state.folderStats[uriStr]
                                val isEncrypted = prefs.isFolderEncrypted(uriStr)

                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = if (hasRead) Icons.Filled.Folder else Icons.Filled.Warning,
                                            contentDescription = null,
                                            tint = if (hasRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                            Text(
                                                text = displayName,
                                                style = TgTypography.Display.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (summary != null && (summary.syncedCount > 0 || summary.pendingCount != null)) {
                                                val statsText = buildString {
                                                    append(stringResource(R.string.backup_folder_synced_count, summary.syncedCount))
                                                    if (summary.lastSyncedMs > 0L) {
                                                        append(" · ")
                                                        append(summary.lastSyncedMs.toLocalShort())
                                                    }
                                                    summary.pendingCount?.let { pending ->
                                                        append(" · ")
                                                        append(
                                                            stringResource(
                                                                R.string.backup_folder_pending_count,
                                                                pending,
                                                                (summary.pendingBytes ?: 0L).toHumanSize(),
                                                            ),
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = statsText,
                                                    style = TgTypography.Caption,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            if (!hasRead || summary?.permissionLost == true) {
                                                TextButton(
                                                    onClick = { pickFolderLauncher.launch(parsed) },
                                                    contentPadding = PaddingValues(0.dp),
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.backup_fix_permission),
                                                        style = TgTypography.Caption,
                                                        color = MaterialTheme.colorScheme.error,
                                                        fontWeight = FontWeight.SemiBold,
                                                    )
                                                }
                                            } else if (missingWrite) {
                                                Text(
                                                    text = stringResource(R.string.backup_delete_after_desc),
                                                    style = TgTypography.Caption,
                                                    color = WarningAmber,
                                                )
                                            } else {
                                                val customId = prefs.customTargetFor(uriStr)
                                                val targetLabel = when {
                                                    customId == null -> stringResource(R.string.backup_folder_target_default)
                                                    customId <= 0L -> stringResource(
                                                        R.string.backup_folder_target_custom,
                                                        stringResource(R.string.backup_target_saved),
                                                    )
                                                    else -> {
                                                        val folderTitle = state.folders.find { it.id == customId }?.title
                                                            ?: stringResource(R.string.backup_target_saved)
                                                        stringResource(R.string.backup_folder_target_custom, folderTitle)
                                                    }
                                                }
                                                Text(
                                                    text = targetLabel,
                                                    style = TgTypography.Caption,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.clickable { targetOverrideUri = uriStr },
                                                )
                                            }
                                        }
                                        IconButton(onClick = { vm.toggleFolderEncrypted(uriStr) }) {
                                            Icon(
                                                imageVector = if (isEncrypted) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                                contentDescription = stringResource(R.string.backup_encrypt_folder),
                                                tint = if (isEncrypted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                runCatching {
                                                    context.contentResolver.releasePersistableUriPermission(
                                                        parsed,
                                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                                    )
                                                }
                                                permissionVersion++
                                                vm.removeFolderUri(uriStr)
                                            },
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = stringResource(R.string.backup_remove_folder),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                            // Quick presets
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FOLDER_PRESETS.forEach { (label, docId) ->
                                    AssistChip(
                                        onClick = {
                                            val hintUri = runCatching {
                                                DocumentsContract.buildDocumentUri(
                                                    "com.android.externalstorage.documents",
                                                    docId,
                                                )
                                            }.getOrNull()
                                            pickFolderLauncher.launch(hintUri)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = { pickFolderLauncher.launch(null) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Text(" " + stringResource(R.string.backup_add_folder))
                            }
                        }
                    }
                }
            }

            // ---- Backup destination
            item {
                SectionCard(stringResource(R.string.backup_target)) {
                    EditorialRadioRow(
                        title = stringResource(R.string.backup_target_saved),
                        selected = prefs.backupTargetFolderId <= 0,
                        onClick = { vm.setTargetFolder(-1L) },
                    )
                    state.folders.forEach { folder ->
                        EditorialRadioRow(
                            title = folder.title,
                            selected = prefs.backupTargetFolderId == folder.id,
                            onClick = { vm.setTargetFolder(folder.id) },
                        )
                    }
                }
            }

            // ---- Schedule + Time Window
            item {
                SectionCard(stringResource(R.string.backup_schedule)) {
                    BackupSchedule.entries.forEach { sched ->
                        val label = when (sched) {
                            BackupSchedule.ON_CHANGE -> stringResource(R.string.backup_schedule_on_change)
                            BackupSchedule.DAILY -> stringResource(R.string.backup_schedule_daily)
                            BackupSchedule.WEEKLY -> stringResource(R.string.backup_schedule_weekly)
                            BackupSchedule.MANUAL -> stringResource(R.string.backup_schedule_manual)
                        }
                        EditorialRadioRow(
                            title = label,
                            selected = prefs.backupSchedule == sched,
                            onClick = { vm.setSchedule(sched) },
                        )
                    }
                    if (prefs.backupSchedule == BackupSchedule.DAILY || prefs.backupSchedule == BackupSchedule.WEEKLY) {
                        Spacer(Modifier.height(6.dp))
                        val formattedTime = String.format(Locale.US, "%02d:%02d", prefs.backupScheduleHour, prefs.backupScheduleMinute)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { showTimePickerDialog = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.backup_schedule_time_row, formattedTime),
                                style = TgTypography.Display.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f).padding(start = 10.dp),
                            )
                            Text(
                                text = stringResource(R.string.backup_change),
                                style = TgTypography.Display.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // ---- Conditions & Mobile Data Quota
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(text = stringResource(R.string.backup_conditions))
                    EditorialSwitchRow(
                        icon = Icons.Filled.Wifi,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.backup_wifi_only),
                        checked = prefs.backupWifiOnly,
                        onChange = vm::setWifiOnly,
                    )
                    if (!prefs.backupWifiOnly) {
                        SectionCard(stringResource(R.string.backup_cellular_quota_title)) {
                            val todayKey = remember { LocalDate.now().toString() }
                            val usedToday = if (prefs.backupCellularUsageDay == todayKey) prefs.backupCellularUsedBytes else 0L
                            val limitLabel = if (prefs.backupCellularDailyLimitMb <= 0) {
                                stringResource(R.string.backup_quota_unlimited)
                            } else {
                                "${prefs.backupCellularDailyLimitMb} MB"
                            }
                            Text(
                                text = stringResource(R.string.backup_cellular_used_today, usedToday.toHumanSize(), limitLabel),
                                style = TgTypography.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            ChipSelectorRow(
                                options = listOf(0, 50, 100, 250, 500),
                                selected = prefs.backupCellularDailyLimitMb,
                                labelFor = { if (it == 0) "∞" else "$it MB" },
                                onSelect = vm::setCellularDailyLimitMb,
                            )
                        }
                        EditorialSwitchRow(
                            icon = Icons.Filled.Wifi,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            title = stringResource(R.string.backup_skip_videos_cellular),
                            checked = prefs.backupSkipVideosOnCellular,
                            onChange = vm::setSkipVideosOnCellular,
                        )
                    }
                    EditorialSwitchRow(
                        icon = Icons.Filled.BatteryChargingFull,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.backup_charging_only),
                        checked = prefs.backupChargingOnly,
                        onChange = vm::setChargingOnly,
                    )
                    SectionCard(stringResource(R.string.backup_min_battery_title)) {
                        ChipSelectorRow(
                            options = listOf(15, 25, 40),
                            selected = prefs.backupMinBatteryPercent,
                            labelFor = { "$it%" },
                            onSelect = vm::setMinBatteryPercent,
                        )
                    }
                }
            }

            // ---- Smart Filters, Ordering & Upload Quality
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(text = stringResource(R.string.backup_smart_options))
                    SectionCard(stringResource(R.string.backup_upload_order_title)) {
                        ChipSelectorRow(
                            options = BackupUploadOrder.entries,
                            selected = prefs.backupUploadOrder,
                            labelFor = { order ->
                                when (order) {
                                    BackupUploadOrder.NEWEST_FIRST -> "Newest"
                                    BackupUploadOrder.OLDEST_FIRST -> "Oldest"
                                    BackupUploadOrder.SMALLEST_FIRST -> "Smallest"
                                }
                            },
                            onSelect = vm::setUploadOrder,
                        )
                    }
                    SectionCard(stringResource(R.string.backup_media_filter_title)) {
                        ChipSelectorRow(
                            options = BackupMediaFilter.entries,
                            selected = prefs.backupMediaFilter,
                            labelFor = { filter ->
                                when (filter) {
                                    BackupMediaFilter.ALL_FILES -> "All files"
                                    BackupMediaFilter.PHOTOS_AND_VIDEOS -> "Photos + Video"
                                    BackupMediaFilter.PHOTOS_ONLY -> "Photos only"
                                }
                            },
                            onSelect = vm::setMediaFilter,
                        )
                    }
                    SectionCard(stringResource(R.string.backup_quality_title)) {
                        ChipSelectorRow(
                            options = BackupQuality.entries,
                            selected = prefs.backupQuality,
                            labelFor = { q ->
                                when (q) {
                                    BackupQuality.ORIGINAL -> "Original"
                                    BackupQuality.STORAGE_SAVER -> "Storage Saver (16MP)"
                                }
                            },
                            onSelect = vm::setQuality,
                        )
                    }
                    SectionCard(stringResource(R.string.backup_max_file_size_title)) {
                        ChipSelectorRow(
                            options = listOf(0, 100, 500, 1024),
                            selected = prefs.backupMaxFileSizeMb,
                            labelFor = { mb ->
                                when (mb) {
                                    0 -> "Account max"
                                    1024 -> "1 GB"
                                    else -> "$mb MB"
                                }
                            },
                            onSelect = vm::setMaxFileSizeMb,
                        )
                    }
                    val cutoffDesc = if (prefs.backupOnlyNewAfterMs > 0L) {
                        stringResource(R.string.backup_only_new_active, prefs.backupOnlyNewAfterMs.toLocalShort())
                    } else {
                        stringResource(R.string.backup_only_new_desc)
                    }
                    EditorialSwitchRow(
                        icon = Icons.Filled.PhotoLibrary,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.backup_only_new_title),
                        description = cutoffDesc,
                        checked = prefs.backupOnlyNewAfterMs > 0L,
                        onChange = vm::setOnlyNewAfterNow,
                    )
                    EditorialSwitchRow(
                        icon = Icons.Filled.CreateNewFolder,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.backup_auto_organize_title),
                        description = stringResource(R.string.backup_auto_organize_desc),
                        checked = prefs.backupAutoOrganizeByDate,
                        onChange = vm::setAutoOrganizeByDate,
                    )
                }
            }

            // ---- Advanced options (Strict SHA, Delete-after, Zip folders, Excludes)
            item {
                var excludeDraft by remember { mutableStateOf(prefs.backupExcludePatterns) }
                LaunchedEffect(prefs.backupExcludePatterns) {
                    if (prefs.backupExcludePatterns != excludeDraft) {
                        excludeDraft = prefs.backupExcludePatterns
                    }
                }
                LaunchedEffect(excludeDraft) {
                    delay(400L)
                    if (excludeDraft != prefs.backupExcludePatterns) {
                        vm.setExcludePatterns(excludeDraft)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(text = stringResource(R.string.backup_advanced))
                    EditorialSwitchRow(
                        icon = Icons.Filled.Fingerprint,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.backup_strict),
                        description = stringResource(R.string.backup_strict_desc),
                        checked = prefs.backupStrictMode,
                        onChange = vm::setStrict,
                    )
                    EditorialSwitchRow(
                        icon = Icons.Filled.DeleteForever,
                        iconTint = MaterialTheme.colorScheme.error,
                        title = stringResource(R.string.backup_delete_after),
                        description = stringResource(R.string.backup_delete_after_desc),
                        checked = prefs.backupDeleteAfterUpload,
                        onChange = { checked ->
                            if (checked) {
                                showDeleteConfirmDialog = true
                            } else {
                                vm.setDeleteAfter(false)
                            }
                        },
                    )
                    EditorialSwitchRow(
                        icon = Icons.Filled.Archive,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.backup_zip_folders),
                        description = stringResource(R.string.backup_zip_folders_desc),
                        checked = prefs.backupZipFolders,
                        onChange = vm::setZipFolders,
                    )
                    if (prefs.backupZipFolders) {
                        EditorialSwitchRow(
                            icon = Icons.Filled.Archive,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            title = stringResource(R.string.backup_zip_incremental),
                            description = stringResource(R.string.backup_zip_incremental_desc),
                            checked = prefs.backupZipIncremental,
                            onChange = vm::setZipIncremental,
                        )
                    }
                    SectionCard(stringResource(R.string.backup_exclude_patterns)) {
                        OutlinedTextField(
                            value = excludeDraft,
                            onValueChange = { excludeDraft = it },
                            label = { Text(stringResource(R.string.backup_exclude_patterns)) },
                            placeholder = { Text("*.tmp, .thumbnails/, cache/, Thumbs.db") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.backup_exclude_patterns_hint),
                            style = TgTypography.Display.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ShortcutCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(tint.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    text = title,
                    style = TgTypography.Display.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BatteryOptimizationBanner(
    onAllowBackground: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WarningAmber.copy(alpha = 0.14f))
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BatteryAlert, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.backup_battery_opt_title),
                    style = TgTypography.Display.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.backup_dismiss), modifier = Modifier.size(16.dp))
                }
            }
            Text(
                text = stringResource(R.string.backup_battery_opt_desc),
                style = TgTypography.Display.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onAllowBackground) {
                Text(stringResource(R.string.backup_allow_background))
            }
        }
    }
}

@Composable
private fun <T> ChipSelectorRow(
    options: List<T>,
    selected: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(labelFor(option)) },
            )
        }
    }
}

@Composable
private fun ScheduleTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    var hour by remember { mutableIntStateOf(initialHour) }
    var minute by remember { mutableIntStateOf(initialMinute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                stringResource(R.string.backup_schedule_time_dialog_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(2 to 0, 3 to 0, 7 to 0, 23 to 0).forEach { (h, m) ->
                        FilterChip(
                            selected = hour == h && minute == m,
                            onClick = {
                                hour = h
                                minute = m
                            },
                            label = { Text(String.format(Locale.US, "%02d:%02d", h, m)) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.backup_hour_label), style = TgTypography.Caption)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { hour = (hour + 23) % 24 }) { Text("-") }
                            Text(String.format(Locale.US, "%02d", hour), style = TgTypography.Display.titleMedium)
                            TextButton(onClick = { hour = (hour + 1) % 24 }) { Text("+") }
                        }
                    }
                    Text(":", style = TgTypography.Display.headlineMedium)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.backup_minute_label), style = TgTypography.Caption)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { minute = (minute + 45) % 60 }) { Text("-") }
                            Text(String.format(Locale.US, "%02d", minute), style = TgTypography.Display.titleMedium)
                            TextButton(onClick = { minute = (minute + 15) % 60 }) { Text("+") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hour, minute) }) {
                Text(stringResource(R.string.backup_save), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ProgressCard(
    phase: BackupPhase,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp),
    ) {
        Column {
            when (phase) {
                is BackupPhase.Idle -> Unit

                is BackupPhase.Scanning -> {
                    ProgressHeader(
                        icon = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.tertiary,
                                strokeWidth = 2.dp,
                            )
                        },
                        title = stringResource(R.string.backup_phase_scanning),
                        subtitle = stringResource(
                            R.string.backup_phase_scanning_sub,
                            phase.foldersDone,
                            phase.foldersTotal,
                            phase.filesFound,
                        ),
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    CancelRow(onCancel)
                }

                is BackupPhase.Diffing -> {
                    ProgressHeader(
                        icon = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.tertiary,
                                strokeWidth = 2.dp,
                            )
                        },
                        title = stringResource(R.string.backup_phase_diffing),
                        subtitle = stringResource(R.string.backup_phase_diffing_sub, phase.filesFound),
                    )
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    CancelRow(onCancel)
                }

                is BackupPhase.Uploading -> {
                    val speedSuffix = buildString {
                        if (phase.speedBytesPerSec > 0L) {
                            append(" · ")
                            append(phase.speedBytesPerSec.toHumanSize())
                            append("/s")
                        }
                        if (phase.etaSeconds > 0L) {
                            append(" · ~")
                            append(BackupOrchestrator.formatEtaShort(phase.etaSeconds))
                        }
                    }
                    val subtitle = if (phase.bytesTotal > 0L) {
                        stringResource(
                            R.string.backup_phase_uploading_sub,
                            phase.fileName.orEmpty(),
                            phase.bytesSent.toHumanSize(),
                            phase.bytesTotal.toHumanSize(),
                        ) + speedSuffix
                    } else {
                        phase.fileName.orEmpty() + speedSuffix
                    }
                    ProgressHeader(
                        icon = {
                            val localThumb = phase.currentLocalUri
                            if (!localThumb.isNullOrBlank()) {
                                AsyncImage(
                                    model = localThumb,
                                    contentDescription = phase.fileName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                )
                            }
                        },
                        title = stringResource(R.string.backup_phase_uploading, phase.current, phase.total),
                        subtitle = subtitle,
                    )
                    val overallFraction = when {
                        phase.totalBytesAll > 0L -> (phase.totalBytesUploaded.toFloat() / phase.totalBytesAll.toFloat()).coerceIn(0f, 1f)
                        phase.bytesTotal > 0L -> (phase.bytesSent.toFloat() / phase.bytesTotal.toFloat()).coerceIn(0f, 1f)
                        phase.total > 0 -> (phase.current.toFloat() / phase.total.toFloat()).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    if (phase.totalBytesAll > 0L) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.backup_overall_bytes,
                                phase.totalBytesUploaded.toHumanSize(),
                                phase.totalBytesAll.toHumanSize(),
                            ),
                            style = TgTypography.Mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { overallFraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                    CancelRow(onCancel)
                }

                is BackupPhase.Finalizing -> {
                    ProgressHeader(
                        icon = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.tertiary,
                                strokeWidth = 2.dp,
                            )
                        },
                        title = stringResource(R.string.backup_phase_finalizing),
                        subtitle = stringResource(
                            R.string.backup_phase_finalizing_sub,
                            phase.uploaded,
                            phase.skipped,
                            phase.total,
                        ),
                    )
                }

                is BackupPhase.Done -> {
                    val failed = phase.failed
                    val tint = if (failed == 0) MaterialTheme.colorScheme.secondary else WarningAmber
                    ProgressHeader(
                        icon = {
                            Icon(
                                imageVector = if (failed == 0) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        title = stringResource(if (failed == 0) R.string.backup_phase_done else R.string.backup_phase_partial),
                        subtitle = stringResource(
                            R.string.backup_phase_done_sub,
                            phase.uploaded,
                            phase.skipped,
                            phase.failed,
                        ),
                    )
                    DismissRow(onDismiss)
                }

                is BackupPhase.Cancelled -> {
                    ProgressHeader(
                        icon = {
                            Icon(
                                Icons.Filled.Cancel,
                                contentDescription = null,
                                tint = WarningAmber,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        title = stringResource(R.string.backup_phase_cancelled),
                        subtitle = stringResource(
                            R.string.backup_phase_cancelled_sub,
                            phase.uploaded,
                            phase.total,
                        ),
                    )
                    DismissRow(onDismiss)
                }

                is BackupPhase.Failed -> {
                    ProgressHeader(
                        icon = {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        title = stringResource(R.string.backup_phase_failed),
                        subtitle = phase.message,
                    )
                    DismissRow(onDismiss)
                }
            }
        }
    }
}

@Composable
private fun ProgressHeader(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text = title,
                style = TgTypography.Display.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = TgTypography.Display.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CancelRow(onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DismissRow(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.backup_dismiss), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(text = title, modifier = Modifier.padding(bottom = 8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(18.dp),
        ) {
            Column { content() }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager ?: return true
    return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
}
