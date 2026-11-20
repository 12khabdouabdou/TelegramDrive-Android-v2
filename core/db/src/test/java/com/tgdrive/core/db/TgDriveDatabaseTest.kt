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
            ),
        ))

        val loaded = db.fileCacheDao().getById(chatId = 77L, messageId = 10L)
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.fileName).isEqualTo("renamed.jpg")
        assertThat(loaded.isStarred).isTrue()
        assertThat(loaded.lastAccessed).isEqualTo(5555L)
        assertThat(loaded.thumbnailPath).isEqualTo("/tmp/thumb.jpg")
        assertThat(loaded.remoteId).isEqualTo("rem1")
        assertThat(loaded.fileSize).isEqualTo(120L)
        assertThat(loaded.date).isEqualTo(2000L)
    }
}
