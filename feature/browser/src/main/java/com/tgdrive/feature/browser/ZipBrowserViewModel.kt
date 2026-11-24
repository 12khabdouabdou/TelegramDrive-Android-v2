package com.tgdrive.feature.browser

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.UploadManager
import com.tgdrive.core.download.index.ZipIndexService
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.zip.ZipEntryInfo
import com.tgdrive.core.zip.ZipUploader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject

/**
 * UI state and ViewModel for the in-app ZIP browser with Vault Key auto-unlock,
 * password caching, single-entry preview, and full Extract-All-to-Downloads support.
 */
data class ZipBrowserUiState(
    val entries: List<ZipEntryInfo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val zipReady: Boolean = false,
    val passwordRequired: Boolean = false,
    val extractedPath: String? = null,
    val extractingEntry: String? = null,
    val unlockedPassword: String? = null,
    val resolvedLocalPath: String? = null,
    val extractingAll: Boolean = false,
    val statusMessage: String? = null,
    val hasSavedBackupPassphrase: Boolean = false,
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
        private val zipAllExtractor: ZipAllExtractor,
        private val zipIndexService: ZipIndexService,
        private val offlinePinStore: OfflinePinStore,
        private val backupCrypto: BackupCrypto,
    ) : ViewModel() {
        private val _state = MutableStateFlow(
            ZipBrowserUiState(hasSavedBackupPassphrase = backupCrypto.hasCustomBackupPassphrase()),
        )
        val state: StateFlow<ZipBrowserUiState> = _state.asStateFlow()

        private var currentZipPath: String? = null
        private var currentZipName: String = ""
        private var cachedPassword: String? = null
        private var isVaultHinted: Boolean = false

        fun hasCachedPassword(): Boolean = cachedPassword != null

        fun ensureArchiveQueued(chatId: Long, messageId: Long) {
            if (chatId != 0L) {
                val pinned = offlinePinStore.getPinnedFilePathSync(chatId, messageId)
                    ?.takeIf { File(it).isFile }
                if (pinned != null) {
                    _state.update { it.copy(resolvedLocalPath = pinned) }
                    return
                }
            }
            if (downloadManager.findItem(chatId, messageId) != null) return
            viewModelScope.launch(dispatchers.io) {
                val cached = if (chatId != 0L) {
                    runCatching { fileCacheDao.getById(chatId, messageId) }.getOrNull()
                } else {
                    runCatching { fileCacheDao.getAllZips().firstOrNull { it.messageId == messageId } }.getOrNull()
                } ?: return@launch
                offlinePinStore.getPinnedFilePathSync(cached.chatId, cached.messageId)
                    ?.takeIf { File(it).isFile }
                    ?.let { pinned ->
                        _state.update { it.copy(resolvedLocalPath = pinned) }
                        return@launch
                    }
                if (cached.remoteId.isNotEmpty()) {
                    downloadManager.enqueue(
                        DownloadItem(
                            id = cached.messageId,
                            chatId = cached.chatId,
                            remoteId = cached.remoteId,
                            fileName = cached.fileName,
                            totalBytes = cached.fileSize,
                        ),
                    )
                }
            }
        }

        fun load(
            zipPath: String,
            chatId: Long = 0L,
            messageId: Long = 0L,
            zipFileName: String = "",
        ) {
            if (currentZipPath != zipPath) {
                cachedPassword = null
                _state.update { it.copy(unlockedPassword = null) }
            }
            currentZipPath = zipPath
            currentZipName = zipFileName.ifBlank { File(zipPath).name }
            viewModelScope.launch(dispatchers.io) {
                _state.update { it.copy(loading = true, error = null) }
                try {
                    val entries = zipLister.listEntries(zipPath)
                    if (chatId != 0L && messageId != 0L) {
                        val cachedRow = runCatching { fileCacheDao.getById(chatId, messageId) }.getOrNull()
                        isVaultHinted = cachedRow?.isEncryptedVault == true
                        if (entries.isNotEmpty()) {
                            val safeTitle = zipFileName.ifBlank { File(zipPath).name }
                            runCatching {
                                zipIndexService.indexLocalEntries(chatId, messageId, safeTitle, entries)
                            }
                        }
                    }
                    _state.update {
                        it.copy(
                            entries = entries,
                            loading = false,
                            error = null,
                            zipReady = true,
                            passwordRequired = entries.any { e -> e.isEncrypted },
                            hasSavedBackupPassphrase = backupCrypto.hasCustomBackupPassphrase(),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w("ZipBrowser: load failed", t)
                    _state.update { it.copy(loading = false, error = t.message ?: "Failed to read ZIP") }
                }
            }
        }

        /**
         * Opens a single entry, transparently attempting the device Vault Key (and saved backup
         * passphrase) if the archive is marked vault-encrypted before prompting for a password.
         */
        fun openOrPromptEntry(
            entry: ZipEntryInfo,
            onNeedPassword: () -> Unit,
        ) {
            if (!entry.isEncrypted || cachedPassword != null) {
                extractEntry(entry.name)
                return
            }
            if (!isVaultHinted) {
                onNeedPassword()
                return
            }
            unlockWithVaultKey(entryName = entry.name) {
                onNeedPassword()
            }
        }

        fun unlockWithVaultKey(
            entryName: String? = null,
            useSavedBackupPassphrase: Boolean = false,
            onFailurePromptNeeded: (() -> Unit)? = null,
        ) {
            val zipPath = currentZipPath ?: return
            val targetName = entryName
                ?: _state.value.entries.firstOrNull { !it.isDirectory && it.isEncrypted }?.name
                ?: _state.value.entries.firstOrNull { !it.isDirectory }?.name
                ?: return
            if (_state.value.extractingEntry != null) return
            _state.update { it.copy(extractingEntry = targetName, error = null) }
            viewModelScope.launch(dispatchers.io) {
                val candidates = buildList {
                    if (useSavedBackupPassphrase) {
                        backupCrypto.getCustomBackupPassphrase()?.let { add(it) }
                        add(backupCrypto.zipVaultPassword())
                    } else {
                        add(backupCrypto.zipVaultPassword())
                        backupCrypto.getCustomBackupPassphrase()?.let { add(it) }
                    }
                }.distinct()

                val outDir = File(appContext.cacheDir, "zip_extracts").apply { mkdirs() }
                for (candidate in candidates) {
                    val extracted = runCatching {
                        zipExtractor.extractEntry(zipPath, targetName, outDir, candidate)
                    }.getOrNull()
                    if (extracted != null) {
                        cachedPassword = candidate
                        _state.update {
                            it.copy(
                                extractingEntry = null,
                                extractedPath = if (entryName != null) extracted else null,
                                unlockedPassword = candidate,
                                statusMessage = if (entryName == null) "Archive unlocked with Vault Key" else null,
                            )
                        }
                        return@launch
                    }
                }
                _state.update {
                    it.copy(
                        extractingEntry = null,
                        error = if (onFailurePromptNeeded == null) {
                            "Vault key did not unlock this archive — enter the password"
                        } else {
                            null
                        },
                    )
                }
                if (onFailurePromptNeeded != null) {
                    withContext(dispatchers.main) { onFailurePromptNeeded() }
                }
            }
        }

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
                        _state.update {
                            it.copy(
                                extractingEntry = null,
                                extractedPath = path,
                                unlockedPassword = cachedPassword,
                            )
                        }
                    } else {
                        if (effectivePassword != null) cachedPassword = null
                        _state.update {
                            it.copy(
                                extractingEntry = null,
                                unlockedPassword = null,
                                error = "Failed to extract '$entryName'",
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    _state.update { it.copy(extractingEntry = null) }
                    throw e
                } catch (t: Throwable) {
                    Log.w("ZipBrowser: extractEntry failed", t)
                    if (effectivePassword != null && t.message?.contains("password", ignoreCase = true) == true) {
                        cachedPassword = null
                    }
                    _state.update {
                        it.copy(
                            extractingEntry = null,
                            unlockedPassword = cachedPassword,
                            error = t.message ?: "Extraction failed",
                        )
                    }
                }
            }
        }

        fun extractAllToDownloads(
            password: String? = null,
            onNeedPassword: () -> Unit = {},
        ) {
            val zipPath = currentZipPath ?: return
            if (_state.value.extractingAll) return
            val hasEncrypted = _state.value.entries.any { it.isEncrypted }
            val supplied = password ?: cachedPassword
            if (hasEncrypted && supplied.isNullOrBlank() && !isVaultHinted) {
                onNeedPassword()
                return
            }

            _state.update { it.copy(extractingAll = true, error = null, statusMessage = null) }
            viewModelScope.launch(dispatchers.io) {
                val archiveName = currentZipName.ifBlank { File(zipPath).name }
                    .substringBeforeLast('.')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "Archive" }
                val stageExtractDir = File(appContext.cacheDir, "zip_extract_all/$archiveName").apply {
                    deleteRecursively()
                    mkdirs()
                }
                var mergedTemp: File? = null
                try {
                    val zipFile = File(zipPath)
                    val targetZipPath = resolveArchiveWithSplitCheck(zipFile, supplied) { merged ->
                        mergedTemp = merged
                    }

                    val candidates = when {
                        !supplied.isNullOrBlank() -> listOf(supplied)
                        hasEncrypted && isVaultHinted -> buildList {
                            add(backupCrypto.zipVaultPassword())
                            backupCrypto.getCustomBackupPassphrase()?.let { add(it) }
                        }.distinct()
                        else -> listOf<String?>(null)
                    }

                    var extractedFiles: List<File>? = null
                    var lastErr: Throwable? = null
                    for (pwd in candidates) {
                        try {
                            stageExtractDir.deleteRecursively()
                            stageExtractDir.mkdirs()
                            val res = zipAllExtractor.extractAll(targetZipPath, stageExtractDir, pwd)
                            extractedFiles = res
                            if (!pwd.isNullOrBlank()) {
                                cachedPassword = pwd
                            }
                            break
                        } catch (t: Throwable) {
                            lastErr = t
                        }
                    }

                    if (extractedFiles == null) {
                        _state.update {
                            it.copy(
                                extractingAll = false,
                                error = lastErr?.message ?: "Wrong password or failed to extract archive",
                            )
                        }
                        if (hasEncrypted && supplied == null) {
                            withContext(dispatchers.main) { onNeedPassword() }
                        }
                        return@launch
                    }

                    var savedCount = 0
                    for (file in extractedFiles) {
                        val rel = file.relativeTo(stageExtractDir).path.replace('\\', '/')
                        if (copyExtractedToDownloads(file, archiveName, rel)) {
                            savedCount++
                        }
                    }
                    val finalCount = if (savedCount > 0) savedCount else extractedFiles.size
                    _state.update {
                        it.copy(
                            extractingAll = false,
                            unlockedPassword = cachedPassword,
                            statusMessage = "Extracted $finalCount file(s) to Downloads/$archiveName",
                        )
                    }
                } catch (e: CancellationException) {
                    _state.update { it.copy(extractingAll = false) }
                    throw e
                } catch (t: Throwable) {
                    Log.w("ZipBrowser: extractAll failed", t)
                    _state.update {
                        it.copy(
                            extractingAll = false,
                            error = t.message ?: "Failed to extract archive",
                        )
                    }
                } finally {
                    runCatching { mergedTemp?.delete() }
                    runCatching { stageExtractDir.deleteRecursively() }
                }
            }
        }

        private fun resolveArchiveWithSplitCheck(
            zipFile: File,
            password: String?,
            onMergedCreated: (File) -> Unit,
        ): String {
            val parent = zipFile.parentFile ?: return zipFile.absolutePath
            val stem = zipFile.nameWithoutExtension
            val hasSplitVol = parent.listFiles()?.any {
                it.name.startsWith("$stem.z") && it.name != zipFile.name &&
                    it.name.substringAfterLast(".z").all { ch -> ch.isDigit() }
            } == true
            if (!hasSplitVol) return zipFile.absolutePath
            val merged = File(appContext.cacheDir, "zip_extract_all/merged_$stem.zip")
            if (ZipUploader.mergeSplitZip(zipFile, merged, password)) {
                onMergedCreated(merged)
                return merged.absolutePath
            }
            return zipFile.absolutePath
        }

        private fun copyExtractedToDownloads(
            source: File,
            archiveFolder: String,
            relativePath: String,
        ): Boolean {
            return runCatching {
                val leafName = source.name
                val subDir = relativePath.substringBeforeLast('/', "").trim('/')
                val relDir = if (subDir.isNotEmpty()) {
                    "${Environment.DIRECTORY_DOWNLOADS}/TelegramDrive/$archiveFolder/$subDir"
                } else {
                    "${Environment.DIRECTORY_DOWNLOADS}/TelegramDrive/$archiveFolder"
                }
                val mime = UploadManager.guessMimeType(leafName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, leafName)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, relDir)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = appContext.contentResolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values,
                    ) ?: return@runCatching false
                    try {
                        appContext.contentResolver.openOutputStream(uri)?.use { out ->
                            FileInputStream(source).use { input -> input.copyTo(out, 64 * 1024) }
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        appContext.contentResolver.update(uri, values, null, null)
                    } catch (t: Throwable) {
                        runCatching { appContext.contentResolver.delete(uri, null, null) }
                        throw t
                    }
                    true
                } else {
                    @Suppress("DEPRECATION")
                    val rootDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "TelegramDrive/$archiveFolder",
                    )
                    val target = File(rootDir, relativePath)
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                    true
                }
            }.getOrDefault(false)
        }

        /** Reset state for a fresh ZIP. */
        fun reset() {
            currentZipPath = null
            currentZipName = ""
            cachedPassword = null
            isVaultHinted = false
            _state.value = ZipBrowserUiState(hasSavedBackupPassphrase = backupCrypto.hasCustomBackupPassphrase())
        }

        fun clearError() {
            _state.update { it.copy(error = null) }
        }

        fun clearStatusMessage() {
            _state.update { it.copy(statusMessage = null) }
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
