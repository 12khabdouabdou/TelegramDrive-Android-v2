package com.tgdrive.core.download

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DownloadQueueStoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `round trip preserves queue items and downgrades stale DOWNLOADING to QUEUED`() {
        val storeFile = File(tempDir, "queue.json")
        val store = DownloadQueueStore.createForTest(storeFile)
        val original = listOf(
            DownloadItem(
                id = 101L,
                remoteId = "rem_101",
                fileName = "backup \"2026\"\\clip.mp4",
                chatId = -100123L,
                totalBytes = 42_000_000L,
                downloadedBytes = 10_000_000L,
                progress = 0.25f,
                status = DownloadStatus.DOWNLOADING,
                pinOfflineWhenDone = true,
            ),
            DownloadItem(
                id = 102L,
                remoteId = "rem_102",
                fileName = "secret.jpg.tgenc",
                chatId = -100123L,
                status = DownloadStatus.PAUSED,
                errorMessage = "Waiting for Wi-Fi…",
                autoPausedForWifi = true,
            ),
            DownloadItem(
                id = 103L,
                remoteId = "rem_103",
                fileName = "notes.txt",
                chatId = -100456L,
                localPath = "/tmp/notes.txt",
                status = DownloadStatus.DONE,
            ),
        )

        store.save(original)
        val loaded = store.load()

        assertThat(loaded).hasSize(3)
        assertThat(loaded[0].fileName).isEqualTo("backup \"2026\"\\clip.mp4")
        assertThat(loaded[0].status).isEqualTo(DownloadStatus.QUEUED)
        assertThat(loaded[0].pinOfflineWhenDone).isTrue()
        assertThat(loaded[1].status).isEqualTo(DownloadStatus.PAUSED)
        assertThat(loaded[1].autoPausedForWifi).isTrue()
        assertThat(loaded[1].errorMessage).isEqualTo("Waiting for Wi-Fi…")
        assertThat(loaded[2].status).isEqualTo(DownloadStatus.DONE)
        assertThat(loaded[2].localPath).isEqualTo("/tmp/notes.txt")
    }
}
