package com.tgdrive.feature.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tgdrive.core.telegram.AuthCodeChannel
import com.tgdrive.designsystem.theme.TgTypography

/**
 * Multi-step Telegram authentication flow (API Creds → Phone → OTP → 2FA Password → Registration → Done).
 * Supports light/dark MaterialTheme schemes, IME & window insets, inline dismissible errors,
 * step backtracking, password visibility toggle, and loading feedback.
 */
@Composable
fun AuthFlow(
    vm: AuthViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.auth_title),
                style = TgTypography.Display.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.auth_subtitle),
                style = TgTypography.Display.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(24.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (state.step) {
                    AuthStep.ApiCreds -> ApiCredsForm(state = state, vm = vm)
                    AuthStep.Phone -> PhoneForm(state = state, vm = vm)
                    AuthStep.Code -> CodeForm(state = state, vm = vm)
                    AuthStep.QrLogin -> QrLoginForm(state = state, vm = vm)
                    AuthStep.EmailAddress -> EmailAddressForm(state = state, vm = vm)
                    AuthStep.EmailCode -> EmailCodeForm(state = state, vm = vm)
                    AuthStep.Password -> PasswordForm(state = state, vm = vm)
                    AuthStep.Registration -> RegistrationForm(state = state, vm = vm)
                    AuthStep.Done -> DoneScreen()
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    error: String?,
    onDismiss: () -> Unit,
) {
    if (error.isNullOrBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = error,
            style = TgTypography.Display.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.auth_error_dismiss),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    isPassword: Boolean = false,
    revealPassword: Boolean = false,
    onToggleReveal: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) {
            { Text(placeholder) }
        } else {
            null
        },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (isPassword && !revealPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = if (isPassword && onToggleReveal != null) {
            {
                IconButton(onClick = onToggleReveal) {
                    Icon(
                        imageVector = if (revealPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (revealPassword) R.string.auth_password_hide else R.string.auth_password_show,
                        ),
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = onImeAction?.let { fn -> { fn() } },
            onNext = onImeAction?.let { fn -> { fn() } },
        ),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AuthButton(
    text: String,
    loading: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.auth_loading), fontWeight = FontWeight.SemiBold)
        } else {
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ApiCredsForm(state: AuthState, vm: AuthViewModel) {
    Text(
        text = stringResource(R.string.auth_api_help),
        style = TgTypography.Display.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AuthField(
        value = state.apiId,
        onValueChange = vm::onApiIdChanged,
        label = stringResource(R.string.auth_api_id),
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Next,
        enabled = !state.loading,
    )
    AuthField(
        value = state.apiHash,
        onValueChange = vm::onApiHashChanged,
        label = stringResource(R.string.auth_api_hash),
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Done,
        onImeAction = vm::submitApiCreds,
        enabled = !state.loading,
    )
    ErrorBanner(error = state.error, onDismiss = vm::clearError)
    AuthButton(
        text = stringResource(R.string.auth_api_save),
        loading = state.loading,
        onClick = vm::submitApiCreds,
    )
}

@Composable
private fun ColumnScope.PhoneForm(state: AuthState, vm: AuthViewModel) {
    Text(
        text = stringResource(R.string.auth_welcome_back),
        style = TgTypography.Display.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
    )
    AuthField(
        value = state.phone,
        onValueChange = vm::onPhoneChanged,
        label = stringResource(R.string.auth_phone_label),
        placeholder = stringResource(R.string.auth_phone_hint),
        keyboardType = KeyboardType.Phone,
        imeAction = ImeAction.Done,
        onImeAction = vm::submitPhone,
        enabled = !state.loading,
    )
    ErrorBanner(error = state.error, onDismiss = vm::clearError)
    AuthButton(
        text = stringResource(R.string.auth_phone_continue),
        loading = state.loading,
        onClick = vm::submitPhone,
    )
    OutlinedButton(
        onClick = vm::startQrLogin,
        enabled = !state.loading,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.auth_qr_button),
            fontWeight = FontWeight.Medium,
        )
    }
    Text(
        text = stringResource(R.string.auth_qr_hint),
        style = TgTypography.Caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    TextButton(
        onClick = vm::goToApiCreds,
        enabled = !state.loading,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
        Text(
            text = stringResource(R.string.auth_change_api_creds),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ColumnScope.CodeForm(state: AuthState, vm: AuthViewModel) {
    val displayPhone = state.codeInfo?.phoneNumber?.ifBlank { state.phone } ?: state.phone
    val deliveryText = when (state.codeInfo?.channel) {
        AuthCodeChannel.TELEGRAM_APP -> stringResource(R.string.auth_code_via_telegram_app, displayPhone)
        AuthCodeChannel.SMS, AuthCodeChannel.SMS_WORD, AuthCodeChannel.SMS_PHRASE ->
            stringResource(R.string.auth_code_via_sms, displayPhone)
        AuthCodeChannel.CALL, AuthCodeChannel.FLASH_CALL, AuthCodeChannel.MISSED_CALL ->
            stringResource(R.string.auth_code_via_call, displayPhone)
        AuthCodeChannel.FRAGMENT -> stringResource(R.string.auth_code_via_fragment, displayPhone)
        AuthCodeChannel.FIREBASE_ANDROID -> stringResource(R.string.auth_code_via_firebase, displayPhone)
        else -> stringResource(R.string.auth_code_via_unknown, displayPhone)
    }
    val resendLabel = when {
        state.resendWaitSeconds > 0 -> stringResource(R.string.auth_code_resend_wait, state.resendWaitSeconds)
        state.codeInfo?.nextChannel == AuthCodeChannel.SMS -> stringResource(R.string.auth_code_resend_sms)
        state.codeInfo?.nextChannel == AuthCodeChannel.CALL ||
            state.codeInfo?.nextChannel == AuthCodeChannel.FLASH_CALL ||
            state.codeInfo?.nextChannel == AuthCodeChannel.MISSED_CALL ->
            stringResource(R.string.auth_code_resend_call)
        else -> stringResource(R.string.auth_code_resend)
    }
    val canResend = state.resendWaitSeconds == 0 && state.cooldownSeconds == 0 && !state.loading

    Text(
        text = stringResource(R.string.auth_code_title),
        style = TgTypography.Display.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = deliveryText,
        style = TgTypography.Display.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AuthField(
        value = state.code,
        onValueChange = vm::onCodeChanged,
        label = stringResource(R.string.auth_code_title),
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Done,
        onImeAction = vm::submitCode,
        enabled = !state.loading && state.cooldownSeconds == 0,
    )
    if (state.attemptsRemaining < 3) {
        Text(
            text = stringResource(R.string.auth_code_attempts_remaining, state.attemptsRemaining),
            style = TgTypography.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.cooldownSeconds > 0) {
        Text(
            text = stringResource(R.string.auth_code_cooldown, state.cooldownSeconds),
            style = TgTypography.Caption,
            color = MaterialTheme.colorScheme.error,
        )
    }
    ErrorBanner(error = state.error, onDismiss = vm::clearError)
    AuthButton(
        text = stringResource(R.string.auth_phone_continue),
        loading = state.loading,
        enabled = state.cooldownSeconds == 0,
        onClick = vm::submitCode,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(
            onClick = vm::goToPhone,
            enabled = !state.loading,
        ) {
            Text(
                text = stringResource(R.string.auth_change_phone),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = vm::resendCode,
            enabled = canResend,
        ) {
            Text(
                text = resendLabel,
                color = if (canResend) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
    TextButton(
        onClick = vm::startQrLogin,
        enabled = !state.loading,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
        Text(
            text = stringResource(R.string.auth_code_no_code_qr),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ColumnScope.QrLoginForm(state: AuthState, vm: AuthViewModel) {
    val context = LocalContext.current
    var localNotice by remember(state.qrLink) { mutableStateOf<String?>(null) }
    val missingAppMsg = stringResource(R.string.auth_qr_no_telegram_app)
    val copiedMsg = stringResource(R.string.auth_qr_link_copied)

    val qrMatrix = remember(state.qrLink) {
        if (state.qrLink.isBlank()) {
            null
        } else {
            runCatching { QRCodeWriter().encode(state.qrLink, BarcodeFormat.QR_CODE, 48, 48) }.getOrNull()
        }
    }

    Text(
        text = stringResource(R.string.auth_qr_title),
        style = TgTypography.Display.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = stringResource(R.string.auth_qr_subtitle),
        style = TgTypography.Display.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (qrMatrix != null) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(180.dp)) {
                val w = qrMatrix.width
                val h = qrMatrix.height
                val cellW = size.width / w
                val cellH = size.height / h
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        if (qrMatrix.get(x, y)) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(x * cellW, y * cellH),
                                size = Size(cellW + 0.5f, cellH + 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
    ErrorBanner(error = state.error ?: localNotice, onDismiss = {
        localNotice = null
        vm.clearError()
    })
    AuthButton(
        text = stringResource(R.string.auth_qr_open_telegram),
        loading = state.loading,
        enabled = state.qrLink.isNotBlank(),
        onClick = {
            localNotice = null
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(state.qrLink))
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                localNotice = missingAppMsg
            } catch (t: Throwable) {
                localNotice = t.message ?: missingAppMsg
            }
        },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Telegram login link", state.qrLink).apply {
                    description.extras = PersistableBundle().apply {
                        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ClipDescription.EXTRA_IS_SENSITIVE
                        } else {
                            "android.content.extra.IS_SENSITIVE"
                        }
                        putBoolean(key, true)
                    }
                }
                cm.setPrimaryClip(clip)
                localNotice = copiedMsg
            },
            enabled = state.qrLink.isNotBlank(),
        ) {
            Text(
                text = stringResource(R.string.auth_qr_copy_link),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        TextButton(
            onClick = vm::goToPhone,
            enabled = !state.loading,
        ) {
            Text(
                text = stringResource(R.string.auth_qr_use_phone),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ColumnScope.EmailAddressForm(state: AuthState, vm: AuthViewModel) {
    Text(
        text = stringResource(R.string.auth_email_title),
        style = TgTypography.Display.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = stringResource(R.string.auth_email_subtitle),
        style = TgTypography.Display.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AuthField(
        value = state.email,
        onValueChange = vm::onEmailChanged,
        label = stringResource(R.string.auth_email_label),
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Done,
        onImeAction = vm::submitEmail,
        enabled = !state.loading,
    )
    ErrorBanner(error = state.error, onDismiss = vm::clearError)
    AuthButton(
        text = stringResource(R.string.auth_phone_continue),
        loading = state.loading,
        onClick = vm::submitEmail,
    )
    TextButton(
        onClick = vm::goToPhone,
        enabled = !state.loading,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
        Text(
            text = stringResource(R.string.auth_change_phone),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.EmailCodeForm(state: AuthState, vm: AuthViewModel) {
    Text(
        text = stringResource(R.string.auth_email_code_title),
        style = TgTypography.Display.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = stringResource(R.string.auth_email_code_subtitle, state.emailPattern.ifBlank { state.email }),
        style = TgTypography.Display.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AuthField(
        value = state.emailCode,
        onValueChange = vm::onEmailCodeChanged,
        label = stringResource(R.string.auth_code_title),
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Done,
        onImeAction = vm::submitEmailCode,
        enabled = !state.loading,
    )
    ErrorBanner(error = state.error, onDismiss = vm::clearError)
    AuthButton(
        text = stringResource(R.string.auth_phone_continue),
        loading = state.loading,
        onClick = vm::submitEmailCode,
    )
    TextButton(
        onClick = vm::goToPhone,
        enabled = !state.loading,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
        Text(
            text = stringResource(R.string.auth_change_phone),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.PasswordForm(state: AuthState, vm: AuthViewModel) {
    val context = LocalContext.current
    var reveal by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val window = context.findActivity()?.window
        val hadSecure = ((window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (!hadSecure) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (!hadSecure) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    Text(
        text = stringResource(R.string.auth_password_title),
        style = TgTypography.Display.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = stringResource(R.string.auth_password_subtitle),
        style = TgTypography.Display.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AuthField(
        value = state.password,
        onValueChange = vm::onPasswordChanged,
        label = stringResource(R.string.auth_password_label),
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
        onImeAction = vm::submitPassword,
        isPassword = true,
        revealPassword = reveal,
        onToggleReveal = { reveal = !reveal },
        enabled = !state.loading,
    )
    ErrorBanner(error = state.error, onDismiss = vm::clearError)
    AuthButton(
        text = stringResource(R.string.auth_password_continue),
        loading = state.loading,
        onClick = vm::submitPassword,
    )
    TextButton(
        onClick = vm::goToPhone,
        enabled = !state.loading,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
        Text(
            text = stringResource(R.string.auth_change_phone),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.RegistrationForm(state: AuthState, vm: AuthViewModel) {
    Text(
        text = stringResource(R.string.auth_registration_title),
        style = TgTypography.Display.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = stringResource(R.string.auth_registration_subtitle),
        style = TgTypography.Display.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AuthField(
        value = state.firstName,
        onValueChange = vm::onFirstNameChanged,
        label = stringResource(R.string.auth_first_name),
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Next,
        enabled = !state.loading,
    )
    AuthField(
        value = state.lastName,
        onValueChange = vm::onLastNameChanged,
        label = stringResource(R.string.auth_last_name),
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Done,
        onImeAction = vm::submitRegistration,
        enabled = !state.loading,
    )
    ErrorBanner(error = state.error, onDismiss = vm::clearError)
    AuthButton(
        text = stringResource(R.string.auth_registration_submit),
        loading = state.loading,
        onClick = vm::submitRegistration,
    )
    TextButton(
        onClick = vm::goToPhone,
        enabled = !state.loading,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
        Text(
            text = stringResource(R.string.auth_change_phone),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DoneScreen() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = stringResource(R.string.auth_done_title),
            style = TgTypography.Display.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.auth_done_subtitle),
            style = TgTypography.Display.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
