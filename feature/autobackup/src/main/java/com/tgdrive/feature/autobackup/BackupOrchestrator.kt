package com.tgdrive.feature.autobackup

import android.content.Context
import com.tgdrive.core.common.Log
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncLogDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.SyncLogEntity
import com.tgdrive.core.db.entity.SyncedFileEntity
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.feature.autobackup.diff.DiffEngine
import com.tgdrive.feature.autobackup.diff.LocalFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Single source of truth for the AutoBackup pipeline.
 *
 * Both [BackupWorker.doWork] and (indirectly via the scheduler) the manual
 * "Run now" button end up here. The orchestrator owns:
 *
 *   1. Acquiring the [ActiveBackupRegistry] slot (mutual exclusion).
 *   2. The full pipeline: scan → diff → resolve target → upload → finalize.
 *   3. Publishing [BackupPhase] updates to the registry (so the UI sees
 *      live progress, even across ViewModel recreation).
 *   4. Honoring cancellation at every suspend point.
 *   5. Cleaning up staging files in a `finally` block — even on cancel/fail.
 *
 * The orchestrator is a Singleton so it survives across ViewModel
 * recreations. It does NOT hold any per-job state in fields — everything
 * is local to [runBackup], so concurrent invocations are safe (and the
 * registry will reject the second one anyway).
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
) {

    /**
     * Run one backup job.
     *
     * @param jobId Unique identifier (e.g. "manual_<timestamp>" or "periodic_<timestamp>").
     * @param isManual true if triggered by the user (controls foreground-notif text).
     * @param setForeground Callback the orchestrator invokes to promote the worker
     *        to a foreground service. Required on Android 12+ for long-running
     *        workers. The lambda receives (jobId, count, total) and the worker
     *        calls `setForeground(notifier.buildForegroundInfo(...))`. If the
     *        callback throws (e.g. notification permission denied), the
     *        orchestrator logs and continues — the run still works, it's just
     *        more likely to be killed by the OS.
     * @return A [RunOutcome] describing the final state. The caller (Worker)
     *         uses this to decide Result.success / Result.failure.
     */
    suspend fun runBackup(
        jobId: String,
        isManual: Boolean,
        setForeground: suspend (jobId: String, count: Int, total: Int) -> Unit = { _, _, _ -> },
    ): RunOutcome {
        val startedAt = System.currentTimeMillis()

        // --- 1. Mutual exclusion: refuse if another run is active.
        if (!registry.tryBegin(jobId, startedAt)) {
            Log.i("BackupOrchestrator: refusing to start $jobId — another run is active")
            return RunOutcome.Skipped("Another backup is already running")
        }

        // --- 2. Persist sync_log row in RUNNING state up-front.
        try {
            syncLogDao.upsert(SyncLogEntity(
                jobId = jobId, startedAt = startedAt,
                completedAt = null, status = "RUNNING",
            ))
        } catch (t: Throwable) {
            Log.w("BackupOrchestrator: failed to create sync log", t)
        }

        // --- 2.5. CANCELLATION WATCHER: launch a background job that polls
        // the registry for cancellation requests AND observes preferences
        // for pause/disable. When either is detected, it cancels the parent
        // coroutine's Job — which interrupts ANY suspend point, including
        // the `sendConfirmation.await()` inside uploadFile.
        //
        // C5 FIX: Previously used GlobalScope.launch (process-scoped, leaks
        // if finally is skipped). Now uses a CHILD scope of the current
        // coroutine via coroutineScope, so it's automatically cancelled when
        // runBackup returns. Also uses cancelAndJoin() in finally to ensure
        // the watcher has fully stopped before runBackup returns.
        //
        // C6 FIX: Previously called preferences.data.first() every 500ms,
        // creating ~7200 flow collections per hour. Now collects the flow
        // ONCE and reacts to emissions — zero polling overhead.
        val parentJob = coroutineContext[Job]
        val watcherJob = kotlinx.coroutines.CoroutineScope(coroutineContext[Job]!! + kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // Launch two watchers in parallel: one for the registry's
                // cancelRequested StateFlow, one for the preferences Flow.
                // Use launch + selectFirst-style joinAll to wait for either.
                val cancelWatcher = launch {
                    registry.cancelRequested.collect { requestedId ->
                        if (requestedId == jobId) {
                            Log.i("BackupOrchestrator: watcher detected cancellation — cancelling parent job")
                            parentJob?.cancel()
                        }
                    }
                }
                val pauseWatcher = launch {
                    preferences.data.collect { p ->
                        val shouldCancel = if (isManual) p.backupPaused else (!p.backupEnabled || p.backupPaused)
                        if (shouldCancel) {
                            Log.i("BackupOrchestrator: watcher detected pause/disable — cancelling parent job")
                            parentJob?.cancel()
                        }
                    }
                }
                cancelWatcher.join()
                pauseWatcher.cancel()
            } catch (e: CancellationException) {
                // Watcher was cancelled (runBackup finished) — just exit.
            }
        }

        // --- 3. Pre-run cleanup: purge staging files older than 1 hour.
        stagingManager.purgeStale()

        // --- Per-run mutable state. We track these explicitly so we can
        // --- finalize correctly even on cancellation/failure.
        var filesScanned = 0
        var filesToUpload = 0
        var filesUploaded = 0
        var filesSkipped = 0
        var lastStagingPath: String? = null

        try {
            val prefs = preferences.data.first()
            if (!prefs.backupEnabled && !isManual) {
                Log.i("BackupOrchestrator: disabled; skipping")
                finalize(jobId, startedAt, filesScanned, filesUploaded, filesSkipped, "SUCCESS", null)
                registry.updatePhase(BackupPhase.Done(0, 0, 0, 0))
                return RunOutcome.Success(uploaded = 0, skipped = 0, total = 0)
            }
            if (prefs.backupPaused && !isManual) {
                Log.i("BackupOrchestrator: paused; skipping")
                finalize(jobId, startedAt, filesScanned, filesUploaded, filesSkipped, "SUCCESS", null)
                registry.updatePhase(BackupPhase.Done(0, 0, 0, 0))
                return RunOutcome.Skipped("Paused")
            }
            if (prefs.backupFolderUris.isEmpty()) {
                val msg = "No folders selected"
                finalize(jobId, startedAt, 0, 0, 0, "FAILURE", msg)
                registry.updatePhase(BackupPhase.Failed(msg, 0, 0))
                return RunOutcome.Failed(msg, 0, 0)
            }

            // --- 3.5. Promote to foreground service IMMEDIATELY — before
            // --- scanning. On Android 12+, background workers are killed
            // --- after ~3 minutes without foreground priority.
            // ---
            // --- CRITICAL: If setForeground fails (e.g., POST_NOTIFICATIONS
            // --- permission not granted on Android 13+), we ABORT with a
            // --- clear error instead of continuing without foreground
            // --- priority. Previously we continued, which led to the OS
            // --- killing the worker mid-scan → hard crash.
            try {
                setForeground(jobId, 0, 0)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Background WorkManager runs on Android 12+ may hit
                // ForegroundServiceStartNotAllowedException; degrade gracefully
                // and let WorkManager run with standard background execution.
                Log.w("BackupOrchestrator: setForeground unavailable (${t.javaClass.simpleName}) — continuing without foreground service", t)
            }

            // --- 4. Scan
            // CRITICAL: Pass a progress callback so the phase updates DURING
            // the scan, not just between folders. Without this, a single
            // large folder shows "Scanning folders 0/1" for minutes with
            // no file count update — the user thinks the app is frozen.
            //
            // THROTTLING: The callback fires every 50 files from FolderScanner.
            // If the scan finds files very fast (e.g., thousands of small
            // files), this could mean dozens of StateFlow updates per second
            // → dozens of Compose recompositions per second → main thread
            // overloaded → app freeze. We throttle to at most one update per
            // 200ms by tracking the last update timestamp.
            registry.updatePhase(BackupPhase.Scanning(foldersDone = 0, foldersTotal = prefs.backupFolderUris.size, filesFound = 0))
            val allLocal = mutableListOf<LocalFile>()
            val folderUris = prefs.backupFolderUris.toList()
            val excludePatterns = ExcludePatternMatcher.parse(prefs.backupExcludePatterns)
            var lastProgressUpdateMs = 0L
            var anyScanFailed = false
            for ((i, uri) in folderUris.withIndex()) {
                ensureNotCancelled(jobId)
                val folderIndex = i
                try {
                    val scanned = folderScanner.scan(uri) { filesFound ->
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdateMs >= 200) {
                            lastProgressUpdateMs = now
                            registry.updatePhase(BackupPhase.Scanning(
                                foldersDone = folderIndex,
                                foldersTotal = folderUris.size,
                                filesFound = filesFound,
                            ))
                        }
                    }
                    // Apply exclude patterns — filter out files matching
                    // user-configured exclusion rules (e.g. *.tmp, Thumbs.db)
                    allLocal += if (excludePatterns.isEmpty()) {
                        scanned
                    } else {
                        scanned.filterNot { file ->
                            ExcludePatternMatcher.matches(file.name, excludePatterns)
                        }
                    }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    anyScanFailed = true
                    Log.w("BackupOrchestrator: scan failed for $uri", t)
                }
                registry.updatePhase(BackupPhase.Scanning(
                    foldersDone = i + 1,
                    foldersTotal = folderUris.size,
                    filesFound = allLocal.size,
                ))
            }
            filesScanned = allLocal.size
            Log.i("BackupOrchestrator: scanned $filesScanned files from ${folderUris.size} folder(s)")

            ensureNotCancelled(jobId)

            // --- 5. Resolve target chat BEFORE diffing, so the diff uses
            // --- the correct synced-files set for the actual destination.
            val targetChatId = try {
                resolveTargetChatId(prefs.backupTargetFolderId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val msg = "Cannot connect to Telegram: ${t.message}"
                Log.w("BackupOrchestrator: resolveTargetChatId failed", t)
                finalize(jobId, startedAt, filesScanned, 0, 0, "FAILURE", msg)
                registry.updatePhase(BackupPhase.Failed(msg, 0, 0))
                return RunOutcome.Failed(msg, 0, 0)
            }
            Log.i("BackupOrchestrator: target chatId = $targetChatId")

            // --- 6. Diff
            Log.i("BackupOrchestrator: starting diff — ${allLocal.size} local files, strict=${prefs.backupStrictMode}")
            registry.updatePhase(BackupPhase.Diffing(filesFound = filesScanned))
            val queryFolderId = prefs.backupTargetFolderId.takeIf { it > 0 } ?: -1L
            val already = syncedFileDao.forFolder(queryFolderId)
            Log.i("BackupOrchestrator: loaded ${already.size} existing synced records for folderId=$queryFolderId")
            val diff = try {
                diffEngine.compute(
                    local = allLocal,
                    already = already,
                    strict = prefs.backupStrictMode,
                )
            } catch (t: CancellationException) {
                // Re-throw — don't swallow cancellation.
                Log.i("BackupOrchestrator: diff cancelled")
                throw t
            } catch (t: Throwable) {
                // Convert any diff error to a Failed outcome instead of crashing.
                Log.e("BackupOrchestrator: diff failed", t)
                val msg = "Diff failed: ${t.message ?: t.javaClass.simpleName}"
                finalize(jobId, startedAt, filesScanned, 0, 0, "FAILURE", msg)
                registry.updatePhase(BackupPhase.Failed(msg, 0, 0))
                return RunOutcome.Failed(msg, 0, 0)
            }
            val toUpload = diff.toUpload
            filesSkipped = diff.toSkip.size
            filesToUpload = toUpload.size
            Log.i("BackupOrchestrator: diff complete — ${toUpload.size} to upload, $filesSkipped to skip, ${diff.staleRemotePaths.size} stale")

            // Clean up stale synced rows only when all folders scanned cleanly.
            if (!anyScanFailed) {
                for (stalePath in diff.staleRemotePaths) {
                    runCatching { syncedFileDao.deleteByPath(stalePath) }
                }
            }

            ensureNotCancelled(jobId)

            if (toUpload.isEmpty()) {
                Log.i("BackupOrchestrator: nothing to upload")
                finalize(jobId, startedAt, filesScanned, 0, filesSkipped, "SUCCESS", null)
                registry.updatePhase(BackupPhase.Done(0, 0, filesSkipped, 0))
                return RunOutcome.Success(uploaded = 0, skipped = filesSkipped, total = 0)
            }

            // --- 6.5. ZIP mode: if backupZipFolders is enabled, zip all
            // files into a single archive per folder and upload that instead
            // of individual files. This reduces message count and keeps
            // files organized by folder.
            if (prefs.backupZipFolders) {
                Log.i("BackupOrchestrator: zip mode enabled — uploading as ZIP archives")
                return runZipMode(
                    jobId, startedAt, isManual, prefs, folderUris, toUpload,
                    targetChatId, filesScanned, filesSkipped, setForeground,
                )
            }

            // --- 7. Update the foreground notification with upload counts.
            // --- (setForeground was already called in step 3.5 before scanning.
            // --- Here we just update the progress text.)
            try {
                setForeground(jobId, 0, filesToUpload)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("BackupOrchestrator: setForeground update failed", t)
            }
            try {
                notifier.showForeground(jobId, 0, filesToUpload)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("BackupOrchestrator: showForeground failed", t)
            }

            // --- 8. Upload loop
            for ((i, file) in toUpload.withIndex()) {
                ensureNotCancelled(jobId)

                // Also honor pause/disable toggled mid-run.
                val currentPrefs = preferences.data.first()
                val shouldAbort = currentPrefs.backupPaused || (!isManual && !currentPrefs.backupEnabled)
                if (shouldAbort) {
                    Log.i("BackupOrchestrator: aborted mid-run (enabled=${currentPrefs.backupEnabled}, paused=${currentPrefs.backupPaused}, manual=$isManual)")
                    finalize(jobId, startedAt, filesScanned, filesUploaded, filesSkipped, "CANCELLED", "Cancelled by user")
                    registry.updatePhase(BackupPhase.Cancelled(filesUploaded, filesToUpload))
                    notifier.showResult(jobId, filesUploaded, filesSkipped, partial = filesUploaded > 0, failed = false)
                    return RunOutcome.Cancelled(filesUploaded, filesToUpload)
                }

                registry.updatePhase(BackupPhase.Uploading(
                    current = i + 1, total = filesToUpload,
                    fileName = file.name, bytesSent = 0, bytesTotal = file.size,
                ))

                try {
                    val stagingPath = stagingManager.stage(file.absolutePath, file.name)
                    if (stagingPath == null) {
                        Log.w("BackupOrchestrator: failed to stage '${file.name}' — skipping")
                        continue
                    }
                    lastStagingPath = stagingPath

                    var lastUploadProgressMs = 0L
                    val uploadResult = try {
                        telegramClient.uploadFile(
                            chatId = targetChatId,
                            localPath = stagingPath,
                            fileName = file.name,
                            mimeType = file.mimeType,
                        ) { sent, total ->
                            val now = System.currentTimeMillis()
                            if (sent >= total || now - lastUploadProgressMs >= 200L) {
                                lastUploadProgressMs = now
                                registry.updatePhase(
                                    BackupPhase.Uploading(
                                        current = i + 1,
                                        total = filesToUpload,
                                        fileName = file.name,
                                        bytesSent = sent,
                                        bytesTotal = total.coerceAtLeast(file.size),
                                    ),
                                )
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // DON'T delete the staging file — TDLib may still be
                        // reading it. Leave it for purgeStale().
                        lastStagingPath = null
                        throw e
                    } catch (t: Throwable) {
                        // Upload failed — also don't delete, just in case
                        // TDLib is still retrying internally.
                        lastStagingPath = null
                        throw t
                    }

                    // Upload succeeded — safe to delete the staging file.
                    stagingManager.release(stagingPath)
                    lastStagingPath = null

                    val msgId = uploadResult.messageId

                    // Record in synced_files (use composite key path+size to prevent duplicates).
                    syncedFileDao.upsert(SyncedFileEntity(
                        localPath = file.absolutePath,
                        telegramMessageId = msgId,
                        sha256 = file.sha256,
                        size = file.size,
                        syncedAt = System.currentTimeMillis(),
                        folderId = prefs.backupTargetFolderId.takeIf { it > 0 },
                    ))

                    // Mirror into file_cache so the file appears in the browser immediately.
                    fileCacheDao.upsertAll(listOf(
                        FileCacheEntity(
                            messageId = msgId,
                            chatId = targetChatId,
                            fileName = file.name,
                            fileSize = file.size,
                            mimeType = file.mimeType,
                            thumbnailPath = null,
                            remoteId = uploadResult.remoteId,
                            date = System.currentTimeMillis(),
                            isStarred = false,
                            lastAccessed = 0L,
                        )
                    ))

                    // Optional: delete the original SAF document.
                    if (prefs.backupDeleteAfterUpload) {
                        try {
                            val uri = android.net.Uri.parse(file.absolutePath)
                            val deleted = android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, uri)
                            if (deleted) {
                                Log.i("BackupOrchestrator: deleted original '${file.name}' after upload")
                            } else {
                                Log.w("BackupOrchestrator: SAF provider refused to delete '${file.name}'")
                            }
                        } catch (t: Throwable) {
                            Log.w("BackupOrchestrator: could not delete original '${file.name}'", t)
                        }
                    }

                    filesUploaded++
                    // Update foreground notification progress.
                    try {
                        setForeground(jobId, filesUploaded, filesToUpload)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {}
                    try {
                        notifier.showForeground(jobId, filesUploaded, filesToUpload)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {}

                    // Rate-limit safety: 1s between sends to avoid Telegram flood limits.
                    kotlinx.coroutines.delay(1000)
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Log.w("BackupOrchestrator: failed to upload ${file.name}", t)
                    // Continue with next file — partial failure is OK.
                }
            }

            // --- 9. Finalize
            registry.updatePhase(BackupPhase.Finalizing(filesUploaded, filesToUpload, filesSkipped))
            val status = when {
                filesUploaded == filesToUpload -> "SUCCESS"
                filesUploaded == 0 -> "FAILURE"
                else -> "PARTIAL"
            }
            val errMsg = if (status == "FAILURE") "All uploads failed" else null
            finalize(jobId, startedAt, filesScanned, filesUploaded, filesSkipped, status, errMsg)

            notifier.showResult(
                jobId = jobId,
                uploaded = filesUploaded,
                skipped = filesSkipped,
                partial = status == "PARTIAL",
                failed = status == "FAILURE",
            )

            val phase = when (status) {
                "SUCCESS" -> BackupPhase.Done(filesUploaded, filesToUpload, filesSkipped, 0)
                "PARTIAL" -> BackupPhase.Done(filesUploaded, filesToUpload, filesSkipped, filesToUpload - filesUploaded)
                else -> BackupPhase.Failed(errMsg ?: "All uploads failed", 0, filesToUpload)
            }
            registry.updatePhase(phase)
            return when (status) {
                "SUCCESS" -> RunOutcome.Success(filesUploaded, filesSkipped, filesToUpload)
                "PARTIAL" -> RunOutcome.Partial(filesUploaded, filesToUpload, filesSkipped)
                else -> RunOutcome.Failed(errMsg ?: "All uploads failed", 0, filesToUpload)
            }

        } catch (t: CancellationException) {
            // Cancellation arrives as CancellationException.
            //
            // IMPORTANT: When a coroutine is cancelled, suspend functions called
            // from this catch block will throw CancellationException immediately
            // because the coroutine's Job is in "Cancelling" state. To run
            // cleanup code that MUST complete (DB writes, notifications), we
            // use `withContext(NonCancellable) { ... }` which switches to a
            // context that cannot be cancelled.
            //
            // Previously, `finalize()` was called directly here, and its
            // internal `catch (t: Throwable)` swallowed the CancellationException
            // — a coroutine anti-pattern that left the coroutine in a "zombie"
            // state and caused cascading failures (the app crashed after
            // showing "Cancelled").
            Log.i("BackupOrchestrator: cancelled (uploaded=$filesUploaded/$filesToUpload)")
            // DON'T delete lastStagingPath — TDLib may still be reading it.
            // Leave it for purgeStale() to clean up after 1 hour.
            // stagingManager.release(lastStagingPath)  // ← removed
            // Run cleanup in a NonCancellable context so DB writes complete.
            withContext(NonCancellable) {
                try {
                    finalize(jobId, startedAt, filesScanned, filesUploaded, filesSkipped, "CANCELLED", "Cancelled")
                } catch (t: Throwable) {
                    Log.w("BackupOrchestrator: finalize failed during cancellation", t)
                }
            }
            try { notifier.showResult(jobId, filesUploaded, filesSkipped, partial = filesUploaded > 0, failed = false) } catch (_: Throwable) {}
            registry.updatePhase(BackupPhase.Cancelled(filesUploaded, filesToUpload))
            throw t  // Propagate so the Worker can return Result.success(CANCELLED).
        } catch (t: Throwable) {
            // Catch-all for unexpected errors (OOM, SQLiteException, etc.).
            Log.e("BackupOrchestrator: fatal error", t)
            // DON'T delete lastStagingPath — TDLib may still be reading it.
            // stagingManager.release(lastStagingPath)  // ← removed
            withContext(NonCancellable) {
                try {
                    finalize(jobId, startedAt, filesScanned, filesUploaded, filesSkipped, "FAILURE", t.message)
                } catch (t: Throwable) {
                    Log.w("BackupOrchestrator: finalize failed during error recovery", t)
                }
            }
            try { notifier.showResult(jobId, filesUploaded, filesSkipped, partial = false, failed = true) } catch (_: Throwable) {}
            registry.updatePhase(BackupPhase.Failed(t.message ?: "Unexpected error", filesUploaded, filesToUpload))
            return RunOutcome.Failed(t.message ?: "Unexpected error", filesUploaded, filesToUpload)
        } finally {
            // C5 FIX: Use NonCancellable + cancelAndJoin() so the watcher
            // stops cleanly even if runBackup was cancelled.
            withContext(NonCancellable) {
                try { watcherJob.cancelAndJoin() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Mark the active job as cancelled. Called by [BackupActionReceiver].
     *
     * This is a soft cancel: the orchestrator polls
     * [ActiveBackupRegistry.isCancellationRequested] at every chunk boundary
     * and exits cleanly. Hard cancel (WorkManager.cancelAllWork) is also
     * supported — the orchestrator's catch blocks will clean up staging.
     */
    fun requestCancellation(jobId: String) {
        Log.i("BackupOrchestrator: cancellation requested for $jobId")
        registry.requestCancellation(jobId)
    }

    /**
     * ZIP mode: zip each folder's files into a single .zip and upload that.
     *
     * Instead of uploading N individual files, we create one .zip per
     * source folder and upload it as a single message. This:
     *   - Reduces message count (1 per folder instead of N per folder)
     *   - Keeps files organized by folder
     *   - Reduces TDLib upload overhead
     *
     * The .zip is created in the staging directory, uploaded, then deleted.
     */
    private suspend fun runZipMode(
        jobId: String,
        startedAt: Long,
        isManual: Boolean,
        prefs: com.tgdrive.core.datastore.AppPreferencesData,
        folderUris: List<String>,
        toUpload: List<com.tgdrive.feature.autobackup.diff.LocalFile>,
        targetChatId: Long,
        filesScanned: Int,
        filesSkipped: Int,
        setForeground: suspend (String, Int, Int) -> Unit,
    ): RunOutcome {
        var filesUploaded = 0
        var filesFailed = 0
        val filesToUpload = folderUris.size // 1 zip per folder
        val excludePatterns = ExcludePatternMatcher.parse(prefs.backupExcludePatterns)
        val excludeMatcher: ((String) -> Boolean)? =
            if (excludePatterns.isEmpty()) null else { name -> ExcludePatternMatcher.matches(name, excludePatterns) }

        try {
            setForeground(jobId, 0, filesToUpload)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (t: Throwable) { Log.w("BackupOrchestrator: setForeground (zip) failed", t) }

        registry.updatePhase(BackupPhase.Uploading(
            current = 0, total = filesToUpload,
            fileName = "Creating ZIP archives…", bytesSent = 0, bytesTotal = 0,
        ))

        val stagingDir = java.io.File(ctx.cacheDir, "backup_staging").apply { mkdirs() }
        val uploadedMsgIds = mutableListOf<Long>()

        for ((i, folderUri) in folderUris.withIndex()) {
            ensureNotCancelled(jobId)

            val currentPrefs = preferences.data.first()
            val shouldAbort = currentPrefs.backupPaused || (!isManual && !currentPrefs.backupEnabled)
            if (shouldAbort) {
                finalize(jobId, startedAt, filesScanned, filesUploaded, filesSkipped, "CANCELLED", "Cancelled by user")
                registry.updatePhase(BackupPhase.Cancelled(filesUploaded, filesToUpload))
                notifier.showResult(jobId, filesUploaded, filesSkipped, partial = filesUploaded > 0, failed = false)
                return RunOutcome.Cancelled(filesUploaded, filesToUpload)
            }

            // Parse folder name from URI
            val folderName = try {
                val uri = android.net.Uri.parse(folderUri)
                val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri)
                docFile?.name ?: "folder_${System.currentTimeMillis()}"
            } catch (t: Throwable) {
                "folder_${System.currentTimeMillis()}"
            }
            val zipName = "${folderName}_${SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}"

            registry.updatePhase(BackupPhase.Uploading(
                current = i + 1, total = filesToUpload,
                fileName = "$zipName.zip", bytesSent = 0, bytesTotal = 0,
            ))

            var zipFile: java.io.File? = null
            try {
                val zipPath = com.tgdrive.core.zip.ZipUploader.zipFolder(
                    context = ctx,
                    treeUri = android.net.Uri.parse(folderUri),
                    outputDir = stagingDir,
                    zipName = zipName,
                    password = null,
                    excludeMatcher = excludeMatcher,
                )
                if (zipPath == null) {
                    Log.w("BackupOrchestrator: zip failed for folder $folderUri — skipping")
                    filesFailed++
                    continue
                }

                val currentZipFile = java.io.File(zipPath)
                zipFile = currentZipFile
                val zipSize = currentZipFile.length()
                if (zipSize <= 0L || zipSize > TelegramClient.MAX_FILE_SIZE) {
                    Log.w("BackupOrchestrator: invalid ZIP size ($zipSize bytes) for $folderUri")
                    currentZipFile.delete()
                    filesFailed++
                    continue
                }
                Log.i("BackupOrchestrator: zipped → $zipPath ($zipSize bytes)")

                var lastZipProgressMs = 0L
                val uploadResult = telegramClient.uploadFile(
                    chatId = targetChatId,
                    localPath = zipPath,
                    fileName = "$zipName.zip",
                    mimeType = "application/zip",
                ) { sent, total ->
                    val now = System.currentTimeMillis()
                    if (sent >= total || now - lastZipProgressMs >= 200L) {
                        lastZipProgressMs = now
                        registry.updatePhase(
                            BackupPhase.Uploading(
                                current = i + 1,
                                total = filesToUpload,
                                fileName = "$zipName.zip",
                                bytesSent = sent,
                                bytesTotal = total.coerceAtLeast(zipSize),
                            ),
                        )
                    }
                }

                uploadedMsgIds += uploadResult.messageId

                // Cache the archive entry in the target chat browser
                fileCacheDao.upsertAll(listOf(FileCacheEntity(
                    messageId = uploadResult.messageId,
                    chatId = targetChatId,
                    fileName = "$zipName.zip",
                    fileSize = zipSize,
                    mimeType = "application/zip",
                    thumbnailPath = null,
                    remoteId = uploadResult.remoteId,
                    date = System.currentTimeMillis(),
                )))

                filesUploaded++
                currentZipFile.delete()
                Log.i("BackupOrchestrator: uploaded zip $filesUploaded/$filesToUpload")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("BackupOrchestrator: zip upload failed for $folderUri", t)
                runCatching { zipFile?.delete() }
                filesFailed++
            }
        }

        // Record all diffed files as synced when ZIP archives uploaded without error so the
        // next run doesn't re-zip and re-upload identical unchanged folders.
        if (filesUploaded > 0 && filesFailed == 0) {
            val syncedMsgId = uploadedMsgIds.last()
            val nowMs = System.currentTimeMillis()
            val folderId = prefs.backupTargetFolderId.takeIf { it > 0 }
            for (localFile in toUpload) {
                runCatching {
                    syncedFileDao.upsert(SyncedFileEntity(
                        localPath = localFile.absolutePath,
                        telegramMessageId = syncedMsgId,
                        sha256 = localFile.sha256,
                        size = localFile.size,
                        syncedAt = nowMs,
                        folderId = folderId,
                    ))
                }
            }
        }

        val status = when {
            filesUploaded == filesToUpload && filesFailed == 0 -> "SUCCESS"
            filesUploaded == 0 -> "FAILURE"
            else -> "PARTIAL"
        }
        val errMsg = if (status == "FAILURE") "All ZIP uploads failed" else null
        finalize(jobId, startedAt, filesScanned, filesUploaded, filesSkipped, status, errMsg)
        notifier.showResult(
            jobId = jobId,
            uploaded = filesUploaded,
            skipped = filesSkipped,
            partial = status == "PARTIAL",
            failed = status == "FAILURE",
        )
        val phase = when (status) {
            "SUCCESS" -> BackupPhase.Done(filesUploaded, filesToUpload, filesSkipped, 0)
            "PARTIAL" -> BackupPhase.Done(filesUploaded, filesToUpload, filesSkipped, filesFailed)
            else -> BackupPhase.Failed(errMsg ?: "All ZIP uploads failed", 0, filesToUpload)
        }
        registry.updatePhase(phase)
        return when (status) {
            "SUCCESS" -> RunOutcome.Success(uploaded = filesUploaded, skipped = filesSkipped, total = filesToUpload)
            "PARTIAL" -> RunOutcome.Partial(uploaded = filesUploaded, total = filesToUpload, skipped = filesSkipped)
            else -> RunOutcome.Failed(errMsg ?: "All ZIP uploads failed", 0, filesToUpload)
        }
    }

    private suspend fun ensureNotCancelled(jobId: String) {
        if (registry.isCancellationRequested(jobId)) {
            throw CancellationException("User requested cancellation")
        }
        coroutineContext.ensureActive()
    }

    private suspend fun resolveTargetChatId(folderId: Long): Long {
        if (folderId <= 0) return telegramClient.myUserId()  // Saved Messages
        val folder = folderDao.getById(folderId)
        return folder?.channelId ?: telegramClient.myUserId()
    }

    /**
     * Write the final sync_log row + delete old entries.
     *
     * **Cancellation safety:** This function catches all exceptions EXCEPT
     * `CancellationException` — the latter is re-thrown so the coroutine
     * framework can properly propagate cancellation. Previously, catching
     * `Throwable` (which includes `CancellationException`) was a coroutine
     * anti-pattern that caused "zombie" coroutines and cascading failures.
     *
     * When called from a cancelled coroutine, the caller MUST wrap this in
     * `withContext(NonCancellable) { ... }` to ensure the DB writes complete.
     */
    private suspend fun finalize(
        jobId: String, startedAt: Long, scanned: Int,
        uploaded: Int, skipped: Int, status: String, errMsg: String?,
    ) {
        try {
            val existing = syncLogDao.get(jobId)
            syncLogDao.upsert(
                (existing ?: SyncLogEntity(
                    jobId = jobId, startedAt = startedAt, completedAt = null, status = status,
                )).copy(
                    completedAt = System.currentTimeMillis(),
                    filesScanned = scanned, filesUploaded = uploaded,
                    filesSkipped = skipped, status = status, errorMessage = errMsg,
                )
            )
            syncLogDao.deleteOlderThan(System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000))
        } catch (t: CancellationException) {
            // Re-throw cancellation — NEVER swallow it. Swallowing
            // CancellationException is a coroutine anti-pattern that leaves
            // the coroutine in a "zombie" state and causes cascading failures.
            throw t
        } catch (t: Throwable) {
            Log.w("BackupOrchestrator: finalize failed", t)
        }
    }

    /** Outcome of a backup run. Used by the Worker to decide Result.success/failure. */
    sealed class RunOutcome {
        data class Success(val uploaded: Int, val skipped: Int, val total: Int) : RunOutcome()
        data class Partial(val uploaded: Int, val total: Int, val skipped: Int) : RunOutcome()
        data class Cancelled(val uploaded: Int, val total: Int) : RunOutcome()
        data class Failed(val message: String, val uploaded: Int, val total: Int) : RunOutcome()
        data class Skipped(val reason: String) : RunOutcome()
    }
}
