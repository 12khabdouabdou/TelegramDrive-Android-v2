package com.tgdrive.feature.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.tgdrive.core.telegram.StreamFileState
import com.tgdrive.core.telegram.TelegramClient
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Progressive Media3 [androidx.media3.datasource.DataSource] that streams byte ranges
 * directly from TDLib while downloading on demand, enabling near-instant playback
 * and scrubbing before the full video finishes caching.
 */
@OptIn(UnstableApi::class)
class TdLibVideoDataSource(
    private val telegramClient: TelegramClient,
    private val remoteId: String,
    private val fallbackTotalBytes: Long,
    private val chatId: Long = 0L,
    private val messageId: Long = 0L,
    private val onOpenedFileId: (Int) -> Unit = {},
) : BaseDataSource(/* isNetwork = */ true) {

    @Volatile
    private var fileId: Int = 0
    private var totalSize: Long = 0L
    private var position: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false
    private var dataUri: Uri? = null
    private var cachedState: StreamFileState? = null
    private var raf: RandomAccessFile? = null
    private var openedPath: String? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        dataUri = dataSpec.uri
        val info = runBlocking {
            telegramClient.openStreamFile(remoteId) ?: run {
                if (chatId != 0L && messageId != 0L) {
                    val refreshed = telegramClient.refreshMessageRemoteId(chatId, messageId)
                    if (!refreshed.isNullOrBlank() && refreshed != remoteId) {
                        telegramClient.openStreamFile(refreshed)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } ?: throw IOException("Unable to open Telegram stream")

        fileId = info.fileId
        onOpenedFileId(fileId)
        totalSize = if (info.size > 0L) info.size else fallbackTotalBytes.coerceAtLeast(0L)
        position = dataSpec.position.coerceAtLeast(0L)

        if (totalSize in 1 until position) {
            throw IOException("Requested offset $position exceeds file size $totalSize")
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (totalSize > 0L) {
            (totalSize - position).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }

        if (info.isCompleted && info.localPath.isNotEmpty()) {
            cachedState = StreamFileState(
                localPath = info.localPath,
                downloadOffset = 0L,
                downloadedPrefixSize = totalSize,
                isCompleted = true,
            )
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (totalSize > 0L && position >= totalSize) return C.RESULT_END_OF_INPUT

        val maxToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length.toLong()
        } else {
            minOf(length.toLong(), bytesRemaining)
        }

        val state = ensureByteAvailable(position, maxToRead)
            ?: if (totalSize > 0L && position >= totalSize) return C.RESULT_END_OF_INPUT
            else throw IOException("Timed out waiting for stream chunk at offset $position")

        if (state.localPath.isEmpty()) {
            throw IOException("TDLib local stream path unavailable")
        }

        val fileHandle = ensureRandomAccessFile(state.localPath)
        val avail = if (state.isCompleted) {
            maxToRead
        } else {
            (state.downloadOffset + state.downloadedPrefixSize - position).coerceIn(1L, maxToRead)
        }

        fileHandle.seek(position)
        val readCount = fileHandle.read(buffer, offset, avail.toInt())
        if (readCount <= 0) {
            return C.RESULT_END_OF_INPUT
        }

        position += readCount
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= readCount
        }
        bytesTransferred(readCount)
        return readCount
    }

    private fun ensureByteAvailable(targetOffset: Long, requestedBytes: Long): StreamFileState? {
        val current = cachedState
        val covered = current != null && current.localPath.isNotEmpty() && (
            current.isCompleted || (
                current.downloadOffset <= targetOffset &&
                    current.downloadOffset + current.downloadedPrefixSize > targetOffset
                )
            )
        if (covered) return current

        val remainingInFile = if (totalSize > 0L) (totalSize - targetOffset).coerceAtLeast(1L) else PREFETCH_CHUNK_BYTES
        val chunkLen = minOf(maxOf(requestedBytes, PREFETCH_CHUNK_BYTES), remainingInFile)
        val refreshed = runBlocking {
            telegramClient.ensureFileRangeDownloaded(fileId, targetOffset, chunkLen)
        }
        if (refreshed != null && refreshed.localPath.isNotEmpty() && (
                refreshed.isCompleted || (
                    refreshed.downloadOffset <= targetOffset &&
                        refreshed.downloadOffset + refreshed.downloadedPrefixSize > targetOffset
                    )
                )
        ) {
            cachedState = refreshed
            return refreshed
        }
        return null
    }

    private fun ensureRandomAccessFile(path: String): RandomAccessFile {
        val existing = raf
        if (existing != null && openedPath == path) return existing
        runCatching { existing?.close() }
        val openedRaf = RandomAccessFile(path, "r")
        raf = openedRaf
        openedPath = path
        return openedRaf
    }

    override fun getUri(): Uri? = dataUri

    override fun close() {
        dataUri = null
        cachedState = null
        openedPath = null
        try {
            raf?.close()
        } catch (_: IOException) {
        } finally {
            raf = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        private const val PREFETCH_CHUNK_BYTES = 512L * 1024L
    }
}
