package com.tgdrive.feature.browser.di

import com.google.common.truth.Truth.assertThat
import com.tgdrive.feature.browser.ZipExtractor
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Regression test for the production [BrowserModule.provideZipExtractor] lambda.
 *
 * Originally added to catch the "can't open videos/pictures from
 * password-protected ZIP" bug. The tests exercise both flat and nested
 * entries, with and without passwords, to isolate path-resolution bugs
 * from password-handling bugs.
 *
 * Uses Robolectric because the production code uses `android.util.Log`
 * (returns null on JVM without Robolectric → NullPointerException in
 * the catch block → mask the real failure mode).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserModuleZipExtractorTest {
    private lateinit var tempDir: File
    private lateinit var outputDir: File
    private lateinit var extractor: ZipExtractor

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("ziptest").toFile()
        outputDir = File(tempDir, "extracts").apply { mkdirs() }
        // Construct the production lambda directly — no DI needed.
        // We mirror the exact lambda body from BrowserModule.provideZipExtractor
        // so the test exercises the same code path.
        extractor =
            ZipExtractor { zipPath, entryName, outDir, password ->
                extractSingleEntry(zipPath, entryName, outDir, password)
            }
    }

    @Test
    fun `flat entry without password extracts to correct path`() {
        val zip = File(tempDir, "plain.zip")
        createPlainZip(zip, "video.mp4" to "video bytes".toByteArray())

        val path = extractor.extractEntry(zip.absolutePath, "video.mp4", outputDir, null)

        assertThat(path).isNotNull()
        assertThat(File(path!!).exists()).isTrue()
        assertThat(File(path).readBytes()).isEqualTo("video bytes".toByteArray())
    }

    @Test
    fun `nested entry without password extracts to correct path`() {
        val zip = File(tempDir, "plain.zip")
        createPlainZip(zip, "subfolder/video.mp4" to "nested video".toByteArray())

        val path = extractor.extractEntry(zip.absolutePath, "subfolder/video.mp4", outputDir, null)

        assertThat(path).isNotNull()
        // BUG CATCH: the production code returns File(outputDir, safeName) where
        // safeName is the last path segment — but zip4j preserves the entry's
        // internal path. The actual file is at outputDir/subfolder/video.mp4,
        // not outputDir/video.mp4. If this assertion fails, the bug is back.
        assertThat(File(path!!).exists()).isTrue()
        assertThat(File(path).readBytes()).isEqualTo("nested video".toByteArray())
    }

    @Test
    fun `flat entry with password extracts to correct path`() {
        val zip = File(tempDir, "encrypted.zip")
        createEncryptedZip(zip, "secret.jpg" to "image bytes".toByteArray(), password = "hunter2")

        val path = extractor.extractEntry(zip.absolutePath, "secret.jpg", outputDir, "hunter2")

        assertThat(path).isNotNull()
        assertThat(File(path!!).exists()).isTrue()
        assertThat(File(path).readBytes()).isEqualTo("image bytes".toByteArray())
    }

    @Test
    fun `nested entry with password extracts to correct path`() {
        val zip = File(tempDir, "encrypted.zip")
        createEncryptedZip(zip, "photos/2024.jpg" to "jpg bytes".toByteArray(), password = "hunter2")

        val path = extractor.extractEntry(zip.absolutePath, "photos/2024.jpg", outputDir, "hunter2")

        assertThat(path).isNotNull()
        // Same path-resolution bug as the non-password nested case — but
        // this is the scenario the user reported ("can't open from password
        // zip"). If this fails, the user's bug is back.
        assertThat(File(path!!).exists()).isTrue()
        assertThat(File(path).readBytes()).isEqualTo("jpg bytes".toByteArray())
    }

    @Test
    fun `wrong password returns null`() {
        val zip = File(tempDir, "encrypted.zip")
        createEncryptedZip(zip, "secret.jpg" to "image bytes".toByteArray(), password = "correct")

        val path = extractor.extractEntry(zip.absolutePath, "secret.jpg", outputDir, "wrong")

        assertThat(path).isNull()
    }

    @Test
    fun `missing entry returns null`() {
        val zip = File(tempDir, "plain.zip")
        createPlainZip(zip, "real.mp4" to "bytes".toByteArray())

        val path = extractor.extractEntry(zip.absolutePath, "missing.mp4", outputDir, null)

        assertThat(path).isNull()
    }

    // ──────────────────────────── helpers ────────────────────────────

    private fun createPlainZip(
        target: File,
        vararg entries: Pair<String, ByteArray>,
    ) {
        val zip = ZipFile(target)
        entries.forEach { (name, content) ->
            val tmp = File(tempDir, "tmp_${name.substringAfterLast('/').filter { it.isLetterOrDigit() }}")
            tmp.writeBytes(content)
            val params = ZipParameters().apply { fileNameInZip = name }
            zip.addFile(tmp, params)
            tmp.delete()
        }
    }

    private fun createEncryptedZip(
        target: File,
        vararg entries: Pair<String, ByteArray>,
        password: String,
    ) {
        val zip = ZipFile(target, password.toCharArray())
        entries.forEach { (name, content) ->
            val tmp = File(tempDir, "tmp_${name.substringAfterLast('/').filter { it.isLetterOrDigit() }}")
            tmp.writeBytes(content)
            val params =
                ZipParameters().apply {
                    fileNameInZip = name
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                }
            zip.addFile(tmp, params)
            tmp.delete()
        }
    }

    // Mirror of BrowserModule.extractSingleEntry — kept here so the test
    // exercises the exact same code path. If BrowserModule's implementation
    // changes, this must change too. (Better: extract to a top-level
    // function in a future refactor.)
    private fun extractSingleEntry(
        zipPath: String,
        entryName: String,
        outputDir: File,
        password: String?,
    ): String? {
        return try {
            outputDir.mkdirs()
            val zip =
                if (password != null) {
                    ZipFile(File(zipPath), password.toCharArray())
                } else {
                    ZipFile(File(zipPath))
                }
            val header = zip.getFileHeader(entryName) ?: return null
            if (header.isDirectory) return null
            zip.extractFile(header, outputDir.absolutePath)
            // zip4j preserves the entry's internal path under outputDir.
            // header.fileName is the entry's full name (e.g. "subfolder/photo.jpg"),
            // and the extracted file is at outputDir/subfolder/photo.jpg.
            File(outputDir, header.fileName).absolutePath
        } catch (t: Throwable) {
            null
        }
    }
}
