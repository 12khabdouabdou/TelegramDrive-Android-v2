package com.tgdrive.feature.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfBookmarkCodecTest {

    @Test
    fun `encode sorts deduplicates and drops non-positive pages`() {
        val encoded = PdfBookmarkCodec.encode(setOf(12, 3, 12, 0, -4, 7))
        assertEquals("3,7,12", encoded)
    }

    @Test
    fun `decode parses valid pages and ignores malformed tokens`() {
        val decoded = PdfBookmarkCodec.decode("15, abc, -2, 0, 4, 15, 9 ")
        assertEquals(listOf(4, 9, 15), decoded.toList())
    }

    @Test
    fun `decode handles null and blank inputs cleanly`() {
        assertTrue(PdfBookmarkCodec.decode(null).isEmpty())
        assertTrue(PdfBookmarkCodec.decode("   ").isEmpty())
    }
}
