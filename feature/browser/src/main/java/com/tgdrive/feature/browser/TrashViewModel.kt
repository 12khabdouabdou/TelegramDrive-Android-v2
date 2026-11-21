package com.tgdrive.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.TrashEntryEntity
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.telegram.TelegramClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TrashUiState(
    val items: List<TrashEntryEntity> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trashEntryDao: TrashEntryDao,
    private val fileCacheDao: FileCacheDao,
    private val telegramClient: TelegramClient,
    private val cloudSyncManager: CloudSyncManager,
    private val dispatchers: DispatchersProvider,
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    val state: StateFlow<TrashUiState> = combine(
        trashEntryDao.observeAll(),
        _busy,
        _error,
    ) { items, busy, error ->
        TrashUiState(items = items, busy = busy, error = error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrashUiState())

    fun daysRemaining(entry: TrashEntryEntity): Int {
        val remainingMs = (entry.deletedAt + CloudSyncManager.TRASH_RETENTION_MS) - System.currentTimeMillis()
        return (remainingMs.coerceAtLeast(0L) / DAY_MS).toInt().coerceIn(0, 30)
    }

    fun restore(entry: TrashEntryEntity) {
        viewModelScope.launch(dispatchers.io) {
            _busy.value = true
            try {
                fileCacheDao.upsertAll(
                    listOf(
                        FileCacheEntity(
                            messageId = entry.messageId,
                            chatId = entry.chatId,
                            fileName = entry.fileName,
                            fileSize = entry.fileSize,
                            mimeType = entry.mimeType,
                            thumbnailPath = entry.thumbnailPath,
                            remoteId = entry.remoteId,
                            date = entry.originalDate,
                            isStarred = entry.isStarred,
                        ),
                    ),
                )
                trashEntryDao.deleteById(entry.chatId, entry.messageId)
                cloudSyncManager.noteTrashRemoved(entry.chatId, entry.messageId)
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("TrashViewModel: restore failed", t)
                _error.value = t.message ?: "Restore failed"
            } finally {
                withContext(NonCancellable) { _busy.value = false }
            }
        }
    }

    fun deleteForever(entry: TrashEntryEntity) {
        viewModelScope.launch(dispatchers.io) {
            _busy.value = true
            try {
                try {
                    telegramClient.deleteMessage(entry.chatId, entry.messageId)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // Message may already be deleted on server; still remove local trash record.
                    Log.w("TrashViewModel: remote delete failed for msgId=${entry.messageId}", t)
                }
                trashEntryDao.deleteById(entry.chatId, entry.messageId)
                cloudSyncManager.noteTrashRemoved(entry.chatId, entry.messageId)
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("TrashViewModel: deleteForever failed", t)
                _error.value = t.message ?: "Delete failed"
            } finally {
                withContext(NonCancellable) { _busy.value = false }
            }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch(dispatchers.io) {
            _busy.value = true
            try {
                val all = trashEntryDao.getAll()
                for (entry in all) {
                    try {
                        telegramClient.deleteMessage(entry.chatId, entry.messageId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w("TrashViewModel: remote delete failed during emptyTrash", t)
                    }
                    trashEntryDao.deleteById(entry.chatId, entry.messageId)
                    cloudSyncManager.noteTrashRemoved(entry.chatId, entry.messageId)
                }
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("TrashViewModel: emptyTrash failed", t)
                _error.value = t.message ?: "Failed to empty trash"
            } finally {
                withContext(NonCancellable) { _busy.value = false }
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    companion object {
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
