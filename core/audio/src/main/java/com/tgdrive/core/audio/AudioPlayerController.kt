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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Single audio item inside the playback queue.
 */
data class AudioTrack(
    val title: String,
    val path: String,
    val chatId: Long = 0L,
    val messageId: Long = 0L,
)

/**
 * Immutable snapshot of the audio player's state, including queue and playback speed.
 */
data class AudioState(
    val title: String? = null,
    val isPlaying: Boolean = false,
    val path: String? = null,
    val queue: List<AudioTrack> = emptyList(),
    val currentIndex: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val repeatAll: Boolean = false,
    val sleepTimerRemainingMs: Long = 0L,
    val sleepTimerEndOfTrack: Boolean = false,
)

/**
 * Pure queue, speed, and repeat logic shared with unit tests.
 */
object AudioQueueLogic {
    val SPEED_STEPS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    fun normalizeSpeed(speed: Float): Float = speed.coerceIn(0.5f, 2.5f)

    fun nextSpeed(current: Float): Float {
        return SPEED_STEPS.firstOrNull { it > current + 0.01f } ?: SPEED_STEPS.first()
    }

    fun deduplicateQueue(tracks: List<AudioTrack>, startIndex: Int): Pair<List<AudioTrack>, Int> {
        val valid = tracks.filter { it.path.isNotBlank() }
        if (valid.isEmpty()) return emptyList<AudioTrack>() to 0
        val clampedStart = startIndex.coerceIn(0, valid.lastIndex)
        val targetPath = valid[clampedStart].path
        val distinct = valid.distinctBy { it.path }
        val mappedIndex = distinct.indexOfFirst { it.path == targetPath }.coerceAtLeast(0)
        return distinct to mappedIndex
    }

    fun nextIndex(currentIndex: Int, size: Int, repeatAll: Boolean): Int? {
        if (size <= 0) return null
        return when {
            currentIndex + 1 < size -> currentIndex + 1
            repeatAll -> 0
            else -> null
        }
    }

    fun previousIndex(currentIndex: Int, size: Int, repeatAll: Boolean): Int? {
        if (size <= 0) return null
        return when {
            currentIndex - 1 >= 0 -> currentIndex - 1
            repeatAll -> size - 1
            else -> null
        }
    }

    fun formatSpeedLabel(speed: Float): String {
        val rounded = SPEED_STEPS.firstOrNull { abs(it - speed) < 0.01f }
        val v = rounded ?: speed
        return if (v % 1.0f == 0.0f) "${v.toInt()}x" else "${v}x"
    }

    fun tickSleepTimerRemaining(remainingMs: Long, elapsedMs: Long): Long =
        (remainingMs - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(0L)

    fun formatSleepTimerLabel(remainingMs: Long, endOfTrack: Boolean): String? = when {
        endOfTrack -> "1 track"
        remainingMs > 0L -> {
            val mins = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
            "${mins}m"
        }
        else -> null
    }
}

/**
 * Process-wide audio player controller backed by Media3 [ExoPlayer] with
 * queue support, speed control, repeat-all, lock-screen [AudioMediaNotification],
 * audio focus management, and noisy (headphone-unplug) handling.
 */
@Singleton
class AudioPlayerController @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private var mediaNotification: AudioMediaNotification? = null

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

    private fun ensureNotification(): AudioMediaNotification {
        mediaNotification?.let { return it }
        return AudioMediaNotification(
            appContext = appContext,
            onPlayPause = ::togglePlayPause,
            onNext = ::playNext,
            onPrevious = ::playPrevious,
            onStop = ::release,
        ).also { mediaNotification = it }
    }

    private fun syncNotification() {
        val notif = ensureNotification().update(_state.value, cachedPositionMs, cachedDurationMs)
        lastNotification = notif
        if (_state.value.isPlaying && _state.value.path != null) {
            runCatching {
                appContext.startForegroundService(
                    android.content.Intent(appContext, AudioPlaybackService::class.java),
                )
            }
        }
    }

    internal fun currentNotification(): android.app.Notification? =
        lastNotification ?: ensureNotification().update(_state.value, cachedPositionMs, cachedDurationMs)

