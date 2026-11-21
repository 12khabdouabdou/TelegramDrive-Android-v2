package com.tgdrive.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.NetworkMonitor
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.datastore.ThemeMode
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.UploadTracker
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.security.KeystoreHelper
import com.tgdrive.core.telegram.AuthorizationState
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.zip.ZipUploader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
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
)

@HiltViewModel
class RootViewModel @Inject constructor(
    private val telegramClient: TelegramClient,
    private val preferences: AppPreferences,
    private val networkMonitor: NetworkMonitor,
    private val keystoreHelper: KeystoreHelper,
    private val cloudSyncManager: CloudSyncManager,
    private val fileCacheDao: FileCacheDao,
    private val folderDao: FolderDao,
    private val mediaThumbnailCache: MediaThumbnailCache,
    private val uploadTracker: UploadTracker,
    private val dispatchers: DispatchersProvider,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val _state = MutableStateFlow(RootState())
    val state: StateFlow<RootState> = _state.asStateFlow()

    // Shadow flags for biometric lock & persisted background timeout.
    private var biometricPrefEnabled = false
    private var biometricLastBgTimeMs = 0L
    private var initialPrefSynced = false
    private var cloudSyncedForSession = false

    init {
        observeAuth()
        observeThumbnailUpdates()
        observePrefs()
        observeShareFolders()
        observeNetwork()
        startSplashTimer()
        checkStoredCreds()
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
                _state.update { current ->
                    current.copy(
                        isAuthorized = st is AuthorizationState.Ready,
                        biometricRequired = st is AuthorizationState.Ready && biometricPrefEnabled,
                        // Flip isInitializing to false as soon as TDLib reports
                        // any non-WaitInitialization state. This is the "early
                        // exit" path for returning users.
                        isInitializing = current.isInitializing && st is AuthorizationState.WaitInitialization,
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
        if (!biometricPrefEnabled) return
        val now = System.currentTimeMillis()
        biometricLastBgTimeMs = now
        viewModelScope.launch(dispatchers.io) {
            try {
                preferences.update { it.copy(biometricLastBgTime = now) }
            } catch (t: Throwable) {
                Log.w("RootViewModel: persist biometricLastBgTime failed", t)
            }
        }
    }

    fun onAppForegrounded() {
        if (!biometricPrefEnabled) return
        val lastBg = biometricLastBgTimeMs
        val now = System.currentTimeMillis()
        if (lastBg == 0L || now < lastBg || (now - lastBg) > BIOMETRIC_RELOCK_MS) {
            _state.update { it.copy(biometricUnlocked = false) }
        }
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
    fun confirmShareUpload(
        targetChatId: Long? = null,
        packAsZip: Boolean = false,
        zipName: String? = null,
        zipPassword: String? = null,
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
                if (packAsZip) {
                    val base = ZipUploader.sanitizeZipBaseName(
                        zipName?.takeIf { it.isNotBlank() } ?: "shared_${System.currentTimeMillis()}",
                    ).removeSuffix(".zip")
                    val finalZipName = "$base.zip"
                    val stagingDir = java.io.File(appContext.cacheDir, "share_staging").apply { mkdirs() }
                    val zipFile = java.io.File(stagingDir, finalZipName)
                    val trackerKey = "share_zip_${UUID.randomUUID()}"
                    try {
                        val parsedUris = uris.map { android.net.Uri.parse(it) }
                        val entries = ZipUploader.zipUrisToFile(
                            context = appContext,
                            uris = parsedUris,
                            outputFile = zipFile,
                            password = zipPassword?.takeIf { it.isNotBlank() },
                        )
                        val zipLen = zipFile.length()
                        uploadTracker.beginUpload(trackerKey, chatId, finalZipName, zipLen)
                        val result = telegramClient.uploadFile(
                            chatId = chatId,
                            localPath = zipFile.absolutePath,
                            fileName = finalZipName,
                            mimeType = "application/zip",
                        ) { sent, total ->
                            uploadTracker.updateProgress(trackerKey, sent, total)
                        }
                        val thumb = if (zipPassword.isNullOrBlank()) {
                            mediaThumbnailCache.generateFromLocalFile(
                                chatId = chatId,
                                messageId = result.messageId,
                                localPath = zipFile.absolutePath,
                                mimeType = "application/zip",
                                fileName = finalZipName,
                            )
                        } else {
                            null
                        }
                        fileCacheDao.upsertAll(
                            listOf(
                                FileCacheEntity(
                                    messageId = result.messageId,
                                    chatId = chatId,
                                    fileName = finalZipName,
                                    fileSize = zipLen,
                                    mimeType = "application/zip",
                                    thumbnailPath = thumb,
                                    remoteId = result.remoteId,
                                    date = System.currentTimeMillis(),
                                    lastAccessed = System.currentTimeMillis(),
                                ),
                            ),
                        )
                        uploadTracker.completeUpload(trackerKey)
                        _state.update {
                            it.copy(
                                pendingShareUris = emptyList(),
                                shareUploadInProgress = false,
                                shareUploadMessage = "Uploaded $finalZipName (${entries.size} files)",
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        uploadTracker.failUpload(trackerKey, "Cancelled")
                        throw e
                    } catch (t: Throwable) {
                        uploadTracker.failUpload(trackerKey, t.message ?: "ZIP upload failed")
                        throw t
                    } finally {
                        zipFile.delete()
                    }
                    return@launch
                }

                var done = 0
                var failed = 0
                for (uriStr in uris) {
                    try {
                        uploadSharedUri(uriStr, chatId)
                        done++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w("Share upload failed for $uriStr", t)
                        failed++
                    }
                }
                val msg = if (failed == 0) "Uploaded $done file(s)" else "Uploaded $done, failed $failed"
                _state.update {
                    it.copy(
                        pendingShareUris = emptyList(),
                        shareUploadInProgress = false,
                        shareUploadMessage = msg,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
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

    private suspend fun uploadSharedUri(uriStr: String, chatId: Long) {
        val uri = android.net.Uri.parse(uriStr)
        val name = resolveDisplayName(uri) ?: "shared_${System.currentTimeMillis()}"
        val mime = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
        val stagingDir = java.io.File(appContext.cacheDir, "share_staging").apply { mkdirs() }
        val staging = java.io.File.createTempFile("share_", "_$name", stagingDir)
        val trackerKey = "share_${UUID.randomUUID()}"
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Cannot open URI stream")
            val size = staging.length()
            uploadTracker.beginUpload(trackerKey, chatId, name, size)
            val res = telegramClient.uploadFile(
                chatId = chatId,
                localPath = staging.absolutePath,
                fileName = name,
                mimeType = mime,
            ) { sent, total ->
                uploadTracker.updateProgress(trackerKey, sent, total)
            }
            val thumb = mediaThumbnailCache.generateFromLocalFile(
                chatId = chatId,
                messageId = res.messageId,
                localPath = staging.absolutePath,
                mimeType = mime,
                fileName = name,
            )
            fileCacheDao.upsertAll(
                listOf(
                    FileCacheEntity(
                        messageId = res.messageId,
                        chatId = chatId,
                        fileName = name,
                        fileSize = size,
                        mimeType = mime,
                        thumbnailPath = thumb,
                        remoteId = res.remoteId,
                        date = System.currentTimeMillis(),
                        lastAccessed = System.currentTimeMillis(),
                    ),
                ),
            )
            uploadTracker.completeUpload(trackerKey)
        } catch (t: Throwable) {
            uploadTracker.failUpload(trackerKey, t.message ?: "Upload failed")
            throw t
        } finally {
            staging.delete()
        }
    }

    private fun resolveDisplayName(uri: android.net.Uri): String? {
        runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val value = c.getString(idx)
                        if (!value.isNullOrBlank()) return value
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** Maximum splash duration. The user always reaches the auth screen
         *  within this many milliseconds, regardless of TDLib state. */
        private const val SPLASH_TIMEOUT_MS = 1500L
        private const val BIOMETRIC_RELOCK_MS = 60_000L
    }
}
