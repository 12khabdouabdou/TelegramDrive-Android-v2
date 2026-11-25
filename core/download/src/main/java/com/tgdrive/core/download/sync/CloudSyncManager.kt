package com.tgdrive.core.download.sync

import android.content.Context
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.db.dao.AlbumDao
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.dao.ZipEntryDao
import com.tgdrive.core.db.entity.AlbumEntity
import com.tgdrive.core.db.entity.AlbumItemEntity
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
    private val albumDao: AlbumDao,
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
    private val localVirtualPathTimestamps =
        ConcurrentHashMap<Pair<Long, Long>, CloudSyncCodec.CloudVirtualPathRecord>()
    private val localLockTimestamps =
        ConcurrentHashMap<Pair<Long, Long>, CloudSyncCodec.CloudLockRecord>()
    private val localNoteTimestamps =
        ConcurrentHashMap<Pair<Long, Long>, CloudSyncCodec.CloudNoteRecord>()
    private val localAlbumTimestamps =
        ConcurrentHashMap<String, CloudSyncCodec.CloudAlbumRecord>()
    private val localAlbumItemTimestamps =
        ConcurrentHashMap<Triple<String, Long, Long>, CloudSyncCodec.CloudAlbumItemRecord>()
    private val localAiTimestamps =
        ConcurrentHashMap<Pair<Long, Long>, CloudSyncCodec.CloudAiIndexRecord>()
    private val localEncryptedThumbTimestamps =
        ConcurrentHashMap<Pair<Long, Long>, CloudSyncCodec.CloudEncryptedThumbRecord>()

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

    fun noteVirtualPathChanged(chatId: Long, messageId: Long, virtualPath: String) {
        localVirtualPathTimestamps[chatId to messageId] = CloudSyncCodec.CloudVirtualPathRecord(
            chatId = chatId,
            messageId = messageId,
            virtualPath = virtualPath,
            updatedAtMs = System.currentTimeMillis(),
        )
        schedulePushMetadata()
    }

    fun noteLockChanged(chatId: Long, messageId: Long, isLocked: Boolean) {
        localLockTimestamps[chatId to messageId] = CloudSyncCodec.CloudLockRecord(
            chatId = chatId,
            messageId = messageId,
            isLocked = isLocked,
            updatedAtMs = System.currentTimeMillis(),
        )
        schedulePushMetadata()
    }

    fun noteUserNoteChanged(chatId: Long, messageId: Long, note: String?) {
        localNoteTimestamps[chatId to messageId] = CloudSyncCodec.CloudNoteRecord(
            chatId = chatId,
            messageId = messageId,
            note = note,
            updatedAtMs = System.currentTimeMillis(),
        )
        schedulePushMetadata()
    }

    fun noteAlbumUpserted(album: AlbumEntity) {
        localAlbumTimestamps[album.id] = CloudSyncCodec.CloudAlbumRecord(
            id = album.id,
            name = album.name,
            coverChatId = album.coverChatId,
            coverMessageId = album.coverMessageId,
            createdAt = album.createdAt,
            updatedAtMs = maxOf(album.updatedAt, System.currentTimeMillis()),
            deleted = false,
        )
        schedulePushMetadata()
    }

    fun noteAlbumDeleted(albumId: String) {
        localAlbumTimestamps[albumId] = CloudSyncCodec.CloudAlbumRecord(
            id = albumId,
            name = "",
            createdAt = 0L,
            updatedAtMs = System.currentTimeMillis(),
            deleted = true,
        )
        schedulePushMetadata()
    }

    fun noteAlbumItemChanged(albumId: String, chatId: Long, messageId: Long, added: Boolean) {
        localAlbumItemTimestamps[Triple(albumId, chatId, messageId)] = CloudSyncCodec.CloudAlbumItemRecord(
            albumId = albumId,
            chatId = chatId,
            messageId = messageId,
            addedAt = System.currentTimeMillis(),
            removed = !added,
        )
        schedulePushMetadata()
    }

    fun noteAiIndexChanged(
        chatId: Long,
        messageId: Long,
        ocrText: String?,
        aiTags: String?,
        exifLat: Double?,
        exifLng: Double?,
    ) {
        localAiTimestamps[chatId to messageId] = CloudSyncCodec.CloudAiIndexRecord(
            chatId = chatId,
            messageId = messageId,
            ocrText = ocrText,
            aiTags = aiTags,
            exifLat = exifLat,
            exifLng = exifLng,
            updatedAtMs = System.currentTimeMillis(),
        )
        schedulePushSearchIndex()
    }

    fun noteEncryptedThumbChanged(chatId: Long, messageId: Long, thumbRemoteId: String) {
        if (thumbRemoteId.isBlank()) return
        localEncryptedThumbTimestamps[chatId to messageId] = CloudSyncCodec.CloudEncryptedThumbRecord(
            chatId = chatId,
            messageId = messageId,
            thumbRemoteId = thumbRemoteId,
            updatedAtMs = System.currentTimeMillis(),
        )
        schedulePushMetadata()
    }

    fun getCloudEncryptedThumbRemoteId(chatId: Long, messageId: Long): String? =
        localEncryptedThumbTimestamps[chatId to messageId]?.thumbRemoteId

    fun getAllCloudEncryptedThumbs(): List<CloudSyncCodec.CloudEncryptedThumbRecord> =
        localEncryptedThumbTimestamps.values.toList()

    /**
     * Uploads an encrypted `.tgethumb` blob to `"TelegramDrive System"` and indexes its
     * `remoteId` in the LWW metadata snapshot so thumbnails follow across installs/devices.
     */
    suspend fun uploadEncryptedThumbBytes(
        chatId: Long,
        messageId: Long,
        bytes: ByteArray,
    ): String? = withContext(dispatchers.io) {
        if (bytes.isEmpty()) return@withContext null
        val systemChatId = runCatching { telegramClient.getOrCreateSystemChannel() }.getOrNull()
            ?: return@withContext null
        val fileName = "${chatId}_${messageId}.tgethumb"
        val tmpFile = File(syncDir, "up_$fileName")
        try {
            tmpFile.writeBytes(bytes)
            val res = telegramClient.uploadFile(
                chatId = systemChatId,
                localPath = tmpFile.absolutePath,
                fileName = fileName,
                mimeType = "application/octet-stream",
            ) { _, _ -> }
            if (res.remoteId.isNotBlank()) {
                noteEncryptedThumbChanged(chatId, messageId, res.remoteId)
                res.remoteId
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("CloudSyncManager: uploadEncryptedThumbBytes failed for ${chatId}_$messageId", t)
            null
        } finally {
            runCatching { tmpFile.delete() }
        }
    }

    /**
     * Downloads an encrypted `.tgethumb` blob from `"TelegramDrive System"` if registered in metadata.
     */
    suspend fun restoreEncryptedThumbBytes(chatId: Long, messageId: Long): ByteArray? = withContext(dispatchers.io) {
        val remoteId = getCloudEncryptedThumbRemoteId(chatId, messageId)?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        try {
            val terminal = telegramClient.downloadFile(remoteId, "")
                .filter { it is DownloadUpdate.Done || it is DownloadUpdate.Failed }
                .first()
            if (terminal !is DownloadUpdate.Done) return@withContext null
            val f = File(terminal.localPath)
            if (f.isFile && f.length() > 0L) f.readBytes() else null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("CloudSyncManager: restoreEncryptedThumbBytes failed for ${chatId}_$messageId", t)
            null
        }
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

    suspend fun purgeExpiredTrashNow() = withContext(dispatchers.io) {
        mutex.withLock {
            purgeExpiredTrashLocked()
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

        val virtualPaths = LinkedHashMap<Pair<Long, Long>, CloudSyncCodec.CloudVirtualPathRecord>()
        files.filter { it.virtualPath.isNotEmpty() }.forEach { f ->
            val key = f.chatId to f.messageId
            virtualPaths[key] = localVirtualPathTimestamps[key]
                ?: CloudSyncCodec.CloudVirtualPathRecord(f.chatId, f.messageId, f.virtualPath, f.date)
        }
        localVirtualPathTimestamps.forEach { (key, rec) -> virtualPaths[key] = rec }

        val lockedFlags = LinkedHashMap<Pair<Long, Long>, CloudSyncCodec.CloudLockRecord>()
        files.filter { it.isLocked }.forEach { f ->
            val key = f.chatId to f.messageId
            lockedFlags[key] = localLockTimestamps[key]
                ?: CloudSyncCodec.CloudLockRecord(f.chatId, f.messageId, true, f.date)
        }
        localLockTimestamps.forEach { (key, rec) -> lockedFlags[key] = rec }

        val notes = LinkedHashMap<Pair<Long, Long>, CloudSyncCodec.CloudNoteRecord>()
        files.filter { !it.userNote.isNullOrBlank() }.forEach { f ->
            val key = f.chatId to f.messageId
            notes[key] = localNoteTimestamps[key]
                ?: CloudSyncCodec.CloudNoteRecord(f.chatId, f.messageId, f.userNote, f.date)
        }
        localNoteTimestamps.forEach { (key, rec) -> notes[key] = rec }

        val albumsMap = LinkedHashMap<String, CloudSyncCodec.CloudAlbumRecord>()
        albumDao.getAll().forEach { a ->
            albumsMap[a.id] = localAlbumTimestamps[a.id]
                ?: CloudSyncCodec.CloudAlbumRecord(
                    id = a.id,
                    name = a.name,
                    coverChatId = a.coverChatId,
                    coverMessageId = a.coverMessageId,
                    createdAt = a.createdAt,
                    updatedAtMs = a.updatedAt,
                    deleted = false,
                )
        }
        localAlbumTimestamps.forEach { (id, rec) -> albumsMap[id] = rec }

        val albumItemsMap = LinkedHashMap<Triple<String, Long, Long>, CloudSyncCodec.CloudAlbumItemRecord>()
        albumDao.getAllItems().forEach { itx ->
            val key = Triple(itx.albumId, itx.chatId, itx.messageId)
            albumItemsMap[key] = localAlbumItemTimestamps[key]
                ?: CloudSyncCodec.CloudAlbumItemRecord(itx.albumId, itx.chatId, itx.messageId, itx.addedAt, false)
        }
        localAlbumItemTimestamps.forEach { (key, rec) -> albumItemsMap[key] = rec }

        return CloudSyncCodec.CloudMetaSnapshot(
            version = 1,
            updatedAtMs = now,
            stars = stars,
            renames = renames,
            folderColors = colors,
            trash = trash,
            syncedFiles = synced,
            playbackPositions = playbackPositions.values.toList(),
            virtualPaths = virtualPaths.values.toList(),
            lockedFlags = lockedFlags.values.toList(),
            notes = notes.values.toList(),
            albums = albumsMap.values.toList(),
            albumItems = albumItemsMap.values.toList(),
            encryptedThumbs = localEncryptedThumbTimestamps.values.toList(),
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
        val aiEntries = LinkedHashMap<Pair<Long, Long>, CloudSyncCodec.CloudAiIndexRecord>()
        fileCacheDao.getAll().forEach { f ->
            if (f.ocrText != null || f.aiTags != null || f.exifLat != null || f.exifLng != null) {
                val key = f.chatId to f.messageId
                aiEntries[key] = localAiTimestamps[key]
                    ?: CloudSyncCodec.CloudAiIndexRecord(
                        chatId = f.chatId,
                        messageId = f.messageId,
                        ocrText = f.ocrText,
                        aiTags = f.aiTags,
                        exifLat = f.exifLat,
                        exifLng = f.exifLng,
                        updatedAtMs = maxOf(f.date, f.lastAccessed),
                    )
            }
        }
        localAiTimestamps.forEach { (key, rec) -> aiEntries[key] = rec }
        return CloudSyncCodec.CloudSearchIndexSnapshot(
            version = 1,
            updatedAtMs = System.currentTimeMillis(),
            zipEntries = entries,
            aiEntries = aiEntries.values.toList(),
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

        merged.virtualPaths.forEach { vp ->
            localVirtualPathTimestamps[vp.chatId to vp.messageId] = vp
            val local = existingByKey[vp.chatId to vp.messageId]
            if (local != null && local.virtualPath != vp.virtualPath) {
                runCatching { fileCacheDao.updateVirtualPath(vp.chatId, vp.messageId, vp.virtualPath) }
            }
        }

        merged.lockedFlags.forEach { lk ->
            localLockTimestamps[lk.chatId to lk.messageId] = lk
            val local = existingByKey[lk.chatId to lk.messageId]
            if (local != null && local.isLocked != lk.isLocked) {
                runCatching { fileCacheDao.setLocked(lk.chatId, lk.messageId, lk.isLocked) }
            }
        }

        merged.notes.forEach { nt ->
            localNoteTimestamps[nt.chatId to nt.messageId] = nt
            val local = existingByKey[nt.chatId to nt.messageId]
            if (local != null && local.userNote != nt.note) {
                runCatching { fileCacheDao.updateUserNote(nt.chatId, nt.messageId, nt.note) }
            }
        }

        val activeAlbumIds = HashSet<String>()
        merged.albums.forEach { al ->
            localAlbumTimestamps[al.id] = al
            if (al.deleted) {
                runCatching { albumDao.deleteById(al.id) }
            } else {
                activeAlbumIds += al.id
                runCatching {
                    albumDao.upsert(
                        AlbumEntity(
                            id = al.id,
                            name = al.name,
                            coverChatId = al.coverChatId,
                            coverMessageId = al.coverMessageId,
                            createdAt = al.createdAt,
                            updatedAt = al.updatedAtMs,
                        ),
                    )
                }
            }
        }

        merged.albumItems.forEach { item ->
            localAlbumItemTimestamps[Triple(item.albumId, item.chatId, item.messageId)] = item
            if (item.removed) {
                runCatching { albumDao.removeItem(item.albumId, item.chatId, item.messageId) }
            } else if (item.albumId in activeAlbumIds || albumDao.getById(item.albumId) != null) {
                runCatching {
                    albumDao.addItem(
                        AlbumItemEntity(
                            albumId = item.albumId,
                            chatId = item.chatId,
                            messageId = item.messageId,
                            addedAt = item.addedAt,
                        ),
                    )
                }
            }
        }

        merged.encryptedThumbs.forEach { rec ->
            localEncryptedThumbTimestamps[rec.chatId to rec.messageId] = rec
        }
    }

    private suspend fun applyMergedSearchIndex(merged: CloudSyncCodec.CloudSearchIndexSnapshot) {
        if (merged.zipEntries.isNotEmpty()) {
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
        if (merged.aiEntries.isNotEmpty()) {
            val existingByKey = fileCacheDao.getAll().associateBy { it.chatId to it.messageId }
            merged.aiEntries.forEach { ai ->
                localAiTimestamps[ai.chatId to ai.messageId] = ai
                val local = existingByKey[ai.chatId to ai.messageId]
                if (local != null) {
                    if (local.ocrText != ai.ocrText || local.aiTags != ai.aiTags) {
                        runCatching { fileCacheDao.updateAiIndex(ai.chatId, ai.messageId, ai.ocrText, ai.aiTags) }
                    }
                    if ((ai.exifLat != null && ai.exifLng != null) &&
                        (local.exifLat != ai.exifLat || local.exifLng != ai.exifLng)
                    ) {
                        runCatching { fileCacheDao.updateExifLocation(ai.chatId, ai.messageId, ai.exifLat, ai.exifLng) }
                    }
                }
            }
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

    fun clearInMemoryState() {
        pushMetaJob?.cancel()
        pushMetaJob = null
        pushSearchJob?.cancel()
        pushSearchJob = null
        localStarTimestamps.clear()
        localRenameTimestamps.clear()
        localColorTimestamps.clear()
        localTrashTombstones.clear()
        localPlaybackTimestamps.clear()
        localVirtualPathTimestamps.clear()
        localLockTimestamps.clear()
        localNoteTimestamps.clear()
        localAlbumTimestamps.clear()
        localAlbumItemTimestamps.clear()
        localAiTimestamps.clear()
        localEncryptedThumbTimestamps.clear()
    }

    companion object {
        private const val PUSH_DEBOUNCE_MS = 8_000L
        const val TRASH_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
