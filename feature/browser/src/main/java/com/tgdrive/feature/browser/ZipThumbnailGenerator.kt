package com.tgdrive.feature.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.collection.LruCache
import com.tgdrive.core.common.Log
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Generates + caches thumbnails for entries inside a ZIP archive using Zip4j
 * (supporting plain, ZIP64, backslash paths, and password-unlocked AES/ZipCrypto entries).
 *
 * - **Image entries** (jpg/png/gif/webp/heic/...) -> read entry bytes once and decode a downsampled
 *   EXIF-oriented bitmap via `BitmapFactory.decodeByteArray`.
 * - **Video entries** (mp4/mkv/mov/...) -> extract to a temp file preserving extension, then ask
 *   the injected [VideoFrameExtractor] for a frame bitmap. The temp file is deleted in `finally`.
 * - **Other entries** -> returns null (caller falls back to a file-type icon).
 */
class ZipThumbnailGenerator(
    maxCacheEntries: Int = 64,
    private val targetSizePx: Int = 200,
    private val videoFrameExtractor: VideoFrameExtractor = DefaultVideoFrameExtractor(),
    private val cacheDir: File? = null,
) {
    private val cache: LruCache<String, Bitmap> = LruCache(maxCacheEntries)

    /**
     * Get a thumbnail bitmap for [entryName] inside [zipPath], or null if
     * the entry is not an image/video or cannot be decoded.
     */
    fun getThumbnail(
        zipPath: String,
        entryName: String,
        password: String? = null,
    ): Bitmap? {
        if (!isThumbnailable(entryName)) return null
        val key = "$zipPath|$entryName|${password.orEmpty()}"
        cache.get(key)?.let { return it }

        val bitmap =
            when (thumbnailKind(entryName)) {
                ThumbnailKind.IMAGE -> decodeImageEntry(zipPath, entryName, password)
                ThumbnailKind.VIDEO -> decodeVideoEntry(zipPath, entryName, password)
                ThumbnailKind.NONE -> null
            }
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    /** Drop all cached thumbnails. Call when leaving the ZIP browser. */
    fun clear() {
        cache.evictAll()
    }

    private fun isThumbnailable(entryName: String): Boolean = thumbnailKind(entryName) != ThumbnailKind.NONE

    private fun thumbnailKind(entryName: String): ThumbnailKind {
        val ext = entryName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in IMAGE_EXTS -> ThumbnailKind.IMAGE
            in VIDEO_EXTS -> ThumbnailKind.VIDEO
            else -> ThumbnailKind.NONE
        }
    }

    private fun openZip(zipFile: File, password: String?): ZipFile =
        if (!password.isNullOrEmpty()) {
            ZipFile(zipFile, password.toCharArray())
        } else {
            ZipFile(zipFile)
        }

    private fun findEntryHeader(zip: ZipFile, entryName: String): FileHeader? {
        zip.getFileHeader(entryName)?.let { return it }
        val normalized = entryName.replace('\\', '/').trimStart('/')
        zip.getFileHeader(normalized)?.let { return it }
        return zip.fileHeaders.firstOrNull {
            it.fileName == entryName || it.fileName.replace('\\', '/').trimStart('/') == normalized
        }
    }

    private fun decodeImageEntry(
        zipPath: String,
        entryName: String,
        password: String?,
    ): Bitmap? {
        return try {
            val zipFile = File(zipPath)
            if (!zipFile.exists()) return null
            val bytes = openZip(zipFile, password).use { zip ->
                val header = findEntryHeader(zip, entryName) ?: return null
                if (header.isDirectory) return null
                if (header.isEncrypted && password.isNullOrEmpty()) return null
                if (header.uncompressedSize > MAX_IMAGE_THUMB_BYTES) return null

                zip.getInputStream(header).use { input ->
                    val baos = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        copied += n
                        if (copied > MAX_IMAGE_THUMB_BYTES) return null
                        baos.write(buf, 0, n)
                    }
                    baos.toByteArray()
                }
            }
            if (bytes.isEmpty()) return null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetSizePx)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            applyExifOrientation(bytes, decoded)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("ZipThumbnailGenerator: failed to decode image '$entryName'", t)
            null
        }
    }

    private fun applyExifOrientation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

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
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            rotated
        } catch (_: Throwable) {
            bitmap
        }
    }

    private fun decodeVideoEntry(
        zipPath: String,
        entryName: String,
        password: String?,
    ): Bitmap? {
        var tempFile: File? = null
        return try {
            val zipFile = File(zipPath)
            if (!zipFile.exists()) return null
            cacheDir?.mkdirs()
            val ext = entryName.substringAfterLast('.', "mp4")
                .lowercase()
                .replace(Regex("[^a-z0-9]"), "")
                .ifBlank { "mp4" }
            val outFile = File.createTempFile("tgdrive_thumb_", ".$ext", cacheDir)
            tempFile = outFile
            val extractedOk = openZip(zipFile, password).use { zip ->
                val header = findEntryHeader(zip, entryName) ?: return null
                if (header.isDirectory) return null
                if (header.isEncrypted && password.isNullOrEmpty()) return null
                if (header.uncompressedSize > MAX_VIDEO_THUMB_BYTES) {
                    Log.w("ZipThumbnailGenerator: skipping oversized video thumbnail for '$entryName' (${header.uncompressedSize} bytes)")
                    return null
                }
                zip.getInputStream(header).use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(16 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            copied += n
                            if (copied > MAX_VIDEO_THUMB_BYTES) {
                                Log.w("ZipThumbnailGenerator: aborted oversized video thumbnail stream for '$entryName'")
                                return null
                            }
                            output.write(buf, 0, n)
                        }
                    }
                }
                true
            }
            if (!extractedOk) return null
            val raw = videoFrameExtractor.extractFrame(outFile) ?: return null
            downsampleIfNeeded(raw)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("ZipThumbnailGenerator: failed to decode video '$entryName'", t)
            null
        } finally {
            runCatching { tempFile?.delete() }
        }
    }

    private fun downsampleIfNeeded(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= targetSizePx && bitmap.height <= targetSizePx) return bitmap
        val sample = computeInSampleSize(bitmap.width, bitmap.height, targetSizePx)
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width / sample).coerceAtLeast(1),
            (bitmap.height / sample).coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return scaled
    }

    private fun computeInSampleSize(
        width: Int,
        height: Int,
        target: Int,
    ): Int {
        var sample = 1
        while (width / sample > target || height / sample > target) {
            sample *= 2
        }
        return sample
    }

    private enum class ThumbnailKind { IMAGE, VIDEO, NONE }

    companion object {
        private const val MAX_IMAGE_THUMB_BYTES = 25L * 1024 * 1024
        private const val MAX_VIDEO_THUMB_BYTES = 50L * 1024 * 1024
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
        internal val VIDEO_EXTS = setOf("mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp")
    }
}

/**
 * Extract a single frame bitmap from a video file. Used by
 * [ZipThumbnailGenerator] for video entry thumbnails.
 */
fun interface VideoFrameExtractor {
    fun extractFrame(videoFile: File): Bitmap?
}

/**
 * Default [VideoFrameExtractor] backed by [android.media.MediaMetadataRetriever].
 */
class DefaultVideoFrameExtractor : VideoFrameExtractor {
    override fun extractFrame(videoFile: File): Bitmap? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            retriever.getFrameAtTime(1_000_000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("DefaultVideoFrameExtractor: failed to extract frame from '${videoFile.name}'", t)
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }
}
