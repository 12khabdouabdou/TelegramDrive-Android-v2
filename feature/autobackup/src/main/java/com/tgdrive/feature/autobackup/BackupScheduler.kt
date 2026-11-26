package com.tgdrive.feature.autobackup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tgdrive.core.common.Log
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.BackupSchedule
import com.tgdrive.feature.autobackup.watcher.FolderWatcher
import com.tgdrive.feature.autobackup.worker.BackupWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val preferences: AppPreferences,
    private val registry: ActiveBackupRegistry,
    private val folderWatcher: FolderWatcher,
) {

    companion object {
        const val PERIODIC_WORK_NAME = "autobackup_periodic"
        const val ONE_TIME_WORK_NAME = "autobackup_onetime"
        const val CONTENT_TRIGGER_WORK_NAME = "autobackup_content_trigger"
        const val TAG = "autobackup"
    }

    /** Read prefs + (re)schedule accordingly. Call on app start + whenever prefs change. */
    suspend fun rescheduleIfEnabled() {
        val prefs = preferences.data.first()
        if (!prefs.backupEnabled || prefs.backupPaused) {
            cancelPeriodic()
            cancelContentTrigger()
            folderWatcher.stop()
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (prefs.backupWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(prefs.backupChargingOnly)
            .build()

        val intervalMin = when (prefs.backupSchedule) {
            BackupSchedule.DAILY -> 24L * 60
            BackupSchedule.WEEKLY -> 7L * 24 * 60
            BackupSchedule.ON_CHANGE -> {
                cancelPeriodic()
                val armed = armContentUriTriggerIfEnabled()
                if (armed) {
                    folderWatcher.stop()
                } else {
                    folderWatcher.start()
                }
                return
            }
            BackupSchedule.MANUAL -> {
                cancelPeriodic()
                cancelContentTrigger()
                folderWatcher.stop()
                return
            }
        }
        cancelContentTrigger()
        folderWatcher.stop()

        // Minimum WorkManager interval is 15 minutes.
        val interval = intervalMin.coerceAtLeast(15)
        val initialDelayMin = computeInitialDelayMinutes(prefs.backupScheduleHour, prefs.backupScheduleMinute)

        val req = PeriodicWorkRequestBuilder<BackupWorker>(interval, TimeUnit.MINUTES)
            .addTag(TAG)
            .setConstraints(constraints)
            .setInitialDelay(initialDelayMin, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(Data.Builder()
                .putString(BackupWorker.KEY_JOB_ID, "periodic")
                .putBoolean(BackupWorker.KEY_IS_MANUAL, false)
                .build())
            .build()

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
        Log.i("BackupScheduler: scheduled periodic ($interval min)")
    }

    /**
     * Enqueue an immediate one-shot backup.
     *
     * Used by the "Run now" button AND the on-change trigger. Constraints
     * are NOT applied (the user explicitly asked for it now).
     *
     * Returns the jobId, or null if another run is already active (the
     * [ActiveBackupRegistry] refused it).
     */
    fun runNow(manual: Boolean = true): String? {
        if (registry.isAnyActive()) {
            Log.i("BackupScheduler: refusing to enqueue — another run is active")
            return null
        }
        val jobId = if (manual) "manual_${System.currentTimeMillis()}" else "onChange_${System.currentTimeMillis()}"
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag(TAG)
            .addTag(if (manual) "manual" else "onchange")
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(Data.Builder()
                .putString(BackupWorker.KEY_JOB_ID, jobId)
                .putBoolean(BackupWorker.KEY_IS_MANUAL, manual)
                .build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            req,
        )
        Log.i("BackupScheduler: enqueued one-time $jobId (manual=$manual)")
        return jobId
    }

    /**
     * Enqueue a constrained one-time backup triggered by FolderWatcher in ON_CHANGE mode.
     * Respects backupEnabled, backupPaused, Wi-Fi-only, and charging-only preferences.
     */
    suspend fun enqueueOnChange(): String? {
        val prefs = preferences.data.first()
        if (!prefs.backupEnabled || prefs.backupPaused || registry.isAnyActive()) {
            return null
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (prefs.backupWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(prefs.backupChargingOnly)
            .build()
        val jobId = "onChange_${System.currentTimeMillis()}"
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag(TAG)
            .addTag("onchange")
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(Data.Builder()
                .putString(BackupWorker.KEY_JOB_ID, jobId)
                .putBoolean(BackupWorker.KEY_IS_MANUAL, false)
                .build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            req,
        )
        Log.i("BackupScheduler: enqueued constrained on-change $jobId")
        return jobId
    }

    /**
     * Request soft-cancellation of the active job (if any).
     *
     * The orchestrator polls [ActiveBackupRegistry.isCancellationRequested]
     * at every chunk boundary and exits cleanly. This is preferable to
     * `WorkManager.cancelAllWorkByTag(TAG)` because it lets the current
     * file upload finish and the sync_log row to be finalized.
     *
     * If [force] is true, we ALSO cancel the one-time work without cancelling
     * the periodic schedule.
     */
    fun cancelActive(force: Boolean = false) {
        val active = registry.active.value
        if (active != null && active.phase.isActive) {
            registry.requestCancellation(active.jobId)
            Log.i("BackupScheduler: requested soft cancel of ${active.jobId}")
        }
        if (force) {
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork(ONE_TIME_WORK_NAME)
            wm.cancelAllWorkByTag("manual")
            wm.cancelAllWorkByTag("onchange")
        }
    }

    /**
     * Arms a WorkManager ContentUriTrigger worker for background ON_CHANGE detection
     * across selected SAF trees and MediaStore even when the app process is terminated.
     */
    suspend fun armContentUriTriggerIfEnabled(): Boolean {
        val prefs = preferences.data.first()
        if (!prefs.backupEnabled || prefs.backupPaused || prefs.backupSchedule != BackupSchedule.ON_CHANGE) {
            cancelContentTrigger()
            return false
        }
        val builder = Constraints.Builder()
            .setRequiredNetworkType(
                if (prefs.backupWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(prefs.backupChargingOnly)
            .setTriggerContentUpdateDelay(30, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(5, TimeUnit.MINUTES)

        var triggerCount = 0
        for (raw in prefs.backupFolderUris) {
            runCatching {
                val treeUri = Uri.parse(raw)
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                builder.addContentUriTrigger(childUri, true)
                triggerCount++
            }
        }
        runCatching {
            builder.addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            triggerCount++
        }
        runCatching {
            builder.addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            triggerCount++
        }
        runCatching {
            builder.addContentUriTrigger(MediaStore.Files.getContentUri("external"), true)
            triggerCount++
        }
        if (triggerCount == 0) return false

        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag(TAG)
            .addTag("content_trigger")
            .setConstraints(builder.build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                Data.Builder()
                    .putString(BackupWorker.KEY_JOB_ID, "contentTrigger_${System.currentTimeMillis()}")
                    .putBoolean(BackupWorker.KEY_IS_MANUAL, false)
                    .build(),
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            CONTENT_TRIGGER_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            req,
        )
        Log.i("BackupScheduler: armed ContentUriTrigger ($triggerCount URIs)")
        return true
    }

    fun cancelContentTrigger() {
        WorkManager.getInstance(ctx).cancelUniqueWork(CONTENT_TRIGGER_WORK_NAME)
    }

    fun cancelPeriodic() {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC_WORK_NAME)
        Log.i("BackupScheduler: cancelled periodic")
    }

    fun retryFailed(failedPaths: List<String>, sourceJobId: String? = null): String? {
        if (registry.isAnyActive() || (failedPaths.isEmpty() && sourceJobId.isNullOrBlank())) return null
        val jobId = "retry_${System.currentTimeMillis()}"
        val inputBuilder = Data.Builder()
            .putString(BackupWorker.KEY_JOB_ID, jobId)
            .putBoolean(BackupWorker.KEY_IS_MANUAL, true)
        if (!sourceJobId.isNullOrBlank() && failedPaths.size > 20) {
            inputBuilder.putString(BackupWorker.KEY_RETRY_SOURCE_JOB_ID, sourceJobId)
        } else {
            inputBuilder.putStringArray(BackupWorker.KEY_RETRY_PATHS, failedPaths.toTypedArray())
        }
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag(TAG)
            .addTag("manual")
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(inputBuilder.build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            req,
        )
        Log.i("BackupScheduler: enqueued retry-failed $jobId (${failedPaths.size} paths)")
        return jobId
    }

    private fun computeInitialDelayMinutes(targetHour: Int, targetMinute: Int): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, targetHour.coerceIn(0, 23))
            set(Calendar.MINUTE, targetMinute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return ((next.timeInMillis - now.timeInMillis) / (60L * 1000L)).coerceAtLeast(1L)
    }

    fun cancelAll() {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(TAG)
        cancelPeriodic()
        cancelContentTrigger()
        folderWatcher.stop()
    }
}
