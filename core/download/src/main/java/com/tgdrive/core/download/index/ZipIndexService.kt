package com.tgdrive.core.download.index

import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.ZipEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.ZipEntryEntity
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.zip.ZipEntryInfo
import com.tgdrive.core.zip.ZipUploader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Indexes internal entries of `.zip` archives into Room ([ZipEntryDao]) and triggers
 * cloud index sync without downloading entire multi-gigabyte archives:
 *
 * 1. Immediate indexing on upload (`indexPartEntries`).
 * 2. Local archive listing when already downloaded (`indexLocalEntries`).
 * 3. Remote Central Directory tail-peek (<64 KB typically) via TDLib byte-range reads (`peekRemoteZip`).
 */
@Singleton
class ZipIndexService @Inject constructor(
    private val telegramClient: TelegramClient,
    private val zipEntryDao: ZipEntryDao,
    private val fileCacheDao: FileCacheDao,
    private val cloudSyncManager: CloudSyncManager,
    private val dispatchers: DispatchersProvider,
) {
    suspend fun indexPartEntries(
        chatId: Long,
        zipMessageId: Long,
        zipFileName: String,
        entryPaths: List<String>,
        encrypted: Boolean,
    ) = withContext(dispatchers.io) {
        if (entryPaths.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val rows = entryPaths.mapNotNull { raw ->
            val path = raw.trimEnd('/')
            if (path.isEmpty()) return@mapNotNull null
            ZipEntryEntity(
                chatId = chatId,
                zipMessageId = zipMessageId,
                zipFileName = zipFileName,
                entryPath = raw,
                entryName = path.substringAfterLast('/'),
                entrySize = 0L,
                isEncrypted = encrypted,
                indexedAt = now,
            )
        }
        if (rows.isNotEmpty()) {
            zipEntryDao.deleteForZip(chatId, zipMessageId)
            rows.chunked(250).forEach { zipEntryDao.upsertAll(it) }
            cloudSyncManager.schedulePushSearchIndex()
        }
    }

    suspend fun indexLocalEntries(
        chatId: Long,
        zipMessageId: Long,
        zipFileName: String,
        entries: List<ZipEntryInfo>,
    ) = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val rows = entries.filterNot { it.isDirectory }.mapNotNull { e ->
            val clean = e.name.trimEnd('/')
            if (clean.isEmpty()) return@mapNotNull null
            ZipEntryEntity(
                chatId = chatId,
                zipMessageId = zipMessageId,
                zipFileName = zipFileName,
                entryPath = e.name,
                entryName = clean.substringAfterLast('/'),
                entrySize = e.uncompressedSize.coerceAtLeast(0L),
                isEncrypted = e.isEncrypted,
                indexedAt = now,
            )
        }
        if (rows.isNotEmpty()) {
            zipEntryDao.deleteForZip(chatId, zipMessageId)
            rows.chunked(250).forEach { zipEntryDao.upsertAll(it) }
            cloudSyncManager.schedulePushSearchIndex()
        }
    }

    /**
     * Fetches only the ZIP Central Directory near the end of the remote file via TDLib
     * range download, indexes all entries into Room, and returns the count indexed.
     */
    suspend fun peekRemoteZip(zipFile: FileCacheEntity): Int = withContext(dispatchers.io) {
        if (zipFile.remoteId.isBlank()) return@withContext 0
        try {
            val totalSize = if (zipFile.fileSize > 0L) {
                zipFile.fileSize
            } else {
                telegramClient.openStreamFile(zipFile.remoteId)?.size ?: 0L
            }
            if (totalSize < 22L) return@withContext 0

            val tailLen = minOf(TAIL_PEEK_BYTES, totalSize)
            val tailOffset = (totalSize - tailLen).coerceAtLeast(0L)
            val tailBytes = telegramClient.downloadFileRangeBytes(zipFile.remoteId, tailOffset, tailLen)
                ?: return@withContext 0

            var entries = ZipUploader.parseCentralDirectoryFromTail(tailBytes, tailOffset)
            if (entries == null) {
                val range = ZipUploader.findCentralDirectoryRange(tailBytes, tailOffset) ?: return@withContext 0
                if (range.size <= 0L || range.size > MAX_REMOTE_CD_BYTES) {
                    Log.w("ZipIndexService: skipping oversized Central Directory (${range.size} bytes) for ${zipFile.fileName}")
                    return@withContext 0
                }
                val cdBytes = telegramClient.downloadFileRangeBytes(zipFile.remoteId, range.offset, range.size)
                    ?: return@withContext 0
                entries = ZipUploader.parseCentralDirectoryEntries(cdBytes)
            }

            if (entries.isEmpty()) return@withContext 0
            indexLocalEntries(zipFile.chatId, zipFile.messageId, zipFile.fileName, entries)
            val nonDirCount = entries.count { !it.isDirectory }
            Log.i("ZipIndexService: remote-peeked $nonDirCount entries in ${zipFile.fileName}")
            nonDirCount
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("ZipIndexService: peekRemoteZip failed for ${zipFile.fileName}", t)
            0
        }
    }

    suspend fun getUnindexedZips(): List<FileCacheEntity> = withContext(dispatchers.io) {
        val indexedIds = zipEntryDao.indexedZipMessageIds().toHashSet()
        fileCacheDao.getAllZips()
            .filter { it.messageId !in indexedIds && it.remoteId.isNotBlank() }
    }

    suspend fun indexAllUncachedZips(
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int = withContext(dispatchers.io) {
        val targets = getUnindexedZips()
        if (targets.isEmpty()) return@withContext 0
        var totalIndexedEntries = 0
        targets.forEachIndexed { idx, zip ->
            totalIndexedEntries += peekRemoteZip(zip)
            onProgress(idx + 1, targets.size)
        }
        totalIndexedEntries
    }

    companion object {
        private const val TAIL_PEEK_BYTES = 65_536L
        private const val MAX_REMOTE_CD_BYTES = 4L * 1024L * 1024L
    }
}
