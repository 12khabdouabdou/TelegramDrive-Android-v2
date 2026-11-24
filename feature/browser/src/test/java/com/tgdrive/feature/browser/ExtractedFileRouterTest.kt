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
    fun `pdf routes to in-app PdfView`() {
        val route = routeForExtractedFile("document.pdf", "/tmp/document.pdf")
        assertThat(route).isInstanceOf(ExtractedFileRoute.PdfView::class.java)
        val pdf = route as ExtractedFileRoute.PdfView
        assertThat(pdf.localPath).isEqualTo("/tmp/document.pdf")
        assertThat(pdf.fileName).isEqualTo("document.pdf")
    }

    @Test
    fun `video extensions route to in-app VideoView`() {
        val route = routeForExtractedFile("clip.mp4", "/tmp/clip.mp4") as ExtractedFileRoute.VideoView
        assertThat(route.localPath).isEqualTo("/tmp/clip.mp4")
    }

    @Test
    fun `audio extensions route to in-app AudioPlay`() {
        val route = routeForExtractedFile("song.mp3", "/tmp/song.mp3") as ExtractedFileRoute.AudioPlay
        assertThat(route.localPath).isEqualTo("/tmp/song.mp3")
        assertThat(route.fileName).isEqualTo("song.mp3")
    }

    @Test
    fun `text extensions route to in-app TextView`() {
        listOf("notes.txt", "readme.md", "config.json", "Main.kt").forEach { name ->
            val route = routeForExtractedFile(name, "/tmp/$name") as ExtractedFileRoute.TextView
            assertThat(route.localPath).isEqualTo("/tmp/$name")
            assertThat(route.fileName).isEqualTo(name)
        }
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
