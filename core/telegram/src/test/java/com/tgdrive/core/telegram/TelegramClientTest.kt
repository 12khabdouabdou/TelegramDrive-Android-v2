package com.tgdrive.core.telegram

import com.google.common.truth.Truth.assertThat
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Test

class AuthorizationStateTest {

    @Test
    fun `WaitPhoneNumber maps correctly`() {
        val st = TdApi.AuthorizationStateWaitPhoneNumber().toDomain()
        assertThat(st).isEqualTo(AuthorizationState.WaitPhoneNumber)
    }

    @Test
    fun `WaitCode maps correctly`() {
        val st = TdApi.AuthorizationStateWaitCode().toDomain()
        assertThat(st).isEqualTo(AuthorizationState.WaitCode)
    }

    @Test
    fun `WaitPassword maps correctly`() {
        val st = TdApi.AuthorizationStateWaitPassword().toDomain()
        assertThat(st).isEqualTo(AuthorizationState.WaitPassword)
    }

    @Test
    fun `Ready maps correctly`() {
        val st = TdApi.AuthorizationStateReady().toDomain()
        assertThat(st).isEqualTo(AuthorizationState.Ready)
    }

    @Test
    fun `Closed maps correctly`() {
        val st = TdApi.AuthorizationStateClosed().toDomain()
        assertThat(st).isEqualTo(AuthorizationState.Closed)
    }

    @Test
    fun `WaitInitialization maps correctly`() {
        val st = TdApi.AuthorizationStateWaitTdlibParameters().toDomain()
        assertThat(st).isEqualTo(AuthorizationState.WaitInitialization)
    }

    @Test
    fun `LoggingOut maps to Closing`() {
        val st = TdApi.AuthorizationStateLoggingOut().toDomain()
        assertThat(st).isInstanceOf(AuthorizationState.Closing::class.java)
    }

    @Test
    fun `WaitRegistration maps correctly`() {
        val st = TdApi.AuthorizationStateWaitRegistration().toDomain()
        assertThat(st).isEqualTo(AuthorizationState.WaitRegistration)
    }
}

class TelegramExceptionTest {

    @Test
    fun `fromError captures code and message`() {
        val err = TdApi.Error().apply {
            code = 400
            message = "PHONE_NUMBER_INVALID"
        }
        val ex = TelegramException.fromError(err)
        assertThat(ex.code).isEqualTo(400)
        assertThat(ex.errorMessage).isEqualTo("PHONE_NUMBER_INVALID")
    }
}

class TelegramClientConstTest {

    @Test
    fun `MAX_FILE_SIZE is 2 GiB`() {
        assertThat(TelegramClient.MAX_FILE_SIZE).isEqualTo(2L * 1024 * 1024 * 1024)
    }
}
