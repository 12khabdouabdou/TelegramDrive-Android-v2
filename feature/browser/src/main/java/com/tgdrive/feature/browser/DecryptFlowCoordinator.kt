package com.tgdrive.feature.browser

import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DecryptOutcome
import com.tgdrive.core.download.DownloadQueueState
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.security.EncryptedFileFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Coordinates the download-if-needed -> detect encryption format -> decrypt -> open/save workflow
 * shared across FolderViewModel, GlobalSearchViewModel, and VaultViewModel.
 */
internal class DecryptFlowCoordinator(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val downloadState: StateFlow<DownloadQueueState>,
    private val detectFormat: (File) -> EncryptedFileFormat?,
    private val decryptFile: suspend (
        encryptedFile: File,
        originalDisplayName: String,
        passphrase: String?,
        saveToDownloads: Boolean,
        chatId: Long,
        messageId: Long,
    ) -> DecryptOutcome,
    private val onSavedMessage: (String) -> Unit,
    private val onMissingRemoteId: (String) -> Unit,
) {
    private val _dialog = MutableStateFlow<DecryptDialogState?>(null)
    val dialog: StateFlow<DecryptDialogState?> = _dialog.asStateFlow()

    private val _openEvent = MutableStateFlow<DecryptedOpenEvent?>(null)
    val openEvent: StateFlow<DecryptedOpenEvent?> = _openEvent.asStateFlow()

    private var waitJob: Job? = null

    fun start(
        item: FileCacheEntity,
        resolveLocalFile: () -> File?,
        enqueueDownload: () -> Unit,
    ) {
        waitJob?.cancel()
        val localFile = resolveLocalFile()?.takeIf { it.isFile }
        if (localFile != null) {
            val fmt = detectFormat(localFile)
            _dialog.value = DecryptDialogState(
                item = item,
                localFile = localFile,
                format = fmt,
            )
            return
        }
        if (item.remoteId.isBlank()) {
            onMissingRemoteId("File has no remote ID — can't download")
            return
        }
        _dialog.value = DecryptDialogState(
            item = item,
            downloading = true,
        )
        enqueueDownload()
        waitJob = scope.launch(ioDispatcher) {
            val terminal = downloadState
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
            when (terminal?.status) {
                DownloadStatus.DONE -> {
                    val f = terminal.localPath?.let(::File)?.takeIf { it.isFile }
                    if (f != null) {
                        val fmt = detectFormat(f)
                        _dialog.update { cur ->
                            cur?.copy(localFile = f, format = fmt, downloading = false, error = null)
                        }
                    } else {
                        _dialog.update { cur ->
                            cur?.copy(downloading = false, error = "Downloaded file could not be opened")
                        }
                    }
                }
                DownloadStatus.FAILED -> {
                    _dialog.update { cur ->
                        cur?.copy(downloading = false, error = terminal.errorMessage ?: "Download failed")
                    }
                }
                DownloadStatus.PAUSED -> {
                    _dialog.update { cur ->
                        cur?.copy(
                            downloading = false,
                            error = terminal.errorMessage ?: "Download paused (waiting for Wi-Fi)",
                        )
                    }
                }
                else -> _dialog.value = null
            }
        }
    }

    fun decrypt(
        passphrase: String?,
        saveToDownloads: Boolean,
    ) {
        val current = _dialog.value ?: return
        val localFile = current.localFile
        if (localFile == null || !localFile.isFile) {
            _dialog.update { it?.copy(error = "Waiting for download to complete…") }
            return
        }
        scope.launch(ioDispatcher) {
            _dialog.update { it?.copy(decrypting = true, error = null) }
            when (
                val outcome = decryptFile(
                    localFile,
                    current.item.fileName,
                    passphrase,
                    saveToDownloads,
                    current.item.chatId,
                    current.item.messageId,
                )
            ) {
                is DecryptOutcome.Success -> {
                    _dialog.value = null
                    if (saveToDownloads) {
                        onSavedMessage("Saved decrypted file: ${outcome.displayName}")
                    } else {
                        _openEvent.value = DecryptedOpenEvent(System.nanoTime(), outcome)
                    }
                }
                is DecryptOutcome.WrongPassphrase -> {
                    _dialog.update { it?.copy(decrypting = false, error = outcome.message) }
                }
                is DecryptOutcome.Failed -> {
                    _dialog.update { it?.copy(decrypting = false, error = outcome.message) }
                }
            }
        }
    }

    fun dismiss() {
        waitJob?.cancel()
        _dialog.value = null
    }

    fun clearOpenEvent() {
        _openEvent.value = null
    }
}
