package com.tgdrive.core.download.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Pure-Kotlin gzipped JSON codec + Last-Write-Wins merger for cloud-synced
 * metadata (`meta_v1.json.gz`) and search ZIP catalogs (`search_index_v1.json.gz`).
 */
object CloudSyncCodec {

    const val META_SNAPSHOT_FILE = "tgdrive_meta_v1.json.gz"
    const val SEARCH_INDEX_FILE = "tgdrive_search_index_v1.json.gz"
    const val VAULT_INDEX_FILE = "tgdrive_vault_index_v1.tgenc"

    data class CloudStarRecord(
        val chatId: Long,
        val messageId: Long,
        val isStarred: Boolean,
        val updatedAtMs: Long,
    )

    data class CloudRenameRecord(
        val chatId: Long,
        val messageId: Long,
        val fileName: String,
        val updatedAtMs: Long,
    )

    data class CloudFolderColorRecord(
        val channelId: Long,
        val color: Int,
        val updatedAtMs: Long,
    )

    data class CloudTrashRecord(
        val messageId: Long,
        val chatId: Long,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String?,
        val thumbnailPath: String?,
        val remoteId: String,
        val originalDate: Long,
        val isStarred: Boolean,
        val deletedAt: Long,
        val purged: Boolean = false,
    )

    data class CloudSyncedFileRecord(
        val localPath: String,
        val telegramMessageId: Long,
        val sha256: String?,
        val size: Long,
        val syncedAt: Long,
        val channelId: Long?,
    )

    data class CloudPlaybackPositionRecord(
        val chatId: Long,
        val messageId: Long,
        val positionMs: Long,
        val updatedAtMs: Long,
    )

    data class CloudVirtualPathRecord(
        val chatId: Long,
        val messageId: Long,
        val virtualPath: String,
        val updatedAtMs: Long,
    )

    data class CloudLockRecord(
        val chatId: Long,
        val messageId: Long,
        val isLocked: Boolean,
        val updatedAtMs: Long,
    )

    data class CloudNoteRecord(
        val chatId: Long,
        val messageId: Long,
        val note: String?,
        val updatedAtMs: Long,
    )

    data class CloudAlbumRecord(
        val id: String,
        val name: String,
        val coverChatId: Long? = null,
        val coverMessageId: Long? = null,
        val createdAt: Long,
        val updatedAtMs: Long,
        val deleted: Boolean = false,
    )

    data class CloudAlbumItemRecord(
        val albumId: String,
        val chatId: Long,
        val messageId: Long,
        val addedAt: Long,
        val removed: Boolean = false,
    )

    data class CloudAiIndexRecord(
        val chatId: Long,
        val messageId: Long,
        val ocrText: String?,
        val aiTags: String?,
        val exifLat: Double? = null,
        val exifLng: Double? = null,
        val updatedAtMs: Long,
    )

    data class CloudEncryptedThumbRecord(
        val chatId: Long,
        val messageId: Long,
        val thumbRemoteId: String,
        val updatedAtMs: Long,
    )

    data class CloudMetaSnapshot(
        val version: Int = 1,
        val updatedAtMs: Long = 0L,
        val stars: List<CloudStarRecord> = emptyList(),
        val renames: List<CloudRenameRecord> = emptyList(),
        val folderColors: List<CloudFolderColorRecord> = emptyList(),
        val trash: List<CloudTrashRecord> = emptyList(),
        val syncedFiles: List<CloudSyncedFileRecord> = emptyList(),
        val playbackPositions: List<CloudPlaybackPositionRecord> = emptyList(),
        val virtualPaths: List<CloudVirtualPathRecord> = emptyList(),
        val lockedFlags: List<CloudLockRecord> = emptyList(),
        val notes: List<CloudNoteRecord> = emptyList(),
        val albums: List<CloudAlbumRecord> = emptyList(),
        val albumItems: List<CloudAlbumItemRecord> = emptyList(),
        val encryptedThumbs: List<CloudEncryptedThumbRecord> = emptyList(),
    )

