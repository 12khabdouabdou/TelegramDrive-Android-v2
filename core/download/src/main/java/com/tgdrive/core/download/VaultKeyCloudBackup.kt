package com.tgdrive.core.download

import android.content.Context
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.telegram.DownloadUpdate
import com.tgdrive.core.telegram.TelegramClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backs up and restores the 256-bit `TGE1` Device Vault Key inside the user's
 * private TelegramDrive System channel, encrypted under a separate user-chosen
 * recovery passphrase (`TGE2`).
 */
@Singleton
class VaultKeyCloudBackup @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val telegramClient: TelegramClient,
    private val backupCrypto: BackupCrypto,
    private val dispatchers: DispatchersProvider,
) {
    suspend fun backupToTelegram(recoveryPassphrase: String): Result<Long> = withContext(dispatchers.io) {
        runCatching {
            val wrapped = backupCrypto.wrapVaultKeyWithPassphrase(recoveryPassphrase)
            val channelId = telegramClient.getOrCreateSystemChannel()
                ?: error("Telegram System channel is not available")
            val previousBackups = runCatching {
                telegramClient.fetchMessages(channelId, 0L, 50)
                    .filter { it.fileName == VAULT_KEY_CLOUD_FILE }
            }.getOrDefault(emptyList())

            val dir = File(ctx.cacheDir, "vault_sync").apply { mkdirs() }
            val tempFile = File(dir, "vault_${UUID.randomUUID()}.tgenc")
            try {
                tempFile.writeBytes(wrapped)
                val uploaded = telegramClient.uploadFile(
                    chatId = channelId,
                    localPath = tempFile.absolutePath,
                    fileName = VAULT_KEY_CLOUD_FILE,
                    mimeType = "application/octet-stream",
                    onProgress = { _, _ -> },
                )
                previousBackups.forEach { old ->
                    if (old.id != uploaded.messageId) {
                        runCatching { telegramClient.deleteMessage(channelId, old.id) }
                    }
                }
                uploaded.messageId
            } finally {
                runCatching { tempFile.delete() }
            }
        }
    }

    suspend fun restoreFromTelegram(recoveryPassphrase: String): Result<String> = withContext(dispatchers.io) {
        runCatching {
            val channelId = telegramClient.getOrCreateSystemChannel()
                ?: error("Telegram System channel is not available")
            val match = telegramClient.fetchMessages(channelId, 0L, 50)
                .filter { it.fileName == VAULT_KEY_CLOUD_FILE && it.fileRemoteId.isNotBlank() }
                .maxByOrNull { it.date }
                ?: error("No cloud Vault Key backup found in TelegramDrive System channel")

            val dir = File(ctx.cacheDir, "vault_sync").apply { mkdirs() }
            val dest = File(dir, "restore_${UUID.randomUUID()}.tgenc")
            try {
                val terminal = telegramClient.downloadFile(match.fileRemoteId, dest.absolutePath)
                    .filter { it is DownloadUpdate.Done || it is DownloadUpdate.Failed }
                    .first()
                if (terminal is DownloadUpdate.Failed) {
                    error(terminal.message)
                }
                val downloadedFile = File((terminal as DownloadUpdate.Done).localPath)
                val wrappedBytes = downloadedFile.readBytes()
                val ok = backupCrypto.unwrapAndImportVaultKey(wrappedBytes, recoveryPassphrase)
                if (!ok) error("Restored Vault Key payload had invalid length")
                backupCrypto.vaultKeyFingerprint()
            } finally {
                runCatching { dest.delete() }
            }
        }
    }

    suspend fun hasCloudBackup(): Boolean = withContext(dispatchers.io) {
        try {
            val channelId = telegramClient.getOrCreateSystemChannel() ?: return@withContext false
            telegramClient.fetchMessages(channelId, 0L, 50).any { it.fileName == VAULT_KEY_CLOUD_FILE }
        } catch (t: Throwable) {
            Log.w("VaultKeyCloudBackup: hasCloudBackup check failed", t)
            false
        }
    }

    companion object {
        const val VAULT_KEY_CLOUD_FILE = "tgdrive_vault_key_backup.tgenc"
    }
}
