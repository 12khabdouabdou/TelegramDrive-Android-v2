package com.tgdrive.feature.player

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PdfValidationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `isLikelyPdfFile returns true for standard pdf header`() {
        val pdf = File(tempDir, "valid.pdf")
        pdf.writeBytes("%PDF-1.7\n1 0 obj\n".toByteArray(Charsets.ISO_8859_1))
        assertTrue(isLikelyPdfFile(pdf))
    }

    @Test
    fun `isLikelyPdfFile returns true when header follows leading bom or whitespace`() {
        val pdf = File(tempDir, "bom.pdf")
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "  %PDF-1.4\n".toByteArray(Charsets.ISO_8859_1)
        pdf.writeBytes(bytes)
        assertTrue(isLikelyPdfFile(pdf))
    }

    @Test
    fun `isLikelyPdfFile rejects empty or non-pdf content`() {
        val empty = File(tempDir, "empty.pdf").apply { writeBytes(byteArrayOf()) }
        val html = File(tempDir, "html.pdf").apply { writeText("<html>error</html>") }
        val missing = File(tempDir, "missing.pdf")

        assertFalse(isLikelyPdfFile(empty))
        assertFalse(isLikelyPdfFile(html))
        assertFalse(isLikelyPdfFile(missing))
    }
}
