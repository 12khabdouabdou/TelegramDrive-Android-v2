package com.tgdrive.core.download

import android.content.Context
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.telegram.DownloadUpdate
import com.tgdrive.core.telegram.TelegramClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide download queue manager — Singleton.
 *
 * Uses composite `(chatId, messageId)` keys so identical message IDs across
 * different folders never collide, computes rolling speed/ETA, triggers
 * [TransferKeepAliveWorker] so background downloads survive process backgrounding,
 * and copies pinned downloads into [OfflinePinStore].
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val telegramClient: TelegramClient,
    private val dispatchers: DispatchersProvider,
    private val offlinePinStore: OfflinePinStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val concurrencyLimiter = Semaphore(2)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lastNotifUpdateMs = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var dismissNotifJob: Job? = null

    private data class SpeedSample(var lastMs: Long, var lastBytes: Long, var smoothedBps: Long)
    private val speedSamples = ConcurrentHashMap<String, SpeedSample>()

    private val _state = MutableStateFlow(DownloadQueueState())
    val state: StateFlow<DownloadQueueState> = _state.asStateFlow()

    private val downloadsDir = File(ctx.cacheDir, "downloads").apply { mkdirs() }

    private fun keyFor(chatId: Long, id: Long): String = "${chatId}_$id"

    private fun matchesKey(item: DownloadItem, chatId: Long, id: Long): Boolean =
        item.id == id && (chatId == 0L || item.chatId == 0L || item.chatId == chatId)

    fun findItem(chatId: Long, id: Long): DownloadItem? =
        _state.value.items.firstOrNull { matchesKey(it, chatId, id) }

    fun enqueue(item: DownloadItem) {
        val key = keyFor(item.chatId, item.id)
        val pinnedPath = offlinePinStore.getPinnedFilePathSync(item.chatId, item.id)
        if (pinnedPath != null) {
            val doneItem = item.copy(
                status = DownloadStatus.DONE,
                progress = 1f,
                downloadedBytes = item.totalBytes,
                localPath = pinnedPath,
                speedBytesPerSec = 0L,
                etaSeconds = null,
            )
            _state.update { s ->
                val without = s.items.filterNot { matchesKey(it, item.chatId, item.id) }
                s.copy(items = without + doneItem)
            }
            return
        }

        val existing = _state.value.items.find { matchesKey(it, item.chatId, item.id) }
        if (existing != null) {
            if (existing.status == DownloadStatus.DONE) {
                val fileExists = runCatching {
                    existing.localPath != null && File(existing.localPath).exists()
                }.getOrDefault(false)
                if (fileExists) {
                    if (item.pinOfflineWhenDone && existing.localPath != null) {
                        scope.launch {
                            offlinePinStore.pinFile(item.chatId, item.id, existing.localPath, item.fileName)?.let { pinned ->
                                updateItem(item.chatId, item.id) { it.copy(localPath = pinned, pinOfflineWhenDone = true) }
                            }
                        }
                    }
                    return
                }
                updateItem(item.chatId, item.id) {
                    it.copy(
                        status = DownloadStatus.QUEUED,
                        progress = 0f,
                        downloadedBytes = 0L,
                        speedBytesPerSec = 0L,
                        etaSeconds = null,
                        errorMessage = null,
                        localPath = null,
                        pinOfflineWhenDone = it.pinOfflineWhenDone || item.pinOfflineWhenDone,
                    )
                }
            } else if (existing.status != DownloadStatus.FAILED && existing.status != DownloadStatus.CANCELLED) {
                if (item.pinOfflineWhenDone && !existing.pinOfflineWhenDone) {
                    updateItem(item.chatId, item.id) { it.copy(pinOfflineWhenDone = true) }
                }
                return
            } else {
                updateItem(item.chatId, item.id) {
                    it.copy(
                        status = DownloadStatus.QUEUED,
                        progress = 0f,
                        downloadedBytes = 0L,
                        speedBytesPerSec = 0L,
                        etaSeconds = null,
                        errorMessage = null,
                        pinOfflineWhenDone = it.pinOfflineWhenDone || item.pinOfflineWhenDone,
                    )
                }
            }
            jobs[key]?.cancel()
            TransferKeepAliveWorker.ensureRunning(ctx)
            jobs[key] = scope.launch { runDownload(item.copy(pinOfflineWhenDone = existing.pinOfflineWhenDone || item.pinOfflineWhenDone)) }
            return
        }
        _state.update { it.copy(items = it.items + item) }
        TransferKeepAliveWorker.ensureRunning(ctx)
        jobs[key] = scope.launch { runDownload(item) }
    }

    fun cancel(id: Long) {
        val matchingKeys = _state.value.items.filter { it.id == id }.map { keyFor(it.chatId, it.id) }
        matchingKeys.forEach { k ->
            jobs[k]?.cancel()
            jobs.remove(k)
            speedSamples.remove(k)
        }
        _state.update { s -> s.copy(items = s.items.filter { it.id != id }) }
    }

    fun cancel(chatId: Long, id: Long) {
        val k = keyFor(chatId, id)
        jobs[k]?.cancel()
        jobs.remove(k)
        speedSamples.remove(k)
        _state.update { s -> s.copy(items = s.items.filterNot { matchesKey(it, chatId, id) }) }
    }

    fun clearCompleted() {
        _state.update { s ->
            s.copy(
                items = s.items.filter {
                    it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
                },
            )
        }
    }

    private suspend fun runDownload(item: DownloadItem) {
        val key = keyFor(item.chatId, item.id)
        concurrencyLimiter.withPermit {
            speedSamples[key] = SpeedSample(System.currentTimeMillis(), 0L, 0L)
            updateItem(item.chatId, item.id) { it.copy(status = DownloadStatus.DOWNLOADING, progress = 0f) }
            updateNotification(force = true)
            try {
                downloadsDir.mkdirs()
                Log.i("DownloadManager: starting download for '${item.fileName}' (chat=${item.chatId}, id=${item.id})")
                telegramClient.downloadFile(item.remoteId, "")
                    .collect { upd ->
                        when (upd) {
                            is DownloadUpdate.Progress -> {
                                val now = System.currentTimeMillis()
                                val sample = speedSamples[key]
                                var speedBps = sample?.smoothedBps ?: 0L
                                if (sample != null) {
                                    val dt = now - sample.lastMs
                                    val db = upd.downloaded - sample.lastBytes
                                    if (dt >= 400L && db >= 0L) {
                                        val instant = (db * 1000L) / dt.coerceAtLeast(1L)
                                        speedBps = if (speedBps == 0L) instant else (speedBps * 7L + instant * 3L) / 10L
                                        sample.lastMs = now
                                        sample.lastBytes = upd.downloaded
                                        sample.smoothedBps = speedBps
                                    }
                                }
                                updateItem(item.chatId, item.id) { cur ->
                                    val effectiveTotal = if (upd.total > 0L) upd.total else cur.totalBytes
                                    val raw = if (effectiveTotal <= 0L) 0f else upd.downloaded.toFloat() / effectiveTotal.toFloat()
                                    val safeProgress = if (raw.isFinite()) raw.coerceIn(0f, 1f) else 0f
                                    val remaining = (effectiveTotal - upd.downloaded).coerceAtLeast(0L)
                                    val eta = if (speedBps > 0L && effectiveTotal > 0L && remaining > 0L) remaining / speedBps else null
                                    cur.copy(
                                        progress = safeProgress,
                                        downloadedBytes = upd.downloaded,
                                        totalBytes = effectiveTotal,
                                        speedBytesPerSec = speedBps,
                                        etaSeconds = eta,
                                    )
                                }
                                updateNotification(force = false)
                            }
                            is DownloadUpdate.Done -> {
                                val latestItem = findItem(item.chatId, item.id) ?: item
                                val finalPath = if (latestItem.pinOfflineWhenDone) {
                                    offlinePinStore.pinFile(item.chatId, item.id, upd.localPath, item.fileName) ?: upd.localPath
                                } else {
                                    upd.localPath
                                }
                                Log.i("DownloadManager: done → $finalPath")
                                updateItem(item.chatId, item.id) {
                                    it.copy(
                                        status = DownloadStatus.DONE,
                                        progress = 1f,
                                        downloadedBytes = if (it.totalBytes > 0L) it.totalBytes else it.downloadedBytes,
                                        localPath = finalPath,
                                        speedBytesPerSec = 0L,
                                        etaSeconds = null,
                                    )
                                }
                                updateNotification(force = true)
                            }
                            is DownloadUpdate.Failed -> {
                                Log.w("DownloadManager: failed — ${upd.message}")
                                updateItem(item.chatId, item.id) {
                                    it.copy(
                                        status = DownloadStatus.FAILED,
                                        speedBytesPerSec = 0L,
                                        etaSeconds = null,
                                        errorMessage = upd.message,
                                    )
                                }
                                updateNotification(force = true)
                            }
                        }
                    }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w("Download failed for ${item.fileName}", t)
                updateItem(item.chatId, item.id) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        speedBytesPerSec = 0L,
                        etaSeconds = null,
                        errorMessage = t.message,
                    )
                }
                updateNotification(force = true)
            } finally {
                jobs.remove(key)
                speedSamples.remove(key)
                updateNotification(force = true)
            }
        }
    }

    /**
     * Post/update/cancel the download progress notification.
     */
    private fun updateNotification(force: Boolean = false) {
        try {
            if (!force) {
                val now = System.currentTimeMillis()
                val prev = lastNotifUpdateMs.get()
                if (now - prev < 300L || !lastNotifUpdateMs.compareAndSet(prev, now)) return
            } else {
                lastNotifUpdateMs.set(System.currentTimeMillis())
            }

            val nm = androidx.core.app.NotificationManagerCompat.from(ctx)
            if (!nm.areNotificationsEnabled()) return

            val active = _state.value.items.filter {
                it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
            }
            val completed = _state.value.items.filter {
                it.status == DownloadStatus.DONE || it.status == DownloadStatus.FAILED
            }

            if (active.isEmpty() && completed.isNotEmpty()) {
                val notif = androidx.core.app.NotificationCompat.Builder(ctx, "download.progress")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Downloads complete")
                    .setContentText("${completed.size} file(s) downloaded")
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .build()
                nm.notify(DOWNLOAD_NOTIF_ID, notif)
                dismissNotifJob?.cancel()
                dismissNotifJob = scope.launch {
                    kotlinx.coroutines.delay(3000)
                    runCatching { nm.cancel(DOWNLOAD_NOTIF_ID) }
                }
            } else if (active.isNotEmpty()) {
                dismissNotifJob?.cancel()
                dismissNotifJob = null
                val current = active.firstOrNull { it.status == DownloadStatus.DOWNLOADING } ?: active.first()
                val progress = ((current.progress.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)
                val notif = androidx.core.app.NotificationCompat.Builder(ctx, "download.progress")
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("Downloading ${current.fileName}")
                    .setContentText(if (active.size > 1) "$progress% • ${active.size} files in queue" else "$progress%")
                    .setProgress(100, progress, progress == 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
                nm.notify(DOWNLOAD_NOTIF_ID, notif)
            } else {
                dismissNotifJob?.cancel()
                dismissNotifJob = null
                nm.cancel(DOWNLOAD_NOTIF_ID)
            }
        } catch (_: Throwable) {
            // Notifications are best-effort
        }
    }

    companion object {
        private const val DOWNLOAD_NOTIF_ID = 50001
    }

    private fun updateItem(chatId: Long, id: Long, transform: (DownloadItem) -> DownloadItem) {
        _state.update { cur ->
            cur.copy(items = cur.items.map { if (matchesKey(it, chatId, id)) transform(it) else it })
        }
    }

    /**
     * Clear completed downloads from the temporary local cache directory and TDLib cache,
     * preserving permanently offline-pinned files in `filesDir/offline_pins/`.
     */
    fun clearCache() {
        _state.value.items
            .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
            .forEach { cancel(it.chatId, it.id) }

        _state.update { s ->
            s.copy(
                items = s.items.filter {
                    offlinePinStore.getPinnedFilePathSync(it.chatId, it.id) != null
                },
            )
        }
        updateNotification(force = true)

        scope.launch {
            downloadsDir.listFiles()?.forEach { f ->
                runCatching { f.delete() }
            }
            runCatching { telegramClient.clearDownloadCache() }
        }
    }
}

data class DownloadItem(
    val id: Long,
    val remoteId: String,
    val fileName: String,
    val chatId: Long = 0L,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val localPath: String? = null,
    val errorMessage: String? = null,
    val pinOfflineWhenDone: Boolean = false,
)

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, FAILED, CANCELLED }

data class DownloadQueueState(
    val items: List<DownloadItem> = emptyList(),
)
