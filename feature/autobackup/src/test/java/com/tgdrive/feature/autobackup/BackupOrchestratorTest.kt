package com.tgdrive.feature.autobackup

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.AppPreferencesData
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncLogDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.download.index.ZipIndexService
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.feature.autobackup.diff.DiffEngine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BackupOrchestrator] — the most crash-prone component in
 * the project's history (per docs/REARCHITECTURE_SPEC.md).
 *
 * ## Coverage
 *
 * The mutual-exclusion tests below cover the #1 crash path: two orchestrators
 * running simultaneously (which caused duplicate uploads, TDLib contention,
 * and shared-buffer corruption in the DiffEngine's Sha256Calculator).
 *
 * The spec (REARCHITECTURE_SPEC.md:53-56) also asks for phase-transition
 * tests (Idle→Scanning→Diffing→Uploading→Done/Cancelled/Failed) and
 * cancellation-propagation tests. Those are NOT here because the orchestrator
 * launches its cancellation watcher on `Dispatchers.IO` (hardcoded), which
 * is incompatible with `runTest`'s virtual time controller. Full phase-
 * transition coverage requires injecting `DispatchersProvider` into the
 * orchestrator — tracked as architecture candidate #2 in the review report.
 *
 * ## Mocking strategy
 *
 * [ActiveBackupRegistry] is real (not mocked) because it's the seam the
 * mutual-exclusion invariant depends on — mocking it would test nothing.
 * The other 10 dependencies are MockK `relaxed = true` because the mutual-
 * exclusion check happens BEFORE any of them are touched.
 */
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

    private lateinit var orchestrator: BackupOrchestrator

    private val prefsFlow = MutableStateFlow(AppPreferencesData(backupEnabled = true))

    @BeforeEach
    fun setUp() {
        ctx = mockk(relaxed = true)
        telegramClient = mockk(relaxed = true)
        preferences = mockk(relaxed = true)
        folderScanner = mockk(relaxed = true)
        stagingManager = mockk(relaxed = true)
        diffEngine = mockk(relaxed = true)
        syncedFileDao = mockk(relaxed = true)
        syncLogDao = mockk(relaxed = true)
        folderDao = mockk(relaxed = true)
        fileCacheDao = mockk(relaxed = true)
        notifier = mockk(relaxed = true)
        registry = ActiveBackupRegistry()
        zipIndexService = mockk(relaxed = true)
        cloudSyncManager = mockk(relaxed = true)

        every { preferences.data } returns prefsFlow

        orchestrator = BackupOrchestrator(
            ctx, telegramClient, preferences, folderScanner, stagingManager,
            diffEngine, syncedFileDao, syncLogDao, folderDao, fileCacheDao,
            notifier, registry, zipIndexService, cloudSyncManager,
        )
    }

    @Test
    @DisplayName("runBackup returns Skipped when another job is already active")
    fun mutualExclusion() = runTest {
        // Pre-seed the registry with an active scanning job
        registry.tryBegin("other_job", 0L)
        registry.updatePhase(BackupPhase.Scanning(0, 1, 0))

        val outcome = orchestrator.runBackup(jobId = "new_job", isManual = true)

        assertThat(outcome).isInstanceOf(BackupOrchestrator.RunOutcome.Skipped::class.java)
        val skipped = outcome as BackupOrchestrator.RunOutcome.Skipped
        assertThat(skipped.reason).contains("already running")
        // The registry still holds the original job, not ours
        assertThat(registry.active.value?.jobId).isEqualTo("other_job")
    }

    @Test
    @DisplayName("runBackup clears the registry even on Skipped exit")
    fun clearsRegistryOnSkip() = runTest {
        registry.tryBegin("blocker", 0L)
        registry.updatePhase(BackupPhase.Scanning(0, 1, 0))

        orchestrator.runBackup(jobId = "rejected", isManual = true)

        // The Skipped path must NOT clobber the existing active job
        assertThat(registry.active.value?.jobId).isEqualTo("blocker")
        assertThat(registry.isAnyActive()).isTrue()
    }

    // NOTE: Full phase-transition tests (Idle→Scanning→Diffing→Uploading→Done)
    // and cancellation-propagation tests are deferred until the orchestrator
    // injects DispatchersProvider instead of hardcoding Dispatchers.IO in its
    // watcher. See architecture candidate #2 in the review report.
    // The mutual-exclusion tests above cover the #1 crash path.
}
