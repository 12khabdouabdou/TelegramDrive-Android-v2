package com.tgdrive.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.pip.PipController
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.designsystem.theme.TgTypography
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    val downloadManager: DownloadManager,
    val pipController: PipController,
) : ViewModel() {

    fun getLocalPath(messageId: Long): String? {
        val path = downloadManager.state.value.items
            .find { it.id == messageId && it.status == DownloadStatus.DONE }
            ?.localPath ?: return null
        if (!File(path).exists()) return null
        return path
    }

    fun getDownloadProgress(messageId: Long): Float? {
        val item = downloadManager.state.value.items.find { it.id == messageId } ?: return null
        return when (item.status) {
            DownloadStatus.DOWNLOADING -> item.progress.coerceIn(0f, 1f)
            DownloadStatus.QUEUED -> 0f
            else -> null
        }
    }

    override fun onCleared() {
        pipController.setActive(false)
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * Video player screen with ExoPlayer, PiP button + PiP-aware chrome hiding,
 * Uri.fromFile safety, playback error recovery, and download retry.
 */
@Composable
fun VideoPlayerScreen(
    messageId: Long,
    onBack: () -> Unit,
    vm: VideoPlayerViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val config = LocalConfiguration.current
    val inPip = remember(config, activity) {
        activity != null && vm.pipController.isInPip(activity)
    }
    val pipSupported = remember(ctx) { vm.pipController.isPipSupported(ctx) }

    val downloadState by vm.downloadManager.state.collectAsStateWithLifecycle()
    var localPath by remember(messageId) { mutableStateOf<String?>(vm.getLocalPath(messageId)) }
    var playbackError by remember(messageId) { mutableStateOf<String?>(null) }
    var playerInitError by remember(messageId) { mutableStateOf<String?>(null) }

    val downloadItem = downloadState.items.find { it.id == messageId }
    val isDownloading =
        downloadItem?.status == DownloadStatus.DOWNLOADING ||
            downloadItem?.status == DownloadStatus.QUEUED
    val isFailed = downloadItem?.status == DownloadStatus.FAILED
    val downloadProgress = downloadItem?.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

    LaunchedEffect(messageId, downloadState.items) {
        val path = vm.getLocalPath(messageId)
        if (path != null && path != localPath) {
            localPath = path
            playbackError = null
        }
    }

    val exo = remember(messageId) {
        try {
            ExoPlayer.Builder(ctx.applicationContext).build()
        } catch (t: Throwable) {
            Log.w("VideoPlayer: failed to create ExoPlayer", t)
            playerInitError = t.localizedMessage ?: "Video player initialization failed"
            null
        }
    }
    var savedPositionMs by rememberSaveable(messageId) { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(localPath, exo) {
        val player = exo ?: return@LaunchedEffect
        val path = localPath ?: return@LaunchedEffect
        try {
            val file = File(path)
            if (!file.exists()) return@LaunchedEffect
            playbackError = null
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            if (savedPositionMs > 0L) {
                player.seekTo(savedPositionMs)
            }
            player.prepare()
            player.playWhenReady = true
        } catch (t: Throwable) {
            Log.w("VideoPlayer: failed to prepare media", t)
            playbackError = t.localizedMessage ?: "Playback error"
        }
    }

    LaunchedEffect(localPath, playbackError, playerInitError) {
        vm.pipController.setActive(
            localPath != null && playbackError == null && playerInitError == null,
        )
    }

    DisposableEffect(lifecycleOwner, exo, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && exo != null) {
                runCatching {
                    savedPositionMs = exo.currentPosition.coerceAtLeast(0L)
                    val inPipNow = activity != null && vm.pipController.isInPip(activity)
                    if (!inPipNow) {
                        exo.pause()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w("VideoPlayer: ExoPlayer error", error)
                playbackError = error.localizedMessage ?: "Playback error"
            }
        }
        exo?.let { runCatching { it.addListener(listener) } }
        onDispose {
            runCatching {
                exo?.let {
                    savedPositionMs = it.currentPosition.coerceAtLeast(0L)
                    it.removeListener(listener)
                    it.release()
                }
            }
            vm.pipController.setActive(false)
        }
    }

    val activeError = playerInitError ?: playbackError

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            activeError != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = activeError.ifBlank { stringResource(R.string.player_video_error) },
                        color = Color.White,
                        style = TgTypography.Display.bodyMedium,
                    )
                    if (playerInitError == null && exo != null) {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            playbackError = null
                            runCatching {
                                exo.prepare()
                                exo.playWhenReady = true
                            }.onFailure { err ->
                                playbackError = err.localizedMessage ?: "Playback error"
                            }
                        }) {
                            Text(stringResource(R.string.player_retry))
                        }
                    }
                }
            }
            localPath != null && exo != null -> {
                AndroidView(
                    factory = { viewCtx ->
                        PlayerView(viewCtx).apply {
                            player = exo
                            useController = !inPip
                        }
                    },
                    update = { view ->
                        if (view.player !== exo) {
                            view.player = exo
                        }
                        view.useController = !inPip
                    },
                    onRelease = { view ->
                        view.player = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            isFailed -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = downloadItem?.errorMessage ?: stringResource(R.string.player_download_failed),
                        color = Color.White,
                        style = TgTypography.Display.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { downloadItem?.let { vm.downloadManager.enqueue(it) } }) {
                        Text(stringResource(R.string.player_retry))
                    }
                }
            }
            isDownloading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = stringResource(R.string.player_downloading_pct, (downloadProgress * 100).toInt()),
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                    )
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth(0.7f).clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
            else -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        if (!inPip) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent),
                        ),
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.player_back),
                        tint = Color.White,
                    )
                }

                if (pipSupported && localPath != null && activity != null) {
                    IconButton(
                        onClick = { vm.pipController.enterPip(activity) },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f)),
                    ) {
                        Icon(
                            Icons.Filled.PictureInPictureAlt,
                            contentDescription = stringResource(R.string.player_video_pip),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}
