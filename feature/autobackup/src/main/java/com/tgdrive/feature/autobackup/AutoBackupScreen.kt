package com.tgdrive.feature.autobackup

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.common.toLocalShort
import com.tgdrive.core.datastore.BackupSchedule
import com.tgdrive.designsystem.animation.ConcentricPulse
import com.tgdrive.designsystem.editorial.EditorialHeader
import com.tgdrive.designsystem.editorial.EditorialRadioRow
import com.tgdrive.designsystem.editorial.EditorialSwitchRow
import com.tgdrive.designsystem.editorial.SectionLabel
import com.tgdrive.designsystem.theme.TgTypography
import kotlinx.coroutines.delay

private val WarningAmber = Color(0xFFF5A623)

/**
 * AutoBackup configuration, live progress monitor, folder SAF permission checker,
 * debounced exclude-pattern editing, and persistent history navigation.
 */
@Composable
fun AutoBackupScreen(
    onOpenHistory: () -> Unit = {},
    vm: AutoBackupViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activeJob by vm.activeJob.collectAsStateWithLifecycle()
    val prefs = state.prefs
    val context = LocalContext.current

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var targetOverrideUri by remember { mutableStateOf<String?>(null) }
    var permissionVersion by remember { mutableIntStateOf(0) }

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
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            EditorialHeader(title = stringResource(R.string.backup_title))
        }

        // ---- Status callout (Active / Paused / Off + last backup summary)
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

        // ---- Sync history shortcut (ALWAYS accessible regardless of toggle)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                    .clickable(role = Role.Button, onClick = onOpenHistory)
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.20f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.backup_history_view),
                        style = TgTypography.Display.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                    )
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
                                Text(
                                    " " + stringResource(R.string.backup_resume),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { vm.setPaused(true) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Pause, contentDescription = null)
                                Text(
                                    " " + stringResource(R.string.backup_pause),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        if (isActive) {
                            OutlinedButton(
                                onClick = { vm.cancelActive() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Cancel, contentDescription = null)
                                Text(
                                    " " + stringResource(R.string.action_cancel),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Button(
                                onClick = { vm.runNow() },
                                enabled = hasFolders,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Bolt, contentDescription = null)
                                Text(
                                    " " + stringResource(R.string.backup_run_now),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
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

            // ---- Active/Terminal phase progress card
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

            // ---- Folders list
            item {
                SectionCard(stringResource(R.string.backup_pick_folders)) {
                    val sortedUris = remember(prefs.backupFolderUris) { prefs.backupFolderUris.sorted() }
                    sortedUris.forEach { uriStr ->
                        val parsed = remember(uriStr) { Uri.parse(uriStr) }
                        val displayName = remember(uriStr) {
                            try {
                                DocumentFile.fromTreeUri(context, parsed)?.name
                            } catch (_: Throwable) {
                                null
                            } ?: Uri.decode(uriStr).substringAfterLast(':').substringAfterLast('/').ifBlank { uriStr }
                        }
                        val perm = persistedPermissions[uriStr]
                        val hasRead = perm?.isReadPermission == true
                        val missingWrite = prefs.backupDeleteAfterUpload && perm?.isWritePermission != true

                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
                                    if (!hasRead) {
                                        Text(
                                            text = stringResource(R.string.backup_permission_lost),
                                            style = TgTypography.Caption,
                                            color = MaterialTheme.colorScheme.error,
                                        )
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
                                IconButton(
                                    onClick = {
                                        try {
                                            context.contentResolver.releasePersistableUriPermission(
                                                parsed,
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                            )
                                        } catch (_: Throwable) {}
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

                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { pickFolderLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(" " + stringResource(R.string.backup_add_folder))
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

            // ---- Schedule
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
                }
            }

            // ---- Conditions
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
                    EditorialSwitchRow(
                        icon = Icons.Filled.BatteryChargingFull,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.backup_charging_only),
                        checked = prefs.backupChargingOnly,
                        onChange = vm::setChargingOnly,
                    )
                }
            }

            // ---- Advanced options (Strict SHA, Delete-after confirmation, Zip folders, Debounced excludes)
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
                            placeholder = { Text("*.tmp, Thumbs.db, .DS_Store") },
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
                    val subtitle = if (phase.bytesTotal > 0L) {
                        stringResource(
                            R.string.backup_phase_uploading_sub,
                            phase.fileName.orEmpty(),
                            phase.bytesSent.toHumanSize(),
                            phase.bytesTotal.toHumanSize(),
                        )
                    } else {
                        phase.fileName.orEmpty()
                    }
                    ProgressHeader(
                        icon = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp,
                            )
                        },
                        title = stringResource(R.string.backup_phase_uploading, phase.current, phase.total),
                        subtitle = subtitle,
                    )
                    val fraction = when {
                        phase.bytesTotal > 0L -> (phase.bytesSent.toFloat() / phase.bytesTotal.toFloat()).coerceIn(0f, 1f)
                        phase.total > 0 -> (phase.current.toFloat() / phase.total.toFloat()).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
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
