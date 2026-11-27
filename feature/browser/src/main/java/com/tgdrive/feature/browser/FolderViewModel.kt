package com.tgdrive.feature.browser

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
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
import com.tgdrive.core.security.AccessLockManager
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.BiometricHelper
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
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Collections
import javax.inject.Inject

private const val MAX_FOLDER_DECRYPTED_THUMBS = 60
private const val MAX_PENDING_THUMBS = 160

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

@Immutable
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
    val childVirtualFolderThumbs: Map<String, List<SubfolderThumbItem>> = emptyMap(),
    val allVirtualPaths: List<String> = emptyList(),
    val encryptedPreviewFingerprint: String? = null,
    val thumbRefreshNonce: Int = 0,
    val isFolderLockedByPref: Boolean = false,
    val isFolderLockRequired: Boolean = false,
    val isFolderUnlocked: Boolean = true,
    val folderLockReady: Boolean = false,
    val hasAccessPassword: Boolean = false,
    val canUseBiometricLock: Boolean = false,
    val biometricAvailable: Boolean = false,
    val accessLockBiometricEnabled: Boolean = true,
    val lockError: String? = null,
)

@Immutable
data class SubfolderThumbItem(
    val chatId: Long,
    val messageId: Long,
    val localPath: String?,
    val isEncrypted: Boolean,
)

