package com.tgdrive.core.download

/**
 * Typed Coil image request for `.tgethumb` AES-256-GCM encrypted PassVault thumbnails.
 * Decrypted directly on-demand by [com.tgdrive.app.coil.EncryptedThumbFetcher] with zero
 * duplicate per-screen `ByteArray` maps.
 */
data class EncryptedThumbRequest(
    val chatId: Long,
    val messageId: Long,
    val localPath: String? = null,
    val passphraseFingerprint: String = "",
    val retryVersion: Int = 0,
)
