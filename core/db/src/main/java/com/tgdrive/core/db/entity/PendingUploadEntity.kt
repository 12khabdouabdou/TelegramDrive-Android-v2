package com.tgdrive.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_uploads")
data class PendingUploadEntity(
    @PrimaryKey val id: String,
    val kind: String,
    @ColumnInfo(name = "chat_id") val chatId: Long,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    val status: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "is_encrypted") val isEncrypted: Boolean = false,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long = System.currentTimeMillis(),
)
