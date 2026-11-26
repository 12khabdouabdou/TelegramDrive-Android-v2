package com.tgdrive.feature.browser

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.CrashLogStore
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.isTextPreviewable
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.FileFilter
import com.tgdrive.core.datastore.FileViewMode
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.FolderEntity
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.UploadEncryptionSpec
import com.tgdrive.core.download.UploadFileItem
import com.tgdrive.core.download.UploadManager
import com.tgdrive.core.download.ZipUploadRequest
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.AccessLockManager
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.BiometricHelper
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.zip.ZipExcludeMatcher
import com.tgdrive.core.zip.ZipSelectionSummary
import com.tgdrive.core.zip.ZipUploader
import com.tgdrive.feature.upload.FileReviewAction
import com.tgdrive.feature.upload.ZipUploadOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject

enum class FolderListSort { DEFAULT, NAME_ASC, SIZE_DESC, FILES_DESC }

@Immutable
data class HomeUiState(
    val folders: List<FolderEntity> = emptyList(),
    val pinnedFolderChatIds: Set<Long> = emptySet(),
    val savedMessagesChatId: Long = 0L,
    val loading: Boolean = false,
    val error: String? = null,
    val viewMode: FileViewMode = FileViewMode.GRID,
    val folderSort: FolderListSort = FolderListSort.DEFAULT,
    val folderSizes: Map<Long, Long> = emptyMap(),
    val folderFileCounts: Map<Long, Int> = emptyMap(),
    val totalBytes: Long = 0L,
    val totalFiles: Int = 0,
    val categoryCounts: Map<FileFilter, Int> = emptyMap(),
    val categoryTargets: Map<FileFilter, Pair<Long, String>> = emptyMap(),
    val recentFiles: List<FileCacheEntity> = emptyList(),
    val starredFiles: List<FileCacheEntity> = emptyList(),
    val trashCount: Int = 0,
    val encryptedVaultCount: Int = 0,
    val manualUploadWifiOnly: Boolean = false,
    val hasSavedPassphrase: Boolean = false,
    val cloudSyncing: Boolean = false,
    val lastCloudSyncMs: Long = 0L,
    val lockedFolderChatIds: Set<Long> = emptySet(),
    val hasAccessPassword: Boolean = false,
    val accessLockBiometricEnabled: Boolean = true,
    val biometricAvailable: Boolean = false,
)

