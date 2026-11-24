package com.tgdrive.core.download.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.OfflinePinStore
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Group of visually similar / burst photos clustered by perceptual difference hash (dHash).
 */
data class SimilarPhotoGroup(
    val id: String,
    val items: List<FileCacheEntity>,
    val representativeHash: Long,
)

/**
 * Pure 64-bit difference hash (dHash) math and greedy Hamming clustering, kept
 * Android-free for fast deterministic unit testing.
 */
object DHashCalculator {

    const val HASH_WIDTH = 9
    const val HASH_HEIGHT = 8
    const val DEFAULT_MAX_HAMMING_DISTANCE = 8

    /**
     * Computes a 64-bit horizontal gradient difference hash from a 9x8 row-major
     * array of luminance values. Bit `(y * 8 + x)` is set when pixel `(x, y)` is
     * brighter than pixel `(x + 1, y)`.
     */
    fun computeDHashFromLuma(luma9x8: IntArray): Long {
        require(luma9x8.size >= HASH_WIDTH * HASH_HEIGHT) {
            "Expected at least ${HASH_WIDTH * HASH_HEIGHT} luminance samples"
        }
        var hash = 0L
        var bit = 0
        for (y in 0 until HASH_HEIGHT) {
            val rowOffset = y * HASH_WIDTH
            for (x in 0 until (HASH_WIDTH - 1)) {
                if (luma9x8[rowOffset + x] > luma9x8[rowOffset + x + 1]) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /**
     * Groups entries into clusters of size >= 2 whose hashes fall within [maxDistance]
     * bits of each cluster's anchor hash.
     */
    fun <T> clusterByDHash(
        entries: List<Pair<T, Long>>,
        maxDistance: Int = DEFAULT_MAX_HAMMING_DISTANCE,
    ): List<List<T>> {
        if (entries.size < 2) return emptyList()
        val visited = BooleanArray(entries.size)
        val clusters = ArrayList<List<T>>()
        for (i in entries.indices) {
            if (visited[i]) continue
            visited[i] = true
            val (anchorItem, anchorHash) = entries[i]
            val group = ArrayList<T>()
            group.add(anchorItem)
            for (j in (i + 1) until entries.size) {
                if (visited[j]) continue
                if (hammingDistance(anchorHash, entries[j].second) <= maxDistance) {
                    visited[j] = true
                    group.add(entries[j].first)
                }
            }
            if (group.size >= 2) {
                clusters.add(group)
            }
        }
        return clusters
    }
}

/**
 * Finds near-duplicate and burst-shot photos from locally cached downloads or thumbnails
 * using perceptual dHash similarity.
 */
@Singleton
class SimilarPhotoDetector @Inject constructor(
    private val downloadManager: DownloadManager,
    private val offlinePinStore: OfflinePinStore,
    private val mediaThumbnailCache: MediaThumbnailCache,
    private val dispatchers: DispatchersProvider,
) {

    suspend fun findSimilarGroups(
        mediaItems: List<FileCacheEntity>,
        maxDistance: Int = DHashCalculator.DEFAULT_MAX_HAMMING_DISTANCE,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SimilarPhotoGroup> = withContext(dispatchers.io) {
        val candidates = mediaItems.filter { entity ->
            !entity.isLocked &&
                !entity.isEncryptedVault &&
                !entity.fileName.endsWith(".tgenc", ignoreCase = true) &&
                classifyFile(entity.mimeType, entity.fileName) == FileBucket.IMAGES
        }
        if (candidates.size < 2) return@withContext emptyList()

        val hashed = ArrayList<Pair<FileCacheEntity, Long>>(candidates.size)
        val total = candidates.size
        candidates.forEachIndexed { idx, entity ->
            onProgress(idx + 1, total)
            val path = resolveImagePath(entity)
            if (path != null) {
                val hash = computeFileDHash(path)
                if (hash != null) {
                    hashed.add(entity to hash)
                }
            }
        }

        val clusters = DHashCalculator.clusterByDHash(hashed, maxDistance)
        clusters.mapIndexed { idx, groupItems ->
            val sorted = groupItems.sortedByDescending { it.date }
            val anchorHash = hashed.firstOrNull { it.first == sorted.first() }?.second ?: 0L
            SimilarPhotoGroup(
                id = "sim_${sorted.first().chatId}_${sorted.first().messageId}_$idx",
                items = sorted,
                representativeHash = anchorHash,
            )
        }
    }

    private fun resolveImagePath(entity: FileCacheEntity): String? {
        offlinePinStore.getPinnedFilePathSync(entity.chatId, entity.messageId)?.let { p ->
            if (File(p).let { it.isFile && it.length() > 0L }) return p
        }
        downloadManager.findItem(entity.chatId, entity.messageId)
            ?.takeIf { it.status == DownloadStatus.DONE }
            ?.localPath
            ?.let { p -> if (File(p).let { it.isFile && it.length() > 0L }) return p }
        mediaThumbnailCache.getCachedThumbnailPath(entity.chatId, entity.messageId)?.let { p ->
            if (File(p).let { it.isFile && it.length() > 0L }) return p
        }
        return entity.thumbnailPath?.takeIf { p ->
            File(p).let { it.isFile && it.length() > 0L }
        }
    }

    private fun computeFileDHash(path: String): Long? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 128 || bounds.outHeight / sample > 128) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(path, opts) ?: return null
        try {
            val scaled = Bitmap.createScaledBitmap(
                decoded,
                DHashCalculator.HASH_WIDTH,
                DHashCalculator.HASH_HEIGHT,
                true,
            )
            try {
                val pixels = IntArray(DHashCalculator.HASH_WIDTH * DHashCalculator.HASH_HEIGHT)
                scaled.getPixels(
                    pixels,
                    0,
                    DHashCalculator.HASH_WIDTH,
                    0,
                    0,
                    DHashCalculator.HASH_WIDTH,
                    DHashCalculator.HASH_HEIGHT,
                )
                val luma = IntArray(pixels.size) { idx ->
                    val px = pixels[idx]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    (299 * r + 587 * g + 114 * b) / 1000
                }
                DHashCalculator.computeDHashFromLuma(luma)
            } finally {
                if (scaled !== decoded) scaled.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }.getOrNull()
}
