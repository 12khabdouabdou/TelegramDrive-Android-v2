package com.tgdrive.core.telegram

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.security.KeystoreHelper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TdLibPaginationTest {
    @TempDir
    lateinit var tempDir: File

    private fun makeClient(dispatcher: CoroutineDispatcher): TdLibTelegramClient {
        val dispatchers = object : DispatchersProvider {
            override val io: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
            override val main: CoroutineDispatcher = dispatcher
            override val unconfined: CoroutineDispatcher = dispatcher
        }
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.cacheDir } returns tempDir
        return TdLibTelegramClient(
            dispatchers = dispatchers,
            appContext = ctx,
            keystoreHelper = mockk<KeystoreHelper>(relaxed = true),
        )
    }

    private fun docMsg(id: Long, chatId: Long, name: String): TdApi.Message =
        TdApi.Message().apply {
            this.id = id
            this.chatId = chatId
            this.date = 1_700_000_000
            this.content = TdApi.MessageDocument().apply {
                this.document = TdApi.Document().apply {
                    this.fileName = name
                    this.mimeType = "application/octet-stream"
                    this.document = TdApi.File().apply {
                        this.size = 128L
                        this.remote = TdApi.RemoteFile().apply { this.id = "rem_$id" }
                    }
                }
            }
        }

    private fun textMsg(id: Long, chatId: Long): TdApi.Message =
        TdApi.Message().apply {
            this.id = id
            this.chatId = chatId
            this.date = 1_700_000_000
            this.content = TdApi.MessageText().apply {
                this.text = TdApi.FormattedText().apply { this.text = "hello" }
            }
        }

    @Test
    fun `fetchMessagesPage loops across short TDLib pages and preserves raw cursor`() =
        runTest {
            val client = makeClient(StandardTestDispatcher(testScheduler))
            val requestedFrom = mutableListOf<Long>()
            client.rawSendOverride = { fn, onResult, _ ->
                when (fn) {
                    is TdApi.GetChat -> onResult(TdApi.Chat().apply { id = fn.chatId })
                    is TdApi.GetChatHistory -> {
                        requestedFrom.add(fn.fromMessageId)
                        when (fn.fromMessageId) {
                            0L ->
                                onResult(
                                    TdApi.Messages().apply {
                                        messages =
                                            arrayOf(
                                                docMsg(100L, fn.chatId, "a.bin"),
                                                textMsg(90L, fn.chatId),
                                            )
                                    },
                                )
                            90L ->
                                onResult(
                                    TdApi.Messages().apply {
                                        messages = arrayOf(docMsg(80L, fn.chatId, "b.bin"))
                                    },
                                )
                            else -> onResult(TdApi.Messages().apply { messages = emptyArray() })
                        }
                    }
                    else -> onResult(TdApi.Ok())
                }
            }

            val page = client.fetchMessagesPage(chatId = 55L, fromMessageId = 0L, limit = 10)

            assertThat(requestedFrom).containsExactly(0L, 90L, 80L).inOrder()
            assertThat(page.messages.map { it.id }).containsExactly(100L, 80L).inOrder()
            assertThat(page.nextFromMessageId).isEqualTo(80L)
            assertThat(page.reachedEnd).isTrue()
        }
}
