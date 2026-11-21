package com.tgdrive.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.dao.SyncLogDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.dao.ZipEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.FolderEntity
import com.tgdrive.core.db.entity.SyncedFileEntity
import com.tgdrive.core.db.entity.SyncLogEntity
import com.tgdrive.core.db.entity.TrashEntryEntity
import com.tgdrive.core.db.entity.ZipEntryEntity

@Database(
    entities = [
        FolderEntity::class,
        SyncedFileEntity::class,
        SyncLogEntity::class,
        FileCacheEntity::class,
        ZipEntryEntity::class,
        TrashEntryEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class TgDriveDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun syncedFileDao(): SyncedFileDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun fileCacheDao(): FileCacheDao
    abstract fun zipEntryDao(): ZipEntryDao
    abstract fun trashEntryDao(): TrashEntryDao

    companion object {
        const val NAME = "tgdrive.db"
    }
}
