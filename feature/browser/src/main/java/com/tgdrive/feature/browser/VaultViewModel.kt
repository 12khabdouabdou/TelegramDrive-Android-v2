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
import com.tgdrive.core.download.PassVaultCloudVerifier
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

private const val MAX_VAULT_DECRYPTED_THUMBS = 60

data class VaultExternalOpenEvent(
    val id: Long,
    val file: File,
    val mimeType: String,
)

data class VaultUiState(
    val items: List<FileCacheEntity> = emptyList(),
    val selectedIds: Set<Pair<Long, Long>> = emptySet(),
    val biometricRequired: Boolean = false,
    val isUnlocked: Boolean = false,
    val ready: Boolean = false,
    val decryptedThumbs: Map<Pair<Long, Long>, ByteArray> = emptyMap(),
    val decryptDialog: DecryptDialogState? = null,
    val hasVaultPassphrase: Boolean = false,
    val hasCloudVerifier: Boolean = false,
    val passphraseBusy: Boolean = false,
    val batchProgress: Pair<Int, Int>? = null,
    val error: String? = null,
)

enum class VaultPassphraseSaveStatus {
    SAVED,
    CLOUD_MISMATCH,
}

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
    private val passVaultCloudVerifier: PassVaultCloudVerifier,
    private val cloudSyncManager: CloudSyncManager,
    private val audioPlayerController: AudioPlayerController,
    private val dispatchers: DispatchersProvider,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _selected = MutableStateFlow<Set<Pair<Long, Long>>>(emptySet())
    private val _biometricRequired = MutableStateFlow(false)
    private val _isUnlocked = MutableStateFlow(false)
    private val _ready = MutableStateFlow(false)
    private val _decryptedThumbs = MutableStateFlow<Map<Pair<Long, Long>, ByteArray>>(emptyMap())
    private val _hasVaultPassphrase = MutableStateFlow(backupCrypto.hasVaultPassphrase())
    private val _hasCloudVerifier = MutableStateFlow(false)
    private val _passphraseBusy = MutableStateFlow(false)
    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

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
        onSavedMessage = { msg -> _snackbar.value = msg },
        onMissingRemoteId = { msg -> _error.value = msg },
    )
    val decryptedOpenEvent: StateFlow<DecryptedOpenEvent?> = decryptFlow.openEvent

    private val _externalOpenEvent = MutableStateFlow<VaultExternalOpenEvent?>(null)
    val externalOpenEvent: StateFlow<VaultExternalOpenEvent?> = _externalOpenEvent.asStateFlow()

    private val attemptedThumbKeys = HashSet<Pair<Long, Long>>()
    private val thumbSemaphore = Semaphore(2)
    private var initialLockEvaluated = false

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
            decryptFlow.dialog,
            _batchProgress,
            _error,
            combine(_hasVaultPassphrase, _hasCloudVerifier, _passphraseBusy) { hasPass, hasVer, busy ->
                Triple(hasPass, hasVer, busy)
            },
        ) { thumbs, dialog, batch, err, passTuple ->
            listOf(thumbs, dialog, batch, err, passTuple)
        },
    ) { core, aux ->
        @Suppress("UNCHECKED_CAST")
        val items = core[0] as List<FileCacheEntity>
        @Suppress("UNCHECKED_CAST")
        val selected = core[1] as Set<Pair<Long, Long>>
        val bioReq = core[2] as Boolean
        val unlocked = core[3] as Boolean
        val ready = core[4] as Boolean

        @Suppress("UNCHECKED_CAST")
        val thumbs = aux[0] as Map<Pair<Long, Long>, ByteArray>
        val dialog = aux[1] as DecryptDialogState?
        @Suppress("UNCHECKED_CAST")
        val batch = aux[2] as Pair<Int, Int>?
        val err = aux[3] as String?
        @Suppress("UNCHECKED_CAST")
        val passTuple = aux[4] as Triple<Boolean, Boolean, Boolean>

        VaultUiState(
            items = items,
            selectedIds = selected,
            biometricRequired = bioReq,
            isUnlocked = unlocked,
            ready = ready,
            decryptedThumbs = if (unlocked) thumbs else emptyMap(),
            decryptDialog = dialog,
            hasVaultPassphrase = passTuple.first,
            hasCloudVerifier = passTuple.second,
            passphraseBusy = passTuple.third,
            batchProgress = batch,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VaultUiState())

    init {
        viewModelScope.launch(dispatchers.io) {
            _hasCloudVerifier.value = runCatching { passVaultCloudVerifier.hasCloudVerifier() }.getOrDefault(false)
        }
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
                        _decryptedThumbs.update { cur ->
                            if (cur.size < MAX_VAULT_DECRYPTED_THUMBS) {
                                cur + (key to bytes)
                            } else {
                                val trimmed = LinkedHashMap<Pair<Long, Long>, ByteArray>(MAX_VAULT_DECRYPTED_THUMBS)
                                val dropCount = cur.size - MAX_VAULT_DECRYPTED_THUMBS + 1
                                cur.entries.drop(dropCount).forEach { (k, v) -> trimmed[k] = v }
                                trimmed[key] = bytes
                                trimmed
                            }
                        }
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
        mediaThumbnailCache.clearDecryptedMemoryCache()
        synchronized(attemptedThumbKeys) { attemptedThumbKeys.clear() }
    }

    fun toggleSelection(chatId: Long, messageId: Long) {
        val key = chatId to messageId
        _selected.update { cur -> if (key in cur) cur - key else cur + key }
    }

    fun selectAll() {
        val visible = state.value.items.mapTo(mutableSetOf()) { it.chatId to it.messageId }
        _selected.value = if (_selected.value.size == visible.size) emptySet() else visible
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun unhideSelected() {
        val selectedKeys = _selected.value
        val targets = state.value.items.filter { (it.chatId to it.messageId) in selectedKeys && it.isLocked }
        if (targets.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            for (item in targets) {
                try {
                    fileCacheDao.setLocked(item.chatId, item.messageId, false)
                    cloudSyncManager.noteLockChanged(item.chatId, item.messageId, false)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("VaultViewModel: failed to unhide ${item.chatId}/${item.messageId}", t)
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
        onOpenPdf: (Long, Long) -> Unit,
        onOpenText: (Long, Long) -> Unit,
    ) {
        if (item.isEncryptedVault || BackupCrypto.isEncryptedFileName(item.fileName)) {
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
                onOpenPdf(item.chatId, item.messageId)
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
            else -> {
                val mime = item.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                if (local != null) {
                    _externalOpenEvent.value = VaultExternalOpenEvent(System.nanoTime(), File(local), mime)
                } else {
                    viewModelScope.launch(dispatchers.io) {
                        val downloaded = ensureLocalFile(item)
                        if (downloaded != null) {
                            _externalOpenEvent.value = VaultExternalOpenEvent(System.nanoTime(), downloaded, mime)
                        } else {
                            _error.value = "Failed to download file"
                        }
                    }
                }
            }
        }
    }

    fun clearExternalOpenEvent() {
        _externalOpenEvent.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun clearSnackbar() {
        _snackbar.value = null
    }

    fun clearDecryptedOpenEvent() {
        decryptFlow.clearOpenEvent()
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

    fun saveVaultPassphraseQuietly(passphrase: String) {
        val clean = passphrase.trim()
        if (clean.length < 8) return
        viewModelScope.launch(dispatchers.io) {
            backupCrypto.setVaultPassphrase(clean)
            mediaThumbnailCache.clearDecryptedMemoryCache()
            _hasVaultPassphrase.value = true
            _decryptedThumbs.value = emptyMap()
            synchronized(attemptedThumbKeys) { attemptedThumbKeys.clear() }
            if (_isUnlocked.value && state.value.items.isNotEmpty()) {
                warmEncryptedThumbnails(state.value.items)
            }
        }
    }

    suspend fun setAndVerifyVaultPassphrase(
        passphrase: String,
        forceReplace: Boolean = false,
    ): Result<VaultPassphraseSaveStatus> = withContext(dispatchers.io) {
        val clean = passphrase.trim()
        require(clean.length >= 8) { "Passphrase must be at least 8 characters" }
        _passphraseBusy.value = true
        try {
            val verify = if (forceReplace) {
                PassVaultCloudVerifier.CloudVerifyResult.NO_CLOUD_VERIFIER
            } else {
                passVaultCloudVerifier.verifyAgainstCloud(clean)
            }
            if (verify == PassVaultCloudVerifier.CloudVerifyResult.MISMATCH) {
                _hasCloudVerifier.value = true
                return@withContext Result.success(VaultPassphraseSaveStatus.CLOUD_MISMATCH)
            }
            backupCrypto.setVaultPassphrase(clean)
            mediaThumbnailCache.clearDecryptedMemoryCache()
            _hasVaultPassphrase.value = true
            if (verify == PassVaultCloudVerifier.CloudVerifyResult.NO_CLOUD_VERIFIER || forceReplace) {
                runCatching { passVaultCloudVerifier.publishVerifier(clean) }
                    .onSuccess { _hasCloudVerifier.value = true }
            } else if (verify == PassVaultCloudVerifier.CloudVerifyResult.MATCHED) {
                _hasCloudVerifier.value = true
            }
            _decryptedThumbs.value = emptyMap()
            synchronized(attemptedThumbKeys) { attemptedThumbKeys.clear() }
            if (_isUnlocked.value && state.value.items.isNotEmpty()) {
                warmEncryptedThumbnails(state.value.items)
            }
            Result.success(VaultPassphraseSaveStatus.SAVED)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            _passphraseBusy.value = false
        }
    }

    fun startDecryptFlow(item: FileCacheEntity) {
        decryptFlow.start(
            item = item,
            resolveLocalFile = { getLocalPath(item.chatId, item.messageId)?.let(::File) },
            enqueueDownload = { enqueueDownload(item) },
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
        val selectedKeys = _selected.value
        val targets = state.value.items.filter {
            (it.chatId to it.messageId) in selectedKeys && BackupCrypto.isEncryptedFileName(it.fileName)
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
                    dl.status == DownloadStatus.PAUSED ||
                    dl.status == DownloadStatus.CANCELLED
            }
        return when (terminal?.status) {
            DownloadStatus.DONE -> terminal.localPath?.let(::File)?.takeIf { it.isFile }
            DownloadStatus.PAUSED -> {
                _error.value = terminal.errorMessage ?: "Download paused (waiting for Wi-Fi)"
                null
            }
            DownloadStatus.FAILED -> {
                _error.value = terminal.errorMessage ?: "Download failed"
                null
            }
            else -> null
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
        mediaThumbnailCache.clearDecryptedMemoryCache()
        synchronized(attemptedThumbKeys) { attemptedThumbKeys.clear() }
    }
}
