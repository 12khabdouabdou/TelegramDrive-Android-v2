package com.tgdrive.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "trash_entries",
    primaryKeys = ["message_id", "chat_id"],
    indices = [Index("deleted_at")],
)
data class TrashEntryEntity(
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
    @ColumnInfo(name = "remote_id") val remoteId: String = "",
    @ColumnInfo(name = "original_date") val originalDate: Long,
    @ColumnInfo(name = "is_starred") val isStarred: Boolean = false,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long = System.currentTimeMillis(),
)
