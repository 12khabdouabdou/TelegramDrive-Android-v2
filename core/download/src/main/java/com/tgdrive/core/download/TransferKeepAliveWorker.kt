package com.tgdrive.core.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tgdrive.core.common.Log
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Foreground WorkManager worker that keeps the Android process alive while downloads
 * or uploads are running in the background (preventing OS process kills mid-transfer).
 */
@HiltWorker
class TransferKeepAliveWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadManager: DownloadManager,
    private val uploadTracker: UploadTracker,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            setForeground(buildForegroundInfo("Preparing transfers…", 0, 0, true))
        } catch (t: Throwable) {
            Log.w("TransferKeepAliveWorker: setForeground failed (continuing)", t)
        }

        // Brief grace window for enqueue/beginUpload state propagation, then wait until idle
        delay(250L)
        var lastUpdateMs = 0L
        var lastActiveCount = -1
        combine(downloadManager.state, uploadTracker.state) { dlState, ulState ->
            val activeDl = dlState.items.filter {
                it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
            }
            val activeUl = ulState.items.filter { it.status.isActive }
            val activeCount = activeDl.size + activeUl.size
            val now = System.currentTimeMillis()
            if (activeCount > 0 && (activeCount != lastActiveCount || now - lastUpdateMs >= 400L)) {
                lastUpdateMs = now
                lastActiveCount = activeCount
                val label = activeUl.firstOrNull()?.let { ul ->
                    val verb = when (ul.status) {
                        UploadStatus.PACKING -> "Packing"
                        UploadStatus.ENCRYPTING -> "Encrypting"
                        UploadStatus.PREPARING -> "Preparing"
                        UploadStatus.QUEUED -> "Queued"
                        else -> "Uploading"
                    }
                    "$verb ${ul.fileName} (${(ul.progress * 100f).toInt().coerceIn(0, 100)}%)"
                } ?: activeDl.firstOrNull()?.let {
                    "Downloading ${it.fileName} (${(it.progress * 100f).toInt().coerceIn(0, 100)}%)"
                } ?: "$activeCount transfer(s) active"
                val progressPct = activeUl.firstOrNull()?.let { (it.progress * 100f).toInt().coerceIn(0, 100) }
                    ?: activeDl.firstOrNull()?.let { (it.progress * 100f).toInt().coerceIn(0, 100) }
                    ?: 0
                runCatching {
                    setForeground(
                        buildForegroundInfo(
                            text = if (activeCount > 1) "$label • $activeCount active" else label,
                            progress = progressPct,
                            max = 100,
                            indeterminate = progressPct == 0,
                            hasActiveTransfers = true,
                        ),
                    )
                }
            }
            activeCount
        }.first { it == 0 }

        return Result.success()
    }

    private fun buildForegroundInfo(
        text: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
        hasActiveTransfers: Boolean = false,
    ): ForegroundInfo {
        val pausePi = PendingIntent.getBroadcast(
            applicationContext,
            REQ_PAUSE,
            Intent(applicationContext, TransferActionReceiver::class.java).apply {
                action = TransferActionReceiver.ACTION_PAUSE_ALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getBroadcast(
            applicationContext,
            REQ_STOP,
            Intent(applicationContext, TransferActionReceiver::class.java).apply {
                action = TransferActionReceiver.ACTION_CANCEL_ALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(applicationContext, "download.progress")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("TelegramDrive Transfers")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (max > 0) setProgress(max, progress, indeterminate)
                if (hasActiveTransfers) {
                    addAction(0, "Pause", pausePi)
                }
                addAction(0, "Stop", stopPi)
            }
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val UNIQUE_NAME = "tgdrive_transfer_keepalive"
        private const val NOTIF_ID = 50002
        private const val REQ_PAUSE = 50011
        private const val REQ_STOP = 50012

        fun ensureRunning(ctx: Context) {
            runCatching {
                val req = OneTimeWorkRequestBuilder<TransferKeepAliveWorker>().build()
                WorkManager.getInstance(ctx)
                    .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, req)
            }
        }
    }
}
