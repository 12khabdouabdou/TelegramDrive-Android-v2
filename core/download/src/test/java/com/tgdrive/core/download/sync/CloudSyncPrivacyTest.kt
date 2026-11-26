package com.tgdrive.core.download.sync

import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.ai.MediaAiIndexer
import org.junit.jupiter.api.Test

class CloudSyncPrivacyTest {

    @Test
    fun `filterPublicAiFiles excludes encrypted PassVault and locked items from cloud search sync`() {
        val publicPhoto = FileCacheEntity(
            messageId = 1L,
            chatId = 100L,
            fileName = "Vacation.jpg",
            fileSize = 1024L,
            mimeType = "image/jpeg",
            thumbnailPath = null,
            date = 1000L,
            ocrText = "Menu",
            aiTags = "food, loc:rome",
        )
        val vaultFlagged = FileCacheEntity(
            messageId = 2L,
            chatId = 100L,
            fileName = "Secret.jpg",
            fileSize = 1024L,
            mimeType = "image/jpeg",
            thumbnailPath = null,
            date = 1000L,
            isEncryptedVault = true,
            ocrText = "Private key",
        )
        val tgencNamed = FileCacheEntity(
            messageId = 3L,
            chatId = 100L,
            fileName = "Passport.pdf.tgenc",
            fileSize = 2048L,
            mimeType = "application/octet-stream",
            thumbnailPath = null,
            date = 1000L,
            ocrText = "Passport number",
        )
        val hiddenLocked = FileCacheEntity(
            messageId = 4L,
            chatId = 100L,
            fileName = "HiddenNote.png",
            fileSize = 512L,
            mimeType = "image/png",
            thumbnailPath = null,
            date = 1000L,
            isLocked = true,
            aiTags = "document",
        )

        val filtered = CloudSyncManager.filterPublicAiFiles(
            listOf(publicPhoto, vaultFlagged, tgencNamed, hiddenLocked),
        )
        assertThat(filtered.map { it.messageId }).containsExactly(1L)
        assertThat(CloudSyncManager.isPrivateOrEncryptedForSync(publicPhoto)).isFalse()
        assertThat(CloudSyncManager.isPrivateOrEncryptedForSync(vaultFlagged)).isTrue()
        assertThat(CloudSyncManager.isPrivateOrEncryptedForSync(tgencNamed)).isTrue()
        assertThat(CloudSyncManager.isPrivateOrEncryptedForSync(hiddenLocked)).isTrue()
    }

    @Test
    fun `filterRenamedFilesForSync omits un-renamed cache entries to keep meta snapshots slim`() {
        val untouched = FileCacheEntity(
            messageId = 10L,
            chatId = 100L,
            fileName = "IMG_1234.jpg",
            fileSize = 1000L,
            mimeType = "image/jpeg",
            thumbnailPath = null,
            date = 1000L,
            isRenamed = false,
        )
        val renamed = FileCacheEntity(
            messageId = 11L,
            chatId = 100L,
            fileName = "Tax_2026_Final.pdf",
            fileSize = 2000L,
            mimeType = "application/pdf",
            thumbnailPath = null,
            date = 1000L,
            isRenamed = true,
        )
        val filtered = CloudSyncManager.filterRenamedFilesForSync(listOf(untouched, renamed))
        assertThat(filtered.map { it.messageId }).containsExactly(11L)
    }

    @Test
    fun `mergeAiTags preserves existing tags and deduplicates new visual and location tags`() {
        val merged = MediaAiIndexer.mergeAiTags(
            existingTags = "beach, loc:nice",
            visualOrAudioTags = listOf("ocean", "beach", "sky"),
            locationTags = listOf("loc:nice", "loc:france"),
        )
        assertThat(merged).isEqualTo("beach,loc:nice,ocean,sky,loc:france")
    }
}