private data class HomeDashboardStats(
    val folderSizes: Map<Long, Long> = emptyMap(),
    val folderFileCounts: Map<Long, Int> = emptyMap(),
    val totalBytes: Long = 0L,
    val totalFiles: Int = 0,
    val categoryCounts: Map<FileFilter, Int> = emptyMap(),
    val categoryTargets: Map<FileFilter, Pair<Long, String>> = emptyMap(),
    val recentFiles: List<FileCacheEntity> = emptyList(),
    val starredFiles: List<FileCacheEntity> = emptyList(),
    val trashCount: Int = 0,
    val encryptedVaultCount: Int = 0,
    val manualUploadWifiOnly: Boolean = false,
    val hasSavedPassphrase: Boolean = false,
    val cloudSyncing: Boolean = false,
    val lastCloudSyncMs: Long = 0L,
)

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val telegramClient: TelegramClient,
        private val folderDao: FolderDao,
        private val fileCacheDao: FileCacheDao,
        private val trashEntryDao: TrashEntryDao,
        private val cloudSyncManager: CloudSyncManager,
        private val downloadManager: DownloadManager,
        private val uploadManager: UploadManager,
        private val mediaThumbnailCache: MediaThumbnailCache,
        private val backupCrypto: BackupCrypto,
        private val accessLockManager: AccessLockManager,
        private val biometricHelper: BiometricHelper,
        private val preferences: AppPreferences,
        private val dispatchers: DispatchersProvider,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        private val _lockConfigVersion = MutableStateFlow(0)
        private val _loading = MutableStateFlow(false)
        private val _error = MutableStateFlow<String?>(null)
        private val _snackbar = MutableStateFlow<String?>(null)
        val snackbar: StateFlow<String?> = _snackbar.asStateFlow()
        private val _savedChatId = MutableStateFlow(0L)
        private val _dashboard = MutableStateFlow(HomeDashboardStats())
        private val _folderSort = MutableStateFlow(FolderListSort.DEFAULT)
        private val _pendingReviewFiles = MutableStateFlow<Pair<Long, List<UploadFileItem>>?>(null)
        val pendingReviewFiles: StateFlow<Pair<Long, List<UploadFileItem>>?> = _pendingReviewFiles.asStateFlow()
        private val _pendingZipFolder = MutableStateFlow<Triple<Long, Uri, String?>?>(null)
        val pendingZipFolder: StateFlow<Triple<Long, Uri, String?>?> = _pendingZipFolder.asStateFlow()
        private val _zipPreviewSummary = MutableStateFlow<ZipSelectionSummary?>(null)
        val zipPreviewSummary: StateFlow<ZipSelectionSummary?> = _zipPreviewSummary.asStateFlow()
        private val _zipPreviewLoading = MutableStateFlow(false)
        val zipPreviewLoading: StateFlow<Boolean> = _zipPreviewLoading.asStateFlow()
        private var zipInspectJob: Job? = null
        private val refreshFoldersMutex = Mutex()
        private val warmedThumbKeys = HashSet<Pair<Long, Long>>()

        val state: StateFlow<HomeUiState> =
            combine(
                combine(folderDao.observeAll(), preferences.data, _lockConfigVersion) { folders, prefs, _ ->
                    folders to prefs
                },
                combine(_loading, _error, _savedChatId, _dashboard, _folderSort) { loading, error, savedId, dash, sort ->
                    listOf(loading, error, savedId, dash, sort)
                },
                combine(
                    trashEntryDao.observeCount(),
                    fileCacheDao.observeEncryptedVaultCount(),
                    cloudSyncManager.syncing,
                ) { trash, vaultCount, syncing -> Triple(trash, vaultCount, syncing) },
            ) { (folders, prefs), mid, (trashCount, vaultCount, syncing) ->
                val loading = mid[0] as Boolean
                val error = mid[1] as String?
                val savedId = mid[2] as Long
                val dash = mid[3] as HomeDashboardStats
                val sort = mid[4] as FolderListSort
                val pinned = prefs.pinnedFolderChatIds.mapNotNull { it.toLongOrNull() }.toSet()
                val lockedFolders = prefs.lockedFolderIdsAsLong()
                val sortedFolders = folders.sortedWith(
                    Comparator { a, b ->
                        val pinCmp = (if (b.channelId in pinned) 1 else 0)
                            .compareTo(if (a.channelId in pinned) 1 else 0)
                        if (pinCmp != 0) return@Comparator pinCmp
                        when (sort) {
                            FolderListSort.DEFAULT -> b.createdAt.compareTo(a.createdAt)
                            FolderListSort.NAME_ASC -> a.title.compareTo(b.title, ignoreCase = true)
                            FolderListSort.SIZE_DESC ->
                                (dash.folderSizes[b.channelId] ?: 0L).compareTo(dash.folderSizes[a.channelId] ?: 0L)
                            FolderListSort.FILES_DESC ->
                                (dash.folderFileCounts[b.channelId] ?: 0).compareTo(dash.folderFileCounts[a.channelId] ?: 0)
                        }
                    },
                )
                HomeUiState(
                    folders = sortedFolders,
                    pinnedFolderChatIds = pinned,
                    savedMessagesChatId = savedId,
                    loading = loading,
                    error = error,
                    viewMode = prefs.fileViewMode,
                    folderSort = sort,
                    folderSizes = dash.folderSizes,
                    folderFileCounts = dash.folderFileCounts,
                    totalBytes = dash.totalBytes,
                    totalFiles = dash.totalFiles,
                    categoryCounts = dash.categoryCounts,
                    categoryTargets = dash.categoryTargets,
                    recentFiles = dash.recentFiles.filterNot { it.chatId in lockedFolders },
                    starredFiles = dash.starredFiles.filterNot { it.chatId in lockedFolders },
                    trashCount = trashCount,
                    encryptedVaultCount = vaultCount,
                    manualUploadWifiOnly = prefs.manualUploadWifiOnly,
                    hasSavedPassphrase = backupCrypto.hasVaultPassphrase(),
                    cloudSyncing = syncing,
                    lastCloudSyncMs = prefs.lastCloudSyncMs,
                    lockedFolderChatIds = lockedFolders,
                    hasAccessPassword = accessLockManager.hasPassword(),
                    accessLockBiometricEnabled = prefs.accessLockBiometricEnabled,
                    biometricAvailable = biometricHelper.availability() == BiometricHelper.Availability.AVAILABLE,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

        init {
            refreshSavedMessagesId()
            refreshDashboard()
            refreshFolders()
            viewModelScope.launch(dispatchers.io) {
                uploadManager.completedChatEvents.collect {
                    refreshDashboard()
                }
            }
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
                    val folderFileCounts = aggregates.associate { it.chatId to it.fileCount }
                    val grand = fileCacheDao.grandTotal()
                    val lockedIds = preferences.data.first().lockedFolderIdsAsLong()
                    val recent = fileCacheDao.getRecent()
                    val starred = fileCacheDao.getStarred()
                    val foldersByChat = folderDao.getAll().associateBy { it.channelId }
                    val savedId = _savedChatId.value

                    val counts = mutableMapOf<FileFilter, Int>()
                    val perChatFilterCounts = mutableMapOf<FileFilter, MutableMap<Long, Int>>()
                    for (row in fileCacheDao.chatBucketCounts()) {
                        if (row.chatId in lockedIds) continue
                        val bucket = runCatching { FileBucket.valueOf(row.bucket) }.getOrNull() ?: continue
                        val filter = when (bucket) {
                            FileBucket.IMAGES -> FileFilter.IMAGES
                            FileBucket.VIDEOS -> FileFilter.VIDEOS
                            FileBucket.AUDIO -> FileFilter.AUDIO
                            FileBucket.PDF, FileBucket.DOCUMENTS -> FileFilter.DOCUMENTS
                            FileBucket.ARCHIVES -> FileFilter.ARCHIVES
                            FileBucket.OTHER -> continue
                        }
                        counts[filter] = (counts[filter] ?: 0) + row.fileCount
                        val map = perChatFilterCounts.getOrPut(filter) { mutableMapOf() }
                        map[row.chatId] = (map[row.chatId] ?: 0) + row.fileCount
                    }

                    val targets = perChatFilterCounts.mapValues { (_, byChat) ->
                        val topChatId = byChat.maxByOrNull { it.value }?.key ?: savedId
                        val title = foldersByChat[topChatId]?.title ?: "Saved Messages"
                        topChatId to title
                    }

                    _dashboard.value = HomeDashboardStats(
                        folderSizes = folderSizes,
                        folderFileCounts = folderFileCounts,
                        totalBytes = grand.totalBytes,
                        totalFiles = grand.fileCount,
                        categoryCounts = counts,
                        categoryTargets = targets,
                        recentFiles = recent,
                        starredFiles = starred,
                    )

                    val toWarm = (starred + recent)
                        .distinctBy { it.chatId to it.messageId }
                        .filter { mediaThumbnailCache.needsThumbnailUpgrade(it.thumbnailPath) }
                        .filter { warmedThumbKeys.add(it.chatId to it.messageId) }
                        .take(20)
                    for (item in toWarm) {
                        launch {
                            try {
                                val resolved = mediaThumbnailCache.ensureThumbnail(item)
                                if (resolved != null) {
                                    _dashboard.value = _dashboard.value.copy(
                                        recentFiles = _dashboard.value.recentFiles.map {
                                            if (it.chatId == item.chatId && it.messageId == item.messageId) {
                                                it.copy(thumbnailPath = resolved)
                                            } else {
                                                it
                                            }
                                        },
                                        starredFiles = _dashboard.value.starredFiles.map {
                                            if (it.chatId == item.chatId && it.messageId == item.messageId) {
                                                it.copy(thumbnailPath = resolved)
                                            } else {
                                                it
                                            }
                                        },
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                            }
                        }
                    }
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
                refreshFoldersMutex.withLock {
                    _loading.value = true
                    try {
                        if (_savedChatId.value == 0L) {
                            runCatching { telegramClient.myUserId() }.onSuccess { _savedChatId.value = it }
                        }
                        var remote = telegramClient.listFolders()
                        if (remote.none { !it.isSelf }) {
                            delay(600L)
                            val retry = telegramClient.listFolders()
                            if (retry.any { !it.isSelf }) {
                                remote = retry
                            }
                        }
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
                        Log.w("HomeViewModel: refreshFolders failed", t)
                        CrashLogStore.recordNonFatal(appContext, "refreshFolders", t)
                        val detail = t.message?.takeIf { it.isNotBlank() }?.let { "${t.javaClass.simpleName}: $it" }
                            ?: t.javaClass.simpleName
                        _error.value = "Failed to load folders: $detail"
                    } finally {
                        _loading.value = false
                    }
                }
            }
        }

        fun openOrPreviewFile(
            file: FileCacheEntity,
            onOpenVideo: (Long, Long) -> Unit,
            onOpenImage: (Long, Long) -> Unit,
            onOpenPdf: (Long, Long) -> Unit,
            onOpenZip: (Long) -> Unit,
            onOpenText: (Long, Long) -> Unit,
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
                bucket == FileBucket.VIDEOS -> {
                    onOpenVideo(file.chatId, file.messageId)
                }
                bucket == FileBucket.IMAGES -> {
                    enqueueIfNeeded()
                    onOpenImage(file.chatId, file.messageId)
                }
                bucket == FileBucket.PDF -> {
                    enqueueIfNeeded()
                    onOpenPdf(file.chatId, file.messageId)
                }
                isTextPreviewable(file.mimeType, file.fileName) -> {
                    enqueueIfNeeded()
                    onOpenText(file.chatId, file.messageId)
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

        fun togglePinFolder(channelId: Long) {
            viewModelScope.launch(dispatchers.io) {
                preferences.update { it.withToggledPinnedFolder(channelId) }
            }
        }

        fun setFolderLocked(channelId: Long, locked: Boolean) {
            viewModelScope.launch(dispatchers.io) {
                preferences.update { it.withFolderLocked(channelId, locked) }
                _lockConfigVersion.value += 1
            }
        }

        fun configureAccessLock(
            newPassword: String?,
            biometricEnabled: Boolean,
            removePassword: Boolean = false,
            alsoLockChatId: Long? = null,
        ) {
            viewModelScope.launch(dispatchers.io) {
                if (removePassword) {
                    accessLockManager.clearPassword()
                } else if (!newPassword.isNullOrBlank()) {
                    accessLockManager.setPassword(newPassword)
                }
                preferences.update { prefs ->
                    val updated = prefs.copy(accessLockBiometricEnabled = biometricEnabled)
                    if (alsoLockChatId != null) updated.withFolderLocked(alsoLockChatId, true) else updated
                }
                _lockConfigVersion.value += 1
            }
        }

        fun verifyAccessPassword(candidate: String): Boolean = accessLockManager.verifyPassword(candidate)

        fun authenticateBiometric(
            activity: androidx.fragment.app.FragmentActivity,
            title: String,
            subtitle: String,
            onSuccess: () -> Unit,
            onError: (String) -> Unit,
        ) {
            biometricHelper.authenticate(
                activity = activity,
                title = title,
                subtitle = subtitle,
                onSuccess = onSuccess,
                onError = onError,
            )
        }

        fun setFolderSort(sort: FolderListSort) {
            _folderSort.value = sort
        }

        fun setFolderColor(
            folder: FolderEntity,
            color: Int,
        ) {
            viewModelScope.launch(dispatchers.io) {
                try {
                    folderDao.updateColor(folder.id, color)
                    cloudSyncManager.noteFolderColorChanged(folder.channelId, color)
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

        fun clearSnackbar() {
            _snackbar.value = null
        }

        fun prepareFileReview(
            targetChatId: Long,
            uris: List<Uri>,
        ) {
            if (uris.isEmpty()) return
            viewModelScope.launch(dispatchers.io) {
                val chatId = if (targetChatId == 0L) {
                    runCatching { telegramClient.myUserId() }.getOrDefault(0L)
                } else {
                    targetChatId
                }
                if (chatId == 0L) {
                    _error.value = "Telegram session not ready"
                    return@launch
                }
                val resolved = uploadManager.resolveFileItems(uris)
                if (resolved.isNotEmpty()) {
                    _pendingReviewFiles.value = chatId to resolved
                }
            }
        }

        fun dismissFileReview() {
            _pendingReviewFiles.value = null
        }

        fun confirmFileReview(action: FileReviewAction) {
            val chatId = _pendingReviewFiles.value?.first ?: return
            _pendingReviewFiles.value = null
            when (action) {
                is FileReviewAction.NormalUpload -> {
                    uploadManager.enqueueFiles(chatId, action.files, action.options)
                    _snackbar.value = "Uploading ${action.files.size} file(s) in background"
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
                        ),
                    )
                    _snackbar.value = "Packing ${action.files.size} file(s) into ${action.options.name}.zip"
                }
            }
        }

        fun onZipFolderPicked(
            targetChatId: Long,
            treeUri: Uri?,
            folderDisplayName: String? = null,
        ) {
            if (treeUri == null) return
            viewModelScope.launch(dispatchers.io) {
                val chatId = if (targetChatId == 0L) {
                    runCatching { telegramClient.myUserId() }.getOrDefault(0L)
                } else {
                    targetChatId
                }
                if (chatId == 0L) {
                    _error.value = "Telegram session not ready"
                    return@launch
                }
                _pendingZipFolder.value = Triple(chatId, treeUri, folderDisplayName)
                inspectZipFolder(treeUri, excludePatterns = "", maxPartBytesOverride = null)
            }
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
                    _zipPreviewSummary.value = ZipUploader.inspectTreeSummary(
                        context = appContext,
                        treeUri = treeUri,
                        maxPartBytes = maxPart,
                        excludeMatcher = if (patterns.isEmpty()) {
                            null
                        } else {
                            { relPath, name -> ZipExcludeMatcher.matchesPath(relPath, name, patterns) }
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("HomeViewModel: inspectZipFolder failed", t)
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
            val (chatId, treeUri, _) = _pendingZipFolder.value ?: return
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
                ),
            )
            _snackbar.value = "Archiving ${options.name}.zip in background"
        }

        fun dismissZipDialog() {
            zipInspectJob?.cancel()
            _pendingZipFolder.value = null
            _zipPreviewSummary.value = null
            _zipPreviewLoading.value = false
        }
    }
