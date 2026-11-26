package com.tgdrive.feature.autobackup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.AppPreferencesData
import com.tgdrive.core.datastore.BackupMediaFilter
import com.tgdrive.core.datastore.BackupQuality
import com.tgdrive.core.datastore.BackupSchedule
import com.tgdrive.core.datastore.BackupUploadOrder
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncLogDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.entity.FolderEntity
import com.tgdrive.core.db.entity.SyncLogEntity
import com.tgdrive.core.db.entity.SyncedFileEntity
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.PassVaultCloudVerifier
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.feature.autobackup.diff.DiffEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderBackupSummary(
    val syncedCount: Int = 0,
    val syncedBytes: Long = 0L,
    val lastSyncedMs: Long = 0L,
    val pendingCount: Int? = null,
    val pendingBytes: Long? = null,
    val permissionLost: Boolean = false,
)

data class AutoBackupUiState(
    val prefs: AppPreferencesData = AppPreferencesData(),
    val folders: List<FolderEntity> = emptyList(),
    val history: List<SyncLogEntity> = emptyList(),
    val historyLoaded: Boolean = false,
    val folderStats: Map<String, FolderBackupSummary> = emptyMap(),
    val scanningPending: Boolean = false,
    val hasVaultPassphrase: Boolean = false,
)

