package com.tgdrive.core.zip

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.enums.CompressionMethod
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Random

class ZipUploaderChunkedTest {

    private fun randomBytes(size: Int, seed: Long): ByteArray {
        val out = ByteArray(size)
        Random(seed).nextBytes(out)
        return out
    }

    @Test
    fun `multiple incompressible files split into standalone encrypted parts without exceeding limit`(@TempDir tmp: File) = runTest {
        val workDir = File(tmp, "work")
        val receivedDir = File(tmp, "received").apply { mkdirs() }
        val limitBytes = 140 * 1024L

        val files = listOf(
            "Folder/a.bin" to randomBytes(70 * 1024, 1L),
            "Folder/b.bin" to randomBytes(70 * 1024, 2L),
            "Folder/sub/c.bin" to randomBytes(70 * 1024, 3L),
        )
        val entries = files.map { (path, data) ->
            ZipSourceEntry(
                relativePath = path,
                sizeBytes = data.size.toLong(),
                openStream = { ByteArrayInputStream(data) },
            )
        }

        val capturedParts = mutableListOf<File>()
        val capturedEntriesPerPart = mutableListOf<List<String>>()
        val summary = ZipUploader.zipSourcesInParts(
            entries = entries,
            workDir = workDir,
            baseZipName = "Photos",
            maxPartBytes = limitBytes,
            password = "secret-password",
            checkFreeSpace = false,
        ) { part ->
            assertThat(part.partFile.exists()).isTrue()
            assertThat(part.partFile.length()).isAtMost(limitBytes)
            assertThat(part.isSplitVolume).isFalse()
            // Simulate uploading immediately by copying the standalone part aside
            val copy = File(receivedDir, part.partFile.name)
            part.partFile.copyTo(copy, overwrite = true)
            capturedParts.add(copy)
            capturedEntriesPerPart.add(part.entries)
        }

        assertThat(summary.totalFilesProcessed).isEqualTo(3)
        assertThat(summary.hadSplitVolumes).isFalse()
        assertThat(capturedParts.size).isEqualTo(3)
        assertThat(summary.totalPartsEmitted).isEqualTo(3)
        // Work directory should have deleted each transient part right after onPartReady returned
        assertThat(workDir.listFiles().orEmpty().filter { it.isFile }).isEmpty()

        // Every standalone part must extract independently with password
        capturedParts.forEachIndexed { idx, partZip ->
            val extractDir = File(tmp, "extract_$idx")
            val extracted = ZipUploader.extractZip(partZip.absolutePath, extractDir, password = "secret-password")
            assertThat(extracted).isNotNull()
            assertThat(extracted!!.size).isEqualTo(capturedEntriesPerPart[idx].size)
        }
    }

    @Test
    fun `single oversized file falls back to Zip4j split volumes that merge cleanly`(@TempDir tmp: File) = runTest {
        val workDir = File(tmp, "work")
        val splitStorage = File(tmp, "split_store").apply { mkdirs() }
        val limitBytes = 90 * 1024L
        val payload = randomBytes(190 * 1024, 42L)

        val entries = listOf(
            ZipSourceEntry(
                relativePath = "Videos/big_clip.bin",
                sizeBytes = payload.size.toLong(),
                openStream = { ByteArrayInputStream(payload) },
            ),
        )

        val storedVolumes = mutableListOf<File>()
        val summary = ZipUploader.zipSourcesInParts(
            entries = entries,
            workDir = workDir,
            baseZipName = "Videos",
            maxPartBytes = limitBytes,
            password = null,
            checkFreeSpace = false,
        ) { part ->
            assertThat(part.isSplitVolume).isTrue()
            assertThat(part.partFile.length()).isAtMost(limitBytes)
            val saved = File(splitStorage, part.partFile.name)
            part.partFile.copyTo(saved, overwrite = true)
            storedVolumes.add(saved)
        }

        assertThat(summary.hadSplitVolumes).isTrue()
        assertThat(summary.totalFilesProcessed).isEqualTo(1)
        assertThat(storedVolumes.size).isAtLeast(2)
        val finalZip = storedVolumes.last { it.extension == "zip" }
        val mergedZip = File(tmp, "merged.zip")
        val mergedOk = ZipUploader.mergeSplitZip(finalZip, mergedZip)
        assertThat(mergedOk).isTrue()

        val extracted = ZipUploader.extractZip(mergedZip.absolutePath, File(tmp, "merged_out"))
        assertThat(extracted).isNotNull()
        assertThat(extracted!!).hasSize(1)
        assertThat(File(extracted.first()).readBytes()).isEqualTo(payload)
    }

