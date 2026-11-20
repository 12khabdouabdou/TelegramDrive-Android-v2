package com.tgdrive.feature.autobackup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.AppPreferencesData
import com.tgdrive.core.datastore.BackupSchedule
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncLogDao
import com.tgdrive.core.db.entity.FolderEntity
import com.tgdrive.core.db.entity.SyncLogEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AutoBackupUiState(
    val prefs: AppPreferencesData = AppPreferencesData(),
    val folders: List<FolderEntity> = emptyList(),
    val history: List<SyncLogEntity> = emptyList(),
    val historyLoaded: Boolean = false,
)

/**
 * ViewModel for the AutoBackup screen.
 *
 * Now a thin shell: all the actual work (scan/diff/upload/finalize) lives
 * in [BackupOrchestrator], invoked via [BackupScheduler.runNow] which
 * enqueues a [com.tgdrive.feature.autobackup.worker.BackupWorker]. The
 * ViewModel just observes [ActiveBackupRegistry.active] for live progress,
 * and proxies preference changes.
 *
 * This fixes several long-standing bugs:
 *
 *   - **ViewModel-scoped progress**: progress used to live in
 *     `_progress: MutableStateFlow`, which was lost on navigation → the
 *     "already running" check was unreliable → duplicate concurrent runs.
 *     Now progress lives in the Singleton [ActiveBackupRegistry], which
 *     survives ViewModel recreation.
 *
 *   - **runNow in viewModelScope**: used to run the entire pipeline in
 *     `viewModelScope.launch(IO)`, which died when the user navigated away,
 *     and didn't use a foreground service → Android 12+ would kill it. Now
 *     runNow enqueues a WorkManager job that survives process death.
 *
 *   - **Constraint bypass**: the manual run used to bypass WiFi-only /
 *     charging-only constraints. Now constraints are applied by WorkManager
 *     for periodic runs; manual runs bypass them (which is what the user
 *     expects when they tap "Run now").
 */
@HiltViewModel
class AutoBackupViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val folderDao: FolderDao,
    private val syncLogDao: SyncLogDao,
    private val scheduler: BackupScheduler,
    private val registry: ActiveBackupRegistry,
    private val dispatchers: DispatchersProvider,
) : ViewModel() {

    val state: StateFlow<AutoBackupUiState> = combine(
        preferences.data,
        folderDao.observeAll(),
        syncLogDao.observeRecent(),
    ) { prefs, folders, history ->
        AutoBackupUiState(
            prefs = prefs,
            folders = folders,
            history = history,
            historyLoaded = true,
        )
    }.catch { t -> Log.w("AutoBackupViewModel: state flow error", t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AutoBackupUiState())

    /** Live progress of the active backup job (or null if idle). Survives navigation. */
    val activeJob: StateFlow<BackupJobState?> = registry.active

    fun toggleEnabled(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            try {
                preferences.update {
                    if (enabled) it.copy(backupEnabled = true)
                    else it.copy(backupEnabled = false, backupPaused = false)
                }
                scheduler.rescheduleIfEnabled()
            } catch (t: Throwable) { Log.w("AutoBackup: toggleEnabled failed", t) }
        }
    }

    fun setPaused(paused: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            try {
                preferences.update { it.copy(backupPaused = paused) }
                scheduler.rescheduleIfEnabled()
            } catch (t: Throwable) { Log.w("AutoBackup: setPaused failed", t) }
        }
    }

    fun addFolderUri(uri: String) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupFolderUris = it.backupFolderUris + uri) } }
            catch (t: Throwable) { Log.w("AutoBackup: addFolderUri failed", t) }
        }
    }

    fun removeFolderUri(uri: String) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupFolderUris = it.backupFolderUris - uri) } }
            catch (t: Throwable) { Log.w("AutoBackup: removeFolderUri failed", t) }
        }
    }

    fun setTargetFolder(folderId: Long) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupTargetFolderId = folderId) } }
            catch (t: Throwable) { Log.w("AutoBackup: setTargetFolder failed", t) }
        }
    }

    fun setSchedule(schedule: BackupSchedule) {
        viewModelScope.launch(dispatchers.io) {
            try {
                preferences.update { it.copy(backupSchedule = schedule) }
                scheduler.rescheduleIfEnabled()
            } catch (t: Throwable) { Log.w("AutoBackup: setSchedule failed", t) }
        }
    }

    fun setWifiOnly(v: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            try {
                preferences.update { it.copy(backupWifiOnly = v) }
                scheduler.rescheduleIfEnabled()
            } catch (t: Throwable) { Log.w("AutoBackup: setWifiOnly failed", t) }
        }
    }

    fun setChargingOnly(v: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            try {
                preferences.update { it.copy(backupChargingOnly = v) }
                scheduler.rescheduleIfEnabled()
            } catch (t: Throwable) { Log.w("AutoBackup: setChargingOnly failed", t) }
        }
    }

    fun setStrict(v: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupStrictMode = v) } }
            catch (t: Throwable) { Log.w("AutoBackup: setStrict failed", t) }
        }
    }

    fun setDeleteAfter(v: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupDeleteAfterUpload = v) } }
            catch (t: Throwable) { Log.w("AutoBackup: setDeleteAfter failed", t) }
        }
    }

    fun setZipFolders(v: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupZipFolders = v) } }
            catch (t: Throwable) { Log.w("AutoBackup: setZipFolders failed", t) }
        }
    }

    fun setExcludePatterns(v: String) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupExcludePatterns = v) } }
            catch (t: Throwable) { Log.w("AutoBackup: setExcludePatterns failed", t) }
        }
    }

    /**
     * Trigger an immediate backup. Enqueues a WorkManager job (which
     * survives process death) — the actual pipeline runs in
     * [BackupOrchestrator] inside the worker.
     *
     * Returns true if the job was enqueued, false if another run is
     * already active (the registry refused it).
     */
    fun runNow(): Boolean {
        return scheduler.runNow(manual = true) != null
    }

    /**
     * Cancel the active backup (if any). Soft-cancel by default — the
     * orchestrator exits at the next chunk boundary.
     */
    fun cancelActive() {
        scheduler.cancelActive(force = false)
    }

    /**
     * Dismiss the terminal phase card from the UI. Does NOT stop any
     * running job — only clears the displayed result.
     */
    fun dismissTerminal() {
        registry.finish()
    }

    /**
     * Called on app start to recover from a previous-process crash:
     * any sync_log rows left in RUNNING are marked CANCELLED.
     */
    fun recoverStaleRunningLogs() {
        viewModelScope.launch(dispatchers.io) {
            try {
                val n = syncLogDao.markRunningAsCancelled(System.currentTimeMillis())
                if (n > 0) Log.i("AutoBackup: recovered $n stale RUNNING sync_log row(s)")
            } catch (t: Throwable) { Log.w("AutoBackup: recoverStaleRunningLogs failed", t) }
        }
    }
}