@HiltViewModel
class AutoBackupViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val folderDao: FolderDao,
    private val syncLogDao: SyncLogDao,
    private val syncedFileDao: SyncedFileDao,
    private val folderScanner: FolderScanner,
    private val diffEngine: DiffEngine,
    private val scheduler: BackupScheduler,
    private val registry: ActiveBackupRegistry,
    private val backupCrypto: BackupCrypto,
    private val mediaThumbnailCache: MediaThumbnailCache,
    private val passVaultCloudVerifier: PassVaultCloudVerifier,
    private val dispatchers: DispatchersProvider,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    private data class PendingPreview(
        val pendingCount: Int,
        val pendingBytes: Long,
        val permissionLost: Boolean = false,
    )

    private val _pendingPreviews = MutableStateFlow<Map<String, PendingPreview>>(emptyMap())
    private val _scanningPending = MutableStateFlow(false)
    private val _hasVaultPassphrase = MutableStateFlow(backupCrypto.hasVaultPassphrase())

    val state: StateFlow<AutoBackupUiState> = combine(
        preferences.data,
        folderDao.observeAll(),
        syncLogDao.observeRecent(),
        syncedFileDao.observeAll(),
        combine(_pendingPreviews, _scanningPending, _hasVaultPassphrase) { previews, scanning, hasPass ->
            Triple(previews, scanning, hasPass)
        },
    ) { prefs, folders, history, syncedFiles, (previews, scanning, hasPass) ->
        val stats = buildFolderSummaries(prefs.backupFolderUris, syncedFiles, previews)
        AutoBackupUiState(
            prefs = prefs,
            folders = folders,
            history = history,
            historyLoaded = true,
            folderStats = stats,
            scanningPending = scanning,
            hasVaultPassphrase = hasPass,
        )
    }.catch { t -> Log.w("AutoBackupViewModel: state flow error", t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AutoBackupUiState())

    /** Live progress of the active backup job (or null if idle). Survives navigation. */
    val activeJob: StateFlow<BackupJobState?> = registry.active

    private fun buildFolderSummaries(
        folderUris: Set<String>,
        syncedFiles: List<SyncedFileEntity>,
        previews: Map<String, PendingPreview>,
    ): Map<String, FolderBackupSummary> {
        if (folderUris.isEmpty()) return emptyMap()
        val treeTokens = folderUris.associateWith { uriStr ->
            val encodedTreeId = runCatching {
                Uri.encode(DocumentsContract.getTreeDocumentId(Uri.parse(uriStr)))
            }.getOrNull()
            uriStr to encodedTreeId
        }
        val counts = HashMap<String, Int>(folderUris.size)
        val bytes = HashMap<String, Long>(folderUris.size)
        val latest = HashMap<String, Long>(folderUris.size)

        for (entry in syncedFiles) {
            val matchedUri = treeTokens.entries.firstOrNull { (treeUri, tokenPair) ->
                val encodedId = tokenPair.second
                entry.localPath.startsWith(treeUri) ||
                    (encodedId != null && entry.localPath.contains("/tree/$encodedId/"))
            }?.key ?: continue
            counts[matchedUri] = (counts[matchedUri] ?: 0) + 1
            bytes[matchedUri] = (bytes[matchedUri] ?: 0L) + entry.size.coerceAtLeast(0L)
            val prev = latest[matchedUri] ?: 0L
            if (entry.syncedAt > prev) latest[matchedUri] = entry.syncedAt
        }

        return folderUris.associateWith { uri ->
            val preview = previews[uri]
            FolderBackupSummary(
                syncedCount = counts[uri] ?: 0,
                syncedBytes = bytes[uri] ?: 0L,
                lastSyncedMs = latest[uri] ?: 0L,
                pendingCount = preview?.pendingCount,
                pendingBytes = preview?.pendingBytes,
                permissionLost = preview?.permissionLost ?: false,
            )
        }
    }

    fun scanPendingPreview() {
        if (_scanningPending.value) return
        viewModelScope.launch(dispatchers.io) {
            _scanningPending.value = true
            try {
                val prefs = preferences.data.first()
                val excludePatterns = ExcludePatternMatcher.parse(prefs.backupExcludePatterns)
                val updated = LinkedHashMap<String, PendingPreview>()
                for (uri in prefs.backupFolderUris) {
                    val scanRes = folderScanner.scanDetailed(uri, excludePatterns)
                    if (scanRes.permissionLost) {
                        updated[uri] = PendingPreview(0, 0L, permissionLost = true)
                        continue
                    }
                    val filtered = scanRes.files.filter { file ->
                        if (prefs.backupOnlyNewAfterMs > 0L && file.lastModified > 0L && file.lastModified < prefs.backupOnlyNewAfterMs) {
                            return@filter false
                        }
                        if (prefs.backupMaxFileSizeMb > 0 && file.size > prefs.backupMaxFileSizeMb.toLong() * 1024L * 1024L) {
                            return@filter false
                        }
                        val bucket = classifyFile(file.mimeType, file.name)
                        when (prefs.backupMediaFilter) {
                            BackupMediaFilter.ALL_FILES -> true
                            BackupMediaFilter.PHOTOS_AND_VIDEOS -> bucket == FileBucket.IMAGES || bucket == FileBucket.VIDEOS
                            BackupMediaFilter.PHOTOS_ONLY -> bucket == FileBucket.IMAGES
                        }
                    }
                    val targetId = prefs.targetFolderFor(uri).takeIf { it > 0L } ?: -1L
                    val already = syncedFileDao.forFolder(targetId)
                    val diff = diffEngine.compute(filtered, already, strict = false)
                    updated[uri] = PendingPreview(
                        pendingCount = diff.toUpload.size,
                        pendingBytes = diff.toUpload.sumOf { it.size.coerceAtLeast(0L) },
                        permissionLost = false,
                    )
                }
                _pendingPreviews.value = updated
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("AutoBackup: scanPendingPreview failed", t)
            } finally {
                _scanningPending.value = false
            }
        }
    }

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
            try {
                preferences.update { it.copy(backupFolderUris = it.backupFolderUris + uri) }
                scheduler.rescheduleIfEnabled()
            } catch (t: Throwable) { Log.w("AutoBackup: addFolderUri failed", t) }
        }
    }

    fun removeFolderUri(uri: String) {
        viewModelScope.launch(dispatchers.io) {
            try {
                preferences.update {
                    it.withFolderTarget(uri, null).copy(
                        backupFolderUris = it.backupFolderUris - uri,
                        backupEncryptedFolderUris = it.backupEncryptedFolderUris - uri,
                    )
                }
                _pendingPreviews.update { it - uri }
                scheduler.rescheduleIfEnabled()
            } catch (t: Throwable) { Log.w("AutoBackup: removeFolderUri failed", t) }
        }
    }

    fun toggleFolderEncrypted(uri: String) {
        viewModelScope.launch(dispatchers.io) {
            try {
                preferences.update { cur ->
                    val next = if (uri in cur.backupEncryptedFolderUris) {
                        cur.backupEncryptedFolderUris - uri
                    } else {
                        cur.backupEncryptedFolderUris + uri
                    }
                    cur.copy(backupEncryptedFolderUris = next)
                }
            } catch (t: Throwable) { Log.w("AutoBackup: toggleFolderEncrypted failed", t) }
        }
    }

    fun setFolderTarget(folderUri: String, targetFolderId: Long?) {
        viewModelScope.launch(dispatchers.io) {
            try {
                preferences.update { it.withFolderTarget(folderUri, targetFolderId) }
            } catch (t: Throwable) { Log.w("AutoBackup: setFolderTarget failed", t) }
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

    fun setScheduleTime(hour: Int, minute: Int) {
        viewModelScope.launch(dispatchers.io) {
            try {
                preferences.update {
                    it.copy(
                        backupScheduleHour = hour.coerceIn(0, 23),
                        backupScheduleMinute = minute.coerceIn(0, 59),
                    )
                }
                scheduler.rescheduleIfEnabled()
            } catch (t: Throwable) { Log.w("AutoBackup: setScheduleTime failed", t) }
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

    fun setCellularDailyLimitMb(limitMb: Int) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupCellularDailyLimitMb = limitMb.coerceAtLeast(0)) } }
            catch (t: Throwable) { Log.w("AutoBackup: setCellularDailyLimitMb failed", t) }
        }
    }

    fun setSkipVideosOnCellular(v: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupSkipVideosOnCellular = v) } }
            catch (t: Throwable) { Log.w("AutoBackup: setSkipVideosOnCellular failed", t) }
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

    fun setMinBatteryPercent(percent: Int) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupMinBatteryPercent = percent.coerceIn(5, 80)) } }
            catch (t: Throwable) { Log.w("AutoBackup: setMinBatteryPercent failed", t) }
        }
    }

    fun setUploadOrder(order: BackupUploadOrder) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupUploadOrder = order) } }
            catch (t: Throwable) { Log.w("AutoBackup: setUploadOrder failed", t) }
        }
    }

    fun setMediaFilter(filter: BackupMediaFilter) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupMediaFilter = filter) } }
            catch (t: Throwable) { Log.w("AutoBackup: setMediaFilter failed", t) }
        }
    }

    fun setQuality(quality: BackupQuality) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupQuality = quality) } }
            catch (t: Throwable) { Log.w("AutoBackup: setQuality failed", t) }
        }
    }

    fun setOnlyNewAfterNow(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            try {
                preferences.update {
                    it.copy(backupOnlyNewAfterMs = if (enabled) System.currentTimeMillis() else 0L)
                }
            } catch (t: Throwable) { Log.w("AutoBackup: setOnlyNewAfterNow failed", t) }
        }
    }

    fun setMaxFileSizeMb(maxMb: Int) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupMaxFileSizeMb = maxMb.coerceAtLeast(0)) } }
            catch (t: Throwable) { Log.w("AutoBackup: setMaxFileSizeMb failed", t) }
        }
    }

    fun setAutoOrganizeByDate(v: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupAutoOrganizeByDate = v) } }
            catch (t: Throwable) { Log.w("AutoBackup: setAutoOrganizeByDate failed", t) }
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

    fun setZipIncremental(v: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupZipIncremental = v) } }
            catch (t: Throwable) { Log.w("AutoBackup: setZipIncremental failed", t) }
        }
    }

    fun setExcludePatterns(v: String) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupExcludePatterns = v) } }
            catch (t: Throwable) { Log.w("AutoBackup: setExcludePatterns failed", t) }
        }
    }

    fun setEncryptAll(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            try { preferences.update { it.copy(backupEncryptAll = enabled) } }
            catch (t: Throwable) { Log.w("AutoBackup: setEncryptAll failed", t) }
        }
    }

    fun setVaultPassphrase(passphrase: String?) {
        viewModelScope.launch(dispatchers.io) {
            try {
                backupCrypto.setVaultPassphrase(passphrase)
                mediaThumbnailCache.clearDecryptedThumbMemoryCache()
                val configured = backupCrypto.hasVaultPassphrase()
                _hasVaultPassphrase.value = configured
                val clean = passphrase?.trim()
                if (configured && !clean.isNullOrBlank()) {
                    runCatching { passVaultCloudVerifier.publishVerifier(clean) }
                }
            } catch (t: Throwable) {
                Log.w("AutoBackup: setVaultPassphrase failed", t)
            }
        }
    }

    fun runNow(): Boolean {
        return scheduler.runNow(manual = true) != null
    }

    fun retryFailed(paths: List<String>, sourceJobId: String? = null): Boolean {
        return scheduler.retryFailed(paths, sourceJobId = sourceJobId) != null
    }

    fun cancelActive() {
        scheduler.cancelActive(force = false)
    }

    fun dismissTerminal() {
        registry.finish()
    }

    fun recoverStaleRunningLogs() {
        viewModelScope.launch(dispatchers.io) {
            try {
                val n = syncLogDao.markRunningAsCancelled(System.currentTimeMillis())
                if (n > 0) Log.i("AutoBackup: recovered $n stale RUNNING sync_log row(s)")
            } catch (t: Throwable) { Log.w("AutoBackup: recoverStaleRunningLogs failed", t) }
        }
    }
}
