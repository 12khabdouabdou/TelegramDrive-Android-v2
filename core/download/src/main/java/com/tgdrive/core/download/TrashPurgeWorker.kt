package com.tgdrive.core.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tgdrive.core.common.Log
import com.tgdrive.core.download.sync.CloudSyncManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Daily periodic WorkManager job that permanently purges Trash entries older than 30 days
 * from both Telegram channels and local Room cache.
 */
@HiltWorker
class TrashPurgeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val cloudSyncManager: CloudSyncManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            cloudSyncManager.purgeExpiredTrashNow()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("TrashPurgeWorker: failed", t)
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "tgdrive_trash_daily_purge"

        fun scheduleDaily(context: Context) {
            runCatching {
                val req = PeriodicWorkRequestBuilder<TrashPurgeWorker>(1, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    req,
                )
            }.onFailure { t ->
                Log.w("TrashPurgeWorker: failed to schedule", t)
            }
        }
    }
}
