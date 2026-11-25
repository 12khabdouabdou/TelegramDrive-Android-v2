package com.tgdrive.feature.player

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.NormalUploadOptions
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.UploadConflictStrategy
import com.tgdrive.core.download.UploadFileItem
import com.tgdrive.core.download.UploadManager
import com.tgdrive.core.download.ai.MediaAiIndexer
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
    val latitude: Double? = null,
    val longitude: Double? = null,
    val userNote: String = "",
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
    private val mediaAiIndexer: MediaAiIndexer,
    private val mediaThumbnailCache: MediaThumbnailCache,
    private val uploadManager: UploadManager,
    private val dispatchers: DispatchersProvider,
    @ApplicationContext private val appContext: Context,
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

    suspend fun loadTimelineMedia(): List<FileCacheEntity> =
        runCatching { fileCacheDao.getUnlockedMediaItems() }.getOrDefault(emptyList())

    suspend fun loadAlbumMedia(albumId: String): List<FileCacheEntity> {
        if (albumId.isBlank()) return emptyList()
        return runCatching { fileCacheDao.getMediaItemsForAlbum(albumId) }.getOrDefault(emptyList())
    }

    suspend fun loadSiblingVideos(chatId: Long): List<FileCacheEntity> {
        if (chatId == 0L) return emptyList()
        return runCatching {
            fileCacheDao.forChat(chatId).filter { entity ->
                if (entity.isLocked || entity.isEncryptedVault) return@filter false
                val mime = entity.mimeType.orEmpty().lowercase()
                val ext = entity.fileName.substringAfterLast('.', "").lowercase()
                mime.startsWith("video/") || ext in VIDEO_EXTS
            }
        }.getOrDefault(emptyList())
    }

    suspend fun recognizeBitmapText(bitmap: Bitmap): String = mediaAiIndexer.recognizeBitmapText(bitmap)

    suspend fun saveFileToPublicDownloads(
        source: File,
        displayName: String,
        mimeType: String,
    ): File? = withContext(dispatchers.io) {
        if (!source.isFile || source.length() <= 0L) return@withContext null
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { source.name }
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/TelegramDrive")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = appContext.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
                try {
                    resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
                source
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TelegramDrive")
                    .apply { mkdirs() }
                val dest = File(dir, safeName)
                source.copyTo(dest, overwrite = true)
                dest
            }
        }.getOrNull()
    }

    suspend fun saveEditedTextToDownloads(fileName: String, text: String): Boolean = withContext(dispatchers.io) {
        val dir = File(appContext.cacheDir, "text_edits").apply { mkdirs() }
        val tmp = File(dir, fileName.ifBlank { "edited.txt" })
        runCatching {
            tmp.writeText(text, Charsets.UTF_8)
            saveFileToPublicDownloads(tmp, tmp.name, "text/plain") != null
        }.getOrDefault(false)
    }

    fun reuploadEditedText(
        chatId: Long,
        fileName: String,
        text: String,
        virtualPath: String = "",
    ) {
        if (chatId == 0L) return
        viewModelScope.launch(dispatchers.io) {
            val dir = File(appContext.cacheDir, "text_edits").apply { mkdirs() }
            val safeName = fileName.replace(Regex("[/\\\\]"), "_").ifBlank { "edited.txt" }
            val out = File(dir, "${System.currentTimeMillis()}_$safeName")
            out.writeText(text, Charsets.UTF_8)
            val uri = Uri.fromFile(out)
            uploadManager.enqueueFiles(
                chatId = chatId,
                files = listOf(
                    UploadFileItem(
                        uri = uri,
                        displayName = safeName,
                        mimeType = "text/plain",
                        sizeBytes = out.length(),
                    ),
                ),
                options = NormalUploadOptions(
                    conflictStrategy = UploadConflictStrategy.REPLACE,
                    virtualPath = virtualPath,
                ),
            )
        }
    }

    suspend fun saveBitmapToPublicDownloads(bitmap: Bitmap, displayName: String): Boolean = withContext(dispatchers.io) {
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "frame.jpg" }
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/TelegramDrive")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = appContext.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                try {
                    val ok = resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    } ?: false
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    ok
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TelegramDrive")
                    .apply { mkdirs() }
                File(dir, safeName).outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }
        }.getOrDefault(false)
    }

    suspend fun updateUserNote(chatId: Long, messageId: Long, rawNote: String): String =
        withContext(dispatchers.io) {
            val cleaned = rawNote.trim().ifEmpty { null }
            runCatching {
                fileCacheDao.updateUserNote(chatId, messageId, cleaned)
                cloudSyncManager.noteUserNoteChanged(chatId, messageId, cleaned)
            }
            cleaned.orEmpty()
        }

    suspend fun saveCroppedCopyToDownloads(
        sourcePath: String,
        originalName: String,
        rotationDeg: Float,
        targetAspect: Float?,
    ): String? = withContext(dispatchers.io) {
        val decoded = runCatching { BitmapFactory.decodeFile(sourcePath) }.getOrNull() ?: return@withContext null
        var rotated: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            val normRot = ((rotationDeg % 360f) + 360f) % 360f
            val base = if (normRot > 0.5f) {
                val matrix = Matrix().apply { postRotate(normRot) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
                    rotated = it
                }
            } else {
                decoded
            }
            val resultBmp = if (targetAspect != null && targetAspect > 0f) {
                val w = base.width
                val h = base.height
                val curRatio = w.toFloat() / h.toFloat().coerceAtLeast(1f)
                val (cropW, cropH) = if (curRatio > targetAspect) {
                    (h * targetAspect).toInt().coerceIn(1, w) to h
                } else {
                    w to (w / targetAspect).toInt().coerceIn(1, h)
                }
                val x = ((w - cropW) / 2).coerceAtLeast(0)
                val y = ((h - cropH) / 2).coerceAtLeast(0)
                Bitmap.createBitmap(base, x, y, cropW, cropH).also { cropped = it }
            } else {
                base
            }

            val stem = originalName.substringBeforeLast('.', originalName).ifBlank { "photo" }
            val safeStem = stem.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val outName = "${safeStem}_edited.jpg"

            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, outName)
                    put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext null
                val ok = appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    resultBmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                } ?: false
                if (ok) outName else null
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val dest = File(dir, outName)
                val ok = dest.outputStream().use { out ->
                    resultBmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                if (ok) outName else null
            }
        } catch (t: Throwable) {
            Log.w("VideoPlayerViewModel: saveCroppedCopy failed", t)
            null
        } finally {
            if (cropped != null && cropped !== rotated && cropped !== decoded) cropped?.recycle()
            if (rotated != null && rotated !== decoded) rotated?.recycle()
            decoded.recycle()
        }
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

    fun forceRedownload(entry: FileCacheEntity) {
        if (entry.remoteId.isBlank()) return
        downloadManager.forceRedownload(
            DownloadItem(
                id = entry.messageId,
                chatId = entry.chatId,
                remoteId = entry.remoteId,
                fileName = entry.fileName,
                totalBytes = entry.fileSize,
            ),
        )
    }

    fun ensurePdfThumbnail(
        chatId: Long,
        messageId: Long,
        localPath: String,
        fileName: String,
    ) {
        if (chatId == 0L || messageId == 0L || localPath.isBlank()) return
        if (mediaThumbnailCache.getCachedThumbnailPath(chatId, messageId) != null) return
        persistScope.launch {
            runCatching {
                mediaThumbnailCache.generateFromLocalFile(
                    chatId = chatId,
                    messageId = messageId,
                    localPath = localPath,
                    mimeType = "application/pdf",
                    fileName = fileName,
                )
            }
        }
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

                var parsedLat = entry?.exifLat
                var parsedLng = entry?.exifLng
                val latLong = FloatArray(2)
                if (exif.getLatLong(latLong)) {
                    parsedLat = latLong[0].toDouble()
                    parsedLng = latLong[1].toDouble()
                    if (entry != null && entry.exifLat == null && !entry.isLocked && !entry.isEncryptedVault) {
                        runCatching {
                            fileCacheDao.updateExifLocation(entry.chatId, entry.messageId, parsedLat, parsedLng)
                            cloudSyncManager.noteAiIndexChanged(
                                chatId = entry.chatId,
                                messageId = entry.messageId,
                                ocrText = entry.ocrText,
                                aiTags = entry.aiTags,
                                exifLat = parsedLat,
                                exifLng = parsedLng,
                            )
                        }
                    }
                }
                if (parsedLat != null && parsedLng != null) {
                    location = "${String.format(Locale.US, "%.5f", parsedLat)}, ${String.format(Locale.US, "%.5f", parsedLng)}"
                }
            }
        } else if (entry?.exifLat != null && entry.exifLng != null) {
            location = "${String.format(Locale.US, "%.5f", entry.exifLat)}, ${String.format(Locale.US, "%.5f", entry.exifLng)}"
        }

        val finalLat = runCatching {
            localPath?.takeIf { File(it).isFile }?.let { p ->
                val arr = FloatArray(2)
                if (ExifInterface(p).getLatLong(arr)) arr[0].toDouble() else entry?.exifLat
            } ?: entry?.exifLat
        }.getOrNull()
        val finalLng = runCatching {
            localPath?.takeIf { File(it).isFile }?.let { p ->
                val arr = FloatArray(2)
                if (ExifInterface(p).getLatLong(arr)) arr[1].toDouble() else entry?.exifLng
            } ?: entry?.exifLng
        }.getOrNull()

        ImageExifInfo(
            fileName = entry?.fileName ?: localPath?.let { File(it).name }.orEmpty(),
            fileSize = entry?.fileSize ?: localPath?.let { File(it).length() } ?: 0L,
            width = width,
            height = height,
            dateTaken = dateTaken,
            camera = camera,
            exposure = exposure,
            location = location,
            latitude = finalLat,
            longitude = finalLng,
            userNote = entry?.userNote.orEmpty(),
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
@Composable
fun VideoPlayerScreen(
    messageId: Long,
    onBack: () -> Unit,
    chatId: Long = 0L,
    initialLocalPath: String? = null,
    vm: VideoPlayerViewModel = hiltViewModel(),
) {
    var activeMessageId by remember(chatId, messageId, initialLocalPath) { mutableLongStateOf(messageId) }
    var siblingVideos by remember(chatId, initialLocalPath) { mutableStateOf<List<FileCacheEntity>>(emptyList()) }

    LaunchedEffect(chatId, initialLocalPath) {
        if (chatId != 0L && initialLocalPath.isNullOrBlank()) {
            siblingVideos = vm.loadSiblingVideos(chatId)
        }
    }

    val currentIdx = siblingVideos.indexOfFirst { it.messageId == activeMessageId }
    val prevVideoId = if (currentIdx > 0) siblingVideos[currentIdx - 1].messageId else null
    val nextVideoId = if (currentIdx >= 0 && currentIdx < siblingVideos.lastIndex) siblingVideos[currentIdx + 1].messageId else null

    androidx.compose.runtime.key(chatId, activeMessageId, initialLocalPath) {
        VideoPlayerBody(
            messageId = activeMessageId,
            onBack = onBack,
            chatId = chatId,
            initialLocalPath = initialLocalPath,
            onPrevVideo = prevVideoId?.let { nextId -> { activeMessageId = nextId } },
            onNextVideo = nextVideoId?.let { nextId -> { activeMessageId = nextId } },
            vm = vm,
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoPlayerBody(
    messageId: Long,
    onBack: () -> Unit,
    chatId: Long = 0L,
    initialLocalPath: String? = null,
    onPrevVideo: (() -> Unit)? = null,
    onNextVideo: (() -> Unit)? = null,
    vm: VideoPlayerViewModel,
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
    var cachedEntry by remember(chatId, messageId, initialLocalPath) { mutableStateOf<FileCacheEntity?>(null) }
    var localPath by remember(chatId, messageId, initialLocalPath) {
        mutableStateOf<String?>(initialLocalPath?.takeIf { it.isNotBlank() } ?: vm.getLocalPath(messageId, chatId))
    }
    var playbackError by remember(chatId, messageId, initialLocalPath) { mutableStateOf<String?>(null) }
    var playerInitError by remember(chatId, messageId, initialLocalPath) { mutableStateOf<String?>(null) }
    var useProgressiveStream by remember(chatId, messageId, initialLocalPath) { mutableStateOf(false) }
    var isStreamingBuffering by remember(chatId, messageId, initialLocalPath) { mutableStateOf(false) }
    var streamFileId by remember(chatId, messageId, initialLocalPath) { mutableIntStateOf(0) }
    var fellBackToDownload by remember(chatId, messageId, initialLocalPath) { mutableStateOf(false) }
    var initialCloudSeekApplied by remember(chatId, messageId, initialLocalPath) { mutableStateOf(false) }

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
    var showAudioTrackSheet by remember { mutableStateOf(false) }
    var touchLocked by remember { mutableStateOf(false) }
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

    LaunchedEffect(chatId, messageId, initialLocalPath) {
        cachedEntry = if (initialLocalPath.isNullOrBlank()) vm.getCachedEntry(chatId, messageId) else null
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

    LaunchedEffect(chatId, messageId, initialLocalPath, downloadState.items, cachedEntry) {
        if (!initialLocalPath.isNullOrBlank()) {
            localPath = initialLocalPath
            useProgressiveStream = false
            isStreamingBuffering = false
            return@LaunchedEffect
        }
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
        if (!hasActiveVideo || chatId == 0L || messageId == 0L) return@LaunchedEffect
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
                    if (chatId != 0L && messageId != 0L) vm.savePlaybackPosition(chatId, messageId, pos, dur)
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
                    if (chatId != 0L && messageId != 0L) vm.savePlaybackPosition(chatId, messageId, pos, dur)
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
                                    useController = !inPip && !touchLocked
                                    this.resizeMode = targetResizeMode
                                    playerViewRef = this
                                }
                            },
                            update = { view ->
                                if (view.player !== exo) view.player = exo
                                view.useController = !inPip && !touchLocked
                                view.resizeMode = targetResizeMode
                                playerViewRef = view
                            },
                            onRelease = { view ->
                                view.player = null
                                if (playerViewRef === view) playerViewRef = null
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (!inPip && !touchLocked) {
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
                if (touchLocked && !inPip) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = {})
                            },
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 28.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { touchLocked = false },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = stringResource(R.string.player_video_touch_locked_hint),
                                    color = Color.White,
                                    style = TgTypography.Caption,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
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

        if (!inPip && !touchLocked) {
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onPrevVideo != null) {
                        IconButton(
                            onClick = onPrevVideo,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                        ) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = stringResource(R.string.player_video_prev),
                                tint = Color.White,
                            )
                        }
                    }
                    if (onNextVideo != null) {
                        IconButton(
                            onClick = onNextVideo,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                        ) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = stringResource(R.string.player_video_next),
                                tint = Color.White,
                            )
                        }
                    }
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
                            onClick = { showAudioTrackSheet = true },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                        ) {
                            Icon(
                                Icons.Filled.Audiotrack,
                                contentDescription = stringResource(R.string.player_video_audio_tracks),
                                tint = Color.White,
                            )
                        }
                        IconButton(
                            onClick = {
                                val srcPath = localPath
                                val posMs = exo.currentPosition.coerceAtLeast(0L)
                                if (srcPath != null) {
                                    scope.launch {
                                        val frame = withContext(Dispatchers.IO) {
                                            val retriever = MediaMetadataRetriever()
                                            try {
                                                retriever.setDataSource(srcPath)
                                                retriever.getFrameAtTime(
                                                    posMs * 1000L,
                                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                                )
                                            } catch (_: Throwable) {
                                                null
                                            } finally {
                                                runCatching { retriever.release() }
                                            }
                                        }
                                        val ok = if (frame != null) {
                                            try {
                                                vm.saveBitmapToPublicDownloads(
                                                    frame,
                                                    "frame_${messageId}_${posMs}.jpg",
                                                )
                                            } finally {
                                                if (!frame.isRecycled) frame.recycle()
                                            }
                                        } else {
                                            false
                                        }
                                        gestureHudText = ctx.getString(
                                            if (ok) R.string.player_video_frame_saved else R.string.player_save_failed,
                                        )
                                        gestureHudStamp++
                                    }
                                }
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                        ) {
                            Icon(
                                Icons.Filled.PhotoCamera,
                                contentDescription = stringResource(R.string.player_video_capture_frame),
                                tint = Color.White,
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
                        IconButton(
                            onClick = {
                                touchLocked = true
                                playerViewRef?.hideController()
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f)),
                        ) {
                            Icon(
                                Icons.Filled.LockOpen,
                                contentDescription = stringResource(R.string.player_video_touch_lock),
                                tint = Color.White,
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

    if (showAudioTrackSheet && exo != null) {
        val audioGroups = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        ModalBottomSheet(
            onDismissRequest = { showAudioTrackSheet = false },
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
                    text = stringResource(R.string.player_video_audio_tracks),
                    style = TgTypography.Display.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                audioGroups.forEachIndexed { gIdx, group ->
                    for (tIdx in 0 until group.length) {
                        val fmt = group.getTrackFormat(tIdx)
                        val trackName = listOfNotNull(
                            fmt.label?.takeIf { it.isNotBlank() },
                            fmt.language?.takeIf { it.isNotBlank() },
                            fmt.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
                        ).joinToString(" · ").ifBlank { "Audio ${gIdx + 1}.${tIdx + 1}" }
                        val selected = group.isTrackSelected(tIdx)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    exo.trackSelectionParameters = exo.trackSelectionParameters
                                        .buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, tIdx))
                                        .build()
                                    showAudioTrackSheet = false
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
                Spacer(Modifier.height(12.dp))
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
