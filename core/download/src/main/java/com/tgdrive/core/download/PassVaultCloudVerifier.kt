package com.tgdrive.core.download

import android.content.Context
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.telegram.DownloadUpdate
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.telegram.TelegramMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes and checks a zero-knowledge `TGE2` sentinel verifier in the user's private
 * `TelegramDrive System` channel so reinstalls/new devices immediately detect passphrase typos
 * without ever uploading the plaintext passphrase or any local device key.
 */
@Singleton
class PassVaultCloudVerifier @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val telegramClient: TelegramClient,
    private val backupCrypto: BackupCrypto,
    private val dispatchers: DispatchersProvider,
) {
    enum class CloudVerifyResult {
        MATCHED,
        MISMATCH,
        NO_CLOUD_VERIFIER,
        ERROR,
    }

    suspend fun publishVerifier(passphrase: String): Result<Long> = withContext(dispatchers.io) {
        runCatching {
            val verifierBytes = backupCrypto.createPassphraseVerifier(passphrase)
            val channelId = telegramClient.getOrCreateSystemChannel()
                ?: error("Telegram System channel is not available")
            val allChannels = runCatching { telegramClient.findAllSystemChannelIds() }
                .getOrDefault(listOf(channelId))
                .ifEmpty { listOf(channelId) }
            val previousMessages = allChannels.flatMap { cid ->
                runCatching {
                    findVerifierMessages(cid, stopOnFirstPageMatch = false)
                        .map { cid to it.id }
                }.getOrDefault(emptyList())
            }

            val dir = File(ctx.cacheDir, "vault_sync").apply { mkdirs() }
            val tempFile = File(dir, "verifier_${UUID.randomUUID()}.tgenc")
            try {
                tempFile.writeBytes(verifierBytes)
                val uploaded = telegramClient.uploadFile(
                    chatId = channelId,
                    localPath = tempFile.absolutePath,
                    fileName = VERIFIER_FILE_NAME,
                    mimeType = "application/octet-stream",
                    onProgress = { _, _ -> },
                )
                previousMessages.forEach { (cid, msgId) ->
                    if (cid != channelId || msgId != uploaded.messageId) {
                        runCatching { telegramClient.deleteMessage(cid, msgId) }
                    }
                }
                uploaded.messageId
            } finally {
                runCatching { tempFile.delete() }
            }
        }
    }

    suspend fun verifyAgainstCloud(candidatePassphrase: String): CloudVerifyResult =
        withContext(dispatchers.io) {
            try {
                val channels = telegramClient.findAllSystemChannelIds()
                if (channels.isEmpty()) return@withContext CloudVerifyResult.NO_CLOUD_VERIFIER

                val newest = channels.flatMap { cid ->
                    runCatching { findVerifierMessages(cid, stopOnFirstPageMatch = true) }
                        .getOrDefault(emptyList())
                }.filter { it.fileRemoteId.isNotBlank() }
                    .maxByOrNull { it.date }
                    ?: return@withContext CloudVerifyResult.NO_CLOUD_VERIFIER

                val dir = File(ctx.cacheDir, "vault_sync").apply { mkdirs() }
                val dest = File(dir, "verify_${UUID.randomUUID()}.tgenc")
                var downloadedFile: File? = null
                try {
                    val terminal = telegramClient.downloadFile(newest.fileRemoteId, dest.absolutePath)
                        .filter { it is DownloadUpdate.Done || it is DownloadUpdate.Failed }
                        .first()
                    if (terminal is DownloadUpdate.Failed) {
                        Log.w("PassVaultCloudVerifier: verifier download failed: ${terminal.message}")
                        return@withContext CloudVerifyResult.ERROR
                    }
                    val resolvedFile = File((terminal as DownloadUpdate.Done).localPath)
                    downloadedFile = resolvedFile
                    val bytes = resolvedFile.readBytes()
                    if (backupCrypto.verifyPassphraseAgainstVerifier(bytes, candidatePassphrase)) {
                        CloudVerifyResult.MATCHED
                    } else {
                        CloudVerifyResult.MISMATCH
                    }
                } finally {
                    runCatching { dest.delete() }
                    downloadedFile?.let { f -> runCatching { f.delete() } }
                }
            } catch (t: Throwable) {
                Log.w("PassVaultCloudVerifier: verifyAgainstCloud failed", t)
                CloudVerifyResult.ERROR
            }
        }

    suspend fun hasCloudVerifier(): Boolean = withContext(dispatchers.io) {
        try {
            val channels = telegramClient.findAllSystemChannelIds()
            channels.any { cid ->
                runCatching { findVerifierMessages(cid, stopOnFirstPageMatch = true).isNotEmpty() }
                    .getOrDefault(false)
            }
        } catch (t: Throwable) {
            Log.w("PassVaultCloudVerifier: hasCloudVerifier failed", t)
            false
        }
    }

    private suspend fun findVerifierMessages(
        channelId: Long,
        stopOnFirstPageMatch: Boolean,
        maxPages: Int = 10,
        pageSize: Int = 100,
    ): List<TelegramMessage> {
        val matches = mutableListOf<TelegramMessage>()
        var fromMessageId = 0L
        repeat(maxPages) {
            val page = telegramClient.fetchMessages(channelId, fromMessageId, pageSize)
            if (page.isEmpty()) return matches
            matches += page.filter { it.fileName == VERIFIER_FILE_NAME }
            if (stopOnFirstPageMatch && matches.isNotEmpty()) return matches
            val oldestId = page.minOfOrNull { it.id } ?: return matches
            if (page.size < pageSize || oldestId == fromMessageId) return matches
            fromMessageId = oldestId
        }
        return matches
    }

    companion object {
        const val VERIFIER_FILE_NAME = "tgdrive_passvault_verifier.tgenc"
    }
}
