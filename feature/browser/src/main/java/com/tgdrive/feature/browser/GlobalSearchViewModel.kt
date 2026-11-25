package com.tgdrive.feature.browser

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.audio.AudioPlayerController
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
import com.tgdrive.core.db.entity.ZipEntryEntity
import com.tgdrive.core.download.DecryptOutcome
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.UploadManager
import com.tgdrive.core.download.ai.MediaAiIndexer
import com.tgdrive.core.download.index.ZipIndexService
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.feature.browser.search.SearchFilterCriteria
import com.tgdrive.feature.browser.search.SearchScopeFilter
import com.tgdrive.feature.browser.search.SearchSortOrder
import com.tgdrive.feature.browser.search.SmartSearchEngine
import com.tgdrive.feature.browser.search.SmartSearchHit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

data class AiIndexingProgress(
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
        private val mediaAiIndexer: MediaAiIndexer,
        private val downloadManager: DownloadManager,
        private val offlinePinStore: OfflinePinStore,
        private val uploadManager: UploadManager,
        private val backupCrypto: BackupCrypto,
        private val audioPlayerController: AudioPlayerController,
        private val mediaThumbnailCache: MediaThumbnailCache,
        private val preferences: AppPreferences,
        private val dispatchers: DispatchersProvider,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {

        private val _state = MutableStateFlow<GlobalSearchUiState>(GlobalSearchUiState.Idle)
        val state: StateFlow<GlobalSearchUiState> = _state.asStateFlow()

        private val _query = MutableStateFlow("")
        val query: StateFlow<String> = _query.asStateFlow()

        private val _scope = MutableStateFlow(SearchScopeFilter.ALL)
        val scopeFilter: StateFlow<SearchScopeFilter> = _scope.asStateFlow()

        private val _filterCriteria = MutableStateFlow(SearchFilterCriteria())
        val filterCriteria: StateFlow<SearchFilterCriteria> = _filterCriteria.asStateFlow()

        private val _recentQueries = MutableStateFlow<List<String>>(emptyList())
        val recentQueries: StateFlow<List<String>> = _recentQueries.asStateFlow()

        private val _folderNames = MutableStateFlow<Map<Long, String>>(emptyMap())
        val folderNames: StateFlow<Map<Long, String>> = _folderNames.asStateFlow()

        private val _zipIndexing = MutableStateFlow(ZipIndexingProgress())
        val zipIndexing: StateFlow<ZipIndexingProgress> = _zipIndexing.asStateFlow()

        private val _aiIndexing = MutableStateFlow(AiIndexingProgress())
        val aiIndexing: StateFlow<AiIndexingProgress> = _aiIndexing.asStateFlow()

        private val _decryptDialog = MutableStateFlow<DecryptDialogState?>(null)
        val decryptDialog: StateFlow<DecryptDialogState?> = _decryptDialog.asStateFlow()

        private val _hasSavedBackupPassphrase = MutableStateFlow(false)
        val hasSavedBackupPassphrase: StateFlow<Boolean> = _hasSavedBackupPassphrase.asStateFlow()

        private val _decryptedOpenEvent = MutableStateFlow<DecryptedOpenEvent?>(null)
        val decryptedOpenEvent: StateFlow<DecryptedOpenEvent?> = _decryptedOpenEvent.asStateFlow()

        private val _snackbar = MutableStateFlow<String?>(null)
        val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

        @Volatile private var systemChannelId: Long? = null
        @Volatile private var cachedFiles: List<FileCacheEntity> = emptyList()
        @Volatile private var cachedZipEntries: List<ZipEntryEntity> = emptyList()
        @Volatile private var cachedTrashedKeys: Set<Pair<Long, Long>> = emptySet()
        @Volatile private var snapshotTimestampMs: Long = 0L

        private var searchJob: Job? = null
        private var audioWaitJob: Job? = null
        private var decryptWaitJob: Job? = null
        private val attemptedThumbKeys = HashSet<Pair<Long, Long>>()

        init {
            viewModelScope.launch(dispatchers.io) {
                try {
                    val map = mutableMapOf<Long, String>()
                    folderDao.getAll().forEach { map[it.channelId] = it.title }
                    val myId = runCatching { telegramClient.myUserId() }.getOrDefault(0L)
                    if (myId != 0L) map[myId] = "Saved Messages"
                    _folderNames.value = map
                    _filterCriteria.update { it.copy(folderNames = map) }
                    systemChannelId = runCatching { telegramClient.getOrCreateSystemChannel() }.getOrNull()
                    _hasSavedBackupPassphrase.value = runCatching {
                        backupCrypto.hasCustomBackupPassphrase()
                    }.getOrDefault(false)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {}
                refreshLocalSnapshot()
                refreshUnindexedCount()
                refreshUnindexedAiCount()
            }
            viewModelScope.launch(dispatchers.io) {
                preferences.data.collect { prefs ->
                    _recentQueries.value = prefs.recentSearches()
                }
            }
        }

        private suspend fun refreshLocalSnapshot() {
            val trashedKeys = runCatching {
                trashEntryDao.getAll().mapTo(HashSet()) { it.chatId to it.messageId }
            }.getOrDefault(emptySet())
            cachedTrashedKeys = trashedKeys
            cachedFiles = runCatching { fileCacheDao.getAll() }
                .getOrDefault(emptyList())
                .filterNot { (it.chatId to it.messageId) in trashedKeys }
            cachedZipEntries = runCatching { zipEntryDao.getAll() }
                .getOrDefault(emptyList())
                .filterNot { (it.chatId to it.zipMessageId) in trashedKeys }
            snapshotTimestampMs = System.currentTimeMillis()
        }

        private suspend fun ensureFreshSnapshot() {
            val now = System.currentTimeMillis()
            if (snapshotTimestampMs == 0L || now - snapshotTimestampMs > 15_000L) {
                refreshLocalSnapshot()
            }
        }

        private suspend fun refreshUnindexedCount() {
            val sysId = systemChannelId
            val count = runCatching {
                zipIndexService.getUnindexedZips().count { it.chatId != sysId }
            }.getOrDefault(0)
            _zipIndexing.update { it.copy(unindexedCount = count) }
        }

        private suspend fun refreshUnindexedAiCount() {
            val excluded = systemChannelId?.let { setOf(it) } ?: emptySet()
            val count = runCatching { mediaAiIndexer.countUnindexed(excluded) }.getOrDefault(0)
            _aiIndexing.update { it.copy(unindexedCount = count) }
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
                _zipIndexing.value = ZipIndexingProgress(
                    running = true,
                    unindexedCount = targets.size,
                    done = 0,
                    total = targets.size,
                )
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
                    refreshLocalSnapshot()
                    refreshUnindexedCount()
                    if (_query.value.isNotBlank() || _scope.value != SearchScopeFilter.ALL || _filterCriteria.value.hasUiFilters) {
                        search(_query.value, immediate = true)
                    }
                }
            }
        }

        fun indexMediaAi() {
            if (_aiIndexing.value.running) return
            viewModelScope.launch(dispatchers.io) {
                val excluded = systemChannelId?.let { setOf(it) } ?: emptySet()
                val pending = runCatching { mediaAiIndexer.countUnindexed(excluded) }.getOrDefault(0)
                if (pending == 0) {
                    _aiIndexing.value = AiIndexingProgress(running = false, unindexedCount = 0)
                    return@launch
                }
                _aiIndexing.value = AiIndexingProgress(running = true, unindexedCount = pending, done = 0, total = pending.coerceAtMost(30))
                try {
                    runCatching { mediaAiIndexer.scanLocalExifLocations(excluded) }
                    mediaAiIndexer.indexUnindexedBatch(
                        excludedChatIds = excluded,
                        maxCount = 30,
                    ) { done, total ->
                        _aiIndexing.value = AiIndexingProgress(
                            running = true,
                            unindexedCount = (pending - done).coerceAtLeast(0),
                            done = done,
                            total = total,
                        )
                    }
                } finally {
                    _aiIndexing.value = AiIndexingProgress(running = false, unindexedCount = 0)
                    refreshLocalSnapshot()
                    refreshUnindexedAiCount()
                    if (_query.value.isNotBlank() || _scope.value != SearchScopeFilter.ALL || _filterCriteria.value.hasUiFilters) {
                        search(_query.value, immediate = true)
                    }
                }
            }
        }

        fun setScope(filter: SearchScopeFilter) {
            _scope.value = filter
            search(_query.value, immediate = true)
        }

        fun setFolderFilter(chatId: Long?) {
            _filterCriteria.update { it.copy(folderChatId = chatId) }
            search(_query.value, immediate = true)
        }

        fun setSortOrder(order: SearchSortOrder) {
            _filterCriteria.update { it.copy(sortOrder = order) }
            search(_query.value, immediate = true)
        }

        fun setMinSizePreset(minSizeBytes: Long?) {
            _filterCriteria.update { it.copy(minSizeBytes = minSizeBytes) }
            search(_query.value, immediate = true)
        }

        fun setAfterDatePreset(afterEpochMs: Long?) {
            _filterCriteria.update { it.copy(afterEpochMs = afterEpochMs) }
            search(_query.value, immediate = true)
        }

        fun clearAdvancedFilters() {
            _filterCriteria.update {
                SearchFilterCriteria(folderNames = it.folderNames)
            }
            search(_query.value, immediate = true)
        }

        fun removeRecentSearch(query: String) {
            viewModelScope.launch(dispatchers.io) {
                preferences.update { it.withoutRecentSearch(query) }
            }
        }

        fun clearRecentSearches() {
            viewModelScope.launch(dispatchers.io) {
                preferences.update { it.clearRecentSearches() }
            }
        }

        private fun persistRecentIfNonBlank(q: String) {
            val cleaned = q.trim()
            if (cleaned.isEmpty()) return
            viewModelScope.launch(dispatchers.io) {
                runCatching { preferences.update { it.withRecentSearch(cleaned) } }
            }
        }

        fun search(query: String, immediate: Boolean = false) {
            _query.value = query
            searchJob?.cancel()
            val activeScope = _scope.value
            val activeCriteria = _filterCriteria.value
            val parsed = SmartSearchEngine.parseQuery(query)
            if (query.isBlank() && activeScope == SearchScopeFilter.ALL && !activeCriteria.hasUiFilters) {
                _state.value = GlobalSearchUiState.Idle
                return
            }
            searchJob = viewModelScope.launch(dispatchers.io) {
                if (!immediate) delay(220L)
                ensureFreshSnapshot()
                val trimmed = query.trim()
                val excludedIds = systemChannelId?.let { setOf(it) } ?: emptySet()
                val localFiles = cachedFiles
                val localZips = cachedZipEntries
                val trashedKeys = cachedTrashedKeys

                val localHits = SmartSearchEngine.search(
                    query = trimmed,
                    files = localFiles,
                    zipEntries = localZips,
                    scope = activeScope,
                    excludedChatIds = excludedIds,
                    criteria = activeCriteria,
                )

                val remoteText = parsed.textQuery
                if (localHits.isNotEmpty() || remoteText.length < 2) {
                    _state.value = GlobalSearchUiState.Results(localHits)
                    warmSearchThumbnails(localHits)
                } else {
                    _state.value = GlobalSearchUiState.Searching
                }

                if (trimmed.isNotEmpty() && immediate) {
                    runCatching { preferences.update { it.withRecentSearch(trimmed) } }
                }

                if (remoteText.length >= 2 && activeScope != SearchScopeFilter.IN_ZIPS) {
                    try {
                        val remoteMsgs = telegramClient.searchMessagesGlobal(remoteText, limit = 50)
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
                                criteria = activeCriteria,
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
                    } catch (_: Throwable) {}
                }
            }
        }

        private fun resolveLocalPath(file: FileCacheEntity): String? {
            offlinePinStore.getPinnedFilePathSync(file.chatId, file.messageId)?.let { pinned ->
                if (File(pinned).isFile) return pinned
            }
            val item = downloadManager.findItem(file.chatId, file.messageId)
                ?.takeIf { it.status == DownloadStatus.DONE }
            return item?.localPath?.takeIf { File(it).isFile }
        }

        fun openCloudFile(
            file: FileCacheEntity,
            folderTitle: String,
            onOpenVideo: (Long, Long) -> Unit,
            onOpenImage: (Long, Long) -> Unit,
            onOpenPdf: (Long, Long) -> Unit,
            onOpenZip: (Long) -> Unit,
            onOpenText: (Long, Long) -> Unit,
            onOpenFolder: (Long, String) -> Unit,
        ) {
            persistRecentIfNonBlank(_query.value)
            viewModelScope.launch(dispatchers.io) {
                runCatching { fileCacheDao.updateLastAccessed(file.chatId, file.messageId, System.currentTimeMillis()) }
            }

            if (BackupCrypto.isEncryptedFileName(file.fileName)) {
                startDecryptFlow(file)
                return
            }

            val localPath = resolveLocalPath(file)
            fun enqueueIfNeeded() {
                if (localPath == null && file.remoteId.isNotEmpty()) {
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

            val bucket = classifyFile(file.mimeType, file.fileName)
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
                bucket == FileBucket.AUDIO -> downloadAndPlayAudio(file, localPath)
                bucket == FileBucket.PDF -> {
                    enqueueIfNeeded()
                    onOpenPdf(file.chatId, file.messageId)
                }
                isTextPreviewable(file.mimeType, file.fileName) -> {
                    enqueueIfNeeded()
                    onOpenText(file.chatId, file.messageId)
                }
                else -> onOpenFolder(file.chatId, folderTitle)
            }
        }

        fun onZipMemberClicked() {
            persistRecentIfNonBlank(_query.value)
        }

        private fun downloadAndPlayAudio(item: FileCacheEntity, existingLocalPath: String?) {
            if (existingLocalPath != null) {
                audioPlayerController.playAudio(existingLocalPath, item.fileName)
                return
            }
            if (item.remoteId.isEmpty()) {
                _snackbar.value = "File has no remote ID"
                return
            }
            downloadManager.enqueue(
                DownloadItem(
                    id = item.messageId,
                    chatId = item.chatId,
                    remoteId = item.remoteId,
                    fileName = item.fileName,
                    totalBytes = item.fileSize,
                ),
            )
            audioWaitJob?.cancel()
            audioWaitJob = viewModelScope.launch(dispatchers.io) {
                val terminal = downloadManager.state
                    .map { st ->
                        st.items.find { it.id == item.messageId && (it.chatId == 0L || it.chatId == item.chatId) }
                    }
                    .first { dl ->
                        dl == null ||
                            dl.status == DownloadStatus.DONE ||
                            dl.status == DownloadStatus.FAILED ||
                            dl.status == DownloadStatus.PAUSED ||
                            dl.status == DownloadStatus.CANCELLED
                    }
                if (terminal?.status == DownloadStatus.DONE) {
                    val path = terminal.localPath
                    if (path != null && File(path).exists()) {
                        withContext(dispatchers.main) {
                            audioPlayerController.playAudio(path, item.fileName)
                        }
                    }
                } else if (terminal?.status == DownloadStatus.FAILED) {
                    _snackbar.value = terminal.errorMessage ?: "Audio download failed"
                } else if (terminal?.status == DownloadStatus.PAUSED) {
                    _snackbar.value = terminal.errorMessage ?: "Download paused (waiting for Wi-Fi)"
                }
            }
        }

        private fun startDecryptFlow(item: FileCacheEntity) {
            decryptWaitJob?.cancel()
            val localFile = resolveLocalPath(item)?.let(::File)
            if (localFile != null) {
                val fmt = uploadManager.detectEncryptedFormat(localFile)
                _decryptDialog.value = DecryptDialogState(
                    item = item,
                    localFile = localFile,
                    format = fmt,
                )
                return
            }
            if (item.remoteId.isBlank()) {
                _snackbar.value = "File has no remote ID"
                return
            }
            _decryptDialog.value = DecryptDialogState(item = item, downloading = true)
            downloadManager.enqueue(
                DownloadItem(
                    id = item.messageId,
                    chatId = item.chatId,
                    remoteId = item.remoteId,
                    fileName = item.fileName,
                    totalBytes = item.fileSize,
                ),
            )
            decryptWaitJob = viewModelScope.launch(dispatchers.io) {
                val terminal = downloadManager.state
                    .map { st ->
                        st.items.find { it.id == item.messageId && (it.chatId == 0L || it.chatId == item.chatId) }
                    }
                    .first { dl ->
                        dl == null ||
                            dl.status == DownloadStatus.DONE ||
                            dl.status == DownloadStatus.FAILED ||
                            dl.status == DownloadStatus.PAUSED ||
                            dl.status == DownloadStatus.CANCELLED
                    }
                when (terminal?.status) {
                    DownloadStatus.DONE -> {
                        val f = terminal.localPath?.let(::File)?.takeIf { it.isFile }
                        if (f != null) {
                            val fmt = uploadManager.detectEncryptedFormat(f)
                            _decryptDialog.update { cur ->
                                cur?.copy(localFile = f, format = fmt, downloading = false, error = null)
                            }
                        } else {
                            _decryptDialog.update { cur ->
                                cur?.copy(downloading = false, error = "Downloaded file could not be opened")
                            }
                        }
                    }
                    DownloadStatus.FAILED -> {
                        _decryptDialog.update { cur ->
                            cur?.copy(downloading = false, error = terminal.errorMessage ?: "Download failed")
                        }
                    }
                    DownloadStatus.PAUSED -> {
                        _decryptDialog.update { cur ->
                            cur?.copy(
                                downloading = false,
                                error = terminal.errorMessage ?: "Download paused (waiting for Wi-Fi)",
                            )
                        }
                    }
                    else -> _decryptDialog.value = null
                }
            }
        }

        fun decryptTarget(passphrase: String?, saveToDownloads: Boolean) {
            val current = _decryptDialog.value ?: return
            val localFile = current.localFile
            if (localFile == null || !localFile.isFile) {
                _decryptDialog.update { it?.copy(error = "Waiting for download to complete…") }
                return
            }
            viewModelScope.launch(dispatchers.io) {
                _decryptDialog.update { it?.copy(decrypting = true, error = null) }
                when (
                    val outcome = uploadManager.decryptLocalFile(
                        encryptedFile = localFile,
                        originalDisplayName = current.item.fileName,
                        passphrase = passphrase,
                        saveToDownloads = saveToDownloads,
                        chatId = current.item.chatId,
                        messageId = current.item.messageId,
                    )
                ) {
                    is DecryptOutcome.Success -> {
                        _decryptDialog.value = null
                        if (saveToDownloads) {
                            _snackbar.value = "Saved decrypted file: ${outcome.displayName}"
                        } else {
                            _decryptedOpenEvent.value = DecryptedOpenEvent(System.nanoTime(), outcome)
                        }
                    }
                    is DecryptOutcome.WrongPassphrase -> {
                        _decryptDialog.update { it?.copy(decrypting = false, error = outcome.message) }
                    }
                    is DecryptOutcome.Failed -> {
                        _decryptDialog.update { it?.copy(decrypting = false, error = outcome.message) }
                    }
                }
            }
        }

        fun dismissDecryptDialog() {
            decryptWaitJob?.cancel()
            _decryptDialog.value = null
        }

        fun clearDecryptedOpenEvent() {
            _decryptedOpenEvent.value = null
        }

        fun clearSnackbar() {
            _snackbar.value = null
        }

        fun showMessage(msg: String) {
            _snackbar.value = msg
        }

        fun retry() {
            search(_query.value, immediate = true)
        }
    }
