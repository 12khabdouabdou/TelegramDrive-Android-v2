package com.tgdrive.feature.browser

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.PublicDownloadsExporter
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.FileFilter
import com.tgdrive.core.datastore.FileSort
import com.tgdrive.core.datastore.FileViewMode
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.TrashEntryEntity
import com.tgdrive.core.download.DecryptOutcome
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.NormalUploadOptions
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.UploadEncryptionSpec
import com.tgdrive.core.download.UploadItem
import com.tgdrive.core.download.UploadManager
import com.tgdrive.core.download.UploadTracker
import com.tgdrive.core.download.ZipUploadRequest
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.EncryptedFileFormat
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.telegram.TelegramFolder
import com.tgdrive.core.telegram.TelegramMessage
import com.tgdrive.core.zip.ZipExcludeMatcher
import com.tgdrive.core.zip.ZipSelectionSummary
import com.tgdrive.core.zip.ZipUploader
import com.tgdrive.feature.upload.FileReviewAction
import com.tgdrive.feature.upload.ZipUploadOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private const val MAX_FOLDER_DECRYPTED_THUMBS = 60

private val THUMBNAILABLE_EXTS =
    setOf(
        "jpg",
        "jpeg",
        "png",
        "gif",
        "webp",
        "heic",
        "bmp",
        "heif",
        "avif",
        "mp4",
        "m4v",
        "mkv",
        "mov",
        "webm",
        "avi",
        "3gp",
        "pdf",
    )

data class DecryptDialogState(
    val item: FileCacheEntity,
    val localFile: File? = null,
    val format: EncryptedFileFormat? = null,
    val downloading: Boolean = false,
    val decrypting: Boolean = false,
    val error: String? = null,
)

data class DecryptedOpenEvent(
    val id: Long,
    val outcome: DecryptOutcome.Success,
)

data class FolderUiState(
    val items: List<FileCacheEntity> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val search: String = "",
    val viewMode: FileViewMode = FileViewMode.GRID,
    val fileFilter: FileFilter = FileFilter.ALL,
    val fileSort: FileSort = FileSort.DATE_DESC,
    val uploads: List<UploadItem> = emptyList(),
    val pendingReviewFiles: List<com.tgdrive.core.download.UploadFileItem> = emptyList(),
    val pendingZipTreeUri: Uri? = null,
    val pendingZipFolderName: String? = null,
    val zipPreviewSummary: ZipSelectionSummary? = null,
    val zipPreviewLoading: Boolean = false,
    val decryptDialog: DecryptDialogState? = null,
    val hasSavedBackupPassphrase: Boolean = false,
    val manualUploadWifiOnly: Boolean = false,
    val batchDecryptProgress: Pair<Int, Int>? = null,
    val currentVirtualPath: String = "",
    val childVirtualFolders: List<Pair<String, Int>> = emptyList(),
    val childVirtualFolderThumbs: Map<String, List<String>> = emptyMap(),
    val allVirtualPaths: List<String> = emptyList(),
    val encryptedPreviewFingerprint: String? = null,
)

data class FolderSnackbarEvent(
    val id: Long,
    val message: String,
    val canUndoTrash: Boolean = false,
)

