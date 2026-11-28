package com.tgdrive.feature.autobackup

import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.AppPreferencesData
import com.tgdrive.core.datastore.BackupMediaFilter
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
import com.tgdrive.core.telegram.TelegramPathTag
import com.tgdrive.core.zip.UploadedZipPartRecord
import com.tgdrive.core.zip.ZipCompressionMode
import com.tgdrive.core.zip.ZipUploader
import com.tgdrive.feature.autobackup.diff.DiffEngine
import com.tgdrive.feature.autobackup.diff.LocalFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Single source of truth for the AutoBackup pipeline:
 * scan (fast DocumentsContract cursor + permission guard) -> smart filters ->
 * diff (mtime + SHA-256 strict + reinstall cloud restore) -> resolve target ->
 * ordered & pipelined staging/compression/encryption -> adaptive rate-limited upload ->
 * finalize with per-file failure records & byte totals.
 */
@Singleton
class BackupOrchestrator @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val telegramClient: TelegramClient,
    private val preferences: AppPreferences,
    private val folderScanner: FolderScanner,
    private val stagingManager: StagingManager,
    private val diffEngine: DiffEngine,
    private val syncedFileDao: SyncedFileDao,
    private val syncLogDao: SyncLogDao,
    private val folderDao: FolderDao,
    private val fileCacheDao: FileCacheDao,
    private val notifier: BackupNotifier,
    private val registry: ActiveBackupRegistry,
    private val zipIndexService: ZipIndexService,
    private val cloudSyncManager: CloudSyncManager,
    private val mediaThumbnailCache: MediaThumbnailCache,
    private val backupCrypto: BackupCrypto,
    private val dispatchers: DispatchersProvider,
) {

    suspend fun runBackup(
        jobId: String,
        isManual: Boolean,
        onlyLocalPaths: Set<String>? = null,
        overrideFolderUris: List<String>? = null,
        overrideTargetFolderId: Long? = null,
        setForeground: suspend (jobId: String, count: Int, total: Int, subText: String?) -> Unit = { _, _, _, _ -> },
    ): RunOutcome {
        val startedAt = System.currentTimeMillis()

        // --- 1. Mutual exclusion
        if (!registry.tryBegin(jobId, startedAt)) {
            Log.i("BackupOrchestrator: refusing to start $jobId — another run is active")
            return RunOutcome.Skipped("Another backup is already running")
        }

        // --- 2. Persist sync_log row in RUNNING state
        try {
            syncLogDao.upsert(
                SyncLogEntity(
                    jobId = jobId,
                    startedAt = startedAt,
                    completedAt = null,
                    status = "RUNNING",
                ),
            )
        } catch (t: Throwable) {
            Log.w("BackupOrchestrator: failed to create sync log", t)
        }

        // --- 2.5. Cancellation & Pause watcher on injected IO dispatcher
        val latestPrefsRef = AtomicReference<AppPreferencesData?>(null)
        val pauseOrDisableRequested = AtomicBoolean(false)
        val parentJob = coroutineContext[Job]
        val watcherScopeJob = parentJob?.let { SupervisorJob(it) }
        val watcherJob = if (parentJob != null && watcherScopeJob != null) {
            val pj = parentJob
            CoroutineScope(watcherScopeJob + dispatchers.io).launch {
                try {
                    val cancelWatcher = launch {
                        registry.cancelRequested.collect { requestedId ->
                            if (requestedId == jobId) {
                                Log.i("BackupOrchestrator: watcher detected cancellation — cancelling parent job")
                                pj.cancel()
                            }
                        }
                    }
                    val pauseWatcher = launch {
                        var sawUnpaused = false
                        preferences.data.collect { p ->
                            latestPrefsRef.set(p)
                            val shouldCancel = if (isManual) {
                                if (!p.backupPaused) sawUnpaused = true
                                sawUnpaused && p.backupPaused
                            } else {
                                !p.backupEnabled || p.backupPaused
                            }
                            if (shouldCancel) {
                                pauseOrDisableRequested.set(true)
                                Log.i("BackupOrchestrator: watcher detected pause/disable — cancelling parent job")
                                pj.cancel()
                            }
                        }
                    }
                    cancelWatcher.join()
                    pauseWatcher.cancel()
                } catch (_: CancellationException) {
                }
            }
        } else {
            null
        }

        // --- 3. Purge leftover staging files before starting a new backup run
        stagingManager.purgeStale(maxAgeMs = 0L)

        var filesScanned = 0
        var filesToUpload = 0
        var filesUploaded = 0
        var filesSkipped = 0
        var bytesUploadedTotal = 0L
        var anyMetadataChanged = false
        val failures = Collections.synchronizedList(ArrayList<BackupFailureRecord>())

        try {
            var prefs = preferences.data.first()
            latestPrefsRef.compareAndSet(null, prefs)
            if (!prefs.backupEnabled && !isManual && overrideFolderUris == null) {
                Log.i("BackupOrchestrator: disabled; skipping")
                finalize(jobId, startedAt, 0, 0, 0, "SUCCESS", null)
                registry.updatePhase(BackupPhase.Done(0, 0, 0, 0))
                return RunOutcome.Success(uploaded = 0, skipped = 0, total = 0)
            }
            if (prefs.backupPaused && !isManual) {
                Log.i("BackupOrchestrator: paused; skipping")
                finalize(jobId, startedAt, 0, 0, 0, "SUCCESS", null)
                registry.updatePhase(BackupPhase.Done(0, 0, 0, 0))
                return RunOutcome.Skipped("Paused")
            }

            val folderUris = overrideFolderUris?.takeIf { it.isNotEmpty() } ?: prefs.backupFolderUris.toList()
            if (folderUris.isEmpty()) {
                val msg = "No folders selected"
                finalize(jobId, startedAt, 0, 0, 0, "FAILURE", msg)
                registry.updatePhase(BackupPhase.Failed(msg, 0, 0))
                return RunOutcome.Failed(msg, 0, 0, retryable = false)
            }

            // Battery & power-saver guard for automatic background runs
            if (!isManual) {
                val skipReason = checkBatterySkipReason(prefs.backupMinBatteryPercent)
                if (skipReason != null) {
                    Log.i("BackupOrchestrator: $skipReason")
                    finalize(jobId, startedAt, 0, 0, 0, "SUCCESS", skipReason)
                    registry.updatePhase(BackupPhase.Done(0, 0, 0, 0))
                    return RunOutcome.Skipped(skipReason)
                }
            }

            // Network & daily cellular quota guard
            val isMetered = isNetworkMetered()
            if (!isManual && isMetered && prefs.backupWifiOnly) {
                val msg = "Waiting for Wi-Fi"
                finalize(jobId, startedAt, 0, 0, 0, "SUCCESS", msg)
                registry.updatePhase(BackupPhase.Done(0, 0, 0, 0))
                return RunOutcome.Skipped(msg)
            }

            val todayKey = runCatching { LocalDate.now().toString() }.getOrDefault("")
            var usedCellularToday = if (prefs.backupCellularUsageDay == todayKey) {
                prefs.backupCellularUsedBytes
            } else {
                0L
            }
            val cellularLimitBytes = if (isMetered && prefs.backupCellularDailyLimitMb > 0) {
                prefs.backupCellularDailyLimitMb.toLong() * 1024L * 1024L
            } else {
                Long.MAX_VALUE
            }
            if (isMetered && prefs.backupCellularDailyLimitMb > 0 && usedCellularToday >= cellularLimitBytes) {
                val msg = "Daily mobile data limit reached (${prefs.backupCellularDailyLimitMb} MB)"
                finalize(jobId, startedAt, 0, 0, 0, "SUCCESS", msg)
                registry.updatePhase(BackupPhase.Done(0, 0, 0, 0))
                return RunOutcome.Skipped(msg)
            }

            // First-run / reinstall cloud metadata restore before diffing
            if (!prefs.backupSyncIndexRestored) {
                val existingCount = runCatching { syncedFileDao.grandTotal().fileCount }.getOrDefault(0)
                if (existingCount == 0) {
                    runCatching { cloudSyncManager.syncFromCloud() }
                }
                runCatching { preferences.update { it.copy(backupSyncIndexRestored = true) } }
                prefs = preferences.data.first()
            }

            // Promote to foreground service immediately
            try {
                setForeground(jobId, 0, 0, null)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("BackupOrchestrator: setForeground unavailable (${t.javaClass.simpleName}) — continuing", t)
            }

            // --- 4. Fast scan + permission guard
            registry.updatePhase(BackupPhase.Scanning(0, folderUris.size, 0))
            val allLocal = mutableListOf<LocalFile>()
            val scannedByUri = LinkedHashMap<String, List<LocalFile>>()
            val sourceUriByPath = HashMap<String, String>()
            val folderNameByUri = HashMap<String, String>()
            val scanProblemUris = HashSet<String>()
            var permissionLostCount = 0
            var anyHitLimit = false
            val excludePatterns = ExcludePatternMatcher.parse(prefs.backupExcludePatterns)
            var lastProgressUpdateMs = 0L

            for ((i, uri) in folderUris.withIndex()) {
                ensureNotCancelled(jobId)
                val folderIndex = i
                try {
                    val scanResult = folderScanner.scanDetailed(uri, excludePatterns) { filesFound ->
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdateMs >= 200L) {
                            lastProgressUpdateMs = now
                            registry.updatePhase(
                                BackupPhase.Scanning(
                                    foldersDone = folderIndex,
                                    foldersTotal = folderUris.size,
                                    filesFound = filesFound,
                                ),
                            )
                        }
                    }
                    if (scanResult.hitLimit) anyHitLimit = true
                    folderNameByUri[uri] = scanResult.folderName.ifBlank { fallbackFolderName(uri) }
                    if (scanResult.permissionLost) {
                        permissionLostCount++
                        scanProblemUris += uri
                        failures += BackupFailureRecord(
                            fileName = folderNameByUri[uri] ?: uri,
                            localPath = uri,
                            reason = "Folder permission lost — re-grant access",
                            folderUri = uri,
                        )
                    } else if (scanResult.scanError != null) {
                        scanProblemUris += uri
                        failures += BackupFailureRecord(
                            fileName = folderNameByUri[uri] ?: uri,
                            localPath = uri,
                            reason = scanResult.scanError,
                            folderUri = uri,
                        )
                    }

                    val filtered = ArrayList<LocalFile>(scanResult.files.size)
                    for (file in scanResult.files) {
                        if (excludePatterns.isNotEmpty() &&
                            ExcludePatternMatcher.matchesPath(file.relativePath, file.name, excludePatterns)
                        ) {
                            filesSkipped++
                            continue
                        }
                        if (onlyLocalPaths != null && file.absolutePath !in onlyLocalPaths) {
                            continue
                        }
                        if (!matchesBackupFilters(file, prefs, isMetered, isManual)) {
                            filesSkipped++
                            continue
                        }
                        filtered += file
                        sourceUriByPath[file.absolutePath] = uri
                    }
                    scannedByUri[uri] = filtered
                    allLocal += filtered
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    scanProblemUris += uri
                    Log.w("BackupOrchestrator: scan failed for $uri", t)
                }
                registry.updatePhase(
                    BackupPhase.Scanning(
                        foldersDone = i + 1,
                        foldersTotal = folderUris.size,
                        filesFound = allLocal.size,
                    ),
                )
            }
            filesScanned = allLocal.size
            Log.i("BackupOrchestrator: scanned $filesScanned files across ${folderUris.size} folder(s)")

            if (permissionLostCount == folderUris.size && allLocal.isEmpty()) {
                val msg = "Folder permission lost for all watched folders"
                finalize(
                    jobId, startedAt, 0, 0, filesSkipped, "FAILURE", msg,
                    failedCount = failures.size,
                    failedDetailsJson = BackupFailureRecord.encode(failures),
                )
                registry.updatePhase(BackupPhase.Failed(msg, 0, 0))
                notifier.showResult(jobId, 0, filesSkipped, partial = false, failed = true, failedCount = failures.size)
                return RunOutcome.Failed(msg, 0, 0, retryable = false)
            }

            ensureNotCancelled(jobId)

            // --- 5. Resolve per-folder Telegram targets BEFORE diffing
            val resolvedChatCache = mutableMapOf<Long, Long>()
            suspend fun resolveTargetCached(folderPrefId: Long): Long =
                resolvedChatCache.getOrPut(folderPrefId) { resolveTargetChatId(folderPrefId) }

            val defaultTargetPrefId = overrideTargetFolderId ?: prefs.backupTargetFolderId
            val defaultTargetChatId = try {
                resolveTargetCached(defaultTargetPrefId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val msg = "Cannot connect to Telegram: ${t.message}"
                Log.w("BackupOrchestrator: resolveTargetChatId failed", t)
                finalize(
                    jobId, startedAt, filesScanned, 0, filesSkipped, "FAILURE", msg,
                    failedCount = failures.size,
                    failedDetailsJson = BackupFailureRecord.encode(failures),
                )
                registry.updatePhase(BackupPhase.Failed(msg, 0, 0))
                return RunOutcome.Failed(msg, 0, 0, retryable = true)
            }

            // --- 6. Diff (per target folder destination)
            registry.updatePhase(BackupPhase.Diffing(filesFound = filesScanned))

            val targetByPath = mutableMapOf<String, Pair<Long, Long?>>()
            val toUploadByUri = LinkedHashMap<String, List<LocalFile>>()
            val toUpload = mutableListOf<LocalFile>()
            var totalSkippedCount = filesSkipped

            val urisByTargetPref = folderUris.groupBy { uri ->
                overrideTargetFolderId ?: prefs.targetFolderFor(uri)
            }
            try {
                for ((targetPrefId, urisForTarget) in urisByTargetPref) {
                    val destChatId = resolveTargetCached(targetPrefId)
                    val queryFolderId = targetPrefId.takeIf { it > 0 } ?: -1L
                    val already = if (onlyLocalPaths != null) {
                        emptyList()
                    } else {
                        syncedFileDao.forFolder(queryFolderId)
                    }
                    val localForGroup = urisForTarget.flatMap { scannedByUri[it].orEmpty() }
                    val groupDiff = diffEngine.compute(
                        local = localForGroup,
                        already = already,
                        strict = prefs.backupStrictMode,
                    )
                    totalSkippedCount += groupDiff.toSkip.size
                    val uploadSet = groupDiff.toUpload.mapTo(HashSet()) { it.absolutePath }
                    for (uri in urisForTarget) {
                        toUploadByUri[uri] = scannedByUri[uri].orEmpty().filter { it.absolutePath in uploadSet }
                    }
                    val folderDbId = targetPrefId.takeIf { it > 0 }
                    for (item in groupDiff.toUpload) {
                        targetByPath[item.absolutePath] = destChatId to folderDbId
                        toUpload += item
                    }
                    for (moved in groupDiff.movedFiles) {
                        val movedFile = moved.localFile
                        val prev = moved.previousRecord
                        val movedSrcUri = sourceUriByPath[movedFile.absolutePath].orEmpty()
                        val movedRootTitle = folderNameByUri[movedSrcUri].orEmpty()
                        val movedCaptureMs = movedFile.lastModified.takeIf { it > 0L } ?: System.currentTimeMillis()
                        val newVirtualPath = buildVirtualPath(
                            rootFolderTitle = movedRootTitle,
                            relativeDir = movedFile.relativePath,
                            organizeByDate = prefs.backupAutoOrganizeByDate,
                            captureTimestampMs = movedCaptureMs,
                        )
                        runCatching { syncedFileDao.deleteByPathAndFolder(prev.localPath, queryFolderId) }
                        runCatching {
                            syncedFileDao.upsert(
                                SyncedFileEntity(
                                    localPath = movedFile.absolutePath,
                                    telegramMessageId = prev.telegramMessageId,
                                    sha256 = movedFile.sha256 ?: prev.sha256,
                                    size = movedFile.size,
                                    syncedAt = System.currentTimeMillis(),
                                    folderId = folderDbId,
                                    lastModified = movedFile.lastModified,
                                ),
                            )
                        }
                        runCatching { fileCacheDao.updateVirtualPath(destChatId, prev.telegramMessageId, newVirtualPath) }
                        if (newVirtualPath.isNotBlank()) {
                            cloudSyncManager.noteVirtualPathChanged(
                                chatId = destChatId,
                                messageId = prev.telegramMessageId,
                                virtualPath = newVirtualPath,
                                schedulePush = false,
                            )
                        }
                        anyMetadataChanged = true
                    }
                    val anyTemporaryFilterActive = prefs.backupMediaFilter != BackupMediaFilter.ALL_FILES ||
                        prefs.backupMaxFileSizeMb > 0 ||
                        prefs.backupOnlyNewAfterMs > 0L ||
                        (isMetered && prefs.backupSkipVideosOnCellular && !isManual)
                    val canPruneStale = onlyLocalPaths == null &&
                        overrideFolderUris == null &&
                        !anyHitLimit &&
                        !anyTemporaryFilterActive &&
                        urisForTarget.none { it in scanProblemUris }
                    if (canPruneStale) {
                        for (stalePath in groupDiff.staleRemotePaths) {
                            runCatching { syncedFileDao.deleteByPathAndFolder(stalePath, queryFolderId) }
                        }
                    }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e("BackupOrchestrator: diff failed", t)
                val msg = "Diff failed: ${t.message ?: t.javaClass.simpleName}"
                finalize(
                    jobId, startedAt, filesScanned, 0, totalSkippedCount, "FAILURE", msg,
                    failedCount = failures.size,
                    failedDetailsJson = BackupFailureRecord.encode(failures),
                )
                registry.updatePhase(BackupPhase.Failed(msg, 0, 0))
                return RunOutcome.Failed(msg, 0, 0, retryable = true)
            }

            filesSkipped = totalSkippedCount

            // Filter by upload order + check Telegram max upload size before staging
            val maxTelegramBytes = runCatching { telegramClient.maxUploadSizeBytes() }
                .getOrDefault(TelegramClient.MAX_FILE_SIZE_STANDARD)
            val orderedToUpload = when (prefs.backupUploadOrder) {
                BackupUploadOrder.NEWEST_FIRST -> toUpload.sortedByDescending { it.lastModified }
                BackupUploadOrder.OLDEST_FIRST -> toUpload.sortedBy { it.lastModified }
                BackupUploadOrder.SMALLEST_FIRST -> toUpload.sortedBy { it.size }
            }
            val eligibleToUpload = ArrayList<LocalFile>(orderedToUpload.size)
            for (item in orderedToUpload) {
                if (maxTelegramBytes > 0L && item.size > maxTelegramBytes) {
                    failures += BackupFailureRecord(
                        fileName = item.name,
                        localPath = item.absolutePath,
                        reason = "Exceeds Telegram limit (${maxTelegramBytes.toHumanSize()})",
                        folderUri = sourceUriByPath[item.absolutePath].orEmpty(),
                    )
                } else {
                    eligibleToUpload += item
                }
            }
            filesToUpload = eligibleToUpload.size

            ensureNotCancelled(jobId)

            if (eligibleToUpload.isEmpty() && failures.isEmpty()) {
                Log.i("BackupOrchestrator: nothing to upload")
                finalize(
                    jobId, startedAt, filesScanned, 0, filesSkipped, "SUCCESS", null,
                    triggerCloudSync = anyMetadataChanged,
                )
                registry.updatePhase(BackupPhase.Done(0, 0, filesSkipped, 0))
                return RunOutcome.Success(uploaded = 0, skipped = filesSkipped, total = 0)
            }
            if (eligibleToUpload.isEmpty() && failures.isNotEmpty() && !prefs.backupZipFolders) {
                val msg = failures.first().reason
                finalize(
                    jobId, startedAt, filesScanned, 0, filesSkipped, "FAILURE", msg,
                    failedCount = failures.size,
                    failedDetailsJson = BackupFailureRecord.encode(failures),
                )
                notifier.showResult(jobId, 0, filesSkipped, partial = false, failed = true, failedCount = failures.size)
                registry.updatePhase(BackupPhase.Failed(msg, 0, failures.size))
                return RunOutcome.Failed(msg, 0, failures.size, retryable = false)
            }

            val needsEncryption = eligibleToUpload.any { f ->
                prefs.isFolderEncrypted(sourceUriByPath[f.absolutePath].orEmpty())
            } || (prefs.backupZipFolders && folderUris.any { prefs.isFolderEncrypted(it) })
            if (needsEncryption && !backupCrypto.hasVaultPassphrase()) {
                val msg = "PassVault passphrase is not set. Configure it in Settings → PassVault & Encryption."
                finalize(jobId, startedAt, filesScanned, 0, filesSkipped, "FAILURE", msg)
                notifier.showResult(jobId, 0, filesSkipped, partial = false, failed = true, failedCount = 0)
                registry.updatePhase(BackupPhase.Failed(msg, 0, 0))
                return RunOutcome.Failed(msg, 0, 0, retryable = false)
            }

            // --- 6.5. ZIP mode
            if (prefs.backupZipFolders && onlyLocalPaths == null) {
                Log.i("BackupOrchestrator: zip mode enabled")
                return runZipMode(
                    jobId = jobId,
                    startedAt = startedAt,
                    isManual = isManual,
                    prefs = prefs,
                    overrideTargetFolderId = overrideTargetFolderId,
                    folderUris = folderUris,
                    folderNameByUri = folderNameByUri,
                    scannedByUri = scannedByUri,
                    toUploadByUri = toUploadByUri,
                    resolveTargetCached = ::resolveTargetCached,
                    filesScanned = filesScanned,
                    filesSkipped = filesSkipped,
                    initialFailures = failures,
                    setForeground = setForeground,
                )
            }

            // --- 7. Update foreground notification with total files
            try {
                setForeground(jobId, 0, filesToUpload, null)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("BackupOrchestrator: setForeground update failed", t)
            }
            try {
                notifier.showForeground(jobId, 0, filesToUpload, null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {}

            // --- 8. Bounded Parallel & Pipelined Upload Pool (staging decoupled from network + AIMD pacing)
            val totalBytesAll = eligibleToUpload.sumOf { it.size.coerceAtLeast(0L) }
            val uploadLoopStartMs = System.currentTimeMillis()
            val adaptiveDelayMs = AtomicLong(0L)
            val cleanUploadsSinceFlood = AtomicInteger(0)
            val completedCounter = AtomicInteger(0)
            val startedCounter = AtomicInteger(0)
            val bytesUploadedCounter = AtomicLong(0L)
            val usedCellularCounter = AtomicLong(usedCellularToday)
            val cellularLimitHit = AtomicBoolean(false)
            val abortedMidLoop = AtomicBoolean(false)
            val lastNotifMs = AtomicLong(0L)
            val prefsUpdateMutex = Mutex()
            val metadataDirty = AtomicBoolean(anyMetadataChanged)
            val inFlightBytes = AtomicLongArray(eligibleToUpload.size.coerceAtLeast(1))

            val stagingSemaphore = Semaphore(2)
            val smallNetworkSemaphore = Semaphore(3)
            val smallDiskSlotSemaphore = Semaphore(4)
            val largeNetworkSemaphore = Semaphore(1)

            fun currentLivePrefs(): AppPreferencesData = latestPrefsRef.get() ?: prefs

            fun shouldStopForPause(): Boolean {
                if (pauseOrDisableRequested.get()) return true
                val live = currentLivePrefs()
                return if (isManual) live.backupPaused else (live.backupPaused || !live.backupEnabled)
            }

            fun stageOptionsFor(f: LocalFile): Pair<com.tgdrive.core.datastore.BackupQuality, Boolean> {
                val srcUri = sourceUriByPath[f.absolutePath].orEmpty()
                val cur = currentLivePrefs()
                return cur.backupQuality to cur.isFolderEncrypted(srcUri)
            }

            fun computeLiveBytesTotal(): Long {
                var sum = bytesUploadedCounter.get()
                for (idx in eligibleToUpload.indices) {
                    sum += inFlightBytes.get(idx)
                }
                return sum
            }

            coroutineScope {
                for ((i, file) in eligibleToUpload.withIndex()) {
                    ensureNotCancelled(jobId)
                    if (shouldStopForPause()) {
                        abortedMidLoop.set(true)
                        break
                    }
                    val curPrefs = currentLivePrefs()
                    if (isMetered && curPrefs.backupCellularDailyLimitMb > 0 &&
                        usedCellularCounter.get() >= cellularLimitBytes
                    ) {
                        cellularLimitHit.set(true)
                        break
                    }

                    launch(dispatchers.io) {
                        if (shouldStopForPause()) {
                            abortedMidLoop.set(true)
                            return@launch
                        }
                        val liveAtStart = currentLivePrefs()
                        if (isMetered && liveAtStart.backupCellularDailyLimitMb > 0 &&
                            usedCellularCounter.get() >= cellularLimitBytes
                        ) {
                            cellularLimitHit.set(true)
                            return@launch
                        }

                        val sourceUri = sourceUriByPath[file.absolutePath].orEmpty()
                        var stagedPathToRelease: String? = null
                        val isLarge = file.size >= LARGE_BACKUP_FILE_BYTES
                        val diskSlotSem = if (isLarge) largeNetworkSemaphore else smallDiskSlotSemaphore
                        diskSlotSem.withPermit {
                            try {
                                val stageOutcome = stagingSemaphore.withPermit {
                                    ensureNotCancelled(jobId)
                                    val (q, enc) = stageOptionsFor(file)
                                    stagingManager.stageForUpload(
                                        file.absolutePath,
                                        file.name,
                                        file.mimeType,
                                        file.size,
                                        q,
                                        enc,
                                    )
                                }
                                if (stageOutcome is StagingManager.StageOutcome.Failed) {
                                    failures += BackupFailureRecord(
                                        file.name,
                                        file.absolutePath,
                                        stageOutcome.reason,
                                        sourceUri,
                                    )
                                    return@withPermit
                                }
                                val staged = stageOutcome as StagingManager.StageOutcome.Ready
                                stagedPathToRelease = staged.path

                                suspend fun uploadStaged() {
                                    ensureNotCancelled(jobId)
                                    if (shouldStopForPause()) {
                                        abortedMidLoop.set(true)
                                        return
                                    }
                                    val liveNow = currentLivePrefs()
                                    if (isMetered && liveNow.backupCellularDailyLimitMb > 0 &&
                                        usedCellularCounter.get() >= cellularLimitBytes
                                    ) {
                                        cellularLimitHit.set(true)
                                        return
                                    }

                                    val (fileTargetChatId, fileFolderDbId) = targetByPath[file.absolutePath]
                                        ?: (defaultTargetChatId to defaultTargetPrefId.takeIf { it > 0 })
                                    val captureMs = stagingManager.extractCaptureTimestampMs(
                                        uriStr = file.absolutePath,
                                        mimeType = file.mimeType,
                                        fallbackLastModified = file.lastModified,
                                    )
                                    val rootFolderTitle = folderNameByUri[sourceUri].orEmpty()
                                    val virtualPath = buildVirtualPath(
                                        rootFolderTitle = rootFolderTitle,
                                        relativeDir = file.relativePath,
                                        organizeByDate = liveNow.backupAutoOrganizeByDate,
                                        captureTimestampMs = captureMs,
                                    )

                                    val slotNum = startedCounter.incrementAndGet().coerceAtMost(filesToUpload)
                                    registry.updatePhase(
                                        BackupPhase.Uploading(
                                            current = slotNum,
                                            total = filesToUpload,
                                            fileName = staged.effectiveName,
                                            bytesSent = 0L,
                                            bytesTotal = staged.effectiveSize,
                                            totalBytesUploaded = computeLiveBytesTotal(),
                                            totalBytesAll = totalBytesAll,
                                            speedBytesPerSec = 0L,
                                            etaSeconds = -1L,
                                            currentLocalUri = file.absolutePath,
                                        ),
                                    )

                                    if (!staged.wasEncrypted && virtualPath.isNotBlank()) {
                                        val capTag = TelegramPathTag.formatCaption(virtualPath)
                                        if (capTag.isNotEmpty()) {
                                            telegramClient.registerUploadMetadata(staged.path, caption = capTag)
                                        }
                                    }

                                    var lastUploadProgressMs = 0L
                                    val uploadResult = uploadWithFloodWaitRetry(
                                        chatId = fileTargetChatId,
                                        localPath = staged.path,
                                        fileName = staged.effectiveName,
                                        mimeType = staged.effectiveMime,
                                        onFloodWait = {
                                            adaptiveDelayMs.set(1_000L)
                                            cleanUploadsSinceFlood.set(0)
                                        },
                                        onProgress = { sent, total ->
                                            inFlightBytes.set(i, sent.coerceIn(0L, staged.effectiveSize))
                                            val now = System.currentTimeMillis()
                                            if (sent >= total || now - lastUploadProgressMs >= 200L) {
                                                lastUploadProgressMs = now
                                                val bytesNow = computeLiveBytesTotal()
                                                val elapsedSec =
                                                    ((now - uploadLoopStartMs) / 1000.0).coerceAtLeast(0.25)
                                                val speedBps = (bytesNow / elapsedSec).toLong()
                                                val remBytes = (totalBytesAll - bytesNow).coerceAtLeast(0L)
                                                val etaSec = if (speedBps > 0L) remBytes / speedBps else -1L
                                                val curDone = completedCounter.get().coerceAtLeast(slotNum)
                                                registry.updatePhase(
                                                    BackupPhase.Uploading(
                                                        current = curDone.coerceAtMost(filesToUpload),
                                                        total = filesToUpload,
                                                        fileName = staged.effectiveName,
                                                        bytesSent = sent,
                                                        bytesTotal = total.coerceAtLeast(staged.effectiveSize),
                                                        totalBytesUploaded = bytesNow,
                                                        totalBytesAll = totalBytesAll,
                                                        speedBytesPerSec = speedBps,
                                                        etaSeconds = etaSec,
                                                        currentLocalUri = file.absolutePath,
                                                    ),
                                                )
                                                val prevNotif = lastNotifMs.get()
                                                if (now - prevNotif >= 1500L &&
                                                    lastNotifMs.compareAndSet(prevNotif, now)
                                                ) {
                                                    val sub = buildNotifSubtext(
                                                        staged.effectiveName,
                                                        speedBps,
                                                        etaSec,
                                                    )
                                                    notifier.showForeground(
                                                        jobId,
                                                        curDone.coerceAtMost(filesToUpload),
                                                        filesToUpload,
                                                        sub,
                                                    )
                                                }
                                            }
                                        },
                                    )

                                    inFlightBytes.set(i, 0L)
                                    if (cleanUploadsSinceFlood.incrementAndGet() >= 3) {
                                        cleanUploadsSinceFlood.set(0)
                                        val prevDelay = adaptiveDelayMs.get()
                                        if (prevDelay > 0L) {
                                            adaptiveDelayMs.set(if (prevDelay <= 150L) 0L else prevDelay / 2L)
                                        }
                                    }

                                    val msgId = uploadResult.messageId
                                    val generatedThumb = if (staged.wasEncrypted) {
                                        try {
                                            mediaThumbnailCache.generateAndSyncEncryptedThumb(
                                                chatId = fileTargetChatId,
                                                messageId = msgId,
                                                localPath = file.absolutePath,
                                                mimeType = file.mimeType,
                                                originalFileName = file.name,
                                            )
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    } else {
                                        val promoted = uploadResult.generatedThumbPath?.let { prebuilt ->
                                            runCatching {
                                                mediaThumbnailCache.promotePrebuiltThumbnail(
                                                    chatId = fileTargetChatId,
                                                    messageId = msgId,
                                                    sourcePath = prebuilt,
                                                )
                                            }.getOrNull()
                                        }
                                        promoted ?: try {
                                            mediaThumbnailCache.generateFromLocalFile(
                                                chatId = fileTargetChatId,
                                                messageId = msgId,
                                                localPath = staged.path,
                                                mimeType = staged.effectiveMime,
                                                fileName = staged.effectiveName,
                                            )
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    }

                                    stagingManager.release(staged.path)
                                    stagedPathToRelease = null

                                    syncedFileDao.upsert(
                                        SyncedFileEntity(
                                            localPath = file.absolutePath,
                                            telegramMessageId = msgId,
                                            sha256 = file.sha256,
                                            size = file.size,
                                            syncedAt = System.currentTimeMillis(),
                                            folderId = fileFolderDbId,
                                            lastModified = file.lastModified,
                                        ),
                                    )

                                    fileCacheDao.upsertAll(
                                        listOf(
                                            FileCacheEntity(
                                                messageId = msgId,
                                                chatId = fileTargetChatId,
                                                fileName = staged.effectiveName,
                                                fileSize = staged.effectiveSize,
                                                mimeType = staged.effectiveMime,
                                                thumbnailPath = generatedThumb,
                                                remoteId = uploadResult.remoteId,
                                                date = captureMs,
                                                isStarred = false,
                                                lastAccessed = 0L,
                                                virtualPath = virtualPath,
                                                isEncryptedVault = staged.wasEncrypted,
                                            ),
                                        ),
                                    )

                                    if (virtualPath.isNotBlank()) {
                                        cloudSyncManager.noteVirtualPathChanged(
                                            chatId = fileTargetChatId,
                                            messageId = msgId,
                                            virtualPath = virtualPath,
                                            schedulePush = false,
                                        )
                                        metadataDirty.set(true)
                                    }

                                    if (liveNow.backupDeleteAfterUpload) {
                                        runCatching {
                                            val uri = android.net.Uri.parse(file.absolutePath)
                                            val deleted = DocumentsContract.deleteDocument(ctx.contentResolver, uri)
                                            if (!deleted) {
                                                Log.w(
                                                    "BackupOrchestrator: SAF provider refused delete " +
                                                        "'${file.name}'",
                                                )
                                            }
                                        }
                                    }

                                    val doneCount = completedCounter.incrementAndGet()
                                    val totalBytesDone = bytesUploadedCounter.addAndGet(staged.effectiveSize)

                                    if (isMetered) {
                                        val newCellular = usedCellularCounter.addAndGet(staged.effectiveSize)
                                        prefsUpdateMutex.withLock {
                                            runCatching {
                                                preferences.update { p ->
                                                    p.copy(
                                                        backupCellularUsedBytes =
                                                            maxOf(p.backupCellularUsedBytes, newCellular),
                                                        backupCellularUsageDay = todayKey,
                                                    )
                                                }
                                            }
                                        }
                                        val dailyLimitMb = liveNow.backupCellularDailyLimitMb
                                        if (dailyLimitMb > 0 && newCellular >= cellularLimitBytes) {
                                            cellularLimitHit.set(true)
                                        }
                                    }

                                    val elapsedTotalSec =
                                        ((System.currentTimeMillis() - uploadLoopStartMs) / 1000.0).coerceAtLeast(0.25)
                                    val overallSpeed = (totalBytesDone / elapsedTotalSec).toLong()
                                    val remBytes = (totalBytesAll - totalBytesDone).coerceAtLeast(0L)
                                    val etaSec = if (overallSpeed > 0L) remBytes / overallSpeed else -1L
                                    val sub = buildNotifSubtext(staged.effectiveName, overallSpeed, etaSec)
                                    runCatching { setForeground(jobId, doneCount, filesToUpload, sub) }
                                    val nowAfter = System.currentTimeMillis()
                                    val prevAfter = lastNotifMs.get()
                                    val throttled = nowAfter - prevAfter >= 1_000L &&
                                        lastNotifMs.compareAndSet(prevAfter, nowAfter)
                                    if (doneCount == filesToUpload || throttled) {
                                        runCatching { notifier.showForeground(jobId, doneCount, filesToUpload, sub) }
                                    }

                                    val stepDelay = adaptiveDelayMs.get()
                                    if (stepDelay > 0L) {
                                        delay(stepDelay)
                                    }
                                }

                                if (isLarge) {
                                    uploadStaged()
                                } else if (staged.effectiveSize >= LARGE_BACKUP_FILE_BYTES) {
                                    largeNetworkSemaphore.withPermit { uploadStaged() }
                                } else {
                                    smallNetworkSemaphore.withPermit { uploadStaged() }
                                }
                            } catch (e: CancellationException) {
                                inFlightBytes.set(i, 0L)
                                throw e
                            } catch (t: Throwable) {
                                inFlightBytes.set(i, 0L)
                                Log.w("BackupOrchestrator: failed to upload ${file.name}", t)
                                failures += BackupFailureRecord(
                                    fileName = file.name,
                                    localPath = file.absolutePath,
                                    reason = t.message ?: t.javaClass.simpleName,
                                    folderUri = sourceUri,
                                )
                            } finally {
                                withContext(NonCancellable) {
                                    stagingManager.release(stagedPathToRelease)
                                }
                            }
                        }
                    }
                }
            }

            filesUploaded = completedCounter.get()
            bytesUploadedTotal = bytesUploadedCounter.get()
            anyMetadataChanged = metadataDirty.get()

            if (abortedMidLoop.get()) {
                finalize(
                    jobId, startedAt, filesScanned, filesUploaded, filesSkipped,
                    "CANCELLED", "Cancelled by user",
                    failedCount = failures.size,
                    failedDetailsJson = BackupFailureRecord.encode(failures),
                    bytesUploaded = bytesUploadedTotal,
                    triggerCloudSync = anyMetadataChanged,
                )
                registry.updatePhase(BackupPhase.Cancelled(filesUploaded, filesToUpload))
                notifier.showResult(
                    jobId, filesUploaded, filesSkipped,
                    partial = filesUploaded > 0, failed = false, failedCount = failures.size,
                )
                return RunOutcome.Cancelled(filesUploaded, filesToUpload)
            }

            // --- 9. Finalize
            registry.updatePhase(BackupPhase.Finalizing(filesUploaded, filesToUpload, filesSkipped))
            val failedCount = failures.size
            val status = when {
                cellularLimitHit.get() && failedCount == 0 -> "PARTIAL"
                failedCount == 0 && filesUploaded == filesToUpload -> "SUCCESS"
                filesUploaded == 0 && failedCount > 0 -> "FAILURE"
                else -> "PARTIAL"
            }
            val errMsg = when {
                cellularLimitHit.get() -> "Daily mobile data limit reached (${prefs.backupCellularDailyLimitMb} MB)"
                status == "FAILURE" -> failures.firstOrNull()?.reason ?: "All uploads failed"
                else -> null
            }
            finalize(
                jobId = jobId,
                startedAt = startedAt,
                scanned = filesScanned,
                uploaded = filesUploaded,
                skipped = filesSkipped,
                status = status,
                errMsg = errMsg,
                failedCount = failedCount,
                failedDetailsJson = BackupFailureRecord.encode(failures),
                bytesUploaded = bytesUploadedTotal,
                triggerCloudSync = anyMetadataChanged,
            )

            notifier.showResult(
                jobId = jobId,
                uploaded = filesUploaded,
                skipped = filesSkipped,
                partial = status == "PARTIAL",
                failed = status == "FAILURE",
                failedCount = failedCount,
            )

            val phase = when (status) {
                "SUCCESS" -> BackupPhase.Done(filesUploaded, filesToUpload, filesSkipped, 0)
                "PARTIAL" -> BackupPhase.Done(filesUploaded, filesToUpload, filesSkipped, failedCount)
                else -> BackupPhase.Failed(errMsg ?: "All uploads failed", filesUploaded, filesToUpload)
            }
            registry.updatePhase(phase)
            return when (status) {
                "SUCCESS" -> RunOutcome.Success(filesUploaded, filesSkipped, filesToUpload)
                "PARTIAL" -> RunOutcome.Partial(filesUploaded, filesToUpload, filesSkipped)
                else -> RunOutcome.Failed(errMsg ?: "All uploads failed", filesUploaded, filesToUpload, retryable = !cellularLimitHit.get())
            }
        } catch (t: CancellationException) {
            Log.i("BackupOrchestrator: cancelled (uploaded=$filesUploaded/$filesToUpload)")
            withContext(NonCancellable) {
                try {
                    finalize(
                        jobId = jobId,
                        startedAt = startedAt,
                        scanned = filesScanned,
                        uploaded = filesUploaded,
                        skipped = filesSkipped,
                        status = "CANCELLED",
                        errMsg = "Cancelled",
                        failedCount = failures.size,
                        failedDetailsJson = BackupFailureRecord.encode(failures),
                        bytesUploaded = bytesUploadedTotal,
                    )
                } catch (e: Throwable) {
                    Log.w("BackupOrchestrator: finalize failed during cancellation", e)
                }
            }
            runCatching {
                notifier.showResult(
                    jobId = jobId,
                    uploaded = filesUploaded,
                    skipped = filesSkipped,
                    partial = filesUploaded > 0,
                    failed = false,
                    failedCount = failures.size,
                )
            }
            registry.updatePhase(BackupPhase.Cancelled(filesUploaded, filesToUpload))
            throw t
        } catch (t: Throwable) {
            Log.e("BackupOrchestrator: fatal error", t)
            withContext(NonCancellable) {
                try {
                    finalize(
                        jobId = jobId,
                        startedAt = startedAt,
                        scanned = filesScanned,
                        uploaded = filesUploaded,
                        skipped = filesSkipped,
                        status = "FAILURE",
                        errMsg = t.message,
                        failedCount = failures.size.coerceAtLeast(1),
                        failedDetailsJson = BackupFailureRecord.encode(failures),
                        bytesUploaded = bytesUploadedTotal,
                    )
                } catch (e: Throwable) {
                    Log.w("BackupOrchestrator: finalize failed during error recovery", e)
                }
            }
            runCatching {
                notifier.showResult(jobId, filesUploaded, filesSkipped, partial = false, failed = true, failedCount = failures.size)
            }
            registry.updatePhase(BackupPhase.Failed(t.message ?: "Unexpected error", filesUploaded, filesToUpload))
            return RunOutcome.Failed(t.message ?: "Unexpected error", filesUploaded, filesToUpload)
        } finally {
            withContext(NonCancellable) {
                try {
                    watcherJob?.cancelAndJoin()
                    watcherScopeJob?.cancelAndJoin()
                } catch (_: Throwable) {}
            }
        }
    }

    private suspend fun uploadWithFloodWaitRetry(
        chatId: Long,
        localPath: String,
        fileName: String,
        mimeType: String,
        onFloodWait: () -> Unit,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit,
    ) = try {
        telegramClient.uploadFile(chatId, localPath, fileName, mimeType, onProgress)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        val waitSec = parseFloodWaitSeconds(t.message)
        if (waitSec != null && waitSec in 1..60) {
            onFloodWait()
            Log.w("BackupOrchestrator: hit FLOOD_WAIT_${waitSec}s — backing off and retrying '$fileName'")
            delay((waitSec + 1L) * 1000L)
            telegramClient.uploadFile(chatId, localPath, fileName, mimeType, onProgress)
        } else {
            throw t
        }
    }

    fun requestCancellation(jobId: String) {
        Log.i("BackupOrchestrator: cancellation requested for $jobId")
        registry.requestCancellation(jobId)
    }

    private suspend fun runZipMode(
        jobId: String,
        startedAt: Long,
        isManual: Boolean,
        prefs: AppPreferencesData,
        overrideTargetFolderId: Long? = null,
        folderUris: List<String>,
        folderNameByUri: Map<String, String>,
        scannedByUri: Map<String, List<LocalFile>>,
        toUploadByUri: Map<String, List<LocalFile>>,
        resolveTargetCached: suspend (Long) -> Long,
        filesScanned: Int,
        filesSkipped: Int,
        initialFailures: MutableList<BackupFailureRecord>,
        setForeground: suspend (String, Int, Int, String?) -> Unit,
    ): RunOutcome {
        var foldersSucceeded = 0
        var foldersFailed = 0
        var filesPackedAndUploaded = 0
        var bytesUploadedTotal = 0L
        val foldersTotal = folderUris.size
        val excludePatterns = ExcludePatternMatcher.parse(prefs.backupExcludePatterns)
        val excludeMatcher: ((String) -> Boolean)? =
            if (excludePatterns.isEmpty()) null else { name -> ExcludePatternMatcher.matches(name, excludePatterns) }

        try {
            setForeground(jobId, 0, foldersTotal, "Preparing ZIP archives…")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("BackupOrchestrator: setForeground (zip) failed", t)
        }

        registry.updatePhase(
            BackupPhase.Uploading(
                current = 0,
                total = foldersTotal,
                fileName = "Creating chunked ZIP archives…",
                bytesSent = 0,
                bytesTotal = 0,
            ),
        )

        val stagingDir = File(ctx.cacheDir, "backup_staging").apply { mkdirs() }
        val maxPartBytes = runCatching { telegramClient.safeZipChunkLimitBytes() }
            .getOrDefault(TelegramClient.SAFE_CHUNK_LIMIT_STANDARD)

        var sawUnpausedInZipLoop = false
        for ((i, folderUri) in folderUris.withIndex()) {
            ensureNotCancelled(jobId)

            val currentPrefs = preferences.data.first()
            if (!currentPrefs.backupPaused) sawUnpausedInZipLoop = true
            val shouldAbort = if (isManual) {
                sawUnpausedInZipLoop && currentPrefs.backupPaused
            } else {
                currentPrefs.backupPaused || !currentPrefs.backupEnabled
            }
            if (shouldAbort) {
                finalize(
                    jobId, startedAt, filesScanned, filesPackedAndUploaded, filesSkipped,
                    "CANCELLED", "Cancelled by user",
                    failedCount = initialFailures.size,
                    failedDetailsJson = BackupFailureRecord.encode(initialFailures),
                    bytesUploaded = bytesUploadedTotal,
                )
                registry.updatePhase(BackupPhase.Cancelled(filesPackedAndUploaded, filesScanned))
                notifier.showResult(jobId, filesPackedAndUploaded, filesSkipped, partial = filesPackedAndUploaded > 0, failed = false)
                return RunOutcome.Cancelled(filesPackedAndUploaded, filesScanned)
            }

            val folderToUpload = toUploadByUri[folderUri].orEmpty()
            val filesToRecordForFolder = if (prefs.backupZipIncremental) folderToUpload else scannedByUri[folderUri].orEmpty()
            if (prefs.backupZipIncremental && folderToUpload.isEmpty()) {
                foldersSucceeded++
                continue
            }

            val folderName = folderNameByUri[folderUri] ?: fallbackFolderName(folderUri)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
            val zipBaseName = if (prefs.backupZipIncremental) "${folderName}_inc_$timestamp" else "${folderName}_$timestamp"
            val folderPrefTarget = overrideTargetFolderId ?: prefs.targetFolderFor(folderUri)
            val destChatId = resolveTargetCached(folderPrefTarget)
            val encryptZip = prefs.isFolderEncrypted(folderUri)
            val zipPassword = if (encryptZip) backupCrypto.activeBackupZipPassword() else null
            val includedUris = if (prefs.backupZipIncremental) folderToUpload.map { it.absolutePath }.toSet() else null

            registry.updatePhase(
                BackupPhase.Uploading(
                    current = i + 1,
                    total = foldersTotal,
                    fileName = "$zipBaseName.zip",
                    bytesSent = 0,
                    bytesTotal = 0,
                ),
            )

            var manifestFile: File? = null
            try {
                val uploadedParts = mutableListOf<UploadedZipPartRecord>()
                val summary = ZipUploader.zipFolderInParts(
                    context = ctx,
                    treeUri = android.net.Uri.parse(folderUri),
                    workDir = stagingDir,
                    baseZipName = zipBaseName,
                    maxPartBytes = maxPartBytes,
                    password = zipPassword,
                    excludeMatcher = excludeMatcher,
                    includedUris = includedUris,
                    compressionMode = com.tgdrive.core.zip.ZipCompressionMode.SMART,
                ) { part ->
                    ensureNotCancelled(jobId)
                    val partName = part.partFile.name
                    val partSize = part.partFile.length()
                    var lastZipProgressMs = 0L
                    val uploadResult = uploadWithFloodWaitRetry(
                        chatId = destChatId,
                        localPath = part.partFile.absolutePath,
                        fileName = partName,
                        mimeType = "application/zip",
                        onFloodWait = {},
                    ) { sent, total ->
                        val now = System.currentTimeMillis()
                        if (sent >= total || now - lastZipProgressMs >= 200L) {
                            lastZipProgressMs = now
                            registry.updatePhase(
                                BackupPhase.Uploading(
                                    current = i + 1,
                                    total = foldersTotal,
                                    fileName = partName,
                                    bytesSent = sent,
                                    bytesTotal = total.coerceAtLeast(partSize),
                                    totalBytesUploaded = bytesUploadedTotal + sent,
                                    totalBytesAll = bytesUploadedTotal + total.coerceAtLeast(partSize),
                                ),
                            )
                        }
                    }

                    val zipThumb = if (encryptZip) {
                        try {
                            mediaThumbnailCache.generateAndSyncEncryptedThumb(
                                chatId = destChatId,
                                messageId = uploadResult.messageId,
                                localPath = part.partFile.absolutePath,
                                mimeType = "application/zip",
                                originalFileName = partName,
                                zipPassword = zipPassword,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            null
                        }
                    } else {
                        try {
                            mediaThumbnailCache.generateFromLocalFile(
                                chatId = destChatId,
                                messageId = uploadResult.messageId,
                                localPath = part.partFile.absolutePath,
                                mimeType = "application/zip",
                                fileName = partName,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            null
                        }
                    }
                    fileCacheDao.upsertAll(
                        listOf(
                            FileCacheEntity(
                                messageId = uploadResult.messageId,
                                chatId = destChatId,
                                fileName = partName,
                                fileSize = partSize,
                                mimeType = "application/zip",
                                thumbnailPath = zipThumb,
                                remoteId = uploadResult.remoteId,
                                date = System.currentTimeMillis(),
                                isEncryptedVault = encryptZip,
                            ),
                        ),
                    )
                    bytesUploadedTotal += partSize
                    runCatching {
                        zipIndexService.indexPartEntries(
                            chatId = destChatId,
                            zipMessageId = uploadResult.messageId,
                            zipFileName = partName,
                            entryPaths = part.entries,
                            encrypted = encryptZip,
                        )
                    }
                    uploadedParts += UploadedZipPartRecord(
                        fileName = partName,
                        fileSizeBytes = partSize,
                        partIndex = part.partIndex,
                        isSplitVolume = part.isSplitVolume,
                        messageId = uploadResult.messageId,
                        entries = part.entries,
                    )
                }

                if (summary.totalFilesProcessed == 0 || uploadedParts.isEmpty()) {
                    Log.w("BackupOrchestrator: no eligible files zipped for $folderUri — skipping")
                    foldersFailed++
                    initialFailures += BackupFailureRecord(
                        fileName = folderName,
                        localPath = folderUri,
                        reason = "No eligible files could be packed into ZIP",
                        folderUri = folderUri,
                    )
                    continue
                }

                if (uploadedParts.size > 1) {
                    val mFile = ZipUploader.writeManifestJsonFile(
                        outputDir = stagingDir,
                        baseName = zipBaseName,
                        folderName = folderName,
                        encrypted = encryptZip,
                        parts = uploadedParts,
                    )
                    manifestFile = mFile
                    val mSize = mFile.length()
                    val mResult = uploadWithFloodWaitRetry(
                        chatId = destChatId,
                        localPath = mFile.absolutePath,
                        fileName = mFile.name,
                        mimeType = "application/json",
                        onFloodWait = {},
                    ) { _, _ -> }
                    fileCacheDao.upsertAll(
                        listOf(
                            FileCacheEntity(
                                messageId = mResult.messageId,
                                chatId = destChatId,
                                fileName = mFile.name,
                                fileSize = mSize,
                                mimeType = "application/json",
                                thumbnailPath = null,
                                remoteId = mResult.remoteId,
                                date = System.currentTimeMillis(),
                                isEncryptedVault = encryptZip,
                            ),
                        ),
                    )
                    bytesUploadedTotal += mSize
                }

                val syncedMsgId = uploadedParts.last().messageId
                val nowMs = System.currentTimeMillis()
                val folderDbId = folderPrefTarget.takeIf { it > 0 }
                for (localFile in filesToRecordForFolder) {
                    runCatching {
                        syncedFileDao.upsert(
                            SyncedFileEntity(
                                localPath = localFile.absolutePath,
                                telegramMessageId = syncedMsgId,
                                sha256 = localFile.sha256,
                                size = localFile.size,
                                syncedAt = nowMs,
                                folderId = folderDbId,
                                lastModified = localFile.lastModified,
                            ),
                        )
                    }
                }

                filesPackedAndUploaded += summary.totalFilesProcessed
                foldersSucceeded++
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("BackupOrchestrator: zip upload failed for $folderUri", t)
                foldersFailed++
                initialFailures += BackupFailureRecord(
                    fileName = folderName,
                    localPath = folderUri,
                    reason = t.message ?: t.javaClass.simpleName,
                    folderUri = folderUri,
                )
            } finally {
                runCatching { manifestFile?.delete() }
            }
        }

        val status = when {
            foldersSucceeded == foldersTotal && foldersFailed == 0 && initialFailures.isEmpty() -> "SUCCESS"
            foldersSucceeded == 0 -> "FAILURE"
            else -> "PARTIAL"
        }
        val errMsg = if (status == "FAILURE") (initialFailures.firstOrNull()?.reason ?: "All ZIP uploads failed") else null
        finalize(
            jobId = jobId,
            startedAt = startedAt,
            scanned = filesScanned,
            uploaded = filesPackedAndUploaded,
            skipped = filesSkipped,
            status = status,
            errMsg = errMsg,
            failedCount = initialFailures.size,
            failedDetailsJson = BackupFailureRecord.encode(initialFailures),
            bytesUploaded = bytesUploadedTotal,
        )
        notifier.showResult(
            jobId = jobId,
            uploaded = filesPackedAndUploaded,
            skipped = filesSkipped,
            partial = status == "PARTIAL",
            failed = status == "FAILURE",
            failedCount = initialFailures.size,
        )
        val phase = when (status) {
            "SUCCESS" -> BackupPhase.Done(filesPackedAndUploaded, filesScanned, filesSkipped, 0)
            "PARTIAL" -> BackupPhase.Done(filesPackedAndUploaded, filesScanned, filesSkipped, initialFailures.size)
            else -> BackupPhase.Failed(errMsg ?: "All ZIP uploads failed", 0, filesScanned)
        }
        registry.updatePhase(phase)
        return when (status) {
            "SUCCESS" -> RunOutcome.Success(uploaded = filesPackedAndUploaded, skipped = filesSkipped, total = filesScanned)
            "PARTIAL" -> RunOutcome.Partial(uploaded = filesPackedAndUploaded, total = filesScanned, skipped = filesSkipped)
            else -> RunOutcome.Failed(errMsg ?: "All ZIP uploads failed", 0, filesScanned)
        }
    }

    private fun checkBatterySkipReason(minBatteryPercent: Int): String? {
        if (com.tgdrive.core.common.ThermalGuard.isThermalSevere(ctx)) {
            return "Paused while device cools down"
        }
        val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val battery = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val isCharging = runCatching { battery?.isCharging == true }.getOrDefault(false)
        if (isCharging) return null
        val pct = runCatching { battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 }.getOrDefault(-1)
        if (pct in 1 until minBatteryPercent) {
            return "Paused to save battery ($pct%)"
        }
        if (runCatching { power?.isPowerSaveMode == true }.getOrDefault(false)) {
            return "Paused while Battery Saver is active"
        }
        return null
    }

    private fun isNetworkMetered(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return runCatching { cm?.isActiveNetworkMetered == true }.getOrDefault(false)
    }

    private fun matchesBackupFilters(
        file: LocalFile,
        prefs: AppPreferencesData,
        isMetered: Boolean,
        isManual: Boolean,
    ): Boolean {
        if (prefs.backupOnlyNewAfterMs > 0L && file.lastModified > 0L && file.lastModified < prefs.backupOnlyNewAfterMs) {
            return false
        }
        if (prefs.backupMaxFileSizeMb > 0) {
            val maxBytes = prefs.backupMaxFileSizeMb.toLong() * 1024L * 1024L
            if (file.size > maxBytes) return false
        }
        val bucket = classifyFile(file.mimeType, file.name)
        when (prefs.backupMediaFilter) {
            BackupMediaFilter.ALL_FILES -> Unit
            BackupMediaFilter.PHOTOS_AND_VIDEOS -> if (bucket != FileBucket.IMAGES && bucket != FileBucket.VIDEOS) return false
            BackupMediaFilter.PHOTOS_ONLY -> if (bucket != FileBucket.IMAGES) return false
        }
        if (isMetered && !isManual && prefs.backupSkipVideosOnCellular && bucket == FileBucket.VIDEOS) {
            return false
        }
        return true
    }

    private fun fallbackFolderName(folderUri: String): String =
        runCatching {
            val parsed = android.net.Uri.parse(folderUri)
            DocumentFile.fromTreeUri(ctx, parsed)?.name
        }.getOrNull()
            ?: (android.net.Uri.decode(folderUri) ?: folderUri)
                .substringAfterLast(':')
                .substringAfterLast('/')
                .ifBlank { "Backup" }

    private fun buildNotifSubtext(fileName: String, speedBps: Long, etaSec: Long): String =
        buildString {
            append(fileName)
            if (speedBps > 0L) {
                append(" · ")
                append(speedBps.toHumanSize())
                append("/s")
            }
            if (etaSec > 0L) {
                append(" · ~")
                append(formatEtaShort(etaSec))
            }
        }

    private suspend fun ensureNotCancelled(jobId: String) {
        if (registry.isCancellationRequested(jobId)) {
            throw CancellationException("User requested cancellation")
        }
        coroutineContext.ensureActive()
    }

    private suspend fun resolveTargetChatId(folderId: Long): Long {
        if (folderId <= 0) return telegramClient.myUserId()
        val folder = folderDao.getById(folderId)
        return folder?.channelId ?: telegramClient.myUserId()
    }

    private suspend fun finalize(
        jobId: String,
        startedAt: Long,
        scanned: Int,
        uploaded: Int,
        skipped: Int,
        status: String,
        errMsg: String?,
        failedCount: Int = 0,
        failedDetailsJson: String? = null,
        bytesUploaded: Long = 0L,
        triggerCloudSync: Boolean = false,
    ) {
        try {
            val existing = syncLogDao.get(jobId)
            syncLogDao.upsert(
                (existing ?: SyncLogEntity(
                    jobId = jobId,
                    startedAt = startedAt,
                    completedAt = null,
                    status = status,
                )).copy(
                    completedAt = System.currentTimeMillis(),
                    filesScanned = scanned,
                    filesUploaded = uploaded,
                    filesSkipped = skipped,
                    status = status,
                    errorMessage = errMsg,
                    failedCount = failedCount,
                    failedDetailsJson = failedDetailsJson,
                    bytesUploaded = bytesUploaded,
                ),
            )
            syncLogDao.deleteOlderThan(System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000))
            if (uploaded > 0 || triggerCloudSync) {
                cloudSyncManager.schedulePushMetadata()
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.w("BackupOrchestrator: finalize failed", t)
        }
    }

    sealed class RunOutcome {
        data class Success(val uploaded: Int, val skipped: Int, val total: Int) : RunOutcome()
        data class Partial(val uploaded: Int, val total: Int, val skipped: Int) : RunOutcome()
        data class Cancelled(val uploaded: Int, val total: Int) : RunOutcome()
        data class Failed(
            val message: String,
            val uploaded: Int,
            val total: Int,
            val retryable: Boolean = true,
        ) : RunOutcome()
        data class Skipped(val reason: String) : RunOutcome()
    }

    companion object {
        private const val LARGE_BACKUP_FILE_BYTES = 25L * 1024 * 1024
        private val YEAR_SEGMENT_REGEX = Regex("^(19|20)\\d{2}$")
        private val FLOOD_WAIT_REGEX = Regex("(?:FLOOD_WAIT_|retry after\\s+)(\\d+)", RegexOption.IGNORE_CASE)

        internal fun parseFloodWaitSeconds(message: String?): Int? {
            if (message.isNullOrBlank()) return null
            return FLOOD_WAIT_REGEX.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        internal fun buildVirtualPath(
            rootFolderTitle: String,
            relativeDir: String,
            organizeByDate: Boolean,
            captureTimestampMs: Long,
        ): String {
            val segments = ArrayList<String>(4)
            fun appendClean(raw: String) {
                raw.trim('/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }.forEach { segments += it }
            }
            if (rootFolderTitle.isNotBlank()) appendClean(rootFolderTitle)
            val afterRootIdx = segments.size
            if (relativeDir.isNotBlank()) {
                appendClean(relativeDir)
            }
            val hasYearSegment = segments.subList(afterRootIdx, segments.size).any { YEAR_SEGMENT_REGEX.matches(it) }
            if (organizeByDate && captureTimestampMs > 0L && !hasYearSegment) {
                val z = Instant.ofEpochMilli(captureTimestampMs).atZone(ZoneId.systemDefault())
                segments += z.year.toString()
                segments += z.monthValue.toString().padStart(2, '0')
            }
            return segments.joinToString("/")
        }

        internal fun formatEtaShort(seconds: Long): String {
            if (seconds < 60L) return "${seconds}s"
            val mins = seconds / 60L
            val remSec = seconds % 60L
            if (mins < 60L) return "${mins}m ${remSec}s"
            val hrs = mins / 60L
            val remMin = mins % 60L
            return "${hrs}h ${remMin}m"
        }
    }
}
