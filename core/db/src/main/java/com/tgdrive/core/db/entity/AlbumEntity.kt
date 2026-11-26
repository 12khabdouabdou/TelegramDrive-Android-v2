package com.tgdrive.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "cover_chat_id") val coverChatId: Long? = null,
    @ColumnInfo(name = "cover_message_id") val coverMessageId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "album_items",
    primaryKeys = ["album_id", "chat_id", "message_id"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chat_id", "message_id"]),
    ],
)
data class AlbumItemEntity(
    @ColumnInfo(name = "album_id") val albumId: String,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)
