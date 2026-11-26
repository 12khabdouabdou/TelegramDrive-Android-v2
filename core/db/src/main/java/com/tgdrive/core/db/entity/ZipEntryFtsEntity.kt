package com.tgdrive.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

@Fts4(contentEntity = ZipEntryEntity::class)
@Entity(tableName = "zip_entries_fts")
data class ZipEntryFtsEntity(
    @ColumnInfo(name = "entry_name") val entryName: String,
    @ColumnInfo(name = "entry_path") val entryPath: String,
    @ColumnInfo(name = "zip_file_name") val zipFileName: String,
)
