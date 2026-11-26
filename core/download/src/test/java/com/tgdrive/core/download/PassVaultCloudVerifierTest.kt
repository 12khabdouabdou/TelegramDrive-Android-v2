package com.tgdrive.core.download

import android.content.Context
import android.content.ContextWrapper
import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.KeystoreHelper
import com.tgdrive.core.telegram.DownloadUpdate
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.telegram.TelegramMessage
import com.tgdrive.core.telegram.UploadResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PassVaultCloudVerifierTest {

    private fun inMemoryKeystore(): KeystoreHelper {
        val store = mutableMapOf<String, String>()
        return object : KeystoreHelper(object : ContextWrapper(null) {}) {
            override fun getString(key: String, default: String?): String? = store[key] ?: default
            override fun putString(key: String, value: String): Boolean {
                store[key] = value
                return true
            }
            override fun remove(key: String): Boolean {
                store.remove(key)
                return true
            }
        }
    }

    @Test
    fun `publishVerifier uploads sentinel and verifyAgainstCloud detects match and mismatch across duplicate channels`(
        @TempDir tmp: File,
    ) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatchersProvider {
            override val io: CoroutineDispatcher = d
            override val default: CoroutineDispatcher = d
            override val main: CoroutineDispatcher = d
            override val unconfined: CoroutineDispatcher = d
        }
        val cacheDir = File(tmp, "cache").apply { mkdirs() }
        val ctx = mockk<Context>(relaxed = true) {
            every { this@mockk.cacheDir } returns cacheDir
        }
        val crypto = BackupCrypto(inMemoryKeystore())
        val uploadedBlob = File(tmp, "uploaded_verifier.tgenc")

        val telegramClient = mockk<TelegramClient>(relaxed = true) {
            coEvery { getOrCreateSystemChannel() } returns 100L
            coEvery { findAllSystemChannelIds() } returns listOf(100L, 200L)
            coEvery { fetchMessages(100L, any(), any()) } returns emptyList()
            coEvery { fetchMessages(200L, any(), any()) } returns listOf(
                TelegramMessage(
                    id = 55L,
                    chatId = 200L,
                    fileName = PassVaultCloudVerifier.VERIFIER_FILE_NAME,
                    fileSize = uploadedBlob.length(),
                    mimeType = "application/octet-stream",
                    date = 500L,
                    thumbnailPath = null,
                    fileRemoteId = "rem_old",
                    isVideo = false,
                    isImage = false,
                    isAudio = false,
                    isDocument = true,
                ),
            )
            coEvery { uploadFile(100L, any(), PassVaultCloudVerifier.VERIFIER_FILE_NAME, any(), any()) } answers {
                File(secondArg<String>()).copyTo(uploadedBlob, overwrite = true)
                UploadResult(messageId = 99L, remoteId = "rem_new")
            }
            every { downloadFile("rem_old", any()) } answers {
                val dest = File(secondArg<String>()).apply {
                    parentFile?.mkdirs()
                    uploadedBlob.copyTo(this, overwrite = true)
                }
                flowOf(DownloadUpdate.Done(dest.absolutePath))
            }
        }

        val verifier = PassVaultCloudVerifier(ctx, telegramClient, crypto, dispatchers)

        val pub = verifier.publishVerifier("correct-passphrase")
        assertThat(pub.isSuccess).isTrue()
        assertThat(uploadedBlob.isFile).isTrue()
        coVerify { telegramClient.deleteMessage(200L, 55L) }

        assertThat(verifier.hasCloudVerifier()).isTrue()
        assertThat(verifier.verifyAgainstCloud("correct-passphrase"))
            .isEqualTo(PassVaultCloudVerifier.CloudVerifyResult.MATCHED)
        assertThat(verifier.verifyAgainstCloud("wrong-passphrase"))
            .isEqualTo(PassVaultCloudVerifier.CloudVerifyResult.MISMATCH)
    }

    @Test
    fun `verifyAgainstCloud returns NO_CLOUD_VERIFIER without creating a channel`(@TempDir tmp: File) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatchersProvider {
            override val io: CoroutineDispatcher = d
            override val default: CoroutineDispatcher = d
            override val main: CoroutineDispatcher = d
            override val unconfined: CoroutineDispatcher = d
        }
        val ctx = mockk<Context>(relaxed = true) {
            every { cacheDir } returns File(tmp, "cache").apply { mkdirs() }
        }
        val crypto = BackupCrypto(inMemoryKeystore())
        val telegramClient = mockk<TelegramClient>(relaxed = true) {
            coEvery { findAllSystemChannelIds() } returns emptyList()
        }

        val verifier = PassVaultCloudVerifier(ctx, telegramClient, crypto, dispatchers)
        assertThat(verifier.hasCloudVerifier()).isFalse()
        assertThat(verifier.verifyAgainstCloud("anything-123"))
            .isEqualTo(PassVaultCloudVerifier.CloudVerifyResult.NO_CLOUD_VERIFIER)
        coVerify(exactly = 0) { telegramClient.getOrCreateSystemChannel() }
    }
}