    @Volatile
    private var lastNotification: android.app.Notification? = null

    private val sleepTickRunnable = object : Runnable {
        override fun run() {
            val cur = _state.value
            if (cur.sleepTimerRemainingMs <= 0L) return
            val next = AudioQueueLogic.tickSleepTimerRemaining(cur.sleepTimerRemainingMs, 1_000L)
            if (next <= 0L) {
                _state.update { it.copy(sleepTimerRemainingMs = 0L, isPlaying = false) }
                runOnMain {
                    runCatching { exoPlayer?.pause() }
                    syncNotification()
                }
            } else {
                _state.update { it.copy(sleepTimerRemainingMs = next) }
                mainHandler.postDelayed(this, 1_000L)
            }
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
                syncNotification()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val dur = created.duration
                if (dur > 0L && dur != C.TIME_UNSET) {
                    cachedDurationMs = dur
                }
                if (playbackState == Player.STATE_ENDED) {
                    val cur = _state.value
                    if (cur.sleepTimerEndOfTrack) {
                        cachedPositionMs = cachedDurationMs
                        _state.update { it.copy(isPlaying = false, sleepTimerEndOfTrack = false) }
                        syncNotification()
                        return
                    }
                    val next = AudioQueueLogic.nextIndex(cur.currentIndex, cur.queue.size, cur.repeatAll)
                    if (next != null) {
                        playTrackAtIndex(next)
                    } else {
                        cachedPositionMs = cachedDurationMs
                        _state.update { it.copy(isPlaying = false) }
                        syncNotification()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("AudioPlayerController: ExoPlayer error", error)
                cachedPositionMs = 0L
                cachedDurationMs = 1L
                _state.update { AudioState(playbackSpeed = it.playbackSpeed, repeatAll = it.repeatAll) }
                mediaNotification?.dismiss()
            }
        })
        exoPlayer = created
        return created
    }

    /** Play a single audio file. Replaces any active queue. */
    fun playAudio(localPath: String, fileName: String) {
        playQueue(listOf(AudioTrack(title = fileName, path = localPath)), 0)
    }

    /** Play an ordered queue of [tracks] beginning at [startIndex]. */
    fun playQueue(tracks: List<AudioTrack>, startIndex: Int = 0) {
        val (deduped, mappedIndex) = AudioQueueLogic.deduplicateQueue(tracks, startIndex)
        if (deduped.isEmpty()) return
        val speed = _state.value.playbackSpeed
        val repeat = _state.value.repeatAll
        val current = deduped[mappedIndex]
        _state.value = AudioState(
            title = current.title,
            isPlaying = false,
            path = current.path,
            queue = deduped,
            currentIndex = mappedIndex,
            playbackSpeed = speed,
            repeatAll = repeat,
        )
        loadAndStartTrack(current, speed)
    }

    /** Skip to the next track in the queue (or wrap around if repeat-all is enabled). */
    fun playNext() {
        val cur = _state.value
        val next = AudioQueueLogic.nextIndex(cur.currentIndex, cur.queue.size, cur.repeatAll) ?: return
        playTrackAtIndex(next)
    }

    /** Skip to previous track, or restart current track if > 3s into playback. */
    fun playPrevious() {
        if (currentPosition() > 3_000 && _state.value.path != null) {
            seekTo(0)
            return
        }
        val cur = _state.value
        val prev = AudioQueueLogic.previousIndex(cur.currentIndex, cur.queue.size, cur.repeatAll) ?: return
        playTrackAtIndex(prev)
    }

    private fun playTrackAtIndex(index: Int) {
        val cur = _state.value
        val target = cur.queue.getOrNull(index) ?: return
        _state.update {
            it.copy(
                title = target.title,
                path = target.path,
                currentIndex = index,
                isPlaying = false,
            )
        }
        loadAndStartTrack(target, cur.playbackSpeed)
    }

