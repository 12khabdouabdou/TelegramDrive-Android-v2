package com.tgdrive.core.download

import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.ZipEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.EncryptedFileFormat
import com.tgdrive.core.security.KeystoreHelper
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.telegram.UploadResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class UploadManagerTest {

    private fun testDispatchers(d: CoroutineDispatcher): DispatchersProvider =
        object : DispatchersProvider {
            override val main: CoroutineDispatcher = d
            override val io: CoroutineDispatcher = d
            override val default: CoroutineDispatcher = d
            override val unconfined: CoroutineDispatcher = d
        }

    private fun fakeContext(cacheRoot: File): Context = mockk(relaxed = true) {
        every { cacheDir } returns cacheRoot
    }

    private fun fakeKeystoreHelper(): KeystoreHelper {
        val store = mutableMapOf<String, String>()
        return mockk(relaxed = true) {
            every { getString(any(), any()) } answers {
                val key = firstArg<String>()
                val def = secondArg<String?>()
                store[key] ?: def
            }
            every { putString(any(), any()) } answers {
                store[firstArg()] = secondArg()
                true
            }
            every { remove(any()) } answers {
                store.remove(firstArg())
                true
            }
        }
    }

    private fun fileUri(file: File): Uri = mockk(relaxed = true) {
        every { scheme } returns "file"
        every { path } returns file.absolutePath
        every { lastPathSegment } returns file.name
    }

    private fun createManager(
        tmp: File,
        dispatcher: CoroutineDispatcher,
        telegramClient: TelegramClient,
        fileCacheDao: FileCacheDao,
    ): Pair<UploadManager, UploadTracker> {
        val ctx = fakeContext(File(tmp, "cache").apply { mkdirs() })
        val tracker = UploadTracker(ctx)
        val crypto = BackupCrypto(fakeKeystoreHelper())
        val zipEntryDao = mockk<ZipEntryDao>(relaxed = true)
        val thumbCache = mockk<MediaThumbnailCache>(relaxed = true) {
            coEvery { generateFromLocalFile(any(), any(), any(), any(), any()) } returns null
        }
        val manager = UploadManager(
            ctx = ctx,
            telegramClient = telegramClient,
            fileCacheDao = fileCacheDao,
            zipEntryDao = zipEntryDao,
            mediaThumbnailCache = thumbCache,
            uploadTracker = tracker,
            backupCrypto = crypto,
            dispatchers = testDispatchers(dispatcher),
        )
        return manager to tracker
    }

    @Test
    fun `SKIP conflict strategy skips existing file before uploading`(@TempDir tmp: File) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val src = File(tmp, "photo.jpg").apply { writeBytes("img-bytes".toByteArray()) }

        val tg = mockk<TelegramClient>(relaxed = true) {
            coEvery { maxUploadSizeBytes() } returns TelegramClient.MAX_FILE_SIZE_STANDARD
        }
        val dao = mockk<FileCacheDao>(relaxed = true) {
            coEvery { forChat(42L) } returns listOf(
                FileCacheEntity(
                    messageId = 100L,
                    chatId = 42L,
                    fileName = "photo.jpg",
                    fileSize = 9L,
                    mimeType = "image/jpeg",
                    thumbnailPath = null,
                    remoteId = "r1",
                    date = 1L,
                ),
            )
        }
        val (manager, tracker) = createManager(tmp, d, tg, dao)
        advanceUntilIdle()

        manager.enqueueFiles(
            chatId = 42L,
            files = listOf(UploadFileItem(fileUri(src), "photo.jpg", "image/jpeg", src.length())),
            options = NormalUploadOptions(conflictStrategy = UploadConflictStrategy.SKIP),
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { tg.uploadFile(any(), any(), any(), any(), any()) }
        assertThat(tracker.state.value.items.single().status).isEqualTo(UploadStatus.DONE)
    }

    @Test
    fun `KEEP_BOTH renames colliding filename while preserving extension`(@TempDir tmp: File) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val src = File(tmp, "doc.pdf").apply { writeBytes("pdf-data".toByteArray()) }
        var uploadedName: String? = null

        val tg = mockk<TelegramClient>(relaxed = true) {
            coEvery { maxUploadSizeBytes() } returns TelegramClient.MAX_FILE_SIZE_STANDARD
            coEvery { uploadFile(any(), any(), any(), any(), any()) } answers {
                uploadedName = thirdArg()
                UploadResult(messageId = 55L, remoteId = "rem55")
            }
        }
        val dao = mockk<FileCacheDao>(relaxed = true) {
            coEvery { forChat(7L) } returns listOf(
                FileCacheEntity(
                    messageId = 10L,
                    chatId = 7L,
                    fileName = "doc.pdf",
                    fileSize = 4L,
                    mimeType = "application/pdf",
                    thumbnailPath = null,
                    remoteId = "r10",
                    date = 1L,
                ),
            )
        }
        val (manager, _) = createManager(tmp, d, tg, dao)
        advanceUntilIdle()

        manager.enqueueFiles(
            chatId = 7L,
            files = listOf(UploadFileItem(fileUri(src), "doc.pdf", "application/pdf", src.length())),
            options = NormalUploadOptions(conflictStrategy = UploadConflictStrategy.KEEP_BOTH),
        )
        advanceUntilIdle()

        assertThat(uploadedName).isEqualTo("doc (1).pdf")
    }

    @Test
    fun `encrypted upload produces TGE2 file decryptable with custom passphrase`(@TempDir tmp: File) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val payload = "secret contract content ".repeat(100).toByteArray()
        val src = File(tmp, "secret.txt").apply { writeBytes(payload) }
        val uploadedCopy = File(tmp, "captured.tgenc")
        val upserted = mutableListOf<List<FileCacheEntity>>()

        val tg = mockk<TelegramClient>(relaxed = true) {
            coEvery { maxUploadSizeBytes() } returns TelegramClient.MAX_FILE_SIZE_STANDARD
            coEvery { uploadFile(any(), any(), any(), any(), any()) } answers {
                File(secondArg<String>()).copyTo(uploadedCopy, overwrite = true)
                UploadResult(messageId = 77L, remoteId = "rem77")
            }
        }
        val dao = mockk<FileCacheDao>(relaxed = true) {
            coEvery { forChat(9L) } returns emptyList()
            coEvery { upsertAll(capture(upserted)) } returns Unit
        }
        val (manager, tracker) = createManager(tmp, d, tg, dao)
        advanceUntilIdle()

        manager.enqueueFiles(
            chatId = 9L,
            files = listOf(UploadFileItem(fileUri(src), "secret.txt", "text/plain", src.length())),
            options = NormalUploadOptions(
                encryption = UploadEncryptionSpec(
                    enabled = true,
                    useVaultKey = false,
                    customPassphrase = "my-strong-passphrase",
                ),
            ),
        )
        advanceUntilIdle()

        assertThat(tracker.state.value.items.single().status).isEqualTo(UploadStatus.DONE)
        assertThat(upserted.single().single().fileName).isEqualTo("secret.txt.tgenc")
        assertThat(upserted.single().single().isEncryptedVault).isTrue()
        assertThat(manager.detectEncryptedFormat(uploadedCopy)).isEqualTo(EncryptedFileFormat.PASSPHRASE_TGE2)

        val wrong = manager.decryptLocalFile(uploadedCopy, "secret.txt.tgenc", passphrase = "wrong")
        assertThat(wrong).isInstanceOf(DecryptOutcome.WrongPassphrase::class.java)

        val ok = manager.decryptLocalFile(uploadedCopy, "secret.txt.tgenc", passphrase = "my-strong-passphrase")
        assertThat(ok).isInstanceOf(DecryptOutcome.Success::class.java)
        val success = ok as DecryptOutcome.Success
        assertThat(success.displayName).isEqualTo("secret.txt")
        assertThat(success.file.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `transient upload failure retries and succeeds, and failed non-transient item can be manually retried`(@TempDir tmp: File) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val src = File(tmp, "note.txt").apply { writeBytes("hello world".toByteArray()) }
        var transientAttempts = 0

        val tg = mockk<TelegramClient>(relaxed = true) {
            coEvery { maxUploadSizeBytes() } returns TelegramClient.MAX_FILE_SIZE_STANDARD
            coEvery { uploadFile(any(), any(), any(), any(), any()) } answers {
                transientAttempts++
                if (transientAttempts == 1) throw IOException("network timeout")
                UploadResult(messageId = 88L, remoteId = "rem88")
            }
        }
        val dao = mockk<FileCacheDao>(relaxed = true) {
            coEvery { forChat(5L) } returns emptyList()
        }
        val (manager, tracker) = createManager(tmp, d, tg, dao)
        advanceUntilIdle()

        manager.enqueueFiles(
            chatId = 5L,
            files = listOf(UploadFileItem(fileUri(src), "note.txt", "text/plain", src.length())),
        )
        advanceUntilIdle()

        assertThat(transientAttempts).isEqualTo(2)
        assertThat(tracker.state.value.items.single().status).isEqualTo(UploadStatus.DONE)

        // Now test permanent-first failure followed by manual retry()
        var failHard = true
        coEvery { tg.uploadFile(any(), any(), any(), any(), any()) } answers {
            if (failHard) throw IllegalStateException("server rejected")
            UploadResult(messageId = 99L, remoteId = "rem99")
        }
        manager.enqueueFiles(
            chatId = 5L,
            files = listOf(UploadFileItem(fileUri(src), "second.txt", "text/plain", src.length())),
        )
        advanceUntilIdle()

        val failedItem = tracker.state.value.items.first { it.fileName == "second.txt" }
        assertThat(failedItem.status).isEqualTo(UploadStatus.FAILED)
        assertThat(failedItem.canRetry).isTrue()

        failHard = false
        manager.retry(failedItem.id)
        advanceUntilIdle()

        val retriedItem = tracker.state.value.items.first { it.id == failedItem.id }
        assertThat(retriedItem.status).isEqualTo(UploadStatus.DONE)
    }

    @Test
    fun `pause stops active upload, ignores trailing progress, and resume completes`(@TempDir tmp: File) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val src = File(tmp, "video.mp4").apply { writeBytes("video-stream-payload".toByteArray()) }
        var allowFinish = false

        val tg = mockk<TelegramClient>(relaxed = true) {
            coEvery { maxUploadSizeBytes() } returns TelegramClient.MAX_FILE_SIZE_STANDARD
            coEvery { uploadFile(any(), any(), any(), any(), any()) } coAnswers {
                val progressCb = arg<(Long, Long) -> Unit>(4)
                progressCb(500L, 1000L)
                if (!allowFinish) {
                    kotlinx.coroutines.delay(60_000L)
                }
                UploadResult(messageId = 201L, remoteId = "rem201")
            }
        }
        val dao = mockk<FileCacheDao>(relaxed = true) {
            coEvery { forChat(12L) } returns emptyList()
        }
        val (manager, tracker) = createManager(tmp, d, tg, dao)
        runCurrent()

        manager.enqueueFiles(
            chatId = 12L,
            files = listOf(UploadFileItem(fileUri(src), "video.mp4", "video/mp4", src.length())),
        )
        runCurrent()

        val id = tracker.state.value.items.single().id
        manager.pause(id)
        runCurrent()
        assertThat(tracker.state.value.items.single().status).isEqualTo(UploadStatus.PAUSED)

        // Trailing TDLib progress tick must never resurrect a paused item.
        tracker.updateProgress(id, 900L, 1000L)
        assertThat(tracker.state.value.items.single().status).isEqualTo(UploadStatus.PAUSED)

        allowFinish = true
        manager.resume(id)
        advanceUntilIdle()

        val finalItem = tracker.state.value.items.single()
        assertThat(finalItem.status).isEqualTo(UploadStatus.DONE)
    }

    @Test
    fun `cancel stops active upload and ignores trailing progress updates`(@TempDir tmp: File) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val src = File(tmp, "archive.zip").apply { writeBytes("zip-payload".toByteArray()) }

        val tg = mockk<TelegramClient>(relaxed = true) {
            coEvery { maxUploadSizeBytes() } returns TelegramClient.MAX_FILE_SIZE_STANDARD
            coEvery { uploadFile(any(), any(), any(), any(), any()) } coAnswers {
                val progressCb = arg<(Long, Long) -> Unit>(4)
                progressCb(300L, 1000L)
                kotlinx.coroutines.delay(60_000L)
                UploadResult(messageId = 301L, remoteId = "rem301")
            }
        }
        val dao = mockk<FileCacheDao>(relaxed = true) {
            coEvery { forChat(15L) } returns emptyList()
        }
        val (manager, tracker) = createManager(tmp, d, tg, dao)
        runCurrent()

        manager.enqueueFiles(
            chatId = 15L,
            files = listOf(UploadFileItem(fileUri(src), "archive.zip", "application/zip", src.length())),
        )
        runCurrent()

        val id = tracker.state.value.items.single().id
        manager.cancel(id)
        advanceUntilIdle()

        assertThat(tracker.state.value.items.single().status).isEqualTo(UploadStatus.CANCELLED)
        tracker.updateProgress(id, 850L, 1000L)
        assertThat(tracker.state.value.items.single().status).isEqualTo(UploadStatus.CANCELLED)
    }
}