    data class CloudZipEntryRecord(
        val chatId: Long,
        val zipMessageId: Long,
        val zipFileName: String,
        val entryPath: String,
        val entryName: String,
        val entrySize: Long,
        val isEncrypted: Boolean,
        val indexedAt: Long,
    )

    data class CloudSearchIndexSnapshot(
        val version: Int = 1,
        val updatedAtMs: Long = 0L,
        val zipEntries: List<CloudZipEntryRecord> = emptyList(),
        val aiEntries: List<CloudAiIndexRecord> = emptyList(),
    )

    data class CloudVaultIndexSnapshot(
        val version: Int = 1,
        val updatedAtMs: Long = 0L,
        val entries: List<CloudAiIndexRecord> = emptyList(),
    )

    fun encodeMetaGzip(snapshot: CloudMetaSnapshot): ByteArray =
        gzip(buildMetaJson(snapshot).toByteArray(Charsets.UTF_8))

    fun decodeMetaGzip(bytes: ByteArray): CloudMetaSnapshot? = runCatching {
        val text = gunzip(bytes).toString(Charsets.UTF_8)
        val root = MiniJson.parseObject(text) ?: return null
        val stars = root.optArray("stars").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudStarRecord(
                chatId = o.longVal("chatId") ?: return@mapNotNull null,
                messageId = o.longVal("messageId") ?: return@mapNotNull null,
                isStarred = o.boolVal("isStarred") ?: false,
                updatedAtMs = o.longVal("updatedAtMs") ?: 0L,
            )
        }
        val renames = root.optArray("renames").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudRenameRecord(
                chatId = o.longVal("chatId") ?: return@mapNotNull null,
                messageId = o.longVal("messageId") ?: return@mapNotNull null,
                fileName = o.strVal("fileName") ?: return@mapNotNull null,
                updatedAtMs = o.longVal("updatedAtMs") ?: 0L,
            )
        }
        val colors = root.optArray("folderColors").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudFolderColorRecord(
                channelId = o.longVal("channelId") ?: return@mapNotNull null,
                color = o.longVal("color")?.toInt() ?: return@mapNotNull null,
                updatedAtMs = o.longVal("updatedAtMs") ?: 0L,
            )
        }
        val trash = root.optArray("trash").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudTrashRecord(
                messageId = o.longVal("messageId") ?: return@mapNotNull null,
                chatId = o.longVal("chatId") ?: return@mapNotNull null,
                fileName = o.strVal("fileName") ?: return@mapNotNull null,
                fileSize = o.longVal("fileSize") ?: 0L,
                mimeType = o.strVal("mimeType"),
                thumbnailPath = o.strVal("thumbnailPath"),
                remoteId = o.strVal("remoteId") ?: "",
                originalDate = o.longVal("originalDate") ?: 0L,
                isStarred = o.boolVal("isStarred") ?: false,
                deletedAt = o.longVal("deletedAt") ?: 0L,
                purged = o.boolVal("purged") ?: false,
            )
        }
        val synced = root.optArray("syncedFiles").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudSyncedFileRecord(
                localPath = o.strVal("localPath") ?: return@mapNotNull null,
                telegramMessageId = o.longVal("telegramMessageId") ?: 0L,
                sha256 = o.strVal("sha256"),
                size = o.longVal("size") ?: 0L,
                syncedAt = o.longVal("syncedAt") ?: 0L,
                channelId = o.longVal("channelId"),
            )
        }
        val playbackPositions = root.optArray("playbackPositions").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudPlaybackPositionRecord(
                chatId = o.longVal("chatId") ?: return@mapNotNull null,
                messageId = o.longVal("messageId") ?: return@mapNotNull null,
                positionMs = o.longVal("positionMs") ?: 0L,
                updatedAtMs = o.longVal("updatedAtMs") ?: 0L,
            )
        }
        val virtualPaths = root.optArray("virtualPaths").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudVirtualPathRecord(
                chatId = o.longVal("chatId") ?: return@mapNotNull null,
                messageId = o.longVal("messageId") ?: return@mapNotNull null,
                virtualPath = o.strVal("virtualPath") ?: "",
                updatedAtMs = o.longVal("updatedAtMs") ?: 0L,
            )
        }
        val lockedFlags = root.optArray("lockedFlags").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudLockRecord(
                chatId = o.longVal("chatId") ?: return@mapNotNull null,
                messageId = o.longVal("messageId") ?: return@mapNotNull null,
                isLocked = o.boolVal("isLocked") ?: false,
                updatedAtMs = o.longVal("updatedAtMs") ?: 0L,
            )
        }
        val notes = root.optArray("notes").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudNoteRecord(
                chatId = o.longVal("chatId") ?: return@mapNotNull null,
                messageId = o.longVal("messageId") ?: return@mapNotNull null,
                note = o.strVal("note"),
                updatedAtMs = o.longVal("updatedAtMs") ?: 0L,
            )
        }
        val albums = root.optArray("albums").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudAlbumRecord(
                id = o.strVal("id") ?: return@mapNotNull null,
                name = o.strVal("name") ?: "",
                coverChatId = o.longVal("coverChatId"),
                coverMessageId = o.longVal("coverMessageId"),
                createdAt = o.longVal("createdAt") ?: 0L,
                updatedAtMs = o.longVal("updatedAtMs") ?: 0L,
                deleted = o.boolVal("deleted") ?: false,
            )
        }
        val albumItems = root.optArray("albumItems").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudAlbumItemRecord(
                albumId = o.strVal("albumId") ?: return@mapNotNull null,
                chatId = o.longVal("chatId") ?: return@mapNotNull null,
                messageId = o.longVal("messageId") ?: return@mapNotNull null,
                addedAt = o.longVal("addedAt") ?: 0L,
                removed = o.boolVal("removed") ?: false,
            )
        }
        val encryptedThumbs = root.optArray("encryptedThumbs").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudEncryptedThumbRecord(
                chatId = o.longVal("chatId") ?: return@mapNotNull null,
                messageId = o.longVal("messageId") ?: return@mapNotNull null,
                thumbRemoteId = o.strVal("thumbRemoteId") ?: return@mapNotNull null,
                updatedAtMs = o.longVal("updatedAtMs") ?: 0L,
            )
        }
        CloudMetaSnapshot(
            version = root.longVal("version")?.toInt() ?: 1,
            updatedAtMs = root.longVal("updatedAtMs") ?: 0L,
            stars = stars,
            renames = renames,
            folderColors = colors,
            trash = trash,
            syncedFiles = synced,
            playbackPositions = playbackPositions,
            virtualPaths = virtualPaths,
            lockedFlags = lockedFlags,
            notes = notes,
            albums = albums,
            albumItems = albumItems,
            encryptedThumbs = encryptedThumbs,
        )
    }.getOrNull()

    fun encodeSearchIndexGzip(snapshot: CloudSearchIndexSnapshot): ByteArray =
        gzip(buildSearchJson(snapshot).toByteArray(Charsets.UTF_8))

    fun decodeSearchIndexGzip(bytes: ByteArray): CloudSearchIndexSnapshot? = runCatching {
        val text = gunzip(bytes).toString(Charsets.UTF_8)
        val root = MiniJson.parseObject(text) ?: return null
        val entries = root.optArray("zipEntries").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudZipEntryRecord(
                chatId = o.longVal("chatId") ?: return@mapNotNull null,
                zipMessageId = o.longVal("zipMessageId") ?: return@mapNotNull null,
                zipFileName = o.strVal("zipFileName") ?: return@mapNotNull null,
                entryPath = o.strVal("entryPath") ?: return@mapNotNull null,
                entryName = o.strVal("entryName") ?: return@mapNotNull null,
                entrySize = o.longVal("entrySize") ?: 0L,
                isEncrypted = o.boolVal("isEncrypted") ?: false,
                indexedAt = o.longVal("indexedAt") ?: 0L,
            )
        }
        val aiEntries = root.optArray("aiEntries").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudAiIndexRecord(
                chatId = o.longVal("chatId") ?: return@mapNotNull null,
                messageId = o.longVal("messageId") ?: return@mapNotNull null,
                ocrText = o.strVal("ocrText"),
                aiTags = o.strVal("aiTags"),
                exifLat = o.doubleVal("exifLat"),
                exifLng = o.doubleVal("exifLng"),
                updatedAtMs = o.longVal("updatedAtMs") ?: 0L,
            )
        }
        CloudSearchIndexSnapshot(
            version = root.longVal("version")?.toInt() ?: 1,
            updatedAtMs = root.longVal("updatedAtMs") ?: 0L,
            zipEntries = entries,
            aiEntries = aiEntries,
        )
    }.getOrNull()

    fun encodeVaultIndexGzip(snapshot: CloudVaultIndexSnapshot): ByteArray =
        gzip(buildVaultIndexJson(snapshot).toByteArray(Charsets.UTF_8))

    fun decodeVaultIndexGzip(bytes: ByteArray): CloudVaultIndexSnapshot? = runCatching {
        val text = gunzip(bytes).toString(Charsets.UTF_8)
        val root = MiniJson.parseObject(text) ?: return null
        val entries = root.optArray("entries").mapNotNull { el ->
            val o = el as? Map<*, *> ?: return@mapNotNull null
            CloudAiIndexRecord(
                chatId = o.longVal("chatId") ?: return@mapNotNull null,
                messageId = o.longVal("messageId") ?: return@mapNotNull null,
                ocrText = o.strVal("ocrText"),
                aiTags = o.strVal("aiTags"),
                exifLat = o.doubleVal("exifLat"),
                exifLng = o.doubleVal("exifLng"),
                updatedAtMs = o.longVal("updatedAtMs") ?: 0L,
            )
        }
        CloudVaultIndexSnapshot(
            version = root.longVal("version")?.toInt() ?: 1,
            updatedAtMs = root.longVal("updatedAtMs") ?: 0L,
            entries = entries,
        )
    }.getOrNull()

    fun mergeVaultIndex(
        local: CloudVaultIndexSnapshot,
        remote: CloudVaultIndexSnapshot,
    ): CloudVaultIndexSnapshot {
        val merged = LinkedHashMap<Pair<Long, Long>, CloudAiIndexRecord>()
        (local.entries + remote.entries).forEach { e ->
            val key = e.chatId to e.messageId
            val cur = merged[key]
            if (cur == null || e.updatedAtMs >= cur.updatedAtMs) merged[key] = e
        }
        return CloudVaultIndexSnapshot(
            version = 1,
            updatedAtMs = maxOf(local.updatedAtMs, remote.updatedAtMs, System.currentTimeMillis()),
            entries = merged.values.toList(),
        )
    }

    fun mergeMeta(local: CloudMetaSnapshot, remote: CloudMetaSnapshot): CloudMetaSnapshot {
        val starsMap = LinkedHashMap<Pair<Long, Long>, CloudStarRecord>()
        (local.stars + remote.stars).forEach { r ->
            val key = r.chatId to r.messageId
            val cur = starsMap[key]
            if (cur == null || r.updatedAtMs >= cur.updatedAtMs) starsMap[key] = r
        }

        val renamesMap = LinkedHashMap<Pair<Long, Long>, CloudRenameRecord>()
        (local.renames + remote.renames).forEach { r ->
            val key = r.chatId to r.messageId
            val cur = renamesMap[key]
            if (cur == null || r.updatedAtMs >= cur.updatedAtMs) renamesMap[key] = r
        }

        val colorsMap = LinkedHashMap<Long, CloudFolderColorRecord>()
        (local.folderColors + remote.folderColors).forEach { r ->
            val cur = colorsMap[r.channelId]
            if (cur == null || r.updatedAtMs >= cur.updatedAtMs) colorsMap[r.channelId] = r
        }

        val trashMap = LinkedHashMap<Pair<Long, Long>, CloudTrashRecord>()
        (local.trash + remote.trash).forEach { r ->
            val key = r.chatId to r.messageId
            val cur = trashMap[key]
            if (cur == null || r.deletedAt > cur.deletedAt || (r.deletedAt == cur.deletedAt && r.purged)) {
                trashMap[key] = r
            }
        }

        val syncedMap = LinkedHashMap<Pair<String, Long?>, CloudSyncedFileRecord>()
        (local.syncedFiles + remote.syncedFiles).forEach { r ->
            val key = r.localPath to r.channelId
            val cur = syncedMap[key]
            if (cur == null || r.syncedAt >= cur.syncedAt) syncedMap[key] = r
        }

        val playbackMap = LinkedHashMap<Pair<Long, Long>, CloudPlaybackPositionRecord>()
        (local.playbackPositions + remote.playbackPositions).forEach { r ->
            val key = r.chatId to r.messageId
            val cur = playbackMap[key]
            if (cur == null || r.updatedAtMs >= cur.updatedAtMs) playbackMap[key] = r
        }

        val vpathMap = LinkedHashMap<Pair<Long, Long>, CloudVirtualPathRecord>()
        (local.virtualPaths + remote.virtualPaths).forEach { r ->
            val key = r.chatId to r.messageId
            val cur = vpathMap[key]
            if (cur == null || r.updatedAtMs >= cur.updatedAtMs) vpathMap[key] = r
        }

        val lockMap = LinkedHashMap<Pair<Long, Long>, CloudLockRecord>()
        (local.lockedFlags + remote.lockedFlags).forEach { r ->
            val key = r.chatId to r.messageId
            val cur = lockMap[key]
            if (cur == null || r.updatedAtMs >= cur.updatedAtMs) lockMap[key] = r
        }

        val noteMap = LinkedHashMap<Pair<Long, Long>, CloudNoteRecord>()
        (local.notes + remote.notes).forEach { r ->
            val key = r.chatId to r.messageId
            val cur = noteMap[key]
            if (cur == null || r.updatedAtMs >= cur.updatedAtMs) noteMap[key] = r
        }

        val albumMap = LinkedHashMap<String, CloudAlbumRecord>()
        (local.albums + remote.albums).forEach { r ->
            val cur = albumMap[r.id]
            if (cur == null || r.updatedAtMs > cur.updatedAtMs || (r.updatedAtMs == cur.updatedAtMs && r.deleted)) {
                albumMap[r.id] = r
            }
        }

        val albumItemMap = LinkedHashMap<Triple<String, Long, Long>, CloudAlbumItemRecord>()
        (local.albumItems + remote.albumItems).forEach { r ->
            val key = Triple(r.albumId, r.chatId, r.messageId)
            val cur = albumItemMap[key]
            if (cur == null || r.addedAt > cur.addedAt || (r.addedAt == cur.addedAt && r.removed)) {
                albumItemMap[key] = r
            }
        }

        val encThumbMap = LinkedHashMap<Pair<Long, Long>, CloudEncryptedThumbRecord>()
        (local.encryptedThumbs + remote.encryptedThumbs).forEach { r ->
            val key = r.chatId to r.messageId
            val cur = encThumbMap[key]
            if (cur == null || r.updatedAtMs >= cur.updatedAtMs) {
                encThumbMap[key] = r
            }
        }

        return CloudMetaSnapshot(
            version = 1,
            updatedAtMs = maxOf(local.updatedAtMs, remote.updatedAtMs, System.currentTimeMillis()),
            stars = starsMap.values.toList(),
            renames = renamesMap.values.toList(),
            folderColors = colorsMap.values.toList(),
            trash = trashMap.values.toList(),
            syncedFiles = syncedMap.values.toList(),
            playbackPositions = playbackMap.values.toList(),
            virtualPaths = vpathMap.values.toList(),
            lockedFlags = lockMap.values.toList(),
            notes = noteMap.values.toList(),
            albums = albumMap.values.toList(),
            albumItems = albumItemMap.values.toList(),
            encryptedThumbs = encThumbMap.values.toList(),
        )
    }

    fun mergeSearchIndex(
        local: CloudSearchIndexSnapshot,
        remote: CloudSearchIndexSnapshot,
    ): CloudSearchIndexSnapshot {
        val merged = LinkedHashMap<Triple<Long, Long, String>, CloudZipEntryRecord>()
        (local.zipEntries + remote.zipEntries).forEach { e ->
            val key = Triple(e.chatId, e.zipMessageId, e.entryPath)
            val cur = merged[key]
            if (cur == null || e.indexedAt >= cur.indexedAt) merged[key] = e
        }
        val mergedAi = LinkedHashMap<Pair<Long, Long>, CloudAiIndexRecord>()
        (local.aiEntries + remote.aiEntries).forEach { a ->
            val key = a.chatId to a.messageId
            val cur = mergedAi[key]
            if (cur == null || a.updatedAtMs >= cur.updatedAtMs) mergedAi[key] = a
        }
        return CloudSearchIndexSnapshot(
            version = 1,
            updatedAtMs = maxOf(local.updatedAtMs, remote.updatedAtMs, System.currentTimeMillis()),
            zipEntries = merged.values.toList(),
            aiEntries = mergedAi.values.toList(),
        )
    }

    private fun buildMetaJson(s: CloudMetaSnapshot): String = buildString {
        append("{\n")
        append("  \"version\": ").append(s.version).append(",\n")
        append("  \"updatedAtMs\": ").append(s.updatedAtMs).append(",\n")
        append("  \"stars\": [")
        s.stars.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{\"chatId\":").append(r.chatId)
                .append(",\"messageId\":").append(r.messageId)
                .append(",\"isStarred\":").append(r.isStarred)
                .append(",\"updatedAtMs\":").append(r.updatedAtMs).append("}")
        }
        append("],\n")
        append("  \"renames\": [")
        s.renames.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{\"chatId\":").append(r.chatId)
                .append(",\"messageId\":").append(r.messageId)
                .append(",\"fileName\":").append(jsonStr(r.fileName))
                .append(",\"updatedAtMs\":").append(r.updatedAtMs).append("}")
        }
        append("],\n")
        append("  \"folderColors\": [")
        s.folderColors.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{\"channelId\":").append(r.channelId)
                .append(",\"color\":").append(r.color)
                .append(",\"updatedAtMs\":").append(r.updatedAtMs).append("}")
        }
        append("],\n")
        append("  \"trash\": [")
        s.trash.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{\"messageId\":").append(r.messageId)
                .append(",\"chatId\":").append(r.chatId)
                .append(",\"fileName\":").append(jsonStr(r.fileName))
                .append(",\"fileSize\":").append(r.fileSize)
                .append(",\"mimeType\":").append(r.mimeType?.let(::jsonStr) ?: "null")
                .append(",\"thumbnailPath\":").append(r.thumbnailPath?.let(::jsonStr) ?: "null")
                .append(",\"remoteId\":").append(jsonStr(r.remoteId))
                .append(",\"originalDate\":").append(r.originalDate)
                .append(",\"isStarred\":").append(r.isStarred)
                .append(",\"deletedAt\":").append(r.deletedAt)
                .append(",\"purged\":").append(r.purged).append("}")
        }
        append("],\n")
        append("  \"syncedFiles\": [")
        s.syncedFiles.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{\"localPath\":").append(jsonStr(r.localPath))
                .append(",\"telegramMessageId\":").append(r.telegramMessageId)
                .append(",\"sha256\":").append(r.sha256?.let(::jsonStr) ?: "null")
                .append(",\"size\":").append(r.size)
                .append(",\"syncedAt\":").append(r.syncedAt)
                .append(",\"channelId\":").append(r.channelId?.toString() ?: "null").append("}")
        }
        append("],\n")
        append("  \"playbackPositions\": [")
        s.playbackPositions.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{\"chatId\":").append(r.chatId)
                .append(",\"messageId\":").append(r.messageId)
                .append(",\"positionMs\":").append(r.positionMs)
                .append(",\"updatedAtMs\":").append(r.updatedAtMs).append("}")
        }
        append("],\n")
        append("  \"virtualPaths\": [")
        s.virtualPaths.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{\"chatId\":").append(r.chatId)
                .append(",\"messageId\":").append(r.messageId)
                .append(",\"virtualPath\":").append(jsonStr(r.virtualPath))
                .append(",\"updatedAtMs\":").append(r.updatedAtMs).append("}")
        }
        append("],\n")
        append("  \"lockedFlags\": [")
        s.lockedFlags.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{\"chatId\":").append(r.chatId)
                .append(",\"messageId\":").append(r.messageId)
                .append(",\"isLocked\":").append(r.isLocked)
                .append(",\"updatedAtMs\":").append(r.updatedAtMs).append("}")
        }
        append("],\n")
        append("  \"notes\": [")
        s.notes.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{\"chatId\":").append(r.chatId)
                .append(",\"messageId\":").append(r.messageId)
                .append(",\"note\":").append(r.note?.let(::jsonStr) ?: "null")
                .append(",\"updatedAtMs\":").append(r.updatedAtMs).append("}")
        }
        append("],\n")
        append("  \"albums\": [")
        s.albums.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{\"id\":").append(jsonStr(r.id))
                .append(",\"name\":").append(jsonStr(r.name))
                .append(",\"coverChatId\":").append(r.coverChatId?.toString() ?: "null")
                .append(",\"coverMessageId\":").append(r.coverMessageId?.toString() ?: "null")
                .append(",\"createdAt\":").append(r.createdAt)
                .append(",\"updatedAtMs\":").append(r.updatedAtMs)
                .append(",\"deleted\":").append(r.deleted).append("}")
        }
        append("],\n")
        append("  \"albumItems\": [")
        s.albumItems.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{\"albumId\":").append(jsonStr(r.albumId))
                .append(",\"chatId\":").append(r.chatId)
                .append(",\"messageId\":").append(r.messageId)
                .append(",\"addedAt\":").append(r.addedAt)
                .append(",\"removed\":").append(r.removed).append("}")
        }
        append("],\n")
        append("  \"encryptedThumbs\": [")
        s.encryptedThumbs.forEachIndexed { i, r ->
            if (i > 0) append(",")
            append("{\"chatId\":").append(r.chatId)
                .append(",\"messageId\":").append(r.messageId)
                .append(",\"thumbRemoteId\":").append(jsonStr(r.thumbRemoteId))
                .append(",\"updatedAtMs\":").append(r.updatedAtMs).append("}")
        }
        append("]\n}\n")
    }

    private fun buildSearchJson(s: CloudSearchIndexSnapshot): String = buildString {
        append("{\n")
        append("  \"version\": ").append(s.version).append(",\n")
        append("  \"updatedAtMs\": ").append(s.updatedAtMs).append(",\n")
        append("  \"zipEntries\": [")
        s.zipEntries.forEachIndexed { i, e ->
            if (i > 0) append(",")
            append("{\"chatId\":").append(e.chatId)
                .append(",\"zipMessageId\":").append(e.zipMessageId)
                .append(",\"zipFileName\":").append(jsonStr(e.zipFileName))
                .append(",\"entryPath\":").append(jsonStr(e.entryPath))
                .append(",\"entryName\":").append(jsonStr(e.entryName))
                .append(",\"entrySize\":").append(e.entrySize)
                .append(",\"isEncrypted\":").append(e.isEncrypted)
                .append(",\"indexedAt\":").append(e.indexedAt).append("}")
        }
        append("],\n")
        append("  \"aiEntries\": [")
        s.aiEntries.forEachIndexed { i, a ->
            if (i > 0) append(",")
            appendAiIndexRecord(a)
        }
        append("]\n}\n")
    }

    private fun buildVaultIndexJson(s: CloudVaultIndexSnapshot): String = buildString {
        append("{\n")
        append("  \"version\": ").append(s.version).append(",\n")
        append("  \"updatedAtMs\": ").append(s.updatedAtMs).append(",\n")
        append("  \"entries\": [")
        s.entries.forEachIndexed { i, e ->
            if (i > 0) append(",")
            appendAiIndexRecord(e)
        }
        append("]\n}\n")
    }

    private fun StringBuilder.appendAiIndexRecord(a: CloudAiIndexRecord) {
        append("{\"chatId\":").append(a.chatId)
            .append(",\"messageId\":").append(a.messageId)
            .append(",\"ocrText\":").append(a.ocrText?.let(::jsonStr) ?: "null")
            .append(",\"aiTags\":").append(a.aiTags?.let(::jsonStr) ?: "null")
            .append(",\"exifLat\":").append(a.exifLat?.toString() ?: "null")
            .append(",\"exifLng\":").append(a.exifLng?.toString() ?: "null")
            .append(",\"updatedAtMs\":").append(a.updatedAtMs).append("}")
    }

    private fun jsonStr(s: String): String = buildString(s.length + 2) {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private fun gzip(raw: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(raw) }
        return bos.toByteArray()
    }

    private fun gunzip(compressed: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }

    private fun Map<*, *>.longVal(key: String): Long? = (this[key] as? Number)?.toLong()
    private fun Map<*, *>.doubleVal(key: String): Double? = (this[key] as? Number)?.toDouble()
    private fun Map<*, *>.boolVal(key: String): Boolean? = this[key] as? Boolean
    private fun Map<*, *>.strVal(key: String): String? = this[key] as? String
    private fun Map<*, *>.optArray(key: String): List<Any?> = (this[key] as? List<*>) ?: emptyList()

    private class MiniJson(private val src: String) {
        private var i = 0

        companion object {
            @Suppress("UNCHECKED_CAST")
            fun parseObject(src: String): Map<String, Any?>? =
                MiniJson(src).parseValue() as? Map<String, Any?>
        }

        private fun skipWs() {
            while (i < src.length && src[i].isWhitespace()) i++
        }

        private fun parseValue(): Any? {
            skipWs()
            if (i >= src.length) return null
            return when (val c = src[i]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> parseStr()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                '-', in '0'..'9' -> parseNum()
                else -> throw IllegalArgumentException("Unexpected char '$c' at $i")
            }
        }

        private fun parseObj(): Map<String, Any?> {
            i++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < src.length && src[i] == '}') { i++; return map }
            while (i < src.length) {
                skipWs()
                val k = parseStr()
                skipWs()
                if (src[i] != ':') throw IllegalArgumentException("Expected ':' at $i")
                i++
                val v = parseValue()
                map[k] = v
                skipWs()
                if (i >= src.length) break
                if (src[i] == ',') { i++; continue }
                if (src[i] == '}') { i++; break }
                throw IllegalArgumentException("Expected ',' or '}' at $i")
            }
            return map
        }

        private fun parseArr(): List<Any?> {
            i++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (i < src.length && src[i] == ']') { i++; return list }
            while (i < src.length) {
                list.add(parseValue())
                skipWs()
                if (i >= src.length) break
                if (src[i] == ',') { i++; continue }
                if (src[i] == ']') { i++; break }
                throw IllegalArgumentException("Expected ',' or ']' at $i")
            }
            return list
        }

        private fun parseStr(): String {
            i++ // '"'
            val sb = StringBuilder()
            while (i < src.length) {
                val c = src[i++]
                if (c == '"') return sb.toString()
                if (c == '\\' && i < src.length) {
                    when (val esc = src[i++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = src.substring(i, minOf(i + 4, src.length))
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseNum(): Number {
            val start = i
            if (src[i] == '-') i++
            while (i < src.length && (src[i].isDigit() || src[i] == '.' || src[i] == 'e' || src[i] == 'E' || src[i] == '+' || src[i] == '-')) i++
            val token = src.substring(start, i)
            return token.toLongOrNull() ?: token.toDouble()
        }
    }
}
