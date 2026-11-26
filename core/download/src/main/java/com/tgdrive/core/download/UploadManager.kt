package com.tgdrive.core.download

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.NetworkMonitor
import com.tgdrive.core.common.PublicDownloadsExporter
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.PendingUploadDao
import com.tgdrive.core.db.dao.ZipEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.PendingUploadEntity
import com.tgdrive.core.db.entity.ZipEntryEntity
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.EncryptedFileFormat
import com.tgdrive.core.security.WrongPassphraseException
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.telegram.TelegramPathTag
import com.tgdrive.core.telegram.UploadResult
import com.tgdrive.core.zip.UploadedZipPartRecord
import com.tgdrive.core.zip.ZipCompressionMode
import com.tgdrive.core.zip.ZipExcludeMatcher
import com.tgdrive.core.zip.ZipUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified, process-scoped upload & decryption manager shared across folders, home quick
 * actions, Android Share intents, and the Transfers tab.
 *
 * Survives ViewModel navigation via app-scoped coroutines and [TransferKeepAliveWorker],
 * handles bounded parallelism, pre-stage duplicate conflict checks, optional AES-256 file &
 * ZIP encryption, oversized file auto-splitting, FLOOD_WAIT/transient retry, and `.tgenc`
 * decryption back to cache or Downloads.
 */
