package com.tgdrive.core.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tgdrive.core.common.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Immutable snapshot of the audio player's state.
 */
data class AudioState(
    val title: String? = null,
    val isPlaying: Boolean = false,
    val path: String? = null,
)

/**
 * Process-wide audio player controller backed by Media3 [ExoPlayer] with
 * automatic audio focus management and noisy (headphone-unplug) handling.
 */
@Singleton
class AudioPlayerController @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null

    @Volatile
    private var cachedPositionMs: Long = 0L

    @Volatile
    private var cachedDurationMs: Long = 1L

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        exoPlayer?.let { return it }
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val created = ExoPlayer.Builder(appContext)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        created.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val dur = created.duration
                if (dur > 0L && dur != C.TIME_UNSET) {
                    cachedDurationMs = dur
                }
                cachedPositionMs = created.currentPosition.coerceAtLeast(0L)
                _state.update { cur ->
                    if (cur.path != null) cur.copy(isPlaying = isPlaying) else cur
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val dur = created.duration
                if (dur > 0L && dur != C.TIME_UNSET) {
                    cachedDurationMs = dur
                }
                if (playbackState == Player.STATE_ENDED) {
                    cachedPositionMs = cachedDurationMs
                    _state.update { cur -> cur.copy(isPlaying = false) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("AudioPlayerController: ExoPlayer error", error)
                cachedPositionMs = 0L
                cachedDurationMs = 1L
                _state.value = AudioState()
            }
        })
        exoPlayer = created
        return created
    }

    /** Play an audio file. Stops any current playback first. */
    fun playAudio(localPath: String, fileName: String) {
        _state.value = AudioState(title = fileName, isPlaying = false, path = localPath)
        cachedPositionMs = 0L
        cachedDurationMs = 1L
        runOnMain {
            try {
                val player = ensurePlayer()
                val mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(localPath)))
                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
            } catch (t: Throwable) {
                Log.w("playAudio failed", t)
                _state.value = AudioState()
            }
        }
    }

    /** Toggle play/pause. No-op if nothing is loaded. */
    fun togglePlayPause() {
        if (_state.value.path == null) return
        runOnMain {
            val player = exoPlayer ?: return@runOnMain
            try {
                if (player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(0L)
                    player.play()
                } else if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            } catch (t: Throwable) {
                Log.w("AudioPlayerController: play/pause failed", t)
            }
        }
    }

    /** Current playback position in ms, or 0 if not ready. */
    fun currentPosition(): Int {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            exoPlayer?.let { player ->
                val pos = player.currentPosition.coerceAtLeast(0L)
                cachedPositionMs = pos
            }
        }
        return cachedPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** Total duration in ms, or 1 if unknown. */
    fun duration(): Int {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            exoPlayer?.let { player ->
                val dur = player.duration
                if (dur > 0L && dur != C.TIME_UNSET) {
                    cachedDurationMs = dur
                }
            }
        }
        return cachedDurationMs.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** Seek playback to [ms] clamped to non-negative values. */
    fun seekTo(ms: Int) {
        val safeMs = ms.coerceAtLeast(0).toLong()
        cachedPositionMs = safeMs
        runOnMain {
            try {
                exoPlayer?.seekTo(safeMs)
            } catch (_: Throwable) {}
        }
    }

    /** Stop and release the ExoPlayer instance. */
    fun release() {
        cachedPositionMs = 0L
        cachedDurationMs = 1L
        _state.value = AudioState()
        runOnMain {
            try {
                exoPlayer?.release()
            } catch (_: Throwable) {}
            exoPlayer = null
        }
    }
}
