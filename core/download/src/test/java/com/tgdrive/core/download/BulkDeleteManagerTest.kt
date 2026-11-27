package com.tgdrive.core.download

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.telegram.TelegramClient
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BulkDeleteManagerTest {
    private val chatId = 909L

    private lateinit var appContext: Context
    private lateinit var telegramClient: TelegramClient
    private lateinit var fileCacheDao: FileCacheDao
    private lateinit var trashEntryDao: TrashEntryDao
    private lateinit var offlinePinStore: OfflinePinStore
    private lateinit var cloudSyncManager: CloudSyncManager
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var manager: BulkDeleteManager

    @BeforeEach
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        val dispatchers =
            object : DispatchersProvider {
                override val io: CoroutineDispatcher = testDispatcher
                override val default: CoroutineDispatcher = testDispatcher
                override val main: CoroutineDispatcher = testDispatcher
                override val unconfined: CoroutineDispatcher = testDispatcher
            }
        appContext = mockk(relaxed = true)
        telegramClient = mockk(relaxed = true)
        fileCacheDao = mockk(relaxed = true)
        trashEntryDao = mockk(relaxed = true)
        offlinePinStore = mockk(relaxed = true)
        cloudSyncManager = mockk(relaxed = true)

        coEvery { telegramClient.deleteMessages(any(), any()) } just Runs
        coEvery { telegramClient.deleteMessage(any(), any()) } just Runs
        coEvery { fileCacheDao.deleteByIds(any(), any()) } just Runs
        coEvery { trashEntryDao.upsertAll(any()) } just Runs
        coEvery { offlinePinStore.unpinFile(any(), any()) } just Runs

        manager =
            BulkDeleteManager(
                appContext = appContext,
                telegramClient = telegramClient,
                fileCacheDao = fileCacheDao,
                trashEntryDao = trashEntryDao,
                offlinePinStore = offlinePinStore,
                cloudSyncManager = cloudSyncManager,
                dispatchers = dispatchers,
            )
    }

    @AfterEach
    fun tearDown() {
        manager.cancelAll()
    }

    private fun entity(
        id: Long,
        pinned: Boolean = false,
    ) = FileCacheEntity(
        messageId = id,
        chatId = chatId,
        fileName = "item_$id.jpg",
        fileSize = 512L,
        mimeType = "image/jpeg",
        thumbnailPath = null,
        remoteId = "r_$id",
        date = 1_000L + id,
        virtualPath = "Archive/Bulk",
        isPinnedOffline = pinned,
    )

    @Test
    fun `permanent delete of 1650 items batches 100-item deletes unpins only pinned and clears activeJobs`() =
        runTest(testDispatcher) {
            val targets = (1L..1650L).map { id -> entity(id, pinned = id % 500L == 0L) }
            val deletedBatches = mutableListOf<List<Long>>()
            coEvery { telegramClient.deleteMessages(chatId, capture(deletedBatches)) } just Runs

            var completion: BulkDeleteCompletion? = null
            val collectJob = backgroundScope.launch { completion = manager.completions.first() }

            manager.enqueueDelete(chatId, "Archive", targets, permanent = true)
            advanceUntilIdle()

            assertThat(deletedBatches).hasSize(17)
            assertThat(deletedBatches.first()).hasSize(100)
            assertThat(deletedBatches.last()).hasSize(50)
            coVerify(exactly = 17) { fileCacheDao.deleteByIds(chatId, any()) }
            coVerify(exactly = 1) { offlinePinStore.unpinFile(chatId, 500L) }
            coVerify(exactly = 1) { offlinePinStore.unpinFile(chatId, 1000L) }
            coVerify(exactly = 1) { offlinePinStore.unpinFile(chatId, 1500L) }
            coVerify(exactly = 1) { cloudSyncManager.schedulePushMetadata() }
            assertThat(manager.activeJobs.value).isEmpty()
            assertThat(completion?.deletedCount).isEqualTo(1650)
            assertThat(completion?.totalCount).isEqualTo(1650)
            assertThat(completion?.error).isNull()
            collectJob.cancel()
        }

    @Test
    fun `trash mode batches upserts and deletes 600 items across 250-item chunks`() =
        runTest(testDispatcher) {
            val targets = (1L..600L).map { entity(it) }
            val dbDeleteBatches = mutableListOf<List<Long>>()
            coEvery { fileCacheDao.deleteByIds(chatId, capture(dbDeleteBatches)) } just Runs

            var completion: BulkDeleteCompletion? = null
            val collectJob = backgroundScope.launch { completion = manager.completions.first() }

            manager.enqueueDelete(chatId, "Folder600", targets, permanent = false)
            advanceUntilIdle()

            assertThat(dbDeleteBatches.map { it.size }).containsExactly(250, 250, 100).inOrder()
            coVerify(exactly = 3) { trashEntryDao.upsertAll(any()) }
            assertThat(completion?.trashedEntries).hasSize(600)
            assertThat(completion?.permanent).isFalse()
            assertThat(manager.activeJobs.value).isEmpty()
            collectJob.cancel()
        }

    @Test
    fun `batch failure falls back itemwise isolates bad item and continues remaining chunks`() =
        runTest(testDispatcher) {
            val targets = (1L..250L).map { entity(it) }
            coEvery {
                telegramClient.deleteMessages(chatId, match { it.contains(150L) })
            } throws IllegalStateException("batch rejected")
            coEvery { telegramClient.deleteMessage(chatId, 150L) } throws IllegalStateException("perm denied")

            val dbDeletedIds = mutableListOf<List<Long>>()
            coEvery { fileCacheDao.deleteByIds(chatId, capture(dbDeletedIds)) } just Runs

            var completion: BulkDeleteCompletion? = null
            val collectJob = backgroundScope.launch { completion = manager.completions.first() }

            manager.enqueueDelete(chatId, "Partial", targets, permanent = true)
            advanceUntilIdle()

            val totalDbRemoved = dbDeletedIds.flatten()
            assertThat(totalDbRemoved).hasSize(249)
            assertThat(totalDbRemoved).doesNotContain(150L)
            assertThat(completion?.deletedCount).isEqualTo(249)
            assertThat(completion?.totalCount).isEqualTo(250)
            assertThat(completion?.error).isEqualTo("perm denied")
            collectJob.cancel()
        }
}
