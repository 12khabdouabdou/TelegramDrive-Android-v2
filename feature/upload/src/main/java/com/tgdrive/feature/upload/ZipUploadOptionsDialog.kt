package com.tgdrive.feature.upload

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.zip.ZipCompressionMode
import com.tgdrive.core.zip.ZipSelectionSummary
import com.tgdrive.designsystem.theme.TgTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ZipUploadOptions(
    val name: String,
    val password: String?,
    val useVaultKey: Boolean = false,
    val compressionMode: ZipCompressionMode = ZipCompressionMode.SMART,
    val maxPartBytesOverride: Long? = null,
    val excludePatterns: String = "",
    val wifiOnly: Boolean = false,
)

@Composable
fun ZipUploadOptionsDialog(
    onDismiss: () -> Unit,
    onConfirm: (ZipUploadOptions) -> Unit,
    initialName: String? = null,
    title: String? = null,
    summary: ZipSelectionSummary? = null,
    summaryLoading: Boolean = false,
    showExcludePatterns: Boolean = true,
    defaultWifiOnly: Boolean = false,
    onExcludePatternsChanged: ((String) -> Unit)? = null,
    onMaxPartSizeChanged: ((Long?) -> Unit)? = null,
) {
    val defaultName = remember(initialName) {
        val clean = initialName?.trim()?.removeSuffix(".zip")?.takeIf { it.isNotBlank() }
        if (clean != null) {
            clean
        } else {
            "folder_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        }
    }
    var zipName by remember(defaultName) { mutableStateOf(defaultName) }
    var compressionMode by remember { mutableStateOf(ZipCompressionMode.SMART) }
    var partCapOverride by remember { mutableStateOf<Long?>(null) }
    var excludePatterns by remember { mutableStateOf("") }
    var encrypt by remember { mutableStateOf(false) }
    var useVaultKey by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var wifiOnly by remember(defaultWifiOnly) { mutableStateOf(defaultWifiOnly) }
    var error by remember { mutableStateOf<String?>(null) }

    val errEmptyName = stringResource(R.string.upload_zip_error_empty_name)
    val errEmptyPwd = stringResource(R.string.upload_zip_error_empty_password)
    val errMismatch = stringResource(R.string.upload_zip_error_mismatch)
    val errShort = stringResource(R.string.upload_zip_error_short)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.FolderZip,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title ?: stringResource(R.string.upload_zip_title),
                    style = TgTypography.Display.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (summaryLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.upload_zip_scanning_summary),
                            style = TgTypography.Display.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (summary != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            stringResource(
                                R.string.upload_zip_preview_summary,
                                summary.fileCount,
                                summary.totalBytes.toHumanSize(),
                                summary.estimatedParts.coerceAtLeast(1),
                            ),
                            style = TgTypography.Display.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!summary.hasEnoughSpace) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    stringResource(
                                        R.string.upload_zip_low_space_warning,
                                        summary.freeCacheBytes.toHumanSize(),
                                    ),
                                    style = TgTypography.Caption,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = zipName,
                    onValueChange = {
                        zipName = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.upload_zip_name_label)) },
                    singleLine = true,
                    trailingIcon = {
                        Text(
                            stringResource(R.string.upload_zip_suffix),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )

                Text(
                    stringResource(R.string.upload_zip_compression_label),
                    style = TgTypography.Caption,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = compressionMode == ZipCompressionMode.SMART,
                        onClick = { compressionMode = ZipCompressionMode.SMART },
                        label = { Text(stringResource(R.string.upload_zip_comp_smart)) },
                    )
                    FilterChip(
                        selected = compressionMode == ZipCompressionMode.STORE,
                        onClick = { compressionMode = ZipCompressionMode.STORE },
                        label = { Text(stringResource(R.string.upload_zip_comp_store)) },
                    )
                    FilterChip(
                        selected = compressionMode == ZipCompressionMode.DEFLATE,
                        onClick = { compressionMode = ZipCompressionMode.DEFLATE },
                        label = { Text(stringResource(R.string.upload_zip_comp_deflate)) },
                    )
                }

                Text(
                    stringResource(R.string.upload_zip_part_size_label),
                    style = TgTypography.Caption,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val options = listOf<Pair<String, Long?>>(
                        stringResource(R.string.upload_zip_part_auto) to null,
                        "500 MB" to (500L * 1024 * 1024),
                        "1 GB" to (1024L * 1024 * 1024),
                    )
                    options.forEach { (label, cap) ->
                        FilterChip(
                            selected = partCapOverride == cap,
                            onClick = {
                                partCapOverride = cap
                                onMaxPartSizeChanged?.invoke(cap)
                            },
                            label = { Text(label) },
                        )
                    }
                }

                if (showExcludePatterns) {
                    OutlinedTextField(
                        value = excludePatterns,
                        onValueChange = {
                            excludePatterns = it
                            onExcludePatternsChanged?.invoke(it)
                        },
                        label = { Text(stringResource(R.string.upload_zip_excludes_label)) },
                        placeholder = { Text("*.tmp, .thumbnails/, cache/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(
                            value = encrypt,
                            role = Role.Switch,
                            onValueChange = {
                                encrypt = it
                                error = null
                            },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    ) {
                        Text(
                            stringResource(R.string.upload_zip_password_protect),
                            style = TgTypography.Display.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.upload_zip_encrypt_desc),
                            style = TgTypography.Display.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = encrypt,
                        onCheckedChange = null,
                    )
                }

                if (encrypt) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = useVaultKey,
                            onClick = {
                                useVaultKey = true
                                error = null
                            },
                            label = { Text(stringResource(R.string.upload_encrypt_vault_key)) },
                        )
                        FilterChip(
                            selected = !useVaultKey,
                            onClick = {
                                useVaultKey = false
                                error = null
                            },
                            label = { Text(stringResource(R.string.upload_encrypt_custom_password)) },
                        )
                    }

                    if (useVaultKey) {
                        Text(
                            stringResource(R.string.upload_encrypt_vault_hint),
                            style = TgTypography.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                error = null
                            },
                            label = { Text(stringResource(R.string.upload_zip_password)) },
                            singleLine = true,
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Next,
                            ),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = stringResource(
                                            if (showPassword) {
                                                R.string.upload_zip_hide_password
                                            } else {
                                                R.string.upload_zip_show_password
                                            },
                                        ),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )
                        if (password.isNotEmpty()) {
                            PasswordStrengthMeter(password = password)
                        }
                        OutlinedTextField(
                            value = confirm,
                            onValueChange = {
                                confirm = it
                                error = null
                            },
                            label = { Text(stringResource(R.string.upload_zip_confirm)) },
                            singleLine = true,
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(
                            value = wifiOnly,
                            role = Role.Switch,
                            onValueChange = { wifiOnly = it },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    ) {
                        Text(
                            stringResource(R.string.upload_wifi_only_title),
                            style = TgTypography.Display.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.upload_wifi_only_desc),
                            style = TgTypography.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = wifiOnly, onCheckedChange = null)
                }

                error?.let {
                    Text(it, style = TgTypography.Display.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmedName = zipName.trim().removeSuffix(".zip")
                when {
                    trimmedName.isBlank() -> error = errEmptyName
                    !encrypt -> onConfirm(
                        ZipUploadOptions(
                            name = trimmedName,
                            password = null,
                            useVaultKey = false,
                            compressionMode = compressionMode,
                            maxPartBytesOverride = partCapOverride,
                            excludePatterns = excludePatterns.trim(),
                            wifiOnly = wifiOnly,
                        ),
                    )
                    useVaultKey -> onConfirm(
                        ZipUploadOptions(
                            name = trimmedName,
                            password = null,
                            useVaultKey = true,
                            compressionMode = compressionMode,
                            maxPartBytesOverride = partCapOverride,
                            excludePatterns = excludePatterns.trim(),
                            wifiOnly = wifiOnly,
                        ),
                    )
                    password.isBlank() -> error = errEmptyPwd
                    password != confirm -> error = errMismatch
                    password.length < 4 -> error = errShort
                    else -> onConfirm(
                        ZipUploadOptions(
                            name = trimmedName,
                            password = password,
                            useVaultKey = false,
                            compressionMode = compressionMode,
                            maxPartBytesOverride = partCapOverride,
                            excludePatterns = excludePatterns.trim(),
                            wifiOnly = wifiOnly,
                        ),
                    )
                }
            }) {
                Text(
                    stringResource(R.string.upload_zip_action),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.upload_zip_cancel),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
internal fun PasswordStrengthMeter(password: String) {
    val score = remember(password) { computePasswordStrength(password) }
    val (labelRes, color) = when {
        score < 0.4f -> R.string.upload_pwd_weak to MaterialTheme.colorScheme.error
        score < 0.75f -> R.string.upload_pwd_medium to MaterialTheme.colorScheme.tertiary
        else -> R.string.upload_pwd_strong to MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { score },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp)),
        )
        Text(
            text = stringResource(labelRes),
            style = TgTypography.Caption,
            color = color,
        )
    }
}

internal fun computePasswordStrength(pwd: String): Float {
    if (pwd.isEmpty()) return 0f
    var points = 0f
    if (pwd.length >= 6) points += 0.25f
    if (pwd.length >= 10) points += 0.25f
    if (pwd.any { it.isUpperCase() } && pwd.any { it.isLowerCase() }) points += 0.2f
    if (pwd.any { it.isDigit() }) points += 0.15f
    if (pwd.any { !it.isLetterOrDigit() }) points += 0.15f
    return points.coerceIn(0.15f, 1f)
}
