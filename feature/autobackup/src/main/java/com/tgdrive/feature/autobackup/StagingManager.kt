package com.tgdrive.feature.autobackup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.StatFs
import com.tgdrive.core.common.Log
import com.tgdrive.core.datastore.BackupQuality
import com.tgdrive.core.security.BackupCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralizes lifecycle, disk-space checks, Storage Saver image compression,
 * EXIF capture-date extraction, and optional AES-256-GCM encryption for staged
 * AutoBackup files in app cache.
 */
@Singleton
class StagingManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val backupCrypto: BackupCrypto,
) {

    sealed class StageOutcome {
        data class Ready(
            val path: String,
            val effectiveName: String,
            val effectiveMime: String,
            val effectiveSize: Long,
            val wasCompressed: Boolean = false,
            val wasEncrypted: Boolean = false,
        ) : StageOutcome()

        data class Failed(
            val reason: String,
            val retryable: Boolean = true,
        ) : StageOutcome()
    }

    private val stagingDir: File by lazy {
        File(ctx.cacheDir, "backup_staging").apply { mkdirs() }
    }

    fun hasEnoughDiskSpace(requiredBytes: Long): Boolean {
        if (requiredBytes <= 0L) return true
        return try {
            stagingDir.mkdirs()
            val available = StatFs(stagingDir.path).availableBytes.takeIf { it > 0L }
                ?: stagingDir.usableSpace
            available >= requiredBytes + FREE_SPACE_SAFETY_BUFFER_BYTES
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Copy a SAF document to a unique staging file. Returns the absolute
     * path, or null on failure (e.g. URI no longer accessible).
     */
    fun stage(uriStr: String, displayName: String): String? {
        val uri = try { Uri.parse(uriStr) } catch (_: Throwable) { return null }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        val target = File(stagingDir, "${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}_$safeName")
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: run {
                Log.w("StagingManager: openInputStream returned null for '$displayName'")
                return null
            }
            input.use { src ->
                FileOutputStream(target).use { dst -> src.copyTo(dst) }
            }
            target.absolutePath
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("StagingManager: stage failed for '$displayName'", t)
            runCatching { target.delete() }
            null
        }
    }

    fun stageForUpload(
        uriStr: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
        quality: BackupQuality,
        encrypt: Boolean,
    ): StageOutcome = stageForUpload(
        uriStr = uriStr,
        displayName = displayName,
        mimeType = mimeType,
        expectedSize = expectedSize,
        quality = quality,
        encrypt = encrypt,
        useCustomPassphrase = false,
    )

    fun stageForUpload(
        uriStr: String,
        displayName: String,
        mimeType: String,
        expectedSize: Long,
        quality: BackupQuality,
        encrypt: Boolean,
        useCustomPassphrase: Boolean,
    ): StageOutcome {
        val neededBytes = if (encrypt) expectedSize * 2L else expectedSize
        if (!hasEnoughDiskSpace(neededBytes)) {
            return StageOutcome.Failed("Not enough free cache storage to stage file", retryable = false)
        }

        val stagedPath = stage(uriStr, displayName)
            ?: return StageOutcome.Failed("Could not read file from device storage", retryable = true)

        var currentFile = File(stagedPath)
        var effectiveName = displayName
        var effectiveMime = mimeType.ifBlank { "application/octet-stream" }
        var wasCompressed = false

        if (quality == BackupQuality.STORAGE_SAVER && shouldTryStorageSaver(displayName, mimeType, currentFile.length())) {
            val compressed = runCatching { compressImageFile(currentFile) }.getOrNull()
            if (compressed != null && compressed.length() in 1L until currentFile.length()) {
                currentFile.delete()
                currentFile = compressed
                effectiveName = "${displayName.substringBeforeLast('.')}.jpg"
                effectiveMime = "image/jpeg"
                wasCompressed = true
            } else {
                compressed?.delete()
            }
        }

        if (encrypt) {
            val encFile = File(stagingDir, "${currentFile.name}.tgenc")
            return try {
                backupCrypto.encryptFileWithBackupSettings(currentFile, encFile, useCustomPassphrase)
                currentFile.delete()
                StageOutcome.Ready(
                    path = encFile.absolutePath,
                    effectiveName = "$effectiveName.tgenc",
                    effectiveMime = "application/octet-stream",
                    effectiveSize = encFile.length(),
                    wasCompressed = wasCompressed,
                    wasEncrypted = true,
                )
            } catch (e: CancellationException) {
                runCatching { encFile.delete() }
                throw e
            } catch (t: Throwable) {
                runCatching { currentFile.delete() }
                runCatching { encFile.delete() }
                StageOutcome.Failed("Encryption failed: ${t.message ?: t.javaClass.simpleName}", retryable = false)
            }
        }

        return StageOutcome.Ready(
            path = currentFile.absolutePath,
            effectiveName = effectiveName,
            effectiveMime = effectiveMime,
            effectiveSize = currentFile.length(),
            wasCompressed = wasCompressed,
            wasEncrypted = false,
        )
    }

    fun extractCaptureTimestampMs(
        uriStr: String,
        mimeType: String,
        fallbackLastModified: Long,
    ): Long {
        val isImage = mimeType.startsWith("image/", ignoreCase = true)
        if (isImage) {
            runCatching {
                val uri = Uri.parse(uriStr)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    if (!raw.isNullOrBlank()) {
                        val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw.trim())
                        if (parsed != null && parsed.time > 0L) {
                            return parsed.time
                        }
                    }
                }
            }
        }
        return if (fallbackLastModified > 0L) fallbackLastModified else System.currentTimeMillis()
    }

    /** Delete a staging file. Safe to call with null or non-existent path. */
    fun release(path: String?) {
        if (path == null) return
        runCatching { File(path).delete() }
    }

    /**
     * Delete all staging files older than [maxAgeMs]. Called at the start
     * of every backup run to clean up files left behind by crashed runs.
     */
    fun purgeStale(maxAgeMs: Long = 60L * 60 * 1000) {
        if (!stagingDir.exists()) return
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var purged = 0
        stagingDir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) {
                if (f.delete()) purged++
            }
        }
        if (purged > 0) Log.i("StagingManager: purged $purged stale staging file(s)")
    }

    /** Delete every staging file. Called when the user disables AutoBackup. */
    fun purgeAll() {
        if (!stagingDir.exists()) return
        var purged = 0
        stagingDir.listFiles()?.forEach { f ->
            if (f.isFile && f.delete()) purged++
        }
        if (purged > 0) Log.i("StagingManager: purged all ($purged) staging file(s)")
    }

    private fun shouldTryStorageSaver(name: String, mime: String, sizeBytes: Long): Boolean {
        if (sizeBytes <= STORAGE_SAVER_MIN_BYTES) return false
        val lowerName = name.lowercase(Locale.US)
        val lowerMime = mime.lowercase(Locale.US)
        if (lowerMime.contains("gif") || lowerMime.contains("svg") || lowerName.endsWith(".gif")) return false
        return lowerMime.startsWith("image/") ||
            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".png") || lowerName.endsWith(".webp") ||
            lowerName.endsWith(".heic") || lowerName.endsWith(".heif")
    }

    private fun compressImageFile(source: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sample = 1
        while ((rawW / sample) > STORAGE_SAVER_MAX_DIM * 2 || (rawH / sample) > STORAGE_SAVER_MAX_DIM * 2) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = BitmapFactory.decodeFile(source.absolutePath, decodeOpts) ?: return null
        var rotated: Bitmap? = null
        var scaled: Bitmap? = null
        val outFile = File(stagingDir, "${source.name}_saver.jpg")
        try {
            val orientation = runCatching {
                ExifInterface(source.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            var working = bitmap
            if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                rotated = Bitmap.createBitmap(working, 0, 0, working.width, working.height, matrix, true)
                working = rotated
            }
            val maxSide = maxOf(working.width, working.height)
            if (maxSide > STORAGE_SAVER_MAX_DIM) {
                val ratio = STORAGE_SAVER_MAX_DIM.toFloat() / maxSide.toFloat()
                val newW = (working.width * ratio).toInt().coerceAtLeast(1)
                val newH = (working.height * ratio).toInt().coerceAtLeast(1)
                scaled = Bitmap.createScaledBitmap(working, newW, newH, true)
                working = scaled
            }
            FileOutputStream(outFile).use { fos ->
                working.compress(Bitmap.CompressFormat.JPEG, STORAGE_SAVER_JPEG_QUALITY, fos)
            }
            return outFile
        } finally {
            if (scaled != null && scaled !== rotated && scaled !== bitmap) scaled.recycle()
            if (rotated != null && rotated !== bitmap) rotated.recycle()
            bitmap.recycle()
        }
    }

    private companion object {
        const val FREE_SPACE_SAFETY_BUFFER_BYTES = 50L * 1024 * 1024
        const val STORAGE_SAVER_MIN_BYTES = 350_000L
        const val STORAGE_SAVER_MAX_DIM = 3840
        const val STORAGE_SAVER_JPEG_QUALITY = 85
    }
}
