package com.tgdrive.feature.autobackup.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tgdrive.core.common.Log
import com.tgdrive.feature.autobackup.ActiveBackupRegistry
import com.tgdrive.feature.autobackup.BackupNotifier
import com.tgdrive.feature.autobackup.BackupOrchestrator
import com.tgdrive.feature.autobackup.StagingManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * WorkManager worker that performs one AutoBackup job.
 *
 * This is now a thin wrapper around [BackupOrchestrator]. All scan/diff/
 * upload/finalize logic lives in the orchestrator, which is shared between
 * periodic runs (this Worker) and manual runs (also enqueued via the
 * scheduler so they go through the same code path).
 *
 * ## Cancellation
 *
 * The user can cancel via:
 *   1. The notification's "Cancel" action (handled by [BackupActionReceiver]).
 *      This calls [ActiveBackupRegistry.requestCancellation], which the
 *      orchestrator polls at every chunk boundary → throws
 *      CancellationException → exits cleanly → we return Result.failure().
 *   2. WorkManager.cancelAllWorkByTag("autobackup"). This cancels the
 *      coroutine directly → CancellationException propagates → same path.
 *
 * ## Retries
 *
 * WorkManager backs off exponentially (initial 30s, max 5min, max 3 retries).
 * We return Result.failure() for unrecoverable errors (no folders, TDLib
 * not authorized), and Result.success() for SUCCESS/PARTIAL/CANCELLED.
 *
 * Per spec §8 (WorkManager AutoBackup Job Spec) + §F-05.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters,
    private val orchestrator: BackupOrchestrator,
    private val stagingManager: StagingManager,
    private val notifier: BackupNotifier,
    private val registry: ActiveBackupRegistry,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_PROGRESS_COUNT = "count"
        const val KEY_PROGRESS_TOTAL = "total"
        const val KEY_JOB_ID = "job_id"
        const val KEY_IS_MANUAL = "is_manual"
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: java.util.UUID.randomUUID().toString()
        val effectiveJobId = if (jobId == "periodic") "periodic_${System.currentTimeMillis()}" else jobId
        val isManual = inputData.getBoolean(KEY_IS_MANUAL, false)

        Log.i("BackupWorker: starting $effectiveJobId (manual=$isManual, runAttemptCount=$runAttemptCount)")

        // TOP-LEVEL CRASH GUARD: This is the LAST line of defense against
        // hard crashes. Every exception that escapes the orchestrator's
        // internal try/catch is caught here and converted to a Result.failure
        // instead of crashing the app. This is critical because:
        //
        //   1. On Android 14+, an uncaught exception in a WorkManager worker
        //      process can crash the entire app (not just the worker).
        //   2. The user reported "hard crash" — meaning the app closes to
        //      the home screen. This guard ensures that NEVER happens from
        //      the backup path.
        //
        // The guard wraps EVERYTHING — the orchestrator call, the outcome
        // handling, AND the finally block. If even the finally block throws,
        // we catch it and return Result.failure.
        return try {
            val outcome = orchestrator.runBackup(
                jobId = effectiveJobId,
                isManual = isManual,
                setForeground = { jId, count, total ->
                    // CRITICAL: Re-throw CancellationException — never swallow.
                    try {
                        setForeground(notifier.buildForegroundInfo(jId, count, total))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w("BackupWorker: setForeground failed", t)
                        throw t  // Re-throw so the orchestrator can abort with a clear error.
                    }
                },
            )
            when (outcome) {
                is BackupOrchestrator.RunOutcome.Success -> {
                    Log.i("BackupWorker: $effectiveJobId SUCCESS — uploaded=${outcome.uploaded}")
                    Result.success(workDataOf(KEY_JOB_ID to effectiveJobId))
                }
                is BackupOrchestrator.RunOutcome.Partial -> {
                    Log.i("BackupWorker: $effectiveJobId PARTIAL — uploaded=${outcome.uploaded}/${outcome.total}")
                    Result.success(workDataOf(KEY_JOB_ID to effectiveJobId))
                }
                is BackupOrchestrator.RunOutcome.Cancelled -> {
                    Log.i("BackupWorker: $effectiveJobId CANCELLED — uploaded=${outcome.uploaded}/${outcome.total}")
                    Result.success(workDataOf(KEY_JOB_ID to effectiveJobId))
                }
                is BackupOrchestrator.RunOutcome.Skipped -> {
                    Log.i("BackupWorker: $effectiveJobId SKIPPED — ${outcome.reason}")
                    Result.success(workDataOf(KEY_JOB_ID to effectiveJobId))
                }
                is BackupOrchestrator.RunOutcome.Failed -> {
                    Log.w("BackupWorker: $effectiveJobId FAILED — ${outcome.message}")
                    if (runAttemptCount < 3) {
                        Result.retry()
                    } else {
                        try {
                            notifier.showResult(effectiveJobId, outcome.uploaded, 0, partial = false, failed = true)
                        } catch (_: Throwable) {}
                        Result.failure(workDataOf(KEY_JOB_ID to effectiveJobId))
                    }
                }
            }
        } catch (t: CancellationException) {
            Log.i("BackupWorker: $effectiveJobId coroutine cancelled")
            Result.success(workDataOf(KEY_JOB_ID to effectiveJobId))
        } catch (t: Throwable) {
            // LAST RESORT: catch EVERYTHING. This prevents the app from
            // hard-crashing no matter what goes wrong inside the orchestrator.
            Log.e("BackupWorker: $effectiveJobId UNCAUGHT error (crash guard)", t)
            try {
                notifier.showResult(effectiveJobId, 0, 0, partial = false, failed = true)
            } catch (_: Throwable) {}
            if (runAttemptCount < 3) Result.retry()
            else Result.failure(workDataOf(KEY_JOB_ID to effectiveJobId))
        } finally {
            // These cleanup calls are wrapped in their own try/catch so
            // they can't cause a secondary crash.
            try { notifier.cancelForeground() } catch (_: Throwable) {}
            try { stagingManager.purgeStale() } catch (_: Throwable) {}
            try { if (registry.active.value?.phase?.isTerminal != true) registry.finish() } catch (_: Throwable) {}
        }
    }
}
