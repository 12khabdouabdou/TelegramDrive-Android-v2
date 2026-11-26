package com.tgdrive.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "file_cache",
    primaryKeys = ["message_id", "chat_id"],
    indices = [
        Index("date"),
        Index("is_starred"),
        Index(value = ["chat_id", "date"]),
        Index(value = ["file_name", "file_size"]),
        Index("last_accessed"),
        Index("is_pinned_offline"),
        Index("is_locked"),
        Index(value = ["chat_id", "virtual_path"]),
        Index("bucket"),
        Index(value = ["is_locked", "is_encrypted_vault", "bucket", "date"]),
    ],
)
data class FileCacheEntity(
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
    @ColumnInfo(name = "remote_id") val remoteId: String = "",
    @ColumnInfo(name = "date") val date: Long,
    @ColumnInfo(name = "is_starred") val isStarred: Boolean = false,
    @ColumnInfo(name = "last_accessed") val lastAccessed: Long = 0L,
    @ColumnInfo(name = "is_pinned_offline") val isPinnedOffline: Boolean = false,
    @ColumnInfo(name = "playback_position_ms") val playbackPositionMs: Long = 0L,
    @ColumnInfo(name = "virtual_path") val virtualPath: String = "",
    @ColumnInfo(name = "is_locked") val isLocked: Boolean = false,
    @ColumnInfo(name = "is_encrypted_vault") val isEncryptedVault: Boolean = false,
    @ColumnInfo(name = "user_note") val userNote: String? = null,
    @ColumnInfo(name = "ocr_text") val ocrText: String? = null,
    @ColumnInfo(name = "ai_tags") val aiTags: String? = null,
    @ColumnInfo(name = "exif_lat") val exifLat: Double? = null,
    @ColumnInfo(name = "exif_lng") val exifLng: Double? = null,
    @ColumnInfo(name = "bucket") val bucket: String = "",
    @ColumnInfo(name = "is_renamed", defaultValue = "0") val isRenamed: Boolean = false,
)
