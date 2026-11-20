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
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncLogDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.security.BiometricHelper
import com.tgdrive.core.security.KeystoreHelper
import com.tgdrive.core.telegram.TelegramClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferences: AppPreferences,
    private val keystore: KeystoreHelper,
    private val biometricHelper: BiometricHelper,
    private val telegramClient: TelegramClient,
    private val downloadManager: DownloadManager,
    private val audioPlayerController: AudioPlayerController,
    private val folderDao: FolderDao,
    private val fileCacheDao: FileCacheDao,
    private val syncedFileDao: SyncedFileDao,
    private val syncLogDao: SyncLogDao,
    @ApplicationContext private val appContext: Context,
    private val dispatchers: DispatchersProvider,
) : ViewModel() {

    private val _credsVersion = MutableStateFlow(0)
    private val _clearingCache = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> = combine(
        preferences.data,
        _credsVersion,
        _clearingCache,
    ) { p, _, clearing ->
        val creds = keystore.getApiCreds()
        SettingsUiState(
            themeMode = p.themeMode,
            cacheLimitBytes = p.cacheLimitBytes,
            biometricEnabled = p.biometricLockEnabled,
            apiId = creds?.apiId.orEmpty(),
            apiHash = creds?.apiHash.orEmpty(),
            clearingCache = clearing,
            isEncryptionAvailable = keystore.isEncryptionAvailable,
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
                        )
                    }
                } catch (t: Throwable) {
                    Log.w("logout: preferences clear failed", t)
                }

                // 5. Purge all local cache / staging directories.
                for (dirName in listOf("backup_staging", "upload_staging", "downloads", "image_cache", "zip_extracts", "share_staging")) {
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
}
