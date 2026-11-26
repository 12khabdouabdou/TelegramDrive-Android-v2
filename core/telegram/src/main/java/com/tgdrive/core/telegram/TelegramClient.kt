package com.tgdrive.core.telegram

import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import com.tgdrive.core.common.DispatchersProvider

interface TelegramAuthObserver {
    val authState: StateFlow<AuthorizationState>
}

interface TelegramClient : TelegramAuthObserver {

    companion object {
        const val MAX_FILE_SIZE: Long = 2L * 1024 * 1024 * 1024
        const val MAX_FILE_SIZE_STANDARD: Long = 2L * 1024 * 1024 * 1024
        const val MAX_FILE_SIZE_PREMIUM: Long = 4L * 1024 * 1024 * 1024
        const val SAFE_CHUNK_LIMIT_STANDARD: Long = 1_900L * 1024 * 1024
        const val SAFE_CHUNK_LIMIT_PREMIUM: Long = 3_800L * 1024 * 1024
        const val SYSTEM_CHANNEL_TITLE: String = "TelegramDrive System"
    }

    val authCodeInfo: StateFlow<AuthCodeInfo?>

    suspend fun initialize(apiId: Int, apiHash: String, sessionDir: String)

    // Auth
    suspend fun sendPhoneNumber(phone: String)
    suspend fun resendAuthCode()
    suspend fun sendAuthCode(code: String)
    suspend fun requestQrCodeLogin()
    suspend fun sendEmailAddress(email: String)
    suspend fun sendEmailCode(code: String)
    suspend fun sendAuthPassword(password: String)
    suspend fun registerNewUser(firstName: String, lastName: String)
    suspend fun logout()

    // Flow of message ID updates (local → server). Fires when TDLib confirms
    // a sent message and assigns the final server message ID.
    val messageIdUpdates: kotlinx.coroutines.flow.SharedFlow<MessageIdUpdate>

    // File / chat
    suspend fun fetchMessages(chatId: Long, fromMessageId: Long, limit: Int): List<TelegramMessage>
    suspend fun searchMessages(chatId: Long, query: String, limit: Int = 50): List<TelegramMessage>

    /**
     * Search across ALL chats (Saved Messages + every folder) for files
     * whose name matches [query]. Backed by TDLib's `SearchMessages`
     * (the global variant, not `SearchChatMessages`).
     *
     * Returns results tagged with their source [chatId] so the UI can
     * navigate the user to the right folder when they tap a result.
     */
    suspend fun searchMessagesGlobal(query: String, limit: Int = 50): List<TelegramMessage>
    suspend fun listFolders(): List<TelegramFolder>
    suspend fun createFolder(title: String): TelegramFolder
    suspend fun renameFolder(chatId: Long, newTitle: String)
    suspend fun deleteFolder(chatId: Long)

    /** Upload a file. Returns UploadResult with server message ID + remote file ID. */
    suspend fun uploadFile(
        chatId: Long,
        localPath: String,
        fileName: String,
        mimeType: String,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit,
    ): UploadResult

    suspend fun deleteMessage(chatId: Long, messageId: Long)
    suspend fun moveFile(fromChatId: Long, messageId: Long, toChatId: Long): Long
    suspend fun renameFile(chatId: Long, messageId: Long, newFileName: String)
    fun downloadFile(remoteId: String, localPath: String): Flow<DownloadUpdate>
    suspend fun clearDownloadCache()
    suspend fun optimizeStorage(maxBytes: Long) {}
    suspend fun myUserId(): Long
    suspend fun isPremiumUser(): Boolean = false
    suspend fun maxUploadSizeBytes(): Long =
        if (isPremiumUser()) MAX_FILE_SIZE_PREMIUM else MAX_FILE_SIZE_STANDARD
    suspend fun safeZipChunkLimitBytes(): Long =
        if (isPremiumUser()) SAFE_CHUNK_LIMIT_PREMIUM else SAFE_CHUNK_LIMIT_STANDARD
    suspend fun openStreamFile(remoteId: String): StreamFileInfo? = null
    suspend fun ensureFileRangeDownloaded(fileId: Int, offset: Long, length: Long): StreamFileState? = null
    suspend fun cancelFileStream(fileId: Int) {}
    suspend fun getOrCreateSystemChannel(): Long? = null
    suspend fun findAllSystemChannelIds(): List<Long> = emptyList()
    suspend fun downloadFileRangeBytes(remoteId: String, offset: Long, length: Long): ByteArray? = null
    suspend fun refreshMessageRemoteId(chatId: Long, messageId: Long): String? = null
    val thumbnailUpdates: kotlinx.coroutines.flow.SharedFlow<ThumbnailUpdate>
        get() = EMPTY_THUMBNAIL_UPDATES
    suspend fun close()

    /**
     * Generate a Telegram link to a specific message in a chat.
     *
     * For Saved Messages (user's own chat), the link format is:
     *   https://t.me/me/ephemeral/<messageId>  (not publicly shareable)
     *
     * For private channels where the user is the creator, the link is:
     *   https://t.me/c/<channelId>/<messageId>
     *
     * Returns null if the chat is not a shareable channel (e.g. Saved Messages
     * can't be shared via link — the recipient would need to be in the channel).
     */
    suspend fun generateShareLink(chatId: Long, messageId: Long): String?
}

private val EMPTY_THUMBNAIL_UPDATES: kotlinx.coroutines.flow.SharedFlow<ThumbnailUpdate> =
    MutableSharedFlow<ThumbnailUpdate>().asSharedFlow()

/** Fired when a Telegram thumbnail download completes with a verified local path. */
data class ThumbnailUpdate(
    val chatId: Long,
    val messageId: Long,
    val localPath: String,
)

/** Result of uploading a file — contains both the server message ID and remote file ID. */
data class UploadResult(
    val messageId: Long,
    val remoteId: String,
)

/** Fired when TDLib assigns a server message ID to a previously-sent message. */
data class MessageIdUpdate(
    val oldMessageId: Long,
    val newMessageId: Long,
)

sealed interface DownloadUpdate {
    data class Progress(val downloaded: Long, val total: Long) : DownloadUpdate
    data class Done(val localPath: String) : DownloadUpdate
    data class Failed(val message: String) : DownloadUpdate
}

data class StreamFileInfo(
    val fileId: Int,
    val size: Long,
    val localPath: String,
    val isCompleted: Boolean,
)

data class StreamFileState(
    val localPath: String,
    val downloadOffset: Long,
    val downloadedPrefixSize: Long,
    val isCompleted: Boolean,
)

data class TelegramFolder(
    val chatId: Long,
    val title: String,
    val isSelf: Boolean,
    val isChannel: Boolean,
    val createdAtMs: Long,
)

class TelegramException(val code: Int, val errorMessage: String) : RuntimeException("TDLib $code: $errorMessage") {
    companion object {
        fun fromError(err: TdApi.Error): TelegramException =
            TelegramException(err.code, err.message.orEmpty())
    }
}
