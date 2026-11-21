package com.tgdrive.feature.browser

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.FileFilter
import com.tgdrive.core.datastore.FileSort
import com.tgdrive.core.datastore.FileViewMode
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.UploadTracker
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.telegram.TelegramMessage
import com.tgdrive.core.zip.UploadedZipPartRecord
import com.tgdrive.core.zip.ZipUploader
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

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
    val uploads: List<UploadProgress> = emptyList(),
)

data class UploadProgress(
    val id: Long,
    val name: String,
    val size: Long,
    val progress: Float,
    val status: UploadStatus,
    val errorMessage: String? = null,
)

enum class UploadStatus { UPLOADING, DONE, FAILED }

@HiltViewModel
class FolderViewModel
    @Inject
    constructor(
        private val telegramClient: TelegramClient,
        private val fileCacheDao: FileCacheDao,
        private val preferences: AppPreferences,
        private val dispatchers: DispatchersProvider,
        val downloadManager: com.tgdrive.core.download.DownloadManager,
        val audioPlayerController: com.tgdrive.core.audio.AudioPlayerController,
        private val uploadTracker: UploadTracker,
        private val offlinePinStore: OfflinePinStore,
        private val savedStateHandle: SavedStateHandle,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        private val _selected = MutableStateFlow<Set<Long>>(emptySet())
        private val _loading = MutableStateFlow(false)
        private val _refreshing = MutableStateFlow(false)
        private val _error = MutableStateFlow<String?>(null)
        private val _search = MutableStateFlow("")
        private val _uploads = MutableStateFlow<List<UploadProgress>>(emptyList())
        private val _snackbar = MutableStateFlow<String?>(null)
        val snackbarMessage: StateFlow<String?> = _snackbar.asStateFlow()
        private val _isSavedMessages = MutableStateFlow(false)
        val isSavedMessages: StateFlow<Boolean> = _isSavedMessages.asStateFlow()

        private var currentChatId: Long? = null
        private var pendingZipPassword: String? = null
        private var bindResolveJob: Job? = null
        private var bindJob: Job? = null
        private var refreshJob: Job? = null
        private var searchJob: Job? = null
        private var audioPlayWaitJob: Job? = null

        private val _items = MutableStateFlow<List<FileCacheEntity>>(emptyList())
        private var oldestMessageId: Long = 0L
        private var hasMoreMessages: Boolean = true

        val state: StateFlow<FolderUiState> =
            combine(
                _items,
                _selected,
                _loading,
                _refreshing,
                _error,
            ) { items, selected, loading, refreshing, error ->
                FolderCoreState(items, selected, loading, refreshing, error)
            }.let { core ->
                combine(core, _search, preferences.data, _uploads) { c, search, prefs, uploads ->
                    val searched =
                        if (search.isBlank()) {
                            c.items
                        } else {
                            c.items.filter {
                                it.fileName.contains(
                                    search,
                                    ignoreCase = true,
                                )
                            }
                        }
                    val filtered = applyFilter(searched, prefs.fileFilter)
                    val sorted = applySort(filtered, prefs.fileSort)
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
                        uploads = uploads,
                    )
                }.flowOn(dispatchers.default)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderUiState())

        private data class FolderCoreState(
            val items: List<FileCacheEntity>,
            val selected: Set<Long>,
            val loading: Boolean,
            val refreshing: Boolean,
            val error: String?,
        )

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
            bindJob?.cancel()
            refreshJob?.cancel()
            _items.value = emptyList()
            _selected.value = emptySet()
            oldestMessageId = 0L
            hasMoreMessages = true
            bindJob =
                viewModelScope.launch(dispatchers.io) {
                    fileCacheDao.observeForChat(chatId, 10000, 0).collect { entities -> _items.value = entities }
                }
            viewModelScope.launch(dispatchers.io) {
                _isSavedMessages.value = runCatching { telegramClient.myUserId() == chatId }.getOrDefault(false)
            }
            refresh()
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
                        val msgs = telegramClient.fetchMessages(chatId, 0, 100)
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
                                        current.all { it.thumbnailPath != null || !hasThumbnailableKind(it) }
                                    ) {
                                        break
                                    }
                                    val msgs2 = telegramClient.fetchMessages(chatId, 0, 100)
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
                    val msgs = telegramClient.fetchMessages(chatId, oldestMessageId, 100)
                    if (msgs.isEmpty()) {
                        hasMoreMessages = false
                    } else {
                        fileCacheDao.upsertAll(msgs.map { it.toCacheEntity(chatId) })
                        oldestMessageId = msgs.minOfOrNull { it.id } ?: oldestMessageId
                        hasMoreMessages = msgs.size >= 50
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
                        val results = telegramClient.searchMessages(chatId, v, 50)
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
            _snackbar.value = null
        }

        fun showSnackbar(message: String) {
            _snackbar.value = message
        }

        fun setPendingZipOptions(zipName: String?, password: String?) {
            savedStateHandle[KEY_PENDING_ZIP_NAME] = zipName
            savedStateHandle[KEY_PENDING_ZIP_ENCRYPTED] = !password.isNullOrBlank()
            pendingZipPassword = password
        }

        fun clearPendingZipOptions() {
            savedStateHandle.remove<String?>(KEY_PENDING_ZIP_NAME)
            savedStateHandle.remove<Boolean>(KEY_PENDING_ZIP_ENCRYPTED)
            pendingZipPassword = null
        }

        fun onZipFolderPicked(treeUri: Uri?) {
            val zipName = savedStateHandle.get<String?>(KEY_PENDING_ZIP_NAME)
            val encryptedRequired = savedStateHandle.get<Boolean>(KEY_PENDING_ZIP_ENCRYPTED) ?: false
            val password = pendingZipPassword
            clearPendingZipOptions()

            if (treeUri == null) return
            if (encryptedRequired && password.isNullOrBlank()) {
                _error.value = "ZIP encryption password was cleared while in background; please enter it again"
                return
            }
            uploadFolderAsZip(treeUri, zipName = zipName, password = password)
        }

        fun toggleStar(messageId: Long) {
            val chatId = currentChatId ?: return
            viewModelScope.launch(dispatchers.io) {
                try {
                    val item = _items.value.find { it.messageId == messageId } ?: return@launch
                    fileCacheDao.setStarred(chatId, messageId, !item.isStarred)
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
                        _snackbar.value = "Removed offline copy"
                    } else {
                        val local = getLocalPath(item.messageId)
                        if (local != null) {
                            offlinePinStore.pinFile(item.chatId, item.messageId, local, item.fileName)
                            _snackbar.value = "Saved for offline access"
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
                            _snackbar.value = "Downloading for offline access…"
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
                    Log.i("renameFile: updated local cache for msgId=$messageId → '$newName'")
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
            Log.i("restoreFolder: downloading ${toDownload.size} files to local storage")
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
            viewModelScope.launch(dispatchers.io) {
                _loading.value = true
                var deleted = 0
                var lastError: String? = null
                try {
                    for (id in toDelete) {
                        try {
                            telegramClient.deleteMessage(chatId, id)
                            fileCacheDao.deleteById(chatId, id)
                            deleted++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            lastError = t.message
                            break
                        }
                    }
                    _selected.value = emptySet()
                    if (lastError != null) _error.value = "Deleted $deleted/${toDelete.size}. Error: $lastError"
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    _error.value = t.message ?: "Delete failed"
                } finally {
                    _loading.value = false
                    refresh()
                }
            }
        }

        fun deleteSingle(messageId: Long) {
            val chatId = currentChatId ?: return
            viewModelScope.launch(dispatchers.io) {
                try {
                    telegramClient.deleteMessage(chatId, messageId)
                    fileCacheDao.deleteById(chatId, messageId)
                    _selected.update { it - messageId }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("deleteSingle failed", t)
                    _error.value = "Delete failed: ${t.message}"
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

        suspend fun listFoldersForMove(): List<com.tgdrive.core.telegram.TelegramFolder> =
            try {
                telegramClient.listFolders()
            } catch (t: Throwable) {
                Log.w("listFoldersForMove failed", t)
                emptyList()
            }

        fun uploadFromUris(uris: List<Uri>) {
            val chatId = currentChatId ?: return
            viewModelScope.launch(dispatchers.io) {
                val maxLimit = runCatching { telegramClient.maxUploadSizeBytes() }
                    .getOrDefault(TelegramClient.MAX_FILE_SIZE_STANDARD)
                for (uri in uris) {
                    val id =
                        java.util.UUID
                            .randomUUID()
                            .mostSignificantBits and Long.MAX_VALUE
                    val trackerKey = "folder_ul_$id"
                    val name = queryDisplayName(uri) ?: "file_$id"

                    val fileSize = queryFileSize(uri)
                    if (fileSize != null && fileSize > maxLimit) {
                        val limitGb = maxLimit / (1024L * 1024 * 1024)
                        _uploads.update {
                            it +
                                UploadProgress(id, name, fileSize, 0f, UploadStatus.FAILED, "File exceeds $limitGb GB limit")
                        }
                        continue
                    }

                    val staging = copyToStaging(uri, name) ?: continue

                    val realSize = staging.length()
                    if (realSize > 0L) {
                        val existing = fileCacheDao.findDuplicate(chatId, name, realSize)
                        if (existing != null) {
                            Log.i("uploadFromUris: skipping '$name' — already uploaded")
                            staging.delete()
                            _uploads.update {
                                it +
                                    UploadProgress(
                                        id,
                                        name,
                                        realSize,
                                        1f,
                                        UploadStatus.DONE,
                                        "Already uploaded — skipped",
                                    )
                            }
                            continue
                        }
                    }

                    _uploads.update { it + UploadProgress(id, name, realSize, 0f, UploadStatus.UPLOADING) }
                    uploadTracker.beginUpload(trackerKey, chatId, name, realSize)
                    try {
                        val result =
                            telegramClient.uploadFile(
                                chatId = chatId,
                                localPath = staging.absolutePath,
                                fileName = name,
                                mimeType = guessMime(name),
                            ) { sent, total ->
                                val p = if (total <= 0) 0f else sent.toFloat() / total.toFloat()
                                _uploads.update { list -> list.map { if (it.id == id) it.copy(progress = p) else it } }
                                uploadTracker.updateProgress(trackerKey, sent, total)
                            }
                        fileCacheDao.upsertAll(
                            listOf(
                                FileCacheEntity(
                                    messageId = result.messageId,
                                    chatId = chatId,
                                    fileName = name,
                                    fileSize = realSize,
                                    mimeType = guessMime(name),
                                    thumbnailPath = null,
                                    remoteId = result.remoteId,
                                    date = System.currentTimeMillis(),
                                ),
                            ),
                        )
                        uploadTracker.completeUpload(trackerKey)
                        _uploads.update { list ->
                            list.map {
                                if (it.id == id) {
                                    it.copy(status = UploadStatus.DONE, progress = 1f)
                                } else {
                                    it
                                }
                            }
                        }
                        Log.i("uploadFromUris: uploaded '$name' → messageId=${result.messageId}")
                        refresh()
                    } catch (e: CancellationException) {
                        uploadTracker.failUpload(trackerKey, "Cancelled")
                        throw e
                    } catch (t: Throwable) {
                        Log.w("Upload failed for $name", t)
                        uploadTracker.failUpload(trackerKey, t.message ?: "Upload failed")
                        _uploads.update { list ->
                            list.map {
                                if (it.id == id) {
                                    it.copy(status = UploadStatus.FAILED, errorMessage = t.message ?: "Upload failed")
                                } else {
                                    it
                                }
                            }
                        }
                    } finally {
                        staging.delete()
                    }
                }
            }
        }

        fun uploadFolderAsZip(
            treeUri: Uri,
            zipName: String? = null,
            password: String? = null,
        ) {
            val chatId = currentChatId ?: return
            viewModelScope.launch(dispatchers.io) {
                val id =
                    java.util.UUID
                        .randomUUID()
                        .mostSignificantBits and Long.MAX_VALUE
                val name =
                    zipName?.takeIf { it.isNotBlank() }?.let { sanitizeZipName(it) }
                        ?: "folder_${System.currentTimeMillis()}"
                _uploads.update {
                    it +
                        UploadProgress(
                            id,
                            if (password.isNullOrBlank()) "Zipping folder…" else "Zipping + encrypting…",
                            0,
                            0f,
                            UploadStatus.UPLOADING,
                        )
                }
                val stagingDir = File(appContext.cacheDir, "upload_staging").apply { mkdirs() }
                var manifestFile: File? = null
                try {
                    val maxPartBytes = runCatching { telegramClient.safeZipChunkLimitBytes() }
                        .getOrDefault(TelegramClient.SAFE_CHUNK_LIMIT_STANDARD)
                    val uploadedParts = mutableListOf<UploadedZipPartRecord>()
                    val summary = ZipUploader.zipFolderInParts(
                        context = appContext,
                        treeUri = treeUri,
                        workDir = stagingDir,
                        baseZipName = name,
                        maxPartBytes = maxPartBytes,
                        password = password,
                    ) { part ->
                        val partFileName = part.partFile.name
                        val partLen = part.partFile.length()
                        val partTrackerKey = "folder_zip_${id}_${part.partIndex}"
                        _uploads.update { list ->
                            list.map {
                                if (it.id == id) {
                                    it.copy(name = partFileName, size = partLen, progress = 0f)
                                } else {
                                    it
                                }
                            }
                        }
                        uploadTracker.beginUpload(partTrackerKey, chatId, partFileName, partLen)
                        try {
                            val result = telegramClient.uploadFile(
                                chatId = chatId,
                                localPath = part.partFile.absolutePath,
                                fileName = partFileName,
                                mimeType = "application/zip",
                            ) { sent, total ->
                                val p = if (total <= 0) 0f else sent.toFloat() / total.toFloat()
                                _uploads.update { list -> list.map { if (it.id == id) it.copy(progress = p) else it } }
                                uploadTracker.updateProgress(partTrackerKey, sent, total)
                            }
                            uploadTracker.completeUpload(partTrackerKey)
                            fileCacheDao.upsertAll(
                                listOf(
                                    FileCacheEntity(
                                        messageId = result.messageId,
                                        chatId = chatId,
                                        fileName = partFileName,
                                        fileSize = partLen,
                                        mimeType = "application/zip",
                                        thumbnailPath = null,
                                        remoteId = result.remoteId,
                                        date = System.currentTimeMillis(),
                                    ),
                                ),
                            )
                            uploadedParts += UploadedZipPartRecord(
                                fileName = partFileName,
                                fileSizeBytes = partLen,
                                partIndex = part.partIndex,
                                isSplitVolume = part.isSplitVolume,
                                messageId = result.messageId,
                                entries = part.entries,
                            )
                        } catch (t: Throwable) {
                            uploadTracker.failUpload(partTrackerKey, t.message)
                            throw t
                        }
                    }

                    if (summary.totalFilesProcessed == 0 || uploadedParts.isEmpty()) {
                        _uploads.update { list ->
                            list.map {
                                if (it.id == id) {
                                    it.copy(status = UploadStatus.FAILED, errorMessage = "No files found in selected folder")
                                } else {
                                    it
                                }
                            }
                        }
                        return@launch
                    }

                    if (uploadedParts.size > 1) {
                        val mFile = ZipUploader.writeManifestJsonFile(
                            outputDir = stagingDir,
                            baseName = name,
                            folderName = name,
                            encrypted = !password.isNullOrBlank(),
                            parts = uploadedParts,
                        )
                        manifestFile = mFile
                        val mLen = mFile.length()
                        val mKey = "folder_zip_${id}_manifest"
                        uploadTracker.beginUpload(mKey, chatId, mFile.name, mLen)
                        val mResult = telegramClient.uploadFile(
                            chatId = chatId,
                            localPath = mFile.absolutePath,
                            fileName = mFile.name,
                            mimeType = "application/json",
                        ) { sent, total ->
                            uploadTracker.updateProgress(mKey, sent, total)
                        }
                        uploadTracker.completeUpload(mKey)
                        fileCacheDao.upsertAll(
                            listOf(
                                FileCacheEntity(
                                    messageId = mResult.messageId,
                                    chatId = chatId,
                                    fileName = mFile.name,
                                    fileSize = mLen,
                                    mimeType = "application/json",
                                    thumbnailPath = null,
                                    remoteId = mResult.remoteId,
                                    date = System.currentTimeMillis(),
                                ),
                            ),
                        )
                        _snackbar.value = "Uploaded ${uploadedParts.size} ZIP parts + manifest"
                    }

                    _uploads.update { list ->
                        list.map {
                            if (it.id == id) {
                                val finalLabel = if (uploadedParts.size > 1) {
                                    "${name}_part001..${uploadedParts.size}.zip"
                                } else {
                                    uploadedParts.first().fileName
                                }
                                it.copy(name = finalLabel, status = UploadStatus.DONE, progress = 1f)
                            } else {
                                it
                            }
                        }
                    }
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("uploadFolderAsZip failed", t)
                    _uploads.update { list ->
                        list.map {
                            if (it.id == id) {
                                it.copy(status = UploadStatus.FAILED, errorMessage = t.message ?: "Upload failed")
                            } else {
                                it
                            }
                        }
                    }
                } finally {
                    runCatching { manifestFile?.delete() }
                }
            }
        }

        fun clearFinishedUploads() {
            _uploads.update { list -> list.filter { it.status == UploadStatus.UPLOADING } }
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
                        _snackbar.value = "Share link copied to clipboard"
                        Log.i("shareLink: copied → $link")
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
                                    dlItem.status == DownloadStatus.CANCELLED
                            }
                    when (terminal?.status) {
                        DownloadStatus.DONE -> {
                            val path = terminal.localPath
                            if (path != null && File(path).exists()) {
                                withContext(dispatchers.main) {
                                    audioPlayerController.playAudio(path, item.fileName)
                                }
                            }
                        }
                        DownloadStatus.FAILED -> {
                            _error.value = "Audio download failed: ${terminal.errorMessage}"
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
                Log.w("getLocalPath: file evicted from cache → $path")
                return null
            }
            return path
        }

        fun copyToDownloads(messageId: Long) {
            val localPath =
                getLocalPath(messageId) ?: run {
                    _error.value = "Download the file first"
                    return
                }
            val item = _items.value.find { it.messageId == messageId } ?: return
            viewModelScope.launch(dispatchers.io) {
                try {
                    val source = File(localPath)
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val values =
                            android.content.ContentValues().apply {
                                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, item.fileName)
                                put(
                                    android.provider.MediaStore.Downloads.MIME_TYPE,
                                    item.mimeType ?: "application/octet-stream",
                                )
                                put(
                                    android.provider.MediaStore.Downloads.RELATIVE_PATH,
                                    android.os.Environment.DIRECTORY_DOWNLOADS,
                                )
                            }
                        val uri =
                            appContext.contentResolver.insert(
                                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                values,
                            ) ?: throw IllegalStateException("Could not create MediaStore entry")
                        appContext.contentResolver.openOutputStream(uri)?.use { out ->
                            source.inputStream().use { it.copyTo(out) }
                        } ?: throw IllegalStateException("Could not open Downloads stream")
                    } else {
                        val dir =
                            android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS,
                            )
                        val safeName = item.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        source.copyTo(File(dir, safeName), overwrite = true)
                    }
                    _snackbar.value = "Saved to Downloads"
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
                val bucket =
                    com.tgdrive.core.common
                        .classifyFile(item.mimeType, item.fileName)
                when (filter) {
                    FileFilter.ALL -> true
                    FileFilter.IMAGES -> bucket == com.tgdrive.core.common.FileBucket.IMAGES
                    FileFilter.VIDEOS -> bucket == com.tgdrive.core.common.FileBucket.VIDEOS
                    FileFilter.AUDIO -> bucket == com.tgdrive.core.common.FileBucket.AUDIO
                    FileFilter.DOCUMENTS ->
                        bucket == com.tgdrive.core.common.FileBucket.PDF ||
                            bucket == com.tgdrive.core.common.FileBucket.DOCUMENTS
                    FileFilter.ARCHIVES -> bucket == com.tgdrive.core.common.FileBucket.ARCHIVES
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

        private fun queryDisplayName(uri: Uri): String? =
            try {
                appContext.contentResolver
                    .query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
            } catch (t: Throwable) {
                null
            }

        private fun queryFileSize(uri: Uri): Long? =
            try {
                appContext.contentResolver
                    .query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.SIZE),
                        null,
                        null,
                        null,
                    )?.use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                    }
            } catch (t: Throwable) {
                null
            }

        private fun copyToStaging(
            uri: Uri,
            name: String,
        ): File? {
            return try {
                val stagingDir = File(appContext.cacheDir, "upload_staging").apply { mkdirs() }
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val target = File(stagingDir, "${System.currentTimeMillis()}_$safe")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
                target
            } catch (t: Throwable) {
                Log.w("Failed to copy $name", t)
                null
            }
        }

        private fun sanitizeZipName(name: String): String {
            var sanitized = name.trim()
            if (sanitized.endsWith(".zip", ignoreCase = true)) {
                sanitized = sanitized.dropLast(4)
            }
            sanitized = sanitized.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            return sanitized.ifBlank { "folder_${System.currentTimeMillis()}" }
        }

        private fun guessMime(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "flac" -> "audio/flac"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "txt" -> "text/plain"
                "json" -> "application/json"
                else -> "application/octet-stream"
            }
        }

        private fun hasThumbnailableKind(entity: FileCacheEntity): Boolean {
            val ext = entity.fileName.substringAfterLast('.', "").lowercase()
            return ext in THUMBNAILABLE_EXTS ||
                entity.mimeType?.startsWith("image/") == true ||
                entity.mimeType?.startsWith("video/") == true
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

        companion object {
            private const val KEY_PENDING_ZIP_NAME = "pending_zip_name"
            private const val KEY_PENDING_ZIP_ENCRYPTED = "pending_zip_encrypted"
        }
    }
