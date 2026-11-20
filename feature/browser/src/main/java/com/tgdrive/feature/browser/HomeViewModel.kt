package com.tgdrive.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.FileFilter
import com.tgdrive.core.datastore.FileViewMode
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.FolderEntity
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.telegram.TelegramClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class HomeUiState(
    val folders: List<FolderEntity> = emptyList(),
    val savedMessagesChatId: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
    val viewMode: FileViewMode = FileViewMode.GRID,
    val folderSizes: Map<Long, Long> = emptyMap(),
    val totalBytes: Long = 0L,
    val totalFiles: Int = 0,
    val categoryCounts: Map<FileFilter, Int> = emptyMap(),
    val categoryTargets: Map<FileFilter, Pair<Long, String>> = emptyMap(),
    val recentFiles: List<FileCacheEntity> = emptyList(),
    val starredFiles: List<FileCacheEntity> = emptyList(),
)

private data class HomeDashboardStats(
    val folderSizes: Map<Long, Long> = emptyMap(),
    val totalBytes: Long = 0L,
    val totalFiles: Int = 0,
    val categoryCounts: Map<FileFilter, Int> = emptyMap(),
    val categoryTargets: Map<FileFilter, Pair<Long, String>> = emptyMap(),
    val recentFiles: List<FileCacheEntity> = emptyList(),
    val starredFiles: List<FileCacheEntity> = emptyList(),
)

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val telegramClient: TelegramClient,
        private val folderDao: FolderDao,
        private val fileCacheDao: FileCacheDao,
        private val downloadManager: DownloadManager,
        private val preferences: AppPreferences,
        private val dispatchers: DispatchersProvider,
    ) : ViewModel() {
        private val _loading = MutableStateFlow(false)
        private val _error = MutableStateFlow<String?>(null)
        private val _savedChatId = MutableStateFlow(0L)
        private val _dashboard = MutableStateFlow(HomeDashboardStats())

        val state: StateFlow<HomeUiState> =
            combine(
                combine(folderDao.observeAll(), preferences.data) { folders, prefs -> folders to prefs },
                combine(_loading, _error, _savedChatId, _dashboard) { loading, error, savedId, dash ->
                    Triple(loading to error, savedId, dash)
                },
            ) { (folders, prefs), (loadErr, savedId, dash) ->
                HomeUiState(
                    folders = folders,
                    savedMessagesChatId = savedId,
                    loading = loadErr.first,
                    error = loadErr.second,
                    viewMode = prefs.fileViewMode,
                    folderSizes = dash.folderSizes,
                    totalBytes = dash.totalBytes,
                    totalFiles = dash.totalFiles,
                    categoryCounts = dash.categoryCounts,
                    categoryTargets = dash.categoryTargets,
                    recentFiles = dash.recentFiles,
                    starredFiles = dash.starredFiles,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

        init {
            refreshSavedMessagesId()
            refreshDashboard()
            refreshFolders()
        }

        fun getFolderSize(chatId: Long): Long = state.value.folderSizes[chatId] ?: 0L

        fun clearError() {
            _error.value = null
        }

        fun refreshDashboard() {
            viewModelScope.launch(dispatchers.io) {
                try {
                    val aggregates = fileCacheDao.perChatAggregates()
                    val folderSizes = aggregates.associate { it.chatId to it.totalBytes }
                    val grand = fileCacheDao.grandTotal()
                    val recent = fileCacheDao.getRecent()
                    val starred = fileCacheDao.getStarred()
                    val foldersByChat = folderDao.getAll().associateBy { it.channelId }
                    val savedId = _savedChatId.value

                    val counts = mutableMapOf<FileFilter, Int>()
                    val perChatFilterCounts = mutableMapOf<FileFilter, MutableMap<Long, Int>>()
                    for (chatId in fileCacheDao.getAllChatIds()) {
                        for (file in fileCacheDao.forChat(chatId)) {
                            val filter = when (classifyFile(file.mimeType, file.fileName)) {
                                FileBucket.IMAGES -> FileFilter.IMAGES
                                FileBucket.VIDEOS -> FileFilter.VIDEOS
                                FileBucket.AUDIO -> FileFilter.AUDIO
                                FileBucket.PDF, FileBucket.DOCUMENTS -> FileFilter.DOCUMENTS
                                FileBucket.ARCHIVES -> FileFilter.ARCHIVES
                                FileBucket.OTHER -> continue
                            }
                            counts[filter] = (counts[filter] ?: 0) + 1
                            val map = perChatFilterCounts.getOrPut(filter) { mutableMapOf() }
                            map[chatId] = (map[chatId] ?: 0) + 1
                        }
                    }

                    val targets = perChatFilterCounts.mapValues { (_, byChat) ->
                        val topChatId = byChat.maxByOrNull { it.value }?.key ?: savedId
                        val title = foldersByChat[topChatId]?.title ?: "Saved Messages"
                        topChatId to title
                    }

                    _dashboard.value = HomeDashboardStats(
                        folderSizes = folderSizes,
                        totalBytes = grand.totalBytes,
                        totalFiles = grand.fileCount,
                        categoryCounts = counts,
                        categoryTargets = targets,
                        recentFiles = recent,
                        starredFiles = starred,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("refreshDashboard failed", t)
                }
            }
        }

        private fun refreshSavedMessagesId() {
            viewModelScope.launch(dispatchers.io) {
                try {
                    _savedChatId.value = telegramClient.myUserId()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("Could not fetch Saved Messages id", t)
                }
            }
        }

        fun refreshFolders() {
            viewModelScope.launch(dispatchers.io) {
                _loading.value = true
                try {
                    if (_savedChatId.value == 0L) {
                        runCatching { telegramClient.myUserId() }.onSuccess { _savedChatId.value = it }
                    }
                    val remote = telegramClient.listFolders()
                    val remoteChannelIds = remote.filter { !it.isSelf }.map { it.chatId }.toSet()

                    val all = folderDao.getAll()
                    val existing = all.associateBy { it.channelId }
                    remote.filter { !it.isSelf }.forEach { f ->
                        val local = existing[f.chatId]
                        if (local == null) {
                            folderDao.insert(
                                FolderEntity(
                                    channelId = f.chatId,
                                    title = f.title,
                                    createdAt = f.createdAtMs,
                                ),
                            )
                        } else if (local.title != f.title) {
                            folderDao.update(local.copy(title = f.title))
                        }
                    }

                    if (remoteChannelIds.isNotEmpty()) {
                        val toDelete = existing.keys - remoteChannelIds
                        for (channelId in toDelete) {
                            val localFolder = existing[channelId]
                            if (localFolder != null) {
                                folderDao.deleteById(localFolder.id)
                            }
                        }
                    }

                    _error.value = null
                    refreshDashboard()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    _error.value = t.message ?: "Failed to load folders"
                } finally {
                    _loading.value = false
                }
            }
        }

        fun openOrPreviewFile(
            file: FileCacheEntity,
            onOpenVideo: (Long) -> Unit,
            onOpenImage: (String) -> Unit,
            onOpenPdf: (Long) -> Unit,
            onOpenZip: (Long) -> Unit,
            onOpenFolder: (Long, String) -> Unit,
        ) {
            viewModelScope.launch(dispatchers.io) {
                try {
                    fileCacheDao.updateLastAccessed(file.chatId, file.messageId, System.currentTimeMillis())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {}
            }
            val bucket = classifyFile(file.mimeType, file.fileName)
            val needsDownload = run {
                val item = downloadManager.state.value.items
                    .find { it.id == file.messageId && it.status == DownloadStatus.DONE }
                val path = item?.localPath
                path == null || !File(path).exists()
            }
            fun enqueueIfNeeded() {
                if (needsDownload && file.remoteId.isNotEmpty()) {
                    downloadManager.enqueue(
                        DownloadItem(
                            id = file.messageId,
                            remoteId = file.remoteId,
                            fileName = file.fileName,
                            totalBytes = file.fileSize,
                        ),
                    )
                }
            }
            when {
                bucket == FileBucket.ARCHIVES && file.fileName.lowercase().endsWith(".zip") -> {
                    enqueueIfNeeded()
                    onOpenZip(file.messageId)
                }
                bucket == FileBucket.VIDEOS -> {
                    enqueueIfNeeded()
                    onOpenVideo(file.messageId)
                }
                bucket == FileBucket.IMAGES -> {
                    enqueueIfNeeded()
                    onOpenImage(file.messageId.toString())
                }
                bucket == FileBucket.PDF -> {
                    enqueueIfNeeded()
                    onOpenPdf(file.messageId)
                }
                else -> {
                    val title = state.value.folders.find { it.channelId == file.chatId }?.title ?: "Saved Messages"
                    onOpenFolder(file.chatId, title)
                }
            }
        }

        fun setViewMode(mode: FileViewMode) {
            viewModelScope.launch(dispatchers.io) {
                preferences.update { it.copy(fileViewMode = mode) }
            }
        }

        fun deleteFolder(folder: FolderEntity) {
            viewModelScope.launch(dispatchers.io) {
                try {
                    telegramClient.deleteFolder(folder.channelId)
                    folderDao.delete(folder)
                    fileCacheDao.deleteForChat(folder.channelId)
                    Log.i("HomeViewModel: deleted folder '${folder.title}'")
                    refreshDashboard()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("HomeViewModel: deleteFolder failed", t)
                    _error.value = t.message ?: "Failed to delete folder"
                }
            }
        }

        fun renameFolder(
            folder: FolderEntity,
            newTitle: String,
        ) {
            val trimmed = newTitle.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch(dispatchers.io) {
                try {
                    telegramClient.renameFolder(folder.channelId, trimmed)
                    folderDao.update(folder.copy(title = trimmed))
                    Log.i("HomeViewModel: renamed folder to '$trimmed'")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("HomeViewModel: renameFolder failed", t)
                    _error.value = t.message ?: "Failed to rename folder"
                }
            }
        }

        fun setFolderColor(
            folder: FolderEntity,
            color: Int,
        ) {
            viewModelScope.launch(dispatchers.io) {
                try {
                    folderDao.updateColor(folder.id, color)
                    Log.i("HomeViewModel: set folder color to $color")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("HomeViewModel: setFolderColor failed", t)
                }
            }
        }

        suspend fun getStarredFiles(): List<FileCacheEntity> =
            try {
                fileCacheDao.getStarred()
            } catch (_: Throwable) {
                emptyList()
            }

        suspend fun getRecentFiles(): List<FileCacheEntity> =
            try {
                fileCacheDao.getRecent()
            } catch (_: Throwable) {
                emptyList()
            }
    }
