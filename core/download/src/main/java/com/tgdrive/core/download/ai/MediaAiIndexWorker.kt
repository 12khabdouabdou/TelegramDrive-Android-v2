package com.tgdrive.core.download.ai

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tgdrive.core.common.Log
import com.tgdrive.core.telegram.TelegramClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Incremental background WorkManager job that extracts EXIF places, OCR text,
 * PDF/text snippets, audio ID3 tags, and ML Kit labels locally on-device
 * (including strictly local RAM-only thumbnail indexing for PassVault items).
 */
@HiltWorker
class MediaAiIndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val mediaAiIndexer: MediaAiIndexer,
    private val telegramClient: TelegramClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val sysId = runCatching { telegramClient.getOrCreateSystemChannel() }.getOrNull()
            val excluded = sysId?.let { setOf(it) } ?: emptySet()
            mediaAiIndexer.scanLocalExifLocations(excludedChatIds = excluded)
            mediaAiIndexer.indexUnindexedBatch(
                excludedChatIds = excluded,
                maxCount = BATCH_SIZE,
                includeLocked = true,
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("MediaAiIndexWorker: failed", t)
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "tgdrive_media_ai_periodic"
        private const val ONESHOT_WORK_NAME = "tgdrive_media_ai_oneshot"
        private const val BATCH_SIZE = 40

        fun schedulePeriodic(context: Context) {
            runCatching {
                val req = PeriodicWorkRequestBuilder<MediaAiIndexWorker>(6, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .setRequiresDeviceIdle(true)
                            .build(),
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    req,
                )
            }.onFailure { t ->
                Log.w("MediaAiIndexWorker: failed to schedule periodic", t)
            }
        }

        fun enqueueSoon(context: Context) {
            runCatching {
                val req = OneTimeWorkRequestBuilder<MediaAiIndexWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    ONESHOT_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    req,
                )
            }.onFailure { t ->
                Log.w("MediaAiIndexWorker: failed to enqueue one-shot", t)
            }
        }
    }
}
