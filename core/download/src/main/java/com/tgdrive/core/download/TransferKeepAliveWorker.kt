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
                val (label, progressPct) = formatTransferSummary(activeUl, activeDl)
                runCatching {
                    setForeground(
                        buildForegroundInfo(
                            text = label,
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
        val launchPi = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
            ?.let { launchIntent ->
                PendingIntent.getActivity(
                    applicationContext,
                    REQ_OPEN_APP,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        val notif = NotificationCompat.Builder(applicationContext, "download.progress")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("TelegramDrive Transfers")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (launchPi != null) setContentIntent(launchPi)
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
        private const val REQ_OPEN_APP = 50010
        private const val REQ_PAUSE = 50011
        private const val REQ_STOP = 50012

        internal fun formatTransferSummary(
            activeUploads: List<UploadItem>,
            activeDownloads: List<DownloadItem>,
        ): Pair<String, Int> {
            val activeCount = activeUploads.size + activeDownloads.size
            if (activeCount == 0) return "Preparing transfers…" to 0

            var weightedKnownBytes = 0L
            var weightedDoneBytes = 0.0
            var fallbackSum = 0f
            var fallbackCount = 0
            for (ul in activeUploads) {
                val p = ul.progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                if (ul.totalBytes > 0L) {
                    weightedKnownBytes += ul.totalBytes
                    weightedDoneBytes += p * ul.totalBytes.toDouble()
                } else {
                    fallbackSum += p
                    fallbackCount++
                }
            }
            for (dl in activeDownloads) {
                val p = dl.progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                if (dl.totalBytes > 0L) {
                    weightedKnownBytes += dl.totalBytes
                    weightedDoneBytes += p * dl.totalBytes.toDouble()
                } else {
                    fallbackSum += p
                    fallbackCount++
                }
            }
            val pct = when {
                weightedKnownBytes > 0L ->
                    ((weightedDoneBytes / weightedKnownBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
                fallbackCount > 0 ->
                    ((fallbackSum / fallbackCount) * 100f).toInt().coerceIn(0, 100)
                else -> 0
            }

            val label = when {
                activeCount == 1 && activeUploads.size == 1 -> {
                    val ul = activeUploads.first()
                    val verb = when (ul.status) {
                        UploadStatus.PACKING -> "Packing"
                        UploadStatus.ENCRYPTING -> "Encrypting"
                        UploadStatus.PREPARING -> "Preparing"
                        UploadStatus.QUEUED -> "Queued"
                        else -> "Uploading"
                    }
                    "$verb ${ul.fileName} ($pct%)"
                }
                activeCount == 1 && activeDownloads.size == 1 -> {
                    val dl = activeDownloads.first()
                    "Downloading ${dl.fileName} ($pct%)"
                }
                activeUploads.isNotEmpty() && activeDownloads.isNotEmpty() -> {
                    "${activeUploads.size} upload(s), ${activeDownloads.size} download(s) ($pct%)"
                }
                activeUploads.isNotEmpty() -> {
                    "Uploading ${activeUploads.size} file(s) ($pct%)"
                }
                else -> {
                    "Downloading ${activeDownloads.size} file(s) ($pct%)"
                }
            }
            return label to pct
        }

        fun ensureRunning(ctx: Context) {
            runCatching {
                val req = OneTimeWorkRequestBuilder<TransferKeepAliveWorker>().build()
                WorkManager.getInstance(ctx)
                    .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, req)
            }
        }
    }
}
