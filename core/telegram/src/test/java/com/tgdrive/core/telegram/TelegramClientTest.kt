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

    @Test
    fun `WaitOtherDeviceConfirmation maps to WaitQrConfirmation`() {
        val st = TdApi.AuthorizationStateWaitOtherDeviceConfirmation("tg://login?token=abc").toDomain()
        assertThat(st).isEqualTo(AuthorizationState.WaitQrConfirmation("tg://login?token=abc"))
    }

    @Test
    fun `WaitEmailAddress and WaitEmailCode map correctly`() {
        assertThat(TdApi.AuthorizationStateWaitEmailAddress().toDomain())
            .isEqualTo(AuthorizationState.WaitEmailAddress)
        val codeState = TdApi.AuthorizationStateWaitEmailCode(
            false,
            false,
            TdApi.EmailAddressAuthenticationCodeInfo("a***@gmail.com", 6),
            null,
        ).toDomain()
        assertThat(codeState).isEqualTo(AuthorizationState.WaitEmailCode("a***@gmail.com", 6))
    }

    @Test
    fun `AuthenticationCodeInfo maps delivery channel and timeout`() {
        val info = TdApi.AuthenticationCodeInfo(
            "+15551234567",
            TdApi.AuthenticationCodeTypeTelegramMessage(5),
            TdApi.AuthenticationCodeTypeSms(5),
            60,
        ).toDomain()
        assertThat(info).isNotNull()
        assertThat(info!!.channel).isEqualTo(AuthCodeChannel.TELEGRAM_APP)
        assertThat(info.nextChannel).isEqualTo(AuthCodeChannel.SMS)
        assertThat(info.timeoutSeconds).isEqualTo(60)
        assertThat(info.codeLength).isEqualTo(5)
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
