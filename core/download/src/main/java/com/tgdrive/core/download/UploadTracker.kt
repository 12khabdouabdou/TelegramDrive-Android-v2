package com.tgdrive.core.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks active and recent uploads across FolderViewModel & AutoBackup so the unified
 * Transfers tab and [TransferKeepAliveWorker] have live upload progress, speed, and ETA.
 */
@Singleton
class UploadTracker @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val _state = MutableStateFlow(UploadQueueState())
    val state: StateFlow<UploadQueueState> = _state.asStateFlow()

    private data class SpeedSample(var lastMs: Long, var lastBytes: Long, var smoothedBps: Long)
    private val samples = ConcurrentHashMap<String, SpeedSample>()

    fun beginUpload(id: String, chatId: Long, fileName: String, totalBytes: Long) {
        val now = System.currentTimeMillis()
        samples[id] = SpeedSample(lastMs = now, lastBytes = 0L, smoothedBps = 0L)
        val item = UploadItem(
            id = id,
            chatId = chatId,
            fileName = fileName,
            totalBytes = totalBytes.coerceAtLeast(0L),
            uploadedBytes = 0L,
            progress = 0f,
            status = UploadStatus.UPLOADING,
            startedAtMs = now,
        )
        _state.update { cur ->
            cur.copy(items = cur.items.filterNot { it.id == id } + item)
        }
        TransferKeepAliveWorker.ensureRunning(ctx)
    }

    fun updateProgress(id: String, sentBytes: Long, totalBytes: Long) {
        val now = System.currentTimeMillis()
        val safeTotal = totalBytes.coerceAtLeast(0L)
        val safeSent = sentBytes.coerceAtLeast(0L)
        val prevSample = samples[id]
        var speedBps = prevSample?.smoothedBps ?: 0L
        if (prevSample != null) {
            val dt = now - prevSample.lastMs
            val db = safeSent - prevSample.lastBytes
            if (dt >= 400L && db >= 0L) {
                val instant = (db * 1000L) / dt.coerceAtLeast(1L)
                speedBps = if (speedBps == 0L) instant else (speedBps * 7L + instant * 3L) / 10L
                prevSample.lastMs = now
                prevSample.lastBytes = safeSent
                prevSample.smoothedBps = speedBps
            }
        }
        val remaining = (safeTotal - safeSent).coerceAtLeast(0L)
        val etaSeconds = if (speedBps > 0L && safeTotal > 0L && remaining > 0L) remaining / speedBps else null
        val progress = if (safeTotal > 0L) (safeSent.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f) else 0f

        _state.update { cur ->
            cur.copy(
                items = cur.items.map { item ->
                    if (item.id != id) {
                        item
                    } else {
                        item.copy(
                            uploadedBytes = safeSent,
                            totalBytes = if (safeTotal > 0L) safeTotal else item.totalBytes,
                            progress = progress,
                            speedBytesPerSec = speedBps,
                            etaSeconds = etaSeconds,
                        )
                    }
                },
            )
        }
    }

    fun completeUpload(id: String) {
        samples.remove(id)
        _state.update { cur ->
            cur.copy(
                items = cur.items.map { item ->
                    if (item.id != id) {
                        item
                    } else {
                        item.copy(
                            uploadedBytes = item.totalBytes,
                            progress = 1f,
                            speedBytesPerSec = 0L,
                            etaSeconds = null,
                            status = UploadStatus.DONE,
                        )
                    }
                },
            )
        }
    }

    fun failUpload(id: String, message: String?) {
        samples.remove(id)
        _state.update { cur ->
            cur.copy(
                items = cur.items.map { item ->
                    if (item.id != id) {
                        item
                    } else {
                        item.copy(
                            speedBytesPerSec = 0L,
                            etaSeconds = null,
                            status = UploadStatus.FAILED,
                            errorMessage = message,
                        )
                    }
                },
            )
        }
    }

    fun dismiss(id: String) {
        samples.remove(id)
        _state.update { cur -> cur.copy(items = cur.items.filterNot { it.id == id }) }
    }

    fun clearCompleted() {
        _state.update { cur ->
            cur.copy(items = cur.items.filter { it.status == UploadStatus.UPLOADING })
        }
    }
}

data class UploadItem(
    val id: String,
    val chatId: Long,
    val fileName: String,
    val totalBytes: Long = 0L,
    val uploadedBytes: Long = 0L,
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long? = null,
    val status: UploadStatus = UploadStatus.UPLOADING,
    val errorMessage: String? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
)

enum class UploadStatus { UPLOADING, DONE, FAILED }

data class UploadQueueState(
    val items: List<UploadItem> = emptyList(),
)
