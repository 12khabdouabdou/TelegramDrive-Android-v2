package com.tgdrive.core.zip

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.tgdrive.core.common.Log
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream as Zip4jOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Zips a SAF folder tree into single or standalone multi-part .zip files (with
 * Zip4j split-volume fallback for single files exceeding the Telegram cap),
 * optionally AES-256 encrypted, and safely lists/extracts ZIP archives.
 */
object ZipUploader {

    private const val MAX_EXTRACT_ENTRIES = 10_000
    private const val MAX_EXTRACT_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_COMPRESSION_RATIO = 100L
    private const val RATIO_CHECK_MIN_BYTES = 10L * 1024 * 1024
    private const val MIN_ZIP4J_SPLIT_BYTES = 65_536L
    private const val PART_HEADER_MARGIN_BYTES = 8_192L
    private const val COPY_BUFFER_SIZE = 64 * 1024

    fun sanitizeZipBaseName(rawName: String): String {
        val cleaned = rawName
            .replace('\u0000', '_')
            .replace('/', '_')
            .replace('\\', '_')
            .replace("..", "_")
            .trim()
        return cleaned.ifBlank { "archive" }
    }

    fun zipFolder(
        context: Context,
        treeUri: Uri,
        outputDir: File,
        zipName: String,
        password: String? = null,
        excludeMatcher: ((String) -> Boolean)? = null,
    ): String? {
        return try {
            outputDir.mkdirs()
            val safeName = sanitizeZipBaseName(zipName)
            val zipFile = File(outputDir, "$safeName.zip")
            val outRoot = outputDir.canonicalFile.canonicalPath + File.separator
            if (!zipFile.canonicalFile.canonicalPath.startsWith(outRoot)) {
                Log.w("ZipUploader: rejected unsafe zipName '$zipName'")
                return null
            }
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: run {
                Log.w("ZipUploader: fromTreeUri returned null")
                return null
            }
            if (!password.isNullOrBlank()) {
                zipWithPassword(context, root, zipFile, password, excludeMatcher)
            } else {
                zipPlain(context, root, zipFile, excludeMatcher)
            }
            Log.i("ZipUploader: zipped → ${zipFile.absolutePath} (${zipFile.length()} bytes)")
            zipFile.absolutePath
        } catch (t: Throwable) {
            Log.w("ZipUploader: failed", t)
            null
        }
    }

    private fun zipPlain(
        context: Context,
        root: DocumentFile,
        zipFile: File,
        excludeMatcher: ((String) -> Boolean)? = null,
    ) {
        zipStreamSingle(context, root, zipFile, password = null, excludeMatcher = excludeMatcher)
    }

    private fun zipWithPassword(
        context: Context,
        root: DocumentFile,
        zipFile: File,
        password: String,
        excludeMatcher: ((String) -> Boolean)? = null,
    ) {
        zipStreamSingle(context, root, zipFile, password = password, excludeMatcher = excludeMatcher)
    }

