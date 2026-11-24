package com.tgdrive.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.audio.AudioPlayerController
import com.tgdrive.core.audio.AudioQueueLogic
import com.tgdrive.designsystem.theme.TgTypography
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import javax.inject.Inject

/**
 * ViewModel exposing the singleton [AudioPlayerController] to Compose.
 * Does NOT release playback on composition leave so listening persists across screens.
 */
@HiltViewModel
class AudioPlayerViewModel @Inject constructor(
    val controller: AudioPlayerController,
) : ViewModel()

/**
 * Persistent mini audio player sitting below the active navigation host with queue
 * skip controls, playback speed cycling, repeat-all toggle, and seek scrubber.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioPlayerScaffold(
    content: @Composable () -> Unit,
    vm: AudioPlayerViewModel = hiltViewModel(),
) {
    val controller = vm.controller
    val state by controller.state.collectAsStateWithLifecycle()
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(1) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state.path, state.isPlaying) {
        if (state.path == null) return@LaunchedEffect
        while (isActive) {
            val pos = controller.currentPosition().coerceAtLeast(0)
            val dur = controller.duration().coerceAtLeast(1)
            positionMs = pos
            durationMs = dur
            if (!isSeeking) {
                seekFraction = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            }
            delay(250L)
        }
    }

    val isVisible = state.path != null
    val hasQueue = state.queue.size > 1

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (isVisible) {
                        Modifier.consumeWindowInsets(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            content()
        }

        AnimatedVisibility(
            visible = isVisible,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, top = 6.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    ) {
                        Text(
                            text = state.title ?: "",
                            style = TgTypography.Display.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val subtitle = if (hasQueue) {
                                "${state.currentIndex + 1} / ${state.queue.size}"
                            } else {
                                stringResource(R.string.player_audio_now_playing)
                            }
                            Text(
                                text = subtitle,
                                style = TgTypography.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            val displayedPosMs = if (isSeeking) (seekFraction * durationMs).toInt() else positionMs
                            Text(
                                text = "${formatAudioMs(displayedPosMs)} / ${formatAudioMs(durationMs)}",
                                style = TgTypography.Mono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                onClickLabel = stringResource(R.string.player_audio_speed),
                            ) { controller.cyclePlaybackSpeed() },
                    ) {
                        Text(
                            text = AudioQueueLogic.formatSpeedLabel(state.playbackSpeed),
                            style = TgTypography.Mono,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                        )
                    }

                    if (hasQueue) {
                        IconButton(
                            onClick = { controller.toggleRepeatAll() },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Filled.Repeat,
                                contentDescription = stringResource(R.string.player_audio_repeat),
                                tint = if (state.repeatAll) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = { controller.playPrevious() },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = stringResource(R.string.player_audio_prev),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    IconButton(
                        onClick = { controller.togglePlayPause() },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (state.isPlaying) R.string.player_audio_pause else R.string.player_audio_play,
                            ),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (hasQueue) {
                        IconButton(
                            onClick = { controller.playNext() },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = stringResource(R.string.player_audio_next),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    IconButton(
                        onClick = { controller.release() },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.player_audio_stop),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                val seekDesc = stringResource(R.string.player_audio_seek)
                Slider(
                    value = seekFraction.coerceIn(0f, 1f),
                    onValueChange = {
                        isSeeking = true
                        seekFraction = it
                    },
                    onValueChangeFinished = {
                        controller.seekTo((seekFraction * durationMs).toInt())
                        isSeeking = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .padding(horizontal = 12.dp, vertical = 0.dp)
                        .semantics { contentDescription = seekDesc },
                )
            }
        }
    }
}

private fun formatAudioMs(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
