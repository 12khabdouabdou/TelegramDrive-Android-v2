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
import com.tgdrive.core.db.dao.SyncLogDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.dao.ZipEntryDao
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.OfflinePinStore
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
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val cacheLimitBytes: Long = 500L * 1024 * 1024,
    val biometricEnabled: Boolean = false,
    val apiId: String = "",
    val apiHash: String = "",
    val clearingCache: Boolean = false,
    val isEncryptionAvailable: Boolean = true,
    val cloudSyncing: Boolean = false,
    val lastCloudSyncMs: Long = 0L,
    val backupEncryptAll: Boolean = false,
    val backupUseCustomPassphrase: Boolean = false,
    val hasCustomPassphrase: Boolean = false,
    val vaultKeyFingerprint: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val keystore: KeystoreHelper,
    private val backupCrypto: BackupCrypto,
    private val biometricHelper: BiometricHelper,
    private val telegramClient: TelegramClient,
    private val downloadManager: DownloadManager,
    private val audioPlayerController: AudioPlayerController,
    private val folderDao: FolderDao,
    private val fileCacheDao: FileCacheDao,
    private val syncedFileDao: SyncedFileDao,
    private val syncLogDao: SyncLogDao,
    private val zipEntryDao: ZipEntryDao,
    private val trashEntryDao: TrashEntryDao,
    private val albumDao: AlbumDao,
    private val cloudSyncManager: CloudSyncManager,
    @ApplicationContext private val appContext: Context,
    private val dispatchers: DispatchersProvider,
    private val offlinePinStore: OfflinePinStore,
) : ViewModel() {

    private val _credsVersion = MutableStateFlow(0)
    private val _vaultVersion = MutableStateFlow(0)
    private val _clearingCache = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> = combine(
        preferences.data,
        _credsVersion,
        _vaultVersion,
        _clearingCache,
        cloudSyncManager.syncing,
    ) { p, _, _, clearing, syncing ->
        val creds = keystore.getApiCreds()
        val fingerprint = runCatching { backupCrypto.vaultKeyFingerprint() }.getOrDefault("")
        SettingsUiState(
            themeMode = p.themeMode,
            cacheLimitBytes = p.cacheLimitBytes,
            biometricEnabled = p.biometricLockEnabled,
            apiId = creds?.apiId.orEmpty(),
            apiHash = creds?.apiHash.orEmpty(),
            clearingCache = clearing,
            isEncryptionAvailable = keystore.isEncryptionAvailable,
            cloudSyncing = syncing,
            lastCloudSyncMs = p.lastCloudSyncMs,
            backupEncryptAll = p.backupEncryptAll,
            backupUseCustomPassphrase = p.backupUseCustomPassphrase,
            hasCustomPassphrase = backupCrypto.hasCustomBackupPassphrase(),
            vaultKeyFingerprint = fingerprint,
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

    fun saveBackupPassphrase(passphrase: String) {
        val clean = passphrase.trim()
        if (clean.length < 4) return
        viewModelScope.launch(dispatchers.io) {
            backupCrypto.setCustomBackupPassphrase(clean)
            preferences.update { it.copy(backupUseCustomPassphrase = true) }
            _vaultVersion.value += 1
        }
    }

    fun clearBackupPassphrase() {
        viewModelScope.launch(dispatchers.io) {
            backupCrypto.setCustomBackupPassphrase(null)
            preferences.update { it.copy(backupUseCustomPassphrase = false) }
            _vaultVersion.value += 1
        }
    }

    fun exportVaultKey(): String = backupCrypto.exportVaultKeyBase64()

    fun importVaultKey(encoded: String): Boolean {
        val ok = backupCrypto.importVaultKeyBase64(encoded)
        if (ok) _vaultVersion.value += 1
        return ok
    }

    suspend fun clearDecryptedTempFiles(): Int = withContext(dispatchers.io) {
        val dir = File(appContext.cacheDir, "decrypted")
        if (!dir.isDirectory) return@withContext 0
        val children = dir.listFiles().orEmpty()
        var removed = 0
        children.forEach { child -> if (child.deleteRecursively()) removed++ }
        removed
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
    fun logout() {
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            withContext(NonCancellable) {
                Log.i("SettingsViewModel: starting full logout")

                // 1. Stop background workers, active downloads, and audio playback first.
                try { WorkManager.getInstance(appContext).cancelAllWork() } catch (t: Throwable) {
                    Log.w("logout: cancelAllWork failed", t)
                }
                try { downloadManager.clearCache() } catch (t: Throwable) {
                    Log.w("logout: downloadManager.clearCache failed", t)
                }
                try { audioPlayerController.release() } catch (t: Throwable) {
                    Log.w("logout: audio release failed", t)
                }

                // 2. Ask TDLib to clear cache & log out BEFORE deleting session directory.
                try { telegramClient.clearDownloadCache() } catch (t: Throwable) {
                    Log.w("logout: clearDownloadCache failed", t)
                }
                try { telegramClient.logout() } catch (t: Throwable) {
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
                try { offlinePinStore.clearAll() } catch (t: Throwable) { Log.w("logout: offlinePinStore.clearAll failed", t) }

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
                    preferences.update {
                        it.copy(
                            biometricLockEnabled = false,
                            biometricLastBgTime = 0L,
                            backupEnabled = false,
                            backupFolderUris = emptySet(),
                            backupTargetFolderId = -1L,
                            backupPaused = false,
                            backupStrictMode = false,
                            backupDeleteAfterUpload = false,
                            backupZipIncremental = false,
                            backupEncryptAll = false,
                            backupUseCustomPassphrase = false,
                            backupFolderTargetsJson = "{}",
                            recentSearchQueries = "",
                            lastCloudSyncMs = 0L,
                        )
                    }
                } catch (t: Throwable) {
                    Log.w("logout: preferences clear failed", t)
                }

                // 5. Purge all local cache / staging directories.
                for (dirName in listOf("backup_staging", "upload_staging", "decrypted", "downloads", "image_cache", "zip_extracts", "share_staging", "pdf_thumbs", "cloud_sync", "media_thumbs", "minithumbs", "upload_thumbs")) {
                    try { File(appContext.cacheDir, dirName).deleteRecursively() } catch (_: Throwable) {}
                }

                _credsVersion.value += 1
                Log.i("SettingsViewModel: logout complete")
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
