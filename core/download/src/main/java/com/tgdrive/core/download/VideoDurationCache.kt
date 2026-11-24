package com.tgdrive.core.download

import android.media.MediaMetadataRetriever
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.db.entity.FileCacheEntity
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight in-memory cache of local video durations extracted via [MediaMetadataRetriever].
 */
@Singleton
class VideoDurationCache @Inject constructor(
    private val downloadManager: DownloadManager,
    private val offlinePinStore: OfflinePinStore,
    private val dispatchers: DispatchersProvider,
) {

    private val durationMsCache = ConcurrentHashMap<String, Long>()

    fun key(chatId: Long, messageId: Long): String = "${chatId}_$messageId"

    fun getCachedDurationMs(chatId: Long, messageId: Long): Long? =
        durationMsCache[key(chatId, messageId)]

    suspend fun resolveDurationMs(entity: FileCacheEntity): Long? {
        val k = key(entity.chatId, entity.messageId)
        durationMsCache[k]?.let { return it }
        val localPath = offlinePinStore.getPinnedFilePathSync(entity.chatId, entity.messageId)
            ?.takeIf { File(it).isFile }
            ?: downloadManager.findItem(entity.chatId, entity.messageId)
                ?.takeIf { it.status == DownloadStatus.DONE }
                ?.localPath
                ?.takeIf { File(it).isFile }
            ?: return null

        return withContext(dispatchers.io) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(localPath)
                    val ms = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                    if (ms != null && ms > 0L) {
                        durationMsCache[k] = ms
                        ms
                    } else {
                        null
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull()
        }
    }

    companion object {
        fun formatDuration(durationMs: Long): String {
            val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
    }
}
