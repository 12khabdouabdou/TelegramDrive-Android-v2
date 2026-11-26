package com.tgdrive.feature.browser

import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DecryptOutcome
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadQueueState
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.security.EncryptedFileFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class DecryptFlowCoordinatorTest {

    private fun sampleItem(remoteId: String = "rem_1") = FileCacheEntity(
        messageId = 42L,
        chatId = 100L,
        fileName = "secret.pdf.tgenc",
        fileSize = 2048L,
        mimeType = "application/octet-stream",
        thumbnailPath = null,
        remoteId = remoteId,
        date = 1_000L,
        isEncryptedVault = true,
    )

    @Test
    fun `start with existing local file populates dialog without enqueueing download`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dlState = MutableStateFlow(DownloadQueueState())
        val encFile = File(tempDir.toFile(), "secret.pdf.tgenc").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var enqueueCount = 0

        val coordinator = DecryptFlowCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            downloadState = dlState,
            detectFormat = { EncryptedFileFormat.VAULT_TGE1 },
            decryptFile = { _, _, _, _, _, _ -> DecryptOutcome.Failed("unused") },
            onSavedMessage = {},
            onMissingRemoteId = {},
        )

        coordinator.start(
            item = sampleItem(),
            resolveLocalFile = { encFile },
            enqueueDownload = { enqueueCount++ },
        )

        assertThat(enqueueCount).isEqualTo(0)
        val dlg = coordinator.dialog.value
        assertThat(dlg).isNotNull()
        assertThat(dlg!!.localFile).isEqualTo(encFile)
        assertThat(dlg.format).isEqualTo(EncryptedFileFormat.VAULT_TGE1)
        assertThat(dlg.downloading).isFalse()
    }

    @Test
    fun `start without remoteId reports missing remote ID error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dlState = MutableStateFlow(DownloadQueueState())
        var missingMsg: String? = null
        var enqueueCount = 0

        val coordinator = DecryptFlowCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            downloadState = dlState,
            detectFormat = { null },
            decryptFile = { _, _, _, _, _, _ -> DecryptOutcome.Failed("unused") },
            onSavedMessage = {},
            onMissingRemoteId = { missingMsg = it },
        )

        coordinator.start(
            item = sampleItem(remoteId = ""),
            resolveLocalFile = { null },
            enqueueDownload = { enqueueCount++ },
        )

        assertThat(enqueueCount).isEqualTo(0)
        assertThat(coordinator.dialog.value).isNull()
        assertThat(missingMsg).contains("no remote ID")
    }

    @Test
    fun `start downloads file and updates dialog on DONE`(@TempDir tempDir: Path) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dlState = MutableStateFlow(DownloadQueueState())
        val encFile = File(tempDir.toFile(), "downloaded.tgenc").apply { writeBytes(byteArrayOf(4, 5)) }
        var enqueueCount = 0

        val coordinator = DecryptFlowCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            downloadState = dlState,
            detectFormat = { EncryptedFileFormat.PASSPHRASE_TGE2 },
            decryptFile = { _, _, _, _, _, _ -> DecryptOutcome.Failed("unused") },
            onSavedMessage = {},
            onMissingRemoteId = {},
        )

        coordinator.start(
            item = sampleItem(),
            resolveLocalFile = { null },
            enqueueDownload = {
                enqueueCount++
                dlState.value = DownloadQueueState(
                    listOf(
                        DownloadItem(
                            id = 42L,
                            chatId = 100L,
                            remoteId = "rem_1",
                            fileName = "secret.pdf.tgenc",
                            status = DownloadStatus.DOWNLOADING,
                        ),
                    ),
                )
            },
        )

        assertThat(enqueueCount).isEqualTo(1)
        assertThat(coordinator.dialog.value?.downloading).isTrue()

        dlState.value = DownloadQueueState(
            listOf(
                DownloadItem(
                    id = 42L,
                    chatId = 100L,
                    remoteId = "rem_1",
                    fileName = "secret.pdf.tgenc",
                    status = DownloadStatus.DONE,
                    localPath = encFile.absolutePath,
                ),
            ),
        )
        advanceUntilIdle()

        val dlg = coordinator.dialog.value
        assertThat(dlg?.downloading).isFalse()
        assertThat(dlg?.localFile).isEqualTo(encFile)
        assertThat(dlg?.format).isEqualTo(EncryptedFileFormat.PASSPHRASE_TGE2)
        assertThat(dlg?.error).isNull()
    }

    @Test
    fun `start reports error on FAILED and PAUSED downloads`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dlState = MutableStateFlow(
            DownloadQueueState(
                listOf(
                    DownloadItem(
                        id = 42L,
                        chatId = 100L,
                        remoteId = "rem_1",
                        fileName = "secret.pdf.tgenc",
                        status = DownloadStatus.FAILED,
                        errorMessage = "Network reset",
                    ),
                ),
            ),
        )

        val coordinator = DecryptFlowCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            downloadState = dlState,
            detectFormat = { null },
            decryptFile = { _, _, _, _, _, _ -> DecryptOutcome.Failed("unused") },
            onSavedMessage = {},
            onMissingRemoteId = {},
        )

        coordinator.start(item = sampleItem(), resolveLocalFile = { null }, enqueueDownload = {})
        advanceUntilIdle()
        assertThat(coordinator.dialog.value?.downloading).isFalse()
        assertThat(coordinator.dialog.value?.error).isEqualTo("Network reset")

        dlState.value = DownloadQueueState(
            listOf(
                DownloadItem(
                    id = 42L,
                    chatId = 100L,
                    remoteId = "rem_1",
                    fileName = "secret.pdf.tgenc",
                    status = DownloadStatus.PAUSED,
                ),
            ),
        )
        coordinator.start(item = sampleItem(), resolveLocalFile = { null }, enqueueDownload = {})
        advanceUntilIdle()
        assertThat(coordinator.dialog.value?.error).contains("paused")
    }

    @Test
    fun `decrypt handles waiting guard saveToDownloads snackbar open event and wrong passphrase`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dlState = MutableStateFlow(DownloadQueueState())
        val encFile = File(tempDir.toFile(), "secret.pdf.tgenc").apply { writeBytes(byteArrayOf(7, 8)) }
        val decFile = File(tempDir.toFile(), "secret.pdf").apply { writeBytes(byteArrayOf(9)) }
        val savedMessages = mutableListOf<String>()
        var nextOutcome: DecryptOutcome = DecryptOutcome.WrongPassphrase("Bad passphrase")

        val coordinator = DecryptFlowCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            downloadState = dlState,
            detectFormat = { EncryptedFileFormat.PASSPHRASE_TGE2 },
            decryptFile = { _, _, _, _, _, _ -> nextOutcome },
            onSavedMessage = { savedMessages.add(it) },
            onMissingRemoteId = {},
        )

        // While downloading (no localFile yet), decrypt shows waiting error
        coordinator.start(item = sampleItem(), resolveLocalFile = { null }, enqueueDownload = {})
        coordinator.decrypt(passphrase = "pw", saveToDownloads = false)
        assertThat(coordinator.dialog.value?.error).contains("Waiting")

        // With local file: WrongPassphrase keeps dialog open with error
        coordinator.start(item = sampleItem(), resolveLocalFile = { encFile }, enqueueDownload = {})
        coordinator.decrypt(passphrase = "wrong", saveToDownloads = false)
        advanceUntilIdle()
        assertThat(coordinator.dialog.value?.decrypting).isFalse()
        assertThat(coordinator.dialog.value?.error).isEqualTo("Bad passphrase")

        // Success with saveToDownloads = false emits openEvent
        nextOutcome = DecryptOutcome.Success(decFile, "secret.pdf", "application/pdf", savedToDownloads = false)
        coordinator.decrypt(passphrase = "good", saveToDownloads = false)
        advanceUntilIdle()
        assertThat(coordinator.dialog.value).isNull()
        assertThat(coordinator.openEvent.value?.outcome?.displayName).isEqualTo("secret.pdf")
        coordinator.clearOpenEvent()
        assertThat(coordinator.openEvent.value).isNull()

        // Success with saveToDownloads = true invokes saved callback
        nextOutcome = DecryptOutcome.Success(decFile, "secret.pdf", "application/pdf", savedToDownloads = true)
        coordinator.start(item = sampleItem(), resolveLocalFile = { encFile }, enqueueDownload = {})
        coordinator.decrypt(passphrase = "good", saveToDownloads = true)
        advanceUntilIdle()
        assertThat(savedMessages).containsExactly("Saved decrypted file: secret.pdf")
        assertThat(coordinator.openEvent.value).isNull()
    }

    @Test
    fun `dismiss clears dialog and cancels pending download wait`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dlState = MutableStateFlow(DownloadQueueState())
        val coordinator = DecryptFlowCoordinator(
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            downloadState = dlState,
            detectFormat = { null },
            decryptFile = { _, _, _, _, _, _ -> DecryptOutcome.Failed("unused") },
            onSavedMessage = {},
            onMissingRemoteId = {},
        )

        coordinator.start(item = sampleItem(), resolveLocalFile = { null }, enqueueDownload = {})
        assertThat(coordinator.dialog.value).isNotNull()

        coordinator.dismiss()
        assertThat(coordinator.dialog.value).isNull()

        // Subsequent emission after dismiss should not revive dialog
        dlState.value = DownloadQueueState(
            listOf(
                DownloadItem(
                    id = 42L,
                    chatId = 100L,
                    remoteId = "rem_1",
                    fileName = "secret.pdf.tgenc",
                    status = DownloadStatus.FAILED,
                ),
            ),
        )
        advanceUntilIdle()
        assertThat(coordinator.dialog.value).isNull()
    }
}
