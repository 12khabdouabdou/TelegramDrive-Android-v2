package com.tgdrive.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.isTextPreviewable
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.dao.ZipEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.index.ZipIndexService
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.feature.browser.search.SearchScopeFilter
import com.tgdrive.feature.browser.search.SmartSearchEngine
import com.tgdrive.feature.browser.search.SmartSearchHit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed class GlobalSearchUiState {
    data object Idle : GlobalSearchUiState()
    data object Searching : GlobalSearchUiState()
    data class Results(val hits: List<SmartSearchHit>) : GlobalSearchUiState()
    data class Error(val message: String) : GlobalSearchUiState()
}

data class ZipIndexingProgress(
    val running: Boolean = false,
    val unindexedCount: Int = 0,
    val done: Int = 0,
    val total: Int = 0,
)

@HiltViewModel
class GlobalSearchViewModel
    @Inject
    constructor(
        private val telegramClient: TelegramClient,
        private val folderDao: FolderDao,
        private val fileCacheDao: FileCacheDao,
        private val zipEntryDao: ZipEntryDao,
        private val trashEntryDao: TrashEntryDao,
        private val zipIndexService: ZipIndexService,
        private val downloadManager: DownloadManager,
        private val mediaThumbnailCache: MediaThumbnailCache,
        private val preferences: AppPreferences,
        private val dispatchers: DispatchersProvider,
    ) : ViewModel() {
        private val _state = MutableStateFlow<GlobalSearchUiState>(GlobalSearchUiState.Idle)
        val state: StateFlow<GlobalSearchUiState> = _state.asStateFlow()

        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val _scope = MutableStateFlow(SearchScopeFilter.ALL)
        val scopeFilter: StateFlow<SearchScopeFilter> = _scope.asStateFlow()

        private val _recentQueries = MutableStateFlow<List<String>>(emptyList())
        val recentQueries: StateFlow<List<String>> = _recentQueries.asStateFlow()

        private val _folderNames = MutableStateFlow<Map<Long, String>>(emptyMap())
        val folderNames: StateFlow<Map<Long, String>> = _folderNames.asStateFlow()

        private val _zipIndexing = MutableStateFlow(ZipIndexingProgress())
        val zipIndexing: StateFlow<ZipIndexingProgress> = _zipIndexing.asStateFlow()

        @Volatile private var systemChannelId: Long? = null
        private var searchJob: Job? = null
        private val attemptedThumbKeys = HashSet<Pair<Long, Long>>()

        init {
            viewModelScope.launch(dispatchers.io) {
                try {
                    val map = mutableMapOf<Long, String>()
                    folderDao.getAll().forEach { map[it.channelId] = it.title }
                    val myId = runCatching { telegramClient.myUserId() }.getOrDefault(0L)
                    if (myId != 0L) map[myId] = "Saved Messages"
                    _folderNames.value = map
                    systemChannelId = runCatching { telegramClient.getOrCreateSystemChannel() }.getOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {}
                refreshUnindexedCount()
            }
            viewModelScope.launch(dispatchers.io) {
                preferences.data.collect { prefs ->
                    _recentQueries.value = prefs.recentSearches()
                }
            }
        }

        private suspend fun refreshUnindexedCount() {
            val sysId = systemChannelId
            val count = runCatching {
                zipIndexService.getUnindexedZips().count { it.chatId != sysId }
            }.getOrDefault(0)
            _zipIndexing.value = _zipIndexing.value.copy(unindexedCount = count)
        }

        fun indexUncachedZips() {
            if (_zipIndexing.value.running) return
            viewModelScope.launch(dispatchers.io) {
                val targets = runCatching {
                    val sysId = systemChannelId
                    zipIndexService.getUnindexedZips().filter { it.chatId != sysId }
                }.getOrDefault(emptyList())
                if (targets.isEmpty()) {
                    _zipIndexing.value = ZipIndexingProgress(running = false, unindexedCount = 0)
                    return@launch
                }
                _zipIndexing.value = ZipIndexingProgress(running = true, unindexedCount = targets.size, done = 0, total = targets.size)
                try {
                    targets.forEachIndexed { idx, zip ->
                        zipIndexService.peekRemoteZip(zip)
                        _zipIndexing.value = ZipIndexingProgress(
                            running = true,
                            unindexedCount = (targets.size - idx - 1).coerceAtLeast(0),
                            done = idx + 1,
                            total = targets.size,
                        )
                    }
                } finally {
                    _zipIndexing.value = ZipIndexingProgress(running = false, unindexedCount = 0)
                    refreshUnindexedCount()
                    if (_query.value.isNotBlank() || _scope.value != SearchScopeFilter.ALL) {
                        search(_query.value, immediate = true)
                    }
                }
            }
        }

        fun setScope(filter: SearchScopeFilter) {
            _scope.value = filter
            search(_query.value, immediate = true)
        }

        fun clearRecentSearches() {
            viewModelScope.launch(dispatchers.io) {
                preferences.update { it.clearRecentSearches() }
            }
        }

        fun search(query: String, immediate: Boolean = false) {
            _query.value = query
            searchJob?.cancel()
            val activeScope = _scope.value
            if (query.isBlank() && activeScope == SearchScopeFilter.ALL) {
                _state.value = GlobalSearchUiState.Idle
                return
            }
            searchJob = viewModelScope.launch(dispatchers.io) {
                if (!immediate) delay(220L)
                val trimmed = query.trim()
                val excludedIds = systemChannelId?.let { setOf(it) } ?: emptySet()
                val trashedKeys = runCatching {
                    trashEntryDao.getAll().mapTo(HashSet()) { it.chatId to it.messageId }
                }.getOrDefault(emptySet())

                val localFiles = runCatching { fileCacheDao.getAll() }
                    .getOrDefault(emptyList())
                    .filterNot { (it.chatId to it.messageId) in trashedKeys }
                val localZips = runCatching { zipEntryDao.getAll() }
                    .getOrDefault(emptyList())
                    .filterNot { (it.chatId to it.zipMessageId) in trashedKeys }

                val localHits = SmartSearchEngine.search(
                    query = trimmed,
                    files = localFiles,
                    zipEntries = localZips,
                    scope = activeScope,
                    excludedChatIds = excludedIds,
                )

                if (localHits.isNotEmpty() || trimmed.isBlank()) {
                    _state.value = GlobalSearchUiState.Results(localHits)
                    warmSearchThumbnails(localHits)
                } else {
                    _state.value = GlobalSearchUiState.Searching
                }

                if (trimmed.isNotEmpty() && immediate) {
                    runCatching { preferences.update { it.withRecentSearch(trimmed) } }
                }

                if (trimmed.length >= 2 && activeScope != SearchScopeFilter.IN_ZIPS) {
                    try {
                        val remoteMsgs = telegramClient.searchMessagesGlobal(trimmed, limit = 50)
                            .filterNot { it.chatId in excludedIds || (it.chatId to it.id) in trashedKeys }
                        val existingKeys = localFiles.mapTo(HashSet()) { it.chatId to it.messageId }
                        val remoteAsEntities = remoteMsgs
                            .filterNot { (it.chatId to it.id) in existingKeys }
                            .map { msg ->
                                FileCacheEntity(
                                    messageId = msg.id,
                                    chatId = msg.chatId,
                                    fileName = msg.fileName,
                                    fileSize = msg.fileSize,
                                    mimeType = msg.mimeType,
                                    thumbnailPath = msg.thumbnailPath,
                                    remoteId = msg.fileRemoteId,
                                    date = msg.date,
                                )
                            }
                        if (remoteAsEntities.isNotEmpty()) {
                            val mergedHits = SmartSearchEngine.search(
                                query = trimmed,
                                files = localFiles + remoteAsEntities,
                                zipEntries = localZips,
                                scope = activeScope,
                                excludedChatIds = excludedIds,
                            )
                            _state.value = GlobalSearchUiState.Results(mergedHits)
                            warmSearchThumbnails(mergedHits)
                        } else if (_state.value is GlobalSearchUiState.Searching) {
                            _state.value = GlobalSearchUiState.Results(localHits)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w("GlobalSearchVM: remote search failed", t)
                        if (_state.value is GlobalSearchUiState.Searching) {
                            _state.value = GlobalSearchUiState.Results(localHits)
                        }
                    }
                }
            }
        }

        private fun warmSearchThumbnails(hits: List<SmartSearchHit>) {
            val candidates = hits
                .filterIsInstance<SmartSearchHit.CloudFileHit>()
                .map { it.entity }
                .filter { mediaThumbnailCache.needsThumbnailUpgrade(it.thumbnailPath) }
                .filter { attemptedThumbKeys.add(it.chatId to it.messageId) }
                .take(20)
            for (entity in candidates) {
                viewModelScope.launch(dispatchers.io) {
                    try {
                        val path = mediaThumbnailCache.ensureThumbnail(entity) ?: return@launch
                        val cur = _state.value as? GlobalSearchUiState.Results ?: return@launch
                        _state.value = GlobalSearchUiState.Results(
                            cur.hits.map { hit ->
                                if (hit is SmartSearchHit.CloudFileHit &&
                                    hit.entity.chatId == entity.chatId &&
                                    hit.entity.messageId == entity.messageId
                                ) {
                                    hit.copy(entity = hit.entity.copy(thumbnailPath = path))
                                } else {
                                    hit
                                }
                            },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        fun openCloudFile(
            file: FileCacheEntity,
            folderTitle: String,
            onOpenVideo: (Long, Long) -> Unit,
            onOpenImage: (Long, Long) -> Unit,
            onOpenPdf: (Long) -> Unit,
            onOpenZip: (Long) -> Unit,
            onOpenText: (Long, Long) -> Unit,
            onOpenFolder: (Long, String) -> Unit,
        ) {
            viewModelScope.launch(dispatchers.io) {
                runCatching { fileCacheDao.updateLastAccessed(file.chatId, file.messageId, System.currentTimeMillis()) }
            }
            val bucket = classifyFile(file.mimeType, file.fileName)
            val needsDownload = run {
                val item = downloadManager.findItem(file.chatId, file.messageId)
                    ?.takeIf { it.status == DownloadStatus.DONE }
                val path = item?.localPath
                path == null || !File(path).exists()
            }
            fun enqueueIfNeeded() {
                if (needsDownload && file.remoteId.isNotEmpty()) {
                    downloadManager.enqueue(
                        DownloadItem(
                            id = file.messageId,
                            chatId = file.chatId,
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
                bucket == FileBucket.VIDEOS -> onOpenVideo(file.chatId, file.messageId)
                bucket == FileBucket.IMAGES -> {
                    enqueueIfNeeded()
                    onOpenImage(file.chatId, file.messageId)
                }
                bucket == FileBucket.PDF -> {
                    enqueueIfNeeded()
                    onOpenPdf(file.messageId)
                }
                isTextPreviewable(file.mimeType, file.fileName) -> {
                    enqueueIfNeeded()
                    onOpenText(file.chatId, file.messageId)
                }
                else -> onOpenFolder(file.chatId, folderTitle)
            }
        }

        fun retry() {
            search(_query.value, immediate = true)
        }
    }
