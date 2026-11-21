package com.tgdrive.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.dao.SyncLogDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.FolderEntity
import com.tgdrive.core.db.entity.SyncedFileEntity
import com.tgdrive.core.db.entity.SyncLogEntity

@Database(
    entities = [
        FolderEntity::class,
        SyncedFileEntity::class,
        SyncLogEntity::class,
        FileCacheEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class TgDriveDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun syncedFileDao(): SyncedFileDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun fileCacheDao(): FileCacheDao

    companion object {
        const val NAME = "tgdrive.db"
    }
}
