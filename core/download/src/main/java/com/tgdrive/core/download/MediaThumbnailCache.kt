package com.tgdrive.core.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
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
    private val dispatchers: DispatchersProvider,
) {
    private val thumbsDir: File
        get() = File(ctx.cacheDir, "media_thumbs").apply { mkdirs() }

    private val encryptedThumbsDir: File
        get() = File(ctx.cacheDir, "encrypted_thumbs").apply { mkdirs() }

    private val semaphore = Semaphore(2)
    private val inFlight = ConcurrentHashMap<Pair<Long, Long>, Deferred<String?>>()
    private val failedAtMs = ConcurrentHashMap<Pair<Long, Long>, Long>()

    fun getCachedThumbnailPath(chatId: Long, messageId: Long): String? {
        val file = File(thumbsDir, "${chatId}_$messageId.jpg")
        return file.takeIf { it.isFile && it.length() > 0L }?.absolutePath
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
            runCatching { fileCacheDao.updateThumbnailPath(chatId, messageId, saved) }
        }
        saved
    }

    /**
     * Returns an existing local `.tgethumb` path for `(chatId, messageId)` if already cached.
     */
    fun getEncryptedThumbnailPath(chatId: Long, messageId: Long): String? {
        val f = File(encryptedThumbsDir, "${chatId}_${messageId}$ENCRYPTED_THUMB_EXT")
        return f.absolutePath.takeIf { f.isFile && f.length() > 0L }
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
            scaled = scaleToMax(rawBmp, TARGET_MAX_PX)
            val jpegOut = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, jpegOut)
            val plainBytes = jpegOut.toByteArray()
            if (plainBytes.isEmpty()) return@withContext null
            val encOut = ByteArrayOutputStream()
            BackupCrypto.encryptStream(backupCrypto.vaultKey(), ByteArrayInputStream(plainBytes), encOut)
            encOut.toByteArray()
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
            val encOut = ByteArrayOutputStream()
            BackupCrypto.encryptStream(backupCrypto.vaultKey(), ByteArrayInputStream(plain), encOut)
            encOut.toByteArray()
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
        runCatching { cloudSyncManager.uploadEncryptedThumbBytes(chatId, messageId, encryptedBytes) }
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
        val bytes = cloudSyncManager.restoreEncryptedThumbBytes(chatId, messageId) ?: return@withContext null
        val path = saveEncryptedThumbnailBytes(chatId, messageId, bytes) ?: return@withContext null
        runCatching { fileCacheDao.updateThumbnailPath(chatId, messageId, path) }
        path
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
    ): Bitmap? {
        val valid = ArrayList<Bitmap>(4)
        for (bmp in bitmaps) {
            if (!bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                valid.add(bmp)
                if (valid.size >= 4) break
            }
        }
        if (valid.isEmpty()) return null
        if (valid.size == 1) return scaleToMax(valid[0], sizePx)

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(20, 24, 32))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val half = sizePx / 2
        val gap = 2
        val slots: List<Rect> = when (valid.size) {
            2 -> listOf(
                Rect(0, 0, half - gap / 2, sizePx),
                Rect(half + gap / 2, 0, sizePx, sizePx),
            )
            3 -> listOf(
                Rect(0, 0, half - gap / 2, half - gap / 2),
                Rect(half + gap / 2, 0, sizePx, half - gap / 2),
                Rect(0, half + gap / 2, sizePx, sizePx),
            )
            else -> listOf(
                Rect(0, 0, half - gap / 2, half - gap / 2),
                Rect(half + gap / 2, 0, sizePx, half - gap / 2),
                Rect(0, half + gap / 2, half - gap / 2, sizePx),
                Rect(half + gap / 2, half + gap / 2, sizePx, sizePx),
            )
        }
        for (i in slots.indices) {
            val bmp = valid[i]
            val dst = slots[i]
            val src = centerCropSrcRect(bmp.width, bmp.height, dst.width(), dst.height())
            canvas.drawBitmap(bmp, src, dst, paint)
        }
        return out
    }

    private fun centerCropSrcRect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
        if (dstW <= 0 || dstH <= 0) return Rect(0, 0, srcW, srcH)
        val srcRatio = srcW.toFloat() / srcH.toFloat()
        val dstRatio = dstW.toFloat() / dstH.toFloat()
        return if (srcRatio > dstRatio) {
            val cropW = (srcH * dstRatio).toInt().coerceIn(1, srcW)
            val left = (srcW - cropW) / 2
            Rect(left, 0, left + cropW, srcH)
        } else {
            val cropH = (srcW / dstRatio).toInt().coerceIn(1, srcH)
            val top = (srcH - cropH) / 2
            Rect(0, top, srcW, top + cropH)
        }
    }

    fun saveEncryptedThumbnailBytes(
        chatId: Long,
        messageId: Long,
        encryptedBytes: ByteArray,
    ): String? {
        if (encryptedBytes.isEmpty()) return null
        return runCatching {
            val out = File(encryptedThumbsDir, "${chatId}_${messageId}$ENCRYPTED_THUMB_EXT")
            out.writeBytes(encryptedBytes)
            out.absolutePath
        }.getOrNull()
    }

    suspend fun decryptThumbnailToBytes(encryptedThumbPath: String): ByteArray? = withContext(dispatchers.io) {
        val f = File(encryptedThumbPath)
        if (!f.isFile || f.length() <= 0L) return@withContext null
        runCatching {
            val out = ByteArrayOutputStream()
            f.inputStream().buffered().use { input ->
                BackupCrypto.decryptStream(backupCrypto.vaultKey(), input, out)
            }
            out.toByteArray()
        }.getOrNull()
    }

    suspend fun ensureThumbnail(entity: FileCacheEntity): String? {
        if (entity.thumbnailPath != null &&
            !entity.thumbnailPath!!.contains("/minithumbs/") &&
            isValidThumbnailPath(entity.thumbnailPath)
        ) {
            return entity.thumbnailPath
        }

        getCachedThumbnailPath(entity.chatId, entity.messageId)?.let { cached ->
            if (entity.thumbnailPath != cached) {
                runCatching { fileCacheDao.updateThumbnailPath(entity.chatId, entity.messageId, cached) }
            }
            return cached
        }

        val key = entity.chatId to entity.messageId
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
        if (entity.isEncryptedVault || BackupCrypto.isEncryptedFileName(entity.fileName)) return@withContext null
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
        return@withContext when (bucket) {
            FileBucket.IMAGES -> {
                if (entity.fileSize > MAX_REMOTE_IMAGE_BYTES) return@withContext null
                semaphore.withPermit {
                    val terminal = telegramClient.downloadFile(entity.remoteId, "")
                        .filter { it is DownloadUpdate.Done || it is DownloadUpdate.Failed }
                        .first()
                    if (terminal !is DownloadUpdate.Done) return@withPermit null
                    generateFromLocalFile(
                        entity.chatId,
                        entity.messageId,
                        terminal.localPath,
                        entity.mimeType,
                        entity.fileName,
                    )
                }
            }
            FileBucket.VIDEOS -> {
                semaphore.withPermit {
                    val bytes = telegramClient.downloadFileRangeBytes(
                        remoteId = entity.remoteId,
                        offset = 0L,
                        length = VIDEO_PEEK_BYTES,
                    ) ?: return@withPermit null
                    if (bytes.isEmpty()) return@withPermit null

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
                            runCatching { fileCacheDao.updateThumbnailPath(entity.chatId, entity.messageId, saved) }
                        }
                        saved
                    } finally {
                        runCatching { tmpVideo.delete() }
                    }
                }
            }
            else -> null
        }
    }

    private fun writeThumbnailJpeg(
        chatId: Long,
        messageId: Long,
        rawBmp: Bitmap,
    ): String? {
        val outFile = File(thumbsDir, "${chatId}_$messageId.jpg")
        val tmpFile = File(thumbsDir, "${chatId}_$messageId.tmp")
        var scaled: Bitmap = rawBmp
        return try {
            scaled = scaleToMax(rawBmp, TARGET_MAX_PX)
            FileOutputStream(tmpFile).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, fos)
            }
            if (tmpFile.isFile && tmpFile.length() > 0L) {
                if (outFile.exists()) outFile.delete()
                if (!tmpFile.renameTo(outFile)) {
                    tmpFile.copyTo(outFile, overwrite = true)
                    tmpFile.delete()
                }
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
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, TARGET_MAX_PX)
        val bmp = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return applyExifOrientation(bmp, orientation)
    }

    private fun decodeSampledImageBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, TARGET_MAX_PX)
        val bmp = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return applyExifOrientation(bmp, orientation)
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

    private fun decodePdfFirstPage(path: String): Bitmap? {
        val pfd = runCatching {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull() ?: return null
        return pfd.use { fd ->
            runCatching {
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount <= 0) return@runCatching null
                    renderer.openPage(0).use { page ->
                        val targetW = TARGET_MAX_PX
                        val ratio = targetW.toFloat() / page.width.coerceAtLeast(1).toFloat()
                        val targetH = (page.height * ratio).toInt().coerceIn(120, 640)
                        val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }.getOrNull()
        }
    }

    private fun applyExifOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bmp
        }
        return try {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated !== bmp && !bmp.isRecycled) bmp.recycle()
            rotated
        } catch (_: Throwable) {
            bmp
        }
    }

    private fun scaleToMax(src: Bitmap, maxDim: Int): Bitmap {
        if (src.width <= maxDim && src.height <= maxDim) return src
        val ratio = minOf(maxDim.toFloat() / src.width.coerceAtLeast(1), maxDim.toFloat() / src.height.coerceAtLeast(1))
        val targetW = (src.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    private fun computeInSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (width / sample > target || height / sample > target) {
            sample *= 2
        }
        return sample
    }

    companion object {
        const val ENCRYPTED_THUMB_EXT = ".tgethumb"
        private const val TARGET_MAX_PX = 360
        private const val MAX_REMOTE_IMAGE_BYTES = 15L * 1024 * 1024
        private const val VIDEO_PEEK_BYTES = 2L * 1024 * 1024
        private const val FAIL_COOLDOWN_MS = 5L * 60 * 1000L
    }
}
