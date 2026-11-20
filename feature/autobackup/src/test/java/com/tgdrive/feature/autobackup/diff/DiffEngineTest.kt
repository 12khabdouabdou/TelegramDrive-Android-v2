package com.tgdrive.feature.autobackup.diff

import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.db.entity.SyncedFileEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DiffEngineTest {

    private val sampleLocal = listOf(
        LocalFile("/a/1.jpg", "1.jpg", 100, 0, "image/jpeg"),
        LocalFile("/a/2.jpg", "2.jpg", 200, 0, "image/jpeg"),
        LocalFile("/a/3.jpg", "3.jpg", 300, 0, "image/jpeg"),
    )

    @Test
    fun `empty already list uploads all files`() = runTest {
        val r = DiffEngine().compute(sampleLocal, emptyList())
        assertThat(r.toUpload).hasSize(3)
        assertThat(r.toSkip).isEmpty()
    }

    @Test
    fun `matching path and size is skipped`() = runTest {
        val already = listOf(
            SyncedFileEntity(localPath = "/a/1.jpg", telegramMessageId = 1, sha256 = null, size = 100, syncedAt = 0, folderId = 1),
        )
        val r = DiffEngine().compute(sampleLocal, already)
        assertThat(r.toUpload.map { it.name }).containsExactly("2.jpg", "3.jpg")
        assertThat(r.toSkip.map { it.name }).containsExactly("1.jpg")
    }

    @Test
    fun `same path but different size is uploaded`() = runTest {
        val already = listOf(
            SyncedFileEntity(localPath = "/a/1.jpg", telegramMessageId = 1, sha256 = null, size = 999, syncedAt = 0, folderId = 1),
        )
        val r = DiffEngine().compute(sampleLocal, already)
        assertThat(r.toUpload).hasSize(3)
        assertThat(r.toSkip).isEmpty()
    }

    @Test
    fun `strict mode uses sha256 when available`() = runTest {
        val local = listOf(
            LocalFile("/a/1.txt", "1.txt", 100, 0, "text/plain", sha256 = "abc"),
            LocalFile("/a/2.txt", "2.txt", 100, 0, "text/plain", sha256 = "def"),
        )
        val already = listOf(
            // Same name + size as #1 but different sha → should re-upload in strict mode
            SyncedFileEntity(localPath = "/a/1.txt", telegramMessageId = 1, sha256 = "different", size = 100, syncedAt = 0, folderId = 1),
            // Different name + same sha as #2 → should skip
            SyncedFileEntity(localPath = "/a/2_other.txt", telegramMessageId = 2, sha256 = "def", size = 100, syncedAt = 0, folderId = 1),
        )
        val r = DiffEngine().compute(local, already, strict = true)
        assertThat(r.toUpload.map { it.name }).containsExactly("1.txt")
        assertThat(r.toSkip.map { it.name }).containsExactly("2.txt")
    }

    @Test
    fun `non-strict mode falls back to path-size when sha differs but path-size matches`() = runTest {
        val local = listOf(
            LocalFile("/a/1.txt", "1.txt", 100, 0, "text/plain", sha256 = "abc"),
        )
        val already = listOf(
            SyncedFileEntity(localPath = "/a/1.txt", telegramMessageId = 1, sha256 = "different", size = 100, syncedAt = 0, folderId = 1),
        )
        // Non-strict: path-size match wins → skip
        val r = DiffEngine().compute(local, already, strict = false)
        assertThat(r.toSkip).hasSize(1)
        assertThat(r.toUpload).isEmpty()
    }

    @Test
    fun `empty local list yields empty result`() = runTest {
        val r = DiffEngine().compute(emptyList(), emptyList())
        assertThat(r.toUpload).isEmpty()
        assertThat(r.toSkip).isEmpty()
    }

    @Test
    fun `strict mode with lazy sha computation verifies path-size matches`() = runTest {
        // Local file has no precomputed sha; provider returns "abc".
        // Already has same path+size with sha="different" → strict mode
        // should compute the local sha and detect the mismatch.
        val local = listOf(
            LocalFile("/a/1.txt", "1.txt", 100, 0, "text/plain", sha256 = null),
        )
        val already = listOf(
            SyncedFileEntity(localPath = "/a/1.txt", telegramMessageId = 1, sha256 = "different", size = 100, syncedAt = 0, folderId = 1),
        )
        val r = DiffEngine(sha256Provider = { _ -> "abc" }).compute(local, already, strict = true)
        assertThat(r.toUpload.map { it.sha256 }).containsExactly("abc")
        assertThat(r.toSkip).isEmpty()
    }

    @Test
    fun `stale remote paths are reported`() = runTest {
        // Local has only /a/1.jpg. Already has /a/1.jpg AND /a/deleted.jpg.
        // /a/deleted.jpg should appear in staleRemotePaths.
        val local = listOf(
            LocalFile("/a/1.jpg", "1.jpg", 100, 0, "image/jpeg"),
        )
        val already = listOf(
            SyncedFileEntity(localPath = "/a/1.jpg", telegramMessageId = 1, sha256 = null, size = 100, syncedAt = 0, folderId = 1),
            SyncedFileEntity(localPath = "/a/deleted.jpg", telegramMessageId = 2, sha256 = null, size = 200, syncedAt = 0, folderId = 1),
        )
        val r = DiffEngine().compute(local, already)
        assertThat(r.staleRemotePaths).containsExactly("/a/deleted.jpg")
    }

    @Test
    fun `strict mode populates sha256 on first upload and skips moved files with matching sha`() = runTest {
        val local = listOf(
            LocalFile("/a/new.txt", "new.txt", 100, 0, "text/plain", sha256 = null),
            LocalFile("/a/moved.txt", "moved.txt", 100, 0, "text/plain", sha256 = null),
        )
        val already = listOf(
            SyncedFileEntity(localPath = "/a/original.txt", telegramMessageId = 1, sha256 = "sha_moved", size = 100, syncedAt = 0, folderId = 1),
        )
        val r = DiffEngine(sha256Provider = { path ->
            if (path == "/a/moved.txt") "sha_moved" else "sha_new"
        }).compute(local, already, strict = true)

        assertThat(r.toUpload.map { it.name to it.sha256 }).containsExactly("new.txt" to "sha_new")
        assertThat(r.toSkip.map { it.name to it.sha256 }).containsExactly("moved.txt" to "sha_moved")
    }

    @Test
    fun `large local list completes without stack overflow`() = runTest {
        // Verify that the diff handles a large list without crashing.
        // (Previously, a blocking diff on 50k files could OOM or ANR.)
        val local = (1..10_000).map { i ->
            LocalFile("/a/file_$i.bin", "file_$i.bin", 100L * i, 0L, "application/octet-stream")
        }
        val r = DiffEngine().compute(local, emptyList())
        assertThat(r.toUpload).hasSize(10_000)
    }
}
