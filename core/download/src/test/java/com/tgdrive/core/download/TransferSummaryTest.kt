package com.tgdrive.core.download

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TransferSummaryTest {

    @Test
    fun `formatTransferSummary formats single upload with status verb`() {
        val upload = UploadItem(
            id = "u1",
            chatId = 10L,
            fileName = "backup.zip",
            totalBytes = 1_000L,
            progress = 0.42f,
            status = UploadStatus.ENCRYPTING,
        )
        val (text, pct) = TransferKeepAliveWorker.formatTransferSummary(listOf(upload), emptyList())
        assertThat(pct).isEqualTo(42)
        assertThat(text).isEqualTo("Encrypting backup.zip (42%)")
    }

    @Test
    fun `formatTransferSummary computes byte-weighted progress across mixed transfers`() {
        val upload = UploadItem(
            id = "u1",
            chatId = 10L,
            fileName = "a.bin",
            totalBytes = 3_000L,
            progress = 0.5f, // 1500 done
            status = UploadStatus.UPLOADING,
        )
        val download = DownloadItem(
            id = 2L,
            chatId = 10L,
            remoteId = "r2",
            fileName = "b.bin",
            totalBytes = 1_000L,
            progress = 1.0f, // 1000 done
            status = DownloadStatus.DOWNLOADING,
        )
        // Weighted total = 2500 / 4000 = 62.5% -> 63% (rounded)
        val (text, pct) = TransferKeepAliveWorker.formatTransferSummary(listOf(upload), listOf(download))
        assertThat(pct).isEqualTo(63)
        assertThat(text).isEqualTo("1 upload(s), 1 download(s) (63%)")
    }

    @Test
    fun `formatTransferSummary falls back to unweighted average when totalBytes is 0`() {
        val dl1 = DownloadItem(
            id = 1L,
            chatId = 10L,
            remoteId = "r1",
            fileName = "one.pdf",
            totalBytes = 0L,
            progress = 0.25f,
            status = DownloadStatus.DOWNLOADING,
        )
        val dl2 = DownloadItem(
            id = 2L,
            chatId = 10L,
            remoteId = "r2",
            fileName = "two.pdf",
            totalBytes = 0L,
            progress = 0.75f,
            status = DownloadStatus.DOWNLOADING,
        )
        val (text, pct) = TransferKeepAliveWorker.formatTransferSummary(emptyList(), listOf(dl1, dl2))
        assertThat(pct).isEqualTo(50)
        assertThat(text).isEqualTo("Downloading 2 file(s) (50%)")
    }
}