@HiltViewModel
class FolderViewModel
    @Inject
    constructor(
        private val telegramClient: TelegramClient,
        private val fileCacheDao: FileCacheDao,
        private val preferences: AppPreferences,
        private val dispatchers: DispatchersProvider,
        val downloadManager: DownloadManager,
        val audioPlayerController: com.tgdrive.core.audio.AudioPlayerController,
        private val uploadTracker: UploadTracker,
        private val uploadManager: UploadManager,
        private val backupCrypto: BackupCrypto,
        private val offlinePinStore: OfflinePinStore,
        private val trashEntryDao: TrashEntryDao,
        private val cloudSyncManager: CloudSyncManager,
        private val mediaThumbnailCache: MediaThumbnailCache,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        private val attemptedThumbKeys = HashSet<Pair<Long, Long>>()
        private val _selected = MutableStateFlow<Set<Long>>(emptySet())
        private val _loading = MutableStateFlow(false)
        private val _refreshing = MutableStateFlow(false)
        private val _error = MutableStateFlow<String?>(null)
        private val _search = MutableStateFlow("")
        private val _virtualPath = MutableStateFlow("")
        private val _createdVirtualFolders = MutableStateFlow<Map<Long, Set<String>>>(emptyMap())
        private val _snackbarEvent = MutableStateFlow<FolderSnackbarEvent?>(null)
        val snackbarEvent: StateFlow<FolderSnackbarEvent?> = _snackbarEvent.asStateFlow()
        private val _isSavedMessages = MutableStateFlow(false)
        val isSavedMessages: StateFlow<Boolean> = _isSavedMessages.asStateFlow()

        private val _pendingReviewFiles = MutableStateFlow<List<com.tgdrive.core.download.UploadFileItem>>(emptyList())
        private val _pendingZipFolder = MutableStateFlow<Pair<Uri?, String?>>(null to null)
        private val _zipPreviewSummary = MutableStateFlow<ZipSelectionSummary?>(null)
        private val _zipPreviewLoading = MutableStateFlow(false)
        private val _batchDecryptProgress = MutableStateFlow<Pair<Int, Int>?>(null)

        private val decryptFlow = DecryptFlowCoordinator(
            scope = viewModelScope,
            ioDispatcher = dispatchers.io,
            downloadState = downloadManager.state,
            detectFormat = { file -> uploadManager.detectEncryptedFormat(file) },
            hasSavedPassphrase = { backupCrypto.hasVaultPassphrase() },
            decryptFile = { encryptedFile, originalDisplayName, passphrase, saveToDownloads, chatId, messageId ->
                uploadManager.decryptLocalFile(
                    encryptedFile = encryptedFile,
                    originalDisplayName = originalDisplayName,
                    passphrase = passphrase,
                    saveToDownloads = saveToDownloads,
                    chatId = chatId,
                    messageId = messageId,
                )
            },
            onSavedMessage = { msg -> showSnackbar(msg) },
            onMissingRemoteId = { msg -> _error.value = msg },
        )
        val decryptedOpenEvent: StateFlow<DecryptedOpenEvent?> = decryptFlow.openEvent

        private var currentChatId: Long? = null
        private var bindResolveJob: Job? = null
        private var bindJob: Job? = null
        private var pdfWatchJob: Job? = null
        private var refreshJob: Job? = null
        private var searchJob: Job? = null
        private var audioPlayWaitJob: Job? = null
        private var zipInspectJob: Job? = null
        private var lastTrashedBatch: List<TrashEntryEntity> = emptyList()

        private val _items = MutableStateFlow<List<FileCacheEntity>>(emptyList())
        private val _boundChatId = MutableStateFlow<Long?>(null)
        private var oldestMessageId: Long = 0L
        private var hasMoreMessages: Boolean = true

        init {
            viewModelScope.launch(dispatchers.io) {
                uploadManager.completedChatEvents.collect { doneChatId ->
                    if (doneChatId == currentChatId) {
                        refresh()
                    }
                }
            }
        }

        private data class FolderAuxTail(
            val boundId: Long?,
            val batchProgress: Pair<Int, Int>?,
            val virtualPath: String,
            val createdVirtualFolders: Map<Long, Set<String>>,
        )

        private data class FolderCoreState(
            val items: List<FileCacheEntity>,
            val selected: Set<Long>,
            val loading: Boolean,
            val refreshing: Boolean,
            val error: String?,
        )

        private data class FolderOverlayState(
            val reviewFiles: List<com.tgdrive.core.download.UploadFileItem>,
            val zipFolder: Pair<Uri?, String?>,
            val zipSummary: ZipSelectionSummary?,
            val zipSummaryLoading: Boolean,
            val decryptDialog: DecryptDialogState?,
        )

        val state: StateFlow<FolderUiState> =
            combine(
                combine(_items, _selected, _loading, _refreshing, _error) { items, selected, loading, refreshing, error ->
                    FolderCoreState(items, selected, loading, refreshing, error)
                },
                combine(
                    _pendingReviewFiles,
                    _pendingZipFolder,
                    _zipPreviewSummary,
                    _zipPreviewLoading,
                    decryptFlow.dialog,
                ) { review, zipFolder, summary, summaryLoading, decrypt ->
                    FolderOverlayState(review, zipFolder, summary, summaryLoading, decrypt)
                },
                combine(
                    _search,
                    preferences.data,
                    uploadManager.uploads,
                    combine(
                        _boundChatId,
                        _batchDecryptProgress,
                        _virtualPath,
                        _createdVirtualFolders,
                    ) { b, bp, vp, createdVps ->
                        FolderAuxTail(b, bp, vp, createdVps)
                    },
                ) {
                        search,
                        prefs,
                        uploadQueue,
                        auxTail,
                    ->
                    listOf(search, prefs, uploadQueue, auxTail)
                },
            ) { c, overlays, aux ->
                val search = aux[0] as String
                val prefs = aux[1] as com.tgdrive.core.datastore.AppPreferencesData
                val uploadQueue = aux[2] as com.tgdrive.core.download.UploadQueueState
                val auxTail = aux[3] as FolderAuxTail
                val boundId = auxTail.boundId
                val batchProgress = auxTail.batchProgress
                val rawVirtualPath = normalizeVirtualPath(auxTail.virtualPath)
                val customForChat = boundId?.let { auxTail.createdVirtualFolders[it] }.orEmpty()

                val unlockedItems = c.items.filterNot { it.isLocked }
                val inVirtualMode = search.isBlank() && prefs.fileFilter == FileFilter.ALL

                val childCounts = LinkedHashMap<String, Int>()
                val childThumbs = LinkedHashMap<String, MutableList<String>>()
                val allVPaths = LinkedHashSet<String>()
                for (cv in customForChat) {
                    val normCv = normalizeVirtualPath(cv)
                    if (normCv.isNotEmpty()) allVPaths.add(normCv)
                }

                val folderScoped = if (inVirtualMode) {
                    unlockedItems.filter { item ->
                        val itemPath = normalizeVirtualPath(item.virtualPath)
                        if (itemPath.isNotEmpty()) allVPaths.add(itemPath)
                        val candidateThumb = if (!item.isEncryptedVault &&
                            !BackupCrypto.isEncryptedFileName(item.fileName)
                        ) {
                            item.thumbnailPath?.takeIf { !it.endsWith(".tgethumb", ignoreCase = true) }
                        } else {
                            null
                        }
                        if (rawVirtualPath.isEmpty()) {
                            if (itemPath.isNotEmpty()) {
                                val top = itemPath.substringBefore('/')
                                childCounts[top] = (childCounts[top] ?: 0) + 1
                                if (candidateThumb != null) {
                                    val list = childThumbs.getOrPut(top) { ArrayList(4) }
                                    if (list.size < 4) list.add(candidateThumb)
                                }
                            }
                        } else if (itemPath.startsWith("$rawVirtualPath/")) {
                            val rest = itemPath.removePrefix("$rawVirtualPath/")
                            val directChild = rest.substringBefore('/')
                            if (directChild.isNotEmpty()) {
                                childCounts[directChild] = (childCounts[directChild] ?: 0) + 1
                                if (candidateThumb != null) {
                                    val list = childThumbs.getOrPut(directChild) { ArrayList(4) }
                                    if (list.size < 4) list.add(candidateThumb)
                                }
                            }
                        }
                        itemPath == rawVirtualPath
                    }
                } else {
                    for (item in unlockedItems) {
                        val itemPath = normalizeVirtualPath(item.virtualPath)
                        if (itemPath.isNotEmpty()) allVPaths.add(itemPath)
                    }
                    unlockedItems
                }
                if (inVirtualMode) {
                    for (cv in customForChat) {
                        val normCv = normalizeVirtualPath(cv)
                        if (normCv.isEmpty()) continue
                        if (rawVirtualPath.isEmpty()) {
                            val top = normCv.substringBefore('/')
                            if (top.isNotEmpty() && top !in childCounts) {
                                childCounts[top] = 0
                            }
                        } else if (normCv.startsWith("$rawVirtualPath/")) {
                            val directChild = normCv.removePrefix("$rawVirtualPath/").substringBefore('/')
                            if (directChild.isNotEmpty() && directChild !in childCounts) {
                                childCounts[directChild] = 0
                            }
                        }
                    }
                }
                val childFolders = childCounts.entries
                    .sortedBy { it.key.lowercase() }
                    .map { it.key to it.value }

                val searched =
                    if (search.isBlank()) {
                        folderScoped
                    } else {
                        folderScoped.filter {
                            it.fileName.contains(search, ignoreCase = true) ||
                                it.userNote?.contains(search, ignoreCase = true) == true ||
                                it.virtualPath.contains(search, ignoreCase = true)
                        }
                    }
                val filtered = applyFilter(searched, prefs.fileFilter)
                val sorted = applySort(filtered, prefs.fileSort)
                val chatUploads = uploadQueue.items.filter {
                    boundId == null || it.chatId == boundId || it.chatId == 0L
                }
                FolderUiState(
                    items = sorted,
                    selectedIds = c.selected,
                    loading = c.loading,
                    refreshing = c.refreshing,
                    error = c.error,
                    search = search,
                    viewMode = prefs.fileViewMode,
                    fileFilter = prefs.fileFilter,
                    fileSort = prefs.fileSort,
                    uploads = chatUploads,
                    pendingReviewFiles = overlays.reviewFiles,
                    pendingZipTreeUri = overlays.zipFolder.first,
                    pendingZipFolderName = overlays.zipFolder.second,
                    zipPreviewSummary = overlays.zipSummary,
                    zipPreviewLoading = overlays.zipSummaryLoading,
                    decryptDialog = overlays.decryptDialog,
                    hasSavedBackupPassphrase = backupCrypto.hasVaultPassphrase(),
                    manualUploadWifiOnly = prefs.manualUploadWifiOnly,
                    batchDecryptProgress = batchProgress,
                    currentVirtualPath = rawVirtualPath,
                    childVirtualFolders = childFolders,
                    childVirtualFolderThumbs = childThumbs,
                    allVirtualPaths = allVPaths.sorted(),
                    encryptedPreviewFingerprint = if (prefs.showEncryptedPreviewsWhenUnlocked) {
                        backupCrypto.passphraseFingerprint()
                    } else {
                        null
                    },
                )
            }.flowOn(dispatchers.default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderUiState())

        fun bind(chatId: Long) {
            if (chatId == 0L) {
                bindResolveJob?.cancel()
                bindResolveJob = viewModelScope.launch(dispatchers.io) {
                    val resolved = runCatching { telegramClient.myUserId() }.getOrDefault(0L)
                    if (resolved != 0L) {
                        withContext(dispatchers.main) {
                            bindResolved(resolved)
                        }
                    }
                }
                return
            }
            bindResolveJob?.cancel()
            bindResolved(chatId)
        }

        private fun bindResolved(chatId: Long) {
            if (chatId == currentChatId) return
            currentChatId = chatId
            _boundChatId.value = chatId
            _virtualPath.value = ""
            bindJob?.cancel()
            pdfWatchJob?.cancel()
            refreshJob?.cancel()
            attemptedThumbKeys.clear()
            _items.value = emptyList()
            _selected.value = emptySet()
            oldestMessageId = 0L
            hasMoreMessages = true
            bindJob =
                viewModelScope.launch(dispatchers.io) {
                    fileCacheDao.observeForChat(chatId, 10000, 0).collect { entities ->
                        _items.value = entities
                        val toWarm =
                            entities
                                .filter { mediaThumbnailCache.needsThumbnailUpgrade(it.thumbnailPath) }
                                .filter { attemptedThumbKeys.add(it.chatId to it.messageId) }
                                .take(50)
                        for (entity in toWarm) {
                            launch {
                                try {
                                    mediaThumbnailCache.ensureThumbnail(entity)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                }
                            }
                        }
                        launch { refreshLocalThumbnails(chatId, entities) }
                    }
                }
            pdfWatchJob =
                viewModelScope.launch(dispatchers.io) {
                    downloadManager.state
                        .map { st ->
                            st.items
                                .filter { it.chatId == chatId && it.status == DownloadStatus.DONE }
                                .map { it.id to it.localPath }
                        }.distinctUntilChanged()
                        .collect {
                            refreshLocalThumbnails(chatId, _items.value)
                        }
                }
            viewModelScope.launch(dispatchers.io) {
                _isSavedMessages.value = runCatching { telegramClient.myUserId() == chatId }.getOrDefault(false)
            }
            refresh()
        }

        private suspend fun refreshLocalThumbnails(
            chatId: Long,
            entities: List<FileCacheEntity>,
        ) {
            val candidates =
                entities.filter { mediaThumbnailCache.needsThumbnailUpgrade(it.thumbnailPath) }
            if (candidates.isEmpty()) return
            for (entity in candidates) {
                val cached = mediaThumbnailCache.getCachedThumbnailPath(chatId, entity.messageId)
                if (cached != null) {
                    if (entity.thumbnailPath != cached) {
                        runCatching { fileCacheDao.updateThumbnailPath(chatId, entity.messageId, cached) }
                    }
                    continue
                }
                val localPath = getLocalPath(entity.messageId) ?: continue
                mediaThumbnailCache.generateFromLocalFile(
                    chatId = chatId,
                    messageId = entity.messageId,
                    localPath = localPath,
                    mimeType = entity.mimeType,
                    fileName = entity.fileName,
                )
            }
        }

        private suspend fun filterOutTrashed(
            chatId: Long,
            msgs: List<TelegramMessage>,
        ): List<TelegramMessage> {
            val trashedIds = trashEntryDao.getAll()
                .asSequence()
                .filter { it.chatId == chatId }
                .mapTo(HashSet()) { it.messageId }
            return if (trashedIds.isEmpty()) msgs else msgs.filter { it.id !in trashedIds }
        }

        fun refresh() {
            val chatId = currentChatId ?: return
            refreshJob?.cancel()
            oldestMessageId = 0L
            hasMoreMessages = true
            refreshJob =
                viewModelScope.launch(dispatchers.io) {
                    _refreshing.value = true
                    _error.value = null
                    try {
                        val msgs = filterOutTrashed(chatId, telegramClient.fetchMessages(chatId, 0, 100))
                        val entities = msgs.map { it.toCacheEntity(chatId) }
                        fileCacheDao.upsertAll(entities)
                        oldestMessageId = msgs.minOfOrNull { it.id } ?: 0L
                        hasMoreMessages = msgs.size >= 50
                        _refreshing.value = false
                        launch {
                            for (delayMs in listOf(2000L, 4000L, 8000L, 16000L, 32000L)) {
                                delay(delayMs)
                                try {
                                    val current = _items.value
                                    if (current.isNotEmpty() &&
                                        current.all {
                                            !mediaThumbnailCache.needsThumbnailUpgrade(it.thumbnailPath) ||
                                                !hasThumbnailableKind(it)
                                        }
                                    ) {
                                        break
                                    }
                                    val msgs2 = filterOutTrashed(chatId, telegramClient.fetchMessages(chatId, 0, 100))
                                    val withThumbs =
                                        msgs2.map { it.toCacheEntity(chatId) }.filter {
                                            it.thumbnailPath != null
                                        }
                                    if (withThumbs.isNotEmpty()) fileCacheDao.upsertAll(withThumbs)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w("Folder refresh failed", t)
                        _error.value = t.message ?: "Failed to load"
                    } finally {
                        withContext(NonCancellable) { _refreshing.value = false }
                    }
                }
        }

        fun loadMore() {
            val chatId = currentChatId ?: return
            if (!hasMoreMessages || _loading.value) return
            viewModelScope.launch(dispatchers.io) {
                _loading.value = true
                try {
                    val raw = telegramClient.fetchMessages(chatId, oldestMessageId, 100)
                    if (raw.isEmpty()) {
                        hasMoreMessages = false
                    } else {
                        val msgs = filterOutTrashed(chatId, raw)
                        if (msgs.isNotEmpty()) {
                            fileCacheDao.upsertAll(msgs.map { it.toCacheEntity(chatId) })
                        }
                        oldestMessageId = raw.minOfOrNull { it.id } ?: oldestMessageId
                        hasMoreMessages = raw.size >= 50
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("loadMore failed", t)
                } finally {
                    _loading.value = false
                }
            }
        }

        fun setSearch(v: String) {
            _search.value = v
            searchJob?.cancel()
            val chatId = currentChatId ?: return
            if (v.length < 2) return
            searchJob =
                viewModelScope.launch(dispatchers.io) {
                    delay(400)
                    try {
                        val results = filterOutTrashed(chatId, telegramClient.searchMessages(chatId, v, 50))
                        if (results.isNotEmpty()) {
                            fileCacheDao.upsertAll(
                                results.map { it.toCacheEntity(chatId) },
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w("setSearch: server-side search failed for '$v'", t)
                        _error.value = "Search failed: ${t.message}"
                    }
                }
        }

        fun toggleSelection(id: Long) {
            _selected.update { if (id in it) it - id else it + id }
        }

        fun clearSelection() {
            _selected.value = emptySet()
        }

        fun selectAll() {
            _selected.value = state.value.items.map { it.messageId }.toSet()
        }

        fun clearError() {
            _error.value = null
        }

        fun clearSnackbar() {
            _snackbarEvent.value = null
        }

        fun showSnackbar(message: String) {
            _snackbarEvent.value = FolderSnackbarEvent(System.nanoTime(), message, canUndoTrash = false)
        }

        fun clearDecryptedOpenEvent() {
            decryptFlow.clearOpenEvent()
        }

        fun toggleStar(messageId: Long) {
            val chatId = currentChatId ?: return
            viewModelScope.launch(dispatchers.io) {
                try {
                    val item = _items.value.find { it.messageId == messageId } ?: return@launch
                    val nextStar = !item.isStarred
                    fileCacheDao.setStarred(chatId, messageId, nextStar)
                    cloudSyncManager.noteStarChanged(chatId, messageId, nextStar)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("toggleStar failed", t)
                }
            }
        }

        fun togglePinOffline(item: FileCacheEntity) {
            viewModelScope.launch(dispatchers.io) {
                try {
                    if (item.isPinnedOffline) {
                        offlinePinStore.unpinFile(item.chatId, item.messageId)
                        showSnackbar("Removed offline copy")
                    } else {
                        val local = getLocalPath(item.messageId)
                        if (local != null) {
                            offlinePinStore.pinFile(item.chatId, item.messageId, local, item.fileName)
                            showSnackbar("Saved for offline access")
                        } else if (item.remoteId.isNotEmpty()) {
                            downloadManager.enqueue(
                                DownloadItem(
                                    id = item.messageId,
                                    chatId = item.chatId,
                                    remoteId = item.remoteId,
                                    fileName = item.fileName,
                                    totalBytes = item.fileSize,
                                    pinOfflineWhenDone = true,
                                ),
                            )
                            fileCacheDao.setPinnedOffline(item.chatId, item.messageId, true)
                            showSnackbar("Downloading for offline access…")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("togglePinOffline failed", t)
                }
            }
        }

        fun markAccessed(messageId: Long) {
            val chatId = currentChatId ?: return
            viewModelScope.launch(dispatchers.io) {
                try {
                    fileCacheDao.updateLastAccessed(chatId, messageId, System.currentTimeMillis())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                }
            }
        }

        fun renameFile(
            messageId: Long,
            newName: String,
        ) {
            val chatId = currentChatId ?: return
            viewModelScope.launch(dispatchers.io) {
                try {
                    fileCacheDao.updateFileName(chatId, messageId, newName)
                    cloudSyncManager.noteFileRenamed(chatId, messageId, newName)
                    Log.i("renameFile: updated local cache for msgId=$messageId -> '$newName'")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("renameFile failed", t)
                    _error.value = "Rename failed: ${t.message}"
                }
            }
        }

        fun restoreFolder() {
            val toDownload = _items.value.filter { it.remoteId.isNotEmpty() }
            if (toDownload.isEmpty()) {
                _error.value = "No files to restore"
                return
            }
            viewModelScope.launch(dispatchers.io) {
                for (item in toDownload) {
                    if (getLocalPath(item.messageId) == null) {
                        downloadFile(item)
                    }
                }
                _error.value = "Restoring ${toDownload.size} files — check download progress"
            }
        }

        fun deleteSelected() {
            val chatId = currentChatId ?: return
            val toDelete = _selected.value.toList()
            if (toDelete.isEmpty()) return
            moveIdsToTrash(chatId, toDelete)
        }

        fun deleteSingle(messageId: Long) {
            val chatId = currentChatId ?: return
            moveIdsToTrash(chatId, listOf(messageId))
        }

        private fun moveIdsToTrash(
            chatId: Long,
            ids: List<Long>,
        ) {
            viewModelScope.launch(dispatchers.io) {
                try {
                    val now = System.currentTimeMillis()
                    val byId = _items.value.associateBy { it.messageId }
                    val entities = ids.mapNotNull { id -> byId[id] ?: fileCacheDao.getById(chatId, id) }
                    if (entities.isEmpty()) return@launch

                    val trashed = entities.map { item ->
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
                    trashEntryDao.upsertAll(trashed)
                    for (item in entities) {
                        fileCacheDao.deleteById(chatId, item.messageId)
                    }
                    _selected.update { current -> current - ids.toSet() }
                    lastTrashedBatch = trashed
                    cloudSyncManager.schedulePushMetadata()
                    _snackbarEvent.value = FolderSnackbarEvent(
                        id = System.nanoTime(),
                        message = appContext.getString(R.string.trash_moved_snackbar, trashed.size),
                        canUndoTrash = true,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("moveIdsToTrash failed", t)
                    _error.value = t.message ?: "Delete failed"
                }
            }
        }

        fun undoLastTrash() {
            val batch = lastTrashedBatch
            if (batch.isEmpty()) return
            lastTrashedBatch = emptyList()
            viewModelScope.launch(dispatchers.io) {
                try {
                    fileCacheDao.upsertAll(
                        batch.map { entry ->
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
                            )
                        },
                    )
                    for (entry in batch) {
                        trashEntryDao.deleteById(entry.chatId, entry.messageId)
                        cloudSyncManager.noteTrashRemoved(entry.chatId, entry.messageId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("undoLastTrash failed", t)
                }
            }
        }

        fun downloadAllSelected() {
            val ids = _selected.value.toList()
            if (ids.isEmpty()) return
            viewModelScope.launch(dispatchers.io) {
                for (id in ids) {
                    val item = _items.value.find { it.messageId == id } ?: continue
                    if (item.remoteId.isNotEmpty() && getLocalPath(id) == null) downloadFile(item)
                }
            }
        }

        fun moveSelected(targetChatId: Long) {
            val chatId = currentChatId ?: return
            val toMove = _selected.value.toList()
            if (toMove.isEmpty()) return
            viewModelScope.launch(dispatchers.io) {
                _loading.value = true
                var moved = 0
                var lastError: String? = null
                try {
                    for (id in toMove) {
                        try {
                            telegramClient.moveFile(chatId, id, targetChatId)
                            fileCacheDao.deleteById(chatId, id)
                            moved++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            lastError = t.message
                            break
                        }
                    }
                    _selected.value = emptySet()
                    if (lastError != null) _error.value = "Moved $moved/${toMove.size}. Error: $lastError"
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    _error.value = t.message ?: "Move failed"
                } finally {
                    _loading.value = false
                    refresh()
                }
            }
        }

        suspend fun listFoldersForMove(): List<TelegramFolder> =
            try {
                telegramClient.listFolders()
            } catch (t: Throwable) {
                Log.w("listFoldersForMove failed", t)
                emptyList()
            }

        // ---- Unified Upload Workflows ----

        fun prepareFileUploadReview(uris: List<Uri>) {
            if (uris.isEmpty()) return
            viewModelScope.launch(dispatchers.io) {
                val resolved = uploadManager.resolveFileItems(uris)
                _pendingReviewFiles.value = resolved
            }
        }

        fun dismissFileUploadReview() {
            _pendingReviewFiles.value = emptyList()
        }

        fun confirmFileReview(action: FileReviewAction) {
            val chatId = currentChatId ?: return
            _pendingReviewFiles.value = emptyList()
            val activeVPath = normalizeVirtualPath(_virtualPath.value)
            when (action) {
                is FileReviewAction.NormalUpload -> {
                    val opts = if (activeVPath.isNotEmpty() && action.options.virtualPath.isEmpty()) {
                        action.options.copy(virtualPath = activeVPath)
                    } else {
                        action.options
                    }
                    uploadManager.enqueueFiles(chatId, action.files, opts)
                }
                is FileReviewAction.PackAsZip -> {
                    val encEnabled = action.options.useSavedPassphrase || !action.options.password.isNullOrBlank()
                    uploadManager.enqueueUrisAsZip(
                        files = action.files,
                        request = ZipUploadRequest(
                            chatId = chatId,
                            baseName = action.options.name,
                            compressionMode = action.options.compressionMode,
                            maxPartBytesOverride = action.options.maxPartBytesOverride,
                            excludePatterns = action.options.excludePatterns,
                            encryption = UploadEncryptionSpec(
                                enabled = encEnabled,
                                useSavedPassphrase = action.options.useSavedPassphrase,
                                customPassphrase = action.options.password,
                            ),
                            wifiOnly = action.options.wifiOnly,
                            virtualPath = activeVPath,
                        ),
                    )
                }
            }
        }

        fun uploadFolderAsNormal(
            treeUri: Uri,
            options: NormalUploadOptions,
        ) {
            val chatId = currentChatId ?: return
            val activeVPath = normalizeVirtualPath(_virtualPath.value)
            val opts = if (activeVPath.isNotEmpty() && options.virtualPath.isEmpty()) {
                options.copy(virtualPath = activeVPath)
            } else {
                options
            }
            uploadManager.enqueueFolderAsNormalFiles(chatId, treeUri, opts)
        }

        fun onZipFolderPicked(
            treeUri: Uri?,
            folderDisplayName: String? = null,
        ) {
            if (treeUri == null) {
                dismissZipDialog()
                return
            }
            val cleanName = folderDisplayName?.takeIf { it.isNotBlank() }
            _pendingZipFolder.value = treeUri to cleanName
            inspectZipFolder(treeUri, excludePatterns = "", maxPartBytesOverride = null)
        }

        fun inspectZipFolder(
            treeUri: Uri,
            excludePatterns: String,
            maxPartBytesOverride: Long?,
        ) {
            zipInspectJob?.cancel()
            zipInspectJob = viewModelScope.launch(dispatchers.io) {
                _zipPreviewLoading.value = true
                try {
                    val patterns = ZipExcludeMatcher.parse(excludePatterns)
                    val accountCap = runCatching { telegramClient.safeZipChunkLimitBytes() }
                        .getOrDefault(TelegramClient.SAFE_CHUNK_LIMIT_STANDARD)
                    val maxPart = maxPartBytesOverride?.takeIf { it > 0L }?.coerceAtMost(accountCap) ?: accountCap
                    val summary = ZipUploader.inspectTreeSummary(
                        context = appContext,
                        treeUri = treeUri,
                        maxPartBytes = maxPart,
                        excludeMatcher = if (patterns.isEmpty()) {
                            null
                        } else {
                            { relPath, name -> ZipExcludeMatcher.matchesPath(relPath, name, patterns) }
                        },
                    )
                    _zipPreviewSummary.value = summary
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("inspectZipFolder failed", t)
                } finally {
                    _zipPreviewLoading.value = false
                }
            }
        }

        fun saveVaultPassphrase(passphrase: String) {
            val clean = passphrase.trim()
            if (clean.length >= 8) {
                backupCrypto.setVaultPassphrase(clean)
                mediaThumbnailCache.clearDecryptedMemoryCache()
            }
        }

        fun confirmZipFolderUpload(options: ZipUploadOptions) {
            val treeUri = _pendingZipFolder.value.first ?: return
            val chatId = currentChatId ?: return
            val activeVPath = normalizeVirtualPath(_virtualPath.value)
            dismissZipDialog()
            val encEnabled = options.useSavedPassphrase || !options.password.isNullOrBlank()
            uploadManager.enqueueFolderAsZip(
                treeUri = treeUri,
                request = ZipUploadRequest(
                    chatId = chatId,
                    baseName = options.name,
                    compressionMode = options.compressionMode,
                    maxPartBytesOverride = options.maxPartBytesOverride,
                    excludePatterns = options.excludePatterns,
                    encryption = UploadEncryptionSpec(
                        enabled = encEnabled,
                        useSavedPassphrase = options.useSavedPassphrase,
                        customPassphrase = options.password,
                    ),
                    wifiOnly = options.wifiOnly,
                    virtualPath = activeVPath,
                ),
            )
        }

        fun dismissZipDialog() {
            zipInspectJob?.cancel()
            _pendingZipFolder.value = null to null
            _zipPreviewSummary.value = null
            _zipPreviewLoading.value = false
        }

        fun pauseUpload(uploadId: String) {
            uploadManager.pause(uploadId)
        }

        fun resumeUpload(uploadId: String) {
            uploadManager.resume(uploadId)
        }

        fun cancelUpload(uploadId: String) {
            uploadManager.cancel(uploadId)
        }

        fun cancelAllUploads() {
            uploadManager.cancelAll()
        }

        fun retryUpload(uploadId: String) {
            uploadManager.retry(uploadId)
        }

        fun clearFinishedUploads() {
            uploadTracker.clearCompleted()
        }

        // ---- Decryption Workflow (.tgenc) ----

        fun startDecryptFlow(item: FileCacheEntity) {
            decryptFlow.start(
                item = item,
                resolveLocalFile = { getLocalPath(item.messageId)?.let(::File) },
                enqueueDownload = { downloadFile(item) },
            )
        }

        fun decryptTarget(
            passphrase: String?,
            saveToDownloads: Boolean,
        ) {
            decryptFlow.decrypt(passphrase, saveToDownloads)
        }

        fun dismissDecryptDialog() {
            decryptFlow.dismiss()
        }

        fun batchDecryptSelected(passphrase: String? = null) {
            val targets = _items.value.filter {
                it.messageId in _selected.value && BackupCrypto.isEncryptedFileName(it.fileName)
            }
            if (targets.isEmpty() || _batchDecryptProgress.value != null) return
            viewModelScope.launch(dispatchers.io) {
                var succeeded = 0
                try {
                    for ((idx, item) in targets.withIndex()) {
                        _batchDecryptProgress.value = (idx + 1) to targets.size
                        var localFile = getLocalPath(item.messageId)?.let(::File)?.takeIf { it.isFile }
                        if (localFile == null && item.remoteId.isNotBlank()) {
                            downloadFile(item)
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
                                localFile = terminal.localPath?.let(::File)?.takeIf { it.isFile }
                            }
                        }
                        if (localFile == null || !localFile.isFile) continue
                        val outcome = uploadManager.decryptLocalFile(
                            encryptedFile = localFile,
                            originalDisplayName = item.fileName,
                            passphrase = passphrase,
                            saveToDownloads = true,
                            chatId = item.chatId,
                            messageId = item.messageId,
                        )
                        if (outcome is DecryptOutcome.Success) {
                            succeeded++
                        }
                    }
                    _selected.update { cur -> cur - targets.map { it.messageId }.toSet() }
                    showSnackbar(
                        if (succeeded == targets.size) {
                            appContext.getString(R.string.browser_batch_decrypt_done, succeeded)
                        } else {
                            appContext.getString(R.string.browser_batch_decrypt_partial, succeeded, targets.size)
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("FolderViewModel: batchDecryptSelected failed", t)
                    _error.value = t.message ?: "Batch decryption failed"
                } finally {
                    _batchDecryptProgress.value = null
                }
            }
        }

        fun downloadFile(item: FileCacheEntity) {
            if (item.remoteId.isEmpty()) {
                _error.value = "File has no remote ID — can't download"
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
        }

        fun shareLink(item: FileCacheEntity) {
            val chatId = currentChatId ?: return
            viewModelScope.launch(dispatchers.io) {
                try {
                    val link = telegramClient.generateShareLink(chatId, item.messageId)
                    if (link != null) {
                        withContext(dispatchers.main) {
                            val clipboard = android.content.ClipData.newPlainText("TelegramDrive share link", link)
                            (
                                appContext.getSystemService(
                                    Context.CLIPBOARD_SERVICE,
                                ) as android.content.ClipboardManager
                            ).setPrimaryClip(clipboard)
                        }
                        _error.value = null
                        showSnackbar("Share link copied to clipboard")
                    } else {
                        _error.value = "Share links are only available for channel folders (not Saved Messages)"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    _error.value = "Failed to generate link: ${t.message}"
                    Log.w("shareLink failed", t)
                }
            }
        }

        fun openVirtualFolder(path: String) {
            _virtualPath.value = normalizeVirtualPath(path)
            _selected.value = emptySet()
        }

        fun navigateUpVirtualFolder(): Boolean {
            val cur = normalizeVirtualPath(_virtualPath.value)
            if (cur.isEmpty()) return false
            _virtualPath.value = cur.substringBeforeLast('/', "")
            _selected.value = emptySet()
            return true
        }

        private fun normalizeVirtualPath(raw: String): String =
            raw.trim().trim('/').replace('\\', '/')

        fun createVirtualFolder(rawName: String) {
            val chatId = currentChatId ?: return
            val cleanSeg = rawName.trim().replace('\\', '/').trim('/').replace('/', '_')
            if (cleanSeg.isEmpty()) return
            val cur = normalizeVirtualPath(_virtualPath.value)
            val fullPath = if (cur.isEmpty()) cleanSeg else "$cur/$cleanSeg"
            _createdVirtualFolders.update { map ->
                val curSet = map[chatId].orEmpty()
                map + (chatId to (curSet + fullPath))
            }
            showSnackbar("Created subfolder: $cleanSeg")
        }

        fun moveSelectedToVirtualPath(targetVirtualPath: String) {
            val chatId = currentChatId ?: return
            val ids = _selected.value.toList()
            if (ids.isEmpty()) return
            val cleanTarget = normalizeVirtualPath(targetVirtualPath)
            viewModelScope.launch(dispatchers.io) {
                for (id in ids) {
                    runCatching {
                        fileCacheDao.updateVirtualPath(chatId, id, cleanTarget)
                        cloudSyncManager.noteVirtualPathChanged(chatId, id, cleanTarget)
                    }
                }
                _selected.value = emptySet()
                val label = cleanTarget.ifEmpty { "Root" }
                showSnackbar("Moved ${ids.size} file(s) to $label")
            }
        }

        fun exportSelectedAsLocalZip() {
            val chatId = currentChatId ?: return
            val targets = _items.value.filter { it.messageId in _selected.value }
            if (targets.isEmpty()) return
            viewModelScope.launch(dispatchers.io) {
                val stageDir = File(appContext.cacheDir, "zip_export_${System.currentTimeMillis()}").apply { mkdirs() }
                try {
                    showSnackbar("Preparing ${targets.size} file(s) for ZIP export…")
                    val readyFiles = ArrayList<File>(targets.size)
                    for (item in targets) {
                        var localPath = getLocalPath(item.messageId)
                        if (localPath == null && item.remoteId.isNotEmpty()) {
                            downloadFile(item)
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
                                localPath = terminal.localPath
                            }
                        }
                        val src = localPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L } ?: continue
                        val copy = File(stageDir, item.fileName.replace(Regex("[/\\\\]"), "_"))
                        src.copyTo(copy, overwrite = true)
                        readyFiles.add(copy)
                    }
                    if (readyFiles.isEmpty()) {
                        _error.value = "Could not download any selected files for ZIP export"
                        return@launch
                    }
                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                    val zipName = "TelegramDrive_Export_$ts.zip"
                    val tempZip = File(stageDir, zipName)
                    net.lingala.zip4j.ZipFile(tempZip).use { zf ->
                        zf.addFiles(readyFiles)
                    }
                    PublicDownloadsExporter.saveToPublicDownloads(
                        ctx = appContext,
                        source = tempZip,
                        displayName = zipName,
                        mimeType = "application/zip",
                    ) ?: throw IllegalStateException("Could not create Downloads entry")
                    _selected.value = emptySet()
                    showSnackbar("Saved $zipName (${readyFiles.size} file(s)) to Downloads/TelegramDrive")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("FolderViewModel: exportSelectedAsLocalZip failed", t)
                    _error.value = t.message ?: "ZIP export failed"
                } finally {
                    runCatching { stageDir.deleteRecursively() }
                }
            }
        }

        fun updateUserNote(item: FileCacheEntity, rawNote: String) {
            val cleaned = rawNote.trim().ifEmpty { null }
            viewModelScope.launch(dispatchers.io) {
                runCatching {
                    fileCacheDao.updateUserNote(item.chatId, item.messageId, cleaned)
                    cloudSyncManager.noteUserNoteChanged(item.chatId, item.messageId, cleaned)
                    showSnackbar(appContext.getString(R.string.details_note_saved))
                }
            }
        }

        fun setItemLocked(item: FileCacheEntity, locked: Boolean) {
            viewModelScope.launch(dispatchers.io) {
                runCatching {
                    fileCacheDao.setLocked(item.chatId, item.messageId, locked)
                    cloudSyncManager.noteLockChanged(item.chatId, item.messageId, locked)
                    if (locked) {
                        _selected.update { it - item.messageId }
                        showSnackbar(appContext.getString(R.string.details_locked_hidden))
                    }
                }
            }
        }

        fun playAudioFromFolder(item: FileCacheEntity) {
            val localPath = getLocalPath(item.messageId)
            if (localPath == null) {
                downloadAndPlayAudio(item)
                return
            }
            val visibleItems = state.value.items
            val tracks = visibleItems.mapNotNull { entry ->
                if (classifyFile(entry.mimeType, entry.fileName) != FileBucket.AUDIO) {
                    null
                } else {
                    val p = getLocalPath(entry.messageId) ?: return@mapNotNull null
                    com.tgdrive.core.audio.AudioTrack(
                        title = entry.fileName,
                        path = p,
                        chatId = entry.chatId,
                        messageId = entry.messageId,
                    )
                }
            }
            if (tracks.isEmpty()) {
                audioPlayerController.playAudio(localPath, item.fileName)
            } else {
                val startIdx = tracks.indexOfFirst { it.messageId == item.messageId }.coerceAtLeast(0)
                audioPlayerController.playQueue(tracks, startIdx)
            }
        }

        fun downloadAndPlayAudio(item: FileCacheEntity) {
            if (item.remoteId.isEmpty()) {
                _error.value = "File has no remote ID — can't download"
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
            audioPlayWaitJob?.cancel()
            audioPlayWaitJob =
                viewModelScope.launch(dispatchers.io) {
                    val terminal =
                        downloadManager.state
                            .map { state ->
                                state.items.find {
                                    it.id == item.messageId && (it.chatId == 0L || it.chatId == item.chatId)
                                }
                            }
                            .first { dlItem ->
                                dlItem == null ||
                                    dlItem.status == DownloadStatus.DONE ||
                                    dlItem.status == DownloadStatus.FAILED ||
                                    dlItem.status == DownloadStatus.PAUSED ||
                                    dlItem.status == DownloadStatus.CANCELLED
                            }
                    when (terminal?.status) {
                        DownloadStatus.DONE -> {
                            val path = terminal.localPath
                            if (path != null && File(path).exists()) {
                                withContext(dispatchers.main) {
                                    playAudioFromFolder(item)
                                }
                            }
                        }
                        DownloadStatus.FAILED -> {
                            _error.value = "Audio download failed: ${terminal.errorMessage}"
                        }
                        DownloadStatus.PAUSED -> {
                            _error.value = terminal.errorMessage ?: "Download paused (waiting for Wi-Fi)"
                        }
                        else -> Unit
                    }
                }
        }

        fun getLocalPath(messageId: Long): String? {
            val chatId = currentChatId ?: 0L
            if (chatId != 0L) {
                offlinePinStore.getPinnedFilePathSync(chatId, messageId)?.let { pinned ->
                    if (File(pinned).exists()) return pinned
                }
            }
            val path =
                downloadManager.findItem(chatId, messageId)
                    ?.takeIf { it.status == DownloadStatus.DONE }
                    ?.localPath ?: return null
            if (!File(path).exists()) {
                Log.w("getLocalPath: file evicted from cache -> $path")
                return null
            }
            return path
        }

        fun copyToDownloads(messageId: Long) {
            val item = _items.value.find { it.messageId == messageId } ?: return
            viewModelScope.launch(dispatchers.io) {
                try {
                    var localPath = getLocalPath(messageId)
                    if (localPath == null) {
                        if (item.remoteId.isEmpty()) {
                            _error.value = "File has no remote ID — can't download"
                            return@launch
                        }
                        showSnackbar("Downloading ${item.fileName}…")
                        downloadFile(item)
                        val terminal = downloadManager.state
                            .map { st ->
                                st.items.find { it.id == messageId && (it.chatId == 0L || it.chatId == item.chatId) }
                            }
                            .first { dl ->
                                dl == null ||
                                    dl.status == DownloadStatus.DONE ||
                                    dl.status == DownloadStatus.FAILED ||
                                    dl.status == DownloadStatus.PAUSED ||
                                    dl.status == DownloadStatus.CANCELLED
                            }
                        if (terminal?.status != DownloadStatus.DONE) {
                            _error.value = terminal?.errorMessage ?: if (terminal?.status == DownloadStatus.PAUSED) {
                                "Download paused (waiting for Wi-Fi)"
                            } else {
                                "Download failed"
                            }
                            return@launch
                        }
                        localPath = terminal.localPath ?: getLocalPath(messageId)
                    }
                    val source = localPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L } ?: run {
                        _error.value = "Downloaded file is missing"
                        return@launch
                    }
                    val saved =
                        PublicDownloadsExporter.saveToPublicDownloads(
                            ctx = appContext,
                            source = source,
                            displayName = item.fileName,
                            mimeType = item.mimeType,
                        )
                    if (saved != null) {
                        showSnackbar("Saved to Downloads/TelegramDrive")
                    } else {
                        _error.value = "Failed to copy file to Downloads"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    _error.value = "Failed to copy: ${t.message}"
                }
            }
        }

        private fun applyFilter(
            items: List<FileCacheEntity>,
            filter: FileFilter,
        ): List<FileCacheEntity> {
            if (filter == FileFilter.ALL) return items
            return items.filter { item ->
                val bucket = classifyFile(item.mimeType, item.fileName)
                when (filter) {
                    FileFilter.ALL -> true
                    FileFilter.IMAGES -> bucket == FileBucket.IMAGES
                    FileFilter.VIDEOS -> bucket == FileBucket.VIDEOS
                    FileFilter.AUDIO -> bucket == FileBucket.AUDIO
                    FileFilter.DOCUMENTS ->
                        bucket == FileBucket.PDF ||
                            bucket == FileBucket.DOCUMENTS
                    FileFilter.ARCHIVES -> bucket == FileBucket.ARCHIVES
                }
            }
        }

        private fun applySort(
            items: List<FileCacheEntity>,
            sort: FileSort,
        ): List<FileCacheEntity> =
            items.sortedWith(
                Comparator { a, b ->
                    when (sort) {
                        FileSort.NAME_ASC -> a.fileName.compareTo(b.fileName, ignoreCase = true)
                        FileSort.NAME_DESC -> b.fileName.compareTo(a.fileName, ignoreCase = true)
                        FileSort.SIZE_ASC -> a.fileSize.compareTo(b.fileSize)
                        FileSort.SIZE_DESC -> b.fileSize.compareTo(a.fileSize)
                        FileSort.DATE_ASC -> a.date.compareTo(b.date)
                        FileSort.DATE_DESC -> b.date.compareTo(a.date)
                    }
                },
            )

        fun setViewMode(mode: FileViewMode) {
            viewModelScope.launch(dispatchers.io) { preferences.update { it.copy(fileViewMode = mode) } }
        }

        fun setFileFilter(filter: FileFilter) {
            viewModelScope.launch(dispatchers.io) { preferences.update { it.copy(fileFilter = filter) } }
        }

        fun setFileSort(sort: FileSort) {
            viewModelScope.launch(dispatchers.io) { preferences.update { it.copy(fileSort = sort) } }
        }

        private fun hasThumbnailableKind(entity: FileCacheEntity): Boolean {
            val ext = entity.fileName.substringAfterLast('.', "").lowercase()
            return ext in THUMBNAILABLE_EXTS ||
                entity.mimeType?.startsWith("image/") == true ||
                entity.mimeType?.startsWith("video/") == true ||
                entity.mimeType.equals("application/pdf", ignoreCase = true)
        }

        private fun TelegramMessage.toCacheEntity(chatId: Long): FileCacheEntity =
            FileCacheEntity(
                messageId = id,
                chatId = chatId,
                fileName = fileName,
                fileSize = fileSize,
                mimeType = mimeType,
                thumbnailPath = thumbnailPath,
                remoteId = fileRemoteId,
                date = date,
            )
    }
