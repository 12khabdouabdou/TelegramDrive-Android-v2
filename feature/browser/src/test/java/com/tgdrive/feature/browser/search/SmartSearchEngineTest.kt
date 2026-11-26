package com.tgdrive.feature.browser.search

import com.google.common.truth.Truth.assertThat
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.ZipEntryEntity
import org.junit.jupiter.api.Test
import java.time.Instant

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

    @Test
    fun `cross-field fusion concepts stemming accents EXIF locations and audio tags work seamlessly`() {
        val mid2025Ms = Instant.parse("2025-07-15T12:00:00Z").toEpochMilli()
        val files = listOf(
            FileCacheEntity(
                messageId = 20L,
                chatId = 10L,
                fileName = "IMG_4410.jpg",
                fileSize = 3_000_000L,
                mimeType = "image/jpeg",
                thumbnailPath = null,
                date = mid2025Ms,
                ocrText = "Auto Garage Receipt Total 180",
                aiTags = "car, vehicle, loc:paris, loc:france",
            ),
            FileCacheEntity(
                messageId = 21L,
                chatId = 10L,
                fileName = "Résumé_Candidat.pdf",
                fileSize = 512_000L,
                mimeType = "application/pdf",
                thumbnailPath = null,
                date = mid2025Ms,
            ),
            FileCacheEntity(
                messageId = 22L,
                chatId = 10L,
                fileName = "Track04.mp3",
                fileSize = 4_200_000L,
                mimeType = "audio/mpeg",
                thumbnailPath = null,
                date = mid2025Ms,
                aiTags = "audio:daft punk, audio:discovery",
            ),
        )

        // Cross-field fusion: "car" (aiTags) + "receipt" (ocrText) + "2025" (date)
        val crossField = SmartSearchEngine.search("car receipt 2025", files, emptyList())
        assertThat(crossField.map { it.displayName }).containsExactly("IMG_4410.jpg")

        // French / concept synonym expansion: "voiture facture" -> car + receipt
        val frConcept = SmartSearchEngine.search("voiture facture", files, emptyList())
        assertThat(frConcept.map { it.displayName }).containsExactly("IMG_4410.jpg")

        // Location query: "photos from Paris" -> Scope IMAGES + loc:paris with Location hint
        val locationHits = SmartSearchEngine.search("photos from Paris", files, emptyList())
        val locHit = locationHits.single() as SmartSearchHit.CloudFileHit
        assertThat(locHit.entity.messageId).isEqualTo(20L)
        assertThat(locHit.matchHint).startsWith("Location:")

        // Diacritic folding + stemming: "resumes" matches "Résumé_Candidat.pdf"
        val resumeHits = SmartSearchEngine.search("resumes", files, emptyList())
        assertThat(resumeHits.map { it.displayName }).containsExactly("Résumé_Candidat.pdf")

        // Audio ID3 tag search: "songs daft punk"
        val audioHits = SmartSearchEngine.search("songs daft punk", files, emptyList())
        val audioHit = audioHits.single() as SmartSearchHit.CloudFileHit
        assertThat(audioHit.entity.messageId).isEqualTo(22L)
        assertThat(audioHit.matchHint).startsWith("Audio:")
    }

    @Test
    fun `encrypted PassVault tgenc files match effective types local AI tags and includeLocked toggle`() {
        val nowMs = System.currentTimeMillis()
        val files = listOf(
            FileCacheEntity(
                messageId = 30L,
                chatId = 500L,
                fileName = "Summer_Beach.jpg.tgenc",
                fileSize = 2_500_000L,
                mimeType = "application/octet-stream",
                thumbnailPath = "/cache/encrypted_thumbs/500_30.tgethumb",
                date = nowMs,
                isEncryptedVault = true,
                aiTags = "beach, ocean, loc:nice",
            ),
            FileCacheEntity(
                messageId = 31L,
                chatId = 500L,
                fileName = "Hidden_Passport_Scan.pdf.tgenc",
                fileSize = 4_000_000L,
                mimeType = "application/octet-stream",
                thumbnailPath = null,
                date = nowMs,
                isLocked = true,
                isEncryptedVault = true,
                ocrText = "PASSPORT REPUBLIQUE FRANCAISE",
            ),
        )

        // Effective bucket allows "encrypted photos" to match .jpg.tgenc (even with application/octet-stream MIME)
        val encPhotos = SmartSearchEngine.search("encrypted photos plage", files, emptyList())
        assertThat(encPhotos.map { it.displayName }).containsExactly("Summer_Beach.jpg.tgenc")

        // Global search (includeLocked = false) hides locked vault file even if OCR matches
        val globalPassport = SmartSearchEngine.search("passport", files, emptyList(), includeLocked = false)
        assertThat(globalPassport).isEmpty()

        // Unlocked Vault search (includeLocked = true) finds locked PDF via OCR and "encrypted pdfs" intent
        val vaultPassport = SmartSearchEngine.search("encrypted pdfs passport", files, emptyList(), includeLocked = true)
        assertThat(vaultPassport.map { it.displayName }).containsExactly("Hidden_Passport_Scan.pdf.tgenc")

        val parsedTags = SearchConcepts.parseAiTagParts("beach, loc:paris, audio:daft punk, ocean, loc:paris")
        assertThat(parsedTags.visualTags).containsExactly("beach", "ocean")
        assertThat(parsedTags.locations).containsExactly("paris")
        assertThat(parsedTags.audioMeta).containsExactly("daft punk")
    }
}
