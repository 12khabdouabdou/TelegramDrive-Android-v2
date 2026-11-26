package com.tgdrive.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tgdrive.core.security.AccessLockManager
import com.tgdrive.designsystem.theme.TgTypography

@Composable
fun AccessLockGate(
    title: String,
    subtitle: String,
    hasPassword: Boolean,
    canUseBiometric: Boolean,
    error: String?,
    onVerifyPassword: (String) -> Unit,
    onUseBiometric: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = TgTypography.Display.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = TgTypography.Display.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasPassword) {
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.access_lock_password_label)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (password.isNotBlank()) onVerifyPassword(password)
                    },
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
            )
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = TgTypography.Caption, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(18.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasPassword) {
                Button(
                    enabled = password.isNotBlank(),
                    onClick = { onVerifyPassword(password) },
                ) {
                    Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.vault_unlock))
                }
            }
            if (canUseBiometric) {
                if (hasPassword) {
                    OutlinedButton(onClick = onUseBiometric) {
                        Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.access_lock_use_fingerprint))
                    }
                } else {
                    Button(onClick = onUseBiometric) {
                        Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.access_lock_use_fingerprint))
                    }
                }
            }
        }
    }
}

@Composable
fun SetAccessPasswordDialog(
    title: String,
    subtitle: String,
    hasExistingPassword: Boolean,
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    onSave: (newPassword: String?, biometricEnabled: Boolean) -> Unit,
    onRemovePassword: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var useFingerprint by remember(biometricEnabled) { mutableStateOf(biometricEnabled && biometricAvailable) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val tooShortMsg = stringResource(R.string.access_lock_password_short, AccessLockManager.MIN_PASSWORD_LENGTH)
    val mismatchMsg = stringResource(R.string.access_lock_password_mismatch)
    val needAuthMsg = stringResource(R.string.access_lock_need_method)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = subtitle,
                    style = TgTypography.Display.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMsg = null
                    },
                    label = {
                        Text(
                            stringResource(
                                if (hasExistingPassword) R.string.access_lock_new_password_optional
                                else R.string.access_lock_password_label,
                            ),
                        )
                    },
                    singleLine = true,
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
                if (password.isNotEmpty() || !hasExistingPassword) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = {
                            confirm = it
                            errorMsg = null
                        },
                        label = { Text(stringResource(R.string.access_lock_password_confirm)) },
                        singleLine = true,
                        visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (biometricAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = stringResource(R.string.access_lock_enable_fingerprint),
                                style = TgTypography.Display.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Switch(
                            checked = useFingerprint,
                            onCheckedChange = {
                                useFingerprint = it
                                errorMsg = null
                            },
                        )
                    }
                }
                errorMsg?.let { msg ->
                    Text(msg, style = TgTypography.Caption, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = password.trim()
                    when {
                        trimmed.isNotEmpty() && trimmed.length < AccessLockManager.MIN_PASSWORD_LENGTH -> {
                            errorMsg = tooShortMsg
                        }
                        trimmed.isNotEmpty() && trimmed != confirm.trim() -> {
                            errorMsg = mismatchMsg
                        }
                        trimmed.isEmpty() && !hasExistingPassword && !(biometricAvailable && useFingerprint) -> {
                            errorMsg = needAuthMsg
                        }
                        else -> {
                            onSave(trimmed.ifEmpty { null }, useFingerprint)
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.access_lock_save))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasExistingPassword && onRemovePassword != null) {
                    TextButton(onClick = onRemovePassword) {
                        Text(
                            stringResource(R.string.access_lock_remove_password),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.browser_cancel))
                }
            }
        },
    )
}

@Composable
fun ConfirmAccessUnlockDialog(
    title: String,
    subtitle: String,
    hasPassword: Boolean,
    canUseBiometric: Boolean,
    error: String?,
    onVerifyPassword: (String) -> Unit,
    onUseBiometric: () -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = subtitle,
                    style = TgTypography.Display.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasPassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.access_lock_password_label)) },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (password.isNotBlank()) onVerifyPassword(password) },
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
                    )
                }
                error?.let {
                    Text(it, style = TgTypography.Caption, color = MaterialTheme.colorScheme.error)
                }
                if (canUseBiometric) {
                    OutlinedButton(
                        onClick = onUseBiometric,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.access_lock_use_fingerprint))
                    }
                }
            }
        },
        confirmButton = {
            if (hasPassword) {
                Button(
                    enabled = password.isNotBlank(),
                    onClick = { onVerifyPassword(password) },
                ) {
                    Text(stringResource(R.string.vault_unlock))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.browser_cancel))
            }
        },
    )
}