@Singleton
class UploadManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val telegramClient: TelegramClient,
    private val fileCacheDao: FileCacheDao,
    private val zipEntryDao: ZipEntryDao,
    private val pendingUploadDao: PendingUploadDao,
    private val mediaThumbnailCache: MediaThumbnailCache,
    private val uploadTracker: UploadTracker,
    private val backupCrypto: BackupCrypto,
    private val cloudSyncManager: CloudSyncManager,
    private val appPreferences: AppPreferences,
    private val networkMonitor: NetworkMonitor,
    private val dispatchers: DispatchersProvider,
) {
    private sealed interface RetrySpec {
        data class SingleFile(
            val chatId: Long,
            val file: UploadFileItem,
            val options: NormalUploadOptions,
            val virtualPath: String = "",
            val lastModifiedMs: Long = 0L,
        ) : RetrySpec

        data class FolderZip(
            val treeUri: Uri,
            val request: ZipUploadRequest,
        ) : RetrySpec

        data class UrisZip(
            val files: List<UploadFileItem>,
            val request: ZipUploadRequest,
        ) : RetrySpec
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val stagingSemaphore = Semaphore(MAX_STAGE_PARALLEL)
    private val smallFileSemaphore = Semaphore(MAX_SMALL_PARALLEL)
    private val heavyJobSemaphore = Semaphore(1)

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val batchJobs = ConcurrentHashMap<String, Job>()
    private val retrySpecs = ConcurrentHashMap<String, RetrySpec>()
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
    private val waitingForWifiIds = ConcurrentHashMap.newKeySet<String>()

    val uploads: StateFlow<UploadQueueState> = uploadTracker.state

    private val _completedChatEvents = MutableSharedFlow<Long>(extraBufferCapacity = 32)
    val completedChatEvents: SharedFlow<Long> = _completedChatEvents.asSharedFlow()

    init {
        scope.launch { purgeStaleStaging() }
        scope.launch { restorePendingUploads() }
        observeNetworkForWifiQueue()
    }

    suspend fun resolveFileItems(uris: List<Uri>): List<UploadFileItem> = withContext(dispatchers.io) {
        uris.mapIndexed { idx, uri ->
            var name: String? = null
            var size = 0L
            runCatching {
                ctx.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) name = c.getString(nameIdx)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
            }
            if (size <= 0L && uri.scheme == "file") {
                size = uri.path?.let { File(it).length() } ?: 0L
            }
            val fallbackName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "file_${idx + 1}"
            val safeName = sanitizeLeafName(name?.takeIf { it.isNotBlank() } ?: fallbackName)
            val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: guessMimeType(safeName)
            UploadFileItem(
                uri = uri,
                displayName = safeName,
                mimeType = mime,
                sizeBytes = size.coerceAtLeast(0L),
            )
        }
    }

    fun enqueueFiles(
        chatId: Long,
        files: List<UploadFileItem>,
        options: NormalUploadOptions = NormalUploadOptions(),
    ) {
        if (files.isEmpty()) return
        val batchId = "batch_${UUID.randomUUID()}"
        val preparedIds = files.mapIndexed { idx, f ->
            val id = "ul_${System.currentTimeMillis()}_${idx}_${UUID.randomUUID().toString().take(8)}"
            pausedIds.remove(id)
            cancelledIds.remove(id)
            retrySpecs[id] = RetrySpec.SingleFile(chatId, f, options, virtualPath = options.virtualPath)
            val shownName = if (options.encryption.enabled) "${sanitizeLeafName(f.displayName)}.tgenc" else sanitizeLeafName(f.displayName)
            uploadTracker.start(
                id = id,
                fileName = shownName,
                totalBytes = f.sizeBytes,
                chatId = chatId,
                status = UploadStatus.QUEUED,
                isEncrypted = options.encryption.enabled,
            )
            id to f
        }
        val batchJob = scope.launch {
            try {
                val resolvedChatId = resolveChatId(chatId)
                if (resolvedChatId == 0L) {
                    preparedIds.forEach { (id, _) ->
                        if (id !in cancelledIds && id !in pausedIds) {
                            uploadTracker.markFailed(id, "Telegram session not ready", canRetry = false)
                        }
                    }
                    return@launch
                }
                val existingRows = runCatching { fileCacheDao.forChat(resolvedChatId) }.getOrDefault(emptyList())
                val claimedNames = ConcurrentHashMap.newKeySet<String>()
                existingRows.forEach { claimedNames.add(claimedNameKey(it.virtualPath, it.fileName)) }

                val results = supervisorScope {
                    val jobs = preparedIds.mapNotNull { (id, file) ->
                        val spec = RetrySpec.SingleFile(resolvedChatId, file, options, virtualPath = options.virtualPath)
                        retrySpecs[id] = spec
                        val shownName = if (options.encryption.enabled) {
                            "${sanitizeLeafName(file.displayName)}.tgenc"
                        } else {
                            sanitizeLeafName(file.displayName)
                        }
                        if (id !in cancelledIds) {
                            persistPendingSpec(
                                id = id,
                                spec = spec,
                                displayName = shownName,
                                totalBytes = file.sizeBytes,
                                status = if (id in pausedIds) UploadStatus.PAUSED.name else UploadStatus.QUEUED.name,
                            )
                        }
                        if (id in cancelledIds || id in pausedIds) return@mapNotNull null
                        val job = async {
                            executeSingleFileTask(id, spec, claimedNames, existingRows)
                        }
                        activeJobs[id] = job
                        job
                    }
                    jobs.map { job ->
                        try {
                            job.await()
                        } catch (e: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            false
                        }
                    }
                }
                if (results.any { it }) {
                    if (options.virtualPath.isNotBlank()) {
                        cloudSyncManager.schedulePushMetadata()
                    }
                    _completedChatEvents.tryEmit(resolvedChatId)
                }
            } finally {
                batchJobs.remove(batchId)
            }
        }
        batchJobs[batchId] = batchJob
    }

    fun enqueueFolderAsNormalFiles(
        chatId: Long,
        treeUri: Uri,
        options: NormalUploadOptions = NormalUploadOptions(),
    ) {
        val batchId = "dir_batch_${UUID.randomUUID()}"
        val batchJob = scope.launch {
            try {
                val resolvedChatId = resolveChatId(chatId)
                if (resolvedChatId == 0L) return@launch
                val scanned = scanFolderForNormalUpload(treeUri)
                if (scanned.isEmpty()) return@launch

                val existingRows = runCatching { fileCacheDao.forChat(resolvedChatId) }.getOrDefault(emptyList())
                val claimedNames = ConcurrentHashMap.newKeySet<String>()
                existingRows.forEach { claimedNames.add(claimedNameKey(it.virtualPath, it.fileName)) }

                val prepared = scanned.mapIndexed { idx, entry ->
                    val id = "ul_dir_${System.currentTimeMillis()}_${idx}_${UUID.randomUUID().toString().take(8)}"
                    pausedIds.remove(id)
                    cancelledIds.remove(id)
                    val basePrefix = options.virtualPath.trim('/')
                    val combinedVpath = listOf(basePrefix, entry.virtualPath.trim('/'))
                        .filter { it.isNotEmpty() }
                        .joinToString("/")
                    val spec = RetrySpec.SingleFile(
                        chatId = resolvedChatId,
                        file = entry.item,
                        options = options,
                        virtualPath = combinedVpath,
                        lastModifiedMs = entry.lastModifiedMs,
                    )
                    retrySpecs[id] = spec
                    val shownName = if (options.encryption.enabled) {
                        "${sanitizeLeafName(entry.item.displayName)}.tgenc"
                    } else {
                        sanitizeLeafName(entry.item.displayName)
                    }
                    uploadTracker.start(
                        id = id,
                        fileName = shownName,
                        totalBytes = entry.item.sizeBytes,
                        chatId = resolvedChatId,
                        status = UploadStatus.QUEUED,
                        statusDetail = combinedVpath.takeIf { it.isNotBlank() },
                        isEncrypted = options.encryption.enabled,
                    )
                    id to spec
                }

                val results = supervisorScope {
                    val jobs = prepared.mapNotNull { (id, spec) ->
                        val shownName = if (options.encryption.enabled) {
                            "${sanitizeLeafName(spec.file.displayName)}.tgenc"
                        } else {
                            sanitizeLeafName(spec.file.displayName)
                        }
                        if (id !in cancelledIds) {
                            persistPendingSpec(
                                id = id,
                                spec = spec,
                                displayName = shownName,
                                totalBytes = spec.file.sizeBytes,
                                status = if (id in pausedIds) UploadStatus.PAUSED.name else UploadStatus.QUEUED.name,
                            )
                        }
                        if (id in cancelledIds || id in pausedIds) return@mapNotNull null
                        val job = async {
                            executeSingleFileTask(id, spec, claimedNames, existingRows)
                        }
                        activeJobs[id] = job
                        job
                    }
                    jobs.map { job ->
                        try {
                            job.await()
                        } catch (e: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            false
                        }
                    }
                }
                if (results.any { it }) {
                    if (prepared.any { it.second.virtualPath.isNotBlank() }) {
                        cloudSyncManager.schedulePushMetadata()
                    }
                    _completedChatEvents.tryEmit(resolvedChatId)
                }
            } finally {
                batchJobs.remove(batchId)
            }
        }
        batchJobs[batchId] = batchJob
    }

    fun enqueueFolderAsZip(
        treeUri: Uri,
        request: ZipUploadRequest,
    ): String {
        val id = "zip_dir_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val safeBase = ZipUploader.sanitizeZipBaseName(request.baseName).removeSuffix(".zip")
        pausedIds.remove(id)
        cancelledIds.remove(id)
        val spec = RetrySpec.FolderZip(treeUri, request.copy(baseName = safeBase))
        retrySpecs[id] = spec
        uploadTracker.start(
            id = id,
            fileName = "$safeBase.zip",
            totalBytes = 0L,
            chatId = request.chatId,
            status = UploadStatus.PACKING,
            statusDetail = if (request.encryption.enabled) "Preparing encrypted ZIP…" else "Scanning folder…",
            isEncrypted = request.encryption.enabled,
        )
        val job = scope.launch {
            if (id !in cancelledIds) {
                persistPendingSpec(
                    id = id,
                    spec = spec,
                    displayName = "$safeBase.zip",
                    totalBytes = 0L,
                    status = if (id in pausedIds) UploadStatus.PAUSED.name else UploadStatus.PACKING.name,
                )
            }
            executeZipTask(id, spec)
        }
        activeJobs[id] = job
        return id
    }

    fun enqueueUrisAsZip(
        files: List<UploadFileItem>,
        request: ZipUploadRequest,
    ): String {
        val id = "zip_uris_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val safeBase = ZipUploader.sanitizeZipBaseName(request.baseName).removeSuffix(".zip")
        pausedIds.remove(id)
        cancelledIds.remove(id)
        val spec = RetrySpec.UrisZip(files, request.copy(baseName = safeBase))
        retrySpecs[id] = spec
        val totalZipBytes = files.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        uploadTracker.start(
            id = id,
            fileName = "$safeBase.zip",
            totalBytes = totalZipBytes,
            chatId = request.chatId,
            status = UploadStatus.PACKING,
            statusDetail = if (request.encryption.enabled) "Packing & encrypting ${files.size} file(s)…" else "Packing ${files.size} file(s)…",
            isEncrypted = request.encryption.enabled,
        )
        val job = scope.launch {
            if (id !in cancelledIds) {
                persistPendingSpec(
                    id = id,
                    spec = spec,
                    displayName = "$safeBase.zip",
                    totalBytes = totalZipBytes,
                    status = if (id in pausedIds) UploadStatus.PAUSED.name else UploadStatus.PACKING.name,
                )
            }
            executeZipTask(id, spec)
        }
        activeJobs[id] = job
        return id
    }

    fun pause(uploadId: String) {
        cancelledIds.remove(uploadId)
        waitingForWifiIds.remove(uploadId)
        pausedIds.add(uploadId)
        activeJobs.remove(uploadId)?.cancel()
        uploadTracker.markPaused(uploadId)
        scope.launch { runCatching { pendingUploadDao.updateStatus(uploadId, UploadStatus.PAUSED.name) } }
    }

    fun resume(uploadId: String) {
        waitingForWifiIds.remove(uploadId)
        pausedIds.remove(uploadId)
        cancelledIds.remove(uploadId)
        retry(uploadId)
    }

    fun cancel(uploadId: String) {
        waitingForWifiIds.remove(uploadId)
        pausedIds.remove(uploadId)
        cancelledIds.add(uploadId)
        activeJobs.remove(uploadId)?.cancel()
        uploadTracker.markCancelled(uploadId)
        scope.launch { runCatching { pendingUploadDao.deleteById(uploadId) } }
    }

    fun pauseAll() {
        uploads.value.items.filter { it.status.isActive }.forEach { pause(it.id) }
    }

    fun resumeAll() {
        uploads.value.items.filter { it.status == UploadStatus.PAUSED }.forEach { resume(it.id) }
    }

    fun cancelAll() {
        batchJobs.values.toList().forEach { it.cancel() }
        batchJobs.clear()
        uploads.value.items.filter { it.status.isInQueue }.forEach { cancel(it.id) }
    }

    fun retry(uploadId: String) {
        val spec = retrySpecs[uploadId] ?: return
        if (activeJobs[uploadId]?.isActive == true) return
        waitingForWifiIds.remove(uploadId)
        pausedIds.remove(uploadId)
        cancelledIds.remove(uploadId)
        when (spec) {
            is RetrySpec.SingleFile -> {
                val shownName = if (spec.options.encryption.enabled) {
                    "${sanitizeLeafName(spec.file.displayName)}.tgenc"
                } else {
                    sanitizeLeafName(spec.file.displayName)
                }
                uploadTracker.start(
                    id = uploadId,
                    fileName = shownName,
                    totalBytes = spec.file.sizeBytes,
                    chatId = spec.chatId,
                    status = UploadStatus.QUEUED,
                    statusDetail = spec.virtualPath.takeIf { it.isNotBlank() },
                    isEncrypted = spec.options.encryption.enabled,
                )
                val job = scope.launch {
                    runCatching { pendingUploadDao.updateStatus(uploadId, UploadStatus.QUEUED.name) }
                    val targetChat = resolveChatId(spec.chatId)
                    if (targetChat == 0L) {
                        uploadTracker.markFailed(uploadId, "Telegram session not ready", canRetry = true)
                        runCatching { pendingUploadDao.updateStatus(uploadId, UploadStatus.FAILED.name) }
                        return@launch
                    }
                    val resolvedSpec = spec.copy(chatId = targetChat)
                    retrySpecs[uploadId] = resolvedSpec
                    val existingRows = runCatching { fileCacheDao.forChat(targetChat) }.getOrDefault(emptyList())
                    val claimed = ConcurrentHashMap.newKeySet<String>().apply {
                        existingRows.forEach { add(it.fileName.lowercase(Locale.US)) }
                    }
                    if (executeSingleFileTask(uploadId, resolvedSpec, claimed, existingRows)) {
                        if (resolvedSpec.virtualPath.isNotBlank()) {
                            cloudSyncManager.schedulePushMetadata()
                        }
                        _completedChatEvents.tryEmit(targetChat)
                    }
                }
                activeJobs[uploadId] = job
            }
            is RetrySpec.FolderZip -> {
                uploadTracker.start(
                    id = uploadId,
                    fileName = "${spec.request.baseName}.zip",
                    totalBytes = 0L,
                    chatId = spec.request.chatId,
                    status = UploadStatus.PACKING,
                    isEncrypted = spec.request.encryption.enabled,
                )
                val job = scope.launch {
                    runCatching { pendingUploadDao.updateStatus(uploadId, UploadStatus.PACKING.name) }
                    executeZipTask(uploadId, spec)
                }
                activeJobs[uploadId] = job
            }
            is RetrySpec.UrisZip -> {
                uploadTracker.start(
                    id = uploadId,
                    fileName = "${spec.request.baseName}.zip",
                    totalBytes = spec.files.sumOf { it.sizeBytes.coerceAtLeast(0L) },
                    chatId = spec.request.chatId,
                    status = UploadStatus.PACKING,
                    isEncrypted = spec.request.encryption.enabled,
                )
                val job = scope.launch {
                    runCatching { pendingUploadDao.updateStatus(uploadId, UploadStatus.PACKING.name) }
                    executeZipTask(uploadId, spec)
                }
                activeJobs[uploadId] = job
            }
        }
    }

    fun retryAllFailed() {
        val failedIds = uploads.value.items
            .filter { it.status == UploadStatus.FAILED && it.canRetry }
            .map { it.id }
        failedIds.forEach { retry(it) }
    }

    fun detectEncryptedFormat(file: File): EncryptedFileFormat = backupCrypto.detectFormat(file)

    suspend fun decryptLocalFile(
        encryptedFile: File,
        originalDisplayName: String,
        passphrase: String? = null,
        saveToDownloads: Boolean = false,
        chatId: Long = 0L,
        messageId: Long = 0L,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): DecryptOutcome = withContext(dispatchers.io) {
        if (!encryptedFile.isFile) {
            return@withContext DecryptOutcome.Failed("Encrypted file is not cached locally yet")
        }
        val cleanName = sanitizeLeafName(BackupCrypto.stripEncryptedExtension(originalDisplayName))
        val outDir = File(ctx.cacheDir, "decrypted").apply { mkdirs() }
        val tmpOut = File(outDir, ".tmp_${UUID.randomUUID()}_$cleanName")
        val finalOut = File(outDir, cleanName)
        return@withContext try {
            backupCrypto.decryptFile(
                inputFile = encryptedFile,
                outputFile = tmpOut,
                passphrase = passphrase,
                onProgress = onProgress,
            )
            if (finalOut.exists()) finalOut.delete()
            if (!tmpOut.renameTo(finalOut)) {
                tmpOut.copyTo(finalOut, overwrite = true)
                tmpOut.delete()
            }
            val mime = guessMimeType(cleanName)
            if (chatId != 0L && messageId != 0L &&
                mediaThumbnailCache.getEncryptedThumbnailPath(chatId, messageId) == null
            ) {
                runCatching {
                    mediaThumbnailCache.generateAndSyncEncryptedThumb(
                        chatId = chatId,
                        messageId = messageId,
                        localPath = finalOut.absolutePath,
                        mimeType = mime,
                        originalFileName = cleanName,
                    )
                }
            }
            var savedFile = finalOut
            if (saveToDownloads) {
                savedFile = writeDecryptedToDownloads(finalOut, cleanName, mime) ?: finalOut
            }
            DecryptOutcome.Success(
                file = savedFile,
                displayName = cleanName,
                mimeType = mime,
                savedToDownloads = saveToDownloads,
            )
        } catch (e: WrongPassphraseException) {
            runCatching { tmpOut.delete() }
            DecryptOutcome.WrongPassphrase(e.message ?: "Incorrect key or passphrase")
        } catch (e: CancellationException) {
            runCatching { tmpOut.delete() }
            throw e
        } catch (t: Throwable) {
            runCatching { tmpOut.delete() }
            Log.w("UploadManager: decryptLocalFile failed for '$originalDisplayName'", t)
            DecryptOutcome.Failed(t.message ?: "Decryption failed")
        }
    }

    private suspend fun executeSingleFileTask(
        id: String,
        spec: RetrySpec.SingleFile,
        claimedNames: MutableSet<String>,
        existingRowsSnapshot: List<FileCacheEntity>,
    ): Boolean {
        if (id in cancelledIds) {
            uploadTracker.markCancelled(id)
            withContext(NonCancellable) { runCatching { pendingUploadDao.deleteById(id) } }
            return false
        }
        if (id in pausedIds) {
            val isWifiWait = id in waitingForWifiIds
            uploadTracker.markPaused(id, detail = if (isWifiWait) "Waiting for Wi-Fi…" else "Paused")
            withContext(NonCancellable) {
                runCatching {
                    pendingUploadDao.updateStatus(
                        id,
                        if (isWifiWait) STATUS_WAITING_WIFI else UploadStatus.PAUSED.name,
                    )
                }
            }
            return false
        }
        if (shouldDeferForWifi(spec.options.wifiOnly)) {
            markWaitingForWifi(id)
            return false
        }
        val chatId = spec.chatId
        val item = spec.file
        val options = spec.options
        val maxLimit = runCatching { telegramClient.maxUploadSizeBytes() }
            .getOrDefault(TelegramClient.MAX_FILE_SIZE_STANDARD)
        val rawName = sanitizeLeafName(item.displayName)
        val desiredName = if (options.encryption.enabled && !BackupCrypto.isEncryptedFileName(rawName)) {
            "$rawName${BackupCrypto.ENCRYPTED_EXT}"
        } else {
            rawName
        }
        val effectiveMime = if (options.encryption.enabled) "application/octet-stream" else item.mimeType.ifBlank { guessMimeType(rawName) }

        val normVpath = spec.virtualPath.trim('/')
        val existingMatch = existingRowsSnapshot.firstOrNull {
            it.virtualPath.trim('/').equals(normVpath, ignoreCase = true) &&
                it.fileName.equals(desiredName, ignoreCase = true)
        }
        var targetName = desiredName
        when (options.conflictStrategy) {
            UploadConflictStrategy.SKIP -> {
                if (existingMatch != null) {
                    uploadTracker.completeUpload(id, detail = "Already exists — skipped")
                    waitingForWifiIds.remove(id)
                    withContext(NonCancellable) { runCatching { pendingUploadDao.deleteById(id) } }
                    activeJobs.remove(id)
                    return false
                }
                claimedNames.add(claimedNameKey(normVpath, targetName))
            }
            UploadConflictStrategy.KEEP_BOTH -> {
                targetName = claimUniqueName(normVpath, desiredName, claimedNames)
                uploadTracker.updatePhase(id, UploadStatus.QUEUED, fileName = targetName)
            }
            UploadConflictStrategy.REPLACE -> {
                claimedNames.add(claimedNameKey(normVpath, targetName))
            }
        }

        val isHeavy = item.sizeBytes >= LARGE_FILE_THRESHOLD_BYTES ||
            (item.sizeBytes > maxLimit && options.autoSplitOversized)
        val networkSemaphore = if (isHeavy) heavyJobSemaphore else smallFileSemaphore

        data class PreparedStage(
            val fileToUpload: File? = null,
            val finalMime: String = effectiveMime,
            val encryptedThumbBytes: ByteArray? = null,
            val captureDateMs: Long = spec.lastModifiedMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
            val oversizedSplitSource: File? = null,
            val abortResult: Boolean? = null,
        )

        val stagingDir = File(ctx.cacheDir, STAGING_DIR_NAME).apply { mkdirs() }
        var stagedFile: File? = null
        var encryptedFile: File? = null
        try {
            val prepared = stagingSemaphore.withPermit {
                if (id in cancelledIds) {
                    uploadTracker.markCancelled(id)
                    withContext(NonCancellable) { runCatching { pendingUploadDao.deleteById(id) } }
                    return@withPermit PreparedStage(abortResult = false)
                }
                if (id in pausedIds) {
                    val isWifiWait = id in waitingForWifiIds
                    uploadTracker.markPaused(id, detail = if (isWifiWait) "Waiting for Wi-Fi…" else "Paused")
                    withContext(NonCancellable) {
                        runCatching {
                            pendingUploadDao.updateStatus(
                                id,
                                if (isWifiWait) STATUS_WAITING_WIFI else UploadStatus.PAUSED.name,
                            )
                        }
                    }
                    return@withPermit PreparedStage(abortResult = false)
                }
                if (shouldDeferForWifi(options.wifiOnly)) {
                    markWaitingForWifi(id)
                    return@withPermit PreparedStage(abortResult = false)
                }

                currentCoroutineContext().ensureActive()
                if (!options.encryption.enabled && item.uri.scheme == "file") {
                    val direct = item.uri.path?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
                    if (direct != null) {
                        val rawSize = direct.length()
                        if (rawSize > maxLimit) {
                            if (!options.autoSplitOversized) {
                                val limitGb = maxLimit / (1024L * 1024 * 1024)
                                uploadTracker.markFailed(id, "File exceeds $limitGb GB limit", canRetry = false)
                                return@withPermit PreparedStage(abortResult = false)
                            }
                            val capMs = extractCaptureTimestampMs(direct, item.mimeType, spec.lastModifiedMs)
                            return@withPermit PreparedStage(captureDateMs = capMs, oversizedSplitSource = direct)
                        }
                        val capMs = extractCaptureTimestampMs(direct, item.mimeType, spec.lastModifiedMs)
                        return@withPermit PreparedStage(
                            fileToUpload = direct,
                            finalMime = effectiveMime,
                            captureDateMs = capMs,
                        )
                    }
                }

                val canDirectStreamEncrypt = options.encryption.enabled &&
                    item.sizeBytes in 1L..maxLimit &&
                    (
                        !options.encryption.useSavedPassphrase ||
                            !backupCrypto.hasVaultPassphrase() ||
                            classifyFile(item.mimeType, rawName) !in setOf(
                                FileBucket.IMAGES,
                                FileBucket.VIDEOS,
                                FileBucket.PDF,
                                FileBucket.ARCHIVES,
                            )
                    )

                if (canDirectStreamEncrypt) {
                    val estNeeded = item.sizeBytes + (item.sizeBytes / 50L) + 65_536L
                    if (!hasEnoughDiskSpace(stagingDir, estNeeded)) {
                        uploadTracker.markFailed(id, "Not enough free storage space to stage file", canRetry = false)
                        return@withPermit PreparedStage(abortResult = false)
                    }
                    val captureDateMs = spec.lastModifiedMs.takeIf { it > 0L } ?: System.currentTimeMillis()
                    uploadTracker.updatePhase(
                        id = id,
                        status = UploadStatus.ENCRYPTING,
                        detail = if (options.encryption.useSavedPassphrase) "Encrypting (PassVault)…" else "Encrypting (AES-256)…",
                        progress = 0f,
                        fileName = targetName,
                        isEncrypted = true,
                    )
                    val encOut = File(stagingDir, "${UUID.randomUUID().toString().take(8)}_${rawName}${BackupCrypto.ENCRYPTED_EXT}")
                    encryptedFile = encOut
                    val customPass = if (options.encryption.useSavedPassphrase) null else options.encryption.customPassphrase
                    val stream = openUriStream(item.uri)
                        ?: throw IllegalStateException("Cannot open selected file")
                    stream.use { input ->
                        backupCrypto.encryptInputStream(
                            input = input,
                            outputFile = encOut,
                            passphrase = customPass,
                            totalBytes = item.sizeBytes,
                            onProgress = { done, tot ->
                                if (tot > 0L) {
                                    uploadTracker.updatePhase(
                                        id = id,
                                        status = UploadStatus.ENCRYPTING,
                                        progress = (done.toFloat() / tot.toFloat()).coerceIn(0f, 1f),
                                    )
                                }
                            },
                            cancelCheck = { id in cancelledIds || id in pausedIds },
                        )
                    }
                    PreparedStage(
                        fileToUpload = encOut,
                        finalMime = "application/octet-stream",
                        captureDateMs = captureDateMs,
                    )
                } else {
                    val estNeeded = if (options.encryption.enabled) item.sizeBytes * 2L else item.sizeBytes
                    if (!hasEnoughDiskSpace(stagingDir, estNeeded)) {
                        uploadTracker.markFailed(id, "Not enough free storage space to stage file", canRetry = false)
                        return@withPermit PreparedStage(abortResult = false)
                    }

                    uploadTracker.updatePhase(id, UploadStatus.PREPARING, detail = "Copying to staging…", fileName = targetName)
                    val tempRaw = File(stagingDir, "${UUID.randomUUID().toString().take(8)}_$rawName")
                    stagedFile = tempRaw
                    val stream = openUriStream(item.uri)
                        ?: throw IllegalStateException("Cannot open selected file")
                    stream.use { input ->
                        FileOutputStream(tempRaw).use { out ->
                            copyWithCancel(input, out)
                        }
                    }

                    val rawSize = tempRaw.length()
                    val captureDateMs = extractCaptureTimestampMs(tempRaw, item.mimeType, spec.lastModifiedMs)

                    if (rawSize > maxLimit) {
                        if (!options.autoSplitOversized) {
                            val limitGb = maxLimit / (1024L * 1024 * 1024)
                            uploadTracker.markFailed(id, "File exceeds $limitGb GB limit", canRetry = false)
                            return@withPermit PreparedStage(abortResult = false)
                        }
                        return@withPermit PreparedStage(
                            captureDateMs = captureDateMs,
                            oversizedSplitSource = tempRaw,
                        )
                    }

                    val encryptedThumbBytes = if (
                        options.encryption.enabled &&
                        options.encryption.useSavedPassphrase &&
                        backupCrypto.hasVaultPassphrase()
                    ) {
                        runCatching {
                            mediaThumbnailCache.buildEncryptedThumbnailBytes(
                                localPath = tempRaw.absolutePath,
                                mimeType = item.mimeType.ifBlank { guessMimeType(rawName) },
                                originalFileName = rawName,
                            )
                        }.getOrNull()
                    } else {
                        null
                    }

                    var finalFile = tempRaw
                    var finalMime = effectiveMime
                    if (options.encryption.enabled) {
                        uploadTracker.updatePhase(
                            id = id,
                            status = UploadStatus.ENCRYPTING,
                            detail = if (options.encryption.useSavedPassphrase) "Encrypting (PassVault)…" else "Encrypting (AES-256)…",
                            progress = 0f,
                            isEncrypted = true,
                        )
                        val encOut = File(stagingDir, "${tempRaw.name}${BackupCrypto.ENCRYPTED_EXT}")
                        encryptedFile = encOut
                        val customPass = if (options.encryption.useSavedPassphrase) null else options.encryption.customPassphrase
                        backupCrypto.encryptFile(
                            inputFile = tempRaw,
                            outputFile = encOut,
                            passphrase = customPass,
                            onProgress = { done, tot ->
                                if (tot > 0L) {
                                    uploadTracker.updatePhase(
                                        id = id,
                                        status = UploadStatus.ENCRYPTING,
                                        progress = (done.toFloat() / tot.toFloat()).coerceIn(0f, 1f),
                                    )
                                }
                            },
                            cancelCheck = { id in cancelledIds || id in pausedIds },
                        )
                        tempRaw.delete()
                        finalFile = encOut
                        finalMime = "application/octet-stream"
                    }
                    PreparedStage(
                        fileToUpload = finalFile,
                        finalMime = finalMime,
                        encryptedThumbBytes = encryptedThumbBytes,
                        captureDateMs = captureDateMs,
                    )
                }
            }

            prepared.abortResult?.let { return it }

            val splitSrc = prepared.oversizedSplitSource
            if (splitSrc != null) {
                val splitOk = heavyJobSemaphore.withPermit {
                    uploadOversizedSingleFileSplit(
                        id = id,
                        chatId = chatId,
                        sourceFile = splitSrc,
                        displayName = rawName,
                        maxPartBytes = (maxLimit * 92L / 100L),
                        options = options,
                        virtualPath = spec.virtualPath,
                        captureDateMs = prepared.captureDateMs,
                        stagingDir = stagingDir,
                    )
                }
                if (splitOk) {
                    waitingForWifiIds.remove(id)
                    if (spec.virtualPath.isNotBlank()) {
                        cloudSyncManager.schedulePushMetadata()
                    }
                    withContext(NonCancellable) { runCatching { pendingUploadDao.deleteById(id) } }
                }
                return splitOk
            }

            val fileToUpload = prepared.fileToUpload
                ?: throw IllegalStateException("Staged upload file missing")
            val finalMime = prepared.finalMime
            val uploadSize = fileToUpload.length()

            return networkSemaphore.withPermit {
                if (id in cancelledIds) {
                    uploadTracker.markCancelled(id)
                    withContext(NonCancellable) { runCatching { pendingUploadDao.deleteById(id) } }
                    return@withPermit false
                }
                if (id in pausedIds) {
                    val isWifiWait = id in waitingForWifiIds
                    uploadTracker.markPaused(id, detail = if (isWifiWait) "Waiting for Wi-Fi…" else "Paused")
                    withContext(NonCancellable) {
                        runCatching {
                            pendingUploadDao.updateStatus(
                                id,
                                if (isWifiWait) STATUS_WAITING_WIFI else UploadStatus.PAUSED.name,
                            )
                        }
                    }
                    return@withPermit false
                }
                if (shouldDeferForWifi(options.wifiOnly)) {
                    markWaitingForWifi(id)
                    return@withPermit false
                }

                uploadTracker.updatePhase(
                    id = id,
                    status = UploadStatus.UPLOADING,
                    detail = null,
                    progress = 0f,
                    totalBytes = uploadSize,
                    fileName = targetName,
                    isEncrypted = options.encryption.enabled,
                )

                val captionTag = if (!options.encryption.enabled && spec.virtualPath.isNotBlank()) {
                    TelegramPathTag.formatCaption(spec.virtualPath)
                } else {
                    ""
                }
                if (captionTag.isNotEmpty()) {
                    telegramClient.registerUploadMetadata(
                        localPath = fileToUpload.absolutePath,
                        caption = captionTag,
                    )
                }

                val uploadResult = uploadWithRetry(
                    id = id,
                    chatId = chatId,
                    localPath = fileToUpload.absolutePath,
                    fileName = targetName,
                    mimeType = finalMime,
                )

                val thumbPath = if (options.encryption.enabled) {
                    val encBytes = prepared.encryptedThumbBytes
                    if (encBytes != null) {
                        runCatching {
                            mediaThumbnailCache.saveAndSyncEncryptedThumbBytes(
                                chatId = chatId,
                                messageId = uploadResult.messageId,
                                encryptedBytes = encBytes,
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                } else {
                    val promoted = uploadResult.generatedThumbPath?.let { prebuilt ->
                        runCatching {
                            mediaThumbnailCache.promotePrebuiltThumbnail(
                                chatId = chatId,
                                messageId = uploadResult.messageId,
                                sourcePath = prebuilt,
                            )
                        }.getOrNull()
                    }
                    promoted ?: runCatching {
                        mediaThumbnailCache.generateFromLocalFile(
                            chatId = chatId,
                            messageId = uploadResult.messageId,
                            localPath = fileToUpload.absolutePath,
                            mimeType = finalMime,
                            fileName = targetName,
                        )
                    }.getOrNull()
                }

                fileCacheDao.upsertAll(
                    listOf(
                        FileCacheEntity(
                            messageId = uploadResult.messageId,
                            chatId = chatId,
                            fileName = targetName,
                            fileSize = uploadSize,
                            mimeType = finalMime,
                            thumbnailPath = thumbPath,
                            remoteId = uploadResult.remoteId,
                            date = prepared.captureDateMs,
                            lastAccessed = System.currentTimeMillis(),
                            virtualPath = spec.virtualPath,
                            isEncryptedVault = options.encryption.enabled,
                        ),
                    ),
                )

                if (spec.virtualPath.isNotBlank()) {
                    cloudSyncManager.noteVirtualPathChanged(
                        chatId = chatId,
                        messageId = uploadResult.messageId,
                        virtualPath = spec.virtualPath,
                        schedulePush = false,
                    )
                }

                if (options.conflictStrategy == UploadConflictStrategy.REPLACE && existingMatch != null) {
                    runCatching { telegramClient.deleteMessage(chatId, existingMatch.messageId) }
                    runCatching { fileCacheDao.deleteById(chatId, existingMatch.messageId) }
                }

                waitingForWifiIds.remove(id)
                uploadTracker.completeUpload(id)
                withContext(NonCancellable) { runCatching { pendingUploadDao.deleteById(id) } }
                true
            }
        } catch (e: CancellationException) {
            val isWifiWait = id in waitingForWifiIds
            if (id in pausedIds) {
                uploadTracker.markPaused(id, detail = if (isWifiWait) "Waiting for Wi-Fi…" else "Paused")
                withContext(NonCancellable) {
                    runCatching {
                        pendingUploadDao.updateStatus(
                            id,
                            if (isWifiWait) STATUS_WAITING_WIFI else UploadStatus.PAUSED.name,
                        )
                    }
                }
            } else {
                waitingForWifiIds.remove(id)
                uploadTracker.markCancelled(id)
                withContext(NonCancellable) { runCatching { pendingUploadDao.deleteById(id) } }
            }
            throw e
        } catch (t: Throwable) {
            Log.w("UploadManager: single file upload failed for '$targetName'", t)
            waitingForWifiIds.remove(id)
            uploadTracker.markFailed(id, t.message ?: "Upload failed", canRetry = true)
            withContext(NonCancellable) {
                runCatching { pendingUploadDao.updateStatus(id, UploadStatus.FAILED.name) }
            }
            return false
        } finally {
            activeJobs.remove(id)
            runCatching { stagedFile?.delete() }
            runCatching { encryptedFile?.delete() }
        }
    }

    private suspend fun uploadOversizedSingleFileSplit(
        id: String,
        chatId: Long,
        sourceFile: File,
        displayName: String,
        maxPartBytes: Long,
        options: NormalUploadOptions,
        virtualPath: String,
        captureDateMs: Long,
        stagingDir: File,
    ): Boolean {
        val password = resolveEncryptionPassphrase(options.encryption)
        val isEncrypted = password != null
        uploadTracker.updatePhase(
            id = id,
            status = UploadStatus.PACKING,
            detail = "Splitting oversized file into volumes…",
            isEncrypted = isEncrypted,
        )
        val splitWorkDir = File(stagingDir, "split_${id.take(16)}").apply { mkdirs() }
        return try {
            val uploadedParts = mutableListOf<UploadedZipPartRecord>()
            ZipUploader.zipSingleFileSplit(
                sourceFile = sourceFile,
                displayName = displayName,
                workDir = splitWorkDir,
                maxPartBytes = maxPartBytes,
                password = password,
            ) { part ->
                val partName = part.partFile.name
                val partSize = part.partFile.length()
                uploadTracker.updatePhase(
                    id = id,
                    status = UploadStatus.UPLOADING,
                    detail = "Volume ${part.partIndex}",
                    progress = 0f,
                    totalBytes = partSize,
                    fileName = partName,
                    isEncrypted = isEncrypted,
                )
                val res = uploadWithRetry(
                    id = id,
                    chatId = chatId,
                    localPath = part.partFile.absolutePath,
                    fileName = partName,
                    mimeType = "application/zip",
                )
                fileCacheDao.upsertAll(
                    listOf(
                        FileCacheEntity(
                            messageId = res.messageId,
                            chatId = chatId,
                            fileName = partName,
                            fileSize = partSize,
                            mimeType = "application/zip",
                            thumbnailPath = null,
                            remoteId = res.remoteId,
                            date = captureDateMs,
                            lastAccessed = System.currentTimeMillis(),
                            virtualPath = virtualPath,
                            isEncryptedVault = isEncrypted,
                        ),
                    ),
                )
                if (part.entries.isNotEmpty()) {
                    indexZipEntries(chatId, res.messageId, partName, part.entries, isEncrypted)
                }
                uploadedParts += UploadedZipPartRecord(
                    fileName = partName,
                    fileSizeBytes = partSize,
                    partIndex = part.partIndex,
                    isSplitVolume = part.isSplitVolume,
                    messageId = res.messageId,
                    entries = part.entries,
                )
            }
            uploadTracker.completeUpload(id, detail = "Split into ${uploadedParts.size} volumes")
            true
        } finally {
            runCatching { splitWorkDir.deleteRecursively() }
        }
    }

    private suspend fun executeZipTask(
        id: String,
        spec: RetrySpec,
    ) {
        val request = when (spec) {
            is RetrySpec.FolderZip -> spec.request
            is RetrySpec.UrisZip -> spec.request
            else -> return
        }
        if (id in cancelledIds) {
            uploadTracker.markCancelled(id)
            withContext(NonCancellable) { runCatching { pendingUploadDao.deleteById(id) } }
            return
        }
        if (id in pausedIds) {
            val isWifiWait = id in waitingForWifiIds
            uploadTracker.markPaused(id, detail = if (isWifiWait) "Waiting for Wi-Fi…" else "Paused")
            withContext(NonCancellable) {
                runCatching {
                    pendingUploadDao.updateStatus(
                        id,
                        if (isWifiWait) STATUS_WAITING_WIFI else UploadStatus.PAUSED.name,
                    )
                }
            }
            return
        }
        if (shouldDeferForWifi(request.wifiOnly)) {
            markWaitingForWifi(id)
            return
        }
        heavyJobSemaphore.withPermit {
            if (id in cancelledIds) {
                uploadTracker.markCancelled(id)
                withContext(NonCancellable) { runCatching { pendingUploadDao.deleteById(id) } }
                return@withPermit
            }
            if (id in pausedIds) {
                val isWifiWait = id in waitingForWifiIds
                uploadTracker.markPaused(id, detail = if (isWifiWait) "Waiting for Wi-Fi…" else "Paused")
                withContext(NonCancellable) {
                    runCatching {
                        pendingUploadDao.updateStatus(
                            id,
                            if (isWifiWait) STATUS_WAITING_WIFI else UploadStatus.PAUSED.name,
                        )
                    }
                }
                return@withPermit
            }
            if (shouldDeferForWifi(request.wifiOnly)) {
                markWaitingForWifi(id)
                return@withPermit
            }
            val stagingDir = File(ctx.cacheDir, STAGING_DIR_NAME).apply { mkdirs() }
            val zipWorkDir = File(stagingDir, "zip_${id.take(18)}").apply { mkdirs() }
            var manifestFile: File? = null
            try {
                val chatId = resolveChatId(request.chatId)
                if (chatId == 0L) {
                    uploadTracker.markFailed(id, "Telegram session not ready", canRetry = true)
                    return@withPermit
                }

                val accountChunkCap = runCatching { telegramClient.safeZipChunkLimitBytes() }
                    .getOrDefault(TelegramClient.SAFE_CHUNK_LIMIT_STANDARD)
                val maxPartBytes = request.maxPartBytesOverride
                    ?.takeIf { it > 0L }
                    ?.coerceAtMost(accountChunkCap)
                    ?: accountChunkCap

                val password = resolveEncryptionPassphrase(request.encryption)
                val isEncrypted = password != null
                val excludeList = ZipExcludeMatcher.parse(request.excludePatterns)
                val uploadedParts = mutableListOf<UploadedZipPartRecord>()

                val summary = when (spec) {
                    is RetrySpec.FolderZip -> {
                        ZipUploader.zipFolderInParts(
                            context = ctx,
                            treeUri = spec.treeUri,
                            workDir = zipWorkDir,
                            baseZipName = request.baseName,
                            maxPartBytes = maxPartBytes,
                            password = password,
                            compressionMode = request.compressionMode,
                            excludePathMatcher = if (excludeList.isEmpty()) {
                                null
                            } else {
                                { relPath, name -> ZipExcludeMatcher.matchesPath(relPath, name, excludeList) }
                            },
                            onZipProgress = { p ->
                                val pct = if (p.totalBytes > 0L) {
                                    (p.bytesPacked.toFloat() / p.totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                uploadTracker.updatePhase(
                                    id = id,
                                    status = UploadStatus.PACKING,
                                    detail = "Packing ${p.filesPacked}/${p.totalFiles} · ${p.currentFileName}",
                                    progress = pct,
                                )
                            },
                        ) { part ->
                            handleReadyZipPart(
                                id = id,
                                chatId = chatId,
                                part = part,
                                isEncrypted = isEncrypted,
                                useSavedPassphrase = request.encryption.useSavedPassphrase,
                                zipPassword = password,
                                virtualPath = request.virtualPath,
                                uploadedParts = uploadedParts,
                            )
                        }
                    }
                    is RetrySpec.UrisZip -> {
                        val customNames = spec.files.associate { it.uri to it.displayName }
                        ZipUploader.zipUrisInParts(
                            context = ctx,
                            uris = spec.files.map { it.uri },
                            workDir = zipWorkDir,
                            baseZipName = request.baseName,
                            customNames = customNames,
                            maxPartBytes = maxPartBytes,
                            password = password,
                            compressionMode = request.compressionMode,
                            onZipProgress = { p ->
                                val pct = if (p.totalBytes > 0L) {
                                    (p.bytesPacked.toFloat() / p.totalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                uploadTracker.updatePhase(
                                    id = id,
                                    status = UploadStatus.PACKING,
                                    detail = "Packing ${p.filesPacked}/${p.totalFiles} · ${p.currentFileName}",
                                    progress = pct,
                                )
                            },
                        ) { part ->
                            handleReadyZipPart(
                                id = id,
                                chatId = chatId,
                                part = part,
                                isEncrypted = isEncrypted,
                                useSavedPassphrase = request.encryption.useSavedPassphrase,
                                zipPassword = password,
                                virtualPath = request.virtualPath,
                                uploadedParts = uploadedParts,
                            )
                        }
                    }
                    else -> error("Invalid ZIP retry spec")
                }

                if (summary.totalFilesProcessed == 0 || uploadedParts.isEmpty()) {
                    uploadTracker.markFailed(id, "No eligible files found to archive", canRetry = false)
                    return@withPermit
                }

                if (uploadedParts.size > 1) {
                    val mFile = ZipUploader.writeManifestJsonFile(
                        outputDir = zipWorkDir,
                        baseName = request.baseName,
                        folderName = request.baseName,
                        encrypted = isEncrypted,
                        parts = uploadedParts,
                    )
                    manifestFile = mFile
                    val mLen = mFile.length()
                    uploadTracker.updatePhase(
                        id = id,
                        status = UploadStatus.UPLOADING,
                        detail = "Uploading manifest (${uploadedParts.size} parts)…",
                        totalBytes = mLen,
                        fileName = mFile.name,
                    )
                    val mRes = uploadWithRetry(
                        id = id,
                        chatId = chatId,
                        localPath = mFile.absolutePath,
                        fileName = mFile.name,
                        mimeType = "application/json",
                    )
                    fileCacheDao.upsertAll(
                        listOf(
                            FileCacheEntity(
                                messageId = mRes.messageId,
                                chatId = chatId,
                                fileName = mFile.name,
                                fileSize = mLen,
                                mimeType = "application/json",
                                thumbnailPath = null,
                                remoteId = mRes.remoteId,
                                date = System.currentTimeMillis(),
                                lastAccessed = System.currentTimeMillis(),
                                virtualPath = request.virtualPath,
                            ),
                        ),
                    )
                }

                val summaryLabel = if (uploadedParts.size > 1) {
                    "${request.baseName} (${uploadedParts.size} parts)"
                } else {
                    uploadedParts.first().fileName
                }
                waitingForWifiIds.remove(id)
                uploadTracker.updatePhase(id, UploadStatus.DONE, fileName = summaryLabel)
                uploadTracker.completeUpload(
                    id = id,
                    detail = "${summary.totalFilesProcessed} files archived",
                )
                withContext(NonCancellable) { runCatching { pendingUploadDao.deleteById(id) } }
                _completedChatEvents.tryEmit(chatId)
            } catch (e: CancellationException) {
                val isWifiWait = id in waitingForWifiIds
                if (id in pausedIds) {
                    uploadTracker.markPaused(id, detail = if (isWifiWait) "Waiting for Wi-Fi…" else "Paused")
                    withContext(NonCancellable) {
                        runCatching {
                            pendingUploadDao.updateStatus(
                                id,
                                if (isWifiWait) STATUS_WAITING_WIFI else UploadStatus.PAUSED.name,
                            )
                        }
                    }
                } else {
                    waitingForWifiIds.remove(id)
                    uploadTracker.markCancelled(id)
                    withContext(NonCancellable) { runCatching { pendingUploadDao.deleteById(id) } }
                }
                throw e
            } catch (t: Throwable) {
                Log.w("UploadManager: ZIP upload failed", t)
                waitingForWifiIds.remove(id)
                uploadTracker.markFailed(id, t.message ?: "ZIP upload failed", canRetry = true)
                withContext(NonCancellable) {
                    runCatching { pendingUploadDao.updateStatus(id, UploadStatus.FAILED.name) }
                }
            } finally {
                activeJobs.remove(id)
                runCatching { manifestFile?.delete() }
                runCatching { zipWorkDir.deleteRecursively() }
            }
        }
    }

    private suspend fun handleReadyZipPart(
        id: String,
        chatId: Long,
        part: com.tgdrive.core.zip.ZipPartOutput,
        isEncrypted: Boolean,
        useSavedPassphrase: Boolean = true,
        zipPassword: String?,
        virtualPath: String = "",
        uploadedParts: MutableList<UploadedZipPartRecord>,
    ) {
        val partName = part.partFile.name
        val partLen = part.partFile.length()
        uploadTracker.updatePhase(
            id = id,
            status = UploadStatus.UPLOADING,
            detail = "Uploading part ${part.partIndex}",
            progress = 0f,
            totalBytes = partLen,
            fileName = partName,
            isEncrypted = isEncrypted,
        )
        val captionTag = if (!isEncrypted && virtualPath.isNotBlank()) TelegramPathTag.formatCaption(virtualPath) else ""
        if (captionTag.isNotEmpty()) {
            telegramClient.registerUploadMetadata(part.partFile.absolutePath, caption = captionTag)
        }
        val result = uploadWithRetry(
            id = id,
            chatId = chatId,
            localPath = part.partFile.absolutePath,
            fileName = partName,
            mimeType = "application/zip",
        )
        val partThumb = if (isEncrypted && useSavedPassphrase && backupCrypto.hasVaultPassphrase()) {
            runCatching {
                mediaThumbnailCache.generateAndSyncEncryptedThumb(
                    chatId = chatId,
                    messageId = result.messageId,
                    localPath = part.partFile.absolutePath,
                    mimeType = "application/zip",
                    originalFileName = partName,
                    zipPassword = zipPassword,
                )
            }.getOrNull()
        } else {
            val promoted = result.generatedThumbPath?.let { prebuilt ->
                runCatching {
                    mediaThumbnailCache.promotePrebuiltThumbnail(chatId, result.messageId, prebuilt)
                }.getOrNull()
            }
            promoted ?: runCatching {
                mediaThumbnailCache.generateFromLocalFile(
                    chatId = chatId,
                    messageId = result.messageId,
                    localPath = part.partFile.absolutePath,
                    mimeType = "application/zip",
                    fileName = partName,
                )
            }.getOrNull()
        }
        fileCacheDao.upsertAll(
            listOf(
                FileCacheEntity(
                    messageId = result.messageId,
                    chatId = chatId,
                    fileName = partName,
                    fileSize = partLen,
                    mimeType = "application/zip",
                    thumbnailPath = partThumb,
                    remoteId = result.remoteId,
                    date = System.currentTimeMillis(),
                    lastAccessed = System.currentTimeMillis(),
                    virtualPath = virtualPath,
                    isEncryptedVault = isEncrypted,
                ),
            ),
        )
        if (virtualPath.isNotBlank()) {
            cloudSyncManager.noteVirtualPathChanged(chatId, result.messageId, virtualPath, schedulePush = true)
        }
        if (part.entries.isNotEmpty()) {
            indexZipEntries(chatId, result.messageId, partName, part.entries, isEncrypted)
        }
        uploadedParts += UploadedZipPartRecord(
            fileName = partName,
            fileSizeBytes = partLen,
            partIndex = part.partIndex,
            isSplitVolume = part.isSplitVolume,
            messageId = result.messageId,
            entries = part.entries,
        )
    }

    private suspend fun indexZipEntries(
        chatId: Long,
        messageId: Long,
        zipFileName: String,
        entryPaths: List<String>,
        encrypted: Boolean,
    ) {
        runCatching {
            val now = System.currentTimeMillis()
            val rows = entryPaths.mapNotNull { raw ->
                val clean = raw.trimEnd('/')
                if (clean.isEmpty()) return@mapNotNull null
                ZipEntryEntity(
                    chatId = chatId,
                    zipMessageId = messageId,
                    zipFileName = zipFileName,
                    entryPath = raw,
                    entryName = clean.substringAfterLast('/'),
                    entrySize = 0L,
                    isEncrypted = encrypted,
                    indexedAt = now,
                )
            }
            if (rows.isNotEmpty()) {
                zipEntryDao.deleteForZip(chatId, messageId)
                rows.chunked(250).forEach { zipEntryDao.upsertAll(it) }
            }
        }
    }

    private suspend fun uploadWithRetry(
        id: String,
        chatId: Long,
        localPath: String,
        fileName: String,
        mimeType: String,
    ): UploadResult {
        var lastErr: Throwable? = null
        for (attempt in 1..MAX_UPLOAD_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            try {
                return telegramClient.uploadFile(chatId, localPath, fileName, mimeType) { sent, total ->
                    uploadTracker.updateProgress(id, sent, total)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastErr = t
                if (attempt >= MAX_UPLOAD_ATTEMPTS) break
                val floodSec = parseFloodWaitSeconds(t.message)
                if (floodSec != null) {
                    val waitMs = ((floodSec + 1L) * 1000L).coerceAtMost(120_000L)
                    uploadTracker.updatePhase(
                        id = id,
                        status = UploadStatus.QUEUED,
                        detail = "Rate limited — waiting ${floodSec + 1}s",
                    )
                    delay(waitMs)
                } else if (isTransientError(t)) {
                    val backoffMs = attempt * 1_500L
                    uploadTracker.updatePhase(
                        id = id,
                        status = UploadStatus.QUEUED,
                        detail = "Retrying ($attempt/$MAX_UPLOAD_ATTEMPTS)…",
                    )
                    delay(backoffMs)
                } else {
                    break
                }
                uploadTracker.updatePhase(id = id, status = UploadStatus.UPLOADING, detail = null)
            }
        }
        throw lastErr ?: IllegalStateException("Upload failed for $fileName")
    }

    private fun resolveEncryptionPassphrase(spec: UploadEncryptionSpec): String? {
        if (!spec.enabled) return null
        return if (spec.useSavedPassphrase) {
            backupCrypto.requireVaultPassphrase()
        } else {
            spec.customPassphrase?.takeIf { it.isNotBlank() } ?: backupCrypto.requireVaultPassphrase()
        }
    }

    private suspend fun resolveChatId(chatId: Long): Long =
        if (chatId != 0L) chatId else runCatching { telegramClient.myUserId() }.getOrDefault(0L)

    private fun openUriStream(uri: Uri): InputStream? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val f = File(path)
            return if (f.isFile) FileInputStream(f) else null
        }
        return ctx.contentResolver.openInputStream(uri)
    }

    private suspend fun copyWithCancel(input: InputStream, output: FileOutputStream) {
        val buf = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) output.write(buf, 0, n)
        }
        output.flush()
    }

    private fun hasEnoughDiskSpace(dir: File, requiredBytes: Long): Boolean {
        if (requiredBytes <= 0L) return true
        val available = try {
            StatFs(dir.absolutePath).availableBytes.takeIf { it > 0L } ?: dir.usableSpace
        } catch (_: Throwable) {
            dir.usableSpace
        }
        return available >= requiredBytes + FREE_SPACE_SAFETY_BYTES
    }

    private fun claimedNameKey(virtualPath: String, fileName: String): String =
        "${virtualPath.trim('/').lowercase(Locale.US)}\u0000${fileName.lowercase(Locale.US)}"

    private fun claimUniqueName(
        virtualPath: String,
        desiredName: String,
        claimedNames: MutableSet<String>,
    ): String {
        if (claimedNames.add(claimedNameKey(virtualPath, desiredName))) return desiredName
        val isEnc = BackupCrypto.isEncryptedFileName(desiredName)
        val inner = if (isEnc) BackupCrypto.stripEncryptedExtension(desiredName) else desiredName
        val dot = inner.lastIndexOf('.')
        val stem = if (dot > 0) inner.substring(0, dot) else inner
        val ext = if (dot > 0) inner.substring(dot) else ""
        var index = 1
        while (true) {
            val candidateInner = "$stem ($index)$ext"
            val candidate = if (isEnc) "$candidateInner${BackupCrypto.ENCRYPTED_EXT}" else candidateInner
            if (claimedNames.add(claimedNameKey(virtualPath, candidate))) return candidate
            index++
        }
    }

    private data class ScannedNormalEntry(
        val item: UploadFileItem,
        val virtualPath: String,
        val lastModifiedMs: Long,
    )

    private suspend fun scanFolderForNormalUpload(treeUri: Uri): List<ScannedNormalEntry> {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val rootName = runCatching { DocumentFile.fromTreeUri(ctx, treeUri)?.name }.getOrNull()
            ?: rootDocId?.substringAfterLast(':')?.substringAfterLast('/')
            ?: "Folder"
        if (!rootDocId.isNullOrBlank()) {
            val cursorOut = runCatching {
                scanNormalViaCursor(treeUri, rootDocId, rootName)
            }.getOrNull()
            if (cursorOut != null) return cursorOut
        }
        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return emptyList()
        val out = mutableListOf<ScannedNormalEntry>()
        suspend fun walk(dir: DocumentFile, relPath: String) {
            dir.listFiles().sortedBy { it.name.orEmpty().lowercase(Locale.US) }.forEach { child ->
                currentCoroutineContext().ensureActive()
                val name = child.name ?: return@forEach
                if (child.isDirectory) {
                    val nextRel = if (relPath.isEmpty()) name else "$relPath/$name"
                    walk(child, nextRel)
                } else if (child.isFile) {
                    val safeName = sanitizeLeafName(name)
                    out.add(
                        ScannedNormalEntry(
                            item = UploadFileItem(
                                uri = child.uri,
                                displayName = safeName,
                                mimeType = child.type ?: guessMimeType(safeName),
                                sizeBytes = child.length().coerceAtLeast(0L),
                            ),
                            virtualPath = relPath,
                            lastModifiedMs = child.lastModified().coerceAtLeast(0L),
                        ),
                    )
                }
            }
        }
        walk(root, sanitizeLeafName(rootName))
        return out
    }

    private suspend fun scanNormalViaCursor(
        treeUri: Uri,
        rootDocId: String,
        rootName: String,
    ): List<ScannedNormalEntry>? {
        data class Node(val docId: String, val relPath: String)
        data class Row(val docId: String, val name: String, val mime: String, val size: Long, val lastMod: Long)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<ScannedNormalEntry>()
        val stack = ArrayDeque<Node>()
        stack.add(Node(rootDocId, sanitizeLeafName(rootName)))
        var dirsVisited = 0

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val node = stack.removeFirst()
            dirsVisited++
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.docId)
            val rows = mutableListOf<Row>()
            val cursor = runCatching {
                ctx.contentResolver.query(childrenUri, projection, null, null, null)
            }.getOrNull()
            if (cursor == null) {
                if (dirsVisited == 1) return null
                continue
            }
            cursor.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idIdx < 0 || nameIdx < 0) return@use
                while (c.moveToNext()) {
                    val dId = c.getString(idIdx) ?: continue
                    val dName = c.getString(nameIdx) ?: continue
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx).orEmpty() else ""
                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx).coerceAtLeast(0L) else 0L
                    val lastMod = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx).coerceAtLeast(0L) else 0L
                    rows.add(Row(dId, dName, mime, size, lastMod))
                }
            }
            rows.sortBy { it.name.lowercase(Locale.US) }
            val childDirs = mutableListOf<Node>()
            for (r in rows) {
                if (r.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val nextRel = if (node.relPath.isEmpty()) r.name else "${node.relPath}/${r.name}"
                    childDirs.add(Node(r.docId, nextRel))
                } else {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, r.docId)
                    val safeName = sanitizeLeafName(r.name)
                    out.add(
                        ScannedNormalEntry(
                            item = UploadFileItem(
                                uri = docUri,
                                displayName = safeName,
                                mimeType = r.mime.takeIf { it.isNotBlank() } ?: guessMimeType(safeName),
                                sizeBytes = r.size,
                            ),
                            virtualPath = node.relPath,
                            lastModifiedMs = r.lastMod,
                        ),
                    )
                }
            }
            for (idx in childDirs.indices.reversed()) {
                stack.addFirst(childDirs[idx])
            }
        }
        return out
    }

    private fun extractCaptureTimestampMs(
        file: File,
        mimeType: String,
        fallbackModifiedMs: Long,
    ): Long {
        if (mimeType.startsWith("image/", ignoreCase = true) && file.isFile) {
            val exifMs = runCatching {
                val exif = ExifInterface(file.absolutePath)
                val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    ?: return@runCatching null
                val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }
                fmt.parse(raw)?.time
            }.getOrNull()
            if (exifMs != null && exifMs > 946_684_800_000L) {
                return exifMs
            }
        }
        if (fallbackModifiedMs > 946_684_800_000L) return fallbackModifiedMs
        val mod = file.lastModified()
        return if (mod > 946_684_800_000L) mod else System.currentTimeMillis()
    }

    private fun writeDecryptedToDownloads(source: File, fileName: String, mimeType: String): File? =
        PublicDownloadsExporter.saveToPublicDownloads(ctx, source, fileName, mimeType)

    private suspend fun shouldDeferForWifi(specWifiOnly: Boolean): Boolean {
        val globalWifiOnly = runCatching { appPreferences.data.first().manualUploadWifiOnly }.getOrDefault(false)
        if (!specWifiOnly && !globalWifiOnly) return false
        return !networkMonitor.isCurrentlyUnmetered()
    }

    private suspend fun markWaitingForWifi(id: String) {
        waitingForWifiIds.add(id)
        pausedIds.add(id)
        activeJobs.remove(id)
        uploadTracker.markPaused(id, detail = "Waiting for Wi-Fi…")
        withContext(NonCancellable) {
            runCatching { pendingUploadDao.updateStatus(id, STATUS_WAITING_WIFI) }
        }
    }

    private suspend fun deferForWifi(id: String) {
        cancelledIds.remove(id)
        waitingForWifiIds.add(id)
        pausedIds.add(id)
        activeJobs.remove(id)?.cancel()
        uploadTracker.markPaused(id, detail = "Waiting for Wi-Fi…")
        withContext(NonCancellable) {
            runCatching { pendingUploadDao.updateStatus(id, STATUS_WAITING_WIFI) }
        }
    }

    private fun observeNetworkForWifiQueue() {
        scope.launch {
            networkMonitor.isUnmetered.collect { unmetered ->
                if (unmetered) {
                    if (waitingForWifiIds.isNotEmpty()) {
                        val toResume = waitingForWifiIds.toList()
                        toResume.forEach { id ->
                            waitingForWifiIds.remove(id)
                            pausedIds.remove(id)
                            retry(id)
                        }
                    }
                } else {
                    val globalWifiOnly = runCatching {
                        appPreferences.data.first().manualUploadWifiOnly
                    }.getOrDefault(false)
                    uploads.value.items
                        .filter { it.status.isActive }
                        .forEach { item ->
                            val specWifiOnly = when (val spec = retrySpecs[item.id]) {
                                is RetrySpec.SingleFile -> spec.options.wifiOnly
                                is RetrySpec.FolderZip -> spec.request.wifiOnly
                                is RetrySpec.UrisZip -> spec.request.wifiOnly
                                null -> false
                            }
                            if (specWifiOnly || globalWifiOnly) {
                                deferForWifi(item.id)
                            }
                        }
                }
            }
        }
    }

    private suspend fun restorePendingUploads() {
        val rows = runCatching { pendingUploadDao.getAll() }.getOrDefault(emptyList())
        if (rows.isEmpty()) return
        for (row in rows) {
            if (row.status == UploadStatus.DONE.name || row.status == UploadStatus.CANCELLED.name) {
                runCatching { pendingUploadDao.deleteById(row.id) }
                continue
            }
            if (retrySpecs.containsKey(row.id) || uploadTracker.state.value.items.any { it.id == row.id }) {
                continue
            }
            val spec = deserializeSpec(row.kind, row.payloadJson) ?: continue
            val isWifiWait = row.status == STATUS_WAITING_WIFI
            val detail = if (isWifiWait) "Waiting for Wi-Fi…" else "Paused after restart"
            retrySpecs[row.id] = spec
            pausedIds.add(row.id)
            if (isWifiWait) {
                waitingForWifiIds.add(row.id)
            }
            uploadTracker.start(
                id = row.id,
                fileName = row.displayName,
                totalBytes = row.totalBytes,
                chatId = row.chatId,
                status = UploadStatus.PAUSED,
                statusDetail = detail,
                isEncrypted = row.isEncrypted,
                canCancel = true,
            )
            uploadTracker.markPaused(row.id, detail = detail)
        }
        if (waitingForWifiIds.isNotEmpty() && networkMonitor.isCurrentlyUnmetered()) {
            val toResume = waitingForWifiIds.toList()
            toResume.forEach { id ->
                waitingForWifiIds.remove(id)
                pausedIds.remove(id)
                retry(id)
            }
        }
    }

    private suspend fun persistPendingSpec(
        id: String,
        spec: RetrySpec,
        displayName: String,
        totalBytes: Long,
        status: String,
    ) {
        val (kind, payload) = serializeSpec(spec) ?: return
        val (chatId, isEncrypted) = when (spec) {
            is RetrySpec.SingleFile -> spec.chatId to spec.options.encryption.enabled
            is RetrySpec.FolderZip -> spec.request.chatId to spec.request.encryption.enabled
            is RetrySpec.UrisZip -> spec.request.chatId to spec.request.encryption.enabled
        }
        runCatching {
            pendingUploadDao.upsert(
                PendingUploadEntity(
                    id = id,
                    kind = kind,
                    chatId = chatId,
                    displayName = displayName,
                    totalBytes = totalBytes.coerceAtLeast(0L),
                    status = status,
                    payloadJson = payload,
                    isEncrypted = isEncrypted,
                ),
            )
        }
    }

    private fun serializeSpec(spec: RetrySpec): Pair<String, String>? {
        return when (spec) {
            is RetrySpec.SingleFile -> {
                val map = linkedMapOf(
                    "chatId" to spec.chatId.toString(),
                    "uri" to spec.file.uri.toString(),
                    "name" to spec.file.displayName,
                    "mime" to spec.file.mimeType,
                    "size" to spec.file.sizeBytes.toString(),
                    "vpath" to spec.virtualPath,
                    "mtime" to spec.lastModifiedMs.toString(),
                    "conflict" to spec.options.conflictStrategy.name,
                    "split" to spec.options.autoSplitOversized.toString(),
                    "wifi" to spec.options.wifiOnly.toString(),
                    "enc" to spec.options.encryption.enabled.toString(),
                    "saved_pass" to spec.options.encryption.useSavedPassphrase.toString(),
                )
                val pass = spec.options.encryption.customPassphrase
                if (spec.options.encryption.enabled && !spec.options.encryption.useSavedPassphrase && !pass.isNullOrBlank()) {
                    encryptSecretHex(pass)?.let { map["pass"] = it }
                }
                KIND_SINGLE to encodeKvMap(map)
            }
            is RetrySpec.FolderZip -> {
                val req = spec.request
                val map = linkedMapOf(
                    "treeUri" to spec.treeUri.toString(),
                    "chatId" to req.chatId.toString(),
                    "baseName" to req.baseName,
                    "mode" to req.compressionMode.name,
                    "excl" to req.excludePatterns,
                    "wifi" to req.wifiOnly.toString(),
                    "vpath" to req.virtualPath,
                    "enc" to req.encryption.enabled.toString(),
                    "saved_pass" to req.encryption.useSavedPassphrase.toString(),
                )
                req.maxPartBytesOverride?.let { map["maxPart"] = it.toString() }
                val pass = req.encryption.customPassphrase
                if (req.encryption.enabled && !req.encryption.useSavedPassphrase && !pass.isNullOrBlank()) {
                    encryptSecretHex(pass)?.let { map["pass"] = it }
                }
                KIND_FOLDER_ZIP to encodeKvMap(map)
            }
            is RetrySpec.UrisZip -> {
                if (spec.files.isEmpty()) return null
                val req = spec.request
                val map = linkedMapOf(
                    "chatId" to req.chatId.toString(),
                    "baseName" to req.baseName,
                    "mode" to req.compressionMode.name,
                    "excl" to req.excludePatterns,
                    "wifi" to req.wifiOnly.toString(),
                    "vpath" to req.virtualPath,
                    "enc" to req.encryption.enabled.toString(),
                    "saved_pass" to req.encryption.useSavedPassphrase.toString(),
                    "count" to spec.files.size.toString(),
                )
                req.maxPartBytesOverride?.let { map["maxPart"] = it.toString() }
                val pass = req.encryption.customPassphrase
                if (req.encryption.enabled && !req.encryption.useSavedPassphrase && !pass.isNullOrBlank()) {
                    encryptSecretHex(pass)?.let { map["pass"] = it }
                }
                spec.files.forEachIndexed { idx, f ->
                    map["f_${idx}_uri"] = f.uri.toString()
                    map["f_${idx}_name"] = f.displayName
                    map["f_${idx}_mime"] = f.mimeType
                    map["f_${idx}_size"] = f.sizeBytes.toString()
                }
                KIND_URIS_ZIP to encodeKvMap(map)
            }
        }
    }

    private fun deserializeSpec(kind: String, payload: String): RetrySpec? {
        val map = decodeKvMap(payload)
        if (map.isEmpty()) return null
        val customPass = map["pass"]?.let { decryptSecretHex(it) }
        val encSpec = UploadEncryptionSpec(
            enabled = map["enc"]?.toBooleanStrictOrNull() ?: false,
            useSavedPassphrase = (map["saved_pass"] ?: map["vault"])?.toBooleanStrictOrNull() ?: true,
            customPassphrase = customPass,
        )
        return when (kind) {
            KIND_SINGLE -> {
                val uriStr = map["uri"]?.takeIf { it.isNotBlank() } ?: return null
                val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return null
                val conflict = map["conflict"]?.let { name ->
                    UploadConflictStrategy.entries.firstOrNull { it.name == name }
                } ?: UploadConflictStrategy.KEEP_BOTH
                RetrySpec.SingleFile(
                    chatId = map["chatId"]?.toLongOrNull() ?: 0L,
                    file = UploadFileItem(
                        uri = uri,
                        displayName = map["name"].orEmpty().ifBlank { "file" },
                        mimeType = map["mime"].orEmpty().ifBlank { "application/octet-stream" },
                        sizeBytes = map["size"]?.toLongOrNull() ?: 0L,
                    ),
                    options = NormalUploadOptions(
                        conflictStrategy = conflict,
                        encryption = encSpec,
                        autoSplitOversized = map["split"]?.toBooleanStrictOrNull() ?: true,
                        wifiOnly = map["wifi"]?.toBooleanStrictOrNull() ?: false,
                    ),
                    virtualPath = map["vpath"].orEmpty(),
                    lastModifiedMs = map["mtime"]?.toLongOrNull() ?: 0L,
                )
            }
            KIND_FOLDER_ZIP -> {
                val treeStr = map["treeUri"]?.takeIf { it.isNotBlank() } ?: return null
                val treeUri = runCatching { Uri.parse(treeStr) }.getOrNull() ?: return null
                val mode = map["mode"]?.let { m ->
                    ZipCompressionMode.entries.firstOrNull { it.name == m }
                } ?: ZipCompressionMode.SMART
                RetrySpec.FolderZip(
                    treeUri = treeUri,
                    request = ZipUploadRequest(
                        chatId = map["chatId"]?.toLongOrNull() ?: 0L,
                        baseName = map["baseName"].orEmpty().ifBlank { "archive" },
                        compressionMode = mode,
                        maxPartBytesOverride = map["maxPart"]?.toLongOrNull(),
                        excludePatterns = map["excl"].orEmpty(),
                        encryption = encSpec,
                        wifiOnly = map["wifi"]?.toBooleanStrictOrNull() ?: false,
                        virtualPath = map["vpath"].orEmpty(),
                    ),
                )
            }
            KIND_URIS_ZIP -> {
                val count = map["count"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                if (count <= 0) return null
                val files = buildList {
                    for (i in 0 until count) {
                        val uStr = map["f_${i}_uri"]?.takeIf { it.isNotBlank() } ?: continue
                        val uri = runCatching { Uri.parse(uStr) }.getOrNull() ?: continue
                        add(
                            UploadFileItem(
                                uri = uri,
                                displayName = map["f_${i}_name"].orEmpty().ifBlank { "file_$i" },
                                mimeType = map["f_${i}_mime"].orEmpty().ifBlank { "application/octet-stream" },
                                sizeBytes = map["f_${i}_size"]?.toLongOrNull() ?: 0L,
                            ),
                        )
                    }
                }
                if (files.isEmpty()) return null
                val mode = map["mode"]?.let { m ->
                    ZipCompressionMode.entries.firstOrNull { it.name == m }
                } ?: ZipCompressionMode.SMART
                RetrySpec.UrisZip(
                    files = files,
                    request = ZipUploadRequest(
                        chatId = map["chatId"]?.toLongOrNull() ?: 0L,
                        baseName = map["baseName"].orEmpty().ifBlank { "archive" },
                        compressionMode = mode,
                        maxPartBytesOverride = map["maxPart"]?.toLongOrNull(),
                        excludePatterns = map["excl"].orEmpty(),
                        encryption = encSpec,
                        wifiOnly = map["wifi"]?.toBooleanStrictOrNull() ?: false,
                        virtualPath = map["vpath"].orEmpty(),
                    ),
                )
            }
            else -> null
        }
    }

    private fun encryptSecretHex(raw: String): String? = backupCrypto.encryptLocalQueueSecret(raw)

    private fun decryptSecretHex(hex: String): String? = backupCrypto.decryptLocalQueueSecret(hex)

    private fun encodeKvMap(map: Map<String, String>): String =
        map.entries.joinToString("\n") { (k, v) ->
            "${escapeKv(k)}=${escapeKv(v)}"
        }

    private fun escapeKv(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                '=' -> append("\\=")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    private fun decodeKvMap(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.split('\n').forEach { line ->
            if (line.isEmpty()) return@forEach
            var eqIdx = -1
            var escaped = false
            for (i in line.indices) {
                val c = line[i]
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '=') {
                    eqIdx = i
                    break
                }
            }
            if (eqIdx > 0) {
                val k = unescapeKv(line.substring(0, eqIdx))
                val v = unescapeKv(line.substring(eqIdx + 1))
                result[k] = v
            }
        }
        return result
    }

    private fun unescapeKv(raw: String): String = buildString(raw.length) {
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    '\\' -> append('\\')
                    '=' -> append('=')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(raw[i + 1])
                }
                i += 2
            } else {
                append(ch)
                i += 1
            }
        }
    }

    private fun purgeStaleStaging() {
        val cutoff = System.currentTimeMillis() - STALE_STAGING_MS
        listOf(STAGING_DIR_NAME, "share_staging").forEach { dirName ->
            val dir = File(ctx.cacheDir, dirName)
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { child ->
                    if (child.lastModified() < cutoff) {
                        runCatching { child.deleteRecursively() }
                    }
                }
            }
        }
    }

    companion object {
        private const val KIND_SINGLE = "SINGLE"
        private const val KIND_FOLDER_ZIP = "FOLDER_ZIP"
        private const val KIND_URIS_ZIP = "URIS_ZIP"
        private const val STATUS_WAITING_WIFI = "WAITING_WIFI"
        private const val STAGING_DIR_NAME = "upload_staging"
        private const val STALE_STAGING_MS = 12L * 60 * 60 * 1000
        private const val MAX_STAGE_PARALLEL = 2
        private const val MAX_SMALL_PARALLEL = 4
        private const val LARGE_FILE_THRESHOLD_BYTES = 100L * 1024 * 1024
        private const val MAX_UPLOAD_ATTEMPTS = 3
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val FREE_SPACE_SAFETY_BYTES = 64L * 1024 * 1024
        private val FLOOD_WAIT_REGEX = Regex("""FLOOD_WAIT_(\d+)""", RegexOption.IGNORE_CASE)

        fun parseFloodWaitSeconds(message: String?): Long? {
            if (message.isNullOrBlank()) return null
            return FLOOD_WAIT_REGEX.find(message)?.groupValues?.getOrNull(1)?.toLongOrNull()
        }

        private fun isTransientError(t: Throwable): Boolean {
            if (t is java.io.IOException) return true
            val msg = t.message.orEmpty().lowercase(Locale.US)
            return "timeout" in msg ||
                "stalled" in msg ||
                "connection" in msg ||
                "network" in msg ||
                "reset" in msg ||
                "502" in msg ||
                "503" in msg ||
                "500" in msg
        }

        fun sanitizeLeafName(raw: String): String =
            raw.substringAfterLast('/')
                .substringAfterLast('\\')
                .replace('\u0000', '_')
                .trim()
                .ifBlank { "file_${System.currentTimeMillis()}" }

        fun guessMimeType(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
            if (ext.isNotEmpty()) {
                runCatching {
                    MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
                }.getOrNull()?.let { if (it.isNotBlank()) return it }
            }
            return when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "heic", "heif" -> "image/heic"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                "flac" -> "audio/flac"
                "ogg", "opus" -> "audio/ogg"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "txt", "log", "md" -> "text/plain"
                "json" -> "application/json"
                else -> "application/octet-stream"
            }
        }
    }
}
