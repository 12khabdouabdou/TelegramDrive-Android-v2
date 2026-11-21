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
 * Promoted from `:feature:download` to `:core:download` so that
 * `:feature:browser`, `:feature:player`, and `:feature:settings` can
 * all depend on it WITHOUT feature→feature dependencies (per
 * CONTRIBUTING.md "Features don't depend on each other").
 *
 * The UI screen ([com.tgdrive.feature.download.DownloadListScreen])
 * stays in `:feature:download` and depends on this module.
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val telegramClient: TelegramClient,
    private val dispatchers: DispatchersProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val concurrencyLimiter = Semaphore(2)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val lastNotifUpdateMs = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var dismissNotifJob: Job? = null

    private val _state = MutableStateFlow(DownloadQueueState())
    val state: StateFlow<DownloadQueueState> = _state.asStateFlow()

    private val downloadsDir = File(ctx.cacheDir, "downloads").apply { mkdirs() }

    fun enqueue(item: DownloadItem) {
        val existing = _state.value.items.find { it.id == item.id }
        if (existing != null) {
            if (existing.status == DownloadStatus.DONE) {
                val fileExists = runCatching {
                    existing.localPath != null && File(existing.localPath).exists()
                }.getOrDefault(false)
                if (fileExists) return
                updateItem(item.id) { it.copy(status = DownloadStatus.QUEUED, progress = 0f, errorMessage = null, localPath = null) }
            } else if (existing.status != DownloadStatus.FAILED && existing.status != DownloadStatus.CANCELLED) {
                return
            } else {
                updateItem(item.id) { it.copy(status = DownloadStatus.QUEUED, progress = 0f, errorMessage = null) }
            }
            jobs[item.id]?.cancel()
            jobs[item.id] = scope.launch { runDownload(item) }
            return
        }
        _state.update { it.copy(items = it.items + item) }
        jobs[item.id] = scope.launch { runDownload(item) }
    }

    fun cancel(id: Long) {
        jobs[id]?.cancel()
        jobs.remove(id)
        _state.update { s -> s.copy(items = s.items.filter { it.id != id }) }
    }

    fun clearCompleted() {
        _state.update { s ->
            s.copy(items = s.items.filter {
                it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
            })
        }
    }

    private suspend fun runDownload(item: DownloadItem) {
        concurrencyLimiter.withPermit {
            updateItem(item.id) { it.copy(status = DownloadStatus.DOWNLOADING, progress = 0f) }
            updateNotification(force = true)
            try {
                downloadsDir.mkdirs()
                Log.i("DownloadManager: starting download for '${item.fileName}' (remoteId=${item.remoteId})")
                telegramClient.downloadFile(item.remoteId, "")
                    .collect { upd ->
                        when (upd) {
                            is DownloadUpdate.Progress -> {
                                updateItem(item.id) { cur ->
                                    val effectiveTotal = if (upd.total > 0L) upd.total else cur.totalBytes
                                    val raw = if (effectiveTotal <= 0L) 0f else upd.downloaded.toFloat() / effectiveTotal.toFloat()
                                    val safeProgress = if (raw.isFinite()) raw.coerceIn(0f, 1f) else 0f
                                    cur.copy(progress = safeProgress, downloadedBytes = upd.downloaded, totalBytes = effectiveTotal)
                                }
                                updateNotification(force = false)
                            }
                            is DownloadUpdate.Done -> {
                                Log.i("DownloadManager: done → ${upd.localPath}")
                                updateItem(item.id) { it.copy(status = DownloadStatus.DONE, progress = 1f, localPath = upd.localPath) }
                                updateNotification(force = true)
                            }
                            is DownloadUpdate.Failed -> {
                                Log.w("DownloadManager: failed — ${upd.message}")
                                updateItem(item.id) { it.copy(status = DownloadStatus.FAILED, errorMessage = upd.message) }
                                updateNotification(force = true)
                            }
                        }
                    }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w("Download failed for ${item.fileName}", t)
                updateItem(item.id) { it.copy(status = DownloadStatus.FAILED, errorMessage = t.message) }
                updateNotification(force = true)
            } finally {
                jobs.remove(item.id)
                updateNotification(force = true)
            }
        }
    }

    /**
     * Post/update/cancel the download progress notification.
     * Shows "Downloading N files…" when active, clears when done.
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
        } catch (t: Throwable) {
            // Notifications are best-effort — don't crash the download
        }
    }

    companion object {
        private const val DOWNLOAD_NOTIF_ID = 50001
    }

    private fun updateItem(id: Long, transform: (DownloadItem) -> DownloadItem) {
        _state.update { cur ->
            cur.copy(items = cur.items.map { if (it.id == id) transform(it) else it })
        }
    }

    /**
     * Clear completed downloads from the local cache directory and TDLib cache.
     */
    fun clearCache() {
        _state.value.items
            .filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
            .forEach { cancel(it.id) }

        _state.update { it.copy(items = emptyList()) }
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
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val progress: Float = 0f,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val localPath: String? = null,
    val errorMessage: String? = null,
)

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, FAILED, CANCELLED }

data class DownloadQueueState(
    val items: List<DownloadItem> = emptyList(),
)
