package com.tgdrive.feature.auth

import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.security.ApiCredentials
import com.tgdrive.core.security.KeystoreHelper
import com.tgdrive.core.telegram.AuthCodeInfo
import com.tgdrive.core.telegram.AuthorizationState
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.telegram.TelegramException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val authStateFlow = MutableStateFlow<AuthorizationState>(AuthorizationState.WaitInitialization)
    private val codeInfoFlow = MutableStateFlow<AuthCodeInfo?>(null)

    private lateinit var telegramClient: TelegramClient
    private lateinit var keystore: KeystoreHelper
    private lateinit var dispatchers: DispatchersProvider

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        telegramClient = mockk(relaxed = true) {
            every { authState } returns authStateFlow
            every { authCodeInfo } returns codeInfoFlow
        }
        keystore = mockk(relaxed = true) {
            every { getApiCreds() } returns null
            every { saveApiCreds(any(), any()) } returns true
        }
        dispatchers = object : DispatchersProvider {
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
            override val main: CoroutineDispatcher = testDispatcher
            override val unconfined: CoroutineDispatcher = testDispatcher
        }
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init preloads stored API credentials from keystore`() = runTest(testDispatcher) {
        every { keystore.getApiCreds() } returns ApiCredentials(apiId = "123456", apiHash = "abcHash")
        val vm = AuthViewModel(telegramClient, keystore, dispatchers)
        runCurrent()

        assertThat(vm.state.value.apiId).isEqualTo("123456")
        assertThat(vm.state.value.apiHash).isEqualTo("abcHash")
    }

    @Test
    fun `submitApiCreds validates blank and non-positive id and initializes client when valid`() = runTest(testDispatcher) {
        val vm = AuthViewModel(telegramClient, keystore, dispatchers)
        runCurrent()

        vm.submitApiCreds()
        assertThat(vm.state.value.error).contains("required")

        vm.onApiIdChanged("0")
        vm.onApiHashChanged("validHash")
        vm.submitApiCreds()
        assertThat(vm.state.value.error).contains("positive")

        coEvery { telegramClient.initialize(98765, "validHash", any()) } answers {
            authStateFlow.value = AuthorizationState.WaitPhoneNumber
        }
        vm.onApiIdChanged("98765")
        vm.onApiHashChanged("  validHash  ")
        vm.submitApiCreds()
        advanceUntilIdle()

        verify { keystore.saveApiCreds("98765", "validHash") }
        coVerify { telegramClient.initialize(98765, "validHash", "tgdrive_tdlib") }
        assertThat(vm.state.value.step).isEqualTo(AuthStep.Phone)
        assertThat(vm.state.value.error).isNull()
    }

    @Test
    fun `submitPhone normalizes formatting and validates E164 before sending`() = runTest(testDispatcher) {
        val vm = AuthViewModel(telegramClient, keystore, dispatchers)
        authStateFlow.value = AuthorizationState.WaitPhoneNumber
        runCurrent()

        vm.onPhoneChanged("123")
        vm.submitPhone()
        assertThat(vm.state.value.error).contains("E.164")
        coVerify(exactly = 0) { telegramClient.sendPhoneNumber(any()) }

        vm.onPhoneChanged("1 (555) 123-4567")
        vm.submitPhone()
        advanceUntilIdle()

        assertThat(vm.state.value.phone).isEqualTo("+15551234567")
        coVerify { telegramClient.sendPhoneNumber("+15551234567") }
    }

    @Test
    fun `authState emissions transition AuthStep across phone code qr email password and ready`() = runTest(testDispatcher) {
        val vm = AuthViewModel(telegramClient, keystore, dispatchers)
        runCurrent()
        assertThat(vm.state.value.step).isEqualTo(AuthStep.ApiCreds)

        authStateFlow.value = AuthorizationState.WaitPhoneNumber
        runCurrent()
        assertThat(vm.state.value.step).isEqualTo(AuthStep.Phone)

        authStateFlow.value = AuthorizationState.WaitCode
        runCurrent()
        assertThat(vm.state.value.step).isEqualTo(AuthStep.Code)

        authStateFlow.value = AuthorizationState.WaitQrConfirmation("tg://login?token=xyz")
        runCurrent()
        assertThat(vm.state.value.step).isEqualTo(AuthStep.QrLogin)
        assertThat(vm.state.value.qrLink).isEqualTo("tg://login?token=xyz")

        authStateFlow.value = AuthorizationState.WaitEmailAddress
        runCurrent()
        assertThat(vm.state.value.step).isEqualTo(AuthStep.EmailAddress)

        authStateFlow.value = AuthorizationState.WaitEmailCode(emailPattern = "a***@example.com", length = 6)
        runCurrent()
        assertThat(vm.state.value.step).isEqualTo(AuthStep.EmailCode)
        assertThat(vm.state.value.emailPattern).isEqualTo("a***@example.com")

        authStateFlow.value = AuthorizationState.WaitPassword
        runCurrent()
        assertThat(vm.state.value.step).isEqualTo(AuthStep.Password)

        authStateFlow.value = AuthorizationState.Ready
        runCurrent()
        assertThat(vm.state.value.step).isEqualTo(AuthStep.Done)
    }

    @Test
    fun `submitCode rejects short codes and decrements attempts on PHONE_CODE_INVALID`() = runTest(testDispatcher) {
        val vm = AuthViewModel(telegramClient, keystore, dispatchers)
        authStateFlow.value = AuthorizationState.WaitCode
        runCurrent()

        vm.onCodeChanged("12")
        vm.submitCode()
        assertThat(vm.state.value.error).contains("at least 4")

        coEvery { telegramClient.sendAuthCode("99999") } throws TelegramException(400, "PHONE_CODE_INVALID")
        vm.onCodeChanged("99999")

        vm.submitCode()
        runCurrent()
        assertThat(vm.state.value.attemptsRemaining).isEqualTo(2)

        vm.submitCode()
        runCurrent()
        assertThat(vm.state.value.attemptsRemaining).isEqualTo(1)

        vm.submitCode()
        runCurrent()
        assertThat(vm.state.value.attemptsRemaining).isEqualTo(0)
        assertThat(vm.state.value.cooldownSeconds).isGreaterThan(0)

        advanceTimeBy(61_000L)
        runCurrent()
        assertThat(vm.state.value.cooldownSeconds).isEqualTo(0)
        assertThat(vm.state.value.attemptsRemaining).isEqualTo(3)
    }

    @Test
    fun `submitEmail submitPassword and submitRegistration validate inputs and delegate to client`() = runTest(testDispatcher) {
        val vm = AuthViewModel(telegramClient, keystore, dispatchers)
        runCurrent()

        vm.onEmailChanged("notanemail")
        vm.submitEmail()
        assertThat(vm.state.value.error).contains("valid email")

        vm.onEmailChanged("user@example.com")
        vm.submitEmail()
        advanceUntilIdle()
        coVerify { telegramClient.sendEmailAddress("user@example.com") }

        vm.onPasswordChanged("")
        vm.submitPassword()
        assertThat(vm.state.value.error).contains("Password required")

        vm.onPasswordChanged("hunter2")
        vm.submitPassword()
        advanceUntilIdle()
        coVerify { telegramClient.sendAuthPassword("hunter2") }

        vm.onFirstNameChanged("   ")
        vm.submitRegistration()
        assertThat(vm.state.value.error).contains("First name is required")

        vm.onFirstNameChanged(" Ada ")
        vm.onLastNameChanged(" Lovelace ")
        vm.submitRegistration()
        advanceUntilIdle()
        coVerify { telegramClient.registerNewUser("Ada", "Lovelace") }
    }

    @Test
    fun `runAuthAction surfaces exception class name when throwable message is null`() = runTest(testDispatcher) {
        coEvery { telegramClient.sendPhoneNumber("+15551234567") } throws RuntimeException()
        val vm = AuthViewModel(telegramClient, keystore, dispatchers)
        runCurrent()

        vm.onPhoneChanged("+15551234567")
        vm.submitPhone()
        advanceUntilIdle()

        assertThat(vm.state.value.error).isEqualTo("RuntimeException")
        assertThat(vm.state.value.loading).isFalse()
    }
}
