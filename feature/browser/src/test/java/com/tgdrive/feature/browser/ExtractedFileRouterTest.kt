package com.tgdrive.feature.browser

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ExtractedFileRouterTest {
    @Test
    fun `image extensions route to in-app ImageViewer`() {
        listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "heif", "avif").forEach { ext ->
            val route = routeForExtractedFile("photo.$ext", "/tmp/photo.$ext")
            assertThat(route).isInstanceOf(ExtractedFileRoute.ImageView::class.java)
            assertThat((route as ExtractedFileRoute.ImageView).localPath).isEqualTo("/tmp/photo.$ext")
        }
    }

    @Test
    fun `pdf routes to External with application-pdf mime`() {
        val route = routeForExtractedFile("document.pdf", "/tmp/document.pdf")
        assertThat(route).isInstanceOf(ExtractedFileRoute.External::class.java)
        val ext = route as ExtractedFileRoute.External
        assertThat(ext.localPath).isEqualTo("/tmp/document.pdf")
        assertThat(ext.mimeType).isEqualTo("application/pdf")
    }

    @Test
    fun `video extensions route to External with video mime`() {
        val route = routeForExtractedFile("clip.mp4", "/tmp/clip.mp4") as ExtractedFileRoute.External
        assertThat(route.mimeType).isEqualTo("video/mp4")
    }

    @Test
    fun `audio extensions route to External with audio mime`() {
        val route = routeForExtractedFile("song.mp3", "/tmp/song.mp3") as ExtractedFileRoute.External
        assertThat(route.mimeType).isEqualTo("audio/mpeg")
    }

    @Test
    fun `unknown extensions route to External with wildcard mime`() {
        val route = routeForExtractedFile("data.xyz", "/tmp/data.xyz") as ExtractedFileRoute.External
        assertThat(route.mimeType).isEqualTo("*/*")
    }

    @Test
    fun `extension matching is case-insensitive`() {
        val route = routeForExtractedFile("PHOTO.JPG", "/tmp/PHOTO.JPG")
        assertThat(route).isInstanceOf(ExtractedFileRoute.ImageView::class.java)
    }
}
