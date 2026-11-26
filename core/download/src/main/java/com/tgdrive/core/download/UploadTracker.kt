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
 * Process-wide live upload queue tracker shared by [UploadManager],
 * [TransferKeepAliveWorker], `FolderScreen`, and the unified Transfers tab.
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
        start(
            id = id,
            chatId = chatId,
            fileName = fileName,
            totalBytes = totalBytes,
            status = UploadStatus.UPLOADING,
        )
    }

    fun start(
        id: String,
        fileName: String,
        totalBytes: Long,
        chatId: Long = 0L,
        status: UploadStatus = UploadStatus.QUEUED,
        statusDetail: String? = null,
        isEncrypted: Boolean = false,
        canCancel: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        samples[id] = SpeedSample(lastMs = now, lastBytes = 0L, smoothedBps = 0L)
        val item = UploadItem(
            id = id,
            chatId = chatId,
            fileName = fileName,
            totalBytes = totalBytes.coerceAtLeast(0L),
            uploadedBytes = 0L,
            progress = 0f,
            status = status,
            statusDetail = statusDetail,
            isEncrypted = isEncrypted,
            canCancel = canCancel,
            canRetry = false,
            startedAtMs = now,
        )
        _state.update { cur ->
            cur.copy(items = cur.items.filterNot { it.id == id } + item)
        }
        if (status.isActive) {
            TransferKeepAliveWorker.ensureRunning(ctx)
        }
    }

    fun updatePhase(
        id: String,
        status: UploadStatus,
        detail: String? = null,
        progress: Float? = null,
        totalBytes: Long? = null,
        fileName: String? = null,
        isEncrypted: Boolean? = null,
    ) {
        if (status == UploadStatus.UPLOADING) {
            samples.computeIfAbsent(id) {
                SpeedSample(lastMs = System.currentTimeMillis(), lastBytes = 0L, smoothedBps = 0L)
            }
        }
        _state.update { cur ->
            cur.copy(
                items = cur.items.map { item ->
                    if (item.id != id) {
                        item
                    } else if (
                        item.status == UploadStatus.CANCELLED ||
                        item.status == UploadStatus.PAUSED ||
                        item.status == UploadStatus.DONE
                    ) {
                        item
                    } else {
                        item.copy(
                            status = status,
                            statusDetail = detail,
                            progress = progress?.coerceIn(0f, 1f) ?: item.progress,
                            totalBytes = totalBytes?.coerceAtLeast(0L) ?: item.totalBytes,
                            fileName = fileName ?: item.fileName,
                            isEncrypted = isEncrypted ?: item.isEncrypted,
                            errorMessage = if (status.isActive) null else item.errorMessage,
                            canCancel = status.isActive,
                            canRetry = status == UploadStatus.FAILED || status == UploadStatus.CANCELLED,
                        )
                    }
                },
            )
        }
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
        val isFinalizing = safeTotal > 1L && safeSent == safeTotal - 1L
        val etaSeconds = if (!isFinalizing && speedBps > 0L && safeTotal > 0L && remaining > 0L) remaining / speedBps else null
        val progress = when {
            safeTotal <= 0L -> 0f
            isFinalizing -> 0.98f
            safeSent >= safeTotal -> 1f
            else -> (safeSent.toFloat() / safeTotal.toFloat()).coerceIn(0f, 0.97f)
        }

        _state.update { cur ->
            cur.copy(
                items = cur.items.map { item ->
                    if (item.id != id) {
                        item
                    } else if (
                        item.status == UploadStatus.CANCELLED ||
                        item.status == UploadStatus.PAUSED ||
                        item.status == UploadStatus.DONE
                    ) {
                        item
                    } else {
                        item.copy(
                            status = UploadStatus.UPLOADING,
                            statusDetail = if (isFinalizing) {
                                "Finalizing on Telegram…"
                            } else {
                                item.statusDetail?.takeUnless { it == "Finalizing on Telegram…" }
                            },
                            uploadedBytes = safeSent,
                            totalBytes = if (safeTotal > 0L) safeTotal else item.totalBytes,
                            progress = progress,
                            speedBytesPerSec = speedBps,
                            etaSeconds = etaSeconds,
                            canCancel = true,
                            canRetry = false,
                        )
                    }
                },
            )
        }
    }

    fun completeUpload(id: String, detail: String? = null) {
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
                            statusDetail = detail ?: item.statusDetail?.takeUnless { it == "Finalizing on Telegram…" },
                            errorMessage = null,
                            canCancel = false,
                            canRetry = false,
                        )
                    }
                },
            )
        }
    }

    fun failUpload(id: String, message: String?) {
        markFailed(id = id, message = message, canRetry = true)
    }

    fun markFailed(id: String, message: String?, canRetry: Boolean = true) {
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
                            statusDetail = null,
                            errorMessage = message,
                            canCancel = false,
                            canRetry = canRetry,
                        )
                    }
                },
            )
        }
    }

    fun markPaused(id: String, detail: String = "Paused") {
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
                            status = UploadStatus.PAUSED,
                            statusDetail = detail,
                            errorMessage = null,
                            canCancel = true,
                            canRetry = true,
                        )
                    }
                },
            )
        }
    }

    fun markCancelled(id: String) {
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
                            status = UploadStatus.CANCELLED,
                            statusDetail = "Cancelled",
                            errorMessage = null,
                            canCancel = false,
                            canRetry = true,
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
            cur.copy(items = cur.items.filter { it.status.isInQueue })
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
    val statusDetail: String? = null,
    val isEncrypted: Boolean = false,
    val canCancel: Boolean = true,
    val canRetry: Boolean = false,
)

enum class UploadStatus {
    QUEUED,
    PREPARING,
    PACKING,
    ENCRYPTING,
    UPLOADING,
    PAUSED,
    DONE,
    FAILED,
    CANCELLED,
    ;

    val isActive: Boolean
        get() = this == QUEUED ||
            this == PREPARING ||
            this == PACKING ||
            this == ENCRYPTING ||
            this == UPLOADING

    val isInQueue: Boolean
        get() = isActive || this == PAUSED
}

data class UploadQueueState(
    val items: List<UploadItem> = emptyList(),
)
