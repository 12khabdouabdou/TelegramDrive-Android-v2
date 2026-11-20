package com.tgdrive.feature.autobackup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tgdrive.feature.autobackup.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts notifications for the AutoBackup system:
 *
 * - Foreground service notification during a backup (indeterminate progress
 *   bar showing "Backing up X of Y files")
 * - Result notification after a backup finishes (success/partial/failed)
 *
 * The "Pause" / "Cancel" / "Retry" actions are wired in [BackupActionReceiver].
 *
 * Per spec §F-05 + §8.
 *
 * Channel IDs are defined in :app's [com.tgdrive.app.boot.NotificationChannels];
 * we hardcode the strings here to avoid a backward dependency.
 */
@Singleton
class BackupNotifier @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    private val channelProgress = "backup.progress"
    private val channelResult = "backup.result"

    fun showForeground(jobId: String, count: Int, total: Int) {
        try {
            val title = if (total <= 0) {
                "Scanning folders…"
            } else {
                ctx.getString(R.string.notif_backup_running, count, total)
            }
            val notif = NotificationCompat.Builder(ctx, channelProgress)
                .setContentTitle(title)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(total.coerceAtLeast(0), count, total <= 0)
                .addAction(android.R.drawable.ic_media_pause, ctx.getString(R.string.action_pause), pauseIntent(jobId))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, ctx.getString(R.string.action_cancel), cancelIntent(jobId))
                .build()
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID_FOREGROUND, notif)
        } catch (t: Throwable) {
            com.tgdrive.core.common.Log.w("BackupNotifier: showForeground failed", t)
        }
    }

    fun updateProgress(jobId: String, count: Int, total: Int) {
        showForeground(jobId, count, total)
    }

    /** Build a ForegroundInfo for WorkManager's setForeground() — required on Android 14+. */
    fun buildForegroundInfo(jobId: String, count: Int, total: Int): androidx.work.ForegroundInfo {
        val title = if (total <= 0) {
            "Scanning folders…"
        } else {
            ctx.getString(R.string.notif_backup_running, count, total)
        }
        return androidx.work.ForegroundInfo(
            NOTIF_ID_FOREGROUND,
            NotificationCompat.Builder(ctx, channelProgress)
                .setContentTitle(title)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(total.coerceAtLeast(0), count, total <= 0)
                .addAction(android.R.drawable.ic_media_pause, ctx.getString(R.string.action_pause), pauseIntent(jobId))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, ctx.getString(R.string.action_cancel), cancelIntent(jobId))
                .build(),
            if (android.os.Build.VERSION.SDK_INT >= 34) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    /** Cancel the foreground notification. Called when a worker finishes (success or fail). */
    fun cancelForeground() {
        try { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID_FOREGROUND) }
        catch (_: Throwable) {}
    }

    fun showResult(jobId: String, uploaded: Int, skipped: Int, partial: Boolean, failed: Boolean) {
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID_FOREGROUND)
            val (title, intent) = when {
                failed -> ctx.getString(R.string.notif_backup_failed) to retryIntent(jobId)
                partial -> ctx.getString(R.string.notif_backup_partial, uploaded, 0) to retryIntent(jobId)
                else -> ctx.getString(R.string.notif_backup_success, uploaded, skipped) to null
            }
            val builder = NotificationCompat.Builder(ctx, channelResult)
                .setContentTitle(title)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setAutoCancel(true)
            intent?.let { builder.setContentIntent(it) }
            if (failed || partial) {
                builder.addAction(android.R.drawable.ic_menu_rotate, ctx.getString(R.string.action_retry), retryIntent(jobId))
            }
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID_RESULT, builder.build())
        } catch (t: Throwable) {
            com.tgdrive.core.common.Log.w("BackupNotifier: showResult failed", t)
        }
    }

    private fun pauseIntent(jobId: String): PendingIntent {
        val intent = Intent(ctx, BackupActionReceiver::class.java).apply {
            action = BackupActionReceiver.ACTION_PAUSE
            putExtra(BackupActionReceiver.EXTRA_JOB_ID, jobId)
        }
        return PendingIntent.getBroadcast(
            ctx, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun cancelIntent(jobId: String): PendingIntent {
        val intent = Intent(ctx, BackupActionReceiver::class.java).apply {
            action = BackupActionReceiver.ACTION_CANCEL
            putExtra(BackupActionReceiver.EXTRA_JOB_ID, jobId)
        }
        return PendingIntent.getBroadcast(
            ctx, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun retryIntent(jobId: String): PendingIntent {
        val intent = Intent(ctx, BackupActionReceiver::class.java).apply {
            action = BackupActionReceiver.ACTION_RETRY
            putExtra(BackupActionReceiver.EXTRA_JOB_ID, jobId)
        }
        return PendingIntent.getBroadcast(
            ctx, 2, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val NOTIF_ID_FOREGROUND = 0xA001
        const val NOTIF_ID_RESULT = 0xA002
    }
}
