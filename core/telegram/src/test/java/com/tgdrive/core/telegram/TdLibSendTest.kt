package com.tgdrive.core.telegram

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.security.KeystoreHelper
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TdLibSendTest {

    private fun makeClient(dispatcher: CoroutineDispatcher): TdLibTelegramClient {
        val dispatchers = object : DispatchersProvider {
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
            override val main: CoroutineDispatcher = dispatcher
            override val unconfined: CoroutineDispatcher = dispatcher
        }
        return TdLibTelegramClient(
            dispatchers = dispatchers,
            appContext = mockk<Context>(relaxed = true),
            keystoreHelper = mockk<KeystoreHelper>(relaxed = true),
        )
    }

    @Test
    fun `send resumes with IllegalStateException when TDLib returns null object`() = runTest {
        val client = makeClient(StandardTestDispatcher(testScheduler))
        client.rawSendOverride = { _, onResult, _ ->
            onResult(null)
        }

        val thrown = try {
            client.send<TdApi.User>(TdApi.GetMe())
            null
        } catch (t: Throwable) {
            t
        }

        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown!!.message).contains("GetMe")
    }

    @Test
    fun `send preserves exception class when TDLib raises null-message Throwable`() = runTest {
        val client = makeClient(StandardTestDispatcher(testScheduler))
        client.rawSendOverride = { _, _, onException ->
            onException(IllegalArgumentException())
        }

        val thrown = try {
            client.send<TdApi.User>(TdApi.GetMe())
            null
        } catch (t: Throwable) {
            t
        }

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `send retries automatically after transient FLOOD_WAIT`() = runTest {
        val client = makeClient(StandardTestDispatcher(testScheduler))
        var calls = 0
        client.rawSendOverride = { _, onResult, _ ->
            calls++
            if (calls == 1) {
                onResult(TdApi.Error().apply {
                    code = 420
                    message = "FLOOD_WAIT_1"
                })
            } else {
                onResult(TdApi.User().apply { id = 99L })
            }
        }

        val user = client.send<TdApi.User>(TdApi.GetMe())

        assertThat(calls).isEqualTo(2)
        assertThat(user.id).isEqualTo(99L)
    }

    @Test
    fun `send propagates CancellationException without masking`() = runTest {
        val client = makeClient(StandardTestDispatcher(testScheduler))
        client.rawSendOverride = { _, _, onException ->
            onException(CancellationException("cancelled"))
        }

        val thrown = try {
            client.send<TdApi.User>(TdApi.GetMe())
            null
        } catch (t: Throwable) {
            t
        }

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `listFolders retries once when GetChats initially returns empty chatIds`() = runTest {
        val client = makeClient(StandardTestDispatcher(testScheduler))
        var getChatsCalls = 0
        client.rawSendOverride = { fn, onResult, _ ->
            when (fn) {
                is TdApi.GetMe -> onResult(TdApi.User().apply { id = 42L })
                is TdApi.LoadChats -> onResult(TdApi.Ok())
                is TdApi.GetChats -> {
                    getChatsCalls++
                    val ids = if (getChatsCalls == 1) longArrayOf() else longArrayOf(1001L)
                    onResult(TdApi.Chats().apply { chatIds = ids })
                }
                is TdApi.GetChat -> onResult(TdApi.Chat().apply {
                    id = 1001L
                    title = "Photos"
                    type = TdApi.ChatTypeSupergroup(77L, true)
                })
                is TdApi.GetSupergroup -> onResult(TdApi.Supergroup().apply {
                    id = 77L
                    status = TdApi.ChatMemberStatusCreator()
                })
                else -> onResult(TdApi.Ok())
            }
        }

        val folders = client.listFolders()

        assertThat(getChatsCalls).isEqualTo(2)
        assertThat(folders.map { it.chatId to it.title }).containsExactly(
            42L to "Saved Messages",
            1001L to "Photos",
        ).inOrder()
    }
}
