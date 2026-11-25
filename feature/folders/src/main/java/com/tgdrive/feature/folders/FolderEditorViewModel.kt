package com.tgdrive.feature.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.dao.ZipEntryDao
import com.tgdrive.core.db.entity.FolderEntity
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.telegram.TelegramClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manages folder CRUD operations:
 * - Create: creates a private Telegram channel; stores channel_id in Room DB
 * - Rename: updates channel title via API
 * - Delete: confirms with destructive dialog; leaves/deletes channel, removes from DB
 *
 * Per spec F-07.
 */
data class FolderEditorState(
    val title: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

@HiltViewModel
class FolderEditorViewModel @Inject constructor(
    private val telegramClient: TelegramClient,
    private val folderDao: FolderDao,
    private val fileCacheDao: FileCacheDao,
    private val zipEntryDao: ZipEntryDao,
    private val trashEntryDao: TrashEntryDao,
    private val offlinePinStore: OfflinePinStore,
    private val dispatchers: DispatchersProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(FolderEditorState())
    val state: StateFlow<FolderEditorState> = _state.asStateFlow()

    fun setTitle(v: String) = _state.update { it.copy(title = v, error = null) }

    fun create() {
        val title = _state.value.title.trim()
        if (title.isEmpty()) {
            _state.update { it.copy(error = "Title required") }
            return
        }
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(loading = true) }
            try {
                val folder = telegramClient.createFolder(title)
                folderDao.insert(
                    FolderEntity(
                        channelId = folder.chatId,
                        title = folder.title,
                        createdAt = folder.createdAtMs,
                    )
                )
                _state.update { it.copy(loading = false, saved = true) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: "Failed") }
            }
        }
    }

    fun rename(folder: FolderEntity, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(loading = true) }
            try {
                telegramClient.renameFolder(folder.channelId, newTitle)
                folderDao.update(folder.copy(title = newTitle))
                _state.update { it.copy(loading = false, saved = true) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: "Failed") }
            }
        }
    }

    fun delete(folder: FolderEntity) {
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(loading = true) }
            try {
                telegramClient.deleteFolder(folder.channelId)
                folderDao.delete(folder)
                runCatching { fileCacheDao.deleteForChat(folder.channelId) }
                runCatching { zipEntryDao.deleteForChat(folder.channelId) }
                runCatching { trashEntryDao.deleteForChat(folder.channelId) }
                runCatching { offlinePinStore.unpinChat(folder.channelId) }
                _state.update { it.copy(loading = false, saved = true) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (t: Throwable) {
                _state.update { it.copy(loading = false, error = t.message ?: "Failed") }
            }
        }
    }

    fun reset() {
        _state.value = FolderEditorState()
    }
}
