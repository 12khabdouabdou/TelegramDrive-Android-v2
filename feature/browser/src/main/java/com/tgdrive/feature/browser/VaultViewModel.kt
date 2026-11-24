package com.tgdrive.feature.browser

import android.content.Context
import androidx.fragment.app.FragmentActivity
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
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DecryptOutcome
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.UploadManager
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.BiometricHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class VaultUiState(
    val items: List<FileCacheEntity> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val biometricRequired: Boolean = false,
    val isUnlocked: Boolean = false,
    val ready: Boolean = false,
    val decryptedThumbs: Map<Pair<Long, Long>, ByteArray> = emptyMap(),
    val decryptDialog: DecryptDialogState? = null,
    val hasSavedBackupPassphrase: Boolean = false,
    val batchProgress: Pair<Int, Int>? = null,
    val error: String? = null,
)

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val fileCacheDao: FileCacheDao,
    private val preferences: AppPreferences,
    private val biometricHelper: BiometricHelper,
    private val mediaThumbnailCache: MediaThumbnailCache,
    val downloadManager: DownloadManager,
    private val offlinePinStore: OfflinePinStore,
    private val uploadManager: UploadManager,
    private val backupCrypto: BackupCrypto,
    private val cloudSyncManager: CloudSyncManager,
    private val audioPlayerController: AudioPlayerController,
    private val dispatchers: DispatchersProvider,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    private val _biometricRequired = MutableStateFlow(false)
    private val _isUnlocked = MutableStateFlow(false)
    private val _ready = MutableStateFlow(false)
    private val _decryptedThumbs = MutableStateFlow<Map<Pair<Long, Long>, ByteArray>>(emptyMap())
    private val _decryptDialog = MutableStateFlow<DecryptDialogState?>(null)
    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

    private val _decryptedOpenEvent = MutableStateFlow<DecryptedOpenEvent?>(null)
    val decryptedOpenEvent: StateFlow<DecryptedOpenEvent?> = _decryptedOpenEvent.asStateFlow()

    private val attemptedThumbKeys = HashSet<Pair<Long, Long>>()
    private val thumbSemaphore = Semaphore(2)
    private var initialLockEvaluated = false
    private var decryptWaitJob: Job? = null

    val state: StateFlow<VaultUiState> = combine(
        combine(
            fileCacheDao.observeEncryptedVaultFiles(),
            _selected,
            _biometricRequired,
            _isUnlocked,
            _ready,
        ) { items, selected, bioReq, unlocked, ready ->
            listOf(items, selected, bioReq, unlocked, ready)
        },
        combine(
            _decryptedThumbs,
            _decryptDialog,
            _batchProgress,
            _error,
        ) { thumbs, dialog, batch, err ->
            listOf(thumbs, dialog, batch, err)
        },
    ) { core, aux ->
        @Suppress("UNCHECKED_CAST")
        val items = core[0] as List<FileCacheEntity>
        @Suppress("UNCHECKED_CAST")
        val selected = core[1] as Set<Long>
        val bioReq = core[2] as Boolean
        val unlocked = core[3] as Boolean
        val ready = core[4] as Boolean

        @Suppress("UNCHECKED_CAST")
        val thumbs = aux[0] as Map<Pair<Long, Long>, ByteArray>
        val dialog = aux[1] as DecryptDialogState?
        @Suppress("UNCHECKED_CAST")
        val batch = aux[2] as Pair<Int, Int>?
        val err = aux[3] as String?

        VaultUiState(
            items = items,
            selectedIds = selected,
            biometricRequired = bioReq,
            isUnlocked = unlocked,
            ready = ready,
            decryptedThumbs = if (unlocked) thumbs else emptyMap(),
            decryptDialog = dialog,
            hasSavedBackupPassphrase = backupCrypto.hasCustomBackupPassphrase(),
            batchProgress = batch,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VaultUiState())

    init {
        viewModelScope.launch(dispatchers.io) {
            preferences.data.collect { prefs ->
                val bioAvail = biometricHelper.availability() == BiometricHelper.Availability.AVAILABLE
                val required = prefs.biometricLockEnabled && bioAvail
                _biometricRequired.value = required
                if (!initialLockEvaluated) {
                    initialLockEvaluated = true
                    _isUnlocked.value = !required
                    _ready.value = true
                } else if (!required) {
                    _isUnlocked.value = true
                }
            }
        }
        viewModelScope.launch(dispatchers.io) {
            combine(fileCacheDao.observeEncryptedVaultFiles(), _isUnlocked) { files, unlocked ->
                if (unlocked) files else emptyList()
            }.collect { files ->
                if (files.isNotEmpty()) {
                    warmEncryptedThumbnails(files)
                }
            }
        }
    }

    private fun warmEncryptedThumbnails(files: List<FileCacheEntity>) {
        files.forEach { entity ->
            val path = entity.thumbnailPath
            val isEncryptedThumb = path?.endsWith(MediaThumbnailCache.ENCRYPTED_THUMB_EXT, ignoreCase = true) == true
            val hasCloudBackup = cloudSyncManager.getCloudEncryptedThumbRemoteId(entity.chatId, entity.messageId) != null
            if (!isEncryptedThumb && !hasCloudBackup && mediaThumbnailCache.getEncryptedThumbnailPath(entity.chatId, entity.messageId) == null) {
                return@forEach
            }
            val key = entity.chatId to entity.messageId
            val shouldAttempt = synchronized(attemptedThumbKeys) { attemptedThumbKeys.add(key) }
            if (!shouldAttempt) return@forEach
            viewModelScope.launch(dispatchers.io) {
                thumbSemaphore.withPermit {
                    if (!_isUnlocked.value) return@withPermit
                    val effectivePath = when {
                        isEncryptedThumb && path != null && File(path).isFile -> path
                        else -> mediaThumbnailCache.restoreEncryptedThumbFromCloud(entity.chatId, entity.messageId)
                    } ?: return@withPermit
                    val bytes = runCatching { mediaThumbnailCache.decryptThumbnailToBytes(effectivePath) }.getOrNull()
                    if (bytes != null && bytes.isNotEmpty() && _isUnlocked.value) {
                        _decryptedThumbs.update { cur -> cur + (key to bytes) }
                    }
                }
            }
        }
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
    ) {
        if (biometricHelper.availability() != BiometricHelper.Availability.AVAILABLE) {
            _isUnlocked.value = true
            return
        }
        biometricHelper.authenticate(
            activity = activity,
            title = title,
            subtitle = subtitle,
            onSuccess = {
                _error.value = null
                _isUnlocked.value = true
            },
            onError = { msg -> _error.value = msg },
        )
    }

    fun lock() {
        _isUnlocked.value = false
        _selected.value = emptySet()
        _decryptedThumbs.value = emptyMap()
        synchronized(attemptedThumbKeys) { attemptedThumbKeys.clear() }
    }

    fun toggleSelection(messageId: Long) {
        _selected.update { cur -> if (messageId in cur) cur - messageId else cur + messageId }
    }

    fun selectAll() {
        val visible = state.value.items.map { it.messageId }.toSet()
        _selected.value = if (_selected.value.size == visible.size) emptySet() else visible
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun unhideSelected() {
        val targets = state.value.items.filter { it.messageId in _selected.value && it.isLocked }
        if (targets.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            for (item in targets) {
                runCatching {
                    fileCacheDao.setLocked(item.chatId, item.messageId, false)
                    cloudSyncManager.noteLockChanged(item.chatId, item.messageId, false)
                }
            }
            _selected.value = emptySet()
            _snackbar.value = appContext.getString(R.string.vault_unhidden_count, targets.size)
        }
    }

    fun openUnlockedVaultItem(
        item: FileCacheEntity,
        onOpenZip: (Long) -> Unit,
        onOpenVideo: (Long, Long) -> Unit,
        onOpenImage: (Long, Long) -> Unit,
        onOpenPdf: (Long) -> Unit,
        onOpenText: (Long, Long) -> Unit,
    ) {
        if (BackupCrypto.isEncryptedFileName(item.fileName)) {
            startDecryptFlow(item)
            return
        }
        val bucket = classifyFile(item.mimeType, item.fileName)
        val local = getLocalPath(item.chatId, item.messageId)
        fun enqueueIfMissing() {
            if (local == null && item.remoteId.isNotEmpty()) {
                enqueueDownload(item)
            }
        }
        when {
            bucket == FileBucket.ARCHIVES && item.fileName.endsWith(".zip", ignoreCase = true) -> {
                enqueueIfMissing()
                onOpenZip(item.messageId)
            }
            bucket == FileBucket.VIDEOS -> onOpenVideo(item.chatId, item.messageId)
            bucket == FileBucket.IMAGES -> {
                enqueueIfMissing()
                onOpenImage(item.chatId, item.messageId)
            }
            bucket == FileBucket.PDF -> {
                enqueueIfMissing()
                onOpenPdf(item.messageId)
            }
            isTextPreviewable(item.mimeType, item.fileName) -> {
                enqueueIfMissing()
                onOpenText(item.chatId, item.messageId)
            }
            bucket == FileBucket.AUDIO -> {
                if (local != null) {
                    audioPlayerController.playAudio(local, item.fileName)
                } else {
                    viewModelScope.launch(dispatchers.io) {
                        val downloaded = ensureLocalFile(item)
                        if (downloaded != null) {
                            withContext(dispatchers.main) {
                                audioPlayerController.playAudio(downloaded.absolutePath, item.fileName)
                            }
                        } else {
                            _error.value = "Failed to download audio"
                        }
                    }
                }
            }
            else -> startDecryptFlow(item)
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun clearSnackbar() {
        _snackbar.value = null
    }

    fun clearDecryptedOpenEvent() {
        _decryptedOpenEvent.value = null
    }

    fun getLocalPath(chatId: Long, messageId: Long): String? {
        offlinePinStore.getPinnedFilePathSync(chatId, messageId)?.let { pinned ->
            if (File(pinned).isFile) return pinned
        }
        val dl = downloadManager.findItem(chatId, messageId)
            ?.takeIf { it.status == DownloadStatus.DONE }
            ?.localPath
        return dl?.takeIf { File(it).isFile }
    }

    fun startDecryptFlow(item: FileCacheEntity) {
        decryptWaitJob?.cancel()
        val localFile = getLocalPath(item.chatId, item.messageId)?.let(::File)
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
            _error.value = "File has no remote ID — can't download"
            return
        }
        _decryptDialog.value = DecryptDialogState(
            item = item,
            downloading = true,
        )
        enqueueDownload(item)
        decryptWaitJob = viewModelScope.launch(dispatchers.io) {
            val terminal = downloadManager.state
                .map { st ->
                    st.items.find { it.id == item.messageId && (it.chatId == 0L || it.chatId == item.chatId) }
                }
                .first { dl ->
                    dl == null ||
                        dl.status == DownloadStatus.DONE ||
                        dl.status == DownloadStatus.FAILED ||
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
                else -> _decryptDialog.value = null
            }
        }
    }

    fun decryptTarget(
        passphrase: String?,
        saveToDownloads: Boolean,
    ) {
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

    fun batchDecryptSelected(passphrase: String? = null) {
        val targets = state.value.items.filter {
            it.messageId in _selected.value && BackupCrypto.isEncryptedFileName(it.fileName)
        }
        if (targets.isEmpty() || _batchProgress.value != null) return
        viewModelScope.launch(dispatchers.io) {
            var succeeded = 0
            try {
                for ((idx, item) in targets.withIndex()) {
                    _batchProgress.value = (idx + 1) to targets.size
                    val localFile = ensureLocalFile(item) ?: continue
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
                _selected.value = emptySet()
                _snackbar.value = if (succeeded == targets.size) {
                    appContext.getString(R.string.browser_batch_decrypt_done, succeeded)
                } else {
                    appContext.getString(R.string.browser_batch_decrypt_partial, succeeded, targets.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("VaultViewModel: batchDecryptSelected failed", t)
                _error.value = t.message ?: "Batch decryption failed"
            } finally {
                _batchProgress.value = null
            }
        }
    }

    private suspend fun ensureLocalFile(item: FileCacheEntity): File? {
        getLocalPath(item.chatId, item.messageId)?.let(::File)?.let { return it }
        if (item.remoteId.isBlank()) return null
        enqueueDownload(item)
        val terminal = downloadManager.state
            .map { st ->
                st.items.find { it.id == item.messageId && (it.chatId == 0L || it.chatId == item.chatId) }
            }
            .first { dl ->
                dl == null ||
                    dl.status == DownloadStatus.DONE ||
                    dl.status == DownloadStatus.FAILED ||
                    dl.status == DownloadStatus.CANCELLED
            }
        return if (terminal?.status == DownloadStatus.DONE) {
            terminal.localPath?.let(::File)?.takeIf { it.isFile }
        } else {
            null
        }
    }

    private fun enqueueDownload(item: FileCacheEntity) {
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

    override fun onCleared() {
        super.onCleared()
        _decryptedThumbs.value = emptyMap()
        synchronized(attemptedThumbKeys) { attemptedThumbKeys.clear() }
    }
}
