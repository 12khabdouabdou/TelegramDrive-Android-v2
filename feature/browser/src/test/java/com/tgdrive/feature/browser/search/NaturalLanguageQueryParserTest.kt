package com.tgdrive.feature.browser.search

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class NaturalLanguageQueryParserTest {

    private val fixedNow = LocalDate.of(2026, 11, 26)
    private val zone = ZoneId.of("UTC")

    @Test
    fun `parses english and french relative date expressions deterministically`() {
        val today = NaturalLanguageQueryParser.parseNaturalLanguage("photos today", nowDate = fixedNow, zoneId = zone)
        assertThat(today.inferredScope).isEqualTo(SearchScopeFilter.IMAGES)
        assertThat(today.inferredAfterMs).isEqualTo(fixedNow.atStartOfDay(zone).toInstant().toEpochMilli())
        assertThat(today.cleanedTextQuery).isEmpty()

        val yesterdayFr = NaturalLanguageQueryParser.parseNaturalLanguage("factures hier", nowDate = fixedNow, zoneId = zone)
        val expectedYest = fixedNow.minusDays(1)
        assertThat(yesterdayFr.inferredAfterMs).isEqualTo(expectedYest.atStartOfDay(zone).toInstant().toEpochMilli())
        assertThat(yesterdayFr.inferredBeforeMs).isEqualTo(expectedYest.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L)
        assertThat(yesterdayFr.cleanedTextQuery).isEqualTo("factures")

        val lastMonth = NaturalLanguageQueryParser.parseNaturalLanguage("pdfs from last month", nowDate = fixedNow, zoneId = zone)
        assertThat(lastMonth.inferredExtensions).containsExactly("pdf")
        val octStart = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val octEnd = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L
        assertThat(lastMonth.inferredAfterMs).isEqualTo(octStart)
        assertThat(lastMonth.inferredBeforeMs).isEqualTo(octEnd)
        assertThat(lastMonth.cleanedTextQuery).isEmpty()

        val lastMonthFr = NaturalLanguageQueryParser.parseNaturalLanguage("recus le mois dernier", nowDate = fixedNow, zoneId = zone)
        assertThat(lastMonthFr.inferredAfterMs).isEqualTo(octStart)
        assertThat(lastMonthFr.inferredBeforeMs).isEqualTo(octEnd)
        assertThat(lastMonthFr.cleanedTextQuery).isEqualTo("recus")

        val last14Days = NaturalLanguageQueryParser.parseNaturalLanguage("videos past 14 days", nowDate = fixedNow, zoneId = zone)
        assertThat(last14Days.inferredScope).isEqualTo(SearchScopeFilter.VIDEOS)
        assertThat(last14Days.inferredAfterMs)
            .isEqualTo(fixedNow.minusDays(14).atStartOfDay(zone).toInstant().toEpochMilli())

        val jan2025 = NaturalLanguageQueryParser.parseNaturalLanguage("tax january 2025", nowDate = fixedNow, zoneId = zone)
        assertThat(jan2025.inferredAfterMs)
            .isEqualTo(LocalDate.of(2025, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli())
        assertThat(jan2025.inferredBeforeMs)
            .isEqualTo(LocalDate.of(2025, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1L)
        assertThat(jan2025.cleanedTextQuery).isEqualTo("tax")
    }

    @Test
    fun `parses file types size bounds status sort and preserves bare years`() {
        val largePdf = NaturalLanguageQueryParser.parseNaturalLanguage("show me big pdf invoices", nowDate = fixedNow, zoneId = zone)
        assertThat(largePdf.inferredExtensions).containsExactly("pdf")
        assertThat(largePdf.inferredMinSizeBytes).isEqualTo(2L * 1024L * 1024L)
        assertThat(largePdf.cleanedTextQuery).isEqualTo("invoices")
        assertThat(largePdf.interpretedChips).containsAtLeast("PDF", "> 2 MB")

        val explicitSize = NaturalLanguageQueryParser.parseNaturalLanguage("videos over 20mb under 500mb", nowDate = fixedNow, zoneId = zone)
        assertThat(explicitSize.inferredScope).isEqualTo(SearchScopeFilter.VIDEOS)
        assertThat(explicitSize.inferredMinSizeBytes).isEqualTo(20L * 1024L * 1024L)
        assertThat(explicitSize.inferredMaxSizeBytes).isEqualTo(500L * 1024L * 1024L)

        val encryptedScreenshots = NaturalLanguageQueryParser.parseNaturalLanguage(
            "find encrypted screenshots starred offline newest",
            nowDate = fixedNow,
            zoneId = zone,
        )
        assertThat(encryptedScreenshots.requireEncrypted).isTrue()
        assertThat(encryptedScreenshots.requireStarred).isTrue()
        assertThat(encryptedScreenshots.requireOffline).isTrue()
        assertThat(encryptedScreenshots.inferredScope).isEqualTo(SearchScopeFilter.IMAGES)
        assertThat(encryptedScreenshots.inferredSort).isEqualTo(SearchSortOrder.NEWEST)
        assertThat(encryptedScreenshots.cleanedTextQuery).isEqualTo("screenshots")

        val bareYear = NaturalLanguageQueryParser.parseNaturalLanguage("Tax 2026", nowDate = fixedNow, zoneId = zone)
        assertThat(bareYear.inferredAfterMs).isNull()
        assertThat(bareYear.inferredBeforeMs).isNull()
        assertThat(bareYear.cleanedTextQuery).isEqualTo("tax 2026")
    }

    @Test
    fun `matches known folders without treating location queries as missing folder filters`() {
        val known = listOf("Work", "Camera Backup", "Receipts")

        val inWork = NaturalLanguageQueryParser.parseNaturalLanguage(
            "pdfs in Work",
            knownFolderNames = known,
            nowDate = fixedNow,
            zoneId = zone,
        )
        assertThat(inWork.inferredExtensions).containsExactly("pdf")
        assertThat(inWork.inferredFolderFilter).isEqualTo("Work")
        assertThat(inWork.cleanedTextQuery).isEmpty()

        val inParis = NaturalLanguageQueryParser.parseNaturalLanguage(
            "photos from Paris",
            knownFolderNames = known,
            nowDate = fixedNow,
            zoneId = zone,
        )
        assertThat(inParis.inferredScope).isEqualTo(SearchScopeFilter.IMAGES)
        assertThat(inParis.inferredFolderFilter).isNull()
        assertThat(inParis.cleanedTextQuery).isEqualTo("paris")
    }
}
