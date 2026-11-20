package com.tgdrive.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "file_cache",
    primaryKeys = ["message_id", "chat_id"],
    indices = [Index("chat_id"), Index("date"), Index("is_starred")]
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
)
