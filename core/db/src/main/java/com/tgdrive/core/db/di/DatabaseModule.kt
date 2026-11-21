package com.tgdrive.core.db.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tgdrive.core.db.TgDriveDatabase
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.dao.SyncLogDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Migration v1→v2: Change file_cache primary key from (message_id) to
     * composite (message_id, chat_id). Telegram message IDs are per-chat,
     * not globally unique — the old PK caused data loss when switching folders.
     *
     * SQLite doesn't support ALTER TABLE PRIMARY KEY, so we recreate the table.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Create new table with composite PK
            db.execSQL("""
                CREATE TABLE file_cache_new (
                    message_id INTEGER NOT NULL,
                    chat_id INTEGER NOT NULL,
                    file_name TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    mime_type TEXT,
                    thumbnail_path TEXT,
                    remote_id TEXT NOT NULL DEFAULT '',
                    date INTEGER NOT NULL,
                    PRIMARY KEY(message_id, chat_id)
                )
            """.trimIndent())
            // Copy data — existing rows have remote_id, so we include it
            db.execSQL("""
                INSERT INTO file_cache_new (message_id, chat_id, file_name, file_size, mime_type, thumbnail_path, remote_id, date)
                SELECT message_id, chat_id, file_name, file_size, mime_type, thumbnail_path, COALESCE(remote_id, ''), date
                FROM file_cache
            """.trimIndent())
            db.execSQL("DROP TABLE file_cache")
            db.execSQL("ALTER TABLE file_cache_new RENAME TO file_cache")
            db.execSQL("CREATE INDEX index_file_cache_chat_id ON file_cache(chat_id)")
            db.execSQL("CREATE INDEX index_file_cache_date ON file_cache(date)")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add new columns to file_cache
            db.execSQL("ALTER TABLE file_cache ADD COLUMN is_starred INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE file_cache ADD COLUMN last_accessed INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX index_file_cache_is_starred ON file_cache(is_starred)")
            // Add color column to folders
            db.execSQL("ALTER TABLE folders ADD COLUMN color INTEGER NOT NULL DEFAULT ${0xFF2D8CF0.toInt()}")
        }
    }

    internal val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE file_cache ADD COLUMN is_pinned_offline INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_cache_chat_id_date ON file_cache(chat_id, date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_cache_file_name_file_size ON file_cache(file_name, file_size)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_cache_last_accessed ON file_cache(last_accessed)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_cache_is_pinned_offline ON file_cache(is_pinned_offline)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_synced_files_sha256 ON synced_files(sha256)")
            db.execSQL(
                """
                DELETE FROM synced_files
                WHERE id NOT IN (
                    SELECT MAX(id) FROM synced_files GROUP BY local_path, folder_id
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_synced_files_local_path_folder_id ON synced_files(local_path, folder_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_log_started_at ON sync_log(started_at)")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): TgDriveDatabase =
        Room.databaseBuilder(ctx, TgDriveDatabase::class.java, TgDriveDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides fun provideFolderDao(db: TgDriveDatabase): FolderDao = db.folderDao()
    @Provides fun provideSyncedFileDao(db: TgDriveDatabase): SyncedFileDao = db.syncedFileDao()
    @Provides fun provideSyncLogDao(db: TgDriveDatabase): SyncLogDao = db.syncLogDao()
    @Provides fun provideFileCacheDao(db: TgDriveDatabase): FileCacheDao = db.fileCacheDao()
}
