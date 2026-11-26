package com.tgdrive.feature.browser

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.tgdrive.core.audio.AudioPlayerController
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.isTextPreviewable
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DecryptOutcome
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.PassVaultCloudVerifier
import com.tgdrive.core.download.UploadManager
import com.tgdrive.core.download.ai.MediaAiIndexWorker
import com.tgdrive.core.download.ai.MediaAiIndexer
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.AccessLockManager
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.BiometricHelper
import com.tgdrive.feature.browser.search.SearchFilterCriteria
import com.tgdrive.feature.browser.search.SmartSearchEngine
import com.tgdrive.feature.browser.search.SmartSearchHit
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class VaultExternalOpenEvent(
    val id: Long,
    val file: File,
    val mimeType: String,
)

@Immutable
data class VaultUiState(
    val items: List<FileCacheEntity> = emptyList(),
    val filteredItems: List<FileCacheEntity> = emptyList(),
    val searchQuery: String = "",
    val interpretedChips: List<String> = emptyList(),
    val matchHints: Map<Pair<Long, Long>, String> = emptyMap(),
    val unindexedAiCount: Int = 0,
    val aiIndexingProgress: Pair<Int, Int>? = null,
    val selectedIds: Set<Pair<Long, Long>> = emptySet(),
    val biometricRequired: Boolean = false,
    val isUnlocked: Boolean = false,
    val ready: Boolean = false,
    val passphraseFingerprint: String? = null,
    val decryptDialog: DecryptDialogState? = null,
    val hasVaultPassphrase: Boolean = false,
    val hasCloudVerifier: Boolean = false,
    val passphraseBusy: Boolean = false,
    val batchProgress: Pair<Int, Int>? = null,
    val vaultAccessLockEnabled: Boolean = false,
    val hasAccessPassword: Boolean = false,
    val canUseBiometricLock: Boolean = false,
    val accessLockBiometricEnabled: Boolean = true,
    val biometricAvailable: Boolean = false,
    val error: String? = null,
)

