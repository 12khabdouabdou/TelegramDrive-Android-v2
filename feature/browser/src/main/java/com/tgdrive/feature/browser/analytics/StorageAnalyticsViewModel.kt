package com.tgdrive.feature.browser.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.telegram.TelegramClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Storage Analytics dashboard, with interactive duplicate
 * inspection & cleanup.
 */
@HiltViewModel
class StorageAnalyticsViewModel
    @Inject
    constructor(
        private val fileCacheDao: FileCacheDao,
        private val folderDao: FolderDao,
        private val syncedFileDao: SyncedFileDao,
        private val telegramClient: TelegramClient,
        private val dispatchers: DispatchersProvider,
    ) : ViewModel() {
        private val _state = MutableStateFlow<UiState>(UiState.Loading)
        val state: StateFlow<UiState> = _state.asStateFlow()

        private val _duplicateDialog = MutableStateFlow<DuplicateCleanupState?>(null)
        val duplicateDialog: StateFlow<DuplicateCleanupState?> = _duplicateDialog.asStateFlow()

        init {
            refresh()
        }

        fun refresh() {
            viewModelScope.launch(dispatchers.io) {
                _state.value = UiState.Loading
                try {
                    val report = buildReport()
                    _state.value = UiState.Ready(report)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("StorageAnalyticsVM: refresh failed", t)
                    _state.value = UiState.Error(t.message ?: "Unknown error")
                }
            }
        }

        fun inspectDuplicate(fileName: String, fileSize: Long) {
            viewModelScope.launch(dispatchers.io) {
                try {
                    val copies = fileCacheDao.findAllDuplicates(fileName, fileSize)
                        .sortedBy { it.date }
                    if (copies.size < 2) {
                        _duplicateDialog.value = null
                        return@launch
                    }
                    val folderNames = folderDao.getAll().associate { it.channelId to it.title }
                    val myId = runCatching { telegramClient.myUserId() }.getOrDefault(0L)
                    val entries = copies.map { entity ->
                        val folderLabel = when {
                            folderNames.containsKey(entity.chatId) -> folderNames[entity.chatId]!!
                            myId != 0L && entity.chatId == myId -> "Saved Messages"
                            else -> "Chat ${entity.chatId}"
                        }
                        DuplicateCopyItem(entity = entity, folderName = folderLabel)
                    }
                    _duplicateDialog.value = DuplicateCleanupState(
                        fileName = fileName,
                        fileSize = fileSize,
                        copies = entries,
                        keepMessageId = entries.first().entity.messageId,
                        keepChatId = entries.first().entity.chatId,
                        deleting = false,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("StorageAnalyticsVM: inspectDuplicate failed", t)
                }
            }
        }

        fun selectKeepCopy(chatId: Long, messageId: Long) {
            _duplicateDialog.value = _duplicateDialog.value?.copy(
                keepChatId = chatId,
                keepMessageId = messageId,
            )
        }

        fun confirmDeleteDuplicates() {
            val cur = _duplicateDialog.value ?: return
            if (cur.deleting) return
            viewModelScope.launch(dispatchers.io) {
                _duplicateDialog.value = cur.copy(deleting = true)
                try {
                    val toDelete = cur.copies
                        .map { it.entity }
                        .filterNot { it.chatId == cur.keepChatId && it.messageId == cur.keepMessageId }
                    for (copy in toDelete) {
                        try {
                            telegramClient.deleteMessage(copy.chatId, copy.messageId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Log.w("StorageAnalyticsVM: deleteMessage failed for ${copy.chatId}/${copy.messageId}", t)
                        }
                        fileCacheDao.deleteById(copy.chatId, copy.messageId)
                    }
                    _duplicateDialog.value = null
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("StorageAnalyticsVM: confirmDeleteDuplicates failed", t)
                    _duplicateDialog.value = _duplicateDialog.value?.copy(deleting = false)
                }
            }
        }

        fun dismissDuplicateDialog() {
            _duplicateDialog.value = null
        }

        private suspend fun buildReport(): StorageAnalytics.Report {
            val myUserId =
                try {
                    telegramClient.myUserId()
                } catch (t: Throwable) {
                    Log.w("StorageAnalyticsVM: myUserId lookup failed — assuming 0", t)
                    0L
                }

            val cacheAggregates = fileCacheDao.perChatAggregates()
            val grandTotal = fileCacheDao.grandTotal()
            val duplicateAggregates = fileCacheDao.duplicateAggregates()
            val folders = folderDao.getAll()
            val backupGrandTotal = syncedFileDao.grandTotal()
            val allCachedFiles = fileCacheDao.getAll()

            return StorageAnalytics.compute(
                cacheAggregates = cacheAggregates,
                grandTotal = grandTotal,
                duplicateAggregates = duplicateAggregates,
                folders = folders,
                savedMessagesChatId = myUserId,
                allCachedFiles = allCachedFiles,
                backupGrandTotal = backupGrandTotal,
            )
        }

        data class DuplicateCopyItem(
            val entity: FileCacheEntity,
            val folderName: String,
        )

        data class DuplicateCleanupState(
            val fileName: String,
            val fileSize: Long,
            val copies: List<DuplicateCopyItem>,
            val keepChatId: Long,
            val keepMessageId: Long,
            val deleting: Boolean,
        )

        sealed class UiState {
            data object Loading : UiState()

            data class Ready(
                val report: StorageAnalytics.Report,
            ) : UiState()

            data class Error(
                val message: String,
            ) : UiState()
        }
    }
