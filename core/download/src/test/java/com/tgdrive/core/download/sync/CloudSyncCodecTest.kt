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

    @Test
    fun `wave2 metadata and ai search index round trip and merge by LWW`() {
        val snap = CloudSyncCodec.CloudMetaSnapshot(
            updatedAtMs = 1000L,
            virtualPaths = listOf(
                CloudSyncCodec.CloudVirtualPathRecord(10L, 1L, "Work/Receipts", 500L),
            ),
            lockedFlags = listOf(
                CloudSyncCodec.CloudLockRecord(10L, 2L, isLocked = true, updatedAtMs = 500L),
            ),
            notes = listOf(
                CloudSyncCodec.CloudNoteRecord(10L, 1L, "Tax deductible \"2026\"\ncheck", 500L),
            ),
            albums = listOf(
                CloudSyncCodec.CloudAlbumRecord("alb-1", "Trip", 10L, 3L, createdAt = 400L, updatedAtMs = 500L, deleted = false),
            ),
            albumItems = listOf(
                CloudSyncCodec.CloudAlbumItemRecord("alb-1", 10L, 3L, addedAt = 500L, removed = false),
            ),
            encryptedThumbs = listOf(
                CloudSyncCodec.CloudEncryptedThumbRecord(10L, 9L, "thumb-remote-v1", 500L),
            ),
        )
        val decoded = CloudSyncCodec.decodeMetaGzip(CloudSyncCodec.encodeMetaGzip(snap))
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.virtualPaths.single().virtualPath).isEqualTo("Work/Receipts")
        assertThat(decoded.lockedFlags.single().isLocked).isTrue()
        assertThat(decoded.notes.single().note).contains("Tax deductible")
        assertThat(decoded.albums.single().name).isEqualTo("Trip")
        assertThat(decoded.albumItems.single().albumId).isEqualTo("alb-1")
        assertThat(decoded.encryptedThumbs.single().thumbRemoteId).isEqualTo("thumb-remote-v1")

        val newer = snap.copy(
            updatedAtMs = 2000L,
            virtualPaths = listOf(
                CloudSyncCodec.CloudVirtualPathRecord(10L, 1L, "Work/Archive", 900L),
            ),
            lockedFlags = listOf(
                CloudSyncCodec.CloudLockRecord(10L, 2L, isLocked = false, updatedAtMs = 900L),
            ),
            notes = listOf(
                CloudSyncCodec.CloudNoteRecord(10L, 1L, "Updated note", 900L),
            ),
            albums = listOf(
                CloudSyncCodec.CloudAlbumRecord("alb-1", "Trip", 10L, 3L, createdAt = 400L, updatedAtMs = 900L, deleted = true),
            ),
            albumItems = listOf(
                CloudSyncCodec.CloudAlbumItemRecord("alb-1", 10L, 3L, addedAt = 900L, removed = true),
            ),
            encryptedThumbs = listOf(
                CloudSyncCodec.CloudEncryptedThumbRecord(10L, 9L, "thumb-remote-v2", 900L),
            ),
        )
        val mergedMeta = CloudSyncCodec.mergeMeta(snap, newer)
        assertThat(mergedMeta.virtualPaths.single().virtualPath).isEqualTo("Work/Archive")
        assertThat(mergedMeta.lockedFlags.single().isLocked).isFalse()
        assertThat(mergedMeta.notes.single().note).isEqualTo("Updated note")
        assertThat(mergedMeta.albums.single().deleted).isTrue()
        assertThat(mergedMeta.albumItems.single().removed).isTrue()
        assertThat(mergedMeta.encryptedThumbs.single().thumbRemoteId).isEqualTo("thumb-remote-v2")

        val aiLocal = CloudSyncCodec.CloudSearchIndexSnapshot(
            updatedAtMs = 100L,
            aiEntries = listOf(
                CloudSyncCodec.CloudAiIndexRecord(10L, 1L, "INVOICE TOTAL $42", "Document, Paper", 36.75, 3.05, 100L),
            ),
        )
        val aiRemote = CloudSyncCodec.CloudSearchIndexSnapshot(
            updatedAtMs = 200L,
            aiEntries = listOf(
                CloudSyncCodec.CloudAiIndexRecord(10L, 1L, "INVOICE TOTAL $99", "Document, Receipt", 36.75, 3.05, 250L),
                CloudSyncCodec.CloudAiIndexRecord(10L, 5L, null, "Beach, Ocean", 41.38, 2.17, 250L),
            ),
        )
        val decodedSearch = CloudSyncCodec.decodeSearchIndexGzip(CloudSyncCodec.encodeSearchIndexGzip(aiRemote))
        assertThat(decodedSearch?.aiEntries).hasSize(2)
        val mergedSearch = CloudSyncCodec.mergeSearchIndex(aiLocal, aiRemote)
        assertThat(mergedSearch.aiEntries).hasSize(2)
        val updatedAi = mergedSearch.aiEntries.first { it.messageId == 1L }
        assertThat(updatedAi.ocrText).contains("$99")
        assertThat(updatedAi.exifLat).isWithin(0.001).of(36.75)

        val vLocal = CloudSyncCodec.CloudVaultIndexSnapshot(
            updatedAtMs = 100L,
            entries = listOf(
                CloudSyncCodec.CloudAiIndexRecord(9L, 1L, "PASSPORT OLD", "document", updatedAtMs = 100L),
            ),
        )
        val vRemote = CloudSyncCodec.CloudVaultIndexSnapshot(
            updatedAtMs = 200L,
            entries = listOf(
                CloudSyncCodec.CloudAiIndexRecord(9L, 1L, "PASSPORT 2026", "document, id", updatedAtMs = 300L),
                CloudSyncCodec.CloudAiIndexRecord(9L, 2L, "BANK IBAN", "bank", updatedAtMs = 200L),
            ),
        )
        val decodedVault = CloudSyncCodec.decodeVaultIndexGzip(CloudSyncCodec.encodeVaultIndexGzip(vRemote))
        assertThat(decodedVault?.entries).hasSize(2)
        val mergedVault = CloudSyncCodec.mergeVaultIndex(vLocal, vRemote)
        assertThat(mergedVault.entries).hasSize(2)
        assertThat(mergedVault.entries.first { it.messageId == 1L }.ocrText).isEqualTo("PASSPORT 2026")
    }
}
