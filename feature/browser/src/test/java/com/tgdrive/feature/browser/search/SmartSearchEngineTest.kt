package com.tgdrive.feature.browser.search

import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.ZipEntryEntity
import org.junit.jupiter.api.Test

class SmartSearchEngineTest {

    @Test
    fun `finds files across fuzzy typos and zip archive entries`() {
        val files = listOf(
            FileCacheEntity(1L, 100L, "Tax_Receipt_2026.pdf", 1024L, "application/pdf", null, "r1", 10L, isStarred = true),
            FileCacheEntity(2L, 100L, "HolidayVideo.mp4", 4096L, "video/mp4", null, "r2", 20L, isPinnedOffline = true),
        )
        val zipEntries = listOf(
            ZipEntryEntity(
                id = 1L,
                chatId = 100L,
                zipMessageId = 99L,
                zipFileName = "Taxes_part001.zip",
                entryPath = "Taxes/Invoices/hospital_invoice_may.pdf",
                entryName = "hospital_invoice_may.pdf",
                entrySize = 512L,
                isEncrypted = true,
            ),
        )

        val typoHits = SmartSearchEngine.search("reciept", files, zipEntries)
        assertThat(typoHits).hasSize(1)
        assertThat(typoHits.first().displayName).isEqualTo("Tax_Receipt_2026.pdf")

        val zipHits = SmartSearchEngine.search("hospital invocie", files, zipEntries)
        assertThat(zipHits).hasSize(1)
        assertThat(zipHits.first()).isInstanceOf(SmartSearchHit.ZipMemberHit::class.java)

        val offlineHits = SmartSearchEngine.search("", files, zipEntries, scope = SearchScopeFilter.OFFLINE)
        assertThat(offlineHits.map { it.displayName }).containsExactly("HolidayVideo.mp4")

        val inZipsHits = SmartSearchEngine.search("invoice", files, zipEntries, scope = SearchScopeFilter.IN_ZIPS)
        assertThat(inZipsHits).hasSize(1)
    }

    @Test
    fun `supports inline operators OCR AI tags and suppresses locked or tgethumb files`() {
        val jan2026Ms = 1_767_225_600_000L
        val jan2024Ms = 1_704_067_200_000L
        val files = listOf(
            FileCacheEntity(
                messageId = 10L,
                chatId = 200L,
                fileName = "IMG_8842.jpg",
                fileSize = 6_000_000L,
                mimeType = "image/jpeg",
                thumbnailPath = null,
                date = jan2026Ms,
                ocrText = "Acme Pharmacy Receipt Total $42",
                aiTags = "receipt, paper, document",
            ),
            FileCacheEntity(
                messageId = 11L,
                chatId = 200L,
                fileName = "secret_locked.pdf",
                fileSize = 8_000_000L,
                mimeType = "application/pdf",
                thumbnailPath = null,
                date = jan2026Ms,
            ).copy(isLocked = true),
            FileCacheEntity(
                messageId = 12L,
                chatId = 200L,
                fileName = "thumb_99.jpg.tgethumb",
                fileSize = 4096L,
                mimeType = "application/octet-stream",
                thumbnailPath = null,
                date = jan2026Ms,
            ),
            FileCacheEntity(
                messageId = 13L,
                chatId = 300L,
                fileName = "Passport_Scan.pdf.tgenc",
                fileSize = 2_000_000L,
                mimeType = "application/octet-stream",
                thumbnailPath = null,
                date = jan2024Ms,
                isEncryptedVault = true,
            ),
        )

        val ocrHits = SmartSearchEngine.search(
            query = "pharmacy ext:jpg size:>5mb after:2025-01-01",
            files = files,
            zipEntries = emptyList(),
        )
        assertThat(ocrHits).hasSize(1)
        val cloudHit = ocrHits.single() as SmartSearchHit.CloudFileHit
        assertThat(cloudHit.entity.messageId).isEqualTo(10L)
        assertThat(cloudHit.matchHint).startsWith("OCR:")

        val encryptedHits = SmartSearchEngine.search(
            query = "ext:pdf is:encrypted in:\"Legal Vault\"",
            files = files,
            zipEntries = emptyList(),
            criteria = SearchFilterCriteria(folderNames = mapOf(300L to "Legal Vault")),
        )
        assertThat(encryptedHits.map { it.displayName }).containsExactly("Passport_Scan.pdf.tgenc")

        val allLockedExcluded = SmartSearchEngine.search(
            query = "secret",
            files = files,
            zipEntries = emptyList(),
        )
        assertThat(allLockedExcluded).isEmpty()

        val thumbExcluded = SmartSearchEngine.search(
            query = "thumb",
            files = files,
            zipEntries = emptyList(),
            scope = SearchScopeFilter.ENCRYPTED,
        )
        assertThat(thumbExcluded).isEmpty()
    }
}
