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
    val useSavedPassphrase: Boolean = true,
    val customPassphrase: String? = null,
)

data class UploadFileItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class NormalUploadOptions(
    val conflictStrategy: UploadConflictStrategy = UploadConflictStrategy.SKIP,
    val encryption: UploadEncryptionSpec = UploadEncryptionSpec(),
    val autoSplitOversized: Boolean = true,
    val wifiOnly: Boolean = false,
    val virtualPath: String = "",
)

data class ZipUploadRequest(
    val chatId: Long,
    val baseName: String,
    val compressionMode: ZipCompressionMode = ZipCompressionMode.SMART,
    val maxPartBytesOverride: Long? = null,
    val excludePatterns: String = "",
    val encryption: UploadEncryptionSpec = UploadEncryptionSpec(),
    val wifiOnly: Boolean = false,
    val virtualPath: String = "",
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
