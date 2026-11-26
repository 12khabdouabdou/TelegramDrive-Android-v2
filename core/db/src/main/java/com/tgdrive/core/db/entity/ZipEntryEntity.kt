package com.tgdrive.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "zip_entries",
    indices = [
        Index("entry_name"),
        Index(value = ["chat_id", "zip_message_id", "entry_path"], unique = true),
    ],
)
data class ZipEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    @ColumnInfo(name = "zip_message_id") val zipMessageId: Long,
    @ColumnInfo(name = "zip_file_name") val zipFileName: String,
    @ColumnInfo(name = "entry_path") val entryPath: String,
    @ColumnInfo(name = "entry_name") val entryName: String,
    @ColumnInfo(name = "entry_size") val entrySize: Long,
    @ColumnInfo(name = "is_encrypted") val isEncrypted: Boolean = false,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long = System.currentTimeMillis(),
)