    /**
     * Streams all files in [root] into a single [zipFile] in O(N) time using Zip4j's
     * [Zip4jOutputStream], supporting optional AES-256 encryption without re-reading the archive.
     */
    private fun zipStreamSingle(
        context: Context,
        root: DocumentFile,
        zipFile: File,
        password: String?,
        excludeMatcher: ((String) -> Boolean)? = null,
    ) {
        val rootName = root.name ?: "folder"
        val usePassword = !password.isNullOrBlank()
        val rawOut = BufferedOutputStream(FileOutputStream(zipFile), COPY_BUFFER_SIZE)
        val zipOut = if (usePassword) {
            Zip4jOutputStream(rawOut, password!!.toCharArray())
        } else {
            Zip4jOutputStream(rawOut)
        }
        zipOut.use { zos ->
            fun walk(dir: DocumentFile, basePath: String) {
                dir.listFiles().forEach { child ->
                    try {
                        if (child.isDirectory) {
                            walk(child, "$basePath${child.name}/")
                        } else if (child.isFile) {
                            val name = child.name ?: return@forEach
                            if (excludeMatcher?.invoke(name) == true) return@forEach
                            val entryPath = if (usePassword) {
                                "$rootName/" + if (basePath.isEmpty()) name else "$basePath$name"
                            } else {
                                "$basePath$name"
                            }
                            val params = buildZipParameters(entryPath, usePassword)
                            context.contentResolver.openInputStream(child.uri)?.use { input ->
                                zos.putNextEntry(params)
                                input.copyTo(zos, COPY_BUFFER_SIZE)
                                zos.closeEntry()
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w("ZipUploader: error adding ${child.name}", t)
                    }
                }
            }
            walk(root, "")
        }
    }

    /**
     * Checks whether [workDir] has at least [requiredBytes] of usable free disk space.
     * Falls back to [File.usableSpace] on non-Android JVM environments (e.g. unit tests).
     */
    fun hasEnoughFreeSpace(workDir: File, requiredBytes: Long): Boolean {
        val probe = when {
            workDir.exists() -> workDir
            workDir.parentFile?.exists() == true -> workDir.parentFile!!
            else -> {
                workDir.mkdirs()
                workDir
            }
        }
        val available = try {
            StatFs(probe.absolutePath).availableBytes
        } catch (_: Throwable) {
            probe.usableSpace
        }
        return available >= requiredBytes
    }

    /**
     * Option A: Streams SAF folder entries into standalone numbered ZIP parts
     * (`<baseZipName>_part001.zip`, `<baseZipName>_part002.zip`, ...), invoking [onPartReady]
     * after each part closes so callers can upload it immediately and let us delete it
     * before creating the next part.
     *
     * Any single file larger than [maxPartBytes] falls back to a Zip4j split-volume sub-archive
     * (`<baseZipName>_partNNN_split.z01`, ..., `.zip`) so single files > 2 GB / 4 GB also succeed.
     */
    suspend fun zipFolderInParts(
        context: Context,
        treeUri: Uri,
        workDir: File,
        baseZipName: String,
        maxPartBytes: Long,
        password: String? = null,
        excludeMatcher: ((String) -> Boolean)? = null,
        includedRelativePaths: Set<String>? = null,
        includedUris: Set<String>? = null,
        onPartReady: suspend (ZipPartOutput) -> Unit,
    ): ChunkedZipSummary {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Could not open folder URI: $treeUri")
        val rootName = root.name ?: "folder"
        val entries = collectTreeEntries(context, root, rootName, "", excludeMatcher, includedRelativePaths, includedUris)
        return zipSourcesInParts(
            entries = entries,
            workDir = workDir,
            baseZipName = baseZipName,
            maxPartBytes = maxPartBytes,
            password = password,
            checkFreeSpace = true,
            onPartReady = onPartReady,
        )
    }

    private fun collectTreeEntries(
        context: Context,
        dir: DocumentFile,
        rootName: String,
        subPath: String,
        excludeMatcher: ((String) -> Boolean)?,
        includedRelativePaths: Set<String>?,
        includedUris: Set<String>?,
    ): List<ZipSourceEntry> {
        val out = mutableListOf<ZipSourceEntry>()
        dir.listFiles().sortedBy { it.name.orEmpty().lowercase(Locale.US) }.forEach { child ->
            val name = child.name ?: return@forEach
            val relInsideRoot = if (subPath.isEmpty()) name else "$subPath$name"
            if (child.isDirectory) {
                out.addAll(collectTreeEntries(context, child, rootName, "$relInsideRoot/", excludeMatcher, includedRelativePaths, includedUris))
            } else if (child.isFile) {
                if (excludeMatcher?.invoke(name) == true) return@forEach
                if (includedRelativePaths != null && relInsideRoot !in includedRelativePaths) return@forEach
                if (includedUris != null && child.uri.toString() !in includedUris) return@forEach
                val zipEntryPath = "$rootName/$relInsideRoot"
                out.add(
                    ZipSourceEntry(
                        relativePath = zipEntryPath,
                        sizeBytes = child.length().coerceAtLeast(0L),
                        openStream = { context.contentResolver.openInputStream(child.uri) },
                    ),
                )
            }
        }
        return out
    }

    /**
     * Pure stream-based Option A chunked zipper (also directly unit-testable on the JVM).
     */
    suspend fun zipSourcesInParts(
        entries: List<ZipSourceEntry>,
        workDir: File,
        baseZipName: String,
        maxPartBytes: Long,
        password: String? = null,
        checkFreeSpace: Boolean = true,
        onPartReady: suspend (ZipPartOutput) -> Unit,
    ): ChunkedZipSummary {
        require(maxPartBytes >= MIN_ZIP4J_SPLIT_BYTES + PART_HEADER_MARGIN_BYTES) {
            "maxPartBytes must be at least ${MIN_ZIP4J_SPLIT_BYTES + PART_HEADER_MARGIN_BYTES} bytes"
        }
        workDir.mkdirs()
        val safeBase = sanitizeZipBaseName(baseZipName)
        val outRoot = workDir.canonicalFile.canonicalPath + File.separator
        if (!File(workDir, "${safeBase}_part001.zip").canonicalFile.canonicalPath.startsWith(outRoot)) {
            throw SecurityException("Rejected unsafe zip base name: $baseZipName")
        }

        if (checkFreeSpace) {
            val totalEstimated = entries.sumOf { it.sizeBytes.coerceAtLeast(0L) }
            val neededOnce = minOf(maxPartBytes, maxOf(totalEstimated, 1L)) + (16L * 1024 * 1024)
            if (!hasEnoughFreeSpace(workDir, neededOnce)) {
                throw IllegalStateException("Insufficient storage space to create ZIP part (need at least ${neededOnce / (1024 * 1024)} MB free)")
            }
        }

        val usePassword = !password.isNullOrBlank()
        val softLimit = (maxPartBytes - PART_HEADER_MARGIN_BYTES).coerceAtLeast(MIN_ZIP4J_SPLIT_BYTES)
        // Reserve ~5% margin when deciding before an entry whether to roll over so central
        // directory + AES headers never push an already-open part past Telegram's hard cap.
        val entryFitCeiling = (maxPartBytes * 95L / 100L).coerceAtLeast(MIN_ZIP4J_SPLIT_BYTES)

        var nextPartIndex = 1
        var totalFiles = 0
        var totalParts = 0
        var totalUncompressed = 0L
        var hadSplit = false

        var currentFile: File? = null
        var currentCountingOut: CountingOutputStream? = null
        var currentZipOut: Zip4jOutputStream? = null
        val currentEntries = mutableListOf<String>()
        var currentUncompressed = 0L

        fun openNewPart() {
            val idx = nextPartIndex++
            val partFile = File(workDir, "${safeBase}_part${idx.toString().padStart(3, '0')}.zip")
            if (partFile.exists()) partFile.delete()
            val counting = CountingOutputStream(BufferedOutputStream(FileOutputStream(partFile), COPY_BUFFER_SIZE))
            val zos = if (usePassword) {
                Zip4jOutputStream(counting, password!!.toCharArray())
            } else {
                Zip4jOutputStream(counting)
            }
            currentFile = partFile
            currentCountingOut = counting
            currentZipOut = zos
            currentEntries.clear()
            currentUncompressed = 0L
        }

        suspend fun flushCurrentPart() {
            val zos = currentZipOut ?: return
            val partFile = currentFile ?: return
            val partNumber = nextPartIndex - 1
            val entriesSnapshot = currentEntries.toList()
            val uncompressedSnapshot = currentUncompressed
            currentZipOut = null
            currentCountingOut = null
            currentFile = null
            currentEntries.clear()
            currentUncompressed = 0L

            try {
                zos.close()
                if (entriesSnapshot.isNotEmpty() && partFile.exists() && partFile.length() > 0L) {
                    totalParts++
                    onPartReady(
                        ZipPartOutput(
                            partFile = partFile,
                            partIndex = partNumber,
                            isSplitVolume = false,
                            splitGroupBaseName = null,
                            entries = entriesSnapshot,
                            uncompressedBytes = uncompressedSnapshot,
                        ),
                    )
                }
            } finally {
                if (partFile.exists()) partFile.delete()
            }
        }

        try {
            for (entry in entries) {
                val safeRelPath = entry.relativePath.trimStart('/')
                    .replace("..", "_")
                if (safeRelPath.isEmpty()) continue

                // Single entry itself exceeds maxPartBytes -> emit current standalone part first,
                // then split this single oversized file into Zip4j .z01/.zip volumes.
                if (entry.sizeBytes > entryFitCeiling) {
                    flushCurrentPart()
                    hadSplit = true
                    val splitPartNumber = nextPartIndex++
                    val splitBase = "${safeBase}_part${splitPartNumber.toString().padStart(3, '0')}_split"
                    val stageDir = File(workDir, "${splitBase}_stage").apply { mkdirs() }
                    val leafName = sanitizeZipBaseName(safeRelPath.substringAfterLast('/'))
                    val stagedFile = File(stageDir, leafName)
                    try {
                        val opened = entry.openStream()
                        if (opened == null) {
                            Log.w("ZipUploader: cannot open oversized entry $safeRelPath")
                            continue
                        }
                        opened.use { input ->
                            FileOutputStream(stagedFile).use { out ->
                                input.copyTo(out, COPY_BUFFER_SIZE)
                            }
                        }
                        val splitOutZip = File(workDir, "$splitBase.zip")
                        if (splitOutZip.exists()) splitOutZip.delete()
                        val params = buildZipParameters(safeRelPath, usePassword)
                        val splitLen = (maxPartBytes - PART_HEADER_MARGIN_BYTES).coerceAtLeast(MIN_ZIP4J_SPLIT_BYTES)
                        val zf = if (usePassword) {
                            ZipFile(splitOutZip, password!!.toCharArray())
                        } else {
                            ZipFile(splitOutZip)
                        }
                        zf.use { zip ->
                            zip.createSplitZipFile(listOf(stagedFile), params, true, splitLen)
                        }
                        val volumes = collectOrderedSplitVolumes(workDir, splitBase, splitOutZip)
                        volumes.forEach { vol ->
                            try {
                                totalParts++
                                onPartReady(
                                    ZipPartOutput(
                                        partFile = vol,
                                        partIndex = totalParts,
                                        isSplitVolume = true,
                                        splitGroupBaseName = splitBase,
                                        entries = if (vol.extension.equals("zip", ignoreCase = true)) listOf(safeRelPath) else emptyList(),
                                        uncompressedBytes = if (vol.extension.equals("zip", ignoreCase = true)) entry.sizeBytes else 0L,
                                    ),
                                )
                            } finally {
                                if (vol.exists()) vol.delete()
                            }
                        }
                        totalFiles++
                        totalUncompressed += entry.sizeBytes
                    } finally {
                        stageDir.deleteRecursively()
                    }
                    continue
                }

                // Standalone part rollover check BEFORE writing entry
                val currentBytes = currentCountingOut?.bytesWritten ?: 0L
                if (currentZipOut != null &&
                    currentEntries.isNotEmpty() &&
                    currentBytes + entry.sizeBytes > entryFitCeiling
                ) {
                    flushCurrentPart()
                }

                if (currentZipOut == null) {
                    openNewPart()
                }

                val stream = entry.openStream() ?: continue
                stream.use { input ->
                    val zos = currentZipOut!!
                    zos.putNextEntry(buildZipParameters(safeRelPath, usePassword))
                    val copied = input.copyTo(zos, COPY_BUFFER_SIZE)
                    zos.closeEntry()
                    currentEntries.add(safeRelPath)
                    currentUncompressed += copied
                    totalFiles++
                    totalUncompressed += copied
                }

                if ((currentCountingOut?.bytesWritten ?: 0L) >= softLimit) {
                    flushCurrentPart()
                }
            }

            flushCurrentPart()
        } catch (t: Throwable) {
            try {
                currentZipOut?.close()
            } catch (_: Throwable) {}
            currentFile?.delete()
            throw t
        }

        return ChunkedZipSummary(
            totalFilesProcessed = totalFiles,
            totalPartsEmitted = totalParts,
            totalUncompressedBytes = totalUncompressed,
            hadSplitVolumes = hadSplit,
        )
    }

    private fun collectOrderedSplitVolumes(dir: File, splitBase: String, finalZip: File): List<File> {
        val numbered = dir.listFiles()
            ?.filter { file ->
                val name = file.name
                name.startsWith("$splitBase.z") && name != finalZip.name &&
                    name.substringAfterLast(".z").all { it.isDigit() }
            }
            ?.sortedBy { it.name.substringAfterLast(".z").toIntOrNull() ?: Int.MAX_VALUE }
            ?: emptyList()
        return buildList {
            addAll(numbered)
            if (finalZip.exists()) add(finalZip)
        }
    }

    /**
     * Merges a set of split ZIP volumes (`.z01`, `.z02`, ..., `.zip`) located in the same
     * directory as [finalSplitZip] into a single extractable archive [mergedZip].
     */
    fun mergeSplitZip(finalSplitZip: File, mergedZip: File, password: String? = null): Boolean {
        return try {
            mergedZip.parentFile?.mkdirs()
            if (mergedZip.exists()) mergedZip.delete()
            val zf = if (!password.isNullOrBlank()) {
                ZipFile(finalSplitZip, password.toCharArray())
            } else {
                ZipFile(finalSplitZip)
            }
            zf.use { zip ->
                zip.mergeSplitFiles(mergedZip)
            }
            mergedZip.exists() && mergedZip.length() > 0L
        } catch (t: Throwable) {
            Log.w("ZipUploader: mergeSplitZip failed", t)
            false
        }
    }

    /**
     * Writes a pure-JSON manifest file listing every part uploaded for a multi-part backup.
     */
    fun writeManifestJsonFile(
        outputDir: File,
        baseName: String,
        folderName: String,
        encrypted: Boolean,
        parts: List<UploadedZipPartRecord>,
    ): File {
        outputDir.mkdirs()
        val safe = sanitizeZipBaseName(baseName)
        val outFile = File(outputDir, "${safe}_manifest.json")
        outFile.writeText(buildManifestJson(safe, folderName, encrypted, parts))
        return outFile
    }

    fun buildManifestJson(
        baseName: String,
        folderName: String,
        encrypted: Boolean,
        parts: List<UploadedZipPartRecord>,
        createdAtMs: Long = System.currentTimeMillis(),
    ): String {
        val totalFiles = parts.sumOf { it.entries.size }
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"version\": 1,\n")
        sb.append("  \"baseName\": \"").append(escapeJson(baseName)).append("\",\n")
        sb.append("  \"folderName\": \"").append(escapeJson(folderName)).append("\",\n")
        sb.append("  \"createdAtMs\": ").append(createdAtMs).append(",\n")
        sb.append("  \"encrypted\": ").append(encrypted).append(",\n")
        sb.append("  \"totalParts\": ").append(parts.size).append(",\n")
        sb.append("  \"totalFiles\": ").append(totalFiles).append(",\n")
        sb.append("  \"parts\": [\n")
        parts.forEachIndexed { idx, part ->
            sb.append("    {\n")
            sb.append("      \"partIndex\": ").append(part.partIndex).append(",\n")
            sb.append("      \"fileName\": \"").append(escapeJson(part.fileName)).append("\",\n")
            sb.append("      \"fileSizeBytes\": ").append(part.fileSizeBytes).append(",\n")
            sb.append("      \"isSplitVolume\": ").append(part.isSplitVolume).append(",\n")
            sb.append("      \"messageId\": ").append(part.messageId).append(",\n")
            sb.append("      \"entries\": [")
            part.entries.forEachIndexed { eIdx, entryName ->
                if (eIdx > 0) sb.append(", ")
                sb.append("\"").append(escapeJson(entryName)).append("\"")
            }
            sb.append("]\n")
            sb.append("    }")
            if (idx < parts.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun escapeJson(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) append("\\u%04x".format(ch.code))
                    else append(ch)
                }
            }
        }
    }

