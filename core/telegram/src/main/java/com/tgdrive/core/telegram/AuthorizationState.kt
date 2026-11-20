package com.tgdrive.core.telegram

import org.drinkless.tdlib.TdApi

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
    /** Need 2FA password (account has it set). */
    data object WaitPassword : AuthorizationState
    /** Need registration (new user on this number). */
    data object WaitRegistration : AuthorizationState
    /** Other (TDLib returns "WaitOtherDeviceConfirmation" etc.) */
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
    is TdApi.AuthorizationStateWaitPassword -> AuthorizationState.WaitPassword
    is TdApi.AuthorizationStateWaitRegistration -> AuthorizationState.WaitRegistration
    is TdApi.AuthorizationStateReady -> AuthorizationState.Ready
    is TdApi.AuthorizationStateClosed -> AuthorizationState.Closed
    is TdApi.AuthorizationStateClosing -> AuthorizationState.Closing("closing")
    is TdApi.AuthorizationStateLoggingOut -> AuthorizationState.Closing("logging-out")
    else -> AuthorizationState.Other(this.toString())
}
