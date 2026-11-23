package com.tgdrive.feature.autobackup

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BackupFailureRecordTest {

    @Test
    fun `encode and decode preserves special characters quotes and newlines`() {
        val records = listOf(
            BackupFailureRecord(
                fileName = "Tax \"2026\"\\copy.pdf",
                localPath = "content://com.android.externalstorage.documents/tree/primary%3ADocs/document/1",
                reason = "Network error\nFLOOD_WAIT_15",
                folderUri = "content://tree/primary%3ADocs",
            ),
            BackupFailureRecord(
                fileName = "4K_Vacation.mp4",
                localPath = "content://doc/2",
                reason = "Exceeds Telegram limit (2 GB)",
                folderUri = "content://tree/primary%3ADCIM",
            ),
        )

        val json = BackupFailureRecord.encode(records)
        assertThat(json).isNotNull()

        val decoded = BackupFailureRecord.decode(json)
        assertThat(decoded).isEqualTo(records)
    }

    @Test
    fun `empty and malformed inputs return null or empty cleanly`() {
        assertThat(BackupFailureRecord.encode(emptyList())).isNull()
        assertThat(BackupFailureRecord.decode(null)).isEmpty()
        assertThat(BackupFailureRecord.decode("")).isEmpty()
        assertThat(BackupFailureRecord.decode("not json")).isEmpty()
    }
}
