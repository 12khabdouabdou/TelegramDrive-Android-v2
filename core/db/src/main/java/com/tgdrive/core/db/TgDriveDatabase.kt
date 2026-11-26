package com.tgdrive.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tgdrive.core.db.dao.AlbumDao
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.PendingUploadDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.dao.SyncLogDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.dao.ZipEntryDao
import com.tgdrive.core.db.entity.AlbumEntity
import com.tgdrive.core.db.entity.AlbumItemEntity
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.FileCacheFtsEntity
import com.tgdrive.core.db.entity.FolderEntity
import com.tgdrive.core.db.entity.PendingUploadEntity
import com.tgdrive.core.db.entity.SyncedFileEntity
import com.tgdrive.core.db.entity.SyncLogEntity
import com.tgdrive.core.db.entity.TrashEntryEntity
import com.tgdrive.core.db.entity.ZipEntryEntity
import com.tgdrive.core.db.entity.ZipEntryFtsEntity

@Database(
    entities = [
        FolderEntity::class,
        SyncedFileEntity::class,
        SyncLogEntity::class,
        FileCacheEntity::class,
        FileCacheFtsEntity::class,
        ZipEntryEntity::class,
        ZipEntryFtsEntity::class,
        TrashEntryEntity::class,
        AlbumEntity::class,
        AlbumItemEntity::class,
        PendingUploadEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class TgDriveDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun syncedFileDao(): SyncedFileDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun fileCacheDao(): FileCacheDao
    abstract fun zipEntryDao(): ZipEntryDao
    abstract fun trashEntryDao(): TrashEntryDao
    abstract fun albumDao(): AlbumDao
    abstract fun pendingUploadDao(): PendingUploadDao

    companion object {
        const val NAME = "tgdrive.db"
    }
}
