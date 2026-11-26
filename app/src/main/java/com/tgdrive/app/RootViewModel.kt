package com.tgdrive.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.audio.AudioPlayerController
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.NetworkMonitor
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.ThemeMode
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.download.DecryptedTempSweeper
import com.tgdrive.core.download.NormalUploadOptions
import com.tgdrive.core.download.UploadConflictStrategy
import com.tgdrive.core.download.UploadEncryptionSpec
import com.tgdrive.core.download.UploadManager
import com.tgdrive.core.download.ZipUploadRequest
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.security.KeystoreHelper
import com.tgdrive.core.telegram.AuthorizationState
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.zip.ZipCompressionMode
import com.tgdrive.core.zip.ZipUploader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Root-level UI state.
 *
 * ## The loading-screen bug (v0.11.1 and earlier)
 *
 * Previously `isInitializing` was bound to `authState is WaitInitialization`.
 * On a fresh install, TDLib is never initialized (no stored creds), so
 * `authState` stays at `WaitInitialization` FOREVER → the loading spinner
 * never disappears → the user can never reach the login screen.
 *
 * Even on a returning install, there was a race: the loading screen blocked
 * the UI until TDLib fired its first `UpdateAuthorizationState`, which could
 * take seconds while TDLib loads its binlog DB.
 *
 * ## The fix (v0.11.2)
 *
 * `isInitializing` is now driven by a TIME-BOUNDED splash: it starts `true`
 * and flips to `false` after [SPLASH_TIMEOUT_MS] (1.5s) regardless of TDLib
 * state. This guarantees the user always reaches the auth screen within 1.5s.
 *
 * If TDLib reaches a non-`WaitInitialization` state BEFORE the timeout, we
 * flip `isInitializing` to `false` early (so returning users with a valid
 * session skip the splash almost instantly).
 */
data class ShareTargetFolder(
    val chatId: Long,
    val title: String,
)

data class RootState(
    val isAuthorized: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val biometricRequired: Boolean = false,
    val biometricUnlocked: Boolean = true,
    val isOnline: Boolean = true,
    val isInitializing: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val hasStoredApiCreds: Boolean = false,
    val shareFolders: List<ShareTargetFolder> = emptyList(),
    /** URIs received via ACTION_SEND / ACTION_SEND_MULTIPLE intent.
     *  Non-empty -> MainBottomNav shows a target folder + upload dialog.
     *  Cleared by [confirmShareUpload] / [cancelShareUpload]. */
    val pendingShareUris: List<String> = emptyList(),
    /** True while a share-upload is in flight. Disables the dialog buttons. */
    val shareUploadInProgress: Boolean = false,
    /** Last share-upload result message (shown briefly in the dialog). */
    val shareUploadMessage: String? = null,
    val manualUploadWifiOnly: Boolean = false,
    val hasSavedPassphrase: Boolean = false,
    val decryptedTempTtlMinutes: Int = 30,
    val blockScreenshotsEverywhere: Boolean = false,
    val pendingShortcutRoute: String? = null,
)

