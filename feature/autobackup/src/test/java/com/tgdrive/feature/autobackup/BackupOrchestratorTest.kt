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
import io.mockk.slot
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
        coEvery { telegramClient.myUserId() } returns 999L
        coEvery { telegramClient.maxUploadSizeBytes() } returns 2_000_000_000L
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

        val logSlot = slot<SyncLogEntity>()
        coVerify { syncLogDao.upsert(capture(logSlot)) }
        val captured = logSlot.captured
        assertThat(captured.failedCount).isEqualTo(1)
        assertThat(BackupFailureRecord.decode(captured.failedDetailsJson).single().fileName).isEqualTo("huge.mkv")
    }
}
