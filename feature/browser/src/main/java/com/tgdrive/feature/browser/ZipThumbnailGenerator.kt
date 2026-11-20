package com.tgdrive.feature.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import com.tgdrive.core.common.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * Generates + caches thumbnails for entries inside a ZIP archive.
 *
 * - **Image entries** (jpg/png/gif/webp/…) → decode a downsampled bitmap
 *   straight from the zip's input stream (no temp file needed).
 * - **Video entries** (mp4/mkv/mov/…) → extract to a temp file, then ask
 *   the injected [VideoFrameExtractor] for a frame bitmap. The temp file
 *   is deleted after extraction (success or failure).
 * - **Other entries** → returns null (caller falls back to a file-type icon).
 *
 * Results are cached in a process-LruCache keyed by `zipPath + entryName`
 * so scrolling the ZIP browser list doesn't re-decode visible rows.
 * Failed decodes aren't cached — the row will retry on next scroll into
 * view. This is intentional: a transient IO failure shouldn't permanently
 * kill the thumbnail for an otherwise-decodable image.
 *
 * Encrypted zips: video frame extraction needs a real file path (it's
 * backed by [android.media.MediaMetadataRetriever] which only accepts
 * files, not streams). The temp-file extraction here uses `java.util.zip`
 * which can't read encrypted entries — so video thumbnails silently
 * return null for encrypted zips, and the UI falls back to the file-type
 * icon. Image thumbnails use the same stream path and have the same
 * limitation. This matches the existing UI gating
 * (`showThumbnail = !passwordRequired && !entry.isEncrypted`).
 */
class ZipThumbnailGenerator(
    maxCacheEntries: Int = 64,
    private val targetSizePx: Int = 200,
    private val videoFrameExtractor: VideoFrameExtractor = DefaultVideoFrameExtractor(),
) {
    private val cache: LruCache<String, Bitmap> = LruCache(maxCacheEntries)

    /**
     * Get a thumbnail bitmap for [entryName] inside [zipPath], or null if
     * the entry is not an image/video or cannot be decoded.
     *
     * Safe to call from any thread. Returns the same bitmap instance on
     * repeated calls (cache hit). Non-thumbnailable extensions short-circuit
     * without touching the zip file.
     */
    fun getThumbnail(
        zipPath: String,
        entryName: String,
    ): Bitmap? {
        if (!isThumbnailable(entryName)) return null
        val key = "$zipPath|$entryName"
        cache.get(key)?.let { return it }

        val bitmap =
            when (thumbnailKind(entryName)) {
                ThumbnailKind.IMAGE -> decodeImageEntry(zipPath, entryName)
                ThumbnailKind.VIDEO -> decodeVideoEntry(zipPath, entryName)
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

    private fun decodeImageEntry(
        zipPath: String,
        entryName: String,
    ): Bitmap? {
        return try {
            val zipFile = File(zipPath)
            if (!zipFile.exists()) return null
            ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(entryName) ?: return null

                // Pass 1: decode bounds only (no allocation).
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                zip.getInputStream(entry).use { s ->
                    BitmapFactory.decodeStream(s, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                // Pass 2: decode for real, downsampled.
                val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetSizePx)
                val opts =
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                zip.getInputStream(entry).use { s ->
                    BitmapFactory.decodeStream(s, null, opts)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("ZipThumbnailGenerator: failed to decode image '$entryName'", t)
            null
        }
    }

    /**
     * Extract the video entry to a temp file, ask [videoFrameExtractor]
     * for a frame bitmap, then delete the temp file.
     *
     * The temp file lives in `java.io.tmpdir` (per-OS) with a `tgdrive_thumb_`
     * prefix. It's deleted in a `finally` block — if the extractor throws,
     * we still clean up.
     */
    private fun decodeVideoEntry(
        zipPath: String,
        entryName: String,
    ): Bitmap? {
        val tempFile = File.createTempFile("tgdrive_thumb_", ".mp4")
        return try {
            val zip = File(zipPath)
            if (!zip.exists()) return null
            ZipFile(zip).use { z ->
                val entry = z.getEntry(entryName) ?: return null
                if (entry.size > MAX_VIDEO_THUMB_BYTES) {
                    Log.w("ZipThumbnailGenerator: skipping oversized video thumbnail for '$entryName' (${entry.size} bytes)")
                    return null
                }
                z.getInputStream(entry).use { input ->
                    tempFile.outputStream().use { output ->
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
            }
            val raw = videoFrameExtractor.extractFrame(tempFile) ?: return null
            // Downsample if the frame is huge (MediaMetadataRetriever returns
            // full-resolution frames by default).
            downsampleIfNeeded(raw)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("ZipThumbnailGenerator: failed to decode video '$entryName'", t)
            null
        } finally {
            tempFile.delete()
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

    private companion object {
        private const val MAX_VIDEO_THUMB_BYTES = 50L * 1024 * 1024
        // HEIC needs platform support that varies by Android version; skip it.
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heif", "avif")
        private val VIDEO_EXTS = setOf("mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp")
    }
}

/**
 * Extract a single frame bitmap from a video file. Used by
 * [ZipThumbnailGenerator] for video entry thumbnails.
 *
 * Production implementation: [DefaultVideoFrameExtractor] (uses
 * [android.media.MediaMetadataRetriever]). Tests inject a fake.
 */
fun interface VideoFrameExtractor {
    fun extractFrame(videoFile: File): Bitmap?
}

/**
 * Default [VideoFrameExtractor] backed by [android.media.MediaMetadataRetriever].
 *
 * Returns the frame at time 0 (first frame). Returns null if the retriever
 * fails (corrupt video, unsupported codec, etc.). Always releases the
 * retriever to avoid leaking native resources.
 */
class DefaultVideoFrameExtractor : VideoFrameExtractor {
    override fun extractFrame(videoFile: File): Bitmap? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("DefaultVideoFrameExtractor: failed to extract frame from '${videoFile.name}'", t)
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
                // release() can throw on some OEM implementations; swallow
                // to avoid masking the real bitmap result.
            }
        }
    }
}
