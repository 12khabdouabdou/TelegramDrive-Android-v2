package com.tgdrive.core.audio

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground media playback service keeping background audio alive and exposing lock-screen
 * controls via [AudioMediaNotification] under Android 14+ `mediaPlayback` FGS rules.
 */
@AndroidEntryPoint
class AudioPlaybackService : Service() {

    @Inject lateinit var controller: AudioPlayerController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = controller.state.value
        val notif = controller.currentNotification()
        if (state.path != null && notif != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    AudioMediaNotification.NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(AudioMediaNotification.NOTIFICATION_ID, notif)
            }
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }
}
