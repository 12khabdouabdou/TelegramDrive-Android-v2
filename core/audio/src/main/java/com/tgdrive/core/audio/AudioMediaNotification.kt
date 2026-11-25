package com.tgdrive.core.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import com.tgdrive.core.common.Log

/**
 * Owns the active [MediaSession] and lock-screen / shade [Notification.MediaStyle]
 * controls for background audio playback.
 */
internal class AudioMediaNotification(
    private val appContext: Context,
    private val onPlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onStop: () -> Unit,
) {

    private var mediaSession: MediaSession? = null
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAY_PAUSE -> onPlayPause()
                ACTION_NEXT -> onNext()
                ACTION_PREVIOUS -> onPrevious()
                ACTION_STOP -> onStop()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun ensureInitialized(): MediaSession? {
        mediaSession?.let { return it }
        return runCatching {
            ensureChannel()
            val session = MediaSession(appContext, SESSION_TAG).apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() = onPlayPause()
                    override fun onPause() = onPlayPause()
                    override fun onSkipToNext() = onNext()
                    override fun onSkipToPrevious() = onPrevious()
                    override fun onStop() = this@AudioMediaNotification.onStop()
                })
                isActive = true
            }
            if (!receiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(ACTION_PLAY_PAUSE)
                    addAction(ACTION_NEXT)
                    addAction(ACTION_PREVIOUS)
                    addAction(ACTION_STOP)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    appContext.registerReceiver(receiver, filter)
                }
                receiverRegistered = true
            }
            mediaSession = session
            session
        }.onFailure {
            Log.w("AudioMediaNotification: init failed", it)
        }.getOrNull()
    }

    fun update(state: AudioState, positionMs: Long, durationMs: Long): Notification? {
        if (state.path == null || state.title.isNullOrBlank()) {
            dismiss()
            return null
        }
        var built: Notification? = null
        runCatching {
            val session = ensureInitialized() ?: return null
            val safeDuration = durationMs.coerceAtLeast(0L)
            val safePosition = positionMs.coerceIn(0L, safeDuration.coerceAtLeast(1L))
            val pbState = if (state.isPlaying) {
                PlaybackState.STATE_PLAYING
            } else {
                PlaybackState.STATE_PAUSED
            }
            val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                (if (state.queue.size > 1) {
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                } else {
                    0L
                })

            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(pbState, safePosition, if (state.isPlaying) state.playbackSpeed else 0f)
                    .build(),
            )
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                    .putString(
                        MediaMetadata.METADATA_KEY_ARTIST,
                        if (state.queue.size > 1) {
                            "${state.currentIndex + 1} / ${state.queue.size} • ${state.playbackSpeed}x"
                        } else {
                            "TelegramDrive Audio"
                        },
                    )
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, safeDuration)
                    .build(),
            )

            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return null
            val playPauseAction = Notification.Action.Builder(
                Icon.createWithResource(
                    appContext,
                    if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                ),
                if (state.isPlaying) "Pause" else "Play",
                actionPendingIntent(ACTION_PLAY_PAUSE, REQ_PLAY_PAUSE),
            ).build()

            val prevAction = Notification.Action.Builder(
                Icon.createWithResource(appContext, android.R.drawable.ic_media_previous),
                "Previous",
                actionPendingIntent(ACTION_PREVIOUS, REQ_PREV),
            ).build()

            val nextAction = Notification.Action.Builder(
                Icon.createWithResource(appContext, android.R.drawable.ic_media_next),
                "Next",
                actionPendingIntent(ACTION_NEXT, REQ_NEXT),
            ).build()

            val stopAction = Notification.Action.Builder(
                Icon.createWithResource(appContext, android.R.drawable.ic_menu_close_clear_cancel),
                "Stop",
                actionPendingIntent(ACTION_STOP, REQ_STOP),
            ).build()

            val style = Notification.MediaStyle()
                .setMediaSession(session.sessionToken)
            if (state.queue.size > 1) {
                style.setShowActionsInCompactView(0, 1, 2)
            } else {
                style.setShowActionsInCompactView(0, 1)
            }

            val builder = Notification.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(state.title)
                .setContentText(
                    if (state.queue.size > 1) {
                        "Track ${state.currentIndex + 1} of ${state.queue.size} (${state.playbackSpeed}x)"
                    } else {
                        "${state.playbackSpeed}x"
                    },
                )
                .setOngoing(state.isPlaying)
                .setOnlyAlertOnce(true)
                .setStyle(style)

            appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.let { launch ->
                val contentIntent = PendingIntent.getActivity(
                    appContext,
                    REQ_CONTENT,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.setContentIntent(contentIntent)
            }

            if (state.queue.size > 1) {
                builder.addAction(prevAction)
                builder.addAction(playPauseAction)
                builder.addAction(nextAction)
            } else {
                builder.addAction(playPauseAction)
                builder.addAction(stopAction)
            }

            built = builder.build()
            nm.notify(NOTIFICATION_ID, built)
        }.onFailure {
            Log.w("AudioMediaNotification: update failed", it)
        }
        return built
    }

    fun dismiss() {
        runCatching {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
            mediaSession?.isActive = false
        }
    }

    fun release() {
        dismiss()
        runCatching {
            mediaSession?.release()
        }
        mediaSession = null
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).setPackage(appContext.packageName)
        return PendingIntent.getBroadcast(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        ensureChannel(appContext)
    }

    internal companion object {
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Audio Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                },
            )
        }

        fun buildFallbackNotification(context: Context): Notification {
            ensureChannel(context)
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("TelegramDrive Audio")
                .setOngoing(true)
                .build()
        }

        const val CHANNEL_ID = "audio.playback"
        const val SESSION_TAG = "TelegramDriveAudioSession"
        const val NOTIFICATION_ID = 4910
        const val ACTION_PLAY_PAUSE = "com.tgdrive.core.audio.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.tgdrive.core.audio.ACTION_NEXT"
        const val ACTION_PREVIOUS = "com.tgdrive.core.audio.ACTION_PREVIOUS"
        const val ACTION_STOP = "com.tgdrive.core.audio.ACTION_STOP"
        const val REQ_CONTENT = 100
        const val REQ_PLAY_PAUSE = 101
        const val REQ_NEXT = 102
        const val REQ_PREV = 103
        const val REQ_STOP = 104
    }
}
