package com.tgdrive.core.download

import android.content.Context
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.TrashEntryEntity
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.telegram.TelegramClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class BulkDeleteProgress(
    val id: Long,
    val chatId: Long,
    val label: String,
    val completed: Int,
    val total: Int,
    val permanent: Boolean,
)

data class BulkDeleteCompletion(
    val id: Long,
    val chatId: Long,
    val label: String,
    val deletedCount: Int,
    val totalCount: Int,
    val permanent: Boolean,
    val trashedEntries: List<TrashEntryEntity> = emptyList(),
    val error: String? = null,
)

/**
 * Application-scoped manager for bulk file/subfolder deletions and virtual-path renames.
 * Runs independently of any screen ViewModel lifecycle so navigating back to Home or
 * backgrounding the app does not cancel in-flight 1,600+ item operations.
 */
@Singleton
class BulkDeleteManager
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val telegramClient: TelegramClient,
        private val fileCacheDao: FileCacheDao,
        private val trashEntryDao: TrashEntryDao,
        private val offlinePinStore: OfflinePinStore,
        private val cloudSyncManager: CloudSyncManager,
        private val dispatchers: DispatchersProvider,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
        private val nextJobId = AtomicLong(0L)
        private val runningJobs = ConcurrentHashMap<Long, Job>()

        private val _activeJobs = MutableStateFlow<List<BulkDeleteProgress>>(emptyList())
        val activeJobs: StateFlow<List<BulkDeleteProgress>> = _activeJobs.asStateFlow()

        private val _completions = MutableSharedFlow<BulkDeleteCompletion>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val completions: SharedFlow<BulkDeleteCompletion> = _completions.asSharedFlow()

        fun enqueueDelete(
            chatId: Long,
            label: String,
            targets: List<FileCacheEntity>,
            permanent: Boolean,
        ): Long {
            val jobId = nextJobId.incrementAndGet()
            if (targets.isEmpty()) {
                _completions.tryEmit(
                    BulkDeleteCompletion(
                        id = jobId,
                        chatId = chatId,
                        label = label,
                        deletedCount = 0,
                        totalCount = 0,
                        permanent = permanent,
                    ),
                )
                return jobId
            }

            _activeJobs.update { current ->
                current + BulkDeleteProgress(
                    id = jobId,
                    chatId = chatId,
                    label = label,
                    completed = 0,
                    total = targets.size,
                    permanent = permanent,
                )
            }
            runCatching { TransferKeepAliveWorker.ensureRunning(appContext) }

            val job = scope.launch {
                try {
                    if (!permanent) {
                        val now = System.currentTimeMillis()
                        val trashedAll = targets.map { item ->
                            TrashEntryEntity(
                                messageId = item.messageId,
                                chatId = item.chatId,
                                fileName = item.fileName,
                                fileSize = item.fileSize,
                                mimeType = item.mimeType,
                                thumbnailPath = item.thumbnailPath,
                                remoteId = item.remoteId,
                                originalDate = item.date,
                                isStarred = item.isStarred,
                                deletedAt = now,
                            )
                        }
                        var doneCount = 0
                        for (chunk in trashedAll.chunked(250)) {
                            trashEntryDao.upsertAll(chunk)
                            fileCacheDao.deleteByIds(chatId, chunk.map { it.messageId })
                            doneCount += chunk.size
                            updateProgress(jobId, doneCount)
                        }
                        cloudSyncManager.schedulePushMetadata()
                        _completions.tryEmit(
                            BulkDeleteCompletion(
                                id = jobId,
                                chatId = chatId,
                                label = label,
                                deletedCount = trashedAll.size,
                                totalCount = targets.size,
                                permanent = false,
                                trashedEntries = trashedAll,
                            ),
                        )
                    } else {
                        var deletedCount = 0
                        var lastErr: String? = null
                        val chunks = targets.chunked(100)
                        for ((idx, chunk) in chunks.withIndex()) {
                            val succeeded = ArrayList<FileCacheEntity>(chunk.size)
                            try {
                                telegramClient.deleteMessages(chatId, chunk.map { it.messageId })
                                succeeded.addAll(chunk)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                lastErr = t.message
                                Log.w("BulkDeleteManager: batch delete failed (${chunk.size}), trying itemwise", t)
                                for (item in chunk) {
                                    try {
                                        telegramClient.deleteMessage(chatId, item.messageId)
                                        succeeded.add(item)
                                    } catch (e2: CancellationException) {
                                        throw e2
                                    } catch (t2: Throwable) {
                                        lastErr = t2.message
                                        Log.w("BulkDeleteManager: failed msg=${item.messageId}", t2)
                                    }
                                }
                            }
                            if (succeeded.isNotEmpty()) {
                                fileCacheDao.deleteByIds(chatId, succeeded.map { it.messageId })
                                for (item in succeeded) {
                                    if (item.isPinnedOffline) {
                                        runCatching { offlinePinStore.unpinFile(chatId, item.messageId) }
                                    }
                                }
                                deletedCount += succeeded.size
                            }
                            updateProgress(jobId, minOf(targets.size, (idx + 1) * 100))
                            if (idx < chunks.lastIndex) {
                                delay(120L)
                            }
                        }
                        cloudSyncManager.schedulePushMetadata()
                        _completions.tryEmit(
                            BulkDeleteCompletion(
                                id = jobId,
                                chatId = chatId,
                                label = label,
                                deletedCount = deletedCount,
                                totalCount = targets.size,
                                permanent = true,
                                error = lastErr,
                            ),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("BulkDeleteManager: job=$jobId failed", t)
                    _completions.tryEmit(
                        BulkDeleteCompletion(
                            id = jobId,
                            chatId = chatId,
                            label = label,
                            deletedCount = 0,
                            totalCount = targets.size,
                            permanent = permanent,
                            error = t.message ?: "Delete failed",
                        ),
                    )
                } finally {
                    runningJobs.remove(jobId)
                    _activeJobs.update { current -> current.filterNot { it.id == jobId } }
                }
            }
            runningJobs[jobId] = job
            return jobId
        }

        fun enqueueRenameVirtualPath(
            chatId: Long,
            updates: List<FileCacheDao.VirtualPathUpdate>,
            onDone: (() -> Unit)? = null,
        ) {
            if (updates.isEmpty()) {
                onDone?.invoke()
                return
            }
            runCatching { TransferKeepAliveWorker.ensureRunning(appContext) }
            scope.launch {
                try {
                    updates.chunked(500).forEach { batch ->
                        fileCacheDao.updateVirtualPaths(batch)
                    }
                    cloudSyncManager.schedulePushMetadata()
                    onDone?.invoke()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("BulkDeleteManager: rename updates failed for chat=$chatId", t)
                }
            }
        }

        fun cancelAll() {
            val jobs = runningJobs.values.toList()
            runningJobs.clear()
            _activeJobs.value = emptyList()
            jobs.forEach { it.cancel() }
        }

        private fun updateProgress(jobId: Long, completed: Int) {
            _activeJobs.update { current ->
                current.map { item ->
                    if (item.id == jobId) item.copy(completed = completed) else item
                }
            }
        }
    }