    @Test
    fun `manifest json lists all uploaded part filenames and entries`(@TempDir tmp: File) {
        val manifestFile = ZipUploader.writeManifestJsonFile(
            outputDir = tmp,
            baseName = "Camera_2026",
            folderName = "Camera",
            encrypted = true,
            parts = listOf(
                UploadedZipPartRecord("Camera_part001.zip", 1024L, 1, false, 100L, listOf("Camera/a.jpg")),
                UploadedZipPartRecord("Camera_part002.zip", 512L, 2, false, 101L, listOf("Camera/b.jpg")),
            ),
        )
        val text = manifestFile.readText()
        assertThat(text).contains("\"totalParts\": 2")
        assertThat(text).contains("\"totalFiles\": 2")
        assertThat(text).contains("\"Camera_part001.zip\"")
        assertThat(text).contains("\"Camera/b.jpg\"")
    }

    @Test
    fun `remote central directory tail parser reads plain and encrypted entries`(@TempDir tmp: File) = runTest {
        val workDir = File(tmp, "work")
        val files = listOf(
            "Docs/Tax_2026.pdf" to randomBytes(12 * 1024, 11L),
            "Photos/Paris_Trip.jpg" to randomBytes(18 * 1024, 22L),
        )
        val entries = files.map { (path, bytes) ->
            ZipSourceEntry(path, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        }
        lateinit var builtZip: File
        ZipUploader.zipSourcesInParts(
            entries = entries,
            workDir = workDir,
            baseZipName = "TailTest",
            maxPartBytes = 256 * 1024L,
            password = "pass",
            checkFreeSpace = false,
        ) { part ->
            builtZip = File(tmp, part.partFile.name)
            part.partFile.copyTo(builtZip, overwrite = true)
        }

        val allBytes = builtZip.readBytes()
        val tailLen = minOf(allBytes.size, 8192)
        val tailOffset = (allBytes.size - tailLen).toLong()
        val tail = allBytes.copyOfRange(tailOffset.toInt(), allBytes.size)

        val parsed = ZipUploader.parseCentralDirectoryFromTail(tail, tailOffset)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.map { it.name }).containsExactly("Docs/Tax_2026.pdf", "Photos/Paris_Trip.jpg")
        assertThat(parsed.all { it.isEncrypted }).isTrue()
    }

    @Test
    fun `readFirstImageEntryBytes returns null cleanly when zip has no images`(@TempDir tmp: File) = runTest {
        val workDir = File(tmp, "work")
        val data = randomBytes(4096, 99L)
        lateinit var builtZip: File
        ZipUploader.zipSourcesInParts(
            entries = listOf(ZipSourceEntry("notes/readme.txt", data.size.toLong()) { ByteArrayInputStream(data) }),
            workDir = workDir,
            baseZipName = "TextOnly",
            maxPartBytes = 256 * 1024L,
            password = null,
            checkFreeSpace = false,
        ) { part ->
            builtZip = File(tmp, part.partFile.name)
            part.partFile.copyTo(builtZip, overwrite = true)
        }
        assertThat(ZipUploader.readFirstImageEntryBytes(builtZip.absolutePath)).isNull()
    }

