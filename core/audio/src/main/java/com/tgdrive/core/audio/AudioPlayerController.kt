package com.tgdrive.core.audio

import android.media.MediaPlayer
import com.tgdrive.core.common.Log
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
 * Process-wide audio player controller — Hilt Singleton.
 *
 * Promoted from `:feature:player` (where it was a file-level `private var
 * audioPlayer: MediaPlayer?` + top-level `playAudio()` function, violating
 * CONTRIBUTING.md's "no manual object singletons with state" rule) to
 * `:core:audio` as a proper Hilt-injected singleton.
 *
 * The Compose UI ([com.tgdrive.feature.player.AudioPlayerScaffold]) stays
 * in `:feature:player` and observes [state]. Callers (e.g. FolderScreen)
 * inject this controller and call [playAudio] — no more file-level globals.
 */
@Singleton
class AudioPlayerController @Inject constructor() {

    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()

    private val lock = Any()
    private var player: MediaPlayer? = null

    @Volatile
    private var isPrepared: Boolean = false

    /** Play an audio file. Stops any current playback first. */
    fun playAudio(localPath: String, fileName: String) {
        synchronized(lock) {
            isPrepared = false
            runCatching { player?.release() }
            player = null
            try {
                val mp = MediaPlayer().apply {
                    setDataSource(localPath)
                    _state.value = AudioState(title = fileName, isPlaying = false, path = localPath)
                    setOnPreparedListener { preparedMp ->
                        var started = false
                        synchronized(lock) {
                            if (player === preparedMp) {
                                isPrepared = true
                                preparedMp.start()
                                started = true
                            }
                        }
                        if (started) {
                            _state.update { it.copy(isPlaying = true) }
                        }
                    }
                    setOnCompletionListener {
                        synchronized(lock) {
                            if (player === it) {
                                _state.update { st -> st.copy(isPlaying = false) }
                            }
                        }
                    }
                    setOnErrorListener { failedMp, what, extra ->
                        Log.w("AudioPlayerController: MediaPlayer error what=$what extra=$extra")
                        synchronized(lock) {
                            if (player === failedMp) {
                                isPrepared = false
                            }
                        }
                        _state.value = AudioState()
                        true
                    }
                    prepareAsync()
                }
                player = mp
            } catch (t: Throwable) {
                isPrepared = false
                player = null
                _state.value = AudioState()
                Log.w("playAudio failed", t)
            }
        }
    }

    /** Toggle play/pause. No-op if nothing is loaded or still preparing. */
    fun togglePlayPause() {
        synchronized(lock) {
            if (!isPrepared) return
            try {
                if (_state.value.isPlaying) {
                    player?.pause()
                    _state.update { it.copy(isPlaying = false) }
                } else {
                    player?.start()
                    _state.update { it.copy(isPlaying = true) }
                }
            } catch (t: Throwable) {
                Log.w("AudioPlayerController: play/pause failed", t)
            }
        }
    }

    /** Current playback position in ms, or 0 if not prepared. */
    fun currentPosition(): Int = synchronized(lock) {
        if (!isPrepared) return 0
        try { player?.currentPosition ?: 0 } catch (_: Throwable) { 0 }
    }

    /** Total duration in ms, or 1 if unknown or not prepared. */
    fun duration(): Int = synchronized(lock) {
        if (!isPrepared) return 1
        try { player?.duration ?: 1 } catch (_: Throwable) { 1 }
    }

    /** Seek playback to [ms] clamped to non-negative values. */
    fun seekTo(ms: Int) {
        synchronized(lock) {
            if (!isPrepared) return
            try {
                player?.seekTo(ms.coerceAtLeast(0))
            } catch (_: Throwable) {}
        }
    }

    /** Stop and release the MediaPlayer. */
    fun release() {
        synchronized(lock) {
            isPrepared = false
            try {
                player?.release()
            } catch (_: Throwable) {}
            player = null
            _state.value = AudioState()
        }
    }
}
