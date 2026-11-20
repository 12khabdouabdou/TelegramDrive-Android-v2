package com.tgdrive.core.telegram

import org.drinkless.tdlib.TdApi

enum class AuthCodeChannel {
    TELEGRAM_APP,
    SMS,
    CALL,
    FLASH_CALL,
    MISSED_CALL,
    FRAGMENT,
    FIREBASE_ANDROID,
    SMS_WORD,
    SMS_PHRASE,
    UNKNOWN,
}

data class AuthCodeInfo(
    val phoneNumber: String,
    val channel: AuthCodeChannel,
    val nextChannel: AuthCodeChannel?,
    val timeoutSeconds: Int,
    val codeLength: Int,
)

/**
 * Authorisation states we expose to the rest of the app.
 *
 * Mirrors [TdApi.AuthorizationState] but flattened to a sealed hierarchy so
 * the UI / repository layer can pattern-match without pulling TDLib types
 * transitively (which would leak JNI into every module).
 */
sealed interface AuthorizationState {
    /** TDLib has just started; no auth attempt yet. */
    data object WaitInitialization : AuthorizationState
    /** Need user's phone number (E.164). */
    data object WaitPhoneNumber : AuthorizationState
    /** Need OTP code sent by Telegram. */
    data object WaitCode : AuthorizationState
    /** QR / 1-tap Telegram app link confirmation. */
    data class WaitQrConfirmation(val link: String) : AuthorizationState
    /** Need email address for login verification. */
    data object WaitEmailAddress : AuthorizationState
    /** Need OTP code sent to email address. */
    data class WaitEmailCode(val emailPattern: String, val length: Int) : AuthorizationState
    /** Need 2FA password (account has it set). */
    data object WaitPassword : AuthorizationState
    /** Need registration (new user on this number). */
    data object WaitRegistration : AuthorizationState
    /** Other (TDLib returns "AuthorizationState..." etc.) */
    data class Other(val description: String) : AuthorizationState
    /** Authorised — TDLib is fully ready for API calls. */
    data object Ready : AuthorizationState
    /** TDLib closed cleanly. */
    data object Closed : AuthorizationState
    /** TDLib closed unexpectedly. */
    data class Closing(val reason: String) : AuthorizationState
}

internal fun TdApi.AuthorizationState.toDomain(): AuthorizationState = when (this) {
    is TdApi.AuthorizationStateWaitTdlibParameters -> AuthorizationState.WaitInitialization
    is TdApi.AuthorizationStateWaitPhoneNumber -> AuthorizationState.WaitPhoneNumber
    is TdApi.AuthorizationStateWaitCode -> AuthorizationState.WaitCode
    is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> AuthorizationState.WaitQrConfirmation(this.link.orEmpty())
    is TdApi.AuthorizationStateWaitEmailAddress -> AuthorizationState.WaitEmailAddress
    is TdApi.AuthorizationStateWaitEmailCode -> AuthorizationState.WaitEmailCode(
        emailPattern = this.codeInfo?.emailAddressPattern.orEmpty(),
        length = this.codeInfo?.length ?: 6,
    )
    is TdApi.AuthorizationStateWaitPassword -> AuthorizationState.WaitPassword
    is TdApi.AuthorizationStateWaitRegistration -> AuthorizationState.WaitRegistration
    is TdApi.AuthorizationStateReady -> AuthorizationState.Ready
    is TdApi.AuthorizationStateClosed -> AuthorizationState.Closed
    is TdApi.AuthorizationStateClosing -> AuthorizationState.Closing("closing")
    is TdApi.AuthorizationStateLoggingOut -> AuthorizationState.Closing("logging-out")
    else -> AuthorizationState.Other(this.toString())
}

internal fun TdApi.AuthenticationCodeType?.toChannel(): AuthCodeChannel = when (this) {
    is TdApi.AuthenticationCodeTypeTelegramMessage -> AuthCodeChannel.TELEGRAM_APP
    is TdApi.AuthenticationCodeTypeSms -> AuthCodeChannel.SMS
    is TdApi.AuthenticationCodeTypeCall -> AuthCodeChannel.CALL
    is TdApi.AuthenticationCodeTypeFlashCall -> AuthCodeChannel.FLASH_CALL
    is TdApi.AuthenticationCodeTypeMissedCall -> AuthCodeChannel.MISSED_CALL
    is TdApi.AuthenticationCodeTypeFragment -> AuthCodeChannel.FRAGMENT
    is TdApi.AuthenticationCodeTypeFirebaseAndroid -> AuthCodeChannel.FIREBASE_ANDROID
    is TdApi.AuthenticationCodeTypeSmsWord -> AuthCodeChannel.SMS_WORD
    is TdApi.AuthenticationCodeTypeSmsPhrase -> AuthCodeChannel.SMS_PHRASE
    else -> AuthCodeChannel.UNKNOWN
}

internal fun TdApi.AuthenticationCodeInfo?.toDomain(): AuthCodeInfo? {
    val info = this ?: return null
    val len = when (val t = info.type) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> t.length
        is TdApi.AuthenticationCodeTypeSms -> t.length
        is TdApi.AuthenticationCodeTypeCall -> t.length
        is TdApi.AuthenticationCodeTypeMissedCall -> t.length
        is TdApi.AuthenticationCodeTypeFragment -> t.length
        is TdApi.AuthenticationCodeTypeFirebaseAndroid -> t.length
        else -> 5
    }
    return AuthCodeInfo(
        phoneNumber = info.phoneNumber.orEmpty(),
        channel = info.type.toChannel(),
        nextChannel = info.nextType?.toChannel(),
        timeoutSeconds = info.timeout.coerceAtLeast(0),
        codeLength = len.coerceAtLeast(4),
    )
}
