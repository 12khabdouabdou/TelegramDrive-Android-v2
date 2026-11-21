package com.tgdrive.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.annotation.OptIn
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.pip.PipController
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.designsystem.theme.TgTypography
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    val downloadManager: DownloadManager,
    val pipController: PipController,
    private val fileCacheDao: FileCacheDao,
    val telegramClient: TelegramClient,
    private val offlinePinStore: OfflinePinStore,
) : ViewModel() {

    fun getLocalPath(messageId: Long, chatId: Long = 0L): String? {
        if (chatId != 0L) {
            offlinePinStore.getPinnedFilePathSync(chatId, messageId)?.let { pinned ->
                if (File(pinned).exists()) return pinned
            }
        }
        val path = downloadManager.findItem(chatId, messageId)
            ?.takeIf { it.status == DownloadStatus.DONE }
            ?.localPath ?: return null
        if (!File(path).exists()) return null
        return path
    }

    fun getDownloadProgress(messageId: Long, chatId: Long = 0L): Float? {
        val item = downloadManager.findItem(chatId, messageId) ?: return null
        return when (item.status) {
            DownloadStatus.DOWNLOADING -> item.progress.coerceIn(0f, 1f)
            DownloadStatus.QUEUED -> 0f
            else -> null
        }
    }

    suspend fun getCachedEntry(chatId: Long, messageId: Long): FileCacheEntity? {
        if (chatId == 0L) return null
        return runCatching { fileCacheDao.getById(chatId, messageId) }.getOrNull()
    }

    suspend fun loadSiblingImages(chatId: Long): List<FileCacheEntity> {
        if (chatId == 0L) return emptyList()
        return runCatching {
            fileCacheDao.forChat(chatId).filter { entity ->
                val mime = entity.mimeType.orEmpty().lowercase()
                val ext = entity.fileName.substringAfterLast('.', "").lowercase()
                mime.startsWith("image/") || ext in IMAGE_EXTS
            }
        }.getOrDefault(emptyList())
    }

    fun ensureDownloaded(entry: FileCacheEntity) {
        if (entry.remoteId.isBlank()) return
        downloadManager.enqueue(
            DownloadItem(
                id = entry.messageId,
                chatId = entry.chatId,
                remoteId = entry.remoteId,
                fileName = entry.fileName,
                totalBytes = entry.fileSize,
            ),
        )
    }

    fun cancelStream(fileId: Int) {
        if (fileId == 0) return
        viewModelScope.launch {
            runCatching { telegramClient.cancelFileStream(fileId) }
        }
    }

    override fun onCleared() {
        pipController.setActive(false)
    }

    companion object {
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * Video player screen supporting local cached playback and progressive TDLib range
 * streaming with automatic download fallback, PiP button + chrome hiding, and retry.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    messageId: Long,
    onBack: () -> Unit,
    chatId: Long = 0L,
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
    var cachedEntry by remember(chatId, messageId) { mutableStateOf<FileCacheEntity?>(null) }
    var localPath by remember(chatId, messageId) { mutableStateOf<String?>(vm.getLocalPath(messageId, chatId)) }
    var playbackError by remember(chatId, messageId) { mutableStateOf<String?>(null) }
    var playerInitError by remember(chatId, messageId) { mutableStateOf<String?>(null) }
    var useProgressiveStream by remember(chatId, messageId) { mutableStateOf(false) }
    var isStreamingBuffering by remember(chatId, messageId) { mutableStateOf(false) }
    var streamFileId by remember(chatId, messageId) { mutableIntStateOf(0) }
    var fellBackToDownload by remember(chatId, messageId) { mutableStateOf(false) }

    val downloadItem = downloadState.items.find {
        it.id == messageId && (chatId == 0L || it.chatId == 0L || it.chatId == chatId)
    }
    val isDownloading =
        downloadItem?.status == DownloadStatus.DOWNLOADING ||
            downloadItem?.status == DownloadStatus.QUEUED
    val isFailed = downloadItem?.status == DownloadStatus.FAILED
    val downloadProgress = downloadItem?.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

    LaunchedEffect(chatId, messageId) {
        cachedEntry = vm.getCachedEntry(chatId, messageId)
    }

    LaunchedEffect(chatId, messageId, downloadState.items, cachedEntry) {
        val path = vm.getLocalPath(messageId, chatId)
        if (path != null && path != localPath) {
            localPath = path
            useProgressiveStream = false
            isStreamingBuffering = false
            playbackError = null
        } else if (path == null &&
            !fellBackToDownload &&
            !isDownloading &&
            !isFailed &&
            cachedEntry?.remoteId?.isNotEmpty() == true
        ) {
            useProgressiveStream = true
            isStreamingBuffering = true
        }
    }

    val exo = remember(chatId, messageId) {
        try {
            ExoPlayer.Builder(ctx.applicationContext).build()
        } catch (t: Throwable) {
            Log.w("VideoPlayer: failed to create ExoPlayer", t)
            playerInitError = t.localizedMessage ?: "Video player initialization failed"
            null
        }
    }
    var savedPositionMs by rememberSaveable(chatId, messageId) { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(localPath, useProgressiveStream, cachedEntry, exo) {
        val player = exo ?: return@LaunchedEffect
        val path = localPath
        if (path != null) {
            try {
                val file = File(path)
                if (!file.exists()) return@LaunchedEffect
                playbackError = null
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                if (savedPositionMs > 0L) player.seekTo(savedPositionMs)
                player.prepare()
                player.playWhenReady = true
            } catch (t: Throwable) {
                Log.w("VideoPlayer: failed to prepare local media", t)
                playbackError = t.localizedMessage ?: "Playback error"
            }
            return@LaunchedEffect
        }

        val entry = cachedEntry
        if (useProgressiveStream && entry != null && entry.remoteId.isNotEmpty()) {
            try {
                playbackError = null
                val factory = DataSource.Factory {
                    TdLibVideoDataSource(
                        telegramClient = vm.telegramClient,
                        remoteId = entry.remoteId,
                        fallbackTotalBytes = entry.fileSize,
                        onOpenedFileId = { fid -> streamFileId = fid },
                    )
                }
                val mediaSource = ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(MediaItem.fromUri("tgstream://video/$chatId/$messageId"))
                player.setMediaSource(mediaSource)
                if (savedPositionMs > 0L) player.seekTo(savedPositionMs)
                player.prepare()
                player.playWhenReady = true
            } catch (t: Throwable) {
                Log.w("VideoPlayer: progressive stream setup failed — falling back to download", t)
                useProgressiveStream = false
                isStreamingBuffering = false
                fellBackToDownload = true
                vm.ensureDownloaded(entry)
            }
        }
    }

    val hasActiveVideo = localPath != null || useProgressiveStream

    LaunchedEffect(hasActiveVideo, playbackError, playerInitError) {
        vm.pipController.setActive(
            hasActiveVideo && playbackError == null && playerInitError == null,
        )
    }

    DisposableEffect(lifecycleOwner, exo, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && exo != null) {
                runCatching {
                    savedPositionMs = exo.currentPosition.coerceAtLeast(0L)
                    val inPipNow = activity != null && vm.pipController.isInPip(activity)
                    if (!inPipNow) exo.pause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isStreamingBuffering = useProgressiveStream && playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("VideoPlayer: ExoPlayer error (streaming=$useProgressiveStream)", error)
                val entry = cachedEntry
                if (useProgressiveStream && !fellBackToDownload && entry != null) {
                    // Progressive seek/codec failure on partial stream -> download cleanly instead
                    useProgressiveStream = false
                    isStreamingBuffering = false
                    fellBackToDownload = true
                    playbackError = null
                    vm.ensureDownloaded(entry)
                } else {
                    isStreamingBuffering = false
                    playbackError = error.localizedMessage ?: "Playback error"
                }
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
            if (streamFileId != 0) {
                vm.cancelStream(streamFileId)
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
            hasActiveVideo && exo != null -> {
                AndroidView(
                    factory = { viewCtx ->
                        PlayerView(viewCtx).apply {
                            player = exo
                            useController = !inPip
                        }
                    },
                    update = { view ->
                        if (view.player !== exo) view.player = exo
                        view.useController = !inPip
                    },
                    onRelease = { view -> view.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
                if (isStreamingBuffering && !inPip) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.player_video_streaming),
                            color = Color.White,
                            style = TgTypography.Caption,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (localPath == null && cachedEntry != null && !isDownloading) {
                        IconButton(
                            onClick = { cachedEntry?.let { vm.ensureDownloaded(it) } },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                        ) {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = stringResource(R.string.player_video_download_full),
                                tint = Color.White,
                            )
                        }
                    }
                    if (pipSupported && hasActiveVideo && activity != null) {
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
}
