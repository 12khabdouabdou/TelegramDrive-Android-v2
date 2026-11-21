package com.tgdrive.core.download.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CloudSyncCodecTest {

    @Test
    fun `meta snapshot round trips through gzip json`() {
        val snap = CloudSyncCodec.CloudMetaSnapshot(
            version = 1,
            updatedAtMs = 123456L,
            stars = listOf(CloudSyncCodec.CloudStarRecord(chatId = 10L, messageId = 55L, isStarred = true, updatedAtMs = 999L)),
            renames = listOf(CloudSyncCodec.CloudRenameRecord(chatId = 10L, messageId = 55L, fileName = "Tax \"2026\".pdf", updatedAtMs = 1000L)),
            folderColors = listOf(CloudSyncCodec.CloudFolderColorRecord(channelId = -1001L, color = 0xFF00C9B1.toInt(), updatedAtMs = 500L)),
            trash = listOf(
                CloudSyncCodec.CloudTrashRecord(
                    messageId = 77L,
                    chatId = 10L,
                    fileName = "deleted.png",
                    fileSize = 2048L,
                    mimeType = "image/png",
                    thumbnailPath = null,
                    remoteId = "rem77",
                    originalDate = 50L,
                    isStarred = false,
                    deletedAt = 888L,
                ),
            ),
            syncedFiles = listOf(
                CloudSyncCodec.CloudSyncedFileRecord(
                    localPath = "content://a/b",
                    telegramMessageId = 900L,
                    sha256 = "abc123",
                    size = 42L,
                    syncedAt = 700L,
                    channelId = null,
                ),
            ),
            playbackPositions = listOf(
                CloudSyncCodec.CloudPlaybackPositionRecord(
                    chatId = 10L,
                    messageId = 88L,
                    positionMs = 123_456L,
                    updatedAtMs = 999L,
                ),
            ),
        )
        val encoded = CloudSyncCodec.encodeMetaGzip(snap)
        val decoded = CloudSyncCodec.decodeMetaGzip(encoded)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.stars).hasSize(1)
        assertThat(decoded.renames.single().fileName).isEqualTo("Tax \"2026\".pdf")
        assertThat(decoded.trash.single().fileName).isEqualTo("deleted.png")
        assertThat(decoded.syncedFiles.single().sha256).isEqualTo("abc123")
        assertThat(decoded.playbackPositions.single().positionMs).isEqualTo(123_456L)

        val newer = snap.copy(
            playbackPositions = listOf(
                CloudSyncCodec.CloudPlaybackPositionRecord(
                    chatId = 10L,
                    messageId = 88L,
                    positionMs = 200_000L,
                    updatedAtMs = 1500L,
                ),
            ),
        )
        val merged = CloudSyncCodec.mergeMeta(snap, newer)
        assertThat(merged.playbackPositions.single().positionMs).isEqualTo(200_000L)
    }

    @Test
    fun `search index snapshot merges without duplicating entries`() {
        val local = CloudSyncCodec.CloudSearchIndexSnapshot(
            updatedAtMs = 100L,
            zipEntries = listOf(
                CloudSyncCodec.CloudZipEntryRecord(1L, 10L, "a.zip", "Docs/old.txt", "old.txt", 10L, false, 100L),
            ),
        )
        val remote = CloudSyncCodec.CloudSearchIndexSnapshot(
            updatedAtMs = 200L,
            zipEntries = listOf(
                CloudSyncCodec.CloudZipEntryRecord(1L, 10L, "a.zip", "Docs/old.txt", "old.txt", 20L, true, 200L),
                CloudSyncCodec.CloudZipEntryRecord(1L, 11L, "b.zip", "Photos/new.jpg", "new.jpg", 30L, false, 200L),
            ),
        )
        val merged = CloudSyncCodec.mergeSearchIndex(local, remote)
        assertThat(merged.zipEntries).hasSize(2)
        val updated = merged.zipEntries.first { it.zipMessageId == 10L }
        assertThat(updated.entrySize).isEqualTo(20L)
        assertThat(updated.isEncrypted).isTrue()
    }
}
