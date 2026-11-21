package com.tgdrive.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.pip.PipController
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.TrashEntryEntity
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.designsystem.theme.TgTypography
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject

data class ImageExifInfo(
    val fileName: String,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val dateTaken: String? = null,
    val camera: String? = null,
    val exposure: String? = null,
    val location: String? = null,
)

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    val downloadManager: DownloadManager,
    val pipController: PipController,
    private val fileCacheDao: FileCacheDao,
    val telegramClient: TelegramClient,
    private val offlinePinStore: OfflinePinStore,
    private val trashEntryDao: TrashEntryDao,
    private val cloudSyncManager: CloudSyncManager,
    private val dispatchers: DispatchersProvider,
) : ViewModel() {

    private val persistScope = CoroutineScope(SupervisorJob() + dispatchers.io)

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

    suspend fun loadSiblingMedia(chatId: Long, includeLocked: Boolean = false): List<FileCacheEntity> {
        if (chatId == 0L) return emptyList()
        return runCatching {
            fileCacheDao.forChat(chatId).filter { entity ->
                if (!includeLocked && entity.isLocked) return@filter false
                val mime = entity.mimeType.orEmpty().lowercase()
                val ext = entity.fileName.substringAfterLast('.', "").lowercase()
                mime.startsWith("image/") ||
                    mime.startsWith("video/") ||
                    ext in IMAGE_EXTS ||
                    ext in VIDEO_EXTS
            }
        }.getOrDefault(emptyList())
    }

    fun savePlaybackPosition(
        chatId: Long,
        messageId: Long,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (chatId == 0L) return
        val effective = when {
            durationMs > 0L && positionMs >= durationMs - 3_000L -> 0L
            positionMs >= 3_000L -> positionMs
            else -> 0L
        }
        persistScope.launch {
            runCatching { fileCacheDao.updatePlaybackPosition(chatId, messageId, effective) }
            cloudSyncManager.notePlaybackPositionChanged(chatId, messageId, effective)
        }
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

    suspend fun toggleStar(entry: FileCacheEntity): Boolean = withContext(dispatchers.io) {
        val next = !entry.isStarred
        fileCacheDao.setStarred(entry.chatId, entry.messageId, next)
        cloudSyncManager.noteStarChanged(entry.chatId, entry.messageId, next)
        next
    }

    suspend fun togglePinOffline(entry: FileCacheEntity): Boolean = withContext(dispatchers.io) {
        if (entry.isPinnedOffline) {
            offlinePinStore.unpinFile(entry.chatId, entry.messageId)
            false
        } else {
            val local = getLocalPath(entry.messageId, entry.chatId)
            if (local != null) {
                offlinePinStore.pinFile(entry.chatId, entry.messageId, local, entry.fileName)
            } else if (entry.remoteId.isNotEmpty()) {
                downloadManager.enqueue(
                    DownloadItem(
                        id = entry.messageId,
                        chatId = entry.chatId,
                        remoteId = entry.remoteId,
                        fileName = entry.fileName,
                        totalBytes = entry.fileSize,
                        pinOfflineWhenDone = true,
                    ),
                )
                fileCacheDao.setPinnedOffline(entry.chatId, entry.messageId, true)
            }
            true
        }
    }

    suspend fun moveToTrash(entry: FileCacheEntity) = withContext(dispatchers.io) {
        try {
            trashEntryDao.upsert(
                TrashEntryEntity(
                    messageId = entry.messageId,
                    chatId = entry.chatId,
                    fileName = entry.fileName,
                    fileSize = entry.fileSize,
                    mimeType = entry.mimeType,
                    thumbnailPath = entry.thumbnailPath,
                    remoteId = entry.remoteId,
                    originalDate = entry.date,
                    isStarred = entry.isStarred,
                    deletedAt = System.currentTimeMillis(),
                ),
            )
            fileCacheDao.deleteById(entry.chatId, entry.messageId)
            cloudSyncManager.schedulePushMetadata()
        } catch (e: CancellationException) {
            throw e
        }
    }

    suspend fun readImageExif(localPath: String?, entry: FileCacheEntity?): ImageExifInfo = withContext(dispatchers.io) {
        var width = 0
        var height = 0
        var dateTaken: String? = null
        var camera: String? = null
        var exposure: String? = null
        var location: String? = null

        if (localPath != null && File(localPath).isFile) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(localPath, opts)
                width = opts.outWidth.coerceAtLeast(0)
                height = opts.outHeight.coerceAtLeast(0)
            }
            runCatching {
                val exif = ExifInterface(localPath)
                dateTaken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
                camera = when {
                    make.isNotEmpty() && model.isNotEmpty() && !model.startsWith(make, ignoreCase = true) -> "$make $model"
                    model.isNotEmpty() -> model
                    make.isNotEmpty() -> make
                    else -> null
                }

                val parts = mutableListOf<String>()
                exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.takeIf { it.isNotBlank() }?.let { ap ->
                    parts += "f/$ap"
                }
                exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { sec ->
                    if (sec > 0.0 && sec < 1.0) {
                        parts += "1/${Math.round(1.0 / sec)}s"
                    } else if (sec >= 1.0) {
                        parts += "${String.format(Locale.US, "%.1f", sec)}s"
                    }
                }
                (exif.getAttribute("ISOSpeedRatings") ?: exif.getAttribute("PhotographicSensitivity"))
                    ?.takeIf { it.isNotBlank() }
                    ?.let { iso ->
                        parts += "ISO $iso"
                    }
                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { raw ->
                    val segs = raw.split('/')
                    val mm = if (segs.size == 2) {
                        val a = segs[0].toDoubleOrNull()
                        val b = segs[1].toDoubleOrNull()
                        if (a != null && b != null && b != 0.0) a / b else null
                    } else {
                        raw.toDoubleOrNull()
                    }
                    if (mm != null && mm > 0.0) {
                        parts += "${String.format(Locale.US, "%.1f", mm)}mm"
                    }
                }
                if (parts.isNotEmpty()) {
                    exposure = parts.joinToString(" · ")
                }

                val latLong = FloatArray(2)
                if (exif.getLatLong(latLong)) {
                    location = "${String.format(Locale.US, "%.5f", latLong[0])}, ${String.format(Locale.US, "%.5f", latLong[1])}"
                }
            }
        }

        ImageExifInfo(
            fileName = entry?.fileName ?: localPath?.let { File(it).name }.orEmpty(),
            fileSize = entry?.fileSize ?: localPath?.let { File(it).length() } ?: 0L,
            width = width,
            height = height,
            dateTaken = dateTaken,
            camera = camera,
            exposure = exposure,
            location = location,
        )
    }

    override fun onCleared() {
        pipController.setActive(false)
    }

    companion object {
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val VIDEO_EXTS = setOf("mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp")
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
 * streaming with speed control, aspect-ratio cycling, embedded & external subtitles,
 * tap/swipe seek/brightness/volume gestures, cloud-synced resume position, and PiP.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    messageId: Long,
    onBack: () -> Unit,
    chatId: Long = 0L,
    vm: VideoPlayerViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = remember(ctx) { ctx.findActivity() }
    val audioManager = remember(ctx) {
        ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
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
    var initialCloudSeekApplied by remember(chatId, messageId) { mutableStateOf(false) }

    val downloadItem = downloadState.items.find {
        it.id == messageId && (chatId == 0L || it.chatId == 0L || it.chatId == chatId)
    }
    val isDownloading =
        downloadItem?.status == DownloadStatus.DOWNLOADING ||
            downloadItem?.status == DownloadStatus.QUEUED
    val isFailed = downloadItem?.status == DownloadStatus.FAILED
    val downloadProgress = downloadItem?.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

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
    var playbackSpeed by rememberSaveable(chatId, messageId) { mutableFloatStateOf(1f) }
    var resizeMode by rememberSaveable(chatId, messageId) {
        mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    }
    var externalSubtitle by remember(chatId, messageId) {
        mutableStateOf<MediaItem.SubtitleConfiguration?>(null)
    }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var gestureHudText by remember { mutableStateOf<String?>(null) }
    var gestureHudStamp by remember { mutableIntStateOf(0) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val subPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { pickedUri: Uri? ->
        if (pickedUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                copyExternalSubtitle(ctx, pickedUri, chatId, messageId)
            }
            if (loaded != null) {
                val (subFile, subMime, subLabel) = loaded
                exo?.currentPosition?.coerceAtLeast(0L)?.let { pos ->
                    if (pos > 0L) savedPositionMs = pos
                }
                externalSubtitle = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subFile))
                    .setMimeType(subMime)
                    .setLanguage("en")
                    .setLabel(subLabel)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                exo?.let { player ->
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build()
                }
            }
        }
    }

    LaunchedEffect(gestureHudStamp) {
        if (gestureHudStamp > 0) {
            delay(900L)
            gestureHudText = null
        }
    }

    LaunchedEffect(chatId, messageId) {
        cachedEntry = vm.getCachedEntry(chatId, messageId)
    }

    LaunchedEffect(cachedEntry?.playbackPositionMs, exo) {
        val resumeMs = cachedEntry?.playbackPositionMs ?: 0L
        if (!initialCloudSeekApplied && resumeMs > 0L) {
            initialCloudSeekApplied = true
            if (savedPositionMs == 0L) {
                savedPositionMs = resumeMs
                exo?.let { p ->
                    if (p.currentPosition < 2_000L) {
                        runCatching { p.seekTo(resumeMs) }
                    }
                }
            }
        }
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

    LaunchedEffect(localPath, useProgressiveStream, cachedEntry, exo, externalSubtitle) {
        val player = exo ?: return@LaunchedEffect
        val path = localPath
        val subConfig = externalSubtitle
        if (path != null) {
            try {
                val file = File(path)
                if (!file.exists()) return@LaunchedEffect
                playbackError = null
                val itemBuilder = MediaItem.Builder().setUri(Uri.fromFile(file))
                if (subConfig != null) {
                    itemBuilder.setSubtitleConfigurations(listOf(subConfig))
                }
                player.setMediaItem(itemBuilder.build())
                if (savedPositionMs > 0L) player.seekTo(savedPositionMs)
                player.setPlaybackSpeed(playbackSpeed)
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
                val videoSource = ProgressiveMediaSource.Factory(factory)
                    .createMediaSource(MediaItem.fromUri("tgstream://video/$chatId/$messageId"))
                val finalSource = if (subConfig != null) {
                    val subSource = SingleSampleMediaSource.Factory(FileDataSource.Factory())
                        .createMediaSource(subConfig, C.TIME_UNSET)
                    MergingMediaSource(videoSource, subSource)
                } else {
                    videoSource
                }
                player.setMediaSource(finalSource)
                if (savedPositionMs > 0L) player.seekTo(savedPositionMs)
                player.setPlaybackSpeed(playbackSpeed)
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

    LaunchedEffect(exo, hasActiveVideo, chatId, messageId) {
        val player = exo ?: return@LaunchedEffect
        if (!hasActiveVideo || chatId == 0L) return@LaunchedEffect
        while (true) {
            delay(15_000L)
            if (player.isPlaying) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration.coerceAtLeast(0L)
                savedPositionMs = pos
                vm.savePlaybackPosition(chatId, messageId, pos, dur)
            }
        }
    }

    LaunchedEffect(hasActiveVideo, playbackError, playerInitError) {
        vm.pipController.setActive(
            hasActiveVideo && playbackError == null && playerInitError == null,
        )
    }

    DisposableEffect(lifecycleOwner, exo, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && exo != null) {
                runCatching {
                    val pos = exo.currentPosition.coerceAtLeast(0L)
                    val dur = exo.duration.coerceAtLeast(0L)
                    savedPositionMs = pos
                    vm.savePlaybackPosition(chatId, messageId, pos, dur)
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
                    val pos = it.currentPosition.coerceAtLeast(0L)
                    val dur = it.duration.coerceAtLeast(0L)
                    savedPositionMs = pos
                    vm.savePlaybackPosition(chatId, messageId, pos, dur)
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
    val seekBackText = stringResource(R.string.player_video_seek_back)
    val seekForwardText = stringResource(R.string.player_video_seek_forward)
    val fitText = stringResource(R.string.player_video_resize_fit)
    val zoomText = stringResource(R.string.player_video_resize_zoom)
    val fillText = stringResource(R.string.player_video_resize_fill)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
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
                val targetResizeMode = resizeMode
                AndroidView(
                    factory = { viewCtx ->
                        PlayerView(viewCtx).apply {
                            player = exo
                            useController = !inPip
                            this.resizeMode = targetResizeMode
                            playerViewRef = this
                        }
                    },
                    update = { view ->
                        if (view.player !== exo) view.player = exo
                        view.useController = !inPip
                        view.resizeMode = targetResizeMode
                        playerViewRef = view
                    },
                    onRelease = { view ->
                        view.player = null
                        if (playerViewRef === view) playerViewRef = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (!inPip) {
                    var dragIsLeft by remember { mutableStateOf(true) }
                    var brightnessAccum by remember { mutableFloatStateOf(-1f) }
                    var volumeAccum by remember { mutableFloatStateOf(0f) }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 68.dp, bottom = 108.dp)
                            .pointerInput(exo, containerSize) {
                                detectTapGestures(
                                    onTap = {
                                        playerViewRef?.let { pv ->
                                            if (pv.isControllerFullyVisible) {
                                                pv.hideController()
                                            } else {
                                                pv.showController()
                                            }
                                        }
                                    },
                                    onDoubleTap = { tapOffset ->
                                        val w = containerSize.width.coerceAtLeast(1)
                                        val dur = exo.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                                        if (tapOffset.x < w * 0.42f) {
                                            exo.seekTo((exo.currentPosition - 10_000L).coerceAtLeast(0L))
                                            gestureHudText = seekBackText
                                            gestureHudStamp++
                                        } else if (tapOffset.x > w * 0.58f) {
                                            exo.seekTo((exo.currentPosition + 10_000L).coerceAtMost(dur))
                                            gestureHudText = seekForwardText
                                            gestureHudStamp++
                                        } else {
                                            if (exo.isPlaying) exo.pause() else exo.play()
                                        }
                                    },
                                )
                            }
                            .pointerInput(activity, audioManager, containerSize) {
                                detectVerticalDragGestures(
                                    onDragStart = { start ->
                                        val w = containerSize.width.coerceAtLeast(1)
                                        dragIsLeft = start.x < w * 0.5f
                                        if (dragIsLeft && activity != null) {
                                            val cur = activity.window.attributes.screenBrightness
                                            brightnessAccum = if (cur in 0f..1f) cur else 0.5f
                                        } else if (!dragIsLeft && audioManager != null) {
                                            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                                .coerceAtLeast(1)
                                            volumeAccum = cur.toFloat() / max.toFloat()
                                        }
                                    },
                                    onVerticalDrag = { _, dragAmount ->
                                        val h = containerSize.height.coerceAtLeast(200)
                                        val delta = -dragAmount / h.toFloat()
                                        if (dragIsLeft && activity != null) {
                                            val next = (brightnessAccum + delta).coerceIn(0.05f, 1f)
                                            brightnessAccum = next
                                            val lp = activity.window.attributes
                                            lp.screenBrightness = next
                                            activity.window.attributes = lp
                                            gestureHudText = ctx.getString(
                                                R.string.player_video_brightness,
                                                (next * 100).toInt(),
                                            )
                                            gestureHudStamp++
                                        } else if (!dragIsLeft && audioManager != null) {
                                            val next = (volumeAccum + delta).coerceIn(0f, 1f)
                                            volumeAccum = next
                                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                                .coerceAtLeast(1)
                                            val targetIdx = Math.round(next * max).coerceIn(0, max)
                                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIdx, 0)
                                            gestureHudText = ctx.getString(
                                                R.string.player_video_volume,
                                                (next * 100).toInt(),
                                            )
                                            gestureHudStamp++
                                        }
                                    },
                                )
                            },
                    )
                }
                if (isStreamingBuffering && !inPip) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 64.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.player_video_streaming),
                            color = Color.White,
                            style = TgTypography.Caption,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                if (gestureHudText != null && !inPip) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Text(
                            text = gestureHudText.orEmpty(),
                            color = Color.White,
                            style = TgTypography.Display.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
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

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasActiveVideo && exo != null) {
                        val speedLabel = if (playbackSpeed == playbackSpeed.toInt().toFloat()) {
                            "${playbackSpeed.toInt()}×"
                        } else {
                            "${playbackSpeed}×"
                        }
                        Surface(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                                    val idx = speeds.indexOfFirst { kotlin.math.abs(it - playbackSpeed) < 0.05f }
                                    val next = speeds[(idx + 1) % speeds.size]
                                    playbackSpeed = next
                                    exo.setPlaybackSpeed(next)
                                },
                        ) {
                            Text(
                                text = speedLabel,
                                color = Color.White,
                                style = TgTypography.Mono,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                val nextMode = when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                                resizeMode = nextMode
                                gestureHudText = when (nextMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> zoomText
                                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> fillText
                                    else -> fitText
                                }
                                gestureHudStamp++
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                        ) {
                            Icon(
                                Icons.Filled.AspectRatio,
                                contentDescription = stringResource(R.string.player_video_resize),
                                tint = Color.White,
                            )
                        }
                        IconButton(
                            onClick = { showSubtitleSheet = true },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                        ) {
                            Icon(
                                Icons.Filled.ClosedCaption,
                                contentDescription = stringResource(R.string.player_video_subtitles),
                                tint = if (externalSubtitle != null) MaterialTheme.colorScheme.primary else Color.White,
                            )
                        }
                    }
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

    if (showSubtitleSheet && exo != null) {
        val textGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val textDisabled = exo.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        ModalBottomSheet(
            onDismissRequest = { showSubtitleSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.player_video_subtitles),
                    style = TgTypography.Display.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            exo.trackSelectionParameters = exo.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                            showSubtitleSheet = false
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.player_video_subtitles_off),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (textDisabled) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                textGroups.forEachIndexed { gIdx, group ->
                    for (tIdx in 0 until group.length) {
                        val fmt = group.getTrackFormat(tIdx)
                        val trackName = listOfNotNull(
                            fmt.label?.takeIf { it.isNotBlank() },
                            fmt.language?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").ifBlank { "Track ${gIdx + 1}.${tIdx + 1}" }
                        val selected = !textDisabled && group.isTrackSelected(tIdx)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    exo.trackSelectionParameters = exo.trackSelectionParameters
                                        .buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, tIdx))
                                        .build()
                                    showSubtitleSheet = false
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(trackName, color = MaterialTheme.colorScheme.onSurface)
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Button(
                    onClick = {
                        showSubtitleSheet = false
                        subPicker.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.player_video_subtitles_load))
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private fun copyExternalSubtitle(
    context: Context,
    uri: Uri,
    chatId: Long,
    messageId: Long,
): Triple<File, String, String>? {
    return try {
        var displayName: String? = null
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) displayName = c.getString(idx)
            }
        }
        val cleanName = (displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "subtitle.srt")
            .replace('/', '_')
            .replace('\\', '_')
        val ext = cleanName.substringAfterLast('.', "srt").lowercase()
        val mime = when (ext) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val subsDir = File(context.cacheDir, "subs").apply { mkdirs() }
        val outFile = File(subsDir, "sub_${chatId}_${messageId}_$cleanName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { out -> input.copyTo(out) }
        } ?: return null
        Triple(outFile, mime, cleanName)
    } catch (t: Throwable) {
        Log.w("VideoPlayer: failed loading subtitle URI", t)
        null
    }
}
