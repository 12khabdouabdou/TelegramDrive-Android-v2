package com.tgdrive.feature.autobackup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tgdrive.core.common.Log
import com.tgdrive.core.datastore.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules a periodic WorkManager job that runs at a specific hour:minute
 * each day (best-effort — WorkManager's minimum interval is 15 minutes).
 * The job uploads files from a specific local folder to a Telegram chat.
 */
@Singleton
class ScheduledUploadScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val preferences: AppPreferences,
) {
    companion object {
        const val WORK_NAME = "scheduled_upload"
    }

    suspend fun rescheduleIfEnabled() {
        try {
            val prefs = preferences.data.first()
            if (!prefs.scheduledUploadEnabled || prefs.scheduledUploadFolderUri.isEmpty()) {
                cancel()
                return
            }

            // Calculate initial delay until next scheduled time
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, prefs.scheduledUploadHour)
                set(Calendar.MINUTE, prefs.scheduledUploadMinute)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= now.timeInMillis) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            val delayMs = target.timeInMillis - now.timeInMillis
            val delayMinutes = delayMs / (60 * 1000)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (prefs.backupWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(prefs.backupChargingOnly)
                .build()

            val req = PeriodicWorkRequestBuilder<com.tgdrive.feature.autobackup.worker.BackupWorker>(
                24,
                TimeUnit.HOURS,
            )
                .addTag("scheduled_upload")
                .setConstraints(constraints)
                .setInitialDelay(delayMinutes.coerceAtLeast(1L), TimeUnit.MINUTES)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString(com.tgdrive.feature.autobackup.worker.BackupWorker.KEY_JOB_ID, "scheduled")
                        .putBoolean(com.tgdrive.feature.autobackup.worker.BackupWorker.KEY_IS_MANUAL, false)
                        .putString(
                            com.tgdrive.feature.autobackup.worker.BackupWorker.KEY_OVERRIDE_FOLDER_URI,
                            prefs.scheduledUploadFolderUri,
                        )
                        .putLong(
                            com.tgdrive.feature.autobackup.worker.BackupWorker.KEY_OVERRIDE_TARGET_ID,
                            prefs.scheduledUploadTargetFolderId,
                        )
                        .build(),
                )
                .build()

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
            Log.i("ScheduledUploadScheduler: scheduled (delay=${delayMinutes}min, target=${prefs.scheduledUploadHour}:${prefs.scheduledUploadMinute})")
        } catch (t: Throwable) {
            Log.w("ScheduledUploadScheduler: reschedule failed", t)
        }
    }

    fun cancel() {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        Log.i("ScheduledUploadScheduler: cancelled")
    }
}
