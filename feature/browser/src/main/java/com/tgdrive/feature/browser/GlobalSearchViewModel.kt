package com.tgdrive.feature.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.dao.ZipEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
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

        fun retry() {
            search(_query.value, immediate = true)
        }
    }