@Immutable
data class SubfolderDeleteStats(
    val virtualPath: String,
    val displayName: String,
    val fileCount: Int,
    val nestedSubfolderCount: Int,
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
        private val accessLockManager: AccessLockManager,
        private val biometricHelper: BiometricHelper,
        private val offlinePinStore: OfflinePinStore,
        private val trashEntryDao: TrashEntryDao,
        private val cloudSyncManager: CloudSyncManager,
        private val mediaThumbnailCache: MediaThumbnailCache,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        private data class PendingThumbRequest(
            val entity: FileCacheEntity,
            val force: Boolean,
        )

        private val thumbQueueLock = Any()
        private val thumbDeque = ArrayDeque<PendingThumbRequest>()
        private val queuedThumbKeys = HashSet<Pair<Long, Long>>()
        private val inFlightThumbKeys = HashSet<Pair<Long, Long>>()
        private val completedThumbKeys = HashSet<Pair<Long, Long>>()
        private val thumbWakeSignal = Channel<Unit>(Channel.CONFLATED)
        private val scannedLocalThumbIds = Collections.synchronizedSet(HashSet<Long>())
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
        private val _isFolderUnlocked = MutableStateFlow(false)
        private val _folderLockReady = MutableStateFlow(false)
        private val _lockError = MutableStateFlow<String?>(null)
        private val _lockConfigVersion = MutableStateFlow(0)
        private val _biometricAvailable = MutableStateFlow(false)
        private val _hasAccessPassword = MutableStateFlow(false)
        private val _thumbRefreshNonce = MutableStateFlow(0)
        private val _passphraseVersion = MutableStateFlow(0)

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
        private var warmThumbsJob: Job? = null
        private var localThumbScanJob: Job? = null
        private var searchJob: Job? = null
        private var audioPlayWaitJob: Job? = null
        private var zipInspectJob: Job? = null
        private var lastTrashedBatch: List<TrashEntryEntity> = emptyList()
        private val lastTrashedVirtualPaths = HashMap<Pair<Long, Long>, String>()

        private val _items = MutableStateFlow<List<FileCacheEntity>>(emptyList())
        private val _boundChatId = MutableStateFlow<Long?>(null)
        private var oldestMessageId: Long = 0L
        private var hasMoreMessages: Boolean = true
        private var hasRefreshedSinceBind: Boolean = false

        init {
            viewModelScope.launch(dispatchers.io) {
                loadCustomVirtualFoldersFromDisk()
                _biometricAvailable.value = biometricHelper.availability() == BiometricHelper.Availability.AVAILABLE
                _hasAccessPassword.value = runCatching { accessLockManager.hasPassword() }.getOrDefault(false)
            }
            viewModelScope.launch(dispatchers.io) {
                uploadManager.completedChatEvents.collect { doneChatId ->
                    if (doneChatId == currentChatId) {
                        refresh()
                    }
                }
            }
            repeat(6) {
                viewModelScope.launch(dispatchers.io) {
                    while (isActive) {
                        var hasMore = false
                        val next = synchronized(thumbQueueLock) {
                            thumbDeque.removeFirstOrNull()?.also { req ->
                                val k = req.entity.chatId to req.entity.messageId
                                queuedThumbKeys.remove(k)
                                inFlightThumbKeys.add(k)
                                hasMore = thumbDeque.isNotEmpty()
                            }
                        }
                        if (next == null) {
                            thumbWakeSignal.receive()
                            continue
                        }
                        if (hasMore) {
                            thumbWakeSignal.trySend(Unit)
                        }
                        val key = next.entity.chatId to next.entity.messageId
                        try {
                            if (isEncryptedEntity(next.entity)) {
                                if (backupCrypto.hasVaultPassphrase()) {
                                    if (next.force) {
                                        mediaThumbnailCache.clearThumbnailFailure(
                                            next.entity.chatId,
                                            next.entity.messageId,
                                        )
                                    }
                                    mediaThumbnailCache.restoreEncryptedThumbFromCloud(
                                        next.entity.chatId,
                                        next.entity.messageId,
                                    )
                                }
                            } else {
                                mediaThumbnailCache.ensureThumbnail(next.entity, force = next.force)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                        } finally {
                            synchronized(thumbQueueLock) {
                                inFlightThumbKeys.remove(key)
                                if (isActive) {
                                    completedThumbKeys.add(key)
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun enqueueThumbRequest(
            entity: FileCacheEntity,
            force: Boolean,
            highPriority: Boolean,
        ) {
            val key = entity.chatId to entity.messageId
            val shouldWake = synchronized(thumbQueueLock) {
                if (force) {
                    completedThumbKeys.remove(key)
                }
                if (key in inFlightThumbKeys || (!force && key in completedThumbKeys)) {
                    return@synchronized false
                }
                if (key in queuedThumbKeys) {
                    if (highPriority || force) {
                        val existing = thumbDeque.firstOrNull {
                            it.entity.chatId == entity.chatId && it.entity.messageId == entity.messageId
                        }
                        if (existing != null) {
                            thumbDeque.remove(existing)
                        }
                        val mergedForce = force || existing?.force == true
                        thumbDeque.addFirst(PendingThumbRequest(entity, mergedForce))
                    }
                    return@synchronized true
                }
                if (highPriority) {
                    thumbDeque.addFirst(PendingThumbRequest(entity, force))
                    queuedThumbKeys.add(key)
                    while (thumbDeque.size > MAX_PENDING_THUMBS) {
                        val dropped = thumbDeque.removeLast()
                        queuedThumbKeys.remove(dropped.entity.chatId to dropped.entity.messageId)
                    }
                    true
                } else {
                    if (thumbDeque.size >= MAX_PENDING_THUMBS) {
                        return@synchronized false
                    }
                    thumbDeque.addLast(PendingThumbRequest(entity, force))
                    queuedThumbKeys.add(key)
                    true
                }
            }
            if (shouldWake) {
                thumbWakeSignal.trySend(Unit)
            }
        }

        private fun clearPendingThumbQueue(clearCompleted: Boolean) {
            synchronized(thumbQueueLock) {
                thumbDeque.clear()
                queuedThumbKeys.clear()
                if (clearCompleted) {
                    completedThumbKeys.clear()
                }
            }
        }

        private fun clearCompletedForSubfolder(
            entities: List<FileCacheEntity>,
            activeSubfolder: String,
        ) {
            val normSub = normalizeVirtualPath(activeSubfolder)
            synchronized(thumbQueueLock) {
                thumbDeque.clear()
                queuedThumbKeys.clear()
                for (entity in entities) {
                    val itemPath = normalizeVirtualPath(entity.virtualPath)
                    if (normSub.isNotEmpty() && !matchesSubfolderPrefix(itemPath, normSub)) continue
                    val p = entity.thumbnailPath
                    val needsThumb = if (isEncryptedEntity(entity)) {
                        p.isNullOrBlank() || !p.endsWith(MediaThumbnailCache.ENCRYPTED_THUMB_EXT, ignoreCase = true)
                    } else {
                        p.isNullOrBlank() || p.contains("/minithumbs/")
                    }
                    if (needsThumb) {
                        completedThumbKeys.remove(entity.chatId to entity.messageId)
                    }
                }
            }
        }

        fun requestThumbnail(entity: FileCacheEntity, force: Boolean = false) {
            val isEncrypted = isEncryptedEntity(entity)
            if (isEncrypted) {
                if (!backupCrypto.hasVaultPassphrase()) return
                if (!force) {
                    val path = entity.thumbnailPath
                    if (!path.isNullOrBlank() &&
                        path.endsWith(MediaThumbnailCache.ENCRYPTED_THUMB_EXT, ignoreCase = true) &&
                        mediaThumbnailCache.isValidThumbnailPath(path)
                    ) {
                        return
                    }
                }
            } else {
                if (!force && !mediaThumbnailCache.needsThumbnailUpgrade(entity.thumbnailPath)) return
            }
            enqueueThumbRequest(entity, force = force, highPriority = true)
        }

        private data class FolderAuxTail(
            val boundId: Long?,
            val batchProgress: Pair<Int, Int>?,
            val virtualPath: String,
            val createdVirtualFolders: Map<Long, Set<String>>,
            val isFolderUnlocked: Boolean,
            val folderLockReady: Boolean,
            val lockError: String?,
            val lockConfigVersion: Int,
            val thumbRefreshNonce: Int,
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

        private val lockTailFlow = combine(
            combine(
                _isFolderUnlocked,
                _folderLockReady,
                _lockError,
                _lockConfigVersion,
            ) { unlocked, ready, lockErr, cfgVer ->
                listOf(unlocked, ready, lockErr, cfgVer)
            },
            _thumbRefreshNonce,
            _passphraseVersion,
        ) { base, nonce, _ ->
            base + nonce
        }

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
                        lockTailFlow,
                    ) { b, bp, vp, createdVps, lt ->
                        FolderAuxTail(
                            boundId = b,
                            batchProgress = bp,
                            virtualPath = vp,
                            createdVirtualFolders = createdVps,
                            isFolderUnlocked = lt[0] as Boolean,
                            folderLockReady = lt[1] as Boolean,
                            lockError = lt[2] as String?,
                            lockConfigVersion = lt[3] as Int,
                            thumbRefreshNonce = lt[4] as Int,
                        )
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

                val isLockedPref = boundId != null && prefs.isFolderLocked(boundId)
                val bioAvail = _biometricAvailable.value
                val hasAccessPass = _hasAccessPassword.value
                val canUseBio = isLockedPref && bioAvail && prefs.accessLockBiometricEnabled
                val lockRequired = isLockedPref && (hasAccessPass || canUseBio)
                val isUnlockedNow = !lockRequired || auxTail.isFolderUnlocked
                val encFp = if (prefs.showEncryptedPreviewsWhenUnlocked && isUnlockedNow) {
                    backupCrypto.passphraseFingerprint()
                } else {
                    null
                }

                val unlockedItems = c.items.filterNot { it.isLocked }
                val inDirectVirtualMode = search.isBlank() && prefs.fileFilter == FileFilter.ALL

                val childCounts = LinkedHashMap<String, Int>()
                val childThumbs = LinkedHashMap<String, MutableList<SubfolderThumbItem>>()
                val allVPaths = LinkedHashSet<String>()
                for (cv in customForChat) {
                    val normCv = normalizeVirtualPath(cv)
                    if (normCv.isNotEmpty()) allVPaths.add(normCv)
                }

                val folderScoped = unlockedItems.filter { item ->
                    val itemPath = normalizeVirtualPath(item.virtualPath)
                    if (itemPath.isNotEmpty()) allVPaths.add(itemPath)

                    val childKey = when {
                        rawVirtualPath.isEmpty() && itemPath.isNotEmpty() -> itemPath.substringBefore('/')
                        rawVirtualPath.isNotEmpty() && itemPath.startsWith("$rawVirtualPath/") ->
                            itemPath.removePrefix("$rawVirtualPath/").substringBefore('/')
                        else -> ""
                    }
                    if (childKey.isNotEmpty()) {
                        childCounts[childKey] = (childCounts[childKey] ?: 0) + 1
                        val existingThumbs = childThumbs[childKey]
                        if ((existingThumbs?.size ?: 0) < 4) {
                            val isEnc = isEncryptedEntity(item)
                            val candidateThumb: SubfolderThumbItem? = if (isEnc) {
                                if (encFp != null) {
                                    val validLocal = item.thumbnailPath?.takeIf { path ->
                                        path.endsWith(MediaThumbnailCache.ENCRYPTED_THUMB_EXT, ignoreCase = true)
                                    } ?: mediaThumbnailCache.getEncryptedThumbnailPath(item.chatId, item.messageId)
                                    val hasCloud = validLocal != null ||
                                        cloudSyncManager.getCloudEncryptedThumbRemoteId(
                                            item.chatId,
                                            item.messageId,
                                        ) != null
                                    if (hasCloud) {
                                        SubfolderThumbItem(
                                            chatId = item.chatId,
                                            messageId = item.messageId,
                                            localPath = validLocal,
                                            isEncrypted = true,
                                        )
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                            } else {
                                item.thumbnailPath?.takeIf { path ->
                                    path.isNotBlank() &&
                                        !path.endsWith(MediaThumbnailCache.ENCRYPTED_THUMB_EXT, ignoreCase = true)
                                }?.let { path ->
                                    SubfolderThumbItem(
                                        chatId = item.chatId,
                                        messageId = item.messageId,
                                        localPath = path,
                                        isEncrypted = false,
                                    )
                                }
                            }
                            if (candidateThumb != null) {
                                val list = existingThumbs ?: ArrayList<SubfolderThumbItem>(4).also {
                                    childThumbs[childKey] = it
                                }
                                list.add(candidateThumb)
                            }
                        }
                    }

                    when {
                        inDirectVirtualMode -> itemPath == rawVirtualPath
                        rawVirtualPath.isNotEmpty() -> matchesSubfolderPrefix(itemPath, rawVirtualPath)
                        else -> true
                    }
                }
                if (inDirectVirtualMode) {
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
                    encryptedPreviewFingerprint = encFp,
                    thumbRefreshNonce = auxTail.thumbRefreshNonce,
                    isFolderLockedByPref = isLockedPref,
                    isFolderLockRequired = lockRequired,
                    isFolderUnlocked = isUnlockedNow,
                    folderLockReady = auxTail.folderLockReady,
                    hasAccessPassword = hasAccessPass,
                    canUseBiometricLock = canUseBio,
                    biometricAvailable = bioAvail,
                    accessLockBiometricEnabled = prefs.accessLockBiometricEnabled,
                    lockError = auxTail.lockError,
                )
            }.flowOn(dispatchers.io)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FolderUiState())

        fun lockFolderView() {
            _isFolderUnlocked.value = false
            _selected.value = emptySet()
        }

        fun clearLockError() {
            _lockError.value = null
        }

        private fun triggerInitialRefreshIfNeeded() {
            if (!hasRefreshedSinceBind) {
                hasRefreshedSinceBind = true
                refresh()
            }
        }

        fun unlockFolderWithPassword(candidate: String) {
            viewModelScope.launch(dispatchers.default) {
                if (accessLockManager.verifyPassword(candidate)) {
                    _lockError.value = null
                    _isFolderUnlocked.value = true
                    withContext(dispatchers.main) { triggerInitialRefreshIfNeeded() }
                } else {
                    _lockError.value = appContext.getString(R.string.access_lock_wrong_password)
                }
            }
        }

        fun authenticateFolder(
            activity: androidx.fragment.app.FragmentActivity,
            title: String,
            subtitle: String,
        ) {
            if (biometricHelper.availability() != BiometricHelper.Availability.AVAILABLE) {
                if (!accessLockManager.hasPassword()) {
                    _isFolderUnlocked.value = true
                    triggerInitialRefreshIfNeeded()
                }
                return
            }
            biometricHelper.authenticate(
                activity = activity,
                title = title,
                subtitle = subtitle,
                onSuccess = {
                    _lockError.value = null
                    _isFolderUnlocked.value = true
                    triggerInitialRefreshIfNeeded()
                },
                onError = { msg -> _lockError.value = msg },
            )
        }

        fun setCurrentFolderLocked(locked: Boolean) {
            val chatId = currentChatId ?: return
            viewModelScope.launch(dispatchers.io) {
                preferences.update { it.withFolderLocked(chatId, locked) }
                _isFolderUnlocked.value = true
                _lockConfigVersion.value += 1
            }
        }

        fun configureFolderAccessLock(
            newPassword: String?,
            biometricEnabled: Boolean,
            lockCurrentFolder: Boolean,
            removePassword: Boolean = false,
        ) {
            val chatId = currentChatId
            viewModelScope.launch(dispatchers.io) {
                if (removePassword) {
                    accessLockManager.clearPassword()
                } else if (!newPassword.isNullOrBlank()) {
                    accessLockManager.setPassword(newPassword)
                }
                _hasAccessPassword.value = accessLockManager.hasPassword()
                _biometricAvailable.value = biometricHelper.availability() == BiometricHelper.Availability.AVAILABLE
                preferences.update { prefs ->
                    val updated = prefs.copy(accessLockBiometricEnabled = biometricEnabled)
                    if (chatId != null && lockCurrentFolder) updated.withFolderLocked(chatId, true) else updated
                }
                _isFolderUnlocked.value = true
                _lockConfigVersion.value += 1
            }
        }

        fun verifyAccessPasswordSync(candidate: String): Boolean = accessLockManager.verifyPassword(candidate)

        fun authenticateRemoveLock(
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
            warmThumbsJob?.cancel()
            localThumbScanJob?.cancel()
            clearPendingThumbQueue(clearCompleted = true)
            scannedLocalThumbIds.clear()
            _items.value = emptyList()
            _selected.value = emptySet()
            _lockError.value = null
            _folderLockReady.value = false
            _isFolderUnlocked.value = false
            hasRefreshedSinceBind = false
            oldestMessageId = 0L
            hasMoreMessages = true
            viewModelScope.launch(dispatchers.io) {
                val prefs = runCatching { preferences.data.first() }.getOrNull()
                val bioAvail = biometricHelper.availability() == BiometricHelper.Availability.AVAILABLE
                val hasPass = accessLockManager.hasPassword()
                _biometricAvailable.value = bioAvail
                _hasAccessPassword.value = hasPass
                val lockedByPref = prefs?.isFolderLocked(chatId) == true
                val lockRequired = lockedByPref && (hasPass || (bioAvail && prefs?.accessLockBiometricEnabled != false))
                _isFolderUnlocked.value = !lockRequired
                _folderLockReady.value = true
                if (!lockRequired && !hasRefreshedSinceBind) {
                    hasRefreshedSinceBind = true
                    refresh()
                }
            }
            bindJob =
                viewModelScope.launch(dispatchers.io) {
                    fileCacheDao.observeForChat(chatId, 10000, 0)
                        .conflate()
                        .collect { entities ->
                            _items.value = entities
                            warmThumbsJob?.cancel()
                            warmThumbsJob = launch {
                                warmThumbnailsForEntities(
                                    entities,
                                    activeSubfolder = normalizeVirtualPath(_virtualPath.value),
                                )
                            }
                            localThumbScanJob?.cancel()
                            localThumbScanJob = launch {
                                delay(250L)
                                refreshLocalThumbnails(chatId, entities)
                            }
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
                            localThumbScanJob?.cancel()
                            localThumbScanJob = launch {
                                refreshLocalThumbnails(chatId, _items.value)
                            }
                        }
                }
            viewModelScope.launch(dispatchers.io) {
                _isSavedMessages.value = runCatching { telegramClient.myUserId() == chatId }.getOrDefault(false)
            }
        }

        private suspend fun refreshLocalThumbnails(
            chatId: Long,
            entities: List<FileCacheEntity>,
        ) {
            val activeSub = normalizeVirtualPath(_virtualPath.value)
            val candidates = ArrayList<FileCacheEntity>(60)
            if (activeSub.isNotEmpty()) {
                for (entity in entities) {
                    if (candidates.size >= 60) break
                    if (entity.messageId in scannedLocalThumbIds) continue
                    val p = entity.thumbnailPath
                    if (!p.isNullOrBlank() && !p.contains("/minithumbs/")) continue
                    if (matchesSubfolderPrefix(normalizeVirtualPath(entity.virtualPath), activeSub)) {
                        candidates.add(entity)
                    }
                }
            }
            for (entity in entities) {
                if (candidates.size >= 60) break
                if (entity.messageId in scannedLocalThumbIds) continue
                val p = entity.thumbnailPath
                if (!p.isNullOrBlank() && !p.contains("/minithumbs/")) continue
                if (activeSub.isNotEmpty() &&
                    matchesSubfolderPrefix(normalizeVirtualPath(entity.virtualPath), activeSub)
                ) {
                    continue
                }
                candidates.add(entity)
            }
            if (candidates.isEmpty()) return
            val updates = ArrayList<FileCacheDao.ThumbnailPathUpdate>()
            for (entity in candidates) {
                scannedLocalThumbIds.add(entity.messageId)
                val cached = mediaThumbnailCache.getCachedThumbnailPath(chatId, entity.messageId)
                if (cached != null) {
                    if (entity.thumbnailPath != cached) {
                        updates.add(FileCacheDao.ThumbnailPathUpdate(chatId, entity.messageId, cached))
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
            if (updates.isNotEmpty()) {
                runCatching { fileCacheDao.updateThumbnailPaths(updates) }
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

        private fun warmThumbnailsForEntities(
            entities: List<FileCacheEntity>,
            activeSubfolder: String,
        ) {
            val hasPassphrase = backupCrypto.hasVaultPassphrase()
            fun needsWarmFast(entity: FileCacheEntity): Boolean {
                if (entity.isLocked) return false
                val p = entity.thumbnailPath
                if (isEncryptedEntity(entity)) {
                    if (!hasPassphrase) return false
                    return p.isNullOrBlank() ||
                        !p.endsWith(MediaThumbnailCache.ENCRYPTED_THUMB_EXT, ignoreCase = true)
                }
                return p.isNullOrBlank() || p.contains("/minithumbs/")
            }

            var queued = 0
            val normActive = normalizeVirtualPath(activeSubfolder)
            if (normActive.isNotEmpty()) {
                for (entity in entities) {
                    if (queued >= 24) break
                    val itemPath = normalizeVirtualPath(entity.virtualPath)
                    if (!matchesSubfolderPrefix(itemPath, normActive)) continue
                    if (!needsWarmFast(entity)) continue
                    enqueueThumbRequest(entity, force = false, highPriority = false)
                    queued++
                }
            }

            val perSubfolderCount = HashMap<String, Int>()
            var subfolderQueued = 0
            for (entity in entities) {
                if (!needsWarmFast(entity)) continue
                val normVpath = normalizeVirtualPath(entity.virtualPath)
                val subKey = when {
                    normActive.isEmpty() && normVpath.isNotEmpty() -> normVpath.substringBefore('/')
                    normActive.isNotEmpty() && normVpath.startsWith("$normActive/") ->
                        normVpath.removePrefix("$normActive/").substringBefore('/')
                    else -> ""
                }
                val isRootMatch = normActive.isEmpty() && normVpath.isEmpty()
                val shouldWarmTop = queued < 24 && isRootMatch
                val shouldWarmSubfolderCover = subKey.isNotEmpty() &&
                    subfolderQueued < 16 &&
                    (perSubfolderCount[subKey] ?: 0) < 2 &&
                    (isEncryptedEntity(entity) || hasThumbnailableKind(entity))
                if (shouldWarmTop || shouldWarmSubfolderCover) {
                    enqueueThumbRequest(entity, force = false, highPriority = false)
                    if (shouldWarmTop) {
                        queued++
                    } else {
                        subfolderQueued++
                        perSubfolderCount[subKey] = (perSubfolderCount[subKey] ?: 0) + 1
                    }
                }
                if (queued >= 24 && subfolderQueued >= 16) break
            }
        }

        fun refresh() {
            val chatId = currentChatId ?: return
            refreshJob?.cancel()
            clearPendingThumbQueue(clearCompleted = true)
            scannedLocalThumbIds.clear()
            _thumbRefreshNonce.value += 1
            oldestMessageId = 0L
            hasMoreMessages = true
            refreshJob =
                viewModelScope.launch(dispatchers.io) {
                    _refreshing.value = true
                    _error.value = null
                    try {
                        val cloudSyncDeferred = async {
                            runCatching {
                                withTimeoutOrNull(4_000L) { cloudSyncManager.syncFromCloud() }
                            }
                        }
                        val firstBatch = filterOutTrashed(chatId, telegramClient.fetchMessages(chatId, 0, 100))
                        cloudSyncDeferred.await()

                        val allFetched = ArrayList<TelegramMessage>(firstBatch.size)
                        allFetched.addAll(firstBatch)
                        oldestMessageId = firstBatch.minOfOrNull { it.id } ?: 0L
                        hasMoreMessages = firstBatch.size >= 50

                        val activeSubfolder = normalizeVirtualPath(_virtualPath.value)
                        if (activeSubfolder.isNotEmpty()) {
                            var matchedCount = allFetched.count { msg ->
                                val resolved =
                                    cloudSyncManager.getCloudVirtualPath(chatId, msg.id) ?: msg.virtualPathFromCaption
                                matchesSubfolderPrefix(normalizeVirtualPath(resolved), activeSubfolder)
                            }
                            var extraPages = 0
                            while (hasMoreMessages && matchedCount < 10 && extraPages < 2) {
                                extraPages++
                                val nextRaw = telegramClient.fetchMessages(chatId, oldestMessageId, 100)
                                if (nextRaw.isEmpty()) {
                                    hasMoreMessages = false
                                    break
                                }
                                val filteredNext = filterOutTrashed(chatId, nextRaw)
                                allFetched.addAll(filteredNext)
                                oldestMessageId = nextRaw.minOfOrNull { it.id } ?: oldestMessageId
                                hasMoreMessages = nextRaw.size >= 50
                                matchedCount += filteredNext.count { msg ->
                                    val resolved =
                                        cloudSyncManager.getCloudVirtualPath(chatId, msg.id)
                                            ?: msg.virtualPathFromCaption
                                    matchesSubfolderPrefix(normalizeVirtualPath(resolved), activeSubfolder)
                                }
                            }
                        }

                        val entities = allFetched.map { it.toCacheEntity(chatId) }
                        if (entities.isNotEmpty()) {
                            fileCacheDao.upsertAll(entities)
                        }
                        warmThumbnailsForEntities(entities, activeSubfolder = activeSubfolder)
                        _refreshing.value = false
                        launch {
                            for (delayMs in listOf(2000L, 4000L, 8000L, 16000L, 32000L)) {
                                delay(delayMs)
                                try {
                                    val current = _items.value
                                    val checkSlice = if (activeSubfolder.isEmpty()) {
                                        current.asSequence().take(60)
                                    } else {
                                        current.asSequence()
                                            .filter {
                                                matchesSubfolderPrefix(
                                                    normalizeVirtualPath(it.virtualPath),
                                                    activeSubfolder,
                                                )
                                            }
                                            .take(60)
                                    }
                                    val allDone = checkSlice.any() && checkSlice.all { item ->
                                        !hasThumbnailableKind(item) ||
                                            (!item.thumbnailPath.isNullOrBlank() &&
                                                !item.thumbnailPath.contains("/minithumbs/"))
                                    }
                                    if (allDone) {
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
                        lastTrashedVirtualPaths[item.chatId to item.messageId] = item.virtualPath
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
                    var restoredAnyVpath = false
                    fileCacheDao.upsertAll(
                        batch.map { entry ->
                            val restoredVpath =
                                lastTrashedVirtualPaths.remove(entry.chatId to entry.messageId).orEmpty()
                            if (restoredVpath.isNotEmpty()) {
                                restoredAnyVpath = true
                                cloudSyncManager.noteVirtualPathChanged(
                                    chatId = entry.chatId,
                                    messageId = entry.messageId,
                                    virtualPath = restoredVpath,
                                    schedulePush = false,
                                )
                            }
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
                                virtualPath = restoredVpath,
                            )
                        },
                    )
                    for (entry in batch) {
                        trashEntryDao.deleteById(entry.chatId, entry.messageId)
                        cloudSyncManager.noteTrashRemoved(entry.chatId, entry.messageId)
                    }
                    if (restoredAnyVpath) {
                        cloudSyncManager.schedulePushMetadata()
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
                _passphraseVersion.value += 1
                _thumbRefreshNonce.value += 1
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
            val target = normalizeVirtualPath(path)
            _virtualPath.value = target
            _selected.value = emptySet()
            val snapshot = _items.value
            clearCompletedForSubfolder(snapshot, target)
            warmThumbsJob?.cancel()
            warmThumbsJob = viewModelScope.launch(dispatchers.io) {
                warmThumbnailsForEntities(snapshot, activeSubfolder = target)
            }
        }

        fun navigateUpVirtualFolder(): Boolean {
            val cur = normalizeVirtualPath(_virtualPath.value)
            if (cur.isEmpty()) return false
            val target = cur.substringBeforeLast('/', "")
            _virtualPath.value = target
            _selected.value = emptySet()
            val snapshot = _items.value
            clearCompletedForSubfolder(snapshot, target)
            warmThumbsJob?.cancel()
            warmThumbsJob = viewModelScope.launch(dispatchers.io) {
                warmThumbnailsForEntities(snapshot, activeSubfolder = target)
            }
            return true
        }

        private fun normalizeVirtualPath(raw: String): String =
            raw.trim().trim('/').replace('\\', '/')

        fun createVirtualFolder(rawName: String) {
            val chatId = currentChatId ?: return
            val cleanSeg = sanitizeSubfolderSegment(rawName)
            if (cleanSeg.isEmpty()) return
            val cur = normalizeVirtualPath(_virtualPath.value)
            val fullPath = if (cur.isEmpty()) cleanSeg else "$cur/$cleanSeg"
            _createdVirtualFolders.update { map ->
                val curSet = map[chatId].orEmpty()
                map + (chatId to (curSet + fullPath))
            }
            viewModelScope.launch(dispatchers.io) { persistCustomVirtualFoldersToDisk() }
            showSnackbar("Created subfolder: $cleanSeg")
        }

        fun computeSubfolderStats(targetVirtualPath: String): SubfolderDeleteStats {
            val target = normalizeVirtualPath(targetVirtualPath)
            val leaf = target.substringAfterLast('/', target)
            if (target.isEmpty()) {
                return SubfolderDeleteStats("", "", 0, 0)
            }
            val nestedFolders = LinkedHashSet<String>()
            var count = 0
            for (item in _items.value) {
                if (item.isLocked) continue
                val p = normalizeVirtualPath(item.virtualPath)
                if (matchesSubfolderPrefix(p, target)) {
                    count++
                    if (p.length > target.length) {
                        nestedFolders.add(p)
                    }
                }
            }
            val chatId = currentChatId
            if (chatId != null) {
                for (cv in _createdVirtualFolders.value[chatId].orEmpty()) {
                    val normCv = normalizeVirtualPath(cv)
                    if (normCv.startsWith("$target/")) {
                        nestedFolders.add(normCv)
                    }
                }
            }
            return SubfolderDeleteStats(
                virtualPath = target,
                displayName = leaf,
                fileCount = count,
                nestedSubfolderCount = nestedFolders.size,
            )
        }

        fun deleteVirtualFolder(
            targetVirtualPath: String,
            permanent: Boolean,
        ) {
            val chatId = currentChatId ?: return
            val target = normalizeVirtualPath(targetVirtualPath)
            if (target.isEmpty()) return
            val displayName = target.substringAfterLast('/', target)

            viewModelScope.launch(dispatchers.io) {
                try {
                    val allInChat =
                        (runCatching { fileCacheDao.forChat(chatId) }.getOrDefault(emptyList()) + _items.value)
                            .distinctBy { it.messageId }
                    val targets = allInChat.filter { item ->
                        !item.isLocked && matchesSubfolderPrefix(normalizeVirtualPath(item.virtualPath), target)
                    }

                    _createdVirtualFolders.update { map ->
                        val curSet = map[chatId].orEmpty()
                        val filtered =
                            curSet.filterNot { matchesSubfolderPrefix(normalizeVirtualPath(it), target) }.toSet()
                        if (filtered.isEmpty()) map - chatId else map + (chatId to filtered)
                    }
                    persistCustomVirtualFoldersToDisk()

                    val curVpath = normalizeVirtualPath(_virtualPath.value)
                    if (matchesSubfolderPrefix(curVpath, target)) {
                        _virtualPath.value = target.substringBeforeLast('/', "")
                    }

                    if (targets.isEmpty()) {
                        showSnackbar("Removed subfolder: $displayName")
                        return@launch
                    }

                    if (!permanent) {
                        val now = System.currentTimeMillis()
                        val trashed = targets.map { item ->
                            lastTrashedVirtualPaths[item.chatId to item.messageId] = item.virtualPath
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
                        for (item in targets) {
                            fileCacheDao.deleteById(chatId, item.messageId)
                        }
                        _selected.update { current -> current - targets.mapTo(HashSet()) { it.messageId } }
                        lastTrashedBatch = trashed
                        cloudSyncManager.schedulePushMetadata()
                        _snackbarEvent.value = FolderSnackbarEvent(
                            id = System.nanoTime(),
                            message = appContext.getString(R.string.trash_moved_snackbar, trashed.size),
                            canUndoTrash = true,
                        )
                    } else {
                        var deletedCount = 0
                        var lastErr: String? = null
                        for (item in targets) {
                            try {
                                telegramClient.deleteMessage(chatId, item.messageId)
                                fileCacheDao.deleteById(chatId, item.messageId)
                                runCatching { offlinePinStore.unpinFile(chatId, item.messageId) }
                                deletedCount++
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                lastErr = t.message
                                Log.w("deleteVirtualFolder: failed deleting msg=${item.messageId}", t)
                            }
                        }
                        _selected.update { current -> current - targets.mapTo(HashSet()) { it.messageId } }
                        cloudSyncManager.schedulePushMetadata()
                        if (lastErr != null && deletedCount < targets.size) {
                            _error.value = "Deleted $deletedCount/${targets.size} file(s) from $displayName: $lastErr"
                        } else {
                            showSnackbar("Deleted $displayName ($deletedCount file(s))")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("deleteVirtualFolder failed", t)
                    _error.value = t.message ?: "Failed to delete subfolder"
                }
            }
        }

        fun renameVirtualFolder(
            oldVirtualPath: String,
            newLeafName: String,
        ) {
            val chatId = currentChatId ?: return
            val oldNorm = normalizeVirtualPath(oldVirtualPath)
            val cleanSeg = sanitizeSubfolderSegment(newLeafName)
            if (oldNorm.isEmpty() || cleanSeg.isEmpty()) return
            val parent = oldNorm.substringBeforeLast('/', "")
            val newNorm = if (parent.isEmpty()) cleanSeg else "$parent/$cleanSeg"
            if (newNorm == oldNorm) return

            viewModelScope.launch(dispatchers.io) {
                try {
                    val allInChat =
                        (runCatching { fileCacheDao.forChat(chatId) }.getOrDefault(emptyList()) + _items.value)
                            .distinctBy { it.messageId }
                    var updatedCount = 0
                    for (item in allInChat) {
                        val itemPath = normalizeVirtualPath(item.virtualPath)
                        if (matchesSubfolderPrefix(itemPath, oldNorm)) {
                            val rewritten = rewriteSubfolderPrefix(itemPath, oldNorm, newNorm)
                            fileCacheDao.updateVirtualPath(chatId, item.messageId, rewritten)
                            cloudSyncManager.noteVirtualPathChanged(
                                chatId = chatId,
                                messageId = item.messageId,
                                virtualPath = rewritten,
                                schedulePush = false,
                            )
                            updatedCount++
                        }
                    }

                    _createdVirtualFolders.update { map ->
                        val curSet = map[chatId].orEmpty()
                        val updatedSet = curSet.mapTo(LinkedHashSet()) { cv ->
                            val normCv = normalizeVirtualPath(cv)
                            if (matchesSubfolderPrefix(normCv, oldNorm)) {
                                rewriteSubfolderPrefix(normCv, oldNorm, newNorm)
                            } else {
                                normCv
                            }
                        }
                        if (updatedSet.isEmpty()) map - chatId else map + (chatId to updatedSet)
                    }
                    persistCustomVirtualFoldersToDisk()

                    val curVpath = normalizeVirtualPath(_virtualPath.value)
                    if (matchesSubfolderPrefix(curVpath, oldNorm)) {
                        _virtualPath.value = rewriteSubfolderPrefix(curVpath, oldNorm, newNorm)
                    }
                    if (updatedCount > 0) {
                        cloudSyncManager.schedulePushMetadata()
                    }
                    showSnackbar("Renamed subfolder to $cleanSeg")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("renameVirtualFolder failed", t)
                    _error.value = t.message ?: "Failed to rename subfolder"
                }
            }
        }

        private fun customFoldersStorageFile(): File? =
            runCatching { File(appContext.filesDir, CUSTOM_FOLDERS_FILE) }.getOrNull()

        private fun loadCustomVirtualFoldersFromDisk() {
            runCatching {
                val f = customFoldersStorageFile() ?: return
                if (!f.isFile || f.length() <= 0L) return
                val loaded = LinkedHashMap<Long, MutableSet<String>>()
                f.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val idx = line.indexOf('\t')
                    if (idx <= 0) return@forEachLine
                    val cid = line.substring(0, idx).toLongOrNull() ?: return@forEachLine
                    val vpath = normalizeVirtualPath(line.substring(idx + 1))
                    if (vpath.isNotEmpty()) {
                        loaded.getOrPut(cid) { LinkedHashSet() }.add(vpath)
                    }
                }
                if (loaded.isNotEmpty()) {
                    _createdVirtualFolders.value = loaded
                }
            }
        }

        private fun persistCustomVirtualFoldersToDisk() {
            runCatching {
                val target = customFoldersStorageFile() ?: return
                target.parentFile?.mkdirs()
                val snapshot = _createdVirtualFolders.value
                val tmp = File(target.parentFile, "$CUSTOM_FOLDERS_FILE.tmp")
                tmp.bufferedWriter().use { writer ->
                    for ((cid, paths) in snapshot) {
                        for (p in paths) {
                            val norm = normalizeVirtualPath(p)
                            if (norm.isNotEmpty()) {
                                writer.write("$cid\t$norm\n")
                            }
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
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
                virtualPath = cloudSyncManager.getCloudVirtualPath(chatId, id) ?: virtualPathFromCaption,
            )

        companion object {
            private const val CUSTOM_FOLDERS_FILE = "custom_virtual_folders.tsv"

            internal fun isEncryptedEntity(entity: FileCacheEntity): Boolean =
                entity.isEncryptedVault ||
                    BackupCrypto.isEncryptedFileName(entity.fileName) ||
                    entity.thumbnailPath?.endsWith(MediaThumbnailCache.ENCRYPTED_THUMB_EXT, ignoreCase = true) == true

            internal fun sanitizeSubfolderSegment(rawName: String): String =
                rawName.trim().replace('\\', '/').trim('/').replace('/', '_')

            internal fun matchesSubfolderPrefix(
                itemPath: String,
                prefix: String,
            ): Boolean {
                if (prefix.isEmpty()) return itemPath.isEmpty()
                return itemPath == prefix || itemPath.startsWith("$prefix/")
            }

            internal fun rewriteSubfolderPrefix(
                itemPath: String,
                oldPrefix: String,
                newPrefix: String,
            ): String = when {
                itemPath == oldPrefix -> newPrefix
                itemPath.startsWith("$oldPrefix/") -> "$newPrefix/${itemPath.removePrefix("$oldPrefix/")}"
                else -> itemPath
            }
        }
    }
