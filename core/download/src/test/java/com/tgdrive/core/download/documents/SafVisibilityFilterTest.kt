package com.tgdrive.core.download.documents

import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.db.entity.FileCacheEntity
import org.junit.jupiter.api.Test

class SafVisibilityFilterTest {

    private fun sampleFile(
        chatId: Long = 100L,
        messageId: Long = 1L,
        fileName: String = "photo.jpg",
        isLocked: Boolean = false,
        isEncryptedVault: Boolean = false,
    ) = FileCacheEntity(
        messageId = messageId,
        chatId = chatId,
        fileName = fileName,
        fileSize = 1024L,
        mimeType = "image/jpeg",
        thumbnailPath = null,
        date = 1_700_000_000L,
        isLocked = isLocked,
        isEncryptedVault = isEncryptedVault,
    )

    @Test
    fun `public regular file is visible when not trashed or locked`() {
        val visible = !TelegramDocumentsProvider.SafVisibilityFilter.isHiddenFile(
            file = sampleFile(),
            trashedIds = emptySet(),
            vaultChatIds = emptySet(),
        )
        assertThat(visible).isTrue()
    }

    @Test
    fun `locked vault tgenc tgethumb and trashed files are hidden`() {
        val filter = TelegramDocumentsProvider.SafVisibilityFilter
        assertThat(filter.isHiddenFile(sampleFile(isLocked = true), emptySet())).isTrue()
        assertThat(filter.isHiddenFile(sampleFile(isEncryptedVault = true), emptySet())).isTrue()
        assertThat(filter.isHiddenFile(sampleFile(fileName = "secret.zip.tgenc"), emptySet())).isTrue()
        assertThat(filter.isHiddenFile(sampleFile(fileName = "thumb_100_1.tgethumb"), emptySet())).isTrue()
        assertThat(filter.isHiddenFile(sampleFile(chatId = 100L, messageId = 7L), setOf(100L to 7L))).isTrue()
        assertThat(filter.isHiddenFile(sampleFile(chatId = 555L), emptySet(), setOf(555L))).isTrue()
        assertThat(filter.isHiddenFolder(555L, setOf(555L))).isTrue()
        assertThat(filter.isHiddenFolder(100L, setOf(555L))).isFalse()
    }
}
