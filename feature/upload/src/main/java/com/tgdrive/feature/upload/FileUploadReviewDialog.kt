package com.tgdrive.feature.upload

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.download.NormalUploadOptions
import com.tgdrive.core.download.UploadConflictStrategy
import com.tgdrive.core.download.UploadEncryptionSpec
import com.tgdrive.core.download.UploadFileItem
import com.tgdrive.core.zip.ZipCompressionMode
import com.tgdrive.designsystem.theme.TgTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface FileReviewAction {
    data class NormalUpload(
        val files: List<UploadFileItem>,
        val options: NormalUploadOptions,
    ) : FileReviewAction

    data class PackAsZip(
        val files: List<UploadFileItem>,
        val options: ZipUploadOptions,
    ) : FileReviewAction
}

@Composable
fun FileUploadReviewDialog(
    initialFiles: List<UploadFileItem>,
    onDismiss: () -> Unit,
    onConfirm: (FileReviewAction) -> Unit,
) {
    val files = remember(initialFiles) {
        mutableStateListOf<UploadFileItem>().apply { addAll(initialFiles) }
    }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var renameDraft by remember { mutableStateOf("") }

    var packAsZip by remember { mutableStateOf(false) }
    var zipName by remember {
        mutableStateOf("files_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}")
    }
    var compressionMode by remember { mutableStateOf(ZipCompressionMode.SMART) }
    var conflictStrategy by remember { mutableStateOf(UploadConflictStrategy.KEEP_BOTH) }
    var autoSplitOversized by remember { mutableStateOf(true) }

    var encrypt by remember { mutableStateOf(false) }
    var useVaultKey by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val errEmptyName = stringResource(R.string.upload_zip_error_empty_name)
    val errEmptyPwd = stringResource(R.string.upload_zip_error_empty_password)
    val errMismatch = stringResource(R.string.upload_zip_error_mismatch)
    val errShort = stringResource(R.string.upload_zip_error_short)

    val totalBytes = files.sumOf { it.sizeBytes.coerceAtLeast(0L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.CloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.upload_review_title, files.size, totalBytes.toHumanSize()),
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
                if (files.isEmpty()) {
                    Text(
                        stringResource(R.string.upload_review_empty),
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(vertical = 4.dp),
                    ) {
                        itemsIndexed(files) { idx, item ->
                            if (editingIndex == idx) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = renameDraft,
                                        onValueChange = { renameDraft = it },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = {
                                        val clean = renameDraft.trim()
                                        if (clean.isNotEmpty() && idx in files.indices) {
                                            files[idx] = files[idx].copy(displayName = clean)
                                        }
                                        editingIndex = -1
                                    }) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = stringResource(R.string.upload_review_save_name),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.displayName,
                                            style = TgTypography.Display.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = item.sizeBytes.toHumanSize(),
                                            style = TgTypography.Caption,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            editingIndex = idx
                                            renameDraft = item.displayName
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Edit,
                                            contentDescription = stringResource(R.string.upload_review_rename),
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (idx in files.indices) {
                                                files.removeAt(idx)
                                                editingIndex = -1
                                            }
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.upload_review_remove),
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Pack as ZIP switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(
                            value = packAsZip,
                            role = Role.Switch,
                            onValueChange = { packAsZip = it },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.upload_review_pack_zip),
                            style = TgTypography.Display.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.upload_review_pack_zip_desc),
                            style = TgTypography.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = packAsZip, onCheckedChange = null)
                }

                if (packAsZip) {
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
                } else {
                    Text(
                        stringResource(R.string.upload_review_conflict_label),
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
                            selected = conflictStrategy == UploadConflictStrategy.KEEP_BOTH,
                            onClick = { conflictStrategy = UploadConflictStrategy.KEEP_BOTH },
                            label = { Text(stringResource(R.string.upload_conflict_keep_both)) },
                        )
                        FilterChip(
                            selected = conflictStrategy == UploadConflictStrategy.SKIP,
                            onClick = { conflictStrategy = UploadConflictStrategy.SKIP },
                            label = { Text(stringResource(R.string.upload_conflict_skip)) },
                        )
                        FilterChip(
                            selected = conflictStrategy == UploadConflictStrategy.REPLACE,
                            onClick = { conflictStrategy = UploadConflictStrategy.REPLACE },
                            label = { Text(stringResource(R.string.upload_conflict_replace)) },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .toggleable(
                                value = autoSplitOversized,
                                role = Role.Switch,
                                onValueChange = { autoSplitOversized = it },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.upload_review_auto_split),
                                style = TgTypography.Display.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                stringResource(R.string.upload_review_auto_split_desc),
                                style = TgTypography.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoSplitOversized, onCheckedChange = null)
                    }
                }

                // Encryption section (for both normal files -> .tgenc and packed ZIP!)
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
                        .padding(vertical = 4.dp),
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
                            .padding(horizontal = 10.dp),
                    ) {
                        Text(
                            stringResource(R.string.upload_review_encrypt_title),
                            style = TgTypography.Display.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (packAsZip) {
                                stringResource(R.string.upload_zip_encrypt_desc)
                            } else {
                                stringResource(R.string.upload_review_encrypt_files_desc)
                            },
                            style = TgTypography.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = encrypt, onCheckedChange = null)
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
                                        contentDescription = null,
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

                error?.let {
                    Text(it, style = TgTypography.Display.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = files.isNotEmpty(),
                onClick = {
                    val cleanZip = zipName.trim().removeSuffix(".zip")
                    when {
                        packAsZip && cleanZip.isBlank() -> error = errEmptyName
                        encrypt && !useVaultKey && password.isBlank() -> error = errEmptyPwd
                        encrypt && !useVaultKey && password != confirm -> error = errMismatch
                        encrypt && !useVaultKey && password.length < 4 -> error = errShort
                        packAsZip -> {
                            onConfirm(
                                FileReviewAction.PackAsZip(
                                    files = files.toList(),
                                    options = ZipUploadOptions(
                                        name = cleanZip,
                                        password = if (encrypt && !useVaultKey) password else null,
                                        useVaultKey = encrypt && useVaultKey,
                                        compressionMode = compressionMode,
                                    ),
                                ),
                            )
                        }
                        else -> {
                            onConfirm(
                                FileReviewAction.NormalUpload(
                                    files = files.toList(),
                                    options = NormalUploadOptions(
                                        conflictStrategy = conflictStrategy,
                                        encryption = UploadEncryptionSpec(
                                            enabled = encrypt,
                                            useVaultKey = useVaultKey,
                                            customPassphrase = if (encrypt && !useVaultKey) password else null,
                                        ),
                                        autoSplitOversized = autoSplitOversized,
                                    ),
                                ),
                            )
                        }
                    }
                },
            ) {
                Text(
                    when {
                        encrypt -> stringResource(R.string.upload_encrypt_and_upload)
                        packAsZip -> stringResource(R.string.upload_pack_and_upload)
                        else -> stringResource(R.string.upload_zip_action)
                    },
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
