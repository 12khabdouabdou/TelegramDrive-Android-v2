package com.tgdrive.core.download

import android.net.Uri
import com.tgdrive.core.zip.ZipCompressionMode
import java.io.File

enum class UploadConflictStrategy {
    KEEP_BOTH,
    SKIP,
    REPLACE,
}

data class UploadEncryptionSpec(
    val enabled: Boolean = false,
    val useVaultKey: Boolean = true,
    val customPassphrase: String? = null,
)

data class UploadFileItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class NormalUploadOptions(
    val conflictStrategy: UploadConflictStrategy = UploadConflictStrategy.KEEP_BOTH,
    val encryption: UploadEncryptionSpec = UploadEncryptionSpec(),
    val autoSplitOversized: Boolean = true,
)

data class ZipUploadRequest(
    val chatId: Long,
    val baseName: String,
    val compressionMode: ZipCompressionMode = ZipCompressionMode.SMART,
    val maxPartBytesOverride: Long? = null,
    val excludePatterns: String = "",
    val encryption: UploadEncryptionSpec = UploadEncryptionSpec(),
)

sealed class DecryptOutcome {
    data class Success(
        val file: File,
        val displayName: String,
        val mimeType: String,
        val savedToDownloads: Boolean = false,
    ) : DecryptOutcome()

    data class WrongPassphrase(
        val message: String,
    ) : DecryptOutcome()

    data class Failed(
        val message: String,
    ) : DecryptOutcome()
}
