package com.tgdrive.feature.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for [ZipThumbnailGenerator]. Needs Robolectric because
 * [BitmapFactory.decodeStream] is an Android API.
 *
 * Generates a real in-memory ZIP with a small bitmap entry as a fixture
 * — no external test resources required.
 *
 * Uses JUnit4 (not JUnit5) because Robolectric requires `@RunWith`
 * which is a JUnit4 annotation. The project's `junit5-vintage-engine`
 * dependency (added to `:feature:browser` for this test) bridges
 * JUnit4 tests onto the JUnit5 platform.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZipThumbnailGeneratorTest {
    private lateinit var tempDir: File
    private lateinit var zipFile: File
    private lateinit var generator: ZipThumbnailGenerator

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("ziptest").toFile()
        zipFile = File(tempDir, "test.zip")
        generator = ZipThumbnailGenerator()
    }

    @After
    fun tearDown() {
        generator.clear()
        tempDir.deleteRecursively()
    }

    @Test
    fun `non-image extensions return null without touching the zip`() {
        // Don't even create the zip — generator should short-circuit
        // before any file I/O.
        val result = generator.getThumbnail("/nonexistent.zip", "readme.txt")
        assertThat(result).isNull()
    }

    @Test
    fun `directory entries return null`() {
        val result = generator.getThumbnail("/nonexistent.zip", "subfolder/")
        assertThat(result).isNull()
    }

    @Test
    fun `image entry in real zip returns a bitmap`() {
        val bmp = createSolidColorBitmap(64, 64, 0xFF0000FF.toInt())
        writeZip(zipFile, "photo.png" to bmp)

        val result = generator.getThumbnail(zipFile.absolutePath, "photo.png")
        assertThat(result).isNotNull()
        assertThat(result!!.width).isAtMost(200)
        assertThat(result.height).isAtMost(200)
    }

    @Test
    fun `large image is downsampled to target size`() {
        val bmp = createSolidColorBitmap(2048, 2048, 0xFF00FF00.toInt())
        writeZip(zipFile, "big.jpg" to bmp)

        val result = generator.getThumbnail(zipFile.absolutePath, "big.jpg")
        assertThat(result).isNotNull()
        // Sample size is a power of 2; with a 2048-source + 200 target, the
        // generator returns sample=16 → 128x128 thumbnail. Allow generous
        // slack in case Robolectric decodes at a slightly different size.
        assertThat(result!!.width).isAtMost(256)
        assertThat(result.height).isAtMost(256)
    }

    @Test
    fun `cached call returns the same bitmap instance`() {
        val bmp = createSolidColorBitmap(64, 64, 0xFFFF0000.toInt())
        writeZip(zipFile, "cached.png" to bmp)

        val first = generator.getThumbnail(zipFile.absolutePath, "cached.png")
        val second = generator.getThumbnail(zipFile.absolutePath, "cached.png")

        assertThat(first).isNotNull()
        // Same instance — proves the cache was hit (no re-decode).
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun `missing zip file returns null`() {
        val result = generator.getThumbnail("/definitely/not/here.zip", "image.png")
        assertThat(result).isNull()
    }

    @Test
    fun `missing entry in zip returns null`() {
        val bmp = createSolidColorBitmap(32, 32, 0xFFFFFFFF.toInt())
        writeZip(zipFile, "real.png" to bmp)

        val result = generator.getThumbnail(zipFile.absolutePath, "missing.png")
        assertThat(result).isNull()
    }

    @Test
    fun `clear drops the cache`() {
        val bmp = createSolidColorBitmap(64, 64, 0xFF0000FF.toInt())
        writeZip(zipFile, "p.png" to bmp)

        val first = generator.getThumbnail(zipFile.absolutePath, "p.png")
        generator.clear()
        val second = generator.getThumbnail(zipFile.absolutePath, "p.png")

        assertThat(first).isNotNull()
        // After clear, must be a fresh decode → different instance.
        assertThat(second).isNotNull()
        assertThat(second).isNotSameInstanceAs(first)
    }

    // ─────────────────────── video thumbnails ───────────────────────

    @Test
    fun `video entry returns bitmap via injected video frame extractor`() {
        val expectedBitmap = createSolidColorBitmap(64, 64, 0xFF00FF00.toInt())
        val fakeExtractor = VideoFrameExtractor { _ -> expectedBitmap }
        val gen = ZipThumbnailGenerator(videoFrameExtractor = fakeExtractor)

        // Write a zip with a fake "video.mp4" entry — content doesn't matter
        // because the fake extractor returns a fixed bitmap regardless.
        writeRawZip(zipFile, "clip.mp4" to "fake video bytes".toByteArray())

        val result = gen.getThumbnail(zipFile.absolutePath, "clip.mp4")
        assertThat(result).isSameInstanceAs(expectedBitmap)
    }

    @Test
    fun `video frame extractor is not called for non-video extensions`() {
        var extractorCalled = false
        val fakeExtractor =
            VideoFrameExtractor { _ ->
                extractorCalled = true;
                null
            }
        val gen = ZipThumbnailGenerator(videoFrameExtractor = fakeExtractor)

        // Non-video file → extractor should not be invoked
        gen.getThumbnail("/nonexistent.zip", "photo.jpg")
        assertThat(extractorCalled).isFalse()
    }

    @Test
    fun `video entry in missing zip returns null`() {
        val fakeExtractor = VideoFrameExtractor { _ -> createSolidColorBitmap(64, 64, 0xFF0000FF.toInt()) }
        val gen = ZipThumbnailGenerator(videoFrameExtractor = fakeExtractor)

        val result = gen.getThumbnail("/nonexistent.zip", "missing.mp4")
        assertThat(result).isNull()
    }

    @Test
    fun `video entry missing in zip returns null`() {
        val fakeExtractor = VideoFrameExtractor { _ -> createSolidColorBitmap(64, 64, 0xFF0000FF.toInt()) }
        val gen = ZipThumbnailGenerator(videoFrameExtractor = fakeExtractor)

        // Real zip but no matching entry — extractor must not be called
        writeRawZip(zipFile, "real.mp4" to "bytes".toByteArray())
        val result = gen.getThumbnail(zipFile.absolutePath, "missing.mp4")
        assertThat(result).isNull()
    }

    @Test
    fun `video entry where extractor returns null yields cached null`() {
        val fakeExtractor = VideoFrameExtractor { _ -> null }
        val gen = ZipThumbnailGenerator(videoFrameExtractor = fakeExtractor)
        writeRawZip(zipFile, "clip.mp4" to "bytes".toByteArray())

        val result = gen.getThumbnail(zipFile.absolutePath, "clip.mp4")
        assertThat(result).isNull()
    }

    @Test
    fun `aes256 encrypted zip entry returns null without password and bitmap with password`() {
        val bmp = createSolidColorBitmap(64, 64, 0xFF112233.toInt())
        val pngFile = File(tempDir, "secret.png")
        FileOutputStream(pngFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val encryptedZip = File(tempDir, "encrypted.zip")
        val params = net.lingala.zip4j.model.ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
            aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
            fileNameInZip = "nested/secret.png"
        }
        net.lingala.zip4j.ZipFile(encryptedZip, "secret-pass".toCharArray()).use { zf ->
            zf.addFile(pngFile, params)
        }

        assertThat(generator.getThumbnail(encryptedZip.absolutePath, "nested/secret.png")).isNull()
        val unlocked = generator.getThumbnail(encryptedZip.absolutePath, "nested/secret.png", "secret-pass")
        assertThat(unlocked).isNotNull()
    }

    @Test
    fun `zip4j outputstream entry with data descriptor decodes thumbnail`() {
        val bmp = createSolidColorBitmap(120, 90, 0xFFAA4400.toInt())
        val pngBytes = java.io.ByteArrayOutputStream().apply {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()

        val streamedZip = File(tempDir, "zip4j_streamed.zip")
        net.lingala.zip4j.io.outputstream.ZipOutputStream(FileOutputStream(streamedZip)).use { zos ->
            val p = net.lingala.zip4j.model.ZipParameters().apply {
                fileNameInZip = "photos/snap.png"
                isEncryptFiles = false
            }
            zos.putNextEntry(p)
            zos.write(pngBytes)
            zos.closeEntry()
        }

        val thumb = generator.getThumbnail(streamedZip.absolutePath, "photos/snap.png")
        assertThat(thumb).isNotNull()
    }

    @Test
    fun `video thumbnail is cached like image thumbnails`() {
        val expectedBitmap = createSolidColorBitmap(64, 64, 0xFF00FF00.toInt())
        var extractorCallCount = 0
        val fakeExtractor =
            VideoFrameExtractor { _ ->
                extractorCallCount++
                expectedBitmap
            }
        val gen = ZipThumbnailGenerator(videoFrameExtractor = fakeExtractor)
        writeRawZip(zipFile, "clip.mp4" to "bytes".toByteArray())

        val first = gen.getThumbnail(zipFile.absolutePath, "clip.mp4")
        val second = gen.getThumbnail(zipFile.absolutePath, "clip.mp4")

        assertThat(first).isSameInstanceAs(expectedBitmap)
        assertThat(second).isSameInstanceAs(expectedBitmap)
        // Cache hit → extractor only called once
        assertThat(extractorCallCount).isEqualTo(1)
    }

    @Test
    fun `temp video file is deleted after frame extraction`() {
        val expectedBitmap = createSolidColorBitmap(64, 64, 0xFF00FF00.toInt())
        // Capture the temp file path when the extractor is called, then
        // verify it no longer exists after getThumbnail returns.
        var capturedTempFile: File? = null
        val fakeExtractor =
            VideoFrameExtractor { f ->
                capturedTempFile = f
                expectedBitmap
            }
        val gen = ZipThumbnailGenerator(videoFrameExtractor = fakeExtractor)
        writeRawZip(zipFile, "clip.mp4" to "bytes".toByteArray())

        gen.getThumbnail(zipFile.absolutePath, "clip.mp4")

        assertThat(capturedTempFile).isNotNull()
        // Generator must clean up the temp file — leaving them around
        // would leak cache space over a long browsing session.
        assertThat(capturedTempFile!!.exists()).isFalse()
    }

    @Test
    fun `temp video file uses explicit cacheDir and cleans up`() {
        val customCache = File(tempDir, "custom_cache")
        val expectedBitmap = createSolidColorBitmap(64, 64, 0xFF00FF00.toInt())
        var capturedTempFile: File? = null
        val gen = ZipThumbnailGenerator(
            videoFrameExtractor = { f ->
                capturedTempFile = f
                expectedBitmap
            },
            cacheDir = customCache,
        )
        writeRawZip(zipFile, "clip.mp4" to "bytes".toByteArray())

        val result = gen.getThumbnail(zipFile.absolutePath, "clip.mp4")

        assertThat(result).isSameInstanceAs(expectedBitmap)
        assertThat(capturedTempFile).isNotNull()
        assertThat(capturedTempFile!!.parentFile).isEqualTo(customCache)
        assertThat(capturedTempFile!!.exists()).isFalse()
    }

    @Test
    fun `invalid cacheDir returns null safely instead of throwing`() {
        val blockerFile = File(tempDir, "not_a_dir").apply { writeText("x") }
        val gen = ZipThumbnailGenerator(
            videoFrameExtractor = { _ -> createSolidColorBitmap(64, 64, 0xFF00FF00.toInt()) },
            cacheDir = blockerFile,
        )
        writeRawZip(zipFile, "clip.mp4" to "bytes".toByteArray())

        val result = gen.getThumbnail(zipFile.absolutePath, "clip.mp4")
        assertThat(result).isNull()
    }

    // ─────────────────────── PDF, head-peek video, collage, disk cache ──

    @Test
    fun `pdf entry renders via injected PdfPageRenderer and cleans up temp file`() {
        val expectedBitmap = createSolidColorBitmap(64, 84, 0xFFEEEEEE.toInt())
        var capturedPdfFile: File? = null
        val gen = ZipThumbnailGenerator(
            pdfPageRenderer = { f, _ ->
                capturedPdfFile = f
                expectedBitmap
            },
        )
        writeRawZip(zipFile, "docs/invoice.pdf" to "%PDF-1.4\n%%EOF".toByteArray())

        val result = gen.getThumbnail(zipFile.absolutePath, "docs/invoice.pdf")
        assertThat(result).isSameInstanceAs(expectedBitmap)
        assertThat(capturedPdfFile).isNotNull()
        assertThat(capturedPdfFile!!.exists()).isFalse()
    }

    @Test
    fun `folder collage composes square thumbnail from subfolder entries`() {
        val b1 = createSolidColorBitmap(40, 40, 0xFFFF0000.toInt())
        val b2 = createSolidColorBitmap(40, 40, 0xFF00FF00.toInt())
        val b3 = createSolidColorBitmap(40, 40, 0xFF0000FF.toInt())
        val b4 = createSolidColorBitmap(40, 40, 0xFFFFFF00.toInt())
        writeZip(
            zipFile,
            "trip/a.png" to b1,
            "trip/b.png" to b2,
            "trip/c.png" to b3,
            "trip/d.png" to b4,
        )

        val collage = generator.getFolderCollage(
            zipPath = zipFile.absolutePath,
            folderPrefix = "trip/",
            entryNames = listOf("trip/a.png", "trip/b.png", "trip/c.png", "trip/d.png"),
        )
        assertThat(collage).isNotNull()
        assertThat(collage!!.width).isEqualTo(200)
        assertThat(collage.height).isEqualTo(200)
    }

    @Test
    fun `oversized video selects 4MB head peek limit instead of skipping`() {
        assertThat(generator.selectVideoReadLimitBytes(10L * 1024 * 1024)).isEqualTo(50L * 1024 * 1024)
        assertThat(generator.selectVideoReadLimitBytes(120L * 1024 * 1024)).isEqualTo(ZipThumbnailGenerator.VIDEO_HEAD_PEEK_BYTES)
    }

    @Test
    fun `disk cache persists plain jpg thumbnails across clear`() {
        val diskDir = File(tempDir, "disk_thumbs")
        val gen = ZipThumbnailGenerator(diskCacheDir = diskDir)
        val bmp = createSolidColorBitmap(64, 64, 0xFF336699.toInt())
        writeZip(zipFile, "persist.png" to bmp)

        val first = gen.getThumbnail(zipFile.absolutePath, "persist.png")
        assertThat(first).isNotNull()
        val cachedFiles = diskDir.listFiles().orEmpty()
        assertThat(cachedFiles.any { it.name.endsWith(".jpg") && it.length() > 0L }).isTrue()

        gen.clear()
        val origMtime = zipFile.lastModified()
        zipFile.writeBytes(byteArrayOf(1, 2, 3))
        zipFile.setLastModified(origMtime)

        val fromDisk = gen.getThumbnail(zipFile.absolutePath, "persist.png")
        assertThat(fromDisk).isNotNull()
    }

    // ───────────────────────────────────────────────── test helpers ──

    private fun createSolidColorBitmap(
        w: Int,
        h: Int,
        color: Int,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    private fun writeZip(
        target: File,
        vararg entries: Pair<String, Bitmap>,
    ) {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            entries.forEach { (name, bitmap) ->
                zip.putNextEntry(ZipEntry(name))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }
        }
    }

    /** Write a zip with raw byte entries (for video tests — content doesn't matter). */
    private fun writeRawZip(
        target: File,
        vararg entries: Pair<String, ByteArray>,
    ) {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
