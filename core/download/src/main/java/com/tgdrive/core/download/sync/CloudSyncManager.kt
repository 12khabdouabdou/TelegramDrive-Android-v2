package com.tgdrive.core.download.sync

import android.content.Context
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.dao.ZipEntryDao
import com.tgdrive.core.db.entity.SyncedFileEntity
import com.tgdrive.core.db.entity.TrashEntryEntity
import com.tgdrive.core.db.entity.ZipEntryEntity
import com.tgdrive.core.telegram.DownloadUpdate
import com.tgdrive.core.telegram.TelegramClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs local-only metadata (starred files, renames, folder colors, 30-day Trash bin,
 * AutoBackup synced-file ledger, and ZIP search catalog) to the hidden private
 * `"TelegramDrive System"` channel on Telegram using compact gzipped JSON snapshots.
 */
@Singleton
class CloudSyncManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val telegramClient: TelegramClient,
    private val fileCacheDao: FileCacheDao,
    private val folderDao: FolderDao,
    private val zipEntryDao: ZipEntryDao,
    private val trashEntryDao: TrashEntryDao,
    private val syncedFileDao: SyncedFileDao,
    private val preferences: AppPreferences,
    private val dispatchers: DispatchersProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val mutex = Mutex()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    @Volatile private var pushMetaJob: Job? = null
    @Volatile private var pushSearchJob: Job? = null

    private val localStarTimestamps = ConcurrentHashMap<Pair<Long, Long>, CloudSyncCodec.CloudStarRecord>()
    private val localRenameTimestamps = ConcurrentHashMap<Pair<Long, Long>, CloudSyncCodec.CloudRenameRecord>()
    private val localColorTimestamps = ConcurrentHashMap<Long, CloudSyncCodec.CloudFolderColorRecord>()
    private val localTrashTombstones = ConcurrentHashMap<Pair<Long, Long>, Long>()
    private val localPlaybackTimestamps =
        ConcurrentHashMap<Pair<Long, Long>, CloudSyncCodec.CloudPlaybackPositionRecord>()

    private val syncDir: File
        get() = File(ctx.cacheDir, "cloud_sync").apply { mkdirs() }

    fun noteStarChanged(chatId: Long, messageId: Long, isStarred: Boolean) {
        val now = System.currentTimeMillis()
        localStarTimestamps[chatId to messageId] =
            CloudSyncCodec.CloudStarRecord(chatId, messageId, isStarred, now)
        schedulePushMetadata()
    }

    fun noteFileRenamed(chatId: Long, messageId: Long, newName: String) {
        val now = System.currentTimeMillis()
        localRenameTimestamps[chatId to messageId] =
            CloudSyncCodec.CloudRenameRecord(chatId, messageId, newName, now)
        schedulePushMetadata()
    }

    fun noteFolderColorChanged(channelId: Long, color: Int) {
        val now = System.currentTimeMillis()
        localColorTimestamps[channelId] =
            CloudSyncCodec.CloudFolderColorRecord(channelId, color, now)
        schedulePushMetadata()
    }

    fun noteTrashRemoved(chatId: Long, messageId: Long) {
        localTrashTombstones[chatId to messageId] = System.currentTimeMillis()
        schedulePushMetadata()
    }

    fun notePlaybackPositionChanged(chatId: Long, messageId: Long, positionMs: Long) {
        localPlaybackTimestamps[chatId to messageId] = CloudSyncCodec.CloudPlaybackPositionRecord(
            chatId = chatId,
            messageId = messageId,
            positionMs = positionMs.coerceAtLeast(0L),
            updatedAtMs = System.currentTimeMillis(),
        )
        schedulePushMetadata()
    }

    fun schedulePushMetadata() {
        pushMetaJob?.cancel()
        pushMetaJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            runCatching { pushMetadataOnly() }
                .onFailure { t ->
                    if (t is CancellationException) throw t
                    Log.w("CloudSyncManager: debounced meta push failed", t)
                }
        }
    }

    fun schedulePushSearchIndex() {
        pushSearchJob?.cancel()
        pushSearchJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            runCatching { pushSearchIndexOnly() }
                .onFailure { t ->
                    if (t is CancellationException) throw t
                    Log.w("CloudSyncManager: debounced search push failed", t)
                }
        }
    }

    /**
     * Pull remote snapshots from `"TelegramDrive System"`, merge with local DB using LWW,
     * apply updates into Room, and auto-purge Trash entries older than 30 days.
     */
    suspend fun syncFromCloud(): Boolean = withContext(dispatchers.io) {
        mutex.withLock {
            _syncing.value = true
            try {
                val systemChatId = telegramClient.getOrCreateSystemChannel() ?: return@withLock false
                val remoteMeta = downloadSnapshot(systemChatId, CloudSyncCodec.META_SNAPSHOT_FILE) { bytes ->
                    CloudSyncCodec.decodeMetaGzip(bytes)
                }
                if (remoteMeta != null) {
                    val localMeta = buildLocalMetaSnapshot()
                    val merged = CloudSyncCodec.mergeMeta(localMeta, remoteMeta)
                    applyMergedMeta(merged)
                }

                val remoteSearch = downloadSnapshot(systemChatId, CloudSyncCodec.SEARCH_INDEX_FILE) { bytes ->
                    CloudSyncCodec.decodeSearchIndexGzip(bytes)
                }
                if (remoteSearch != null) {
                    val localSearch = buildLocalSearchSnapshot()
                    val mergedSearch = CloudSyncCodec.mergeSearchIndex(localSearch, remoteSearch)
                    applyMergedSearchIndex(mergedSearch)
                }

                purgeExpiredTrashLocked()
                preferences.update { it.copy(lastCloudSyncMs = System.currentTimeMillis()) }
                Log.i("CloudSyncManager: syncFromCloud complete")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("CloudSyncManager: syncFromCloud failed", t)
                false
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * Full two-way sync: pulls and merges remote state first, then uploads updated snapshots.
     */
    suspend fun syncNow(): Boolean = withContext(dispatchers.io) {
        mutex.withLock {
            _syncing.value = true
            try {
                val systemChatId = telegramClient.getOrCreateSystemChannel() ?: return@withLock false

                val remoteMeta = downloadSnapshot(systemChatId, CloudSyncCodec.META_SNAPSHOT_FILE) {
                    CloudSyncCodec.decodeMetaGzip(it)
                }
                val mergedMeta = CloudSyncCodec.mergeMeta(
                    buildLocalMetaSnapshot(),
                    remoteMeta ?: CloudSyncCodec.CloudMetaSnapshot(),
                )
                applyMergedMeta(mergedMeta)
                purgeExpiredTrashLocked()
                uploadSnapshot(
                    systemChatId = systemChatId,
                    fileName = CloudSyncCodec.META_SNAPSHOT_FILE,
                    bytes = CloudSyncCodec.encodeMetaGzip(buildLocalMetaSnapshot()),
                )

                val remoteSearch = downloadSnapshot(systemChatId, CloudSyncCodec.SEARCH_INDEX_FILE) {
                    CloudSyncCodec.decodeSearchIndexGzip(it)
                }
                val mergedSearch = CloudSyncCodec.mergeSearchIndex(
                    buildLocalSearchSnapshot(),
                    remoteSearch ?: CloudSyncCodec.CloudSearchIndexSnapshot(),
                )
                applyMergedSearchIndex(mergedSearch)
                uploadSnapshot(
                    systemChatId = systemChatId,
                    fileName = CloudSyncCodec.SEARCH_INDEX_FILE,
                    bytes = CloudSyncCodec.encodeSearchIndexGzip(mergedSearch),
                )

                preferences.update { it.copy(lastCloudSyncMs = System.currentTimeMillis()) }
                Log.i("CloudSyncManager: full syncNow complete")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("CloudSyncManager: syncNow failed", t)
                false
            } finally {
                _syncing.value = false
            }
        }
    }

    private suspend fun pushMetadataOnly() {
        mutex.withLock {
            _syncing.value = true
            try {
                val systemChatId = telegramClient.getOrCreateSystemChannel() ?: return
                val remote = downloadSnapshot(systemChatId, CloudSyncCodec.META_SNAPSHOT_FILE) {
                    CloudSyncCodec.decodeMetaGzip(it)
                }
                val merged = CloudSyncCodec.mergeMeta(
                    buildLocalMetaSnapshot(),
                    remote ?: CloudSyncCodec.CloudMetaSnapshot(),
                )
                applyMergedMeta(merged)
                purgeExpiredTrashLocked()
                uploadSnapshot(
                    systemChatId = systemChatId,
                    fileName = CloudSyncCodec.META_SNAPSHOT_FILE,
                    bytes = CloudSyncCodec.encodeMetaGzip(buildLocalMetaSnapshot()),
                )
                preferences.update { it.copy(lastCloudSyncMs = System.currentTimeMillis()) }
            } finally {
                _syncing.value = false
            }
        }
    }

    private suspend fun pushSearchIndexOnly() {
        mutex.withLock {
            _syncing.value = true
            try {
                val systemChatId = telegramClient.getOrCreateSystemChannel() ?: return
                val remote = downloadSnapshot(systemChatId, CloudSyncCodec.SEARCH_INDEX_FILE) {
                    CloudSyncCodec.decodeSearchIndexGzip(it)
                }
                val merged = CloudSyncCodec.mergeSearchIndex(
                    buildLocalSearchSnapshot(),
                    remote ?: CloudSyncCodec.CloudSearchIndexSnapshot(),
                )
                applyMergedSearchIndex(merged)
                uploadSnapshot(
                    systemChatId = systemChatId,
                    fileName = CloudSyncCodec.SEARCH_INDEX_FILE,
                    bytes = CloudSyncCodec.encodeSearchIndexGzip(merged),
                )
                preferences.update { it.copy(lastCloudSyncMs = System.currentTimeMillis()) }
            } finally {
                _syncing.value = false
            }
        }
    }

    private suspend fun buildLocalMetaSnapshot(): CloudSyncCodec.CloudMetaSnapshot {
        val now = System.currentTimeMillis()
        val files = fileCacheDao.getAll()
        val filesByKey = files.associateBy { it.chatId to it.messageId }
        val stars = files.filter { it.isStarred }.map { f ->
            localStarTimestamps[f.chatId to f.messageId]
                ?: CloudSyncCodec.CloudStarRecord(f.chatId, f.messageId, true, now)
        } + localStarTimestamps.values.filter { !it.isStarred }

        val renames = ArrayList<CloudSyncCodec.CloudRenameRecord>()
        renames.addAll(localRenameTimestamps.values)
        // Include current DB filenames if we don't have an explicit override yet (low timestamp so explicit overrides win)
        filesByKey.forEach { (key, entity) ->
            if (!localRenameTimestamps.containsKey(key)) {
                renames += CloudSyncCodec.CloudRenameRecord(entity.chatId, entity.messageId, entity.fileName, entity.date)
            }
        }

        val folders = folderDao.getAll()
        val foldersById = folders.associateBy { it.id }
        val colors = folders.map { folder ->
            localColorTimestamps[folder.channelId]
                ?: CloudSyncCodec.CloudFolderColorRecord(folder.channelId, folder.color, folder.createdAt)
        }

        val trashRows = trashEntryDao.getAll()
        val activeTrashKeys = trashRows.mapTo(HashSet()) { it.chatId to it.messageId }
        val trash = trashRows.map { t ->
            CloudSyncCodec.CloudTrashRecord(
                messageId = t.messageId,
                chatId = t.chatId,
                fileName = t.fileName,
                fileSize = t.fileSize,
                mimeType = t.mimeType,
                thumbnailPath = t.thumbnailPath,
                remoteId = t.remoteId,
                originalDate = t.originalDate,
                isStarred = t.isStarred,
                deletedAt = t.deletedAt,
                purged = false,
            )
        } + localTrashTombstones.entries
            .filter { (key, _) -> key !in activeTrashKeys }
            .map { (key, ts) ->
                CloudSyncCodec.CloudTrashRecord(
                    messageId = key.second,
                    chatId = key.first,
                    fileName = "",
                    fileSize = 0L,
                    mimeType = null,
                    thumbnailPath = null,
                    remoteId = "",
                    originalDate = 0L,
                    isStarred = false,
                    deletedAt = ts,
                    purged = true,
                )
            }

        val synced = syncedFileDao.getAll().map { s ->
            val channelId = s.folderId?.let { foldersById[it]?.channelId }
            CloudSyncCodec.CloudSyncedFileRecord(
                localPath = s.localPath,
                telegramMessageId = s.telegramMessageId,
                sha256 = s.sha256,
                size = s.size,
                syncedAt = s.syncedAt,
                channelId = channelId,
            )
        }

        val playbackPositions = LinkedHashMap<Pair<Long, Long>, CloudSyncCodec.CloudPlaybackPositionRecord>()
        files.filter { it.playbackPositionMs > 0L }.forEach { f ->
            val key = f.chatId to f.messageId
            playbackPositions[key] = localPlaybackTimestamps[key]
                ?: CloudSyncCodec.CloudPlaybackPositionRecord(
                    chatId = f.chatId,
                    messageId = f.messageId,
                    positionMs = f.playbackPositionMs,
                    updatedAtMs = f.lastAccessed.takeIf { it > 0L } ?: 1L,
                )
        }
        localPlaybackTimestamps.forEach { (key, rec) ->
            playbackPositions[key] = rec
        }

        return CloudSyncCodec.CloudMetaSnapshot(
            version = 1,
            updatedAtMs = now,
            stars = stars,
            renames = renames,
            folderColors = colors,
            trash = trash,
            syncedFiles = synced,
            playbackPositions = playbackPositions.values.toList(),
        )
    }

    private suspend fun buildLocalSearchSnapshot(): CloudSyncCodec.CloudSearchIndexSnapshot {
        val entries = zipEntryDao.getAll().map { e ->
            CloudSyncCodec.CloudZipEntryRecord(
                chatId = e.chatId,
                zipMessageId = e.zipMessageId,
                zipFileName = e.zipFileName,
                entryPath = e.entryPath,
                entryName = e.entryName,
                entrySize = e.entrySize,
                isEncrypted = e.isEncrypted,
                indexedAt = e.indexedAt,
            )
        }
        return CloudSyncCodec.CloudSearchIndexSnapshot(
            version = 1,
            updatedAtMs = System.currentTimeMillis(),
            zipEntries = entries,
        )
    }

    private suspend fun applyMergedMeta(merged: CloudSyncCodec.CloudMetaSnapshot) {
        val existingByKey = fileCacheDao.getAll().associateBy { it.chatId to it.messageId }
        merged.stars.forEach { star ->
            localStarTimestamps[star.chatId to star.messageId] = star
            val local = existingByKey[star.chatId to star.messageId]
            if (local != null && local.isStarred != star.isStarred) {
                runCatching { fileCacheDao.setStarred(star.chatId, star.messageId, star.isStarred) }
            }
        }

        merged.renames.forEach { ren ->
            localRenameTimestamps[ren.chatId to ren.messageId] = ren
            val local = existingByKey[ren.chatId to ren.messageId]
            if (local != null && local.fileName != ren.fileName) {
                runCatching { fileCacheDao.updateFileName(ren.chatId, ren.messageId, ren.fileName) }
            }
        }

        merged.folderColors.forEach { fc ->
            localColorTimestamps[fc.channelId] = fc
            val folder = folderDao.getByChannelId(fc.channelId)
            if (folder != null && folder.color != fc.color) {
                runCatching { folderDao.updateColor(folder.id, fc.color) }
            }
        }

        val activeTrash = mutableListOf<TrashEntryEntity>()
        merged.trash.forEach { tr ->
            if (tr.purged || tr.fileName.isBlank()) {
                localTrashTombstones[tr.chatId to tr.messageId] = tr.deletedAt
                runCatching { trashEntryDao.deleteById(tr.chatId, tr.messageId) }
            } else {
                activeTrash += TrashEntryEntity(
                    messageId = tr.messageId,
                    chatId = tr.chatId,
                    fileName = tr.fileName,
                    fileSize = tr.fileSize,
                    mimeType = tr.mimeType,
                    thumbnailPath = tr.thumbnailPath,
                    remoteId = tr.remoteId,
                    originalDate = tr.originalDate,
                    isStarred = tr.isStarred,
                    deletedAt = tr.deletedAt,
                )
                runCatching { fileCacheDao.deleteById(tr.chatId, tr.messageId) }
            }
        }
        if (activeTrash.isNotEmpty()) {
            runCatching { trashEntryDao.upsertAll(activeTrash) }
        }

        merged.syncedFiles.forEach { sf ->
            val folderDbId = sf.channelId?.let { folderDao.getByChannelId(it)?.id }
            runCatching {
                val existing = syncedFileDao.findByPathAndSize(sf.localPath, sf.size)
                if (existing == null) {
                    syncedFileDao.upsert(
                        SyncedFileEntity(
                            localPath = sf.localPath,
                            telegramMessageId = sf.telegramMessageId,
                            sha256 = sf.sha256,
                            size = sf.size,
                            syncedAt = sf.syncedAt,
                            folderId = folderDbId,
                        ),
                    )
                }
            }
        }

        merged.playbackPositions.forEach { pos ->
            localPlaybackTimestamps[pos.chatId to pos.messageId] = pos
            val local = existingByKey[pos.chatId to pos.messageId]
            if (local != null && local.playbackPositionMs != pos.positionMs) {
                runCatching { fileCacheDao.updatePlaybackPosition(pos.chatId, pos.messageId, pos.positionMs) }
            }
        }
    }

    private suspend fun applyMergedSearchIndex(merged: CloudSyncCodec.CloudSearchIndexSnapshot) {
        if (merged.zipEntries.isEmpty()) return
        val entities = merged.zipEntries.map { e ->
            ZipEntryEntity(
                chatId = e.chatId,
                zipMessageId = e.zipMessageId,
                zipFileName = e.zipFileName,
                entryPath = e.entryPath,
                entryName = e.entryName,
                entrySize = e.entrySize,
                isEncrypted = e.isEncrypted,
                indexedAt = e.indexedAt,
            )
        }
        entities.chunked(250).forEach { batch ->
            runCatching { zipEntryDao.upsertAll(batch) }
        }
    }

    private suspend fun purgeExpiredTrashLocked() {
        val cutoff = System.currentTimeMillis() - TRASH_RETENTION_MS
        val expired = runCatching { trashEntryDao.getOlderThan(cutoff) }.getOrDefault(emptyList())
        for (item in expired) {
            try {
                telegramClient.deleteMessage(item.chatId, item.messageId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("CloudSyncManager: failed deleting expired trash msg ${item.messageId}", t)
            }
            runCatching { trashEntryDao.deleteById(item.chatId, item.messageId) }
            localTrashTombstones[item.chatId to item.messageId] = System.currentTimeMillis()
        }
    }

    private suspend fun <T> downloadSnapshot(
        systemChatId: Long,
        fileName: String,
        decode: (ByteArray) -> T?,
    ): T? {
        val msgs = runCatching { telegramClient.fetchMessages(systemChatId, 0L, 40) }.getOrDefault(emptyList())
        val match = msgs
            .filter { it.fileName == fileName && it.fileRemoteId.isNotEmpty() }
            .maxByOrNull { it.date } ?: return null
        val terminal = telegramClient.downloadFile(match.fileRemoteId, "")
            .filter { it is DownloadUpdate.Done || it is DownloadUpdate.Failed }
            .first()
        if (terminal !is DownloadUpdate.Done) return null
        val file = File(terminal.localPath)
        if (!file.isFile) return null
        return decode(file.readBytes())
    }

    private suspend fun uploadSnapshot(
        systemChatId: Long,
        fileName: String,
        bytes: ByteArray,
    ) {
        val oldMsgs = runCatching { telegramClient.fetchMessages(systemChatId, 0L, 40) }
            .getOrDefault(emptyList())
            .filter { it.fileName == fileName }

        val outFile = File(syncDir, fileName)
        try {
            outFile.writeBytes(bytes)
            telegramClient.uploadFile(
                chatId = systemChatId,
                localPath = outFile.absolutePath,
                fileName = fileName,
                mimeType = "application/gzip",
            ) { _, _ -> }

            oldMsgs.forEach { old ->
                runCatching { telegramClient.deleteMessage(systemChatId, old.id) }
            }
        } finally {
            runCatching { outFile.delete() }
        }
    }

    companion object {
        private const val PUSH_DEBOUNCE_MS = 8_000L
        const val TRASH_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
