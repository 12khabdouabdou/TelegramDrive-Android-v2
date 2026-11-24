package com.tgdrive.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.common.toLocalShort
import com.tgdrive.core.datastore.ThemeMode
import com.tgdrive.core.security.BiometricHelper
import com.tgdrive.designsystem.editorial.EditorialHeader
import com.tgdrive.designsystem.editorial.EditorialRadioRow
import com.tgdrive.designsystem.editorial.SectionLabel
import com.tgdrive.designsystem.theme.TgTypography
import kotlinx.coroutines.launch

/**
 * Settings screen — theme picker, debounced cache limit slider + Cache screen shortcut,
 * biometric lock hardware/enrollment guard, editable Telegram API credentials,
 * clickable About & license links, and destructive logout confirmation.
 */
@Composable
fun SettingsScreen(
    onOpenCache: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showApiDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showPassphraseDialog by remember { mutableStateOf(false) }
    var exportedVaultKey by remember { mutableStateOf<String?>(null) }
    var showImportVaultDialog by remember { mutableStateOf(false) }
    var showCloudBackupDialog by remember { mutableStateOf(false) }
    var showCloudRestoreDialog by remember { mutableStateOf(false) }
    var passvaultNotice by remember { mutableStateOf<String?>(null) }
    var bioAvail by remember { mutableStateOf(vm.biometricAvailability()) }

    LaunchedEffect(Unit) { vm.refreshTempStats() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                bioAvail = vm.biometricAvailability()
                vm.refreshTempStats()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val versionName = remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.3"
        } catch (_: Throwable) {
            "1.0.3"
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.settings_logout_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    stringResource(R.string.settings_logout_msg),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        vm.logout()
                    },
                ) {
                    Text(
                        stringResource(R.string.settings_logout_confirm),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(
                        stringResource(R.string.settings_api_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }

    if (showApiDialog) {
        var apiIdInput by remember(state.apiId) { mutableStateOf(state.apiId) }
        var apiHashInput by remember(state.apiHash) { mutableStateOf(state.apiHash) }
        val valid = (apiIdInput.trim().toIntOrNull() ?: 0) > 0 && apiHashInput.trim().isNotEmpty()

        AlertDialog(
            onDismissRequest = { showApiDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.settings_api_edit),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = apiIdInput,
                        onValueChange = { v -> apiIdInput = v.filter { it.isDigit() } },
                        label = { Text(stringResource(R.string.settings_api_id)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiHashInput,
                        onValueChange = { apiHashInput = it },
                        label = { Text(stringResource(R.string.settings_api_hash)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.settings_api_help),
                        style = TgTypography.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        vm.saveApiCreds(apiIdInput, apiHashInput)
                        showApiDialog = false
                    },
                ) {
                    Text(stringResource(R.string.settings_api_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiDialog = false }) {
                    Text(stringResource(R.string.settings_api_cancel))
                }
            },
        )
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.settings_licenses_dialog_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    stringResource(R.string.settings_licenses_dialog_msg),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) {
                    Text("OK")
                }
            },
        )
    }

    if (showPassphraseDialog) {
        var passInput by remember { mutableStateOf("") }
        var confirmInput by remember { mutableStateOf("") }
        var showPass by remember { mutableStateOf(false) }
        var passErr by remember { mutableStateOf<String?>(null) }
        val shortErr = stringResource(R.string.settings_passvault_passphrase_short)
        val mismatchErr = stringResource(R.string.settings_passvault_passphrase_mismatch)
        val savedMsg = stringResource(R.string.settings_passvault_passphrase_saved)
        val resetMsg = stringResource(R.string.settings_passvault_passphrase_reset)
        AlertDialog(
            onDismissRequest = { showPassphraseDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.settings_passvault_passphrase_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.settings_passvault_passphrase_desc),
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = passInput,
                        onValueChange = { passInput = it; passErr = null },
                        label = { Text(stringResource(R.string.settings_passvault_passphrase_label)) },
                        singleLine = true,
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPass = !showPass }) {
                                Icon(
                                    if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirmInput,
                        onValueChange = { confirmInput = it; passErr = null },
                        label = { Text(stringResource(R.string.settings_passvault_passphrase_confirm)) },
                        singleLine = true,
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    passErr?.let {
                        Text(it, style = TgTypography.Caption, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = passInput.trim()
                        when {
                            trimmed.length < 4 -> passErr = shortErr
                            trimmed != confirmInput.trim() -> passErr = mismatchErr
                            else -> {
                                vm.saveBackupPassphrase(trimmed)
                                passvaultNotice = savedMsg
                                showPassphraseDialog = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_api_save))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.hasCustomPassphrase || state.backupUseCustomPassphrase) {
                        TextButton(
                            onClick = {
                                vm.clearBackupPassphrase()
                                passvaultNotice = resetMsg
                                showPassphraseDialog = false
                            },
                        ) {
                            Text(stringResource(R.string.settings_passvault_use_vault))
                        }
                    }
                    TextButton(onClick = { showPassphraseDialog = false }) {
                        Text(stringResource(R.string.settings_api_cancel))
                    }
                }
            },
        )
    }

    exportedVaultKey?.let { keyText ->
        val copiedMsg = stringResource(R.string.settings_passvault_copied)
        AlertDialog(
            onDismissRequest = { exportedVaultKey = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.settings_passvault_export_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.settings_passvault_export_desc),
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(12.dp),
                    ) {
                        Text(
                            text = keyText,
                            style = TgTypography.Mono,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cb?.setPrimaryClip(ClipData.newPlainText("TelegramDrive Vault Key", keyText))
                        passvaultNotice = copiedMsg
                        exportedVaultKey = null
                    },
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_passvault_copy_key))
                }
            },
            dismissButton = {
                TextButton(onClick = { exportedVaultKey = null }) {
                    Text(stringResource(R.string.settings_api_cancel))
                }
            },
        )
    }

    if (showImportVaultDialog) {
        var keyInput by remember { mutableStateOf("") }
        var importErr by remember { mutableStateOf<String?>(null) }
        val invalidMsg = stringResource(R.string.settings_passvault_restore_invalid)
        AlertDialog(
            onDismissRequest = { showImportVaultDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.settings_passvault_restore_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.settings_passvault_restore_desc),
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it; importErr = null },
                        label = { Text(stringResource(R.string.settings_passvault_restore_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    importErr?.let {
                        Text(it, style = TgTypography.Caption, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (vm.importVaultKey(keyInput)) {
                            passvaultNotice = "Vault key restored"
                            showImportVaultDialog = false
                        } else {
                            importErr = invalidMsg
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_api_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportVaultDialog = false }) {
                    Text(stringResource(R.string.settings_api_cancel))
                }
            },
        )
    }

    if (showCloudBackupDialog) {
        var passInput by remember { mutableStateOf("") }
        var confirmInput by remember { mutableStateOf("") }
        var showPass by remember { mutableStateOf(false) }
        var dialogErr by remember { mutableStateOf<String?>(null) }
        val shortErr = stringResource(R.string.settings_passvault_passphrase_short)
        val mismatchErr = stringResource(R.string.settings_passvault_passphrase_mismatch)
        val backupOkMsg = stringResource(R.string.settings_passvault_cloud_backup_ok)

        AlertDialog(
            onDismissRequest = { if (!state.cloudVaultBusy) showCloudBackupDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.settings_passvault_cloud_backup_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.settings_passvault_cloud_backup_desc),
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = passInput,
                        onValueChange = { passInput = it; dialogErr = null },
                        label = { Text(stringResource(R.string.settings_passvault_cloud_passphrase_label)) },
                        singleLine = true,
                        enabled = !state.cloudVaultBusy,
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
                    OutlinedTextField(
                        value = confirmInput,
                        onValueChange = { confirmInput = it; dialogErr = null },
                        label = { Text(stringResource(R.string.settings_passvault_passphrase_confirm)) },
                        singleLine = true,
                        enabled = !state.cloudVaultBusy,
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.cloudVaultBusy) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                stringResource(R.string.settings_passvault_cloud_busy),
                                style = TgTypography.Caption,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    dialogErr?.let {
                        Text(it, style = TgTypography.Caption, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !state.cloudVaultBusy,
                    onClick = {
                        when {
                            passInput.trim().length < 4 -> dialogErr = shortErr
                            passInput != confirmInput -> dialogErr = mismatchErr
                            else -> {
                                scope.launch {
                                    vm.backupVaultKeyToCloud(passInput.trim())
                                        .onSuccess {
                                            passvaultNotice = backupOkMsg
                                            showCloudBackupDialog = false
                                        }
                                        .onFailure { err ->
                                            dialogErr = err.message ?: "Cloud backup failed"
                                        }
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_passvault_cloud_backup))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.cloudVaultBusy,
                    onClick = { showCloudBackupDialog = false },
                ) {
                    Text(stringResource(R.string.settings_api_cancel))
                }
            },
        )
    }

    if (showCloudRestoreDialog) {
        var passInput by remember { mutableStateOf("") }
        var showPass by remember { mutableStateOf(false) }
        var dialogErr by remember { mutableStateOf<String?>(null) }
        val shortErr = stringResource(R.string.settings_passvault_passphrase_short)
        val restoreOkFmt = stringResource(R.string.settings_passvault_cloud_restore_ok)

        AlertDialog(
            onDismissRequest = { if (!state.cloudVaultBusy) showCloudRestoreDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.settings_passvault_cloud_restore_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.settings_passvault_cloud_restore_desc),
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = passInput,
                        onValueChange = { passInput = it; dialogErr = null },
                        label = { Text(stringResource(R.string.settings_passvault_cloud_passphrase_label)) },
                        singleLine = true,
                        enabled = !state.cloudVaultBusy,
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
                    if (state.cloudVaultBusy) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                stringResource(R.string.settings_passvault_cloud_busy),
                                style = TgTypography.Caption,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    dialogErr?.let {
                        Text(it, style = TgTypography.Caption, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !state.cloudVaultBusy,
                    onClick = {
                        if (passInput.trim().length < 4) {
                            dialogErr = shortErr
                        } else {
                            scope.launch {
                                vm.restoreVaultKeyFromCloud(passInput.trim())
                                    .onSuccess { fp ->
                                        passvaultNotice = String.format(restoreOkFmt, fp)
                                        showCloudRestoreDialog = false
                                    }
                                    .onFailure { err ->
                                        dialogErr = err.message ?: "Cloud restore failed"
                                    }
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_passvault_cloud_restore))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !state.cloudVaultBusy,
                    onClick = { showCloudRestoreDialog = false },
                ) {
                    Text(stringResource(R.string.settings_api_cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (showBack) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                EditorialHeader(title = stringResource(R.string.settings_title))
            }
        } else {
            EditorialHeader(title = stringResource(R.string.settings_title))
        }

        // Appearance
        EditorialSection(title = stringResource(R.string.settings_appearance)) {
            Text(
                text = stringResource(R.string.settings_theme),
                style = TgTypography.Display.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            ThemeMode.entries.forEach { mode ->
                EditorialRadioRow(
                    title = when (mode) {
                        ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                        ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                    },
                    selected = state.themeMode == mode,
                    onClick = { vm.setTheme(mode) },
                )
            }
        }

        // Telegram Cloud Sync
        EditorialSection(title = stringResource(R.string.settings_cloud_sync)) {
            Text(
                text = stringResource(R.string.settings_cloud_sync_desc),
                style = TgTypography.Display.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (state.lastCloudSyncMs > 0L) {
                    stringResource(R.string.settings_cloud_sync_last, state.lastCloudSyncMs.toLocalShort())
                } else {
                    stringResource(R.string.settings_cloud_sync_never)
                },
                style = TgTypography.Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )
            OutlinedButton(
                onClick = vm::syncNow,
                enabled = !state.cloudSyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.cloudSyncing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_cloud_syncing))
                } else {
                    Icon(
                        Icons.Filled.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_cloud_sync_now))
                }
            }
        }

        // Storage (debounced Slider + Cache Management entry)
        EditorialSection(title = stringResource(R.string.settings_storage)) {
            var sliderBytes by remember(state.cacheLimitBytes) {
                mutableFloatStateOf(state.cacheLimitBytes.toFloat())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_cache_limit),
                    style = TgTypography.Display.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = sliderBytes.toLong().toHumanSize(),
                    style = TgTypography.Mono,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Slider(
                value = sliderBytes.coerceIn(100f * 1024 * 1024, 2f * 1024 * 1024 * 1024),
                onValueChange = { sliderBytes = it },
                onValueChangeFinished = { vm.setCacheLimit(sliderBytes.toLong()) },
                valueRange = 100f * 1024 * 1024..2f * 1024 * 1024 * 1024,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenCache)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_cache_manage),
                        style = TgTypography.Display.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.settings_cache_manage_desc),
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Security
        EditorialSection(title = stringResource(R.string.settings_security)) {
            if (!state.isEncryptionAvailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.settings_encryption_unavailable_title),
                            style = TgTypography.Display.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = stringResource(R.string.settings_encryption_unavailable_desc),
                            style = TgTypography.Display.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            val canToggle = bioAvail == BiometricHelper.Availability.AVAILABLE || state.biometricEnabled
            val bioDesc = if (bioAvail == BiometricHelper.Availability.AVAILABLE || state.biometricEnabled) {
                stringResource(R.string.settings_biometric_desc)
            } else {
                stringResource(R.string.settings_biometric_unavailable)
            }
            val confirmTitle = stringResource(R.string.settings_biometric_confirm_title)
            val confirmSubtitle = stringResource(R.string.settings_biometric_confirm_subtitle)
            var bioToggleError by remember { mutableStateOf<String?>(null) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .toggleable(
                        value = state.biometricEnabled,
                        enabled = canToggle,
                        role = Role.Switch,
                        onValueChange = { target ->
                            if (!canToggle) return@toggleable
                            bioToggleError = null
                            if (!target && bioAvail != BiometricHelper.Availability.AVAILABLE) {
                                vm.setBiometric(false)
                                return@toggleable
                            }
                            val activity = context.findFragmentActivity()
                            if (activity == null) {
                                bioToggleError = confirmTitle
                                return@toggleable
                            }
                            vm.authenticateForBiometricToggle(
                                activity = activity,
                                title = confirmTitle,
                                subtitle = confirmSubtitle,
                                onSuccess = {
                                    bioToggleError = null
                                    vm.setBiometric(target)
                                },
                                onError = { msg -> bioToggleError = msg },
                            )
                        },
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_biometric),
                        style = TgTypography.Display.bodyMedium,
                        color = if (canToggle) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = bioDesc,
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.biometricEnabled,
                    onCheckedChange = null,
                    enabled = canToggle,
                )
            }

            bioToggleError?.let { err ->
                Text(
                    text = err,
                    style = TgTypography.Caption,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // PassVault & Encryption
        EditorialSection(title = stringResource(R.string.settings_passvault)) {
            val modeLabel = if (state.backupUseCustomPassphrase && state.hasCustomPassphrase) {
                stringResource(R.string.settings_passvault_mode_passphrase, state.vaultKeyFingerprint)
            } else {
                stringResource(R.string.settings_passvault_mode_vault, state.vaultKeyFingerprint)
            }
            Text(
                text = modeLabel,
                style = TgTypography.Mono,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .toggleable(
                        value = state.backupEncryptAll,
                        role = Role.Switch,
                        onValueChange = vm::setBackupEncryptAll,
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_passvault_encrypt_backup),
                        style = TgTypography.Display.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_passvault_encrypt_backup_desc),
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.backupEncryptAll, onCheckedChange = null)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showPassphraseDialog = true }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.LockOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_passvault_passphrase_row),
                        style = TgTypography.Display.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (state.hasCustomPassphrase) {
                            stringResource(R.string.settings_passvault_passphrase_set)
                        } else {
                            stringResource(R.string.settings_passvault_passphrase_unset)
                        },
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val verifyExportTitle = stringResource(R.string.settings_passvault_verify_export)
            val verifyExportSubtitle = stringResource(R.string.settings_biometric_confirm_subtitle)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (state.biometricEnabled && bioAvail == BiometricHelper.Availability.AVAILABLE) {
                            val activity = context.findFragmentActivity()
                            if (activity != null) {
                                vm.authenticateForBiometricToggle(
                                    activity = activity,
                                    title = verifyExportTitle,
                                    subtitle = verifyExportSubtitle,
                                    onSuccess = { exportedVaultKey = vm.exportVaultKey() },
                                    onError = { err -> passvaultNotice = err },
                                )
                            } else {
                                exportedVaultKey = vm.exportVaultKey()
                            }
                        } else {
                            exportedVaultKey = vm.exportVaultKey()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_passvault_backup_key), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { showImportVaultDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_passvault_restore_key), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { showCloudBackupDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_passvault_cloud_backup), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { showCloudRestoreDialog = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_passvault_cloud_restore), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.settings_passvault_ttl_title),
                style = TgTypography.Display.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.settings_passvault_ttl_desc),
                style = TgTypography.Display.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            val ttlOptions = listOf(
                0 to stringResource(R.string.settings_passvault_ttl_on_close),
                15 to stringResource(R.string.settings_passvault_ttl_15m),
                30 to stringResource(R.string.settings_passvault_ttl_30m),
                60 to stringResource(R.string.settings_passvault_ttl_60m),
                -1 to stringResource(R.string.settings_passvault_ttl_manual),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ttlOptions.forEach { (minutes, label) ->
                    FilterChip(
                        selected = state.decryptedTempTtlMinutes == minutes,
                        onClick = { vm.setDecryptedTempTtlMinutes(minutes) },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.settings_passvault_temp_stats,
                    state.tempDecryptedCount,
                    state.tempDecryptedBytes.toHumanSize(),
                ),
                style = TgTypography.Caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            val wipedMsgFmt = stringResource(R.string.settings_passvault_wiped_count)
            TextButton(
                onClick = {
                    scope.launch {
                        val removed = vm.clearDecryptedTempFiles()
                        passvaultNotice = String.format(wipedMsgFmt, removed)
                    }
                },
            ) {
                Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.settings_passvault_wipe_decrypted))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .toggleable(
                        value = state.manualUploadWifiOnly,
                        role = Role.Switch,
                        onValueChange = vm::setManualUploadWifiOnly,
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_uploads_wifi_only),
                        style = TgTypography.Display.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_uploads_wifi_only_desc),
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.manualUploadWifiOnly, onCheckedChange = null)
            }
            passvaultNotice?.let { msg ->
                Text(
                    text = msg,
                    style = TgTypography.Caption,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // Telegram API
        EditorialSection(title = stringResource(R.string.settings_telegram)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.settings_api_id),
                    style = TgTypography.Display.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    state.apiId.ifBlank { "—" },
                    style = TgTypography.Mono,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.settings_api_hash),
                    style = TgTypography.Display.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (state.apiHash.isNotEmpty()) "••••••••" else "—",
                    style = TgTypography.Mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.settings_api_help),
                style = TgTypography.Display.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )
            OutlinedButton(
                onClick = { showApiDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("  " + stringResource(R.string.settings_api_edit))
            }
        }

        // About
        EditorialSection(title = stringResource(R.string.settings_about)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenAbout)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.settings_about),
                        style = TgTypography.Display.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.settings_version, versionName),
                        style = TgTypography.Mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AboutLink(
                label = stringResource(R.string.settings_github),
                onClick = {
                    try { uriHandler.openUri("https://github.com/TelegramDrive") } catch (_: Throwable) {}
                },
            )
            AboutLink(
                label = stringResource(R.string.settings_donate),
                onClick = {
                    try { uriHandler.openUri("https://github.com/sponsors") } catch (_: Throwable) {}
                },
            )
            AboutLink(
                label = stringResource(R.string.settings_licenses),
                onClick = { showLicensesDialog = true },
            )
        }

        // Logout callout
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .clickable { showLogoutDialog = true }
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        text = stringResource(R.string.settings_logout),
                        style = TgTypography.Display.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.settings_logout_msg),
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun EditorialSection(
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
                .padding(20.dp),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun AboutLink(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TgTypography.Display.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
