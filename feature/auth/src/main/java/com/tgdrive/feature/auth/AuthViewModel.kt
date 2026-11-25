package com.tgdrive.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.isE164
import com.tgdrive.core.security.KeystoreHelper
import com.tgdrive.core.telegram.AuthCodeInfo
import com.tgdrive.core.telegram.AuthorizationState
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.telegram.TelegramException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Steps in the auth flow:
 *
 * 1. [ApiCreds]      — user enters API ID + Hash (kept in EncryptedSharedPreferences)
 * 2. [Phone]         — user enters E.164 phone number
 * 3. [Code]          — user enters the OTP Telegram sent
 * 4. [Password]      — user enters 2FA password (only if account has it)
 * 5. [Registration]  — user enters first/last name for a brand new Telegram account
 * 6. [Done]          — TDLib reached [AuthorizationState.Ready]
 */
sealed interface AuthStep {
    data object ApiCreds : AuthStep
    data object Phone : AuthStep
    data object Code : AuthStep
    data object QrLogin : AuthStep
    data object EmailAddress : AuthStep
    data object EmailCode : AuthStep
    data object Password : AuthStep
    data object Registration : AuthStep
    data object Done : AuthStep
}

data class AuthState(
    val step: AuthStep = AuthStep.ApiCreds,
    val apiId: String = "",
    val apiHash: String = "",
    val phone: String = "",
    val code: String = "",
    val codeInfo: AuthCodeInfo? = null,
    val resendWaitSeconds: Int = 0,
    val qrLink: String = "",
    val email: String = "",
    val emailCode: String = "",
    val emailPattern: String = "",
    val password: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val attemptsRemaining: Int = 3,
    val cooldownSeconds: Int = 0,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val telegramClient: TelegramClient,
    private val keystore: KeystoreHelper,
    private val dispatchers: DispatchersProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private var cooldownJob: Job? = null
    private var resendTimerJob: Job? = null

    init {
        observeAuthState()
        observeCodeInfo()
        loadStoredApiCreds()
    }

    private fun mapAuthStep(st: AuthorizationState): AuthStep? = when (st) {
        AuthorizationState.WaitInitialization -> AuthStep.ApiCreds
        AuthorizationState.WaitPhoneNumber -> AuthStep.Phone
        AuthorizationState.WaitCode -> AuthStep.Code
        is AuthorizationState.WaitQrConfirmation -> AuthStep.QrLogin
        AuthorizationState.WaitEmailAddress -> AuthStep.EmailAddress
        is AuthorizationState.WaitEmailCode -> AuthStep.EmailCode
        AuthorizationState.WaitPassword -> AuthStep.Password
        AuthorizationState.WaitRegistration -> AuthStep.Registration
        AuthorizationState.Ready -> AuthStep.Done
        else -> null
    }

    private fun observeAuthState() {
        viewModelScope.launch(dispatchers.io) {
            telegramClient.authState.collect { st ->
                _state.update { prev ->
                    when (st) {
                        AuthorizationState.WaitInitialization ->
                            if (prev.step != AuthStep.ApiCreds) prev.copy(step = AuthStep.ApiCreds, loading = false, error = null) else prev
                        AuthorizationState.WaitPhoneNumber ->
                            if (prev.step != AuthStep.Phone) prev.copy(step = AuthStep.Phone, loading = false, error = null) else prev
                        AuthorizationState.WaitCode ->
                            if (prev.step != AuthStep.Code) prev.copy(step = AuthStep.Code, loading = false, error = null) else prev
                        is AuthorizationState.WaitQrConfirmation ->
                            prev.copy(step = AuthStep.QrLogin, qrLink = st.link, loading = false, error = null)
                        AuthorizationState.WaitEmailAddress ->
                            if (prev.step != AuthStep.EmailAddress) prev.copy(step = AuthStep.EmailAddress, loading = false, error = null) else prev
                        is AuthorizationState.WaitEmailCode ->
                            prev.copy(step = AuthStep.EmailCode, emailPattern = st.emailPattern, loading = false, error = null)
                        AuthorizationState.WaitPassword ->
                            if (prev.step != AuthStep.Password) prev.copy(step = AuthStep.Password, loading = false, error = null) else prev
                        AuthorizationState.WaitRegistration ->
                            if (prev.step != AuthStep.Registration) prev.copy(step = AuthStep.Registration, loading = false, error = null) else prev
                        AuthorizationState.Ready ->
                            if (prev.step != AuthStep.Done) prev.copy(step = AuthStep.Done, loading = false, error = null) else prev
                        else -> prev
                    }
                }
            }
        }
    }

    private fun observeCodeInfo() {
        viewModelScope.launch(dispatchers.io) {
            telegramClient.authCodeInfo.collect { info ->
                _state.update { it.copy(codeInfo = info) }
                if (info != null && info.timeoutSeconds > 0) {
                    startResendTimer(info.timeoutSeconds)
                } else {
                    resendTimerJob?.cancel()
                    _state.update { it.copy(resendWaitSeconds = 0) }
                }
            }
        }
    }

    private fun loadStoredApiCreds() {
        keystore.getApiCreds()?.let { creds ->
            _state.update { it.copy(apiId = creds.apiId, apiHash = creds.apiHash) }
        }
    }

    // ----------------------------------------------------------------- bindings

    fun onApiIdChanged(v: String) =
        _state.update { it.copy(apiId = v.filter { c -> c.isDigit() }, error = null) }

    fun onApiHashChanged(v: String) =
        _state.update { it.copy(apiHash = v, error = null) }

    fun onPhoneChanged(v: String) =
        _state.update { it.copy(phone = v, error = null) }

    fun onCodeChanged(v: String) =
        _state.update { it.copy(code = v.filter { c -> c.isDigit() }.take(6), error = null) }

    fun onEmailChanged(v: String) =
        _state.update { it.copy(email = v, error = null) }

    fun onEmailCodeChanged(v: String) =
        _state.update { it.copy(emailCode = v.filter { c -> c.isDigit() }.take(8), error = null) }

    fun onPasswordChanged(v: String) =
        _state.update { it.copy(password = v, error = null) }

    fun onFirstNameChanged(v: String) =
        _state.update { it.copy(firstName = v, error = null) }

    fun onLastNameChanged(v: String) =
        _state.update { it.copy(lastName = v, error = null) }

    fun clearError() = _state.update { it.copy(error = null) }

    fun goToApiCreds() =
        _state.update { it.copy(step = AuthStep.ApiCreds, loading = false, error = null) }

    fun goToPhone() =
        _state.update { it.copy(step = AuthStep.Phone, loading = false, error = null) }

    // ----------------------------------------------------------------- actions

    fun submitApiCreds() {
        val s = _state.value
        val trimmedHash = s.apiHash.trim()
        if (s.apiId.isBlank() || trimmedHash.isBlank()) {
            _state.update { it.copy(error = "Both API ID and Hash are required") }
            return
        }
        val parsedId = s.apiId.trim().toIntOrNull()
        if (parsedId == null || parsedId <= 0) {
            _state.update { it.copy(error = "API ID must be a valid positive number") }
            return
        }
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(loading = true, error = null) }
            try {
                keystore.saveApiCreds(parsedId.toString(), trimmedHash)

                telegramClient.initialize(
                    apiId = parsedId,
                    apiHash = trimmedHash,
                    sessionDir = SESSION_DIR,
                )
                val nextStep = mapAuthStep(telegramClient.authState.value) ?: AuthStep.Phone
                _state.update { it.copy(loading = false, step = nextStep) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: "Init failed") }
            }
        }
    }

    fun submitPhone() {
        val cleaned = _state.value.phone.replace(Regex("[\\s\\-()]"), "").trim()
        val normalized = if (cleaned.isNotEmpty() && !cleaned.startsWith("+")) "+$cleaned" else cleaned
        if (!normalized.isE164()) {
            _state.update { it.copy(error = "Enter a valid E.164 phone number (with country code, e.g. +15551234567)") }
            return
        }
        _state.update { it.copy(phone = normalized, code = "", error = null) }
        runAuthAction { telegramClient.sendPhoneNumber(normalized) }
    }

    fun startQrLogin() {
        runAuthAction { telegramClient.requestQrCodeLogin() }
    }

    fun resendCode() {
        val s = _state.value
        if (s.resendWaitSeconds > 0 || s.cooldownSeconds > 0 || s.loading) return
        runAuthAction { telegramClient.resendAuthCode() }
    }

    fun submitEmail() {
        val email = _state.value.email.trim()
        if (email.isBlank() || !email.contains("@")) {
            _state.update { it.copy(error = "Enter a valid email address") }
            return
        }
        runAuthAction { telegramClient.sendEmailAddress(email) }
    }

    fun submitEmailCode() {
        val code = _state.value.emailCode.trim()
        if (code.length < 4) {
            _state.update { it.copy(error = "Code must be at least 4 digits") }
            return
        }
        runAuthAction { telegramClient.sendEmailCode(code) }
    }

    fun submitCode() {
        val s = _state.value
        if (s.code.length < 4) {
            _state.update { it.copy(error = "Code must be at least 4 digits") }
            return
        }
        runAuthAction {
            try {
                telegramClient.sendAuthCode(s.code)
            } catch (e: TelegramException) {
                val isInvalidCode = e.code == 400 && (e.errorMessage?.contains("PHONE_CODE_INVALID", ignoreCase = true) == true)
                if (isInvalidCode) {
                    val left = (_state.value.attemptsRemaining - 1).coerceAtLeast(0)
                    _state.update { it.copy(attemptsRemaining = left) }
                    if (left == 0) startCooldown()
                }
                throw e
            }
        }
    }

    fun submitPassword() {
        val s = _state.value
        if (s.password.isBlank()) {
            _state.update { it.copy(error = "Password required") }
            return
        }
        runAuthAction { telegramClient.sendAuthPassword(s.password) }
    }

    fun submitRegistration() {
        val s = _state.value
        val first = s.firstName.trim()
        if (first.isBlank()) {
            _state.update { it.copy(error = "First name is required") }
            return
        }
        runAuthAction { telegramClient.registerNewUser(first, s.lastName.trim()) }
    }

    private fun runAuthAction(block: suspend () -> Unit) {
        if (_state.value.loading) return
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(loading = true, error = null) }
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramException) {
                val raw = e.errorMessage.orEmpty()
                val friendly = when {
                    raw.contains("UPDATE_APP_TO_LOGIN", ignoreCase = true) ||
                        raw.contains("PHONE_NUMBER_APP_SIGNUP_FORBIDDEN", ignoreCase = true) ->
                        "Telegram blocked direct SMS login for this API key ($raw). Use \"Sign in with Telegram app / QR\" instead."
                    raw.contains("API_ID_INVALID", ignoreCase = true) ->
                        "Invalid Telegram API ID or API Hash. Double-check your credentials at my.telegram.org."
                    raw.contains("PHONE_NUMBER_BANNED", ignoreCase = true) ->
                        "This phone number is banned by Telegram."
                    raw.contains("FLOOD_WAIT", ignoreCase = true) ->
                        "Too many login attempts ($raw). Wait before retrying, or try QR sign-in later."
                    else -> e.message ?: "Authentication error"
                }
                _state.update { it.copy(loading = false, error = friendly) }
            } catch (t: Throwable) {
                Log.w("Auth action failed", t)
                _state.update { it.copy(loading = false, error = t.message ?: "Unexpected error") }
            } finally {
                if (_state.value.error == null) {
                    _state.update { it.copy(loading = false) }
                }
            }
        }
    }

    private fun startResendTimer(seconds: Int) {
        resendTimerJob?.cancel()
        resendTimerJob = viewModelScope.launch(dispatchers.io) {
            for (sec in seconds downTo 1) {
                _state.update { it.copy(resendWaitSeconds = sec) }
                delay(1000)
            }
            _state.update { it.copy(resendWaitSeconds = 0) }
        }
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        cooldownJob = viewModelScope.launch(dispatchers.io) {
            for (sec in 60 downTo 1) {
                _state.update { it.copy(cooldownSeconds = sec) }
                delay(1000)
            }
            _state.update { it.copy(cooldownSeconds = 0, attemptsRemaining = 3) }
        }
    }

    companion object {
        private const val SESSION_DIR = "tgdrive_tdlib"
    }
}
