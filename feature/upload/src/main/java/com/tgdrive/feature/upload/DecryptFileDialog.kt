package com.tgdrive.feature.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.EncryptedFileFormat
import com.tgdrive.designsystem.theme.TgTypography

@Composable
fun DecryptFileDialog(
    fileName: String,
    detectedFormat: EncryptedFileFormat?,
    hasSavedBackupPassphrase: Boolean = false,
    inProgress: Boolean = false,
    progressMessage: String? = null,
    errorMessage: String? = null,
    onDecryptAndOpen: (passphrase: String?) -> Unit,
    onDecryptAndSave: (passphrase: String?) -> Unit,
    onDismiss: () -> Unit,
    onSaveAsVaultPassphrase: ((String) -> Unit)? = null,
) {
    var passphrase by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var saveAsPassVault by remember { mutableStateOf(false) }
    val decryptedName = remember(fileName) { BackupCrypto.stripEncryptedExtension(fileName) }
    val isLegacyVaultFormat = detectedFormat == EncryptedFileFormat.VAULT_TGE1

    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.LockOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.decrypt_dialog_title),
                    style = TgTypography.Display.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "$fileName -> $decryptedName",
                    style = TgTypography.Display.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isLegacyVaultFormat) {
                    Text(
                        text = stringResource(R.string.decrypt_legacy_vault_unsupported),
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = if (hasSavedBackupPassphrase) {
                            stringResource(R.string.decrypt_passphrase_or_saved_hint)
                        } else {
                            stringResource(R.string.decrypt_passphrase_hint)
                        },
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text(stringResource(R.string.upload_zip_password)) },
                        singleLine = true,
                        enabled = !inProgress,
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = null,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (!inProgress) {
                                    val trimmed = passphrase.takeIf { it.isNotBlank() }?.trim()
                                    if (saveAsPassVault && trimmed != null && trimmed.length >= 8) {
                                        onSaveAsVaultPassphrase?.invoke(trimmed)
                                    }
                                    onDecryptAndOpen(passphrase.takeIf { it.isNotBlank() })
                                }
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    if (!hasSavedBackupPassphrase && onSaveAsVaultPassphrase != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(
                                checked = saveAsPassVault,
                                enabled = !inProgress && passphrase.trim().length >= 8,
                                onCheckedChange = { saveAsPassVault = it },
                            )
                            Text(
                                text = stringResource(R.string.decrypt_save_as_passvault),
                                style = TgTypography.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (inProgress) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    progressMessage?.let {
                        Text(
                            text = it,
                            style = TgTypography.Caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                errorMessage?.let {
                    Text(
                        text = it,
                        style = TgTypography.Display.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    OutlinedButton(
                        enabled = !inProgress && !isLegacyVaultFormat,
                        onClick = {
                            val trimmed = passphrase.takeIf { it.isNotBlank() }?.trim()
                            if (saveAsPassVault && trimmed != null && trimmed.length >= 8) {
                                onSaveAsVaultPassphrase?.invoke(trimmed)
                            }
                            onDecryptAndSave(passphrase.takeIf { it.isNotBlank() })
                        },
                    ) {
                        Text(stringResource(R.string.decrypt_action_save))
                    }
                    Button(
                        enabled = !inProgress && !isLegacyVaultFormat,
                        onClick = {
                            val trimmed = passphrase.takeIf { it.isNotBlank() }?.trim()
                            if (saveAsPassVault && trimmed != null && trimmed.length >= 8) {
                                onSaveAsVaultPassphrase?.invoke(trimmed)
                            }
                            onDecryptAndOpen(passphrase.takeIf { it.isNotBlank() })
                        },
                    ) {
                        Text(stringResource(R.string.decrypt_action_open))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(enabled = !inProgress, onClick = onDismiss) {
                        Text(stringResource(R.string.upload_zip_cancel))
                    }
                }
            }
        },
    )
}
