package com.tgdrive.feature.settings

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.tgdrive.core.audio.AudioPlayerController
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.ThemeMode
import com.tgdrive.core.db.dao.AlbumDao
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.PendingUploadDao
import com.tgdrive.core.db.dao.SyncLogDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.dao.ZipEntryDao
import com.tgdrive.core.download.DecryptedTempSweeper
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.PassVaultCloudVerifier
import com.tgdrive.core.download.UploadManager
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.BiometricHelper
import com.tgdrive.core.security.KeystoreHelper
import com.tgdrive.core.telegram.TelegramClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

enum class PassVaultSaveStatus {
    SAVED,
    CLOUD_MISMATCH,
}

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cacheLimitBytes: Long = 500L * 1024 * 1024,
    val biometricEnabled: Boolean = false,
    val apiId: String = "",
    val apiHash: String = "",
    val clearingCache: Boolean = false,
    val isEncryptionAvailable: Boolean = true,
    val keystoreRecoveryReset: Boolean = false,
    val cloudSyncing: Boolean = false,
    val lastCloudSyncMs: Long = 0L,
    val backupEncryptAll: Boolean = false,
    val hasVaultPassphrase: Boolean = false,
    val passphraseFingerprint: String? = null,
    val hasCloudVerifier: Boolean = false,
    val passVaultBusy: Boolean = false,
    val decryptedTempTtlMinutes: Int = 30,
    val manualUploadWifiOnly: Boolean = false,
    val biometricTimeoutSeconds: Int = 60,
    val blockScreenshotsEverywhere: Boolean = false,
    val showEncryptedPreviewsWhenUnlocked: Boolean = false,
    val downloadConcurrency: Int = 2,
    val downloadWifiOnly: Boolean = false,
    val autoSaveDownloadsToPublic: Boolean = false,
    val tempDecryptedCount: Int = 0,
    val tempDecryptedBytes: Long = 0L,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val keystore: KeystoreHelper,
    private val backupCrypto: BackupCrypto,
    private val biometricHelper: BiometricHelper,
    private val telegramClient: TelegramClient,
    private val downloadManager: DownloadManager,
    private val uploadManager: UploadManager,
    private val mediaThumbnailCache: MediaThumbnailCache,
    private val decryptedTempSweeper: DecryptedTempSweeper,
    private val passVaultCloudVerifier: PassVaultCloudVerifier,
    private val audioPlayerController: AudioPlayerController,
    private val folderDao: FolderDao,
    private val fileCacheDao: FileCacheDao,
    private val syncedFileDao: SyncedFileDao,
    private val syncLogDao: SyncLogDao,
    private val zipEntryDao: ZipEntryDao,
    private val trashEntryDao: TrashEntryDao,
    private val albumDao: AlbumDao,
    private val pendingUploadDao: PendingUploadDao,
    private val cloudSyncManager: CloudSyncManager,
    @ApplicationContext private val appContext: Context,
    private val dispatchers: DispatchersProvider,
    private val offlinePinStore: OfflinePinStore,
) : ViewModel() {

    private val _credsVersion = MutableStateFlow(0)
    private val _vaultVersion = MutableStateFlow(0)
    private val _clearingCache = MutableStateFlow(false)
    private val _tempStatsVersion = MutableStateFlow(0)
    private val _hasCloudVerifier = MutableStateFlow(false)
    private val _passVaultBusy = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> = combine(
        combine(preferences.data, _credsVersion, _vaultVersion) { p, c, v ->
            Triple(p, c, v)
        },
        combine(_clearingCache, cloudSyncManager.syncing, _tempStatsVersion, _hasCloudVerifier, _passVaultBusy) {
                clearing, syncing, _, hasVerifier, busy ->
            listOf(clearing, syncing, hasVerifier, busy)
        },
    ) { (p, _, _), aux ->
        val clearing = aux[0] as Boolean
        val syncing = aux[1] as Boolean
        val hasVerifier = aux[2] as Boolean
        val busy = aux[3] as Boolean
        val creds = keystore.getApiCreds()
        val fingerprint = runCatching { backupCrypto.passphraseFingerprint() }.getOrNull()
        val (tempCount, tempBytes) = runCatching { decryptedTempSweeper.tempCacheStats() }.getOrDefault(0 to 0L)
        SettingsUiState(
            themeMode = p.themeMode,
            cacheLimitBytes = p.cacheLimitBytes,
            biometricEnabled = p.biometricLockEnabled,
            apiId = creds?.apiId.orEmpty(),
            apiHash = creds?.apiHash.orEmpty(),
            clearingCache = clearing,
            isEncryptionAvailable = keystore.isEncryptionAvailable,
            keystoreRecoveryReset = keystore.hadRecoveryReset,
            cloudSyncing = syncing,
            lastCloudSyncMs = p.lastCloudSyncMs,
            backupEncryptAll = p.backupEncryptAll,
            hasVaultPassphrase = backupCrypto.hasVaultPassphrase(),
            passphraseFingerprint = fingerprint,
            hasCloudVerifier = hasVerifier,
            passVaultBusy = busy,
            decryptedTempTtlMinutes = p.decryptedTempTtlMinutes,
            manualUploadWifiOnly = p.manualUploadWifiOnly,
            biometricTimeoutSeconds = p.biometricTimeoutSeconds,
            blockScreenshotsEverywhere = p.blockScreenshotsEverywhere,
            showEncryptedPreviewsWhenUnlocked = p.showEncryptedPreviewsWhenUnlocked,
            downloadConcurrency = p.downloadConcurrency,
            downloadWifiOnly = p.downloadWifiOnly,
            autoSaveDownloadsToPublic = p.autoSaveDownloadsToPublic,
            tempDecryptedCount = tempCount,
            tempDecryptedBytes = tempBytes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun biometricAvailability(): BiometricHelper.Availability = biometricHelper.availability()

    fun authenticateForBiometricToggle(
        activity: FragmentActivity,
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

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch(dispatchers.io) {
            preferences.update { it.copy(themeMode = mode) }
        }
    }

    fun setCacheLimit(bytes: Long) {
        viewModelScope.launch(dispatchers.io) {
            preferences.update { it.copy(cacheLimitBytes = bytes) }
            runCatching { telegramClient.optimizeStorage(bytes) }
        }
    }

    fun setBiometric(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            preferences.update { it.copy(biometricLockEnabled = enabled, biometricLastBgTime = 0L) }
        }
    }

    fun saveApiCreds(id: String, hash: String) {
        keystore.saveApiCreds(id, hash)
        _credsVersion.value += 1
    }

    fun setBackupEncryptAll(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            preferences.update { it.copy(backupEncryptAll = enabled) }
        }
    }

    fun refreshCloudVerifierStatus() {
        viewModelScope.launch(dispatchers.io) {
            _hasCloudVerifier.value = runCatching { passVaultCloudVerifier.hasCloudVerifier() }.getOrDefault(false)
        }
    }

    suspend fun saveVaultPassphrase(
        passphrase: String,
        forceReplace: Boolean = false,
    ): Result<PassVaultSaveStatus> = withContext(dispatchers.io) {
        val clean = passphrase.trim()
        require(clean.length >= 8) { "Passphrase must be at least 8 characters" }
        _passVaultBusy.value = true
        try {
            val verify = if (forceReplace) {
                PassVaultCloudVerifier.CloudVerifyResult.NO_CLOUD_VERIFIER
            } else {
                passVaultCloudVerifier.verifyAgainstCloud(clean)
            }
            if (verify == PassVaultCloudVerifier.CloudVerifyResult.MISMATCH) {
                _hasCloudVerifier.value = true
                return@withContext Result.success(PassVaultSaveStatus.CLOUD_MISMATCH)
            }
            backupCrypto.setVaultPassphrase(clean)
            mediaThumbnailCache.clearDecryptedMemoryCache()
            if (verify == PassVaultCloudVerifier.CloudVerifyResult.NO_CLOUD_VERIFIER || forceReplace) {
                runCatching { passVaultCloudVerifier.publishVerifier(clean) }
                    .onSuccess { _hasCloudVerifier.value = true }
            } else if (verify == PassVaultCloudVerifier.CloudVerifyResult.MATCHED) {
                _hasCloudVerifier.value = true
            }
            _vaultVersion.value += 1
            Result.success(PassVaultSaveStatus.SAVED)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            _passVaultBusy.value = false
        }
    }

    fun clearVaultPassphrase() {
        viewModelScope.launch(dispatchers.io) {
            backupCrypto.setVaultPassphrase(null)
            mediaThumbnailCache.clearDecryptedMemoryCache()
            _vaultVersion.value += 1
        }
    }

    suspend fun clearDecryptedTempFiles(): Int = withContext(dispatchers.io) {
        val activeAudio = audioPlayerController.state.value.path
        val removed = decryptedTempSweeper.wipeAll(excludePaths = setOfNotNull(activeAudio))
        _tempStatsVersion.value += 1
        removed
    }

    fun setDecryptedTempTtlMinutes(minutes: Int) {
        viewModelScope.launch(dispatchers.io) {
            preferences.update { it.copy(decryptedTempTtlMinutes = minutes) }
            if (minutes >= 0) {
                val activeAudio = audioPlayerController.state.value.path
                decryptedTempSweeper.sweepExpired(minutes, excludePaths = setOfNotNull(activeAudio))
            }
            _tempStatsVersion.value += 1
        }
    }

    fun setManualUploadWifiOnly(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            preferences.update { it.copy(manualUploadWifiOnly = enabled) }
        }
    }

    fun setBiometricTimeoutSeconds(seconds: Int) {
        viewModelScope.launch(dispatchers.io) {
            preferences.update { it.copy(biometricTimeoutSeconds = seconds.coerceAtLeast(0)) }
        }
    }

    fun setBlockScreenshotsEverywhere(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            preferences.update { it.copy(blockScreenshotsEverywhere = enabled) }
        }
    }

    fun setShowEncryptedPreviewsWhenUnlocked(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            preferences.update { it.copy(showEncryptedPreviewsWhenUnlocked = enabled) }
        }
    }

    fun setDownloadConcurrency(concurrency: Int) {
        viewModelScope.launch(dispatchers.io) {
            preferences.update { it.copy(downloadConcurrency = concurrency.coerceIn(1, 4)) }
        }
    }

    fun setDownloadWifiOnly(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            preferences.update { it.copy(downloadWifiOnly = enabled) }
        }
    }

    fun setAutoSaveDownloadsToPublic(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            preferences.update { it.copy(autoSaveDownloadsToPublic = enabled) }
        }
    }

    fun refreshTempStats() {
        _tempStatsVersion.value += 1
    }

    fun syncNow() {
        viewModelScope.launch(dispatchers.io) {
            try {
                cloudSyncManager.syncNow()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("SettingsViewModel: syncNow failed", t)
            }
        }
    }

    /**
     * Perform complete logout on a ViewModel-independent scope + [NonCancellable]
     * so navigation away from `"main"` never cancels mid-cleanup, and logs out
     * TDLib cleanly before removing session files.
     */
    private val loggingOut = AtomicBoolean(false)

    fun logout() {
        if (!loggingOut.compareAndSet(false, true)) return
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            try {
                withContext(NonCancellable) {
                    Log.i("SettingsViewModel: starting full logout")

                    // 1. Stop background workers, clear pins BEFORE clearing download queue, and stop audio playback.
                    try { WorkManager.getInstance(appContext).cancelAllWork() } catch (t: Throwable) {
                        Log.w("logout: cancelAllWork failed", t)
                    }
                    try { offlinePinStore.clearAll() } catch (t: Throwable) {
                        Log.w("logout: offlinePinStore.clearAll failed", t)
                    }
                    try { uploadManager.cancelAll() } catch (t: Throwable) {
                        Log.w("logout: uploadManager.cancelAll failed", t)
                    }
                    try { downloadManager.clearCache() } catch (t: Throwable) {
                        Log.w("logout: downloadManager.clearCache failed", t)
                    }
                    try { cloudSyncManager.clearInMemoryState() } catch (t: Throwable) {
                        Log.w("logout: cloudSyncManager.clearInMemoryState failed", t)
                    }
                    try { audioPlayerController.release() } catch (t: Throwable) {
                        Log.w("logout: audio release failed", t)
                    }

                    // 2. Ask TDLib to clear cache & log out BEFORE deleting session directory.
                    try {
                        withTimeoutOrNull(5_000L) { telegramClient.clearDownloadCache() }
                    } catch (t: Throwable) {
                        Log.w("logout: clearDownloadCache failed", t)
                    }
                    try {
                        withTimeoutOrNull(5_000L) { telegramClient.logout() }
                    } catch (t: Throwable) {
                        Log.w("logout: telegramClient.logout failed", t)
                    }

                    // 3. Clear Room tables.
                    try { folderDao.clear() } catch (t: Throwable) { Log.w("logout: folderDao.clear failed", t) }
                    try { fileCacheDao.clear() } catch (t: Throwable) { Log.w("logout: fileCacheDao.clear failed", t) }
                    try { syncedFileDao.clear() } catch (t: Throwable) { Log.w("logout: syncedFileDao.clear failed", t) }
                    try { syncLogDao.clear() } catch (t: Throwable) { Log.w("logout: syncLogDao.clear failed", t) }
                    try { zipEntryDao.clear() } catch (t: Throwable) { Log.w("logout: zipEntryDao.clear failed", t) }
                    try { trashEntryDao.clear() } catch (t: Throwable) { Log.w("logout: trashEntryDao.clear failed", t) }
                    try { albumDao.clear() } catch (t: Throwable) { Log.w("logout: albumDao.clear failed", t) }
                    try { pendingUploadDao.clear() } catch (t: Throwable) { Log.w("logout: pendingUploadDao.clear failed", t) }

                    // 4. Delete TDLib directory, Keystore secrets, and reset DataStore preferences.
                    try {
                        val tdlibDir = File(appContext.filesDir, "tgdrive_tdlib")
                        if (tdlibDir.exists()) {
                            tdlibDir.deleteRecursively()
                        }
                    } catch (t: Throwable) {
                        Log.w("logout: could not delete TDLib session dir", t)
                    }
                    try { keystore.clear() } catch (t: Throwable) { Log.w("logout: keystore.clear failed", t) }
                    try {
                        preferences.resetUserSession()
                    } catch (t: Throwable) {
                        Log.w("logout: preferences clear failed", t)
                    }

                    // 5. Purge all local cache / staging directories.
                    try { decryptedTempSweeper.wipeAll() } catch (_: Throwable) {}
                    for (
                        dirName in listOf(
                            "backup_staging",
                            "upload_staging",
                            "decrypted",
                            "downloads",
                            "image_cache",
                            "zip_extracts",
                            "share_staging",
                            "pdf_thumbs",
                            "zip_thumbs",
                            "cloud_sync",
                            "media_thumbs",
                            "minithumbs",
                            "upload_thumbs",
                            "encrypted_thumbs",
                            "vault_sync",
                            "text_edits",
                            "crop_tmp",
                            "scan_tmp",
                        )
                    ) {
                        try { File(appContext.cacheDir, dirName).deleteRecursively() } catch (_: Throwable) {}
                    }
                    try {
                        appContext.cacheDir.listFiles()?.forEach { f ->
                            if (f.name.startsWith("zip_export_")) {
                                runCatching { f.deleteRecursively() }
                            }
                        }
                    } catch (_: Throwable) {}

                    _credsVersion.value += 1
                    Log.i("SettingsViewModel: logout complete")
                }
            } finally {
                loggingOut.set(false)
            }
        }
    }

    suspend fun clearCacheAndAwait() {
        _clearingCache.value = true
        try {
            withContext(dispatchers.io) {
                telegramClient.clearDownloadCache()
            }
            Log.i("SettingsViewModel: cache cleared")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("SettingsViewModel: clearCache failed", t)
        } finally {
            _clearingCache.value = false
        }
    }

    fun clearCache() {
        viewModelScope.launch { clearCacheAndAwait() }
    }

    suspend fun offlinePinStats(): Pair<Int, Long> = offlinePinStore.stats()

    suspend fun clearOfflinePins() {
        withContext(dispatchers.io) {
            offlinePinStore.clearAll()
        }
    }
}
