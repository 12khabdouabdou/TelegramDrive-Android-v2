package com.tgdrive.core.download

import android.content.Context
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.NetworkMonitor
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.AppPreferencesData
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.PendingUploadDao
import com.tgdrive.core.db.dao.ZipEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.PendingUploadEntity
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.EncryptedFileFormat
import com.tgdrive.core.security.KeystoreHelper
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.telegram.UploadResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
        pendingUploadDao: PendingUploadDao = mockk(relaxed = true) {
            coEvery { getAll() } returns emptyList()
        },
        appPreferences: AppPreferences = mockk(relaxed = true) {
            every { data } returns flowOf(AppPreferencesData())
        },
        networkMonitor: NetworkMonitor = mockk(relaxed = true) {
            every { isUnmetered } returns MutableStateFlow(true)
            every { isCurrentlyUnmetered() } returns true
        },
        cloudSyncManager: CloudSyncManager = mockk(relaxed = true),
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
            pendingUploadDao = pendingUploadDao,
            mediaThumbnailCache = thumbCache,
            uploadTracker = tracker,
            backupCrypto = crypto,
            cloudSyncManager = cloudSyncManager,
            appPreferences = appPreferences,
            networkMonitor = networkMonitor,
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
                    useSavedPassphrase = false,
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

    @Test
    fun `persisted pending upload restores as PAUSED on startup and resumes to DONE`(@TempDir tmp: File) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val src = File(tmp, "restored.txt").apply { writeBytes("persist-payload".toByteArray()) }
        mockkStatic(Uri::class)
        try {
            val fakeUri = fileUri(src)
            every { Uri.parse("file://${src.absolutePath}") } returns fakeUri

            val savedPayload = listOf(
                "chatId=77",
                "uri=file://${src.absolutePath}",
                "name=restored.txt",
                "mime=text/plain",
                "size=${src.length()}",
                "vpath=",
                "mtime=0",
                "conflict=KEEP_BOTH",
                "split=true",
                "wifi=false",
                "enc=false",
                "vault=true",
            ).joinToString("\n")
            val pendingDao = mockk<PendingUploadDao>(relaxed = true) {
                coEvery { getAll() } returns listOf(
                    PendingUploadEntity(
                        id = "ul_saved_1",
                        kind = "SINGLE",
                        chatId = 77L,
                        displayName = "restored.txt",
                        totalBytes = src.length(),
                        status = "QUEUED",
                        payloadJson = savedPayload,
                        isEncrypted = false,
                    ),
                )
            }
            val tg = mockk<TelegramClient>(relaxed = true) {
                coEvery { maxUploadSizeBytes() } returns TelegramClient.MAX_FILE_SIZE_STANDARD
                coEvery { uploadFile(any(), any(), any(), any(), any()) } returns
                    UploadResult(messageId = 401L, remoteId = "rem401")
            }
            val dao = mockk<FileCacheDao>(relaxed = true) {
                coEvery { forChat(77L) } returns emptyList()
            }
            val (manager, tracker) = createManager(
                tmp = tmp,
                dispatcher = d,
                telegramClient = tg,
                fileCacheDao = dao,
                pendingUploadDao = pendingDao,
            )
            runCurrent()

            val restored = tracker.state.value.items.single()
            assertThat(restored.id).isEqualTo("ul_saved_1")
            assertThat(restored.status).isEqualTo(UploadStatus.PAUSED)
            assertThat(restored.canRetry).isTrue()

            manager.resume("ul_saved_1")
            runCurrent()

            assertThat(tracker.state.value.items.single().status).isEqualTo(UploadStatus.DONE)
            coVerify { tg.uploadFile(77L, any(), "restored.txt", "text/plain", any()) }
            coVerify { pendingDao.deleteById("ul_saved_1") }
        } finally {
            unmockkStatic(Uri::class)
        }
    }

    @Test
    fun `wifiOnly upload defers on metered network and auto-resumes when unmetered`(@TempDir tmp: File) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val src = File(tmp, "wifi.bin").apply { writeBytes("wifi-payload".toByteArray()) }
        val unmeteredFlow = MutableStateFlow(false)
        val net = mockk<NetworkMonitor>(relaxed = true) {
            every { isUnmetered } returns unmeteredFlow
            every { isCurrentlyUnmetered() } answers { unmeteredFlow.value }
        }
        val tg = mockk<TelegramClient>(relaxed = true) {
            coEvery { maxUploadSizeBytes() } returns TelegramClient.MAX_FILE_SIZE_STANDARD
            coEvery { uploadFile(any(), any(), any(), any(), any()) } returns
                UploadResult(messageId = 501L, remoteId = "rem501")
        }
        val dao = mockk<FileCacheDao>(relaxed = true) {
            coEvery { forChat(88L) } returns emptyList()
        }
        val (manager, tracker) = createManager(
            tmp = tmp,
            dispatcher = d,
            telegramClient = tg,
            fileCacheDao = dao,
            networkMonitor = net,
        )
        runCurrent()

        manager.enqueueFiles(
            chatId = 88L,
            files = listOf(UploadFileItem(fileUri(src), "wifi.bin", "application/octet-stream", src.length())),
            options = NormalUploadOptions(wifiOnly = true),
        )
        runCurrent()

        val deferred = tracker.state.value.items.single()
        assertThat(deferred.status).isEqualTo(UploadStatus.PAUSED)
        assertThat(deferred.statusDetail).isEqualTo("Waiting for Wi-Fi…")
        coVerify(exactly = 0) { tg.uploadFile(any(), any(), any(), any(), any()) }

        unmeteredFlow.value = true
        runCurrent()

        val completed = tracker.state.value.items.single()
        assertThat(completed.status).isEqualTo(UploadStatus.DONE)
        coVerify(exactly = 1) { tg.uploadFile(88L, any(), "wifi.bin", any(), any()) }
    }

    @Test
    fun `REPLACE conflict strategy scopes replacement to matching virtualPath`(@TempDir tmp: File) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val src = File(tmp, "report.pdf").apply { writeBytes("updated-report".toByteArray()) }

        val tg = mockk<TelegramClient>(relaxed = true) {
            coEvery { maxUploadSizeBytes() } returns TelegramClient.MAX_FILE_SIZE_STANDARD
            coEvery { uploadFile(any(), any(), any(), any(), any()) } returns
                UploadResult(messageId = 99L, remoteId = "rem99")
        }
        val dao = mockk<FileCacheDao>(relaxed = true) {
            coEvery { forChat(42L) } returns listOf(
                FileCacheEntity(
                    messageId = 10L,
                    chatId = 42L,
                    fileName = "report.pdf",
                    fileSize = 10L,
                    mimeType = "application/pdf",
                    thumbnailPath = null,
                    remoteId = "r10",
                    date = 1L,
                    virtualPath = "Work",
                ),
                FileCacheEntity(
                    messageId = 20L,
                    chatId = 42L,
                    fileName = "report.pdf",
                    fileSize = 10L,
                    mimeType = "application/pdf",
                    thumbnailPath = null,
                    remoteId = "r20",
                    date = 1L,
                    virtualPath = "Personal",
                ),
            )
        }
        val (manager, tracker) = createManager(tmp, d, tg, dao)
        runCurrent()

        manager.enqueueFiles(
            chatId = 42L,
            files = listOf(UploadFileItem(fileUri(src), "report.pdf", "application/pdf", src.length())),
            options = NormalUploadOptions(
                conflictStrategy = UploadConflictStrategy.REPLACE,
                virtualPath = "Personal",
            ),
        )
        advanceUntilIdle()

        assertThat(tracker.state.value.items.single().status).isEqualTo(UploadStatus.DONE)
        coVerify(exactly = 1) { tg.deleteMessage(42L, 20L) }
        coVerify(exactly = 0) { tg.deleteMessage(42L, 10L) }
    }

    @Test
    fun `pausing one file in a batch does not cancel sibling uploads`(@TempDir tmp: File) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val fileA = File(tmp, "a.bin").apply { writeBytes("alpha".toByteArray()) }
        val fileB = File(tmp, "b.bin").apply { writeBytes("beta".toByteArray()) }
        val aStarted = CompletableDeferred<Unit>()

        val tg = mockk<TelegramClient>(relaxed = true) {
            coEvery { maxUploadSizeBytes() } returns TelegramClient.MAX_FILE_SIZE_STANDARD
            coEvery { uploadFile(any(), any(), any(), any(), any()) } coAnswers {
                val name = thirdArg<String>()
                if (name == "a.bin") {
                    aStarted.complete(Unit)
                    awaitCancellation()
                }
                UploadResult(messageId = 701L, remoteId = "rem701")
            }
        }
        val dao = mockk<FileCacheDao>(relaxed = true) {
            coEvery { forChat(55L) } returns emptyList()
        }
        val (manager, tracker) = createManager(tmp, d, tg, dao)
        runCurrent()

        manager.enqueueFiles(
            chatId = 55L,
            files = listOf(
                UploadFileItem(fileUri(fileA), "a.bin", "application/octet-stream", fileA.length()),
                UploadFileItem(fileUri(fileB), "b.bin", "application/octet-stream", fileB.length()),
            ),
        )
        runCurrent()
        aStarted.await()

        val idA = tracker.state.value.items.first { it.fileName == "a.bin" }.id
        manager.pause(idA)
        advanceUntilIdle()

        val items = tracker.state.value.items.associateBy { it.fileName }
        assertThat(items["a.bin"]?.status).isEqualTo(UploadStatus.PAUSED)
        assertThat(items["b.bin"]?.status).isEqualTo(UploadStatus.DONE)
    }

    @Test
    fun `file URI uploads without extra staging copy and syncs virtualPath to CloudSyncManager`(@TempDir tmp: File) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val src = File(tmp, "contract.pdf").apply { writeBytes("pdf-payload".toByteArray()) }
        var uploadedLocalPath: String? = null

        val tg = mockk<TelegramClient>(relaxed = true) {
            coEvery { maxUploadSizeBytes() } returns TelegramClient.MAX_FILE_SIZE_STANDARD
            coEvery { uploadFile(any(), any(), any(), any(), any()) } answers {
                uploadedLocalPath = secondArg()
                UploadResult(messageId = 808L, remoteId = "rem808")
            }
        }
        val dao = mockk<FileCacheDao>(relaxed = true) {
            coEvery { forChat(33L) } returns emptyList()
        }
        val cloudSync = mockk<CloudSyncManager>(relaxed = true)
        val (manager, tracker) = createManager(tmp, d, tg, dao, cloudSyncManager = cloudSync)
        runCurrent()

        manager.enqueueFiles(
            chatId = 33L,
            files = listOf(UploadFileItem(fileUri(src), "contract.pdf", "application/pdf", src.length())),
            options = NormalUploadOptions(virtualPath = "Docs/2026"),
        )
        advanceUntilIdle()

        assertThat(tracker.state.value.items.single().status).isEqualTo(UploadStatus.DONE)
        assertThat(uploadedLocalPath).isEqualTo(src.absolutePath)
        assertThat(src.exists()).isTrue()
        coVerify { tg.registerUploadMetadata(src.absolutePath, caption = "tgdrive:path=Docs/2026", prebuiltThumbnailPath = null) }
        coVerify { cloudSync.noteVirtualPathChanged(33L, 808L, "Docs/2026", schedulePush = false) }
        coVerify(exactly = 1) { cloudSync.schedulePushMetadata() }
    }
}