    private fun loadAndStartTrack(track: AudioTrack, speed: Float) {
        cachedPositionMs = 0L
        cachedDurationMs = 1L
        runOnMain {
            try {
                val player = ensurePlayer()
                val mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(File(track.path)))
                player.setMediaItem(mediaItem)
                player.setPlaybackSpeed(speed)
                player.prepare()
                player.play()
                syncNotification()
            } catch (t: Throwable) {
                Log.w("playAudio failed", t)
                _state.update { AudioState(playbackSpeed = it.playbackSpeed, repeatAll = it.repeatAll) }
                mediaNotification?.dismiss()
            }
        }
    }

    /** Set playback speed multiplier clamped to [0.5f, 2.5f]. */
    fun setPlaybackSpeed(speed: Float) {
        val safe = AudioQueueLogic.normalizeSpeed(speed)
        _state.update { it.copy(playbackSpeed = safe) }
        runOnMain {
            runCatching { exoPlayer?.setPlaybackSpeed(safe) }
            syncNotification()
        }
    }

    /** Cycle playback speed through 0.75x -> 1x -> 1.25x -> 1.5x -> 2x. */
    fun cyclePlaybackSpeed() {
        setPlaybackSpeed(AudioQueueLogic.nextSpeed(_state.value.playbackSpeed))
    }

    /** Toggle repeat-all mode for the active queue. */
    fun toggleRepeatAll() {
        _state.update { it.copy(repeatAll = !it.repeatAll) }
    }

    /** Jump directly to [index] inside the active queue. */
    fun playQueueIndex(index: Int) {
        if (index in _state.value.queue.indices) {
            playTrackAtIndex(index)
        }
    }

    /** Remove [index] from the queue, stopping if empty or advancing if current track was removed. */
    fun removeQueueTrack(index: Int) {
        val cur = _state.value
        if (index !in cur.queue.indices) return
        val newQueue = cur.queue.toMutableList().apply { removeAt(index) }
        if (newQueue.isEmpty()) {
            release()
            return
        }
        val nextIdx = when {
            index < cur.currentIndex -> cur.currentIndex - 1
            index == cur.currentIndex -> index.coerceAtMost(newQueue.lastIndex)
            else -> cur.currentIndex
        }
        if (index == cur.currentIndex) {
            _state.update { it.copy(queue = newQueue, currentIndex = nextIdx) }
            playTrackAtIndex(nextIdx)
        } else {
            _state.update { it.copy(queue = newQueue, currentIndex = nextIdx) }
            syncNotification()
        }
    }

    fun setSleepTimerMinutes(minutes: Int) {
        mainHandler.removeCallbacks(sleepTickRunnable)
        if (minutes <= 0) {
            _state.update { it.copy(sleepTimerRemainingMs = 0L, sleepTimerEndOfTrack = false) }
            return
        }
        val totalMs = minutes.toLong() * 60_000L
        _state.update { it.copy(sleepTimerRemainingMs = totalMs, sleepTimerEndOfTrack = false) }
        mainHandler.postDelayed(sleepTickRunnable, 1_000L)
    }

    fun setSleepTimerEndOfTrack() {
        mainHandler.removeCallbacks(sleepTickRunnable)
        _state.update { it.copy(sleepTimerRemainingMs = 0L, sleepTimerEndOfTrack = true) }
    }

    fun cancelSleepTimer() {
        mainHandler.removeCallbacks(sleepTickRunnable)
        _state.update { it.copy(sleepTimerRemainingMs = 0L, sleepTimerEndOfTrack = false) }
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
                syncNotification()
            } catch (_: Throwable) {}
        }
    }

    /** Stop and release the ExoPlayer instance and notification. */
    fun release() {
        mainHandler.removeCallbacks(sleepTickRunnable)
        lastNotification = null
        cachedPositionMs = 0L
        cachedDurationMs = 1L
        _state.value = AudioState()
        runOnMain {
            try {
                exoPlayer?.release()
            } catch (_: Throwable) {}
            exoPlayer = null
            mediaNotification?.release()
            mediaNotification = null
            runCatching {
                appContext.stopService(android.content.Intent(appContext, AudioPlaybackService::class.java))
            }
        }
    }
}
