package com.tgdrive.core.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.tgdrive.core.common.BitmapUtils
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.telegram.DownloadUpdate
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.zip.ZipUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates, caches, and persists 360px JPEG thumbnails in `cacheDir/media_thumbs/`
 * for locally available or uploaded files (images, videos, PDFs, and ZIP archives),
 * with bounded remote fallback for already-uploaded files whose Telegram message lacks a server thumbnail.
 */
@Singleton
class MediaThumbnailCache @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val telegramClient: TelegramClient,
    private val fileCacheDao: FileCacheDao,
    private val downloadManager: DownloadManager,
    private val offlinePinStore: OfflinePinStore,
    private val backupCrypto: BackupCrypto,
    private val cloudSyncManager: CloudSyncManager,
    private val appPreferences: AppPreferences,
    private val dispatchers: DispatchersProvider,
) {
    private val thumbsDir: File
        get() = File(ctx.cacheDir, "media_thumbs").apply { mkdirs() }

    private val encryptedThumbsDir: File
        get() = File(ctx.cacheDir, "encrypted_thumbs").apply { mkdirs() }

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    @Volatile private var maxDiskBytes: Long = DEFAULT_DISK_LIMIT_BYTES
    private val writeCounter = AtomicInteger(0)
    private val lastEvictMs = AtomicLong(0L)
    private val decryptedThumbCache = object : LruCache<String, ByteArray>(DECRYPTED_MEM_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }

    private data class PendingEncryptedThumbUpload(
        val chatId: Long,
        val messageId: Long,
        val bytes: ByteArray,
    )

    private val encryptedThumbUploadQueue = Channel<PendingEncryptedThumbUpload>(
        capacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val dbThumbBatchLock = Any()
    private val pendingDbThumbUpdates = LinkedHashMap<Pair<Long, Long>, String>()
    private val dbThumbFlushSignal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            runCatching {
                appPreferences.data.collect { prefs ->
                    maxDiskBytes = prefs.thumbnailCacheLimitBytes.coerceAtLeast(MIN_DISK_LIMIT_BYTES)
                }
            }
        }
        scope.launch {
            for (item in encryptedThumbUploadQueue) {
                runCatching {
                    cloudSyncManager.uploadEncryptedThumbBytes(item.chatId, item.messageId, item.bytes)
                }.onFailure { t ->
                    if (t is CancellationException) throw t
                    Log.w("MediaThumbnailCache: async encrypted thumb upload failed", t)
                }
                delay(150L)
            }
        }
        scope.launch {
            for (sig in dbThumbFlushSignal) {
                delay(200L)
                val batch = synchronized(dbThumbBatchLock) {
                    if (pendingDbThumbUpdates.isEmpty()) {
                        emptyList()
                    } else {
                        pendingDbThumbUpdates.entries.map { (k, path) ->
                            FileCacheDao.ThumbnailPathUpdate(
                                chatId = k.first,
                                messageId = k.second,
                                thumbnailPath = path,
                            )
                        }.also { pendingDbThumbUpdates.clear() }
                    }
                }
                if (batch.isNotEmpty()) {
                    runCatching { fileCacheDao.updateThumbnailPaths(batch) }
                }
            }
        }
    }

    private val fastThumbSemaphore = Semaphore(6)
    private val heavyFallbackSemaphore = Semaphore(2)
    private val inFlight = ConcurrentHashMap<Pair<Long, Long>, Deferred<String?>>()
    private val failedAtMs = ConcurrentHashMap<Pair<Long, Long>, Long>()
    private val encInFlight = ConcurrentHashMap<Pair<Long, Long>, Deferred<String?>>()
    private val encFailedAtMs = ConcurrentHashMap<Pair<Long, Long>, Long>()

    private val _thumbnailReady = MutableSharedFlow<Pair<Long, Long>>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val thumbnailReady: SharedFlow<Pair<Long, Long>> = _thumbnailReady.asSharedFlow()

    fun clearThumbnailFailure(chatId: Long, messageId: Long) {
        val key = chatId to messageId
        failedAtMs.remove(key)
        encFailedAtMs.remove(key)
    }

    private fun enqueueDbThumbUpdate(chatId: Long, messageId: Long, path: String) {
        synchronized(dbThumbBatchLock) {
            pendingDbThumbUpdates[chatId to messageId] = path
        }
        dbThumbFlushSignal.trySend(Unit)
    }

    fun clearDecryptedMemoryCache() {
        decryptedThumbCache.evictAll()
    }

    private fun touchIfStale(file: File) {
        val now = System.currentTimeMillis()
        if (now - file.lastModified() > LRU_TOUCH_INTERVAL_MS) {
            runCatching { file.setLastModified(now) }
        }
    }

    private fun maybeEvictDiskCache() {
        val count = writeCounter.incrementAndGet()
        val now = System.currentTimeMillis()
        val prev = lastEvictMs.get()
        if ((count % EVICT_WRITE_STRIDE == 0 || now - prev >= EVICT_COOLDOWN_MS) &&
            lastEvictMs.compareAndSet(prev, now)
        ) {
            runCatching {
                evictOldestFilesToCap(
                    dirs = listOf(thumbsDir, encryptedThumbsDir),
                    maxBytes = maxDiskBytes,
                )
            }
        }
    }

    fun getCachedThumbnailPath(chatId: Long, messageId: Long): String? {
        val webpFile = File(thumbsDir, "${chatId}_$messageId.webp")
        if (webpFile.isFile && webpFile.length() > 0L) {
            touchIfStale(webpFile)
            return webpFile.absolutePath
        }
        val jpgFile = File(thumbsDir, "${chatId}_$messageId.jpg")
        return jpgFile.takeIf { it.isFile && it.length() > 0L }?.also(::touchIfStale)?.absolutePath
    }

    suspend fun promotePrebuiltThumbnail(
        chatId: Long,
        messageId: Long,
        sourcePath: String,
    ): String? = withContext(dispatchers.io) {
        val src = File(sourcePath)
        if (!src.isFile || src.length() <= 0L) return@withContext null
        try {
            val rawBmp = decodeSampledImageFile(sourcePath)
            val saved = if (rawBmp != null && !rawBmp.isRecycled) {
                writeThumbnailJpeg(chatId, messageId, rawBmp)
            } else {
                val ext = src.extension.lowercase().ifBlank { "jpg" }
                val dest = File(thumbsDir, "${chatId}_$messageId.$ext")
                src.copyTo(dest, overwrite = true)
                maybeEvictDiskCache()
                dest.takeIf { it.isFile && it.length() > 0L }?.absolutePath
            }
            if (saved != null) {
                failedAtMs.remove(chatId to messageId)
                runCatching { fileCacheDao.updateThumbnailPath(chatId, messageId, saved) }
                _thumbnailReady.tryEmit(chatId to messageId)
            }
            saved
        } finally {
            if (sourcePath.contains("/upload_thumbs/") ||
                sourcePath.contains("${File.separator}upload_thumbs${File.separator}")
            ) {
                runCatching { src.delete() }
            }
        }
    }

    fun isValidThumbnailPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val f = File(path)
        return f.isFile && f.length() > 0L
    }

    fun needsThumbnailUpgrade(path: String?): Boolean =
        path.isNullOrBlank() || path.contains("/minithumbs/") || !isValidThumbnailPath(path)

    suspend fun generateFromLocalFile(
        chatId: Long,
        messageId: Long,
        localPath: String,
        mimeType: String?,
        fileName: String,
    ): String? = withContext(dispatchers.io) {
        val src = File(localPath)
        if (!src.isFile || src.length() <= 0L) return@withContext null

        val bucket = classifyFile(mimeType, fileName)
        val rawBmp: Bitmap? = when {
            bucket == FileBucket.IMAGES -> decodeSampledImageFile(localPath)
            bucket == FileBucket.VIDEOS -> decodeVideoFrame(localPath)
            bucket == FileBucket.PDF -> decodePdfFirstPage(localPath)
            bucket == FileBucket.ARCHIVES && fileName.endsWith(".zip", ignoreCase = true) -> {
                decodeZipPreviewCollage(localPath, password = null) ?: return@withContext null
            }
            else -> null
        }
        if (rawBmp == null || rawBmp.isRecycled) return@withContext null

        val saved = writeThumbnailJpeg(chatId, messageId, rawBmp)
        if (saved != null) {
            failedAtMs.remove(chatId to messageId)
            runCatching { fileCacheDao.updateThumbnailPath(chatId, messageId, saved) }
            _thumbnailReady.tryEmit(chatId to messageId)
        }
        saved
    }

    /**
     * Returns an existing local `.tgethumb` path for `(chatId, messageId)` if already cached.
     */
    fun getEncryptedThumbnailPath(chatId: Long, messageId: Long): String? {
        val f = File(encryptedThumbsDir, "${chatId}_${messageId}$ENCRYPTED_THUMB_EXT")
        return f.takeIf { it.isFile && it.length() > 0L }?.also(::touchIfStale)?.absolutePath
    }

    /**
     * Encodes a scaled JPEG preview in memory and writes an AES-256 `TGE1` encrypted sidecar
     * under `encrypted_thumbs/` without ever storing plaintext thumbnail bytes on disk.
     */
    suspend fun buildEncryptedThumbnailBytes(
        localPath: String,
        mimeType: String?,
        originalFileName: String,
        zipPassword: String? = null,
    ): ByteArray? = withContext(dispatchers.io) {
        val src = File(localPath)
        if (!src.isFile || src.length() <= 0L) return@withContext null
        val bucket = classifyFile(mimeType, originalFileName)
        val rawBmp: Bitmap? = when {
            bucket == FileBucket.IMAGES -> decodeSampledImageFile(localPath)
            bucket == FileBucket.VIDEOS -> decodeVideoFrame(localPath)
            bucket == FileBucket.PDF -> decodePdfFirstPage(localPath)
            bucket == FileBucket.ARCHIVES && originalFileName.endsWith(".zip", ignoreCase = true) -> {
                decodeZipPreviewCollage(localPath, password = zipPassword)
            }
            else -> null
        }
        if (rawBmp == null || rawBmp.isRecycled) return@withContext null
        var scaled: Bitmap = rawBmp
        return@withContext try {
            scaled = BitmapUtils.scaleToMax(rawBmp, TARGET_MAX_PX)
            val jpegOut = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, jpegOut)
            val plainBytes = jpegOut.toByteArray()
            if (plainBytes.isEmpty()) return@withContext null
            backupCrypto.encryptThumbnailBytes(plainBytes)
        } catch (t: Throwable) {
            Log.w("MediaThumbnailCache: buildEncryptedThumbnailBytes failed", t)
            null
        } finally {
            if (scaled !== rawBmp && !scaled.isRecycled) scaled.recycle()
            if (!rawBmp.isRecycled) rawBmp.recycle()
        }
    }

    suspend fun buildEncryptedCollageBytes(bitmaps: List<Bitmap>): ByteArray? = withContext(dispatchers.io) {
        val collage = buildCollageBitmap(bitmaps) ?: return@withContext null
        try {
            val jpegOut = ByteArrayOutputStream()
            collage.compress(Bitmap.CompressFormat.JPEG, 82, jpegOut)
            val plain = jpegOut.toByteArray()
            if (plain.isEmpty()) return@withContext null
            backupCrypto.encryptThumbnailBytes(plain)
        } catch (t: Throwable) {
            Log.w("MediaThumbnailCache: buildEncryptedCollageBytes failed", t)
            null
        } finally {
            if (!collage.isRecycled) collage.recycle()
        }
    }

    suspend fun saveAndSyncEncryptedThumbBytes(
        chatId: Long,
        messageId: Long,
        encryptedBytes: ByteArray,
    ): String? = withContext(dispatchers.io) {
        val path = saveEncryptedThumbnailBytes(chatId, messageId, encryptedBytes) ?: return@withContext null
        runCatching { fileCacheDao.updateThumbnailPath(chatId, messageId, path) }
        encryptedThumbUploadQueue.trySend(PendingEncryptedThumbUpload(chatId, messageId, encryptedBytes))
        path
    }

    suspend fun generateAndSyncEncryptedThumb(
        chatId: Long,
        messageId: Long,
        localPath: String,
        mimeType: String?,
        originalFileName: String,
        zipPassword: String? = null,
    ): String? = withContext(dispatchers.io) {
        val bytes = buildEncryptedThumbnailBytes(
            localPath = localPath,
            mimeType = mimeType,
            originalFileName = originalFileName,
            zipPassword = zipPassword,
        ) ?: return@withContext null
        saveAndSyncEncryptedThumbBytes(chatId, messageId, bytes)
    }

    suspend fun restoreEncryptedThumbFromCloud(
        chatId: Long,
        messageId: Long,
    ): String? = withContext(dispatchers.io) {
        getEncryptedThumbnailPath(chatId, messageId)?.let { return@withContext it }
        val key = chatId to messageId
        val prevFail = encFailedAtMs[key]
        if (prevFail != null && System.currentTimeMillis() - prevFail < FAIL_COOLDOWN_MS) {
            return@withContext null
        }
        encInFlight[key]?.let { existing ->
            return@withContext existing.await()
        }
        val deferred = CompletableDeferred<String?>()
        val race = encInFlight.putIfAbsent(key, deferred)
        if (race != null) {
            return@withContext race.await()
        }
        try {
            val path = fastThumbSemaphore.withPermit {
                val bytes = withTimeoutOrNull(15_000L) {
                    cloudSyncManager.restoreEncryptedThumbBytes(chatId, messageId)
                } ?: return@withPermit null
                saveEncryptedThumbnailBytes(chatId, messageId, bytes)
            }
            if (path != null) {
                encFailedAtMs.remove(key)
                enqueueDbThumbUpdate(chatId, messageId, path)
                _thumbnailReady.tryEmit(key)
            } else {
                encFailedAtMs[key] = System.currentTimeMillis()
            }
            deferred.complete(path)
            path
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw e
        } catch (_: Throwable) {
            encFailedAtMs[key] = System.currentTimeMillis()
            deferred.complete(null)
            null
        } finally {
            encInFlight.remove(key, deferred)
        }
    }

    private fun decodeZipPreviewCollage(zipPath: String, password: String?): Bitmap? {
        val previewBytes = ZipUploader.readPreviewImageEntriesBytes(
            zipPath = zipPath,
            maxCount = 4,
            password = password,
        )
        if (previewBytes.isEmpty()) return null
        val bitmaps = ArrayList<Bitmap>(previewBytes.size)
        for (bytes in previewBytes) {
            val decoded = decodeSampledImageBytes(bytes)
            if (decoded != null && !decoded.isRecycled) {
                bitmaps.add(decoded)
            }
        }
        if (bitmaps.isEmpty()) return null
        if (bitmaps.size == 1) return bitmaps[0]
        return try {
            buildCollageBitmap(bitmaps)
        } finally {
            for (b in bitmaps) {
                if (!b.isRecycled) b.recycle()
            }
        }
    }

    /**
     * Composes 1..4 bitmaps into a crisp square collage (single scaled for 1, split for 2,
     * 1+2 for 3, and 2x2 grid for 4). Caller retains ownership of input [bitmaps].
     */
    fun buildCollageBitmap(
        bitmaps: List<Bitmap>,
        sizePx: Int = TARGET_MAX_PX,
    ): Bitmap? = BitmapUtils.composeCollageBitmap(bitmaps, sizePx)

    fun saveEncryptedThumbnailBytes(
        chatId: Long,
        messageId: Long,
        encryptedBytes: ByteArray,
    ): String? {
        if (encryptedBytes.isEmpty()) return null
        return runCatching {
            val out = File(encryptedThumbsDir, "${chatId}_${messageId}$ENCRYPTED_THUMB_EXT")
            out.writeBytes(encryptedBytes)
            maybeEvictDiskCache()
            out.absolutePath
        }.getOrNull()
    }

    suspend fun decryptThumbnailToBytes(encryptedThumbPath: String): ByteArray? = withContext(dispatchers.io) {
        decryptedThumbCache.get(encryptedThumbPath)?.let { return@withContext it }
        val f = File(encryptedThumbPath)
        if (!f.isFile || f.length() <= 0L) return@withContext null
        touchIfStale(f)
        runCatching {
            val decrypted = backupCrypto.decryptThumbnailBytes(f.readBytes()) ?: return@runCatching null
            if (decrypted.isNotEmpty() && decrypted.size <= MAX_CACHED_SINGLE_THUMB_BYTES) {
                decryptedThumbCache.put(encryptedThumbPath, decrypted)
            }
            decrypted
        }.getOrNull()
    }

    suspend fun ensureThumbnail(entity: FileCacheEntity, force: Boolean = false): String? {
        if (entity.thumbnailPath != null &&
            !entity.thumbnailPath!!.contains("/minithumbs/") &&
            isValidThumbnailPath(entity.thumbnailPath)
        ) {
            return entity.thumbnailPath
        }

        getCachedThumbnailPath(entity.chatId, entity.messageId)?.let { cached ->
            if (entity.thumbnailPath != cached) {
                enqueueDbThumbUpdate(entity.chatId, entity.messageId, cached)
            }
            return cached
        }

        val key = entity.chatId to entity.messageId
        if (force) {
            failedAtMs.remove(key)
        }
        val now = System.currentTimeMillis()
        val prevFail = failedAtMs[key]
        if (prevFail != null && now - prevFail < FAIL_COOLDOWN_MS) {
            return null
        }

        inFlight[key]?.let { existing ->
            return existing.await()
        }

        val deferred = CompletableDeferred<String?>()
        val race = inFlight.putIfAbsent(key, deferred)
        if (race != null) {
            return race.await()
        }

        return try {
            val resolved = resolveThumbnailInternal(entity)
            if (resolved == null) {
                failedAtMs[key] = System.currentTimeMillis()
            } else {
                failedAtMs.remove(key)
                _thumbnailReady.tryEmit(key)
            }
            deferred.complete(resolved)
            resolved
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw e
        } catch (t: Throwable) {
            failedAtMs[key] = System.currentTimeMillis()
            deferred.complete(null)
            null
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    private suspend fun resolveThumbnailInternal(entity: FileCacheEntity): String? = withContext(dispatchers.io) {
        if (entity.isLocked || entity.isEncryptedVault || BackupCrypto.isEncryptedFileName(entity.fileName)) {
            return@withContext null
        }
        val pinned = offlinePinStore.getPinnedFilePathSync(entity.chatId, entity.messageId)
        val dlLocal = downloadManager.findItem(entity.chatId, entity.messageId)
            ?.takeIf { it.status == DownloadStatus.DONE }
            ?.localPath
            ?.takeIf { File(it).isFile }
        val local = pinned ?: dlLocal
        if (local != null) {
            return@withContext generateFromLocalFile(
                entity.chatId,
                entity.messageId,
                local,
                entity.mimeType,
                entity.fileName,
            )
        }

        if (entity.remoteId.isEmpty()) return@withContext null

        val bucket = classifyFile(entity.mimeType, entity.fileName)
        if ((bucket == FileBucket.IMAGES ||
                bucket == FileBucket.VIDEOS ||
                bucket == FileBucket.PDF ||
                bucket == FileBucket.DOCUMENTS) &&
            entity.chatId != 0L &&
            entity.messageId != 0L
        ) {
            val serverThumb = fastThumbSemaphore.withPermit {
                withTimeoutOrNull(10_000L) {
                    runCatching {
                        telegramClient.requestMessageThumbnail(entity.chatId, entity.messageId)
                    }.getOrNull()
                }
            }
            if (serverThumb != null && isValidThumbnailPath(serverThumb)) {
                enqueueDbThumbUpdate(entity.chatId, entity.messageId, serverThumb)
                if (!serverThumb.contains("/minithumbs/")) {
                    return@withContext serverThumb
                }
            }
        }

        return@withContext when (bucket) {
            FileBucket.IMAGES -> {
                if (entity.fileSize > MAX_REMOTE_IMAGE_BYTES) return@withContext null
                heavyFallbackSemaphore.withPermit {
                    var activeRemoteId = entity.remoteId
                    var terminal = withTimeoutOrNull(20_000L) {
                        telegramClient.downloadFile(activeRemoteId, "")
                            .filter { it is DownloadUpdate.Done || it is DownloadUpdate.Failed }
                            .first()
                    }
                    if (terminal !is DownloadUpdate.Done && entity.chatId != 0L && entity.messageId != 0L) {
                        val refreshed = telegramClient.refreshMessageRemoteId(entity.chatId, entity.messageId)
                        if (!refreshed.isNullOrBlank() && refreshed != activeRemoteId) {
                            activeRemoteId = refreshed
                            terminal = withTimeoutOrNull(20_000L) {
                                telegramClient.downloadFile(activeRemoteId, "")
                                    .filter { it is DownloadUpdate.Done || it is DownloadUpdate.Failed }
                                    .first()
                            }
                        }
                    }
                    val done = terminal as? DownloadUpdate.Done ?: return@withPermit null
                    generateFromLocalFile(
                        entity.chatId,
                        entity.messageId,
                        done.localPath,
                        entity.mimeType,
                        entity.fileName,
                    )
                }
            }
            FileBucket.VIDEOS -> {
                heavyFallbackSemaphore.withPermit {
                    var activeRemoteId = entity.remoteId
                    var streamInfo = telegramClient.openStreamFile(activeRemoteId)
                    if (streamInfo == null && entity.chatId != 0L && entity.messageId != 0L) {
                        val refreshed = telegramClient.refreshMessageRemoteId(entity.chatId, entity.messageId)
                        if (!refreshed.isNullOrBlank() && refreshed != activeRemoteId) {
                            activeRemoteId = refreshed
                            streamInfo = telegramClient.openStreamFile(activeRemoteId)
                        }
                    }
                    if (streamInfo != null && streamInfo.size > 0L) {
                        val dsFrame = try {
                            withTimeoutOrNull(12_000L) {
                                decodeVideoFrameFromDataSource(
                                    TdLibRangeMediaDataSource(
                                        telegramClient = telegramClient,
                                        fileId = streamInfo.fileId,
                                        fileSize = streamInfo.size,
                                        ioDispatcher = dispatchers.io,
                                    ),
                                )
                            }
                        } finally {
                            runCatching { telegramClient.cancelFileStream(streamInfo.fileId) }
                        }
                        if (dsFrame != null) {
                            val saved = writeThumbnailJpeg(entity.chatId, entity.messageId, dsFrame)
                            if (saved != null) {
                                enqueueDbThumbUpdate(entity.chatId, entity.messageId, saved)
                                return@withPermit saved
                            }
                        }
                    }

                    var bytes = withTimeoutOrNull(15_000L) {
                        telegramClient.downloadFileRangeBytes(
                            remoteId = activeRemoteId,
                            offset = 0L,
                            length = VIDEO_PEEK_BYTES,
                        )
                    }
                    if ((bytes == null || bytes.isEmpty()) && entity.chatId != 0L && entity.messageId != 0L) {
                        val refreshed = telegramClient.refreshMessageRemoteId(entity.chatId, entity.messageId)
                        if (!refreshed.isNullOrBlank() && refreshed != activeRemoteId) {
                            activeRemoteId = refreshed
                            bytes = withTimeoutOrNull(15_000L) {
                                telegramClient.downloadFileRangeBytes(
                                    remoteId = activeRemoteId,
                                    offset = 0L,
                                    length = VIDEO_PEEK_BYTES,
                                )
                            }
                        }
                    }
                    if (bytes == null || bytes.isEmpty()) return@withPermit null

                    val ext = entity.fileName.substringAfterLast('.', "mp4")
                        .lowercase()
                        .replace(Regex("[^a-z0-9]"), "")
                        .ifBlank { "mp4" }
                    val tmpVideo = File(thumbsDir, "tmp_vid_${entity.chatId}_${entity.messageId}.$ext")
                    try {
                        tmpVideo.writeBytes(bytes)
                        val frame = decodeVideoFrame(tmpVideo.absolutePath) ?: return@withPermit null
                        val saved = writeThumbnailJpeg(entity.chatId, entity.messageId, frame)
                        if (saved != null) {
                            enqueueDbThumbUpdate(entity.chatId, entity.messageId, saved)
                        }
                        saved
                    } finally {
                        runCatching { tmpVideo.delete() }
                    }
                }
            }
            FileBucket.PDF -> {
                if (entity.fileSize > MAX_REMOTE_PDF_BYTES) return@withContext null
                heavyFallbackSemaphore.withPermit {
                    var activeRemoteId = entity.remoteId
                    var terminal = withTimeoutOrNull(20_000L) {
                        telegramClient.downloadFile(activeRemoteId, "")
                            .filter { it is DownloadUpdate.Done || it is DownloadUpdate.Failed }
                            .first()
                    }
                    if (terminal !is DownloadUpdate.Done && entity.chatId != 0L && entity.messageId != 0L) {
                        val refreshed = telegramClient.refreshMessageRemoteId(entity.chatId, entity.messageId)
                        if (!refreshed.isNullOrBlank() && refreshed != activeRemoteId) {
                            activeRemoteId = refreshed
                            terminal = withTimeoutOrNull(20_000L) {
                                telegramClient.downloadFile(activeRemoteId, "")
                                    .filter { it is DownloadUpdate.Done || it is DownloadUpdate.Failed }
                                    .first()
                            }
                        }
                    }
                    val done = terminal as? DownloadUpdate.Done ?: return@withPermit null
                    generateFromLocalFile(
                        entity.chatId,
                        entity.messageId,
                        done.localPath,
                        entity.mimeType,
                        entity.fileName,
                    )
                }
            }
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun thumbFormatAndExt(): Pair<Bitmap.CompressFormat, String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY to "webp"
        } else {
            Bitmap.CompressFormat.WEBP to "webp"
        }

    private fun writeThumbnailJpeg(
        chatId: Long,
        messageId: Long,
        rawBmp: Bitmap,
    ): String? {
        val (compressFormat, ext) = thumbFormatAndExt()
        val outFile = File(thumbsDir, "${chatId}_$messageId.$ext")
        val tmpFile = File(thumbsDir, "${chatId}_$messageId.tmp")
        var scaled: Bitmap = rawBmp
        return try {
            scaled = BitmapUtils.scaleToMax(rawBmp, TARGET_MAX_PX)
            FileOutputStream(tmpFile).use { fos ->
                scaled.compress(compressFormat, 78, fos)
            }
            if (tmpFile.isFile && tmpFile.length() > 0L) {
                if (outFile.exists()) outFile.delete()
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.copyTo(outFile, overwrite = true)
                    tmpFile.delete()
                }
                maybeEvictDiskCache()
                outFile.absolutePath
            } else {
                tmpFile.delete()
                null
            }
        } catch (t: Throwable) {
            tmpFile.delete()
            Log.w("MediaThumbnailCache: failed saving thumb for $chatId/$messageId", t)
            null
        } finally {
            if (scaled !== rawBmp && !scaled.isRecycled) scaled.recycle()
            if (!rawBmp.isRecycled) rawBmp.recycle()
        }
    }

    private fun decodeSampledImageFile(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = BitmapUtils.computeInSampleSize(bounds.outWidth, bounds.outHeight, TARGET_MAX_PX)
        val bmp = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        return BitmapUtils.applyExifOrientationFromFile(path, bmp)
    }

    private fun decodeSampledImageBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = BitmapUtils.computeInSampleSize(bounds.outWidth, bounds.outHeight, TARGET_MAX_PX)
        val bmp = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        return BitmapUtils.applyExifOrientationFromBytes(bytes, bmp)
    }

    private fun decodeVideoFrame(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodePdfFirstPage(path: String): Bitmap? = synchronized(PDF_RENDER_LOCK) {
        val srcFile = File(path)
        if (!srcFile.isFile || srcFile.length() < 5L) return@synchronized null
        val pfd = runCatching {
            ParcelFileDescriptor.open(srcFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull() ?: return@synchronized null
        pfd.use { fd ->
            runCatching {
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount <= 0) return@runCatching null
                    renderer.openPage(0).use { page ->
                        val (targetW, targetH) = computePdfThumbDimensions(page.width, page.height, TARGET_MAX_PX)
                        val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }.onFailure { t ->
                Log.w("MediaThumbnailCache: failed rendering PDF thumb for ${srcFile.name}", t)
            }.getOrNull()
        }
    }

    private fun decodeVideoFrameFromDataSource(source: MediaDataSource): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { source.close() }
        }
    }

    private class TdLibRangeMediaDataSource(
        private val telegramClient: TelegramClient,
        private val fileId: Int,
        private val fileSize: Long,
        private val ioDispatcher: CoroutineDispatcher,
    ) : MediaDataSource() {
        private val blocks = object : java.util.LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > 8
        }
        @Volatile private var closed = false

        override fun getSize(): Long = fileSize

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (closed || position < 0L || position >= fileSize) return -1
            if (size == 0) return 0
            val toRead = minOf(size.toLong(), fileSize - position).toInt()
            var copied = 0
            var pos = position
            while (copied < toRead && !closed) {
                val blockIdx = pos / BLOCK_SIZE
                val inBlockOffset = (pos % BLOCK_SIZE).toInt()
                val block = getBlock(blockIdx) ?: break
                if (inBlockOffset >= block.size) break
                val n = minOf(toRead - copied, block.size - inBlockOffset)
                System.arraycopy(block, inBlockOffset, buffer, offset + copied, n)
                copied += n
                pos += n
            }
            return if (copied > 0) copied else -1
        }

        private fun getBlock(blockIdx: Long): ByteArray? {
            synchronized(blocks) {
                blocks[blockIdx]?.let { return it }
            }
            val blockOffset = blockIdx * BLOCK_SIZE
            if (blockOffset >= fileSize) return null
            val wantLen = minOf(BLOCK_SIZE.toLong(), fileSize - blockOffset)
            val state = runCatching {
                runBlocking(ioDispatcher) {
                    withTimeoutOrNull(4_000L) {
                        telegramClient.ensureFileRangeDownloaded(fileId, blockOffset, wantLen)
                    }
                }
            }.getOrNull() ?: return null
            if (state.localPath.isEmpty()) return null
            val f = File(state.localPath)
            if (!f.isFile) return null
            val available = when {
                state.isCompleted -> wantLen
                state.downloadOffset > blockOffset -> 0L
                else -> (state.downloadOffset + state.downloadedPrefixSize - blockOffset).coerceIn(0L, wantLen)
            }
            if (available <= 0L) return null
            val data = runCatching {
                RandomAccessFile(f, "r").use { raf ->
                    val len = minOf(available, (f.length() - blockOffset).coerceAtLeast(0L)).toInt()
                    if (len <= 0) return@use ByteArray(0)
                    ByteArray(len).also { buf ->
                        raf.seek(blockOffset)
                        raf.readFully(buf)
                    }
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            synchronized(blocks) {
                blocks[blockIdx] = data
            }
            return data
        }

        override fun close() {
            closed = true
            synchronized(blocks) { blocks.clear() }
        }

        private companion object {
            const val BLOCK_SIZE = 256 * 1024
        }
    }

    companion object {
        const val ENCRYPTED_THUMB_EXT = ".tgethumb"
        private const val TARGET_MAX_PX = 360
        private const val MAX_REMOTE_IMAGE_BYTES = 20L * 1024 * 1024
        private const val MAX_REMOTE_PDF_BYTES = 25L * 1024 * 1024
        private const val VIDEO_PEEK_BYTES = 2L * 1024 * 1024
        private const val FAIL_COOLDOWN_MS = 45_000L
        private const val DEFAULT_DISK_LIMIT_BYTES = 128L * 1024 * 1024
        private const val MIN_DISK_LIMIT_BYTES = 10L * 1024 * 1024
        private const val EVICT_WRITE_STRIDE = 20
        private const val EVICT_COOLDOWN_MS = 30_000L
        private const val LRU_TOUCH_INTERVAL_MS = 3_600_000L
        private const val DECRYPTED_MEM_CACHE_BYTES = 8 * 1024 * 1024
        private const val MAX_CACHED_SINGLE_THUMB_BYTES = 2 * 1024 * 1024
        private val PDF_RENDER_LOCK = Any()

        internal fun evictOldestFilesToCap(
            dirs: List<File>,
            maxBytes: Long,
        ): Int {
            if (maxBytes <= 0L) return 0
            val files = dirs
                .asSequence()
                .filter { it.isDirectory }
                .flatMap { dir -> (dir.listFiles() ?: emptyArray()).asSequence() }
                .filter { it.isFile }
                .toMutableList()
            if (files.isEmpty()) return 0

            var totalBytes = files.sumOf { it.length() }
            if (totalBytes <= maxBytes) return 0

            val targetBytes = (maxBytes * 85L) / 100L
            files.sortBy { it.lastModified() }
            var evicted = 0
            for (file in files) {
                if (totalBytes <= targetBytes) break
                val len = file.length()
                if (file.delete()) {
                    totalBytes -= len
                    evicted++
                }
            }
            return evicted
        }

        internal fun computePdfThumbDimensions(
            pageWidth: Int,
            pageHeight: Int,
            maxDim: Int = TARGET_MAX_PX,
        ): Pair<Int, Int> {
            val w = pageWidth.coerceAtLeast(1)
            val h = pageHeight.coerceAtLeast(1)
            val scale = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
            val targetW = (w * scale).toInt().coerceIn(48, maxDim)
            val targetH = (h * scale).toInt().coerceIn(48, maxDim)
            return targetW to targetH
        }
    }
}
