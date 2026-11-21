package com.tgdrive.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.FolderEntity
import com.tgdrive.core.db.entity.SyncLogEntity
import com.tgdrive.core.db.entity.SyncedFileEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TgDriveDatabaseTest {

    private lateinit var db: TgDriveDatabase

    @Before
    fun setup() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, TgDriveDatabase::class.java).build()
    }

    @After
    fun teardown() { db.close() }

    @Test
    fun `insert and query folder`() = runTest {
        val id = db.folderDao().insert(
            FolderEntity(channelId = 100L, title = "Test", createdAt = 0)
        )
        val loaded = db.folderDao().getById(id)
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.title).isEqualTo("Test")
        assertThat(loaded.channelId).isEqualTo(100L)
    }

    @Test
    fun `find folder by channel id`() = runTest {
        db.folderDao().insert(FolderEntity(channelId = 42L, title = "Folder", createdAt = 0))
        val f = db.folderDao().getByChannelId(42L)
        assertThat(f?.title).isEqualTo("Folder")
    }

    @Test
    fun `synced file lookup by path and size`() = runTest {
        val folderId = db.folderDao().insert(
            FolderEntity(channelId = 1L, title = "F", createdAt = 0)
        )
        db.syncedFileDao().upsert(
            SyncedFileEntity(
                localPath = "/a/1.jpg",
                telegramMessageId = 5,
                sha256 = null,
                size = 100,
                syncedAt = 0,
                folderId = folderId,
            )
        )
        val hit = db.syncedFileDao().findByPathAndSize("/a/1.jpg", 100)
        val miss = db.syncedFileDao().findByPathAndSize("/a/1.jpg", 999)
        assertThat(hit).isNotNull()
        assertThat(miss).isNull()
    }

    @Test
    fun `synced file cascade delete when folder deleted`() = runTest {
        val folderId = db.folderDao().insert(
            FolderEntity(channelId = 1L, title = "F", createdAt = 0)
        )
        db.syncedFileDao().upsert(
            SyncedFileEntity(
                localPath = "/a/1.jpg", telegramMessageId = 5, sha256 = null,
                size = 100, syncedAt = 0, folderId = folderId,
            )
        )
        assertThat(db.syncedFileDao().forFolder(folderId)).hasSize(1)
        db.folderDao().deleteById(folderId)
        // We have a FK with CASCADE — the synced_files row should be gone.
        // But explicit deleteForFolder is the supported path; FK CASCADE only
        // fires when the parent row is deleted at the SQL level.
        assertThat(db.syncedFileDao().forFolder(folderId)).isEmpty()
    }

    @Test
    fun `sync log keeps only recent entries after trim`() = runTest {
        val now = System.currentTimeMillis()
        // Insert one old entry (30 days ago) and one fresh
        db.syncLogDao().upsert(
            SyncLogEntity(jobId = "old", startedAt = now - 31L * 24 * 60 * 60 * 1000, completedAt = null, status = "FAILURE")
        )
        db.syncLogDao().upsert(
            SyncLogEntity(jobId = "new", startedAt = now, completedAt = now, status = "SUCCESS")
        )

        val threshold = now - 30L * 24 * 60 * 60 * 1000
        val deleted = db.syncLogDao().deleteOlderThan(threshold)
        assertThat(deleted).isEqualTo(1)

        val remaining = db.syncLogDao().get("old")
        assertThat(remaining).isNull()
        val kept = db.syncLogDao().get("new")
        assertThat(kept).isNotNull()
    }

    @Test
    fun `file cache upsert and delete`() = runTest {
        db.fileCacheDao().upsertAll(listOf(
            FileCacheEntity(messageId = 1, chatId = 10, fileName = "a.jpg", fileSize = 100, mimeType = "image/jpeg", thumbnailPath = null, date = 0),
            FileCacheEntity(messageId = 2, chatId = 10, fileName = "b.jpg", fileSize = 200, mimeType = "image/jpeg", thumbnailPath = null, date = 1),
        ))
        assertThat(db.fileCacheDao().countForChat(10)).isEqualTo(2)
        db.fileCacheDao().deleteById(chatId = 10, messageId = 1)
        assertThat(db.fileCacheDao().countForChat(10)).isEqualTo(1)
    }

    @Test
    fun `file cache upsertAll preserves starred lastAccessed rename and thumbnail`() = runTest {
        db.fileCacheDao().upsertAll(listOf(
            FileCacheEntity(
                messageId = 10L,
                chatId = 77L,
                fileName = "orig.jpg",
                fileSize = 100L,
                mimeType = "image/jpeg",
                thumbnailPath = "/tmp/thumb.jpg",
                remoteId = "rem1",
                date = 1000L,
                isStarred = true,
                lastAccessed = 5555L,
                isPinnedOffline = true,
            ),
        ))
        db.fileCacheDao().updateFileName(chatId = 77L, messageId = 10L, newName = "renamed.jpg")

        // Remote sync re-fetches entry with default local fields and null thumbnail
        db.fileCacheDao().upsertAll(listOf(
            FileCacheEntity(
                messageId = 10L,
                chatId = 77L,
                fileName = "orig.jpg",
                fileSize = 120L,
                mimeType = null,
                thumbnailPath = null,
                remoteId = "",
                date = 2000L,
                isStarred = false,
                lastAccessed = 0L,
                isPinnedOffline = false,
            ),
        ))

        val loaded = db.fileCacheDao().getById(chatId = 77L, messageId = 10L)
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.fileName).isEqualTo("renamed.jpg")
        assertThat(loaded.isStarred).isTrue()
        assertThat(loaded.lastAccessed).isEqualTo(5555L)
        assertThat(loaded.isPinnedOffline).isTrue()
        assertThat(loaded.thumbnailPath).isEqualTo("/tmp/thumb.jpg")
        assertThat(loaded.remoteId).isEqualTo("rem1")
        assertThat(loaded.fileSize).isEqualTo(120L)
        assertThat(loaded.date).isEqualTo(2000L)
    }

    @Test
    fun `getRecent excludes zero lastAccessed and offline pins round trip`() = runTest {
        db.fileCacheDao().upsertAll(
            listOf(
                FileCacheEntity(messageId = 1L, chatId = 5L, fileName = "never.txt", fileSize = 10L, mimeType = "text/plain", thumbnailPath = null, date = 10L, lastAccessed = 0L),
                FileCacheEntity(messageId = 2L, chatId = 5L, fileName = "opened.txt", fileSize = 20L, mimeType = "text/plain", thumbnailPath = null, date = 20L, lastAccessed = 100L),
            ),
        )
        val recent = db.fileCacheDao().getRecent()
        assertThat(recent.map { it.messageId }).containsExactly(2L)

        db.fileCacheDao().setPinnedOffline(chatId = 5L, messageId = 1L, pinned = true)
        val pinned = db.fileCacheDao().getPinnedOffline()
        assertThat(pinned.map { it.messageId }).containsExactly(1L)
    }

    @Test
    fun `migration 3 to 4 validates cleanly against Room schema`() = runTest {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val dbFile = ctx.getDatabasePath("migration_3_4_test.db")
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        raw.execSQL(
            """
            CREATE TABLE folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                channel_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                color INTEGER NOT NULL DEFAULT ${0xFF2D8CF0.toInt()}
            )
            """.trimIndent(),
        )
        raw.execSQL("CREATE UNIQUE INDEX index_folders_channel_id ON folders(channel_id)")
        raw.execSQL(
            """
            CREATE TABLE synced_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                local_path TEXT NOT NULL,
                telegram_message_id INTEGER NOT NULL,
                sha256 TEXT,
                size INTEGER NOT NULL,
                synced_at INTEGER NOT NULL,
                folder_id INTEGER,
                FOREIGN KEY(folder_id) REFERENCES folders(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        raw.execSQL("CREATE INDEX index_synced_files_local_path ON synced_files(local_path)")
        raw.execSQL("CREATE INDEX index_synced_files_folder_id ON synced_files(folder_id)")
        raw.execSQL("CREATE INDEX index_synced_files_local_path_size ON synced_files(local_path, size)")
        raw.execSQL(
            """
            CREATE TABLE sync_log (
                job_id TEXT NOT NULL PRIMARY KEY,
                started_at INTEGER NOT NULL,
                completed_at INTEGER,
                files_scanned INTEGER NOT NULL,
                files_uploaded INTEGER NOT NULL,
                files_skipped INTEGER NOT NULL,
                status TEXT NOT NULL,
                error_message TEXT
            )
            """.trimIndent(),
        )
        raw.execSQL(
            """
            CREATE TABLE file_cache (
                message_id INTEGER NOT NULL,
                chat_id INTEGER NOT NULL,
                file_name TEXT NOT NULL,
                file_size INTEGER NOT NULL,
                mime_type TEXT,
                thumbnail_path TEXT,
                remote_id TEXT NOT NULL,
                date INTEGER NOT NULL,
                is_starred INTEGER NOT NULL,
                last_accessed INTEGER NOT NULL,
                PRIMARY KEY(message_id, chat_id)
            )
            """.trimIndent(),
        )
        raw.execSQL("CREATE INDEX index_file_cache_chat_id ON file_cache(chat_id)")
        raw.execSQL("CREATE INDEX index_file_cache_date ON file_cache(date)")
        raw.execSQL("CREATE INDEX index_file_cache_is_starred ON file_cache(is_starred)")
        raw.version = 3
        raw.close()

        val migrated = Room.databaseBuilder(ctx, TgDriveDatabase::class.java, dbFile.absolutePath)
            .addMigrations(com.tgdrive.core.db.di.DatabaseModule.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            assertThat(migrated.fileCacheDao().getPinnedOffline()).isEmpty()
        } finally {
            migrated.close()
            dbFile.delete()
        }
    }
}