@HiltViewModel
class RootViewModel @Inject constructor(
    private val telegramClient: TelegramClient,
    private val preferences: AppPreferences,
    private val networkMonitor: NetworkMonitor,
    private val keystoreHelper: KeystoreHelper,
    private val backupCrypto: BackupCrypto,
    private val cloudSyncManager: CloudSyncManager,
    private val fileCacheDao: FileCacheDao,
    private val folderDao: FolderDao,
    private val uploadManager: UploadManager,
    private val decryptedTempSweeper: DecryptedTempSweeper,
    private val audioPlayerController: AudioPlayerController,
    private val dispatchers: DispatchersProvider,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val _state = MutableStateFlow(RootState())
    val state: StateFlow<RootState> = _state.asStateFlow()

    // Shadow flags for biometric lock & persisted background timeout.
    @Volatile private var biometricPrefEnabled = false
    @Volatile private var biometricLastBgTimeMs = 0L
    @Volatile private var biometricTimeoutSeconds = 0
    @Volatile private var decryptedTempTtlMinutes = 30
    @Volatile private var initialPrefSynced = false
    @Volatile private var authSettled = false
    @Volatile private var cloudSyncedForSession = false

    init {
        observeAuth()
        observeThumbnailUpdates()
        observePrefs()
        observeShareFolders()
        observeNetwork()
        startSplashTimer()
        checkStoredCreds()
        sweepExpiredTempFiles()
    }

    private fun observeShareFolders() {
        viewModelScope.launch(dispatchers.io) {
            folderDao.observeAll().collect { folders ->
                val savedId = runCatching { telegramClient.myUserId() }.getOrDefault(0L)
                val list = buildList {
                    if (savedId != 0L) add(ShareTargetFolder(savedId, "Saved Messages"))
                    folders.forEach { add(ShareTargetFolder(it.channelId, it.title)) }
                }
                _state.update { it.copy(shareFolders = list) }
            }
        }
    }

    private fun observeThumbnailUpdates() {
        viewModelScope.launch(dispatchers.io) {
            telegramClient.thumbnailUpdates.collect { upd ->
                try {
                    fileCacheDao.updateThumbnailPath(upd.chatId, upd.messageId, upd.localPath)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("RootViewModel: updateThumbnailPath failed", t)
                }
            }
        }
    }

    /**
     * Observe TDLib auth state. Updates `isAuthorized` and `biometricRequired`.
     *
     * Also flips `isInitializing` to `false` as soon as TDLib transitions
     * OUT of `WaitInitialization` — this lets returning users with a valid
     * session skip the splash almost instantly (no need to wait the full
     * 1.5s timeout).
     */
    private fun observeAuth() {
        viewModelScope.launch(dispatchers.io) {
            telegramClient.authState.collect { st ->
                authSettled = st !is AuthorizationState.WaitInitialization
                _state.update { current ->
                    current.copy(
                        isAuthorized = st is AuthorizationState.Ready,
                        biometricRequired = st is AuthorizationState.Ready && biometricPrefEnabled,
                        // Flip isInitializing to false once both TDLib leaves
                        // WaitInitialization and DataStore preferences have synced.
                        isInitializing = current.isInitializing && (!authSettled || !initialPrefSynced),
                    )
                }
                if (st is AuthorizationState.Ready && !cloudSyncedForSession) {
                    cloudSyncedForSession = true
                    launch {
                        try {
                            cloudSyncManager.syncFromCloud()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Log.w("RootViewModel: initial cloudSync failed", t)
                        }
                    }
                } else if (st !is AuthorizationState.Ready) {
                    cloudSyncedForSession = false
                }
            }
        }
    }

    /**
     * Observe preferences for theme, onboarding, and biometric.
     *
     * Note: `onboardingCompleted` default is `false` here (matching
     * `AppPreferencesData`'s default). Previously `RootState` defaulted
     * to `true`, which contradicted the prefs default and caused new users
     * to skip onboarding.
     */
    private fun observePrefs() {
        viewModelScope.launch(dispatchers.io) {
            preferences.data.collect { prefs ->
                biometricPrefEnabled = prefs.biometricLockEnabled
                biometricLastBgTimeMs = prefs.biometricLastBgTime
                biometricTimeoutSeconds = prefs.biometricTimeoutSeconds
                decryptedTempTtlMinutes = prefs.decryptedTempTtlMinutes
                _state.update { current ->
                    val coldStartLock = if (!initialPrefSynced) {
                        initialPrefSynced = true
                        prefs.biometricLockEnabled
                    } else {
                        false
                    }
                    current.copy(
                        themeMode = prefs.themeMode,
                        onboardingCompleted = prefs.onboardingCompleted,
                        biometricRequired = current.isAuthorized && prefs.biometricLockEnabled,
                        biometricUnlocked = if (coldStartLock) false else current.biometricUnlocked,
                        isInitializing = current.isInitializing && (!authSettled || !initialPrefSynced),
                        manualUploadWifiOnly = prefs.manualUploadWifiOnly,
                        hasSavedPassphrase = backupCrypto.hasVaultPassphrase(),
                        decryptedTempTtlMinutes = prefs.decryptedTempTtlMinutes,
                        blockScreenshotsEverywhere = prefs.blockScreenshotsEverywhere,
                    )
                }
            }
        }
    }

    private fun observeNetwork() {
        viewModelScope.launch(dispatchers.io) {
            networkMonitor.isOnline.collect { online ->
                _state.update { it.copy(isOnline = online) }
            }
        }
    }

    /**
     * Time-bounded splash: flip `isInitializing` to `false` after
     * [SPLASH_TIMEOUT_MS] regardless of TDLib state. This guarantees the
     * user always reaches the auth screen, even on a fresh install where
     * TDLib is never initialized.
     */
    private fun startSplashTimer() {
        viewModelScope.launch(dispatchers.io) {
            kotlinx.coroutines.delay(SPLASH_TIMEOUT_MS)
            _state.update { it.copy(isInitializing = false) }
            Log.i("RootViewModel: splash timeout reached — isInitializing=false")
        }
    }

    /**
     * Check if stored API creds exist. Used by the nav host to decide
     * whether to show onboarding first (no creds) or go straight to auth.
     */
    private fun checkStoredCreds() {
        viewModelScope.launch(dispatchers.io) {
            try {
                val has = keystoreHelper.getApiCreds() != null
                _state.update { it.copy(hasStoredApiCreds = has) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("RootViewModel: checkStoredCreds failed", t)
            }
        }
    }

    fun onBiometricUnlocked() {
        biometricLastBgTimeMs = 0L
        _state.update { it.copy(biometricUnlocked = true) }
        viewModelScope.launch(dispatchers.io) {
            try {
                preferences.update { it.copy(biometricLastBgTime = 0L) }
            } catch (_: Throwable) {}
        }
    }

    fun onBiometricLocked() {
        _state.update { it.copy(biometricUnlocked = false) }
    }

    fun onAppBackgrounded() {
        sweepExpiredTempFiles()
        if (!biometricPrefEnabled) return
        val now = System.currentTimeMillis()
        biometricLastBgTimeMs = now
        viewModelScope.launch(dispatchers.io) {
            try {
                preferences.update { it.copy(biometricLastBgTime = now) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("RootViewModel: persist biometricLastBgTime failed", t)
            }
        }
    }

    fun onAppForegrounded() {
        sweepExpiredTempFiles()
        if (!biometricPrefEnabled) return
        val lastBg = biometricLastBgTimeMs
        val now = System.currentTimeMillis()
        val timeoutMs = biometricTimeoutSeconds.coerceAtLeast(0).toLong() * 1000L
        if (lastBg > 0L && (now < lastBg || (now - lastBg) >= timeoutMs)) {
            _state.update { it.copy(biometricUnlocked = false) }
        }
    }

    fun handleAppIntent(intent: android.content.Intent) {
        val shortcutRoute = intent.getStringExtra(EXTRA_SHORTCUT_ROUTE)?.takeIf { it.isNotBlank() }
        if (shortcutRoute != null) {
            _state.update { it.copy(pendingShortcutRoute = shortcutRoute) }
        }
        handleShareIntent(intent)
    }

    fun consumeShortcutRoute() {
        _state.update { it.copy(pendingShortcutRoute = null) }
    }

    fun sweepExpiredTempFiles() {
        val ttl = decryptedTempTtlMinutes
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                val activeAudio = audioPlayerController.state.value.path
                decryptedTempSweeper.sweepExpired(
                    ttlMinutes = ttl,
                    excludePaths = setOfNotNull(activeAudio),
                )
            }
        }
    }

    fun onTempViewerClosed(localPath: String) {
        if (decryptedTempTtlMinutes != 0 || localPath.isBlank()) return
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                val activeAudio = audioPlayerController.state.value.path
                if (activeAudio != localPath) {
                    decryptedTempSweeper.deleteIfTempDecrypted(localPath)
                }
            }
        }
    }

    fun playLocalAudio(localPath: String, fileName: String) {
        audioPlayerController.playAudio(localPath, fileName)
    }

    // ---------- Share-to-app intent handling ----------
    //
    // The manifest declares SEND + SEND_MULTIPLE intent filters on
    // MainActivity. Previously the URIs were never read → sharing a photo
    // from the gallery opened TelegramDrive and silently dropped the file.
    // These methods read the URIs, hold them in state, and let the UI
    // prompt the user to upload to Saved Messages.

    /**
     * Read ACTION_SEND / ACTION_MULTIPLE URIs from [intent] and stash them
     * in [RootState.pendingShareUris]. Safe to call with any intent —
     * returns early if the action isn't SEND/SEND_MULTIPLE.
     *
     * The caller (MainActivity) is responsible for taking persistable URI
     * permission before calling this — we only store the URI strings here.
     */
    fun handleShareIntent(intent: android.content.Intent) {
        val action = intent.action ?: return
        val uris: List<String> = when (action) {
            android.content.Intent.ACTION_SEND -> {
                val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
                }
                listOfNotNull(uri?.toString())
            }
            android.content.Intent.ACTION_SEND_MULTIPLE -> {
                val list = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM)
                }
                list?.map { it.toString() } ?: emptyList()
            }
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            Log.i("RootViewModel: received ${uris.size} file(s) via share intent")
            _state.update { it.copy(pendingShareUris = uris, shareUploadMessage = null) }
        }
    }

    /**
     * Upload the pending share URIs to [targetChatId] (or Saved Messages when null/0).
     * Optionally packs all shared URIs into an AES-256 or plain ZIP archive when [packAsZip] is true.
     */
    fun saveVaultPassphrase(passphrase: String) {
        val clean = passphrase.trim()
        if (clean.length >= 8) {
            backupCrypto.setVaultPassphrase(clean)
            _state.update { it.copy(hasSavedPassphrase = true) }
        }
    }

    fun confirmShareUpload(
        targetChatId: Long? = null,
        packAsZip: Boolean = false,
        zipName: String? = null,
        zipPassword: String? = null,
        encryptFiles: Boolean = false,
        useSavedPassphrase: Boolean = true,
        compressionMode: ZipCompressionMode = ZipCompressionMode.SMART,
        wifiOnly: Boolean = false,
    ) {
        val uris = _state.value.pendingShareUris
        if (uris.isEmpty()) return
        _state.update { it.copy(shareUploadInProgress = true, shareUploadMessage = null) }
        viewModelScope.launch(dispatchers.io) {
            try {
                val chatId = targetChatId?.takeIf { it != 0L } ?: telegramClient.myUserId()
                if (chatId == 0L) {
                    _state.update { it.copy(shareUploadInProgress = false, shareUploadMessage = "Not logged in") }
                    return@launch
                }
                val parsedUris = uris.map { android.net.Uri.parse(it) }
                val fileItems = uploadManager.resolveFileItems(parsedUris)
                val encSpec = UploadEncryptionSpec(
                    enabled = encryptFiles,
                    useSavedPassphrase = useSavedPassphrase,
                    customPassphrase = if (encryptFiles && !useSavedPassphrase) zipPassword else null,
                )
                if (packAsZip) {
                    val base = ZipUploader.sanitizeZipBaseName(
                        zipName?.takeIf { it.isNotBlank() } ?: "shared_${System.currentTimeMillis()}",
                    ).removeSuffix(".zip")
                    uploadManager.enqueueUrisAsZip(
                        files = fileItems,
                        request = ZipUploadRequest(
                            chatId = chatId,
                            baseName = base,
                            compressionMode = compressionMode,
                            encryption = encSpec,
                            wifiOnly = wifiOnly,
                        ),
                    )
                } else {
                    uploadManager.enqueueFiles(
                        chatId = chatId,
                        files = fileItems,
                        options = NormalUploadOptions(
                            conflictStrategy = UploadConflictStrategy.KEEP_BOTH,
                            encryption = encSpec,
                            autoSplitOversized = true,
                            wifiOnly = wifiOnly,
                        ),
                    )
                }
                _state.update {
                    it.copy(
                        pendingShareUris = emptyList(),
                        shareUploadInProgress = false,
                        shareUploadMessage = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("confirmShareUpload failed", t)
                _state.update {
                    it.copy(
                        shareUploadInProgress = false,
                        shareUploadMessage = "Upload failed: ${t.message}",
                    )
                }
            }
        }
    }

    fun cancelShareUpload() {
        _state.update { it.copy(pendingShareUris = emptyList(), shareUploadMessage = null) }
    }

    fun clearShareMessage() {
        _state.update { it.copy(shareUploadMessage = null) }
    }

    companion object {
        /** Maximum splash duration. The user always reaches the auth screen
         *  within this many milliseconds, regardless of TDLib state. */
        private const val SPLASH_TIMEOUT_MS = 1500L
        const val EXTRA_SHORTCUT_ROUTE = "shortcut_route"
    }
}