    private fun buildZipParameters(entryPath: String, encrypt: Boolean): ZipParameters {
        return ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            fileNameInZip = entryPath
            if (encrypt) {
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                isEncryptFiles = true
            } else {
                isEncryptFiles = false
            }
        }
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            bytesWritten += len
        }
    }

    /**
     * Extract a ZIP file safely with Zip-Slip and zip-bomb bounds.
     * Supports both plain and AES-encrypted ZIPs (if password is provided).
     */
    fun extractZip(zipPath: String, outputDir: File, password: String? = null): List<String>? {
        return try {
            outputDir.mkdirs()
            val outRoot = outputDir.canonicalFile.canonicalPath + File.separator
            val zipFile = File(zipPath)
            val openZip = if (password != null) ZipFile(zipFile, password.toCharArray()) else ZipFile(zipFile)
            openZip.use { zip ->
                val headers = zip.fileHeaders
                if (headers.size > MAX_EXTRACT_ENTRIES) {
                    throw SecurityException("ZIP exceeds maximum entry count ($MAX_EXTRACT_ENTRIES)")
                }
                var totalBytes = 0L
                for (header in headers) {
                    val uncomp = header.uncompressedSize.coerceAtLeast(0L)
                    totalBytes += uncomp
                    if (totalBytes > MAX_EXTRACT_TOTAL_BYTES) {
                        throw SecurityException("ZIP uncompressed size exceeds 2 GB limit")
                    }
                    if (uncomp > RATIO_CHECK_MIN_BYTES && header.compressedSize > 0L) {
                        val ratio = uncomp / header.compressedSize
                        if (ratio > MAX_COMPRESSION_RATIO) {
                            throw SecurityException("Suspicious compression ratio ($ratio:1) in entry ${header.fileName}")
                        }
                    }
                    val targetCanonical = File(outputDir, header.fileName).canonicalFile.canonicalPath
                    if (!targetCanonical.startsWith(outRoot) && targetCanonical != outputDir.canonicalFile.canonicalPath) {
                        throw SecurityException("Zip-Slip entry rejected: ${header.fileName}")
                    }
                }
                zip.extractAll(outputDir.absolutePath)
            }
            val extracted = mutableListOf<String>()
            outputDir.walkTopDown().filter { it.isFile }.forEach { extracted.add(it.absolutePath) }
            Log.i("ZipUploader: extracted ${extracted.size} files → ${outputDir.absolutePath}")
            extracted
        } catch (t: Throwable) {
            Log.w("ZipUploader: extractZip failed", t)
            null
        }
    }

    fun listZipEntries(zipPath: String): List<Pair<String, Long>> {
        return try {
            val zipFile = File(zipPath)
            if (!zipFile.exists()) return emptyList()
            ZipFile(zipFile).use { zip ->
                zip.fileHeaders.map { header ->
                    header.fileName to header.uncompressedSize
                }
            }
        } catch (t: Throwable) {
            Log.w("ZipUploader: listZipEntries failed", t)
            emptyList()
        }
    }

    fun listEntries(zipPath: String): List<ZipEntryInfo> {
        return try {
            val zipFile = File(zipPath)
            if (!zipFile.exists()) return emptyList()
            ZipFile(zipFile).use { zip ->
                zip.fileHeaders.map { header ->
                    ZipEntryInfo(
                        name = header.fileName,
                        isDirectory = header.isDirectory,
                        compressedSize = header.compressedSize,
                        uncompressedSize = header.uncompressedSize,
                        isEncrypted = header.isEncrypted,
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w("ZipUploader: listEntries failed", t)
            emptyList()
        }
    }
}

data class ZipEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val isEncrypted: Boolean,
)

data class ZipSourceEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val openStream: () -> InputStream?,
)

data class ZipPartOutput(
    val partFile: File,
    val partIndex: Int,
    val isSplitVolume: Boolean,
    val splitGroupBaseName: String?,
    val entries: List<String>,
    val uncompressedBytes: Long,
)

data class UploadedZipPartRecord(
    val fileName: String,
    val fileSizeBytes: Long,
    val partIndex: Int,
    val isSplitVolume: Boolean,
    val messageId: Long,
    val entries: List<String>,
)

data class ChunkedZipSummary(
    val totalFilesProcessed: Int,
    val totalPartsEmitted: Int,
    val totalUncompressedBytes: Long,
    val hadSplitVolumes: Boolean,
)