    @Test
    fun `smart compression uses STORE for media and DEFLATE for text while STORE stores all`(@TempDir tmp: File) = runTest {
        assertThat(ZipUploader.resolveCompressionMethod("photo.JPG", ZipCompressionMode.SMART))
            .isEqualTo(CompressionMethod.STORE)
        assertThat(ZipUploader.resolveCompressionMethod("movie.mp4", ZipCompressionMode.SMART))
            .isEqualTo(CompressionMethod.STORE)
        assertThat(ZipUploader.resolveCompressionMethod("notes.txt", ZipCompressionMode.SMART))
            .isEqualTo(CompressionMethod.DEFLATE)
        assertThat(ZipUploader.resolveCompressionMethod("notes.txt", ZipCompressionMode.STORE))
            .isEqualTo(CompressionMethod.STORE)
        assertThat(ZipUploader.resolveCompressionMethod("photo.jpg", ZipCompressionMode.DEFLATE))
            .isEqualTo(CompressionMethod.DEFLATE)

        val entries = listOf(
            ZipSourceEntry("pic.jpg", 4096L) { ByteArrayInputStream(randomBytes(4096, 5L)) },
            ZipSourceEntry("log.txt", 4096L) { ByteArrayInputStream("hello ".repeat(600).toByteArray()) },
        )
        lateinit var builtZip: File
        ZipUploader.zipSourcesInParts(
            entries = entries,
            workDir = File(tmp, "work"),
            baseZipName = "Smart",
            maxPartBytes = 512 * 1024L,
            checkFreeSpace = false,
            compressionMode = ZipCompressionMode.SMART,
            enablePipeline = false,
        ) { part ->
            builtZip = File(tmp, part.partFile.name)
            part.partFile.copyTo(builtZip, overwrite = true)
        }

        val headers = ZipFile(builtZip).use { it.fileHeaders.associateBy { h -> h.fileName } }
        assertThat(headers["pic.jpg"]?.compressionMethod).isEqualTo(CompressionMethod.STORE)
        assertThat(headers["log.txt"]?.compressionMethod).isEqualTo(CompressionMethod.DEFLATE)
    }

    @Test
    fun `pipelined packing emits progress and cleans temp files if consumer fails`(@TempDir tmp: File) = runTest {
        val workDir = File(tmp, "work_pipe")
        val progressEvents = mutableListOf<ZipProgress>()
        val entries = (1..4).map { idx ->
            val data = randomBytes(70 * 1024, idx.toLong())
            ZipSourceEntry("file_$idx.bin", data.size.toLong()) { ByteArrayInputStream(data) }
        }

        var count = 0
        ZipUploader.zipSourcesInParts(
            entries = entries,
            workDir = workDir,
            baseZipName = "PipeOk",
            maxPartBytes = 130 * 1024L,
            checkFreeSpace = false,
            enablePipeline = true,
            onZipProgress = { progressEvents.add(it) },
        ) { _ ->
            count++
        }
        assertThat(count).isGreaterThan(1)
        assertThat(progressEvents).isNotEmpty()
        assertThat(workDir.listFiles().orEmpty().filter { it.isFile }).isEmpty()

        val workFailDir = File(tmp, "work_fail")
        assertThrows<IllegalStateException> {
            var seen = 0
            ZipUploader.zipSourcesInParts(
                entries = entries,
                workDir = workFailDir,
                baseZipName = "PipeFail",
                maxPartBytes = 130 * 1024L,
                checkFreeSpace = false,
                enablePipeline = true,
            ) {
                seen++
                if (seen == 1) throw IllegalStateException("simulated upload failure")
            }
        }
        assertThat(workFailDir.listFiles().orEmpty().filter { it.isFile }).isEmpty()
    }

    @Test
    fun `zipSingleFileSplit merges and extracts back identically with encryption`(@TempDir tmp: File) = runTest {
        val source = File(tmp, "large.bin")
        val payload = randomBytes(210 * 1024, 77L)
        source.writeBytes(payload)

        val stored = mutableListOf<File>()
        val splitSaveDir = File(tmp, "saved_splits").apply { mkdirs() }
        val summary = ZipUploader.zipSingleFileSplit(
            sourceFile = source,
            displayName = "large.bin",
            workDir = File(tmp, "split_work"),
            maxPartBytes = 90 * 1024L,
            password = "split-password",
        ) { part ->
            val saved = File(splitSaveDir, part.partFile.name)
            part.partFile.copyTo(saved, overwrite = true)
            stored.add(saved)
        }

        assertThat(summary.hadSplitVolumes).isTrue()
        assertThat(stored.size).isAtLeast(2)
        val merged = File(tmp, "merged_split.zip")
        val ok = ZipUploader.mergeSplitZip(stored.last { it.extension == "zip" }, merged, password = "split-password")
        assertThat(ok).isTrue()

        val extracted = ZipUploader.extractAll(merged.absolutePath, File(tmp, "out"), password = "split-password")
        assertThat(extracted).hasSize(1)
        assertThat(extracted.first().readBytes()).isEqualTo(payload)
    }
}
