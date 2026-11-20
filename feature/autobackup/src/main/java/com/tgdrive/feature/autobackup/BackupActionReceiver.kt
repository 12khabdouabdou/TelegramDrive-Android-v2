package com.tgdrive.feature.autobackup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tgdrive.core.common.Log
import com.tgdrive.core.datastore.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the action buttons on AutoBackup notifications:
 *
 *   - [ACTION_PAUSE]: Sets `backupPaused = true` in preferences. The
 *     orchestrator polls prefs in its upload loop and exits cleanly.
 *
 *   - [ACTION_CANCEL]: Requests soft-cancellation via [BackupScheduler.cancelActive].
 *     The orchestrator polls [ActiveBackupRegistry.isCancellationRequested]
 *     and exits cleanly. We also fall back to a hard WorkManager cancel
 *     after 5 seconds (in case the soft cancel is stuck — e.g. on a long
 *     TDLib call that doesn't yield).
 *
 *   - [ACTION_RETRY]: Enqueues a new one-shot backup with the same jobId.
 *
 * Per spec §8 + §F-05.
 */
@AndroidEntryPoint
class BackupActionReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: BackupScheduler
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var registry: ActiveBackupRegistry

    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        when (intent.action) {
            ACTION_PAUSE -> scope.launch {
                try {
                    preferences.update { it.copy(backupPaused = true) }
                    // Soft-cancel too, so the in-flight run exits and the
                    // user sees the Paused state immediately.
                    scheduler.cancelActive(force = false)
                    Log.i("BackupActionReceiver: paused $jobId")
                } finally { pendingResult.finish(); scope.cancel() }
            }

            ACTION_CANCEL -> {
                scheduler.cancelActive(force = false)
                Log.i("BackupActionReceiver: cancel requested for $jobId")
                // Schedule a hard-cancel fallback while keeping goAsync() alive.
                scope.launch {
                    try {
                        kotlinx.coroutines.delay(5_000)
                        val cur = registry.active.value
                        if (cur != null && cur.jobId == jobId && cur.phase.isActive) {
                            Log.w("BackupActionReceiver: soft cancel didn't take effect after 5s — forcing")
                            scheduler.cancelActive(force = true)
                        }
                    } finally {
                        runCatching { pendingResult.finish() }
                        scope.cancel()
                    }
                }
            }

            ACTION_RETRY -> {
                try { scheduler.runNow(manual = true) }
                finally { pendingResult.finish(); scope.cancel() }
            }

            else -> { pendingResult.finish(); scope.cancel() }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.tgdrive.backup.action.PAUSE"
        const val ACTION_CANCEL = "com.tgdrive.backup.action.CANCEL"
        const val ACTION_RETRY = "com.tgdrive.backup.action.RETRY"
        const val EXTRA_JOB_ID = "job_id"
    }
}
