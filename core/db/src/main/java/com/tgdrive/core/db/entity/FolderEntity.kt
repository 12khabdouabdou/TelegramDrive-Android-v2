package com.tgdrive.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folders",
    indices = [Index(value = ["channel_id"], unique = true)]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "color") val color: Int = 0xFF2D8CF0.toInt(), // default accent blue
    @ColumnInfo(name = "is_pinned_offline") val isPinnedOffline: Boolean = false,
    @ColumnInfo(name = "is_vault") val isVault: Boolean = false,
)