enum class VaultPassphraseSaveStatus {
    SAVED,
    CLOUD_MISMATCH,
}

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val fileCacheDao: FileCacheDao,
    private val folderDao: FolderDao,
    private val preferences: AppPreferences,
    private val biometricHelper: BiometricHelper,
    private val accessLockManager: AccessLockManager,
    private val mediaThumbnailCache: MediaThumbnailCache,
    val downloadManager: DownloadManager,
    private val offlinePinStore: OfflinePinStore,
    private val uploadManager: UploadManager,
    private val backupCrypto: BackupCrypto,
    private val passVaultCloudVerifier: PassVaultCloudVerifier,
    private val cloudSyncManager: CloudSyncManager,
    private val mediaAiIndexer: MediaAiIndexer,
    private val audioPlayerController: AudioPlayerController,
    private val dispatchers: DispatchersProvider,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _selected = MutableStateFlow<Set<Pair<Long, Long>>>(emptySet())
    private val _biometricRequired = MutableStateFlow(false)
    private val _isUnlocked = MutableStateFlow(false)
    private val _ready = MutableStateFlow(false)
    private val _passphraseFingerprint = MutableStateFlow(backupCrypto.passphraseFingerprint())
    private val _hasVaultPassphrase = MutableStateFlow(backupCrypto.hasVaultPassphrase())
    private val _hasCloudVerifier = MutableStateFlow(false)
    private val _passphraseBusy = MutableStateFlow(false)
    private val _batchProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _unindexedAiCount = MutableStateFlow(0)
    private val _aiIndexingProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    private val _lockConfigVersion = MutableStateFlow(0)
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

    private var initialLockEvaluated = false

    private data class VaultAiSearchState(
        val searchQuery: String,
        val unindexedAiCount: Int,
        val aiIndexingProgress: Pair<Int, Int>?,
        val passphraseFingerprint: String?,
        val folderNames: Map<Long, String>,
    )

    private data class VaultLockMeta(
        val vaultAccessLockEnabled: Boolean,
        val hasAccessPassword: Boolean,
        val canUseBiometric: Boolean,
        val accessLockBiometricEnabled: Boolean,
        val biometricAvailable: Boolean,
    )

    private val lockMetaFlow = combine(preferences.data, _lockConfigVersion) { prefs, _ ->
        val bioAvail = biometricHelper.availability() == BiometricHelper.Availability.AVAILABLE
        val hasPass = accessLockManager.hasPassword()
        VaultLockMeta(
            vaultAccessLockEnabled = prefs.vaultAccessLockEnabled,
            hasAccessPassword = hasPass,
            canUseBiometric = bioAvail && (prefs.biometricLockEnabled || (prefs.vaultAccessLockEnabled && prefs.accessLockBiometricEnabled)),
            accessLockBiometricEnabled = prefs.accessLockBiometricEnabled,
            biometricAvailable = bioAvail,
        )
    }

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
            decryptFlow.dialog,
            _batchProgress,
            _error,
            combine(_hasVaultPassphrase, _hasCloudVerifier, _passphraseBusy) { hasPass, hasVer, busy ->
                Triple(hasPass, hasVer, busy)
            },
            lockMetaFlow,
        ) { dialog, batch, err, passTuple, lockMeta ->
            listOf(dialog, batch, err, passTuple, lockMeta)
        },
        combine(
            _searchQuery,
            _unindexedAiCount,
            _aiIndexingProgress,
            _passphraseFingerprint,
            folderDao.observeAll(),
        ) { sq, unindexed, aiProg, fp, folders ->
            val folderNames = folders.associate { it.channelId to it.title }
            VaultAiSearchState(sq, unindexed, aiProg, fp, folderNames)
        },
    ) { core, aux, aiSearch ->
        @Suppress("UNCHECKED_CAST")
        val items = core[0] as List<FileCacheEntity>
        @Suppress("UNCHECKED_CAST")
        val selected = core[1] as Set<Pair<Long, Long>>
        val bioReq = core[2] as Boolean
        val unlocked = core[3] as Boolean
        val ready = core[4] as Boolean

        val dialog = aux[0] as DecryptDialogState?
        @Suppress("UNCHECKED_CAST")
        val batch = aux[1] as Pair<Int, Int>?
        val err = aux[2] as String?
        @Suppress("UNCHECKED_CAST")
        val passTuple = aux[3] as Triple<Boolean, Boolean, Boolean>
        val lockMeta = aux[4] as VaultLockMeta

        val q = aiSearch.searchQuery
        val parsedQuery = if (q.isBlank()) {
            null
        } else {
            SmartSearchEngine.parseQuery(q, knownFolderNames = aiSearch.folderNames.values)
        }
        val criteria = SearchFilterCriteria(folderNames = aiSearch.folderNames)
        val searchHits = if (q.isBlank()) {
            null
        } else {
            SmartSearchEngine.search(
                query = q,
                files = items,
                zipEntries = emptyList(),
                criteria = criteria,
                includeLocked = true,
            ).filterIsInstance<SmartSearchHit.CloudFileHit>()
        }
        val filtered = searchHits?.map { it.entity } ?: items
        val hints = searchHits
            ?.mapNotNull { hit -> hit.matchHint?.let { (hit.entity.chatId to hit.entity.messageId) to it } }
            ?.toMap()
            ?: emptyMap()

        VaultUiState(
            items = items,
            filteredItems = filtered,
            searchQuery = q,
            interpretedChips = parsedQuery?.interpretedChips ?: emptyList(),
            matchHints = hints,
            unindexedAiCount = aiSearch.unindexedAiCount,
            aiIndexingProgress = aiSearch.aiIndexingProgress,
            selectedIds = selected,
            biometricRequired = bioReq,
            isUnlocked = unlocked,
            ready = ready,
            passphraseFingerprint = if (unlocked) aiSearch.passphraseFingerprint else null,
            decryptDialog = dialog,
            hasVaultPassphrase = passTuple.first,
            hasCloudVerifier = passTuple.second,
            passphraseBusy = passTuple.third,
            batchProgress = batch,
            vaultAccessLockEnabled = lockMeta.vaultAccessLockEnabled,
            hasAccessPassword = lockMeta.hasAccessPassword,
            canUseBiometricLock = lockMeta.canUseBiometric,
            accessLockBiometricEnabled = lockMeta.accessLockBiometricEnabled,
            biometricAvailable = lockMeta.biometricAvailable,
            error = err,
        )
    }.flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VaultUiState())

    init {
        viewModelScope.launch(dispatchers.io) {
            _hasCloudVerifier.value = runCatching { passVaultCloudVerifier.hasCloudVerifier() }.getOrDefault(false)
            refreshUnindexedAiCount()
        }
        viewModelScope.launch(dispatchers.io) {
            combine(
                fileCacheDao.observeEncryptedVaultFiles().map { it.size }.distinctUntilChanged(),
                _isUnlocked,
                _hasVaultPassphrase,
            ) { _, unlocked, hasPass -> unlocked && hasPass }
                .distinctUntilChanged()
                .collect {
                    refreshUnindexedAiCount()
                }
        }
        viewModelScope.launch(dispatchers.io) {
            combine(preferences.data, _lockConfigVersion) { prefs, _ -> prefs }.collect { prefs ->
                val bioAvail = biometricHelper.availability() == BiometricHelper.Availability.AVAILABLE
                val hasPass = accessLockManager.hasPassword()
                val customLock = prefs.vaultAccessLockEnabled && (hasPass || (prefs.accessLockBiometricEnabled && bioAvail))
                val globalBioLock = prefs.biometricLockEnabled && bioAvail
                val required = customLock || globalBioLock
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
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
    ) {
        if (biometricHelper.availability() != BiometricHelper.Availability.AVAILABLE) {
            if (!accessLockManager.hasPassword()) {
                _isUnlocked.value = true
            }
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

    fun unlockWithPassword(candidate: String) {
        viewModelScope.launch(dispatchers.default) {
            val ok = accessLockManager.verifyPassword(candidate)
            if (ok) {
                _error.value = null
                _isUnlocked.value = true
            } else {
                _error.value = appContext.getString(R.string.access_lock_wrong_password)
            }
        }
    }

    fun configureVaultLock(
        enableVaultLock: Boolean,
        newPassword: String?,
        biometricEnabled: Boolean,
        removePassword: Boolean = false,
    ) {
        viewModelScope.launch(dispatchers.io) {
            if (removePassword) {
                accessLockManager.clearPassword()
            } else if (!newPassword.isNullOrBlank()) {
                accessLockManager.setPassword(newPassword)
            }
            preferences.update { prefs ->
                prefs.copy(
                    vaultAccessLockEnabled = enableVaultLock,
                    accessLockBiometricEnabled = biometricEnabled,
                )
            }
            _lockConfigVersion.value += 1
        }
    }

    @OptIn(ExperimentalCoilApi::class)
    fun lock() {
        _isUnlocked.value = false
        _selected.value = emptySet()
        mediaThumbnailCache.clearDecryptedMemoryCache()
        runCatching { appContext.imageLoader.memoryCache?.clear() }
    }

    fun toggleSelection(chatId: Long, messageId: Long) {
        val key = chatId to messageId
        _selected.update { cur -> if (key in cur) cur - key else cur + key }
    }

    fun selectAll() {
        val visible = state.value.filteredItems.mapTo(mutableSetOf()) { it.chatId to it.messageId }
        _selected.value = if (_selected.value.size == visible.size && visible.isNotEmpty()) emptySet() else visible
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun indexVaultAi() {
        if (_aiIndexingProgress.value != null || !backupCrypto.hasVaultPassphrase()) return
        viewModelScope.launch(dispatchers.io) {
            val pending = runCatching {
                mediaAiIndexer.countUnindexed(includeLocked = true, vaultOnly = true)
            }.getOrDefault(0)
            if (pending == 0) {
                _unindexedAiCount.value = 0
                return@launch
            }
            _aiIndexingProgress.value = 0 to pending.coerceAtMost(50)
            try {
                mediaAiIndexer.indexUnindexedBatch(
                    maxCount = 50,
                    includeLocked = true,
                    vaultOnly = true,
                ) { done, total ->
                    _aiIndexingProgress.value = done to total
                }
            } finally {
                _aiIndexingProgress.value = null
                refreshUnindexedAiCount()
            }
        }
    }

    private suspend fun refreshUnindexedAiCount() {
        val pending = if (backupCrypto.hasVaultPassphrase()) {
            runCatching { mediaAiIndexer.countUnindexed(includeLocked = true, vaultOnly = true) }.getOrDefault(0)
        } else {
            0
        }
        _unindexedAiCount.value = pending
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

    @OptIn(ExperimentalCoilApi::class)
    fun saveVaultPassphraseQuietly(passphrase: String) {
        val clean = passphrase.trim()
        if (clean.length < 8) return
        viewModelScope.launch(dispatchers.io) {
            backupCrypto.setVaultPassphrase(clean)
            mediaThumbnailCache.clearDecryptedMemoryCache()
            runCatching { appContext.imageLoader.memoryCache?.clear() }
            _hasVaultPassphrase.value = true
            _passphraseFingerprint.value = backupCrypto.passphraseFingerprint()
            MediaAiIndexWorker.enqueueSoon(appContext)
            refreshUnindexedAiCount()
        }
    }

    @OptIn(ExperimentalCoilApi::class)
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
            runCatching { appContext.imageLoader.memoryCache?.clear() }
            _hasVaultPassphrase.value = true
            _passphraseFingerprint.value = backupCrypto.passphraseFingerprint()
            if (verify == PassVaultCloudVerifier.CloudVerifyResult.NO_CLOUD_VERIFIER || forceReplace) {
                runCatching { passVaultCloudVerifier.publishVerifier(clean) }
                    .onSuccess { _hasCloudVerifier.value = true }
            } else if (verify == PassVaultCloudVerifier.CloudVerifyResult.MATCHED) {
                _hasCloudVerifier.value = true
            }
            MediaAiIndexWorker.enqueueSoon(appContext)
            refreshUnindexedAiCount()
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
        mediaThumbnailCache.clearDecryptedMemoryCache()
    }
}
