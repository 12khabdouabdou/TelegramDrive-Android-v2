package com.tgdrive.feature.browser

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.audio.AudioPlayerController
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.AppPreferencesData
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadQueueState
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.UploadManager
import com.tgdrive.core.download.UploadQueueState
import com.tgdrive.core.download.UploadTracker
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.AccessLockManager
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.BiometricHelper
import com.tgdrive.core.telegram.TelegramClient
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class FolderSubfolderLogicTest {
    private val chatId = 777L

    private lateinit var telegramClient: TelegramClient
    private lateinit var fileCacheDao: FileCacheDao
    private lateinit var preferences: AppPreferences
    private lateinit var downloadManager: DownloadManager
    private lateinit var audioPlayerController: AudioPlayerController
    private lateinit var uploadTracker: UploadTracker
    private lateinit var uploadManager: UploadManager
    private lateinit var backupCrypto: BackupCrypto
    private lateinit var accessLockManager: AccessLockManager
    private lateinit var biometricHelper: BiometricHelper
    private lateinit var offlinePinStore: OfflinePinStore
    private lateinit var trashEntryDao: TrashEntryDao
    private lateinit var cloudSyncManager: CloudSyncManager
    private lateinit var mediaThumbnailCache: MediaThumbnailCache
    private lateinit var appContext: Context
    private lateinit var dispatchers: DispatchersProvider

    private lateinit var prefsFlow: MutableStateFlow<AppPreferencesData>
    private lateinit var dbItemsFlow: MutableStateFlow<List<FileCacheEntity>>
    private lateinit var dlStateFlow: MutableStateFlow<DownloadQueueState>
    private lateinit var uploadStateFlow: MutableStateFlow<UploadQueueState>
    private lateinit var completedEventsFlow: MutableSharedFlow<Long>

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        val testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        dispatchers =
            object : DispatchersProvider {
                override val io: CoroutineDispatcher = testDispatcher
                override val default: CoroutineDispatcher = testDispatcher
                override val main: CoroutineDispatcher = testDispatcher
                override val unconfined: CoroutineDispatcher = testDispatcher
            }

        telegramClient = mockk(relaxed = true)
        fileCacheDao = mockk(relaxed = true)
        preferences = mockk(relaxed = true)
        downloadManager = mockk(relaxed = true)
        audioPlayerController = mockk(relaxed = true)
        uploadTracker = mockk(relaxed = true)
        uploadManager = mockk(relaxed = true)
        backupCrypto = mockk(relaxed = true)
        accessLockManager = mockk(relaxed = true)
        biometricHelper = mockk(relaxed = true)
        offlinePinStore = mockk(relaxed = true)
        trashEntryDao = mockk(relaxed = true)
        cloudSyncManager = mockk(relaxed = true)
        mediaThumbnailCache = mockk(relaxed = true)
        appContext = mockk(relaxed = true)

        prefsFlow = MutableStateFlow(AppPreferencesData())
        dbItemsFlow = MutableStateFlow(emptyList())
        dlStateFlow = MutableStateFlow(DownloadQueueState())
        uploadStateFlow = MutableStateFlow(UploadQueueState())
        completedEventsFlow = MutableSharedFlow()

        val filesDir = File(tempDir.toFile(), "files").apply { mkdirs() }
        val cacheDir = File(tempDir.toFile(), "cache").apply { mkdirs() }
        every { appContext.filesDir } returns filesDir
        every { appContext.cacheDir } returns cacheDir
        every { appContext.getString(any(), *anyVararg()) } returns "msg"

        every { preferences.data } returns prefsFlow
        every { downloadManager.state } returns dlStateFlow
        every { uploadManager.uploads } returns uploadStateFlow
        every { uploadManager.completedChatEvents } returns completedEventsFlow
        coEvery { accessLockManager.hasPassword() } returns false
        coEvery { telegramClient.myUserId() } returns 12345L
        coEvery { telegramClient.fetchMessages(any(), any(), any()) } returns emptyList()
        coEvery { cloudSyncManager.syncFromCloud() } returns true
        every { cloudSyncManager.getCloudVirtualPath(any(), any()) } returns null
        every { cloudSyncManager.getCloudEncryptedThumbRemoteId(any(), any()) } returns null

        every { fileCacheDao.observeForChat(chatId, any(), any()) } returns dbItemsFlow
        coEvery { fileCacheDao.forChat(chatId) } answers { dbItemsFlow.value }
        coEvery { fileCacheDao.upsertAll(any()) } answers {
            val incoming = firstArg<List<FileCacheEntity>>()
            val map = dbItemsFlow.value.associateBy { it.messageId }.toMutableMap()
            incoming.forEach { map[it.messageId] = it }
            dbItemsFlow.value = map.values.toList()
        }
        coEvery { fileCacheDao.deleteById(chatId, any()) } answers {
            val msgId = secondArg<Long>()
            dbItemsFlow.value = dbItemsFlow.value.filterNot { it.messageId == msgId }
        }
        coEvery { fileCacheDao.updateVirtualPath(chatId, any(), any()) } answers {
            val msgId = secondArg<Long>()
            val vpath = thirdArg<String>()
            dbItemsFlow.value =
                dbItemsFlow.value.map { item ->
                    if (item.messageId == msgId) item.copy(virtualPath = vpath) else item
                }
        }
        coEvery { trashEntryDao.upsertAll(any()) } just Runs
        coEvery { fileCacheDao.updateThumbnailPaths(any()) } just Runs
        every { mediaThumbnailCache.needsThumbnailUpgrade(any()) } returns false
        every { mediaThumbnailCache.getEncryptedThumbnailPath(any(), any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm(): FolderViewModel =
        FolderViewModel(
            telegramClient = telegramClient,
            fileCacheDao = fileCacheDao,
            preferences = preferences,
            dispatchers = dispatchers,
            downloadManager = downloadManager,
            audioPlayerController = audioPlayerController,
            uploadTracker = uploadTracker,
            uploadManager = uploadManager,
            backupCrypto = backupCrypto,
            accessLockManager = accessLockManager,
            biometricHelper = biometricHelper,
            offlinePinStore = offlinePinStore,
            trashEntryDao = trashEntryDao,
            cloudSyncManager = cloudSyncManager,
            mediaThumbnailCache = mediaThumbnailCache,
            appContext = appContext,
        )

    private fun sampleItem(
        id: Long,
        name: String,
        virtualPath: String = "",
        thumbPath: String? = null,
        isEncrypted: Boolean = false,
        mimeType: String? = if (isEncrypted) "application/octet-stream" else "image/jpeg",
    ) = FileCacheEntity(
        messageId = id,
        chatId = chatId,
        fileName = name,
        fileSize = 1024L,
        mimeType = mimeType,
        thumbnailPath = thumbPath,
        remoteId = "rem_$id",
        date = 1_000L + id,
        virtualPath = virtualPath,
        isEncryptedVault = isEncrypted,
    )

    @Test
    fun `matchesSubfolderPrefix matches exact and nested paths only`() {
        assertThat(FolderViewModel.matchesSubfolderPrefix("Photos", "Photos")).isTrue()
        assertThat(FolderViewModel.matchesSubfolderPrefix("Photos/2026", "Photos")).isTrue()
        assertThat(FolderViewModel.matchesSubfolderPrefix("Photos/2026/Trips", "Photos/2026")).isTrue()
        assertThat(FolderViewModel.matchesSubfolderPrefix("Photos Backup", "Photos")).isFalse()
        assertThat(FolderViewModel.matchesSubfolderPrefix("", "Photos")).isFalse()
    }

    @Test
    fun `rewriteSubfolderPrefix rewrites exact and nested descendants`() {
        assertThat(FolderViewModel.rewriteSubfolderPrefix("Work", "Work", "Archive")).isEqualTo("Archive")
        assertThat(
            FolderViewModel.rewriteSubfolderPrefix("Work/Contracts/2026", "Work", "Archive"),
        ).isEqualTo("Archive/Contracts/2026")
        assertThat(FolderViewModel.rewriteSubfolderPrefix("WorkOld", "Work", "Archive")).isEqualTo("WorkOld")
    }

    @Test
    fun `sanitizeSubfolderSegment normalizes path separators`() {
        assertThat(FolderViewModel.sanitizeSubfolderSegment("  /Vacation\\2026/ ")).isEqualTo("Vacation_2026")
        assertThat(FolderViewModel.sanitizeSubfolderSegment("   /\\  ")).isEmpty()
    }

    @Test
    fun `deleteVirtualFolder trash mode deletes contents and undoLastTrash restores virtualPath`() =
        runTest {
            val vm = newVm()
            val collectJob = backgroundScope.launch { vm.state.collect {} }
            dbItemsFlow.value =
                listOf(
                    sampleItem(1L, "keep.jpg", virtualPath = "Other"),
                    sampleItem(2L, "a.jpg", virtualPath = "Trips"),
                    sampleItem(3L, "b.jpg", virtualPath = "Trips/Italy"),
                )
            vm.bind(chatId)
            vm.openVirtualFolder("Trips")
            advanceUntilIdle()

            val stats = vm.computeSubfolderStats("Trips")
            assertThat(stats.virtualPath).isEqualTo("Trips")
            assertThat(stats.displayName).isEqualTo("Trips")
            assertThat(stats.fileCount).isEqualTo(2)
            assertThat(stats.nestedSubfolderCount).isEqualTo(1)

            vm.deleteVirtualFolder("Trips", permanent = false)
            advanceUntilIdle()

            assertThat(dbItemsFlow.value.map { it.messageId }).containsExactly(1L)
            assertThat(vm.state.value.currentVirtualPath).isEmpty()
            coVerify(exactly = 0) { telegramClient.deleteMessage(any(), any()) }

            vm.undoLastTrash()
            advanceUntilIdle()

            val restored = dbItemsFlow.value.associateBy { it.messageId }
            assertThat(restored[2L]?.virtualPath).isEqualTo("Trips")
            assertThat(restored[3L]?.virtualPath).isEqualTo("Trips/Italy")
            verify { cloudSyncManager.noteVirtualPathChanged(chatId, 2L, "Trips", false) }
            verify { cloudSyncManager.noteVirtualPathChanged(chatId, 3L, "Trips/Italy", false) }
            collectJob.cancel()
        }

    @Test
    fun `deleteVirtualFolder permanent mode deletes messages on Telegram and unpins`() =
        runTest {
            val vm = newVm()
            val collectJob = backgroundScope.launch { vm.state.collect {} }
            dbItemsFlow.value =
                listOf(
                    sampleItem(10L, "root.jpg", virtualPath = ""),
                    sampleItem(11L, "doc1.pdf", virtualPath = "Docs/Tax"),
                    sampleItem(12L, "doc2.pdf", virtualPath = "Docs/Tax/2026"),
                )
            vm.bind(chatId)
            vm.openVirtualFolder("Docs/Tax")
            advanceUntilIdle()

            vm.deleteVirtualFolder("Docs/Tax", permanent = true)
            advanceUntilIdle()

            assertThat(dbItemsFlow.value.map { it.messageId }).containsExactly(10L)
            assertThat(vm.state.value.currentVirtualPath).isEqualTo("Docs")
            coVerify(exactly = 1) { telegramClient.deleteMessage(chatId, 11L) }
            coVerify(exactly = 1) { telegramClient.deleteMessage(chatId, 12L) }
            coVerify(exactly = 1) { offlinePinStore.unpinFile(chatId, 11L) }
            coVerify(exactly = 1) { offlinePinStore.unpinFile(chatId, 12L) }
            collectJob.cancel()
        }

    @Test
    fun `renameVirtualFolder updates nested file paths and custom empty folders`() =
        runTest {
            val vm = newVm()
            val collectJob = backgroundScope.launch { vm.state.collect {} }
            dbItemsFlow.value =
                listOf(
                    sampleItem(21L, "shot.jpg", virtualPath = "Media/Old"),
                    sampleItem(22L, "deep.jpg", virtualPath = "Media/Old/Raw"),
                )
            vm.bind(chatId)
            vm.openVirtualFolder("Media/Old")
            advanceUntilIdle()

            vm.renameVirtualFolder("Media/Old", "NewName")
            advanceUntilIdle()

            val byId = dbItemsFlow.value.associateBy { it.messageId }
            assertThat(byId[21L]?.virtualPath).isEqualTo("Media/NewName")
            assertThat(byId[22L]?.virtualPath).isEqualTo("Media/NewName/Raw")
            assertThat(vm.state.value.currentVirtualPath).isEqualTo("Media/NewName")
            verify { cloudSyncManager.noteVirtualPathChanged(chatId, 21L, "Media/NewName", false) }
            verify { cloudSyncManager.noteVirtualPathChanged(chatId, 22L, "Media/NewName/Raw", false) }
            collectJob.cancel()
        }

    @Test
    fun `refresh inside subfolder syncs cloud increments nonce and warms encrypted thumbs`() =
        runTest {
            every { backupCrypto.hasVaultPassphrase() } returns true
            every { backupCrypto.passphraseFingerprint() } returns "fp-123"
            coEvery { mediaThumbnailCache.restoreEncryptedThumbFromCloud(chatId, 31L) } returns "/tmp/enc_31.tgthumb"

            val vm = newVm()
            val collectJob = backgroundScope.launch { vm.state.collect {} }
            dbItemsFlow.value =
                listOf(
                    sampleItem(31L, "secret.jpg.tgenc", virtualPath = "Secret", isEncrypted = true),
                )
            vm.bind(chatId)
            vm.openVirtualFolder("Secret")
            advanceUntilIdle()

            val beforeNonce = vm.state.value.thumbRefreshNonce
            vm.refresh()
            advanceUntilIdle()

            assertThat(vm.state.value.thumbRefreshNonce).isGreaterThan(beforeNonce)
            assertThat(vm.state.value.encryptedPreviewFingerprint).isEqualTo("fp-123")
            coVerify(atLeast = 1) { cloudSyncManager.syncFromCloud() }
            coVerify(atLeast = 1) { mediaThumbnailCache.restoreEncryptedThumbFromCloud(chatId, 31L) }
            collectJob.cancel()
        }

    @Test
    fun `encrypted preview items appear in subfolder cover thumbs when enabled and hide when disabled`() =
        runTest {
            every { backupCrypto.hasVaultPassphrase() } returns true
            every { backupCrypto.passphraseFingerprint() } returns "fp-abc"

            val thumbFile = File(tempDir.toFile(), "41.tgethumb").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            dbItemsFlow.value =
                listOf(
                    sampleItem(
                        id = 41L,
                        name = "vaultpic.png.tgenc",
                        virtualPath = "Private",
                        thumbPath = thumbFile.absolutePath,
                        isEncrypted = true,
                    ),
                )

            val vm = newVm()
            val collectJob = backgroundScope.launch { vm.state.collect {} }
            vm.bind(chatId)
            advanceUntilIdle()

            val enabledThumbs = vm.state.value.childVirtualFolderThumbs["Private"].orEmpty()
            assertThat(enabledThumbs).hasSize(1)
            assertThat(enabledThumbs.first().isEncrypted).isTrue()
            assertThat(enabledThumbs.first().messageId).isEqualTo(41L)

            prefsFlow.value = prefsFlow.value.copy(showEncryptedPreviewsWhenUnlocked = false)
            advanceUntilIdle()

            assertThat(vm.state.value.encryptedPreviewFingerprint).isNull()
            assertThat(vm.state.value.childVirtualFolderThumbs["Private"].orEmpty()).isEmpty()
            collectJob.cancel()
        }

    @Test
    fun `opening subfolder warms all its items immediately after large root warm`() =
        runTest {
            every { mediaThumbnailCache.needsThumbnailUpgrade(null) } returns true
            val rootItems =
                (1L..200L).map { id ->
                    sampleItem(id = id, name = "root_$id.jpg", virtualPath = "")
                }
            val deepItems =
                (1001L..1005L).map { id ->
                    sampleItem(id = id, name = "deep_$id.jpg", virtualPath = "DeepSub")
                }
            dbItemsFlow.value = rootItems + deepItems

            val vm = newVm()
            val collectJob = backgroundScope.launch { vm.state.collect {} }
            vm.bind(chatId)
            advanceUntilIdle()

            vm.openVirtualFolder("DeepSub")
            advanceUntilIdle()

            for (id in 1001L..1005L) {
                coVerify(atLeast = 1) {
                    mediaThumbnailCache.ensureThumbnail(match { it.messageId == id }, any())
                }
            }
            collectJob.cancel()
        }

    @Test
    fun `large 1500 item folder caps subfolder cover thumbs at 4 per subfolder`() =
        runTest {
            val largeList =
                (0 until 1500).map { idx ->
                    val folderIdx = idx / 30
                    val msgId = idx.toLong() + 1L
                    sampleItem(
                        id = msgId,
                        name = "img_$msgId.jpg",
                        virtualPath = "Sub${folderIdx.toString().padStart(2, '0')}",
                        thumbPath = "/cache/t_$msgId.webp",
                    )
                }
            dbItemsFlow.value = largeList

            val vm = newVm()
            val collectJob = backgroundScope.launch { vm.state.collect {} }
            vm.bind(chatId)
            advanceUntilIdle()

            assertThat(vm.state.value.childVirtualFolders).hasSize(50)
            assertThat(vm.state.value.childVirtualFolders.first().second).isEqualTo(30)
            assertThat(vm.state.value.childVirtualFolderThumbs["Sub00"].orEmpty()).hasSize(4)
            assertThat(vm.state.value.childVirtualFolderThumbs["Sub49"].orEmpty()).hasSize(4)
            collectJob.cancel()
        }
}
