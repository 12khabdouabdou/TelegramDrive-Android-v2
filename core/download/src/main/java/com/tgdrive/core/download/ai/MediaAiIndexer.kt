package com.tgdrive.core.download.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.location.Geocoder
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.ThermalGuard
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.isTextPreviewable
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.BackupCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device ML Kit text recognition (OCR), image labeling, PDF/text snippet extraction,
 * audio ID3 tagging, and EXIF GPS reverse-geocoding indexer.
 *
 * Also supports strictly local, RAM-only indexing of PassVault `.tgenc` encrypted
 * thumbnails when the user's PassVault passphrase is configured; encrypted/locked
 * index metadata is never uploaded to Telegram cloud sync.
 */
@Singleton
class MediaAiIndexer @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val fileCacheDao: FileCacheDao,
    private val downloadManager: DownloadManager,
    private val offlinePinStore: OfflinePinStore,
    private val mediaThumbnailCache: MediaThumbnailCache,
    private val cloudSyncManager: CloudSyncManager,
    private val backupCrypto: BackupCrypto,
    private val dispatchers: DispatchersProvider,
) {
    private val semaphore = Semaphore(2)
    private val geocodeCache = ConcurrentHashMap<String, List<String>>()

    suspend fun countUnindexed(
        excludedChatIds: Set<Long> = emptySet(),
        includeLocked: Boolean = false,
        vaultOnly: Boolean = false,
    ): Int = withContext(dispatchers.io) {
        findIndexableCandidates(excludedChatIds, includeLocked, vaultOnly).size
    }

    suspend fun indexUnindexedBatch(
        excludedChatIds: Set<Long> = emptySet(),
        maxCount: Int = 30,
        includeLocked: Boolean = false,
        vaultOnly: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(dispatchers.io) {
        if (ThermalGuard.isThermalThrottling(ctx)) {
            Log.i("MediaAiIndexer: skipping batch due to device thermal state")
            return@withContext 0
        }
        val candidates = findIndexableCandidates(excludedChatIds, includeLocked, vaultOnly).take(maxCount)
        if (candidates.isEmpty()) return@withContext 0
        val total = candidates.size
        val doneCounter = AtomicInteger(0)
        val successCounter = AtomicInteger(0)
        val sharedRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val sharedLabeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.65f)
                .build(),
        )
        try {
            for (entity in candidates) {
                if (ThermalGuard.isThermalThrottling(ctx)) {
                    Log.i("MediaAiIndexer: stopping batch early due to thermal state")
                    break
                }
                semaphore.withPermit {
                    val ok = runCatching {
                        indexFile(
                            entity = entity,
                            sharedRecognizer = sharedRecognizer,
                            sharedLabeler = sharedLabeler,
                        )
                    }
                        .onFailure { t ->
                            if (t is CancellationException) throw t
                            Log.w("MediaAiIndexer: failed indexing ${entity.fileName}", t)
                        }.getOrDefault(false)
                    if (ok) successCounter.incrementAndGet()
                    val done = doneCounter.incrementAndGet()
                    onProgress(done, total)
                }
            }
        } finally {
            runCatching { sharedRecognizer.close() }
            runCatching { sharedLabeler.close() }
        }
        successCounter.get()
    }

    suspend fun indexFile(
        entity: FileCacheEntity,
        sharedRecognizer: TextRecognizer? = null,
        sharedLabeler: ImageLabeler? = null,
    ): Boolean = withContext(dispatchers.io) {
        val privateItem = isEncryptedOrLocked(entity)
        val encryptedFile = isEncryptedItem(entity)
        val effName = effectiveFileName(entity.fileName)
        val bucket = effectiveBucket(entity)

        if (encryptedFile && !backupCrypto.hasVaultPassphrase()) return@withContext false

        if (!encryptedFile && bucket == FileBucket.AUDIO) {
            val localFull = resolveLocalFile(entity) ?: return@withContext false
            val audioTags = extractAudioTags(localFull)
            val mergedTags = mergeAiTags(entity.aiTags, audioTags, emptyList())
            val ocrKeep = entity.ocrText ?: ""
            fileCacheDao.updateAiIndex(entity.chatId, entity.messageId, ocrKeep, mergedTags)
            if (!privateItem) {
                cloudSyncManager.noteAiIndexChanged(
                    chatId = entity.chatId,
                    messageId = entity.messageId,
                    ocrText = ocrKeep,
                    aiTags = mergedTags,
                    exifLat = entity.exifLat,
                    exifLng = entity.exifLng,
                )
            }
            return@withContext true
        }

        if (!encryptedFile && isTextPreviewable(entity.mimeType, effName)) {
            val localFull = resolveLocalFile(entity)
            if (localFull != null) {
                val snippet = extractTextSnippet(localFull)
                val mergedTags = mergeAiTags(entity.aiTags, emptyList(), emptyList())
                fileCacheDao.updateAiIndex(entity.chatId, entity.messageId, snippet, mergedTags)
                if (!privateItem) {
                    cloudSyncManager.noteAiIndexChanged(
                        chatId = entity.chatId,
                        messageId = entity.messageId,
                        ocrText = snippet,
                        aiTags = mergedTags,
                        exifLat = entity.exifLat,
                        exifLng = entity.exifLng,
                    )
                }
                return@withContext true
            }
        }

        val supportedVisual = bucket == FileBucket.IMAGES ||
            bucket == FileBucket.PDF ||
            bucket == FileBucket.VIDEOS ||
            (encryptedFile && bucket == FileBucket.ARCHIVES)
        if (!supportedVisual) return@withContext false

        val localFull = if (encryptedFile) null else resolveLocalFile(entity)

        // Multi-page OCR for locally available unencrypted PDFs
        if (!encryptedFile && bucket == FileBucket.PDF && localFull != null) {
            val pdfOcr = runPdfMultiPageOcr(localFull)
            if (pdfOcr != null) {
                val mergedTags = mergeAiTags(entity.aiTags, listOf("document", "pdf"), emptyList())
                fileCacheDao.updateAiIndex(entity.chatId, entity.messageId, pdfOcr, mergedTags)
                if (!privateItem) {
                    cloudSyncManager.noteAiIndexChanged(
                        chatId = entity.chatId,
                        messageId = entity.messageId,
                        ocrText = pdfOcr,
                        aiTags = mergedTags,
                        exifLat = entity.exifLat,
                        exifLng = entity.exifLng,
                    )
                }
                return@withContext true
            }
        }

        val bmp = decodeBitmapForEntity(entity, bucket, encryptedFile, localFull) ?: return@withContext false
        val ownsRecognizer = sharedRecognizer == null
        val recognizer = sharedRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val shouldLabel = bucket == FileBucket.IMAGES || bucket == FileBucket.VIDEOS || (encryptedFile && bucket == FileBucket.ARCHIVES)
        val ownsLabeler = shouldLabel && sharedLabeler == null
        val labeler = if (shouldLabel) {
            sharedLabeler ?: ImageLabeling.getClient(
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
                .replace(WHITESPACE_REGEX, " ")
                .trim()
                .take(MAX_OCR_CHARS)

            val visualTags = if (labeler != null) {
                val labels = runCatching { labeler.process(input).awaitResult() }.getOrDefault(emptyList())
                labels
                    .sortedByDescending { it.confidence }
                    .map { it.text.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(10)
            } else {
                emptyList()
            }

            var lat: Double? = entity.exifLat
            var lng: Double? = entity.exifLng
            if (!encryptedFile && bucket == FileBucket.IMAGES && localFull != null && (lat == null || lng == null)) {
                readLatLong(localFull)?.let { (xLat, xLng) ->
                    lat = xLat
                    lng = xLng
                    runCatching { fileCacheDao.updateExifLocation(entity.chatId, entity.messageId, xLat, xLng) }
                }
            }

            val placeTags = if (lat != null && lng != null) {
                resolvePlaceTags(lat!!, lng!!)
            } else {
                emptyList()
            }

            val mergedTags = mergeAiTags(entity.aiTags, visualTags, placeTags)
            fileCacheDao.updateAiIndex(entity.chatId, entity.messageId, ocrClean, mergedTags)
            if (privateItem) {
                if (backupCrypto.hasVaultPassphrase()) {
                    cloudSyncManager.noteVaultAiIndexChanged(
                        chatId = entity.chatId,
                        messageId = entity.messageId,
                        ocrText = ocrClean,
                        aiTags = mergedTags,
                    )
                }
            } else {
                cloudSyncManager.noteAiIndexChanged(
                    chatId = entity.chatId,
                    messageId = entity.messageId,
                    ocrText = ocrClean,
                    aiTags = mergedTags,
                    exifLat = lat,
                    exifLng = lng,
                )
            }
            true
        } finally {
            if (ownsRecognizer) runCatching { recognizer.close() }
            if (ownsLabeler) runCatching { labeler?.close() }
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    suspend fun scanLocalExifLocations(excludedChatIds: Set<Long> = emptySet()): Int = withContext(dispatchers.io) {
        val unlocked = runCatching { fileCacheDao.getAllUnlocked() }.getOrDefault(emptyList())
        var updated = 0
        for (entity in unlocked) {
            if (entity.chatId in excludedChatIds) continue
            if (isEncryptedOrLocked(entity)) continue
            if (classifyFile(entity.mimeType, entity.fileName) != FileBucket.IMAGES) continue

            var lat = entity.exifLat
            var lng = entity.exifLng
            var foundNewCoords = false
            if (lat == null || lng == null) {
                val localPath = resolveLocalFile(entity) ?: continue
                val coords = readLatLong(localPath) ?: continue
                lat = coords.first
                lng = coords.second
                foundNewCoords = true
            }

            val existingHasLoc = entity.aiTags?.contains("loc:", ignoreCase = true) == true
            val placeTags = if (!existingHasLoc) resolvePlaceTags(lat!!, lng!!) else emptyList()
            val mergedTags = if (placeTags.isNotEmpty()) {
                mergeAiTags(entity.aiTags, emptyList(), placeTags)
            } else {
                entity.aiTags
            }

            if (foundNewCoords || mergedTags != entity.aiTags) {
                runCatching {
                    if (foundNewCoords) {
                        fileCacheDao.updateExifLocation(entity.chatId, entity.messageId, lat, lng)
                    }
                    if (mergedTags != entity.aiTags) {
                        fileCacheDao.updateAiIndex(entity.chatId, entity.messageId, entity.ocrText, mergedTags)
                    }
                    cloudSyncManager.noteAiIndexChanged(
                        chatId = entity.chatId,
                        messageId = entity.messageId,
                        ocrText = entity.ocrText,
                        aiTags = mergedTags,
                        exifLat = lat,
                        exifLng = lng,
                    )
                    if (foundNewCoords) updated++
                }
            }
        }
        updated
    }

    suspend fun recognizeBitmapText(bitmap: Bitmap): String = withContext(dispatchers.io) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val input = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(input).awaitResult().text.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("MediaAiIndexer: recognizeBitmapText failed", t)
            ""
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private suspend fun findIndexableCandidates(
        excludedChatIds: Set<Long>,
        includeLocked: Boolean,
        vaultOnly: Boolean = false,
    ): List<FileCacheEntity> {
        val files = when {
            vaultOnly -> runCatching { fileCacheDao.getEncryptedVaultFiles() }.getOrDefault(emptyList())
            includeLocked -> runCatching { fileCacheDao.getAll() }.getOrDefault(emptyList())
            else -> runCatching { fileCacheDao.getAllUnlocked() }.getOrDefault(emptyList())
        }
        val hasPassphrase = backupCrypto.hasVaultPassphrase()
        return files.filter { entity ->
            if (entity.chatId in excludedChatIds) return@filter false
            if (!includeLocked && entity.isLocked) return@filter false
            if (entity.fileName.endsWith(MediaThumbnailCache.ENCRYPTED_THUMB_EXT, ignoreCase = true)) {
                return@filter false
            }

            // Consider indexed only once OCR/text pass ran (ocrText != null) or legacy non-location tags exist
            val hasSubstantiveAi = entity.ocrText != null || (
                !entity.aiTags.isNullOrBlank() &&
                    entity.aiTags!!.split(',').any { tag ->
                        val t = tag.trim()
                        t.isNotEmpty() && !t.startsWith("loc:", ignoreCase = true)
                    }
                )
            if (hasSubstantiveAi) return@filter false

            val encrypted = isEncryptedItem(entity)
            val effName = effectiveFileName(entity.fileName)
            val bucket = effectiveBucket(entity)
            if (encrypted) {
                if (!hasPassphrase) return@filter false
                if (bucket != FileBucket.IMAGES &&
                    bucket != FileBucket.PDF &&
                    bucket != FileBucket.VIDEOS &&
                    bucket != FileBucket.ARCHIVES
                ) {
                    return@filter false
                }
                return@filter hasEncryptedThumbAvailable(entity)
            }

            when {
                bucket == FileBucket.IMAGES || bucket == FileBucket.VIDEOS ->
                    resolveLocalFile(entity) != null || resolveCrispThumbPath(entity) != null
                bucket == FileBucket.PDF ->
                    resolveLocalFile(entity) != null ||
                        resolveCrispThumbPath(entity) != null ||
                        mediaThumbnailCache.getCachedThumbnailPath(entity.chatId, entity.messageId) != null
                bucket == FileBucket.AUDIO || isTextPreviewable(entity.mimeType, effName) ->
                    resolveLocalFile(entity) != null
                else -> false
            }
        }
    }

    private fun hasEncryptedThumbAvailable(entity: FileCacheEntity): Boolean {
        val p = entity.thumbnailPath
        if (p != null && p.endsWith(MediaThumbnailCache.ENCRYPTED_THUMB_EXT, ignoreCase = true) && File(p).isFile) {
            return true
        }
        if (mediaThumbnailCache.getEncryptedThumbnailPath(entity.chatId, entity.messageId) != null) {
            return true
        }
        return cloudSyncManager.getCloudEncryptedThumbRemoteId(entity.chatId, entity.messageId) != null
    }

    private suspend fun decodeBitmapForEntity(
        entity: FileCacheEntity,
        bucket: FileBucket,
        encryptedFile: Boolean,
        localFull: String?,
    ): Bitmap? {
        if (encryptedFile) {
            val localEncPath = entity.thumbnailPath
                ?.takeIf { it.endsWith(MediaThumbnailCache.ENCRYPTED_THUMB_EXT, ignoreCase = true) && File(it).isFile }
                ?: mediaThumbnailCache.getEncryptedThumbnailPath(entity.chatId, entity.messageId)
                ?: mediaThumbnailCache.restoreEncryptedThumbFromCloud(entity.chatId, entity.messageId)
                ?: return null
            val plainBytes = mediaThumbnailCache.decryptThumbnailToBytes(localEncPath) ?: return null
            if (plainBytes.isEmpty()) return null
            return decodeBoundedBitmapFromBytes(plainBytes, 1024)
        }

        val thumbPath = resolveCrispThumbPath(entity)
        val imageSourcePath = when (bucket) {
            FileBucket.IMAGES -> localFull ?: thumbPath
            FileBucket.VIDEOS -> thumbPath ?: mediaThumbnailCache.getCachedThumbnailPath(entity.chatId, entity.messageId)
            FileBucket.PDF -> thumbPath ?: mediaThumbnailCache.getCachedThumbnailPath(entity.chatId, entity.messageId)
            else -> null
        } ?: return null

        return decodeBoundedBitmap(imageSourcePath, 1024)
    }

    private suspend fun runPdfMultiPageOcr(localPath: String): String? {
        return try {
            val file = File(localPath)
            if (!file.isFile || file.length() < 5L) return null
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val sb = StringBuilder()
            try {
                pfd.use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        val pagesToScan = renderer.pageCount.coerceAtMost(MAX_PDF_OCR_PAGES)
                        for (pageIndex in 0 until pagesToScan) {
                            if (sb.length >= MAX_OCR_CHARS) break
                            val bmp = renderer.openPage(pageIndex).use { page ->
                                val scale = minOf(1024f / page.width.coerceAtLeast(1), 1024f / page.height.coerceAtLeast(1), 2f)
                                val w = (page.width * scale).toInt().coerceIn(64, 1024)
                                val h = (page.height * scale).toInt().coerceIn(64, 1024)
                                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { canvasBmp ->
                                    Canvas(canvasBmp).drawColor(Color.WHITE)
                                    page.render(canvasBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                }
                            }
                            try {
                                val pageText = runCatching {
                                    recognizer.process(InputImage.fromBitmap(bmp, 0)).awaitResult().text
                                }.getOrElse { t ->
                                    if (t is CancellationException) throw t
                                    ""
                                }
                                val cleaned = pageText.replace(WHITESPACE_REGEX, " ").trim()
                                if (cleaned.isNotEmpty()) {
                                    if (sb.isNotEmpty()) sb.append(' ')
                                    sb.append(cleaned)
                                }
                            } finally {
                                if (!bmp.isRecycled) bmp.recycle()
                            }
                        }
                    }
                }
                sb.toString().trim().take(MAX_OCR_CHARS)
            } finally {
                runCatching { recognizer.close() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractTextSnippet(localPath: String): String {
        return runCatching {
            val file = File(localPath)
            if (!file.isFile || file.length() == 0L) return@runCatching ""
            val toRead = file.length().coerceAtMost(MAX_TEXT_READ_BYTES).toInt()
            val buf = ByteArray(toRead)
            val actual = RandomAccessFile(file, "r").use { raf ->
                raf.read(buf, 0, toRead)
            }
            if (actual <= 0) return@runCatching ""
            String(buf, 0, actual, Charsets.UTF_8)
                .replace('\u0000', ' ')
                .replace(WHITESPACE_REGEX, " ")
                .trim()
                .take(MAX_OCR_CHARS)
        }.getOrDefault("")
    }

    private fun extractAudioTags(localPath: String): List<String> = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(localPath)
            listOf(
                MediaMetadataRetriever.METADATA_KEY_TITLE,
                MediaMetadataRetriever.METADATA_KEY_ARTIST,
                MediaMetadataRetriever.METADATA_KEY_ALBUM,
                MediaMetadataRetriever.METADATA_KEY_GENRE,
            ).mapNotNull { key ->
                retriever.extractMetadata(key)
                    ?.replace(',', ' ')
                    ?.replace(WHITESPACE_REGEX, " ")
                    ?.trim()
                    ?.takeIf { it.length >= 2 }
                    ?.let { "audio:${it.lowercase()}" }
            }.distinct()
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun resolvePlaceTags(lat: Double, lng: Double): List<String> {
        val cacheKey = String.format(Locale.US, "%.2f,%.2f", lat, lng)
        geocodeCache[cacheKey]?.let { return it }
        val resolved = runCatching {
            if (!Geocoder.isPresent()) return@runCatching emptyList()
            val geocoder = Geocoder(ctx, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lng, 1).orEmpty()
            val addr = addresses.firstOrNull() ?: return@runCatching emptyList()
            listOfNotNull(
                addr.locality,
                addr.subAdminArea?.takeIf { !it.equals(addr.locality, ignoreCase = true) },
                addr.adminArea,
                addr.countryName,
            )
                .map { it.replace(',', ' ').replace(WHITESPACE_REGEX, " ").trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { "loc:$it" }
        }.getOrDefault(emptyList())
        geocodeCache[cacheKey] = resolved
        return resolved
    }

    private fun isEncryptedItem(entity: FileCacheEntity): Boolean =
        entity.isEncryptedVault || BackupCrypto.isEncryptedFileName(entity.fileName)

    private fun isEncryptedOrLocked(entity: FileCacheEntity): Boolean =
        entity.isLocked || isEncryptedItem(entity)

    private fun effectiveFileName(fileName: String): String =
        if (BackupCrypto.isEncryptedFileName(fileName)) {
            BackupCrypto.stripEncryptedExtension(fileName)
        } else {
            fileName
        }

    private fun effectiveBucket(entity: FileCacheEntity): FileBucket {
        val effName = effectiveFileName(entity.fileName)
        val mime = if (isEncryptedItem(entity)) null else entity.mimeType
        return classifyFile(mime, effName)
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
        if (p != null &&
            !p.contains("/minithumbs/") &&
            !p.endsWith(MediaThumbnailCache.ENCRYPTED_THUMB_EXT, ignoreCase = true) &&
            File(p).let { it.isFile && it.length() > 0L }
        ) {
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

    private fun decodeBoundedBitmapFromBytes(bytes: ByteArray, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
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
        private const val MAX_OCR_CHARS = 1200
        private const val MAX_PDF_OCR_PAGES = 3
        private const val MAX_TEXT_READ_BYTES = 4096L
        private val WHITESPACE_REGEX = Regex("\\s+")

        internal fun mergeAiTags(
            existingTags: String?,
            visualOrAudioTags: List<String>,
            locationTags: List<String>,
        ): String {
            val out = LinkedHashSet<String>()
            existingTags?.split(',')?.forEach { raw ->
                val cleaned = raw.trim()
                if (cleaned.isNotEmpty()) out.add(cleaned)
            }
            visualOrAudioTags.forEach { raw ->
                val cleaned = raw.trim()
                if (cleaned.isNotEmpty()) out.add(cleaned)
            }
            locationTags.forEach { raw ->
                val cleaned = raw.trim()
                if (cleaned.isNotEmpty()) out.add(cleaned)
            }
            return out.joinToString(",")
        }
    }
}
