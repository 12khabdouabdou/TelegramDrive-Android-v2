package com.tgdrive.feature.autobackup

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.AppPreferencesData
import com.tgdrive.core.datastore.BackupQuality
import com.tgdrive.core.datastore.BackupUploadOrder
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncLogDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.SyncLogEntity
import com.tgdrive.core.db.entity.SyncedFileEntity
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.index.ZipIndexService
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.telegram.UploadResult
import com.tgdrive.feature.autobackup.diff.DiffEngine
import com.tgdrive.feature.autobackup.diff.LocalFile
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackupOrchestratorTest {

    private lateinit var ctx: Context
    private lateinit var telegramClient: TelegramClient
    private lateinit var preferences: AppPreferences
    private lateinit var folderScanner: FolderScanner
    private lateinit var stagingManager: StagingManager
    private lateinit var diffEngine: DiffEngine
    private lateinit var syncedFileDao: SyncedFileDao
    private lateinit var syncLogDao: SyncLogDao
    private lateinit var folderDao: FolderDao
    private lateinit var fileCacheDao: FileCacheDao
    private lateinit var notifier: BackupNotifier
    private lateinit var registry: ActiveBackupRegistry
    private lateinit var zipIndexService: ZipIndexService
    private lateinit var cloudSyncManager: CloudSyncManager
    private lateinit var mediaThumbnailCache: MediaThumbnailCache
    private lateinit var backupCrypto: BackupCrypto
    private lateinit var testDispatcher: CoroutineDispatcher
    private lateinit var dispatchers: DispatchersProvider

    private lateinit var orchestrator: BackupOrchestrator

    private val prefsFlow = MutableStateFlow(
        AppPreferencesData(
            backupEnabled = true,
            backupSyncIndexRestored = true,
        ),
    )

    @BeforeEach
    fun setUp() {
        ctx = mockk(relaxed = true)
        telegramClient = mockk(relaxed = true)
        preferences = mockk(relaxed = true)
        folderScanner = mockk(relaxed = true)
        stagingManager = mockk(relaxed = true)
        diffEngine = DiffEngine()
        syncedFileDao = mockk(relaxed = true)
        syncLogDao = mockk(relaxed = true)
        folderDao = mockk(relaxed = true)
        fileCacheDao = mockk(relaxed = true)
        notifier = mockk(relaxed = true)
        registry = ActiveBackupRegistry()
        zipIndexService = mockk(relaxed = true)
        cloudSyncManager = mockk(relaxed = true)
        mediaThumbnailCache = mockk(relaxed = true)
        backupCrypto = mockk(relaxed = true)
        testDispatcher = UnconfinedTestDispatcher()
        dispatchers = object : DispatchersProvider {
            override val io: CoroutineDispatcher get() = testDispatcher
            override val default: CoroutineDispatcher get() = testDispatcher
            override val main: CoroutineDispatcher get() = testDispatcher
            override val unconfined: CoroutineDispatcher get() = testDispatcher
        }

        every { preferences.data } returns prefsFlow
        every { backupCrypto.hasVaultPassphrase() } returns true
        coEvery { telegramClient.myUserId() } returns 999L
        coEvery { telegramClient.maxUploadSizeBytes() } returns 2_000_000_000L
        coEvery { syncLogDao.get(any()) } returns null
        every { stagingManager.extractCaptureTimestampMs(any(), any(), any()) } answers { thirdArg() }

        orchestrator = BackupOrchestrator(
            ctx = ctx,
            telegramClient = telegramClient,
            preferences = preferences,
            folderScanner = folderScanner,
            stagingManager = stagingManager,
            diffEngine = diffEngine,
            syncedFileDao = syncedFileDao,
            syncLogDao = syncLogDao,
            folderDao = folderDao,
            fileCacheDao = fileCacheDao,
            notifier = notifier,
            registry = registry,
            zipIndexService = zipIndexService,
            cloudSyncManager = cloudSyncManager,
            mediaThumbnailCache = mediaThumbnailCache,
            backupCrypto = backupCrypto,
            dispatchers = dispatchers,
        )
    }

    @Test
    @DisplayName("runBackup returns Skipped when another job is already active")
    fun mutualExclusion() = runTest {
        registry.tryBegin("other_job", 0L)
        registry.updatePhase(BackupPhase.Scanning(0, 1, 0))

        val outcome = orchestrator.runBackup(jobId = "new_job", isManual = true)

        assertThat(outcome).isInstanceOf(BackupOrchestrator.RunOutcome.Skipped::class.java)
        val skipped = outcome as BackupOrchestrator.RunOutcome.Skipped
        assertThat(skipped.reason).contains("already running")
        assertThat(registry.active.value?.jobId).isEqualTo("other_job")
    }

    @Test
    @DisplayName("runBackup preserves registry when Skipped")
    fun clearsRegistryOnSkip() = runTest {
        registry.tryBegin("blocker", 0L)
        registry.updatePhase(BackupPhase.Scanning(0, 1, 0))

        orchestrator.runBackup(jobId = "rejected", isManual = true)

        assertThat(registry.active.value?.jobId).isEqualTo("blocker")
        assertThat(registry.isAnyActive()).isTrue()
    }

    @Test
    fun `full backup transitions through phases and records bytes and virtualPath`() = runTest(testDispatcher) {
        val folderUri = "content://com.android.externalstorage.documents/tree/primary%3ACamera"
        prefsFlow.value = prefsFlow.value.copy(
            backupFolderUris = setOf(folderUri),
            backupUploadOrder = BackupUploadOrder.NEWEST_FIRST,
        )

        val f1 = LocalFile("content://doc/1", "old.jpg", 100L, 1_000L, "image/jpeg", relativePath = "2025")
        val f2 = LocalFile("content://doc/2", "new.jpg", 250L, 5_000L, "image/jpeg", relativePath = "2026")
        coEvery { folderScanner.scanDetailed(folderUri, any(), any()) } returns FolderScanResult(
            files = listOf(f1, f2),
            folderName = "Camera",
        )
        every { stagingManager.stageForUpload(any(), any(), any(), any(), any(), any()) } answers {
            val name = secondArg<String>()
            val size = arg<Long>(3)
            StagingManager.StageOutcome.Ready("/tmp/$name", name, "image/jpeg", size)
        }
        val uploadedNames = mutableListOf<String>()
        var nextMsgId = 10L
        coEvery { telegramClient.uploadFile(any(), any(), any(), any(), any()) } answers {
            val name = thirdArg<String>()
            uploadedNames += name
            UploadResult(messageId = nextMsgId++, remoteId = "rem_$name")
        }

        val observedPhases = mutableListOf<BackupPhase>()
        val collectJob = launch(testDispatcher) {
            registry.active.collect { st ->
                st?.phase?.let { observedPhases += it }
            }
        }

        val outcome = orchestrator.runBackup(jobId = "job_full", isManual = true)
        collectJob.cancel()

        assertThat(outcome).isEqualTo(BackupOrchestrator.RunOutcome.Success(uploaded = 2, skipped = 0, total = 2))
        // NEWEST_FIRST uploads new.jpg (mtime=5000) before old.jpg (mtime=1000)
        assertThat(uploadedNames).containsExactly("new.jpg", "old.jpg").inOrder()
        assertThat(observedPhases.any { it is BackupPhase.Scanning }).isTrue()
        assertThat(observedPhases.any { it is BackupPhase.Diffing }).isTrue()
        assertThat(observedPhases.any { it is BackupPhase.Uploading }).isTrue()
        assertThat(observedPhases.any { it is BackupPhase.Done }).isTrue()

        val cacheEntries = mutableListOf<List<FileCacheEntity>>()
        coVerify { fileCacheDao.upsertAll(capture(cacheEntries)) }
        val firstCached = cacheEntries.first().single()
        assertThat(firstCached.virtualPath).isEqualTo("Camera/2026")
        assertThat(firstCached.date).isEqualTo(5_000L)

        val logSlot = mutableListOf<SyncLogEntity>()
        coVerify { syncLogDao.upsert(capture(logSlot)) }
        val finalLog = logSlot.last()
        assertThat(finalLog.status).isEqualTo("SUCCESS")
        assertThat(finalLog.bytesUploaded).isEqualTo(350L)
        assertThat(finalLog.failedCount).isEqualTo(0)
    }

    @Test
    fun `permission lost skips stale row deletion and fails non-retryably`() = runTest(testDispatcher) {
        val folderUri = "content://com.android.externalstorage.documents/tree/primary%3APrivate"
        prefsFlow.value = prefsFlow.value.copy(backupFolderUris = setOf(folderUri))

        coEvery { folderScanner.scanDetailed(folderUri, any(), any()) } returns FolderScanResult(
            files = emptyList(),
            folderName = "Private",
            permissionLost = true,
            scanError = "Folder permission lost",
        )
        coEvery { syncedFileDao.forFolder(any()) } returns listOf(
            SyncedFileEntity(localPath = "content://doc/existing", telegramMessageId = 5L, sha256 = null, size = 100L, syncedAt = 1L, folderId = null),
        )

        val outcome = orchestrator.runBackup(jobId = "job_perm", isManual = true)

        assertThat(outcome).isInstanceOf(BackupOrchestrator.RunOutcome.Failed::class.java)
        val failed = outcome as BackupOrchestrator.RunOutcome.Failed
        assertThat(failed.retryable).isFalse()

        coVerify(exactly = 0) { syncedFileDao.deleteByPath(any()) }
        coVerify(exactly = 0) { syncedFileDao.deleteByPathAndFolder(any(), any()) }

        val logs = mutableListOf<SyncLogEntity>()
        coVerify { syncLogDao.upsert(capture(logs)) }
        val finalLog = logs.last()
        assertThat(finalLog.failedCount).isEqualTo(1)
        assertThat(BackupFailureRecord.decode(finalLog.failedDetailsJson).single().reason)
            .contains("permission lost")
    }

    @Test
    fun `oversized files and retry failed paths work accurately`() = runTest(testDispatcher) {
        val folderUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
        prefsFlow.value = prefsFlow.value.copy(backupFolderUris = setOf(folderUri))
        coEvery { telegramClient.maxUploadSizeBytes() } returns 1_000L

        val bigFile = LocalFile("content://doc/big", "huge.mkv", 2_500L, 2_000L, "video/x-matroska")
        val okFile = LocalFile("content://doc/ok", "clip.mp4", 400L, 1_000L, "video/mp4")
        coEvery { folderScanner.scanDetailed(folderUri, any(), any()) } returns FolderScanResult(
            files = listOf(bigFile, okFile),
            folderName = "Movies",
        )
        every { stagingManager.stageForUpload(any(), any(), any(), any(), any(), any()) } answers {
            StagingManager.StageOutcome.Ready("/tmp/clip.mp4", "clip.mp4", "video/mp4", 400L)
        }
        coEvery { telegramClient.uploadFile(any(), any(), any(), any(), any()) } returns UploadResult(99L, "rem99")

        val outcome = orchestrator.runBackup(
            jobId = "job_mixed",
            isManual = true,
            onlyLocalPaths = setOf("content://doc/big", "content://doc/ok"),
        )

        assertThat(outcome).isInstanceOf(BackupOrchestrator.RunOutcome.Partial::class.java)
        coVerify(exactly = 0) {
            stagingManager.stageForUpload("content://doc/big", any(), any(), any(), BackupQuality.ORIGINAL, false)
        }
        coVerify(exactly = 1) {
            telegramClient.uploadFile(any(), any(), "clip.mp4", any(), any())
        }

        val logs = mutableListOf<SyncLogEntity>()
        coVerify { syncLogDao.upsert(capture(logs)) }
        val captured = logs.last()
        assertThat(captured.failedCount).isEqualTo(1)
        assertThat(BackupFailureRecord.decode(captured.failedDetailsJson).single().fileName).isEqualTo("huge.mkv")
    }

    @Test
    fun `global backupEncryptAll encrypts unflagged folder backups`() = runTest(testDispatcher) {
        val folderUri = "content://com.android.externalstorage.documents/tree/primary%3ADocs"
        prefsFlow.value = prefsFlow.value.copy(
            backupFolderUris = setOf(folderUri),
            backupEncryptAll = true,
            backupEncryptedFolderUris = emptySet(),
        )
        val doc = LocalFile("content://doc/tax", "tax.pdf", 200L, 2_000L, "application/pdf")
        coEvery { folderScanner.scanDetailed(folderUri, any(), any()) } returns FolderScanResult(
            files = listOf(doc),
            folderName = "Docs",
        )
        every { stagingManager.stageForUpload(any(), any(), any(), any(), any(), any()) } answers {
            val encryptedFlag = arg<Boolean>(5)
            assertThat(encryptedFlag).isTrue()
            StagingManager.StageOutcome.Ready(
                "/tmp/tax.pdf.tgenc",
                "tax.pdf.tgenc",
                "application/octet-stream",
                260L,
                wasEncrypted = true,
            )
        }
        coEvery { telegramClient.uploadFile(any(), any(), any(), any(), any()) } returns UploadResult(120L, "rem120")

        val outcome = orchestrator.runBackup(jobId = "job_global_enc", isManual = true)

        assertThat(outcome).isEqualTo(BackupOrchestrator.RunOutcome.Success(uploaded = 1, skipped = 0, total = 1))
        val cacheEntries = mutableListOf<List<FileCacheEntity>>()
        coVerify { fileCacheDao.upsertAll(capture(cacheEntries)) }
        assertThat(cacheEntries.single().single().isEncryptedVault).isTrue()
        assertThat(cacheEntries.single().single().fileName).isEqualTo("tax.pdf.tgenc")
    }

    @Test
    fun `encrypted backup fails fast when PassVault passphrase is not configured`() = runTest(testDispatcher) {
        val folderUri = "content://com.android.externalstorage.documents/tree/primary%3ADocs"
        prefsFlow.value = prefsFlow.value.copy(
            backupFolderUris = setOf(folderUri),
            backupEncryptAll = true,
        )
        every { backupCrypto.hasVaultPassphrase() } returns false
        val doc = LocalFile("content://doc/tax", "tax.pdf", 200L, 2_000L, "application/pdf")
        coEvery { folderScanner.scanDetailed(folderUri, any(), any()) } returns FolderScanResult(
            files = listOf(doc),
            folderName = "Docs",
        )

        val outcome = orchestrator.runBackup(jobId = "job_no_passphrase", isManual = true)

        assertThat(outcome).isInstanceOf(BackupOrchestrator.RunOutcome.Failed::class.java)
        coVerify(exactly = 0) {
            stagingManager.stageForUpload(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `moved file reconciles virtualPath and cloud sync without re-uploading`() = runTest(testDispatcher) {
        val folderUri = "content://com.android.externalstorage.documents/tree/primary%3ACamera"
        prefsFlow.value = prefsFlow.value.copy(
            backupFolderUris = setOf(folderUri),
            backupAutoOrganizeByDate = true,
        )
        val movedLocal = LocalFile(
            absolutePath = "content://doc/sub/moved.jpg",
            name = "moved.jpg",
            size = 4_096L,
            lastModified = 1_700_000_000_000L,
            mimeType = "image/jpeg",
            relativePath = "Trips",
        )
        coEvery { folderScanner.scanDetailed(folderUri, any(), any()) } returns FolderScanResult(
            files = listOf(movedLocal),
            folderName = "Camera",
        )
        coEvery { syncedFileDao.forFolder(any()) } returns listOf(
            SyncedFileEntity(
                localPath = "content://doc/moved.jpg",
                telegramMessageId = 777L,
                sha256 = "sha777",
                size = 4_096L,
                syncedAt = 1L,
                folderId = null,
                lastModified = 1_700_000_000_000L,
            ),
        )

        val expectedVpath = BackupOrchestrator.buildVirtualPath("Camera", "Trips", true, 1_700_000_000_000L)
        val outcome = orchestrator.runBackup(jobId = "job_moved", isManual = true)

        assertThat(outcome).isEqualTo(BackupOrchestrator.RunOutcome.Success(uploaded = 0, skipped = 1, total = 0))
        coVerify(exactly = 0) { telegramClient.uploadFile(any(), any(), any(), any(), any()) }
        coVerify { syncedFileDao.deleteByPathAndFolder("content://doc/moved.jpg", -1L) }
        coVerify { fileCacheDao.updateVirtualPath(999L, 777L, expectedVpath) }
        coVerify { cloudSyncManager.noteVirtualPathChanged(999L, 777L, expectedVpath, schedulePush = false) }
        coVerify { cloudSyncManager.schedulePushMetadata() }
    }

    @Test
    fun `buildVirtualPath combines relative subfolder with year and month when organizeByDate is enabled`() {
        val ts = java.time.LocalDate.of(2026, 11, 5)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val combined = BackupOrchestrator.buildVirtualPath("Camera", "Vacations", organizeByDate = true, captureTimestampMs = ts)
        assertThat(combined).isEqualTo("Camera/Vacations/2026/11")

        val alreadyDated = BackupOrchestrator.buildVirtualPath("Camera", "2025/Trips", organizeByDate = true, captureTimestampMs = ts)
        assertThat(alreadyDated).isEqualTo("Camera/2025/Trips")
    }

    @Test
    fun `concurrent backup bounds staged files while uploads are suspended`() = runTest(testDispatcher) {
        val folderUri = "content://com.android.externalstorage.documents/tree/primary%3ABulk"
        prefsFlow.value = prefsFlow.value.copy(backupFolderUris = setOf(folderUri))
        val files = (1..10).map { i ->
            LocalFile("content://doc/$i", "img_$i.jpg", 1_024L, 1_000L + i, "image/jpeg")
        }
        coEvery { folderScanner.scanDetailed(folderUri, any(), any()) } returns FolderScanResult(
            files = files,
            folderName = "Bulk",
        )

        val activeStaged = java.util.concurrent.atomic.AtomicInteger(0)
        val peakStaged = java.util.concurrent.atomic.AtomicInteger(0)
        every { stagingManager.stageForUpload(any(), any(), any(), any(), any(), any()) } answers {
            val name = secondArg<String>()
            val cur = activeStaged.incrementAndGet()
            peakStaged.updateAndGet { prev -> maxOf(prev, cur) }
            StagingManager.StageOutcome.Ready("/tmp/$name", name, "image/jpeg", 1_024L)
        }
        every { stagingManager.release(any()) } answers {
            if (firstArg<String?>() != null) {
                activeStaged.decrementAndGet()
            }
        }

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val nextId = java.util.concurrent.atomic.AtomicLong(1L)
        coEvery { telegramClient.uploadFile(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            val id = nextId.incrementAndGet()
            UploadResult(id, "rem_$id")
        }

        var outcome: BackupOrchestrator.RunOutcome? = null
        val job = launch {
            outcome = orchestrator.runBackup(jobId = "job_bounded_stage", isManual = true)
        }
        kotlinx.coroutines.yield()

        assertThat(peakStaged.get()).isIn(1..4)
        assertThat(activeStaged.get()).isAtMost(4)

        gate.complete(Unit)
        job.join()

        assertThat(outcome).isEqualTo(BackupOrchestrator.RunOutcome.Success(uploaded = 10, skipped = 0, total = 10))
        assertThat(activeStaged.get()).isEqualTo(0)
        assertThat(peakStaged.get()).isAtMost(4)
    }

    @Test
    fun `autobackup skips existing same-folder files across encrypted and unencrypted modes`() =
        runTest(testDispatcher) {
            val folderUri = "content://com.android.externalstorage.documents/tree/primary%3ACamera"
            val photo = LocalFile("content://doc/photo", "photo.jpg", 2_048L, 1_500L, "image/jpeg")
            val clip = LocalFile("content://doc/clip", "clip.mp4", 4_096L, 1_600L, "video/mp4")
            val unique = LocalFile("content://doc/unique", "unique.jpg", 1_024L, 1_700L, "image/jpeg")
            coEvery { folderScanner.scanDetailed(folderUri, any(), any()) } returns FolderScanResult(
                files = listOf(photo, clip, unique),
                folderName = "Camera",
            )
            coEvery { fileCacheDao.forChat(999L) } returns listOf(
                FileCacheEntity(
                    messageId = 101L,
                    chatId = 999L,
                    fileName = "photo.jpg.tgenc",
                    fileSize = 2_100L,
                    mimeType = "application/octet-stream",
                    thumbnailPath = null,
                    remoteId = "r101",
                    date = 1_500L,
                    virtualPath = "Camera",
                    isEncryptedVault = true,
                ),
                FileCacheEntity(
                    messageId = 102L,
                    chatId = 999L,
                    fileName = "clip.mp4",
                    fileSize = 4_096L,
                    mimeType = "video/mp4",
                    thumbnailPath = null,
                    remoteId = "r102",
                    date = 1_600L,
                    virtualPath = "Camera",
                    isEncryptedVault = false,
                ),
                FileCacheEntity(
                    messageId = 103L,
                    chatId = 999L,
                    fileName = "unique.jpg",
                    fileSize = 1_024L,
                    mimeType = "image/jpeg",
                    thumbnailPath = null,
                    remoteId = "r103",
                    date = 1_700L,
                    virtualPath = "Archive",
                    isEncryptedVault = false,
                ),
            )
            every { stagingManager.stageForUpload(any(), any(), any(), any(), any(), any()) } answers {
                val name = secondArg<String>()
                val enc = arg<Boolean>(5)
                val outName = if (enc) "$name.tgenc" else name
                StagingManager.StageOutcome.Ready(
                    path = "/tmp/$outName",
                    effectiveName = outName,
                    effectiveMime = "application/octet-stream",
                    effectiveSize = 1_100L,
                    wasEncrypted = enc,
                )
            }
            coEvery { telegramClient.uploadFile(any(), any(), any(), any(), any()) } returns UploadResult(201L, "rem201")

            prefsFlow.value = prefsFlow.value.copy(
                backupFolderUris = setOf(folderUri),
                backupEncryptAll = false,
            )
            val unencOutcome = orchestrator.runBackup(jobId = "job_dup_unenc", isManual = true)
            assertThat(unencOutcome).isEqualTo(
                BackupOrchestrator.RunOutcome.Success(uploaded = 1, skipped = 2, total = 1),
            )
            coVerify {
                syncedFileDao.upsert(
                    match { it.localPath == "content://doc/photo" && it.telegramMessageId == 101L },
                )
            }
            coVerify(exactly = 1) {
                telegramClient.uploadFile(any(), any(), "unique.jpg", any(), any())
            }

            prefsFlow.value = prefsFlow.value.copy(
                backupFolderUris = setOf(folderUri),
                backupEncryptAll = true,
            )
            val encOutcome = orchestrator.runBackup(jobId = "job_dup_enc", isManual = true)
            assertThat(encOutcome).isEqualTo(
                BackupOrchestrator.RunOutcome.Success(uploaded = 1, skipped = 2, total = 1),
            )
            coVerify(exactly = 1) {
                telegramClient.uploadFile(any(), any(), "unique.jpg.tgenc", any(), any())
            }
        }
}
