package com.tgdrive.core.download

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PdfAndDownloadHelperTest {

    @Test
    fun `computePdfThumbDimensions preserves portrait aspect ratio`() {
        val (w, h) = MediaThumbnailCache.computePdfThumbDimensions(600, 1200, maxDim = 360)
        assertThat(h).isEqualTo(360)
        assertThat(w).isEqualTo(180)
    }

    @Test
    fun `computePdfThumbDimensions preserves landscape aspect ratio`() {
        val (w, h) = MediaThumbnailCache.computePdfThumbDimensions(1200, 600, maxDim = 360)
        assertThat(w).isEqualTo(360)
        assertThat(h).isEqualTo(180)
    }

    @Test
    fun `computePdfThumbDimensions guards non-positive dimensions`() {
        val (w, h) = MediaThumbnailCache.computePdfThumbDimensions(0, -10, maxDim = 360)
        assertThat(w).isEqualTo(360)
        assertThat(h).isEqualTo(360)
    }

    @Test
    fun `sanitizeDownloadFileName strips unsafe path characters and preserves pdf extension`() {
        assertThat(DownloadManager.sanitizeDownloadFileName("docs/2026:Q1*report?.pdf"))
            .isEqualTo("2026_Q1_report_.pdf")
        assertThat(DownloadManager.sanitizeDownloadFileName("   ...   "))
            .isEqualTo("download")
        assertThat(DownloadManager.sanitizeDownloadFileName("Lecture Notes (Final).pdf"))
            .isEqualTo("Lecture Notes (Final).pdf")
    }
}
