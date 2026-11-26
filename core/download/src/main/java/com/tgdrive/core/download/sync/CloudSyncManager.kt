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
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.SyncedFileEntity
import com.tgdrive.core.db.entity.TrashEntryEntity
import com.tgdrive.core.db.entity.ZipEntryEntity
import com.tgdrive.core.security.BackupCrypto
import com.tgdrive.core.telegram.DownloadUpdate
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.telegram.TelegramMessage
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
    private val backupCrypto: BackupCrypto,
    private val dispatchers: DispatchersProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val mutex = Mutex()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    @Volatile private var pushMetaJob: Job? = null
    @Volatile private var pushSearchJob: Job? = null
    @Volatile private var pushVaultJob: Job? = null

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
    private val localVaultAiTimestamps =
        ConcurrentHashMap<Pair<Long, Long>, CloudSyncCodec.CloudAiIndexRecord>()
    private val localEncryptedThumbTimestamps =
        ConcurrentHashMap<Pair<Long, Long>, CloudSyncCodec.CloudEncryptedThumbRecord>()

    private val syncDir: File
        get() = File(ctx.cacheDir, "cloud_sync").apply { mkdirs() }

    private val virtualPathOverlayFile: File
        get() = File(ctx.filesDir, VIRTUAL_PATH_OVERLAY_FILE)

    init {
        loadVirtualPathOverlayFromDisk()
    }

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

    fun noteVirtualPathChanged(
        chatId: Long,
        messageId: Long,
        virtualPath: String,
        schedulePush: Boolean = true,
    ) {
        val clean = virtualPath.trim('/')
        localVirtualPathTimestamps[chatId to messageId] = CloudSyncCodec.CloudVirtualPathRecord(
            chatId = chatId,
            messageId = messageId,
            virtualPath = clean,
            updatedAtMs = System.currentTimeMillis(),
        )
        persistVirtualPathOverlayToDisk()
        if (schedulePush) {
            schedulePushMetadata()
        }
    }

    fun getCloudVirtualPath(chatId: Long, messageId: Long): String? =
        localVirtualPathTimestamps[chatId to messageId]?.virtualPath?.takeIf { it.isNotBlank() }

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

    /**
     * Records AI index updates for PassVault/locked items and schedules an AES-256-GCM
     * encrypted (`tgdrive_vault_index_v1.tgenc`) push when a PassVault passphrase is active.
     */
    fun noteVaultAiIndexChanged(
        chatId: Long,
        messageId: Long,
        ocrText: String?,
        aiTags: String?,
    ) {
        localVaultAiTimestamps[chatId to messageId] = CloudSyncCodec.CloudAiIndexRecord(
            chatId = chatId,
            messageId = messageId,
            ocrText = ocrText,
            aiTags = aiTags,
            updatedAtMs = System.currentTimeMillis(),
        )
        schedulePushVaultIndex()
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

    fun schedulePushVaultIndex() {
        if (!backupCrypto.hasVaultPassphrase()) return
        pushVaultJob?.cancel()
        pushVaultJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            runCatching { pushVaultIndexOnly() }
                .onFailure { t ->
                    if (t is CancellationException) throw t
                    Log.w("CloudSyncManager: debounced vault index push failed", t)
                }
        }
    }

    suspend fun purgeExpiredTrashNow() = withContext(dispatchers.io) {
        mutex.withLock {
            purgeExpiredTrashLocked()
        }
    }

    private sealed class SnapshotPull<out T> {
        data object NoRemote : SnapshotPull<Nothing>()
        data class Unchanged(val messageId: Long) : SnapshotPull<Nothing>()
        data class Loaded<T>(val messageId: Long, val value: T) : SnapshotPull<T>()
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
                val prefs = preferences.data.first()
                var newMetaId = prefs.lastSyncedMetaMessageId
                var newSearchId = prefs.lastSyncedSearchMessageId
                var newVaultId = prefs.lastSyncedVaultIndexMessageId

                when (
                    val metaPull = downloadSnapshotIfChanged(
                        systemChatId = systemChatId,
                        fileName = CloudSyncCodec.META_SNAPSHOT_FILE,
                        lastSeenMessageId = prefs.lastSyncedMetaMessageId,
                    ) { bytes -> CloudSyncCodec.decodeMetaGzip(bytes) }
                ) {
                    is SnapshotPull.Loaded -> {
                        newMetaId = metaPull.messageId
                        val localMeta = buildLocalMetaSnapshot()
                        val merged = CloudSyncCodec.mergeMeta(localMeta, metaPull.value)
                        applyMergedMeta(merged)
                    }
                    is SnapshotPull.Unchanged -> newMetaId = metaPull.messageId
                    SnapshotPull.NoRemote -> {}
                }

                when (
                    val searchPull = downloadSnapshotIfChanged(
                        systemChatId = systemChatId,
                        fileName = CloudSyncCodec.SEARCH_INDEX_FILE,
                        lastSeenMessageId = prefs.lastSyncedSearchMessageId,
                    ) { bytes -> CloudSyncCodec.decodeSearchIndexGzip(bytes) }
                ) {
                    is SnapshotPull.Loaded -> {
                        newSearchId = searchPull.messageId
                        val localSearch = buildLocalSearchSnapshot()
                        val mergedSearch = CloudSyncCodec.mergeSearchIndex(localSearch, searchPull.value)
                        applyMergedSearchIndex(mergedSearch)
                    }
                    is SnapshotPull.Unchanged -> newSearchId = searchPull.messageId
                    SnapshotPull.NoRemote -> {}
                }

                if (backupCrypto.hasVaultPassphrase()) {
                    when (
                        val vaultPull = downloadSnapshotIfChanged(
                            systemChatId = systemChatId,
                            fileName = CloudSyncCodec.VAULT_INDEX_FILE,
                            lastSeenMessageId = prefs.lastSyncedVaultIndexMessageId,
                        ) { encBytes ->
                            backupCrypto.decryptVaultBlob(encBytes)?.let { CloudSyncCodec.decodeVaultIndexGzip(it) }
                        }
                    ) {
                        is SnapshotPull.Loaded -> {
                            newVaultId = vaultPull.messageId
                            val localVault = buildLocalVaultIndexSnapshot()
                            val mergedVault = CloudSyncCodec.mergeVaultIndex(localVault, vaultPull.value)
                            applyMergedVaultIndex(mergedVault)
                        }
                        is SnapshotPull.Unchanged -> newVaultId = vaultPull.messageId
                        SnapshotPull.NoRemote -> {}
                    }
                }

                purgeExpiredTrashLocked()
                preferences.update {
                    it.copy(
                        lastCloudSyncMs = System.currentTimeMillis(),
                        lastSyncedMetaMessageId = newMetaId,
                        lastSyncedSearchMessageId = newSearchId,
                        lastSyncedVaultIndexMessageId = newVaultId,
                    )
                }
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
                val uploadedMetaId = uploadSnapshot(
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
                val uploadedSearchId = uploadSnapshot(
                    systemChatId = systemChatId,
                    fileName = CloudSyncCodec.SEARCH_INDEX_FILE,
                    bytes = CloudSyncCodec.encodeSearchIndexGzip(mergedSearch),
                )

                var uploadedVaultId: Long? = null
                if (backupCrypto.hasVaultPassphrase()) {
                    val remoteVault = downloadSnapshot(systemChatId, CloudSyncCodec.VAULT_INDEX_FILE) { enc ->
                        backupCrypto.decryptVaultBlob(enc)?.let { CloudSyncCodec.decodeVaultIndexGzip(it) }
                    }
                    val mergedVault = CloudSyncCodec.mergeVaultIndex(
                        buildLocalVaultIndexSnapshot(),
                        remoteVault ?: CloudSyncCodec.CloudVaultIndexSnapshot(),
                    )
                    applyMergedVaultIndex(mergedVault)
                    val encBytes = backupCrypto.encryptVaultBlob(CloudSyncCodec.encodeVaultIndexGzip(mergedVault))
                    if (encBytes != null && encBytes.isNotEmpty()) {
                        uploadedVaultId = uploadSnapshot(
                            systemChatId = systemChatId,
                            fileName = CloudSyncCodec.VAULT_INDEX_FILE,
                            bytes = encBytes,
                            mimeType = "application/octet-stream",
                        )
                    }
                }

                preferences.update { cur ->
                    cur.copy(
                        lastCloudSyncMs = System.currentTimeMillis(),
                        lastSyncedMetaMessageId = uploadedMetaId ?: cur.lastSyncedMetaMessageId,
                        lastSyncedSearchMessageId = uploadedSearchId ?: cur.lastSyncedSearchMessageId,
                        lastSyncedVaultIndexMessageId = uploadedVaultId ?: cur.lastSyncedVaultIndexMessageId,
                    )
                }
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
                val uploadedMetaId = uploadSnapshot(
                    systemChatId = systemChatId,
                    fileName = CloudSyncCodec.META_SNAPSHOT_FILE,
                    bytes = CloudSyncCodec.encodeMetaGzip(buildLocalMetaSnapshot()),
                )
                preferences.update { cur ->
                    cur.copy(
                        lastCloudSyncMs = System.currentTimeMillis(),
                        lastSyncedMetaMessageId = uploadedMetaId ?: cur.lastSyncedMetaMessageId,
                    )
                }
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
                val uploadedSearchId = uploadSnapshot(
                    systemChatId = systemChatId,
                    fileName = CloudSyncCodec.SEARCH_INDEX_FILE,
                    bytes = CloudSyncCodec.encodeSearchIndexGzip(merged),
                )
                preferences.update { cur ->
                    cur.copy(
                        lastCloudSyncMs = System.currentTimeMillis(),
                        lastSyncedSearchMessageId = uploadedSearchId ?: cur.lastSyncedSearchMessageId,
                    )
                }
            } finally {
                _syncing.value = false
            }
        }
    }

    private suspend fun pushVaultIndexOnly() {
        if (!backupCrypto.hasVaultPassphrase()) return
        mutex.withLock {
            _syncing.value = true
            try {
                val systemChatId = telegramClient.getOrCreateSystemChannel() ?: return
                val remote = downloadSnapshot(systemChatId, CloudSyncCodec.VAULT_INDEX_FILE) { enc ->
                    backupCrypto.decryptVaultBlob(enc)?.let { CloudSyncCodec.decodeVaultIndexGzip(it) }
                }
                val merged = CloudSyncCodec.mergeVaultIndex(
                    buildLocalVaultIndexSnapshot(),
                    remote ?: CloudSyncCodec.CloudVaultIndexSnapshot(),
                )
                applyMergedVaultIndex(merged)
                val encBytes = backupCrypto.encryptVaultBlob(CloudSyncCodec.encodeVaultIndexGzip(merged))
                    ?: return
                val uploadedVaultId = uploadSnapshot(
                    systemChatId = systemChatId,
                    fileName = CloudSyncCodec.VAULT_INDEX_FILE,
                    bytes = encBytes,
                    mimeType = "application/octet-stream",
                )
                preferences.update { cur ->
                    cur.copy(
                        lastCloudSyncMs = System.currentTimeMillis(),
                        lastSyncedVaultIndexMessageId = uploadedVaultId ?: cur.lastSyncedVaultIndexMessageId,
                    )
                }
            } finally {
                _syncing.value = false
            }
        }
    }

    private suspend fun buildLocalMetaSnapshot(): CloudSyncCodec.CloudMetaSnapshot {
        val files = fileCacheDao.getAll()
        val filesByKey = files.associateBy { it.chatId to it.messageId }
        val stars = files.filter { it.isStarred }.map { f ->
            localStarTimestamps[f.chatId to f.messageId]
                ?: CloudSyncCodec.CloudStarRecord(f.chatId, f.messageId, true, 0L)
        } + localStarTimestamps.values.filter { !it.isStarred || (it.chatId to it.messageId) !in filesByKey }

        // Only serialize user-renamed files (80-90% smaller metadata snapshots)
        val renamesMap = LinkedHashMap<Pair<Long, Long>, CloudSyncCodec.CloudRenameRecord>()
        filterRenamedFilesForSync(files).forEach { entity ->
            renamesMap[entity.chatId to entity.messageId] =
                CloudSyncCodec.CloudRenameRecord(entity.chatId, entity.messageId, entity.fileName, entity.date)
        }
        localRenameTimestamps.forEach { (key, rec) -> renamesMap[key] = rec }
        val renames = renamesMap.values.toList()

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
            updatedAtMs = System.currentTimeMillis(),
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
        val allFiles = fileCacheDao.getAll()
        val privateKeys = allFiles
            .filter { isPrivateOrEncryptedForSync(it) }
            .mapTo(HashSet()) { it.chatId to it.messageId }
        filterPublicAiFiles(allFiles).forEach { f ->
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
        localAiTimestamps.forEach { (key, rec) ->
            if (key !in privateKeys) aiEntries[key] = rec
        }
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
        persistVirtualPathOverlayToDisk()

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
            var deletedRemotely = false
            try {
                telegramClient.deleteMessage(item.chatId, item.messageId)
                deletedRemotely = true
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val msg = t.message.orEmpty()
                if (msg.contains("not found", ignoreCase = true) ||
                    msg.contains("MESSAGE_ID_INVALID", ignoreCase = true) ||
                    msg.contains("400")
                ) {
                    deletedRemotely = true
                } else {
                    Log.w("CloudSyncManager: failed deleting expired trash msg ${item.messageId}", t)
                }
            }
            if (deletedRemotely) {
                runCatching { trashEntryDao.deleteById(item.chatId, item.messageId) }
                localTrashTombstones[item.chatId to item.messageId] = System.currentTimeMillis()
            }
        }
    }

    private suspend fun findSystemMessages(
        systemChatId: Long,
        fileName: String,
        stopOnFirstPageMatch: Boolean,
        maxPages: Int = 10,
        pageSize: Int = 100,
    ): List<TelegramMessage> {
        val matches = mutableListOf<TelegramMessage>()
        var fromMessageId = 0L
        repeat(maxPages) {
            val page = runCatching {
                telegramClient.fetchMessages(systemChatId, fromMessageId, pageSize)
            }.getOrDefault(emptyList())
            if (page.isEmpty()) return matches
            matches += page.filter { it.fileName == fileName }
            if (stopOnFirstPageMatch && matches.isNotEmpty()) return matches
            val oldestId = page.minOfOrNull { it.id } ?: return matches
            if (page.size < pageSize || oldestId == fromMessageId) return matches
            fromMessageId = oldestId
        }
        return matches
    }

    private suspend fun buildLocalVaultIndexSnapshot(): CloudSyncCodec.CloudVaultIndexSnapshot {
        val entries = LinkedHashMap<Pair<Long, Long>, CloudSyncCodec.CloudAiIndexRecord>()
        val allFiles = fileCacheDao.getAll()
        val privateKeys = allFiles
            .filter { isPrivateOrEncryptedForSync(it) }
            .mapTo(HashSet()) { it.chatId to it.messageId }
        allFiles.filter { isPrivateOrEncryptedForSync(it) }.forEach { f ->
            if (f.ocrText != null || f.aiTags != null) {
                val key = f.chatId to f.messageId
                entries[key] = localVaultAiTimestamps[key]
                    ?: CloudSyncCodec.CloudAiIndexRecord(
                        chatId = f.chatId,
                        messageId = f.messageId,
                        ocrText = f.ocrText,
                        aiTags = f.aiTags,
                        updatedAtMs = maxOf(f.date, f.lastAccessed),
                    )
            }
        }
        localVaultAiTimestamps.forEach { (key, rec) ->
            if (key in privateKeys || !localAiTimestamps.containsKey(key)) {
                entries[key] = rec
            }
        }
        return CloudSyncCodec.CloudVaultIndexSnapshot(
            version = 1,
            updatedAtMs = System.currentTimeMillis(),
            entries = entries.values.toList(),
        )
    }

    private suspend fun applyMergedVaultIndex(merged: CloudSyncCodec.CloudVaultIndexSnapshot) {
        if (merged.entries.isEmpty()) return
        val existingByKey = fileCacheDao.getAll().associateBy { it.chatId to it.messageId }
        merged.entries.forEach { ai ->
            localVaultAiTimestamps[ai.chatId to ai.messageId] = ai
            val local = existingByKey[ai.chatId to ai.messageId]
            if (local != null && (local.ocrText != ai.ocrText || local.aiTags != ai.aiTags)) {
                runCatching { fileCacheDao.updateAiIndex(ai.chatId, ai.messageId, ai.ocrText, ai.aiTags) }
            }
        }
    }

    private suspend fun <T> downloadSnapshotIfChanged(
        systemChatId: Long,
        fileName: String,
        lastSeenMessageId: Long,
        decode: (ByteArray) -> T?,
    ): SnapshotPull<T> {
        val match = findSystemMessages(systemChatId, fileName, stopOnFirstPageMatch = true)
            .filter { it.fileRemoteId.isNotEmpty() }
            .maxByOrNull { it.date } ?: return SnapshotPull.NoRemote
        if (lastSeenMessageId != 0L && match.id == lastSeenMessageId) {
            return SnapshotPull.Unchanged(match.id)
        }
        val terminal = telegramClient.downloadFile(match.fileRemoteId, "")
            .filter { it is DownloadUpdate.Done || it is DownloadUpdate.Failed }
            .first()
        if (terminal !is DownloadUpdate.Done) return SnapshotPull.NoRemote
        val file = File(terminal.localPath)
        if (!file.isFile) return SnapshotPull.NoRemote
        val decoded = decode(file.readBytes()) ?: return SnapshotPull.NoRemote
        return SnapshotPull.Loaded(match.id, decoded)
    }

    private suspend fun <T> downloadSnapshot(
        systemChatId: Long,
        fileName: String,
        decode: (ByteArray) -> T?,
    ): T? {
        return when (val res = downloadSnapshotIfChanged(systemChatId, fileName, 0L, decode)) {
            is SnapshotPull.Loaded -> res.value
            else -> null
        }
    }

    private suspend fun uploadSnapshot(
        systemChatId: Long,
        fileName: String,
        bytes: ByteArray,
        mimeType: String = "application/gzip",
    ): Long? {
        val oldMsgs = findSystemMessages(systemChatId, fileName, stopOnFirstPageMatch = false)

        val outFile = File(syncDir, fileName)
        return try {
            outFile.writeBytes(bytes)
            val res = telegramClient.uploadFile(
                chatId = systemChatId,
                localPath = outFile.absolutePath,
                fileName = fileName,
                mimeType = mimeType,
            ) { _, _ -> }

            oldMsgs.forEach { old ->
                runCatching { telegramClient.deleteMessage(systemChatId, old.id) }
            }
            res.messageId.takeIf { it != 0L }
        } finally {
            runCatching { outFile.delete() }
        }
    }

    fun clearInMemoryState() {
        pushMetaJob?.cancel()
        pushMetaJob = null
        pushSearchJob?.cancel()
        pushSearchJob = null
        pushVaultJob?.cancel()
        pushVaultJob = null
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
        localVaultAiTimestamps.clear()
        localEncryptedThumbTimestamps.clear()
        runCatching { virtualPathOverlayFile.delete() }
    }

    private fun loadVirtualPathOverlayFromDisk() {
        runCatching {
            val file = virtualPathOverlayFile
            if (!file.isFile || file.length() <= 0L) return
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val parts = line.split('\t', limit = 4)
                if (parts.size < 4) return@forEachLine
                val chatId = parts[0].toLongOrNull() ?: return@forEachLine
                val msgId = parts[1].toLongOrNull() ?: return@forEachLine
                val updatedAt = parts[2].toLongOrNull() ?: 0L
                val vpath = unescapeOverlayValue(parts[3])
                localVirtualPathTimestamps[chatId to msgId] = CloudSyncCodec.CloudVirtualPathRecord(
                    chatId = chatId,
                    messageId = msgId,
                    virtualPath = vpath,
                    updatedAtMs = updatedAt,
                )
            }
        }
    }

    private fun persistVirtualPathOverlayToDisk() {
        runCatching {
            val target = virtualPathOverlayFile
            target.parentFile?.mkdirs()
            val snapshot = localVirtualPathTimestamps.values.toList()
            val tmp = File(target.parentFile, "$VIRTUAL_PATH_OVERLAY_FILE.tmp")
            tmp.bufferedWriter().use { writer ->
                for (rec in snapshot) {
                    writer.write(rec.chatId.toString())
                    writer.write('\t'.code)
                    writer.write(rec.messageId.toString())
                    writer.write('\t'.code)
                    writer.write(rec.updatedAtMs.toString())
                    writer.write('\t'.code)
                    writer.write(escapeOverlayValue(rec.virtualPath))
                    writer.newLine()
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun escapeOverlayValue(raw: String): String =
        raw.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun unescapeOverlayValue(raw: String): String = buildString(raw.length) {
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    '\\' -> append('\\')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    else -> append(raw[i + 1])
                }
                i += 2
            } else {
                append(ch)
                i += 1
            }
        }
    }

    companion object {
        private const val VIRTUAL_PATH_OVERLAY_FILE = "cloud_virtual_paths_overlay.tsv"
        private const val PUSH_DEBOUNCE_MS = 8_000L
        const val TRASH_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L

        internal fun isPrivateOrEncryptedForSync(file: FileCacheEntity): Boolean =
            file.isLocked || file.isEncryptedVault || BackupCrypto.isEncryptedFileName(file.fileName)

        internal fun filterPublicAiFiles(files: List<FileCacheEntity>): List<FileCacheEntity> =
            files.filterNot(::isPrivateOrEncryptedForSync)

        internal fun filterRenamedFilesForSync(files: List<FileCacheEntity>): List<FileCacheEntity> =
            files.filter { it.isRenamed }
    }
}
