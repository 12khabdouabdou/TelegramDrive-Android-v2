package com.tgdrive.core.zip

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tgdrive.core.common.Log
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zips a SAF folder tree into a single .zip file, optionally encrypted,
 * and safely lists/extracts ZIP archives with Zip-Slip & zip-bomb guards.
 */
object ZipUploader {

    private const val MAX_EXTRACT_ENTRIES = 10_000
    private const val MAX_EXTRACT_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_COMPRESSION_RATIO = 100L
    private const val RATIO_CHECK_MIN_BYTES = 10L * 1024 * 1024

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
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zipOut ->
            fun walk(dir: DocumentFile, basePath: String) {
                dir.listFiles().forEach { child ->
                    try {
                        if (child.isDirectory) {
                            walk(child, "$basePath${child.name}/")
                        } else if (child.isFile) {
                            val name = child.name ?: return@forEach
                            if (excludeMatcher?.invoke(name) == true) return@forEach
                            zipOut.putNextEntry(ZipEntry("$basePath$name"))
                            context.contentResolver.openInputStream(child.uri)?.use { it.copyTo(zipOut) }
                            zipOut.closeEntry()
                        }
                    } catch (t: Throwable) {
                        Log.w("ZipUploader: error adding ${child.name}", t)
                    }
                }
            }
            walk(root, "")
        }
    }

    private fun zipWithPassword(
        context: Context,
        root: DocumentFile,
        zipFile: File,
        password: String,
        excludeMatcher: ((String) -> Boolean)? = null,
    ) {
        ZipFile(zipFile, password.toCharArray()).use { zip ->
            val rootName = root.name ?: "folder"
            fun walk(dir: DocumentFile, basePath: String) {
                dir.listFiles().forEach { child ->
                    try {
                        if (child.isDirectory) {
                            walk(child, "$basePath${child.name}/")
                        } else if (child.isFile) {
                            val name = child.name ?: return@forEach
                            if (excludeMatcher?.invoke(name) == true) return@forEach
                            val entryPath = "$rootName/" + if (basePath.isEmpty()) name else "$basePath$name"
                            val params = ZipParameters().apply {
                                encryptionMethod = EncryptionMethod.AES
                                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                                isEncryptFiles = true
                                fileNameInZip = entryPath
                            }
                            context.contentResolver.openInputStream(child.uri)?.use { zip.addStream(it, params) }
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
