package com.tgdrive.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = FileCacheEntity::class)
@Entity(tableName = "file_cache_fts")
data class FileCacheFtsEntity(
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "virtual_path") val virtualPath: String,
    @ColumnInfo(name = "user_note") val userNote: String?,
    @ColumnInfo(name = "ocr_text") val ocrText: String?,
    @ColumnInfo(name = "ai_tags") val aiTags: String?,
)
