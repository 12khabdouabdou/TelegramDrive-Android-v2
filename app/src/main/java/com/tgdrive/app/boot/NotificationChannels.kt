package com.tgdrive.app.boot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.getSystemService
import com.tgdrive.app.R

/**
 * Centralised notification channel IDs. All foreground services and result
 * notifications must post to one of these channels.
 *
 * Channels are created on app launch via [ensureCreated] — Android no-ops if
 * the channel already exists, so this is safe to call repeatedly.
 */
object NotificationChannels {

    const val BACKUP_PROGRESS = "backup.progress"
    const val BACKUP_RESULT = "backup.result"
    const val DOWNLOAD_PROGRESS = "download.progress"
    const val UPLOAD_PROGRESS = "upload.progress"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService<NotificationManager>() ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                BACKUP_PROGRESS,
                context.getString(R.string.channel_backup_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_backup_progress_desc)
                setShowBadge(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                BACKUP_RESULT,
                context.getString(R.string.channel_backup_result),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_backup_result_desc)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                DOWNLOAD_PROGRESS,
                context.getString(R.string.channel_download_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                UPLOAD_PROGRESS,
                context.getString(R.string.channel_upload_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
        )
    }
}
