package com.tgdrive.core.db.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tgdrive.core.db.TgDriveDatabase
import com.tgdrive.core.db.dao.AlbumDao
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.PendingUploadDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.dao.SyncLogDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.dao.ZipEntryDao
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
    internal val MIGRATION_1_2 = object : Migration(1, 2) {
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

    internal val MIGRATION_2_3 = object : Migration(2, 3) {
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

    internal val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS zip_entries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    chat_id INTEGER NOT NULL,
                    zip_message_id INTEGER NOT NULL,
                    zip_file_name TEXT NOT NULL,
                    entry_path TEXT NOT NULL,
                    entry_name TEXT NOT NULL,
                    entry_size INTEGER NOT NULL,
                    is_encrypted INTEGER NOT NULL,
                    indexed_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_zip_entries_chat_id_zip_message_id ON zip_entries(chat_id, zip_message_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_zip_entries_entry_name ON zip_entries(entry_name)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_zip_entries_chat_id_zip_message_id_entry_path ON zip_entries(chat_id, zip_message_id, entry_path)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS trash_entries (
                    message_id INTEGER NOT NULL,
                    chat_id INTEGER NOT NULL,
                    file_name TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    mime_type TEXT,
                    thumbnail_path TEXT,
                    remote_id TEXT NOT NULL,
                    original_date INTEGER NOT NULL,
                    is_starred INTEGER NOT NULL,
                    deleted_at INTEGER NOT NULL,
                    PRIMARY KEY(message_id, chat_id)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_trash_entries_deleted_at ON trash_entries(deleted_at)")
        }
    }

    internal val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE file_cache ADD COLUMN playback_position_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE file_cache ADD COLUMN virtual_path TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE file_cache ADD COLUMN is_locked INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE file_cache ADD COLUMN is_encrypted_vault INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE file_cache ADD COLUMN user_note TEXT")
            db.execSQL("ALTER TABLE file_cache ADD COLUMN ocr_text TEXT")
            db.execSQL("ALTER TABLE file_cache ADD COLUMN ai_tags TEXT")
            db.execSQL("ALTER TABLE file_cache ADD COLUMN exif_lat REAL")
            db.execSQL("ALTER TABLE file_cache ADD COLUMN exif_lng REAL")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_cache_is_locked ON file_cache(is_locked)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_file_cache_chat_id_virtual_path ON file_cache(chat_id, virtual_path)",
            )

            db.execSQL("ALTER TABLE folders ADD COLUMN is_pinned_offline INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE folders ADD COLUMN is_vault INTEGER NOT NULL DEFAULT 0")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS albums (
                    id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    cover_chat_id INTEGER,
                    cover_message_id INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS album_items (
                    album_id TEXT NOT NULL,
                    chat_id INTEGER NOT NULL,
                    message_id INTEGER NOT NULL,
                    added_at INTEGER NOT NULL,
                    PRIMARY KEY(album_id, chat_id, message_id),
                    FOREIGN KEY(album_id) REFERENCES albums(id) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_album_items_album_id ON album_items(album_id)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_album_items_chat_id_message_id ON album_items(chat_id, message_id)",
            )
        }
    }

    internal val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE synced_files ADD COLUMN last_modified INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sync_log ADD COLUMN failed_count INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sync_log ADD COLUMN failed_details_json TEXT")
            db.execSQL("ALTER TABLE sync_log ADD COLUMN bytes_uploaded INTEGER NOT NULL DEFAULT 0")
        }
    }

    internal val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pending_uploads (
                    id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    chat_id INTEGER NOT NULL,
                    display_name TEXT NOT NULL,
                    total_bytes INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    is_encrypted INTEGER NOT NULL DEFAULT 0,
                    created_at_ms INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )
                """.trimIndent(),
            )
        }
    }

    internal val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE file_cache ADD COLUMN bucket TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                """
                UPDATE file_cache
                SET bucket = CASE
                    WHEN mime_type IS NOT NULL
                        AND mime_type != ''
                        AND lower(mime_type) != 'application/octet-stream'
                        AND lower(mime_type) LIKE 'image/%' THEN 'IMAGES'
                    WHEN mime_type IS NOT NULL
                        AND mime_type != ''
                        AND lower(mime_type) != 'application/octet-stream'
                        AND lower(mime_type) LIKE 'video/%' THEN 'VIDEOS'
                    WHEN mime_type IS NOT NULL
                        AND mime_type != ''
                        AND lower(mime_type) != 'application/octet-stream'
                        AND lower(mime_type) LIKE 'audio/%' THEN 'AUDIO'
                    WHEN mime_type IS NOT NULL
                        AND lower(mime_type) != ''
                        AND lower(mime_type) != 'application/octet-stream'
                        AND lower(mime_type) = 'application/pdf' THEN 'PDF'
                    WHEN lower(file_name) LIKE '%.jpg'
                        OR lower(file_name) LIKE '%.jpeg'
                        OR lower(file_name) LIKE '%.png'
                        OR lower(file_name) LIKE '%.gif'
                        OR lower(file_name) LIKE '%.webp'
                        OR lower(file_name) LIKE '%.heic'
                        OR lower(file_name) LIKE '%.bmp'
                        OR lower(file_name) LIKE '%.heif'
                        OR lower(file_name) LIKE '%.avif' THEN 'IMAGES'
                    WHEN lower(file_name) LIKE '%.mp4'
                        OR lower(file_name) LIKE '%.mkv'
                        OR lower(file_name) LIKE '%.mov'
                        OR lower(file_name) LIKE '%.avi'
                        OR lower(file_name) LIKE '%.webm'
                        OR lower(file_name) LIKE '%.m4v'
                        OR lower(file_name) LIKE '%.3gp' THEN 'VIDEOS'
                    WHEN lower(file_name) LIKE '%.mp3'
                        OR lower(file_name) LIKE '%.wav'
                        OR lower(file_name) LIKE '%.flac'
                        OR lower(file_name) LIKE '%.aac'
                        OR lower(file_name) LIKE '%.m4a'
                        OR lower(file_name) LIKE '%.ogg'
                        OR lower(file_name) LIKE '%.opus' THEN 'AUDIO'
                    WHEN lower(file_name) LIKE '%.pdf' THEN 'PDF'
                    WHEN lower(file_name) LIKE '%.txt'
                        OR lower(file_name) LIKE '%.doc'
                        OR lower(file_name) LIKE '%.docx'
                        OR lower(file_name) LIKE '%.rtf'
                        OR lower(file_name) LIKE '%.odt'
                        OR lower(file_name) LIKE '%.md'
                        OR lower(file_name) LIKE '%.csv'
                        OR lower(file_name) LIKE '%.xls'
                        OR lower(file_name) LIKE '%.xlsx'
                        OR lower(file_name) LIKE '%.ppt'
                        OR lower(file_name) LIKE '%.pptx' THEN 'DOCUMENTS'
                    WHEN lower(file_name) LIKE '%.zip'
                        OR lower(file_name) LIKE '%.rar'
                        OR lower(file_name) LIKE '%.7z'
                        OR lower(file_name) LIKE '%.tar'
                        OR lower(file_name) LIKE '%.gz'
                        OR lower(file_name) LIKE '%.bz2'
                        OR lower(file_name) LIKE '%.xz' THEN 'ARCHIVES'
                    ELSE 'OTHER'
                END
                WHERE bucket = ''
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_file_cache_bucket ON file_cache(bucket)")
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS `file_cache_fts` USING FTS4(" +
                    "`file_name` TEXT NOT NULL, " +
                    "`virtual_path` TEXT NOT NULL, " +
                    "`user_note` TEXT, " +
                    "`ocr_text` TEXT, " +
                    "`ai_tags` TEXT, " +
                    "content=`file_cache`)",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_file_cache_fts_BEFORE_UPDATE` " +
                    "BEFORE UPDATE ON `file_cache` BEGIN " +
                    "DELETE FROM `file_cache_fts` WHERE `docid`=OLD.`rowid`; END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_file_cache_fts_BEFORE_DELETE` " +
                    "BEFORE DELETE ON `file_cache` BEGIN " +
                    "DELETE FROM `file_cache_fts` WHERE `docid`=OLD.`rowid`; END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_file_cache_fts_AFTER_UPDATE` " +
                    "AFTER UPDATE ON `file_cache` BEGIN " +
                    "INSERT INTO `file_cache_fts`(`docid`, `file_name`, `virtual_path`, `user_note`, `ocr_text`, `ai_tags`) " +
                    "VALUES (NEW.`rowid`, NEW.`file_name`, NEW.`virtual_path`, NEW.`user_note`, NEW.`ocr_text`, NEW.`ai_tags`); END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_file_cache_fts_AFTER_INSERT` " +
                    "AFTER INSERT ON `file_cache` BEGIN " +
                    "INSERT INTO `file_cache_fts`(`docid`, `file_name`, `virtual_path`, `user_note`, `ocr_text`, `ai_tags`) " +
                    "VALUES (NEW.`rowid`, NEW.`file_name`, NEW.`virtual_path`, NEW.`user_note`, NEW.`ocr_text`, NEW.`ai_tags`); END",
            )
            db.execSQL("INSERT INTO `file_cache_fts`(`file_cache_fts`) VALUES('rebuild')")
        }
    }

    internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
    )

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): TgDriveDatabase =
        Room.databaseBuilder(ctx, TgDriveDatabase::class.java, TgDriveDatabase.NAME)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides fun provideFolderDao(db: TgDriveDatabase): FolderDao = db.folderDao()
    @Provides fun provideSyncedFileDao(db: TgDriveDatabase): SyncedFileDao = db.syncedFileDao()
    @Provides fun provideSyncLogDao(db: TgDriveDatabase): SyncLogDao = db.syncLogDao()
    @Provides fun provideFileCacheDao(db: TgDriveDatabase): FileCacheDao = db.fileCacheDao()
    @Provides fun provideZipEntryDao(db: TgDriveDatabase): ZipEntryDao = db.zipEntryDao()
    @Provides fun provideTrashEntryDao(db: TgDriveDatabase): TrashEntryDao = db.trashEntryDao()
    @Provides fun provideAlbumDao(db: TgDriveDatabase): AlbumDao = db.albumDao()
    @Provides fun providePendingUploadDao(db: TgDriveDatabase): PendingUploadDao = db.pendingUploadDao()
}
