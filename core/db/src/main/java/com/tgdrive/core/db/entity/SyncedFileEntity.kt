package com.tgdrive.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks files already synced by AutoBackup to prevent re-uploading
 * the same local file. The [sha256] is nullable — only set when the
 * user enables "strict mode" to minimize I/O on large folders.
 *
 * Per spec §7.
 */
@Entity(
    tableName = "synced_files",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("local_path"),
        Index("folder_id"),
        Index(value = ["local_path", "size"], unique = false),
    ]
)
data class SyncedFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "telegram_message_id") val telegramMessageId: Long,
    @ColumnInfo(name = "sha256") val sha256: String?,
    @ColumnInfo(name = "size") val size: Long,
    @ColumnInfo(name = "synced_at") val syncedAt: Long,
    @ColumnInfo(name = "folder_id") val folderId: Long?,
)
