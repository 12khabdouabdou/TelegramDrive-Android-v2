package com.tgdrive.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SharedUtilsTest {

    @Test
    fun `computeInSampleSize returns powers of two fitting within targetPx`() {
        assertThat(BitmapUtils.computeInSampleSize(100, 100, 256)).isEqualTo(1)
        assertThat(BitmapUtils.computeInSampleSize(256, 256, 256)).isEqualTo(1)
        assertThat(BitmapUtils.computeInSampleSize(512, 256, 256)).isEqualTo(2)
        assertThat(BitmapUtils.computeInSampleSize(2000, 1000, 256)).isEqualTo(8)
        assertThat(BitmapUtils.computeInSampleSize(0, -10, 0)).isEqualTo(1)
    }

    @Test
    fun `sanitizeFileName strips traversal illegal chars and falls back when blank`() {
        assertThat(PublicDownloadsExporter.sanitizeFileName("../../secret/report:2026?.pdf"))
            .isEqualTo("report_2026_.pdf")
        assertThat(PublicDownloadsExporter.sanitizeFileName("C:\\temp\\notes*.txt "))
            .isEqualTo("notes_.txt")
        assertThat(PublicDownloadsExporter.sanitizeFileName(" ... ", fallback = "backup.bin"))
            .isEqualTo("backup.bin")
        assertThat(PublicDownloadsExporter.sanitizeFileName("a\u0000b|c<d>e\"f"))
            .isEqualTo("a_b_c_d_e_f")
    }

    @Test
    fun `resolveMimeType honors explicit non-generic MIME and PDF extension`() {
        assertThat(PublicDownloadsExporter.resolveMimeType("doc.PDF"))
            .isEqualTo("application/pdf")
        assertThat(PublicDownloadsExporter.resolveMimeType("doc.pdf", "*/*"))
            .isEqualTo("application/pdf")
        assertThat(PublicDownloadsExporter.resolveMimeType("image.bin", "image/webp"))
            .isEqualTo("image/webp")
        assertThat(PublicDownloadsExporter.resolveMimeType("blob.unknownext", "application/octet-stream"))
            .isEqualTo("application/octet-stream")
        assertThat(PublicDownloadsExporter.resolveMimeType("blob.unknownext", null))
            .isEqualTo("application/octet-stream")
    }
}
