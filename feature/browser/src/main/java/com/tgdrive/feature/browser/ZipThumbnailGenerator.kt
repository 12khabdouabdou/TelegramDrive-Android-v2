package com.tgdrive.feature.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.collection.LruCache
import com.tgdrive.core.common.BitmapUtils
import com.tgdrive.core.common.Log
import com.tgdrive.core.security.BackupCrypto
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Generates and caches thumbnails for entries and subfolder 2x2 collages inside a ZIP archive
 * using Zip4j (supporting plain, ZIP64, backslash paths, and password-unlocked entries).
 *
 * - **Image entries** -> read entry bytes and decode a downsampled EXIF-oriented bitmap.
 * - **Video entries** -> extract full small videos or the first 4 MB of oversized videos (> 50 MB),
 *   then ask [VideoFrameExtractor] for a frame bitmap.
 * - **PDF entries** -> extract up to 25 MB to a temp file and render page 1 via [PdfPageRenderer].
 * - **Persistent disk cache** -> caches plain previews as `.jpg` and encrypted-ZIP previews as
 *   Vault-Key AES-256 `.tgethumb` (never plaintext on disk) under an LRU-pruned size cap.
 */
class ZipThumbnailGenerator(
    maxCacheEntries: Int = 64,
    private val targetSizePx: Int = 200,
    private val videoFrameExtractor: VideoFrameExtractor = DefaultVideoFrameExtractor(),
    private val cacheDir: File? = null,
    private val diskCacheDir: File? = null,
    private val backupCrypto: BackupCrypto? = null,
    private val maxDiskCacheBytes: Long = DEFAULT_MAX_DISK_CACHE_BYTES,
    private val pdfPageRenderer: PdfPageRenderer = DefaultPdfPageRenderer(),
) {
    private val cache: LruCache<String, Bitmap> = LruCache(maxCacheEntries)

    /**
     * Get a thumbnail bitmap for [entryName] inside [zipPath], or null if
     * the entry is not thumbnailable or cannot be decoded.
     */
    fun getThumbnail(
        zipPath: String,
        entryName: String,
        password: String? = null,
    ): Bitmap? {
        if (!isThumbnailable(entryName)) return null
        val key = "$zipPath|$entryName|${password.orEmpty()}"
        cache.get(key)?.let { return it }

        val encrypted = !password.isNullOrEmpty()
        val diskHash = computeDiskHash("entry", zipPath, entryName)
        readFromDiskCache(diskHash, encrypted)?.let { diskBmp ->
            cache.put(key, diskBmp)
            return diskBmp
        }

        val bitmap =
            when (thumbnailKind(entryName)) {
                ThumbnailKind.IMAGE -> decodeImageEntry(zipPath, entryName, password)
                ThumbnailKind.VIDEO -> decodeVideoEntry(zipPath, entryName, password)
                ThumbnailKind.PDF -> decodePdfEntry(zipPath, entryName, password)
                ThumbnailKind.NONE -> null
            }
        if (bitmap != null) {
            cache.put(key, bitmap)
            writeToDiskCache(diskHash, bitmap, encrypted)
        }
        return bitmap
    }

    /**
     * Builds (and caches) a 2x2 collage thumbnail for a ZIP subfolder using up to 4 thumbnailable
     * descendant entries inside [zipPath].
     */
    fun getFolderCollage(
        zipPath: String,
        folderPrefix: String,
        entryNames: List<String>,
        password: String? = null,
    ): Bitmap? {
        if (entryNames.isEmpty()) return null
        val key = "collage|$zipPath|$folderPrefix|${password.orEmpty()}"
        cache.get(key)?.let { return it }

        val encrypted = !password.isNullOrEmpty()
        val diskHash = computeDiskHash("collage", zipPath, folderPrefix)
        readFromDiskCache(diskHash, encrypted)?.let { diskBmp ->
            cache.put(key, diskBmp)
            return diskBmp
        }

        val candidates = entryNames
            .filter { isThumbnailable(it) }
            .sortedBy { name ->
                when (thumbnailKind(name)) {
                    ThumbnailKind.IMAGE -> 0
                    ThumbnailKind.PDF -> 1
                    ThumbnailKind.VIDEO -> 2
                    ThumbnailKind.NONE -> 3
                }
            }
        val decoded = ArrayList<Bitmap>(4)
        for (name in candidates) {
            val bmp = getThumbnail(zipPath, name, password)
            if (bmp != null && !bmp.isRecycled) {
                decoded.add(bmp)
                if (decoded.size >= 4) break
            }
        }
        if (decoded.isEmpty()) return null
        val collage = BitmapUtils.composeCollageBitmap(decoded, targetSizePx) ?: return null
        cache.put(key, collage)
        writeToDiskCache(diskHash, collage, encrypted)
        return collage
    }

    /** Drop in-memory thumbnails (persistent [diskCacheDir] remains intact). */
    fun clear() {
        cache.evictAll()
    }

    private fun isThumbnailable(entryName: String): Boolean = thumbnailKind(entryName) != ThumbnailKind.NONE

    private fun thumbnailKind(entryName: String): ThumbnailKind {
        val ext = entryName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in IMAGE_EXTS -> ThumbnailKind.IMAGE
            in VIDEO_EXTS -> ThumbnailKind.VIDEO
            in PDF_EXTS -> ThumbnailKind.PDF
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

            val sample = BitmapUtils.computeInSampleSize(bounds.outWidth, bounds.outHeight, targetSizePx)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            BitmapUtils.applyExifOrientationFromBytes(bytes, decoded)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("ZipThumbnailGenerator: failed to decode image '$entryName'", t)
            null
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
                val maxRead = selectVideoReadLimitBytes(header.uncompressedSize)
                zip.getInputStream(header).use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(16 * 1024)
                        var copied = 0L
                        while (copied < maxRead) {
                            val toRead = minOf(buf.size.toLong(), maxRead - copied).toInt()
                            val n = input.read(buf, 0, toRead)
                            if (n <= 0) break
                            copied += n
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

    private fun decodePdfEntry(
        zipPath: String,
        entryName: String,
        password: String?,
    ): Bitmap? {
        var tempFile: File? = null
        return try {
            val zipFile = File(zipPath)
            if (!zipFile.exists()) return null
            cacheDir?.mkdirs()
            val outFile = File.createTempFile("tgdrive_pdf_thumb_", ".pdf", cacheDir)
            tempFile = outFile
            val extractedOk = openZip(zipFile, password).use { zip ->
                val header = findEntryHeader(zip, entryName) ?: return null
                if (header.isDirectory) return null
                if (header.isEncrypted && password.isNullOrEmpty()) return null
                if (header.uncompressedSize > MAX_PDF_THUMB_BYTES) return null
                zip.getInputStream(header).use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(16 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            copied += n
                            if (copied > MAX_PDF_THUMB_BYTES) return null
                            output.write(buf, 0, n)
                        }
                    }
                }
                true
            }
            if (!extractedOk) return null
            val rendered = pdfPageRenderer.renderFirstPage(outFile, targetSizePx) ?: return null
            downsampleIfNeeded(rendered)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("ZipThumbnailGenerator: failed to render pdf '$entryName'", t)
            null
        } finally {
            runCatching { tempFile?.delete() }
        }
    }

    private fun computeDiskHash(prefix: String, zipPath: String, target: String): String {
        val mtime = runCatching { File(zipPath).lastModified() }.getOrDefault(0L)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$prefix|$zipPath|$mtime|$target".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun readFromDiskCache(hash: String, encrypted: Boolean): Bitmap? {
        val dir = diskCacheDir ?: return null
        if (!dir.isDirectory) return null
        return runCatching {
            if (!encrypted) {
                val f = File(dir, "$hash.jpg")
                if (!f.isFile || f.length() <= 0L) return@runCatching null
                BitmapFactory.decodeFile(f.absolutePath)
            } else {
                val crypto = backupCrypto ?: return@runCatching null
                val f = File(dir, "$hash.tgethumb")
                if (!f.isFile || f.length() <= 0L) return@runCatching null
                val bytes = crypto.decryptThumbnailBytes(f.readBytes()) ?: return@runCatching null
                if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }

    private fun writeToDiskCache(hash: String, bitmap: Bitmap, encrypted: Boolean) {
        val dir = diskCacheDir ?: return
        if (encrypted && backupCrypto == null) return
        if (bitmap.isRecycled) return
        runCatching {
            dir.mkdirs()
            val jpegOut = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, jpegOut)
            val plain = jpegOut.toByteArray()
            if (plain.isEmpty()) return@runCatching
            val targetName = if (encrypted) "$hash.tgethumb" else "$hash.jpg"
            val tmp = File(dir, ".tmp_$targetName")
            val dest = File(dir, targetName)
            if (!encrypted) {
                tmp.writeBytes(plain)
            } else {
                val enc = backupCrypto!!.encryptThumbnailBytes(plain) ?: return@runCatching
                tmp.writeBytes(enc)
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            pruneDiskCacheIfNeeded(dir)
        }
    }

    private fun pruneDiskCacheIfNeeded(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxDiskCacheBytes) return
        val targetCap = (maxDiskCacheBytes * 85L) / 100L
        val sorted = files.sortedBy { it.lastModified() }
        for (f in sorted) {
            if (total <= targetCap) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun downsampleIfNeeded(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= targetSizePx && bitmap.height <= targetSizePx) return bitmap
        val sample = BitmapUtils.computeInSampleSize(bitmap.width, bitmap.height, targetSizePx)
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

    internal fun selectVideoReadLimitBytes(uncompressedSize: Long): Long =
        if (uncompressedSize <= 0L || uncompressedSize <= MAX_VIDEO_THUMB_BYTES) {
            MAX_VIDEO_THUMB_BYTES
        } else {
            VIDEO_HEAD_PEEK_BYTES
        }

    private enum class ThumbnailKind { IMAGE, VIDEO, PDF, NONE }

    companion object {
        private const val DEFAULT_MAX_DISK_CACHE_BYTES = 30L * 1024 * 1024
        private const val MAX_IMAGE_THUMB_BYTES = 25L * 1024 * 1024
        private const val MAX_PDF_THUMB_BYTES = 25L * 1024 * 1024
        private const val MAX_VIDEO_THUMB_BYTES = 50L * 1024 * 1024
        internal const val VIDEO_HEAD_PEEK_BYTES = 4L * 1024 * 1024
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
        internal val VIDEO_EXTS = setOf("mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp")
        internal val PDF_EXTS = setOf("pdf")
    }
}

/**
 * Renders page 1 of a PDF file to a bitmap thumbnail.
 */
fun interface PdfPageRenderer {
    fun renderFirstPage(pdfFile: File, targetSizePx: Int): Bitmap?
}

/**
 * Default [PdfPageRenderer] backed by [android.graphics.pdf.PdfRenderer].
 */
class DefaultPdfPageRenderer : PdfPageRenderer {
    override fun renderFirstPage(pdfFile: File, targetSizePx: Int): Bitmap? = synchronized(PDF_RENDER_LOCK) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount <= 0) return@synchronized null
            page = renderer.openPage(0)
            val pw = page.width.coerceAtLeast(1)
            val ph = page.height.coerceAtLeast(1)
            val scale = minOf(targetSizePx.toFloat() / pw.toFloat(), targetSizePx.toFloat() / ph.toFloat())
                .coerceIn(0.05f, 2f)
            val w = (pw * scale).toInt().coerceIn(48, targetSizePx)
            val h = (ph * scale).toInt().coerceIn(48, targetSizePx)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } catch (t: Throwable) {
            Log.w("DefaultPdfPageRenderer: failed rendering '${pdfFile.name}'", t)
            null
        } finally {
            runCatching { page?.close() }
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }

    private companion object {
        private val PDF_RENDER_LOCK = Any()
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
