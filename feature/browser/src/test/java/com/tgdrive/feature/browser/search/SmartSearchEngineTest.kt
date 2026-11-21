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

        // Typo match ("reciept" -> "Tax_Receipt_2026.pdf")
        val typoHits = SmartSearchEngine.search("reciept", files, zipEntries)
        assertThat(typoHits).hasSize(1)
        assertThat(typoHits.first().displayName).isEqualTo("Tax_Receipt_2026.pdf")

        // Inside-ZIP search
        val zipHits = SmartSearchEngine.search("hospital invocie", files, zipEntries)
        assertThat(zipHits).hasSize(1)
        assertThat(zipHits.first()).isInstanceOf(SmartSearchHit.ZipMemberHit::class.java)

        // Scope filters
        val offlineHits = SmartSearchEngine.search("", files, zipEntries, scope = SearchScopeFilter.OFFLINE)
        assertThat(offlineHits.map { it.displayName }).containsExactly("HolidayVideo.mp4")

        val inZipsHits = SmartSearchEngine.search("invoice", files, zipEntries, scope = SearchScopeFilter.IN_ZIPS)
        assertThat(inZipsHits).hasSize(1)
    }
}
