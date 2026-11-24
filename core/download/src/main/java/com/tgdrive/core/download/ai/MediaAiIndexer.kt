package com.tgdrive.core.download.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.sync.CloudSyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device ML Kit text recognition (OCR), image labeling, and EXIF GPS extraction
 * indexer. All inference executes locally on-device with zero network calls; compact
 * keyword/coordinate results sync through [CloudSyncManager].
 */
@Singleton
class MediaAiIndexer @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val fileCacheDao: FileCacheDao,
    private val downloadManager: DownloadManager,
    private val offlinePinStore: OfflinePinStore,
    private val mediaThumbnailCache: MediaThumbnailCache,
    private val cloudSyncManager: CloudSyncManager,
    private val dispatchers: DispatchersProvider,
) {
    private val semaphore = Semaphore(2)

    suspend fun countUnindexed(excludedChatIds: Set<Long> = emptySet()): Int = withContext(dispatchers.io) {
        findIndexableCandidates(excludedChatIds).size
    }

    suspend fun indexUnindexedBatch(
        excludedChatIds: Set<Long> = emptySet(),
        maxCount: Int = 30,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(dispatchers.io) {
        val candidates = findIndexableCandidates(excludedChatIds).take(maxCount)
        if (candidates.isEmpty()) return@withContext 0
        val total = candidates.size
        val doneCounter = AtomicInteger(0)
        val successCounter = AtomicInteger(0)
        for (entity in candidates) {
            semaphore.withPermit {
                val ok = runCatching { indexFile(entity) }
                    .onFailure { t ->
                        if (t is CancellationException) throw t
                        Log.w("MediaAiIndexer: failed indexing ${entity.fileName}", t)
                    }.getOrDefault(false)
                if (ok) successCounter.incrementAndGet()
                val done = doneCounter.incrementAndGet()
                onProgress(done, total)
            }
        }
        successCounter.get()
    }

    suspend fun indexFile(entity: FileCacheEntity): Boolean = withContext(dispatchers.io) {
        val bucket = classifyFile(entity.mimeType, entity.fileName)
        if (bucket != FileBucket.IMAGES && bucket != FileBucket.PDF) return@withContext false

        val localFull = resolveLocalFile(entity)
        val thumbPath = resolveCrispThumbPath(entity)
        val imageSourcePath = when {
            bucket == FileBucket.IMAGES -> localFull ?: thumbPath
            bucket == FileBucket.PDF -> thumbPath ?: mediaThumbnailCache.getCachedThumbnailPath(entity.chatId, entity.messageId)
            else -> null
        } ?: return@withContext false

        val bmp = decodeBoundedBitmap(imageSourcePath, 1024) ?: return@withContext false
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val labeler = if (bucket == FileBucket.IMAGES) {
            ImageLabeling.getClient(
                ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(0.65f)
                    .build(),
            )
        } else {
            null
        }

        try {
            val input = InputImage.fromBitmap(bmp, 0)
            val rawText = runCatching { recognizer.process(input).awaitResult().text }.getOrDefault("")
            val ocrClean = rawText
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_OCR_CHARS)

            val tagsClean = if (labeler != null) {
                val labels = runCatching { labeler.process(input).awaitResult() }.getOrDefault(emptyList())
                labels
                    .sortedByDescending { it.confidence }
                    .map { it.text.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(8)
                    .joinToString(",")
            } else {
                ""
            }

            var lat: Double? = entity.exifLat
            var lng: Double? = entity.exifLng
            if (bucket == FileBucket.IMAGES && localFull != null && (lat == null || lng == null)) {
                readLatLong(localFull)?.let { (xLat, xLng) ->
                    lat = xLat
                    lng = xLng
                    runCatching { fileCacheDao.updateExifLocation(entity.chatId, entity.messageId, xLat, xLng) }
                }
            }

            fileCacheDao.updateAiIndex(entity.chatId, entity.messageId, ocrClean, tagsClean)
            cloudSyncManager.noteAiIndexChanged(
                chatId = entity.chatId,
                messageId = entity.messageId,
                ocrText = ocrClean,
                aiTags = tagsClean,
                exifLat = lat,
                exifLng = lng,
            )
            true
        } finally {
            runCatching { recognizer.close() }
            runCatching { labeler?.close() }
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    suspend fun scanLocalExifLocations(excludedChatIds: Set<Long> = emptySet()): Int = withContext(dispatchers.io) {
        val unlocked = runCatching { fileCacheDao.getAllUnlocked() }.getOrDefault(emptyList())
        var updated = 0
        for (entity in unlocked) {
            if (entity.chatId in excludedChatIds) continue
            if (entity.isEncryptedVault || entity.fileName.endsWith(".tgenc", ignoreCase = true)) continue
            if (entity.exifLat != null && entity.exifLng != null) continue
            if (classifyFile(entity.mimeType, entity.fileName) != FileBucket.IMAGES) continue
            val localPath = resolveLocalFile(entity) ?: continue
            val coords = readLatLong(localPath) ?: continue
            runCatching {
                fileCacheDao.updateExifLocation(entity.chatId, entity.messageId, coords.first, coords.second)
                cloudSyncManager.noteAiIndexChanged(
                    chatId = entity.chatId,
                    messageId = entity.messageId,
                    ocrText = entity.ocrText,
                    aiTags = entity.aiTags,
                    exifLat = coords.first,
                    exifLng = coords.second,
                )
                updated++
            }
        }
        updated
    }

    private suspend fun findIndexableCandidates(excludedChatIds: Set<Long>): List<FileCacheEntity> {
        val unlocked = runCatching { fileCacheDao.getAllUnlocked() }.getOrDefault(emptyList())
        return unlocked.filter { entity ->
            if (entity.chatId in excludedChatIds) return@filter false
            if (entity.isEncryptedVault || entity.fileName.endsWith(".tgenc", ignoreCase = true)) return@filter false
            if (entity.ocrText != null || entity.aiTags != null) return@filter false
            val bucket = classifyFile(entity.mimeType, entity.fileName)
            when (bucket) {
                FileBucket.IMAGES -> resolveLocalFile(entity) != null || resolveCrispThumbPath(entity) != null
                FileBucket.PDF -> resolveCrispThumbPath(entity) != null ||
                    mediaThumbnailCache.getCachedThumbnailPath(entity.chatId, entity.messageId) != null
                else -> false
            }
        }
    }

    private fun resolveLocalFile(entity: FileCacheEntity): String? {
        offlinePinStore.getPinnedFilePathSync(entity.chatId, entity.messageId)?.let { pinned ->
            if (File(pinned).isFile) return pinned
        }
        val dl = downloadManager.findItem(entity.chatId, entity.messageId)
            ?.takeIf { it.status == DownloadStatus.DONE }
            ?.localPath
        return dl?.takeIf { File(it).isFile }
    }

    private fun resolveCrispThumbPath(entity: FileCacheEntity): String? {
        val p = entity.thumbnailPath
        if (p != null && !p.contains("/minithumbs/") && File(p).let { it.isFile && it.length() > 0L }) {
            return p
        }
        return mediaThumbnailCache.getCachedThumbnailPath(entity.chatId, entity.messageId)
    }

    private fun readLatLong(path: String): Pair<Double, Double>? = runCatching {
        val exif = ExifInterface(path)
        val out = FloatArray(2)
        if (exif.getLatLong(out)) out[0].toDouble() to out[1].toDouble() else null
    }.getOrNull()

    private fun decodeBoundedBitmap(path: String, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(path, opts)
    }.getOrNull()

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { exc ->
            if (cont.isActive) cont.resumeWithException(exc)
        }
        addOnCanceledListener {
            if (cont.isActive) cont.cancel()
        }
    }

    companion object {
        private const val MAX_OCR_CHARS = 600
    }
}
