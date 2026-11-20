package com.tgdrive.feature.browser

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.zip.ZipEntryInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * UI state for the in-app ZIP browser.
 *
 * Lifecycle:
 * 1. User taps a .zip file in [FolderScreen].
 * 2. [RootNavHost] navigates to `zip/{messageId}`.
 * 3. [ZipBrowserScreen] calls [load] with the messageId + local path.
 * 4. If the zip isn't downloaded yet, [DownloadManager] is enqueued;
 *    the screen watches download state and calls [load] when ready.
 * 5. [ZipLister.listEntries] returns the entries → state.entries populated.
 * 6. Tapping an entry → [extractEntry] → file extracted to cacheDir.
 */
data class ZipBrowserUiState(
    val entries: List<ZipEntryInfo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val zipReady: Boolean = false,
    val passwordRequired: Boolean = false,
    val extractedPath: String? = null,
    val extractingEntry: String? = null,
)

@HiltViewModel
class ZipBrowserViewModel
    @Inject
    constructor(
        private val telegramClient: TelegramClient,
        val downloadManager: DownloadManager,
        private val fileCacheDao: FileCacheDao,
        private val dispatchers: DispatchersProvider,
        @ApplicationContext private val appContext: Context,
        private val zipLister: ZipLister,
        private val zipExtractor: ZipExtractor,
    ) : ViewModel() {
        private val _state = MutableStateFlow(ZipBrowserUiState())
        val state: StateFlow<ZipBrowserUiState> = _state.asStateFlow()

        private var currentZipPath: String? = null
        private var cachedPassword: String? = null

        fun hasCachedPassword(): Boolean = cachedPassword != null

        /**
         * Load entries from a local ZIP file path.
         * Sets [ZipBrowserUiState.zipReady] = true on success and populates [ZipBrowserUiState.entries].
         * Sets [ZipBrowserUiState.passwordRequired] when any entry is encrypted —
         * the UI should prompt for a password before allowing extraction.
         */
        fun load(zipPath: String) {
            if (currentZipPath != zipPath) {
                cachedPassword = null
            }
            currentZipPath = zipPath
            viewModelScope.launch(dispatchers.io) {
                _state.update { it.copy(loading = true, error = null) }
                try {
                    val entries = zipLister.listEntries(zipPath)
                    _state.update {
                        it.copy(
                            entries = entries,
                            loading = false,
                            error = null,
                            zipReady = true,
                            passwordRequired = entries.any { e -> e.isEncrypted },
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("ZipBrowser: load failed", t)
                    _state.update { it.copy(loading = false, error = t.message ?: "Failed to read ZIP") }
                }
            }
        }

        /**
         * Extract a single entry from the ZIP into the app cache directory.
         * Sets [ZipBrowserUiState.extractedPath] on success.
         */
        fun extractEntry(
            entryName: String,
            password: String? = null,
        ) {
            val zipPath = currentZipPath ?: return
            if (_state.value.extractingEntry != null) return
            val effectivePassword = password ?: cachedPassword
            _state.update { it.copy(extractingEntry = entryName, error = null) }
            viewModelScope.launch(dispatchers.io) {
                try {
                    val outDir = File(appContext.cacheDir, "zip_extracts").apply { mkdirs() }
                    val path = zipExtractor.extractEntry(zipPath, entryName, outDir, effectivePassword)
                    if (path != null) {
                        if (password != null) cachedPassword = password
                        _state.update { it.copy(extractingEntry = null, extractedPath = path) }
                    } else {
                        if (effectivePassword != null) cachedPassword = null
                        _state.update { it.copy(extractingEntry = null, error = "Failed to extract '$entryName'") }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _state.update { it.copy(extractingEntry = null) }
                    throw e
                } catch (t: Throwable) {
                    Log.w("ZipBrowser: extractEntry failed", t)
                    if (effectivePassword != null && t.message?.contains("password", ignoreCase = true) == true) {
                        cachedPassword = null
                    }
                    _state.update { it.copy(extractingEntry = null, error = t.message ?: "Extraction failed") }
                }
            }
        }

        /** Reset state for a fresh ZIP. */
        fun reset() {
            currentZipPath = null
            cachedPassword = null
            _state.value = ZipBrowserUiState()
        }

        fun clearError() {
            _state.update { it.copy(error = null) }
        }

        fun clearExtractedPath() {
            _state.update { it.copy(extractedPath = null) }
        }

        override fun onCleared() {
            super.onCleared()
            try {
                File(appContext.cacheDir, "zip_extracts").deleteRecursively()
            } catch (_: Throwable) {}
        }
    }
