package com.tgdrive.core.download

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.AppPreferencesData
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.telegram.DownloadUpdate
import com.tgdrive.core.telegram.TelegramClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MediaThumbnailCacheResilienceTest {
    private lateinit var ctx: Context
    private lateinit var telegramClient: TelegramClient
    private lateinit var fileCacheDao: FileCacheDao
    private lateinit var downloadManager: DownloadManager
    private lateinit var offlinePinStore: OfflinePinStore
    private lateinit var backupCrypto: BackupCrypto
    private lateinit var cloudSyncManager: CloudSyncManager
    private lateinit var appPreferences: AppPreferences

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        ctx = mockk(relaxed = true)
        telegramClient = mockk(relaxed = true)
        fileCacheDao = mockk(relaxed = true)
        downloadManager = mockk(relaxed = true)
        offlinePinStore = mockk(relaxed = true)
        backupCrypto = mockk(relaxed = true)
        cloudSyncManager = mockk(relaxed = true)
        appPreferences = mockk(relaxed = true)

        every { ctx.cacheDir } returns tempDir
        every { appPreferences.data } returns MutableStateFlow(AppPreferencesData())
        every { offlinePinStore.getPinnedFilePathSync(any(), any()) } returns null
        every { downloadManager.findItem(any(), any()) } returns null
    }

    private fun testDispatchers(scheduler: TestCoroutineScheduler): DispatchersProvider {
        val d = UnconfinedTestDispatcher(scheduler)
        return object : DispatchersProvider {
            override val io: CoroutineDispatcher = d
            override val default: CoroutineDispatcher = d
            override val main: CoroutineDispatcher = d
            override val unconfined: CoroutineDispatcher = d
        }
    }

    private fun newCache(scheduler: TestCoroutineScheduler) =
        MediaThumbnailCache(
            ctx = ctx,
            telegramClient = telegramClient,
            fileCacheDao = fileCacheDao,
            downloadManager = downloadManager,
            offlinePinStore = offlinePinStore,
            backupCrypto = backupCrypto,
            cloudSyncManager = cloudSyncManager,
            appPreferences = appPreferences,
            dispatchers = testDispatchers(scheduler),
        )

    @Test
    fun `restoreEncryptedThumbFromCloud deduplicates concurrent requests`() =
        runTest {
            val cache = newCache(testScheduler)
            val gate = CompletableDeferred<ByteArray>()
            coEvery { cloudSyncManager.restoreEncryptedThumbBytes(10L, 100L) } coAnswers {
                gate.await()
            }

            val a = async { cache.restoreEncryptedThumbFromCloud(10L, 100L) }
            val b = async { cache.restoreEncryptedThumbFromCloud(10L, 100L) }
            runCurrent()
            gate.complete(byteArrayOf(7, 8, 9))
            val pathA = a.await()
            val pathB = b.await()

            assertThat(pathA).isNotNull()
            assertThat(pathB).isEqualTo(pathA)
            coVerify(exactly = 1) { cloudSyncManager.restoreEncryptedThumbBytes(10L, 100L) }
        }

    @Test
    fun `restoreEncryptedThumbFromCloud retries immediately after clearThumbnailFailure`() =
        runTest {
            val cache = newCache(testScheduler)
            coEvery {
                cloudSyncManager.restoreEncryptedThumbBytes(10L, 200L)
            } returnsMany listOf(null, byteArrayOf(1, 2, 3, 4))

            assertThat(cache.restoreEncryptedThumbFromCloud(10L, 200L)).isNull()
            // Still inside failure cooldown -> skipped without hitting cloud again
            assertThat(cache.restoreEncryptedThumbFromCloud(10L, 200L)).isNull()
            coVerify(exactly = 1) { cloudSyncManager.restoreEncryptedThumbBytes(10L, 200L) }

            cache.clearThumbnailFailure(10L, 200L)
            val recovered = cache.restoreEncryptedThumbFromCloud(10L, 200L)
            assertThat(recovered).isNotNull()
            coVerify(exactly = 2) { cloudSyncManager.restoreEncryptedThumbBytes(10L, 200L) }
        }

    @Test
    fun `ensureThumbnail bypasses cooldown and recovers when force is true`() =
        runTest {
            val cache = newCache(testScheduler)
            val validThumb = File(tempDir, "ok_300.jpg").apply { writeBytes(byteArrayOf(10, 20, 30)) }
            val entity =
                FileCacheEntity(
                    messageId = 300L,
                    chatId = 10L,
                    fileName = "photo.jpg",
                    fileSize = 1024L,
                    mimeType = "image/jpeg",
                    thumbnailPath = null,
                    remoteId = "rem_300",
                    date = 1_000L,
                )

            coEvery { telegramClient.requestMessageThumbnail(10L, 300L) } returnsMany
                listOf(null, validThumb.absolutePath)
            every { telegramClient.downloadFile("rem_300", "") } returns
                flowOf(DownloadUpdate.Failed("timeout"))

            assertThat(cache.ensureThumbnail(entity, force = false)).isNull()
            // Second non-forced attempt stays in cooldown
            assertThat(cache.ensureThumbnail(entity, force = false)).isNull()
            coVerify(exactly = 1) { telegramClient.requestMessageThumbnail(10L, 300L) }

            val recovered = cache.ensureThumbnail(entity, force = true)
            assertThat(recovered).isEqualTo(validThumb.absolutePath)
            coVerify(exactly = 2) { telegramClient.requestMessageThumbnail(10L, 300L) }
        }
}
