package com.tgdrive.core.zip

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.tgdrive.core.common.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream as Zip4jOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

enum class ZipCompressionMode {
    SMART,
    STORE,
    DEFLATE,
}

data class ZipProgress(
    val filesPacked: Int,
    val totalFiles: Int,
    val bytesPacked: Long,
    val totalBytes: Long,
    val currentFileName: String,
    val partIndex: Int,
)

data class ZipSelectionSummary(
    val fileCount: Int,
    val totalBytes: Long,
    val estimatedParts: Int,
    val freeCacheBytes: Long,
    val hasEnoughSpace: Boolean,
)

/**
 * Zips SAF folder trees and arbitrary URI selections into single or standalone multi-part
 * `.zip` archives (with Zip4j split-volume fallback for single files exceeding the Telegram cap),
 * smart per-extension compression, pipelined part packing, cooperative cancellation, AES-256
 * encryption, and safe Zip-Slip-guarded extraction.
 */
object ZipUploader {

    const val DEFAULT_PART_MAX_BYTES = 1_900L * 1024 * 1024
    private const val MAX_EXTRACT_ENTRIES = 10_000
    private const val MAX_EXTRACT_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_COMPRESSION_RATIO = 100L
    private const val RATIO_CHECK_MIN_BYTES = 10L * 1024 * 1024
    private const val MIN_ZIP4J_SPLIT_BYTES = 65_536L
    private const val PART_HEADER_MARGIN_BYTES = 8_192L
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val PIPELINE_BUFFER_SAFETY_BYTES = 128L * 1024 * 1024

    private val INCOMPRESSIBLE_EXTS = setOf(
        "jpg", "jpeg", "heic", "heif", "webp", "png", "gif",
        "mp4", "mkv", "mov", "webm", "avi", "3gp", "m4v",
        "mp3", "aac", "m4a", "ogg", "opus", "flac",
        "zip", "7z", "rar", "gz", "bz2", "xz", "apk", "pdf", "tgenc",
    )

    private val DOC_CHILD_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    fun resolveCompressionMethod(
        fileName: String,
        mode: ZipCompressionMode = ZipCompressionMode.SMART,
    ): CompressionMethod = when (mode) {
        ZipCompressionMode.STORE -> CompressionMethod.STORE
        ZipCompressionMode.DEFLATE -> CompressionMethod.DEFLATE
        ZipCompressionMode.SMART -> {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
            if (ext in INCOMPRESSIBLE_EXTS) CompressionMethod.STORE else CompressionMethod.DEFLATE
        }
    }

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
            zipStreamSingle(
                context = context,
                root = root,
                zipFile = zipFile,
                password = password,
                excludeMatcher = excludeMatcher,
            )
            Log.i("ZipUploader: zipped -> ${zipFile.absolutePath} (${zipFile.length()} bytes)")
            zipFile.absolutePath
        } catch (t: Throwable) {
            Log.w("ZipUploader: failed", t)
            null
        }
    }

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
                            val childSize = child.length().takeIf { it > 0L } ?: resolveUriSize(context, child.uri)
                            val params = buildZipParameters(entryPath, usePassword, ZipCompressionMode.SMART, childSize)
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
        return availableBytesFor(workDir) >= requiredBytes
    }

    fun availableBytesFor(dir: File): Long {
        val probe = when {
            dir.exists() -> dir
            dir.parentFile?.exists() == true -> dir.parentFile!!
            else -> {
                dir.mkdirs()
                dir
            }
        }
        return try {
            StatFs(probe.absolutePath).availableBytes.takeIf { it > 0L } ?: probe.usableSpace
        } catch (_: Throwable) {
            probe.usableSpace
        }
    }

    suspend fun inspectTreeSummary(
        context: Context,
        treeUri: Uri,
        maxPartBytes: Long = DEFAULT_PART_MAX_BYTES,
        excludeMatcher: ((relPath: String, name: String) -> Boolean)? = null,
    ): ZipSelectionSummary {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val cursorEntries = if (!rootDocId.isNullOrBlank()) {
            runCatching {
                collectTreeEntriesViaCursor(
                    context = context,
                    treeUri = treeUri,
                    rootDocId = rootDocId,
                    rootName = rootDocId.substringAfterLast(':').substringAfterLast('/').ifBlank { "folder" },
                    excludeMatcher = null,
                    excludePathMatcher = excludeMatcher,
                    includedRelativePaths = null,
                    includedUris = null,
                )
            }.getOrNull()
        } else {
            null
        }
        val entries: List<ZipSourceEntry> = cursorEntries ?: DocumentFile.fromTreeUri(context, treeUri)?.let { root ->
            collectTreeEntriesFallback(
                context = context,
                dir = root,
                rootName = root.name ?: "folder",
                subPath = "",
                excludeMatcher = null,
                excludePathMatcher = excludeMatcher,
                includedRelativePaths = null,
                includedUris = null,
            )
        }.orEmpty()
        return buildSelectionSummary(context, entries.size, entries.sumOf { it.sizeBytes.coerceAtLeast(0L) }, maxPartBytes)
    }

    suspend fun inspectUrisSummary(
        context: Context,
        uris: List<Uri>,
        maxPartBytes: Long = DEFAULT_PART_MAX_BYTES,
    ): ZipSelectionSummary {
        var total = 0L
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            total += resolveUriSize(context, uri)
        }
        return buildSelectionSummary(context, uris.size, total, maxPartBytes)
    }

    private fun buildSelectionSummary(
        context: Context,
        fileCount: Int,
        totalBytes: Long,
        maxPartBytes: Long,
    ): ZipSelectionSummary {
        val safeMax = maxPartBytes.coerceAtLeast(MIN_ZIP4J_SPLIT_BYTES + PART_HEADER_MARGIN_BYTES)
        val estimatedParts = if (fileCount == 0) {
            0
        } else {
            ((totalBytes + safeMax - 1L) / safeMax).toInt().coerceAtLeast(1)
        }
        val freeBytes = availableBytesFor(context.cacheDir)
        val minNeeded = minOf(totalBytes, safeMax) + PIPELINE_BUFFER_SAFETY_BYTES
        return ZipSelectionSummary(
            fileCount = fileCount,
            totalBytes = totalBytes,
            estimatedParts = estimatedParts,
            freeCacheBytes = freeBytes,
            hasEnoughSpace = freeBytes >= minNeeded,
        )
    }

    /**
     * Option A: Streams SAF folder entries into standalone numbered ZIP parts
     * (`<baseZipName>_part001.zip`, `<baseZipName>_part002.zip`, ...), invoking [onPartReady]
     * after each part closes so callers can upload it immediately and let us delete it
     * before creating the next part (or pack 1 part ahead when pipelining is safe).
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
        compressionMode: ZipCompressionMode = ZipCompressionMode.SMART,
        enablePipeline: Boolean = true,
        excludePathMatcher: ((relPath: String, name: String) -> Boolean)? = null,
        onZipProgress: (suspend (ZipProgress) -> Unit)? = null,
        onPartReady: suspend (ZipPartOutput) -> Unit,
    ): ChunkedZipSummary {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Could not open folder URI: $treeUri")
        val rootName = root.name ?: "folder"
        val entries = collectTreeEntries(
            context = context,
            treeUri = treeUri,
            root = root,
            rootName = rootName,
            excludeMatcher = excludeMatcher,
            excludePathMatcher = excludePathMatcher,
            includedRelativePaths = includedRelativePaths,
            includedUris = includedUris,
        )
        return zipSourcesInParts(
            entries = entries,
            workDir = workDir,
            baseZipName = baseZipName,
            maxPartBytes = maxPartBytes,
            password = password,
            checkFreeSpace = true,
            compressionMode = compressionMode,
            enablePipeline = enablePipeline,
            onZipProgress = onZipProgress,
            onPartReady = onPartReady,
        )
    }

    /**
     * Packs an arbitrary list of SAF [uris] into standalone or split ZIP parts using Option A chunking.
     */
    suspend fun zipUrisInParts(
        context: Context,
        uris: List<Uri>,
        workDir: File,
        baseZipName: String,
        customNames: Map<Uri, String> = emptyMap(),
        maxPartBytes: Long = DEFAULT_PART_MAX_BYTES,
        password: String? = null,
        compressionMode: ZipCompressionMode = ZipCompressionMode.SMART,
        enablePipeline: Boolean = true,
        onZipProgress: (suspend (ZipProgress) -> Unit)? = null,
        onPartReady: suspend (ZipPartOutput) -> Unit,
    ): ChunkedZipSummary {
        val usedNames = HashSet<String>(uris.size)
        val entries = ArrayList<ZipSourceEntry>(uris.size)
        uris.forEachIndexed { idx, uri ->
            currentCoroutineContext().ensureActive()
            val rawName = customNames[uri]?.takeIf { it.isNotBlank() }
                ?: resolveUriDisplayName(context, uri)
                ?: "file_${idx + 1}"
            val safeName = deduplicateEntryName(sanitizeZipBaseName(rawName), usedNames)
            val size = resolveUriSize(context, uri)
            entries.add(
                ZipSourceEntry(
                    relativePath = safeName,
                    sizeBytes = size,
                    openStream = { context.contentResolver.openInputStream(uri) },
                ),
            )
        }
        return zipSourcesInParts(
            entries = entries,
            workDir = workDir,
            baseZipName = baseZipName,
            maxPartBytes = maxPartBytes,
            password = password,
            checkFreeSpace = true,
            compressionMode = compressionMode,
            enablePipeline = enablePipeline,
            onZipProgress = onZipProgress,
            onPartReady = onPartReady,
        )
    }

    /**
     * Wraps a single oversized local file into Zip4j split volumes (`.z01`, ..., `.zip`),
     * streaming each volume through [onPartReady] and deleting temporary volumes afterwards.
     */
    suspend fun zipSingleFileSplit(
        sourceFile: File,
        displayName: String,
        workDir: File,
        maxPartBytes: Long,
        password: String? = null,
        onPartReady: suspend (ZipPartOutput) -> Unit,
    ): ChunkedZipSummary {
        require(sourceFile.isFile) { "Source file does not exist: ${sourceFile.absolutePath}" }
        require(maxPartBytes >= MIN_ZIP4J_SPLIT_BYTES + PART_HEADER_MARGIN_BYTES) {
            "maxPartBytes must be at least ${MIN_ZIP4J_SPLIT_BYTES + PART_HEADER_MARGIN_BYTES} bytes"
        }
        workDir.mkdirs()
        val safeLeaf = sanitizeZipBaseName(displayName)
        val stem = sanitizeZipBaseName(safeLeaf.substringBeforeLast('.').ifBlank { safeLeaf })
        val usePassword = !password.isNullOrBlank()
        val splitOutZip = File(workDir, "$stem.zip")
        if (splitOutZip.exists()) splitOutZip.delete()
        val params = buildZipParameters(safeLeaf, usePassword, ZipCompressionMode.STORE, sourceFile.length())
        val splitLen = (maxPartBytes - PART_HEADER_MARGIN_BYTES).coerceAtLeast(MIN_ZIP4J_SPLIT_BYTES)
        val zf = if (usePassword) ZipFile(splitOutZip, password!!.toCharArray()) else ZipFile(splitOutZip)
        zf.use { zip ->
            zip.createSplitZipFile(listOf(sourceFile), params, true, splitLen)
        }
        val volumes = collectOrderedSplitVolumes(workDir, stem, splitOutZip)
        var emitted = 0
        try {
            volumes.forEach { vol ->
                currentCoroutineContext().ensureActive()
                emitted++
                val isFinalZip = vol.extension.equals("zip", ignoreCase = true)
                onPartReady(
                    ZipPartOutput(
                        partFile = vol,
                        partIndex = emitted,
                        isSplitVolume = true,
                        splitGroupBaseName = stem,
                        entries = if (isFinalZip) listOf(safeLeaf) else emptyList(),
                        uncompressedBytes = if (isFinalZip) sourceFile.length() else 0L,
                    ),
                )
            }
        } finally {
            volumes.forEach { vol -> runCatching { if (vol.exists()) vol.delete() } }
        }
        return ChunkedZipSummary(
            totalFilesProcessed = 1,
            totalPartsEmitted = emitted,
            totalUncompressedBytes = sourceFile.length(),
            hadSplitVolumes = true,
        )
    }

    private suspend fun collectTreeEntries(
        context: Context,
        treeUri: Uri,
        root: DocumentFile,
        rootName: String,
        excludeMatcher: ((String) -> Boolean)?,
        excludePathMatcher: ((relPath: String, name: String) -> Boolean)?,
        includedRelativePaths: Set<String>?,
        includedUris: Set<String>?,
    ): List<ZipSourceEntry> {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (!rootDocId.isNullOrBlank()) {
            val cursorResult = runCatching {
                collectTreeEntriesViaCursor(
                    context = context,
                    treeUri = treeUri,
                    rootDocId = rootDocId,
                    rootName = rootName,
                    excludeMatcher = excludeMatcher,
                    excludePathMatcher = excludePathMatcher,
                    includedRelativePaths = includedRelativePaths,
                    includedUris = includedUris,
                )
            }.getOrNull()
            if (cursorResult != null) return cursorResult
        }
        return collectTreeEntriesFallback(
            context = context,
            dir = root,
            rootName = rootName,
            subPath = "",
            excludeMatcher = excludeMatcher,
            excludePathMatcher = excludePathMatcher,
            includedRelativePaths = includedRelativePaths,
            includedUris = includedUris,
        )
    }

    private suspend fun collectTreeEntriesViaCursor(
        context: Context,
        treeUri: Uri,
        rootDocId: String,
        rootName: String,
        excludeMatcher: ((String) -> Boolean)?,
        excludePathMatcher: ((relPath: String, name: String) -> Boolean)?,
        includedRelativePaths: Set<String>?,
        includedUris: Set<String>?,
    ): List<ZipSourceEntry>? {
        data class DirNode(val docId: String, val subPath: String)
        data class ChildRow(val docId: String, val name: String, val isDir: Boolean, val size: Long)

        val resolver = context.contentResolver
        val out = ArrayList<ZipSourceEntry>(128)
        val stack = ArrayDeque<DirNode>()
        stack.add(DirNode(rootDocId, ""))
        var dirsVisited = 0

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val node = stack.removeFirst()
            dirsVisited++
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.docId)
            val rows = ArrayList<ChildRow>()
            val cursor = try {
                resolver.query(childrenUri, DOC_CHILD_PROJECTION, null, null, null)
            } catch (_: Throwable) {
                null
            }
            if (cursor == null) {
                if (dirsVisited == 1) return null
                continue
            }
            cursor.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (idIdx < 0 || nameIdx < 0) return@use
                while (c.moveToNext()) {
                    val cId = c.getString(idIdx) ?: continue
                    val cName = c.getString(nameIdx) ?: continue
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx).orEmpty() else ""
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (!isDir && sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    rows.add(ChildRow(cId, cName, isDir, size.coerceAtLeast(0L)))
                }
            }
            rows.sortBy { it.name.lowercase(Locale.US) }
            val subDirs = ArrayList<DirNode>()
            for (row in rows) {
                val relInsideRoot = if (node.subPath.isEmpty()) row.name else "${node.subPath}${row.name}"
                if (row.isDir) {
                    val dirRel = "$relInsideRoot/"
                    if (excludeMatcher?.invoke(row.name) == true) continue
                    if (excludePathMatcher?.invoke(dirRel, row.name) == true) continue
                    subDirs.add(DirNode(row.docId, dirRel))
                } else {
                    if (excludeMatcher?.invoke(row.name) == true) continue
                    if (excludePathMatcher?.invoke(relInsideRoot, row.name) == true) continue
                    if (includedRelativePaths != null && relInsideRoot !in includedRelativePaths) continue
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, row.docId)
                    if (includedUris != null && docUri.toString() !in includedUris) continue
                    out.add(
                        ZipSourceEntry(
                            relativePath = "$rootName/$relInsideRoot",
                            sizeBytes = row.size,
                            openStream = { resolver.openInputStream(docUri) },
                        ),
                    )
                }
            }
            // Prepend sorted subdirectories so traversal remains depth-first alphabetical
            for (idx in subDirs.indices.reversed()) {
                stack.addFirst(subDirs[idx])
            }
        }
        return out
    }

    private suspend fun collectTreeEntriesFallback(
        context: Context,
        dir: DocumentFile,
        rootName: String,
        subPath: String,
        excludeMatcher: ((String) -> Boolean)?,
        excludePathMatcher: ((relPath: String, name: String) -> Boolean)?,
        includedRelativePaths: Set<String>?,
        includedUris: Set<String>?,
    ): List<ZipSourceEntry> {
        val out = mutableListOf<ZipSourceEntry>()
        dir.listFiles().sortedBy { it.name.orEmpty().lowercase(Locale.US) }.forEach { child ->
            currentCoroutineContext().ensureActive()
            val name = child.name ?: return@forEach
            val relInsideRoot = if (subPath.isEmpty()) name else "$subPath$name"
            if (child.isDirectory) {
                val dirRel = "$relInsideRoot/"
                if (excludeMatcher?.invoke(name) == true) return@forEach
                if (excludePathMatcher?.invoke(dirRel, name) == true) return@forEach
                out.addAll(
                    collectTreeEntriesFallback(
                        context = context,
                        dir = child,
                        rootName = rootName,
                        subPath = dirRel,
                        excludeMatcher = excludeMatcher,
                        excludePathMatcher = excludePathMatcher,
                        includedRelativePaths = includedRelativePaths,
                        includedUris = includedUris,
                    ),
                )
            } else if (child.isFile) {
                if (excludeMatcher?.invoke(name) == true) return@forEach
                if (excludePathMatcher?.invoke(relInsideRoot, name) == true) return@forEach
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
     * Pure stream-based Option A chunked zipper (directly unit-testable on the JVM).
     * When [enablePipeline] is enabled and [workDir] has room for 2 parts, packs part `N+1`
     * asynchronously while [onPartReady] uploads part `N`.
     */
    suspend fun zipSourcesInParts(
        entries: List<ZipSourceEntry>,
        workDir: File,
        baseZipName: String,
        maxPartBytes: Long,
        password: String? = null,
        checkFreeSpace: Boolean = true,
        compressionMode: ZipCompressionMode = ZipCompressionMode.SMART,
        enablePipeline: Boolean = true,
        onZipProgress: (suspend (ZipProgress) -> Unit)? = null,
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

        val totalEstimated = entries.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        if (checkFreeSpace) {
            val neededOnce = minOf(maxPartBytes, maxOf(totalEstimated, 1L)) + (16L * 1024 * 1024)
            if (!hasEnoughFreeSpace(workDir, neededOnce)) {
                throw IllegalStateException(
                    "Insufficient storage space to create ZIP part (need at least ${neededOnce / (1024 * 1024)} MB free)",
                )
            }
        }

        val canPipeline = enablePipeline && entries.size > 1 && (
            !checkFreeSpace ||
                hasEnoughFreeSpace(workDir, minOf(totalEstimated, maxPartBytes * 2L) + PIPELINE_BUFFER_SAFETY_BYTES)
            )

        if (!canPipeline) {
            return packEntriesSequential(
                entries = entries,
                workDir = workDir,
                safeBase = safeBase,
                maxPartBytes = maxPartBytes,
                password = password,
                compressionMode = compressionMode,
                totalEstimatedBytes = totalEstimated,
                onZipProgress = onZipProgress,
                onPartReady = { part ->
                    try {
                        onPartReady(part)
                    } finally {
                        if (part.partFile.exists()) part.partFile.delete()
                    }
                },
            )
        }

        return coroutineScope {
            val channel = Channel<ZipPartOutput>(
                capacity = 1,
                onUndeliveredElement = { leftover -> runCatching { leftover.partFile.delete() } },
            )
            val producer = async {
                try {
                    packEntriesSequential(
                        entries = entries,
                        workDir = workDir,
                        safeBase = safeBase,
                        maxPartBytes = maxPartBytes,
                        password = password,
                        compressionMode = compressionMode,
                        totalEstimatedBytes = totalEstimated,
                        onZipProgress = onZipProgress,
                        onPartReady = { part -> channel.send(part) },
                    )
                } catch (t: Throwable) {
                    channel.close(t)
                    throw t
                } finally {
                    channel.close()
                }
            }
            try {
                for (part in channel) {
                    currentCoroutineContext().ensureActive()
                    try {
                        onPartReady(part)
                    } finally {
                        if (part.partFile.exists()) part.partFile.delete()
                    }
                }
                producer.await()
            } catch (t: Throwable) {
                producer.cancel()
                while (true) {
                    val rem = channel.tryReceive().getOrNull() ?: break
                    runCatching { rem.partFile.delete() }
                }
                runCatching { producer.await() }
                throw t
            }
        }
    }

    private suspend fun packEntriesSequential(
        entries: List<ZipSourceEntry>,
        workDir: File,
        safeBase: String,
        maxPartBytes: Long,
        password: String?,
        compressionMode: ZipCompressionMode,
        totalEstimatedBytes: Long,
        onZipProgress: (suspend (ZipProgress) -> Unit)?,
        onPartReady: suspend (ZipPartOutput) -> Unit,
    ): ChunkedZipSummary {
        val usePassword = !password.isNullOrBlank()
        val softLimit = (maxPartBytes - PART_HEADER_MARGIN_BYTES).coerceAtLeast(MIN_ZIP4J_SPLIT_BYTES)
        val entryFitCeiling = (maxPartBytes * 95L / 100L).coerceAtLeast(MIN_ZIP4J_SPLIT_BYTES)

        var nextPartIndex = 1
        var totalFiles = 0
        var totalParts = 0
        var totalUncompressed = 0L
        var hadSplit = false
        var lastProgressMs = 0L

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
                } else if (partFile.exists()) {
                    partFile.delete()
                }
            } catch (t: Throwable) {
                if (partFile.exists()) partFile.delete()
                throw t
            }
        }

        try {
            val totalEntryCount = entries.size
            for ((entryIdx, entry) in entries.withIndex()) {
                currentCoroutineContext().ensureActive()
                val safeRelPath = entry.relativePath.trimStart('/').replace("..", "_")
                if (safeRelPath.isEmpty()) continue

                if (entry.sizeBytes > entryFitCeiling) {
                    flushCurrentPart()
                    hadSplit = true
                    val splitPartNumber = nextPartIndex++
                    val splitBase = "${safeBase}_part${splitPartNumber.toString().padStart(3, '0')}_split"
                    val stageDir = File(workDir, "${splitBase}_stage").apply { mkdirs() }
                    val leafName = sanitizeZipBaseName(safeRelPath.substringAfterLast('/'))
                    val stagedFile = File(stageDir, leafName)
                    var emittedVolumes: List<File> = emptyList()
                    try {
                        val opened = entry.openStream()
                        if (opened == null) {
                            Log.w("ZipUploader: cannot open oversized entry $safeRelPath")
                            continue
                        }
                        opened.use { input ->
                            FileOutputStream(stagedFile).use { out ->
                                copyStreamWithCancelCheck(input, out)
                            }
                        }
                        val splitOutZip = File(workDir, "$splitBase.zip")
                        if (splitOutZip.exists()) splitOutZip.delete()
                        val params = buildZipParameters(safeRelPath, usePassword, compressionMode, stagedFile.length())
                        val splitLen = (maxPartBytes - PART_HEADER_MARGIN_BYTES).coerceAtLeast(MIN_ZIP4J_SPLIT_BYTES)
                        val zf = if (usePassword) {
                            ZipFile(splitOutZip, password!!.toCharArray())
                        } else {
                            ZipFile(splitOutZip)
                        }
                        zf.use { zip ->
                            zip.createSplitZipFile(listOf(stagedFile), params, true, splitLen)
                        }
                        emittedVolumes = collectOrderedSplitVolumes(workDir, splitBase, splitOutZip)
                        var emittedCount = 0
                        emittedVolumes.forEach { vol ->
                            currentCoroutineContext().ensureActive()
                            totalParts++
                            onPartReady(
                                ZipPartOutput(
                                    partFile = vol,
                                    partIndex = totalParts,
                                    isSplitVolume = true,
                                    splitGroupBaseName = splitBase,
                                    entries = if (vol.extension.equals("zip", ignoreCase = true)) {
                                        listOf(safeRelPath)
                                    } else {
                                        emptyList()
                                    },
                                    uncompressedBytes = if (vol.extension.equals("zip", ignoreCase = true)) {
                                        entry.sizeBytes
                                    } else {
                                        0L
                                    },
                                ),
                            )
                            emittedCount++
                        }
                        totalFiles++
                        totalUncompressed += entry.sizeBytes
                        onZipProgress?.invoke(
                            ZipProgress(
                                filesPacked = totalFiles,
                                totalFiles = totalEntryCount,
                                bytesPacked = totalUncompressed,
                                totalBytes = totalEstimatedBytes.coerceAtLeast(totalUncompressed),
                                currentFileName = safeRelPath.substringAfterLast('/'),
                                partIndex = totalParts.coerceAtLeast(1),
                            ),
                        )
                    } finally {
                        emittedVolumes.drop(emittedCount).forEach { vol ->
                            runCatching { if (vol.exists()) vol.delete() }
                        }
                        stageDir.deleteRecursively()
                    }
                    continue
                }

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
                    val params = buildZipParameters(safeRelPath, usePassword, compressionMode, entry.sizeBytes)
                    zos.putNextEntry(params)
                    val copied = copyStreamWithCancelCheck(input, zos)
                    zos.closeEntry()
                    currentEntries.add(safeRelPath)
                    currentUncompressed += copied
                    totalFiles++
                    totalUncompressed += copied
                }

                val now = System.currentTimeMillis()
                if (onZipProgress != null && (entryIdx == totalEntryCount - 1 || now - lastProgressMs >= PROGRESS_INTERVAL_MS)) {
                    lastProgressMs = now
                    onZipProgress(
                        ZipProgress(
                            filesPacked = totalFiles,
                            totalFiles = totalEntryCount,
                            bytesPacked = totalUncompressed,
                            totalBytes = totalEstimatedBytes.coerceAtLeast(totalUncompressed),
                            currentFileName = safeRelPath.substringAfterLast('/'),
                            partIndex = (nextPartIndex - 1).coerceAtLeast(1),
                        ),
                    )
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

    private suspend fun copyStreamWithCancelCheck(input: InputStream, output: OutputStream): Long {
        val buf = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) {
                output.write(buf, 0, n)
                total += n
            }
        }
        return total
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

    private fun buildZipParameters(
        entryPath: String,
        encrypt: Boolean,
        compressionMode: ZipCompressionMode,
        entrySizeBytes: Long = -1L,
    ): ZipParameters {
        val resolved = resolveCompressionMethod(entryPath, compressionMode)
        val method = if (resolved == CompressionMethod.STORE && entrySizeBytes <= 0L) {
            CompressionMethod.DEFLATE
        } else {
            resolved
        }
        return ZipParameters().apply {
            compressionMethod = method
            if (method == CompressionMethod.STORE) {
                entrySize = entrySizeBytes
            } else {
                compressionLevel = CompressionLevel.NORMAL
            }
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
     * Supports both plain and AES-encrypted ZIPs (if [password] is provided).
     */
    fun extractZip(zipPath: String, outputDir: File, password: String? = null): List<String>? {
        return try {
            val extractedFiles = extractAll(zipPath = zipPath, outputDir = outputDir, password = password)
            extractedFiles.map { it.absolutePath }
        } catch (t: Throwable) {
            Log.w("ZipUploader: extractZip failed", t)
            null
        }
    }

    /**
     * Extracts all entries in [zipPath] into [outputDir] with Zip-Slip, entry-count,
     * uncompressed-size, and compression-ratio safety checks. Throws on bad password or
     * invalid archive structure.
     */
    fun extractAll(
        zipPath: String,
        outputDir: File,
        password: String? = null,
        onProgress: ((extractedCount: Int, totalCount: Int, currentName: String) -> Unit)? = null,
    ): List<File> {
        outputDir.mkdirs()
        val outCanonical = outputDir.canonicalFile.canonicalPath
        val outRoot = outCanonical + File.separator
        val zipFile = File(zipPath)
        val openZip = if (!password.isNullOrBlank()) {
            ZipFile(zipFile, password.toCharArray())
        } else {
            ZipFile(zipFile)
        }
        val extractedFiles = mutableListOf<File>()
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
                    throw SecurityException("ZIP uncompressed size exceeds 4 GB limit")
                }
                if (uncomp > RATIO_CHECK_MIN_BYTES && header.compressedSize > 0L) {
                    val ratio = uncomp / header.compressedSize
                    if (ratio > MAX_COMPRESSION_RATIO) {
                        throw SecurityException("Suspicious compression ratio ($ratio:1) in entry ${header.fileName}")
                    }
                }
                val targetCanonical = File(outputDir, header.fileName).canonicalFile.canonicalPath
                if (!targetCanonical.startsWith(outRoot) && targetCanonical != outCanonical) {
                    throw SecurityException("Zip-Slip entry rejected: ${header.fileName}")
                }
            }
            val fileHeaders = headers.filterNot { it.isDirectory }
            var actualTotalBytes = 0L
            val copyBuf = ByteArray(64 * 1024)
            fileHeaders.forEachIndexed { idx, header ->
                val outFile = File(outputDir, header.fileName)
                outFile.parentFile?.mkdirs()
                var entryWritten = 0L
                try {
                    zip.getInputStream(header).use { input ->
                        FileOutputStream(outFile).use { output ->
                            while (true) {
                                val read = input.read(copyBuf)
                                if (read <= 0) break
                                entryWritten += read
                                actualTotalBytes += read
                                if (actualTotalBytes > MAX_EXTRACT_TOTAL_BYTES) {
                                    throw SecurityException("ZIP uncompressed size exceeds 4 GB limit")
                                }
                                if (entryWritten > RATIO_CHECK_MIN_BYTES &&
                                    header.compressedSize > 0L &&
                                    (entryWritten / header.compressedSize) > MAX_COMPRESSION_RATIO
                                ) {
                                    val ratio = entryWritten / header.compressedSize
                                    throw SecurityException("Suspicious compression ratio ($ratio:1) in entry ${header.fileName}")
                                }
                                output.write(copyBuf, 0, read)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    runCatching { outFile.delete() }
                    throw t
                }
                if (outFile.isFile) extractedFiles.add(outFile)
                onProgress?.invoke(idx + 1, fileHeaders.size, header.fileName)
            }
        }
        Log.i("ZipUploader: extractAll extracted ${extractedFiles.size} files -> ${outputDir.absolutePath}")
        return extractedFiles
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

    /**
     * Locates the byte range `(offset, size)` of a ZIP file's Central Directory given
     * [tailBytes] read from offset [tailFileOffset] near the end of the archive.
     * Supports both standard ZIP32 EOCD and ZIP64 EOCD records.
     */
    fun findCentralDirectoryRange(
        tailBytes: ByteArray,
        tailFileOffset: Long,
    ): ZipCentralDirectoryRange? {
        if (tailBytes.size < 22) return null
        val buf = ByteBuffer.wrap(tailBytes).order(ByteOrder.LITTLE_ENDIAN)
        var eocdPos = -1
        for (i in (tailBytes.size - 22) downTo 0) {
            if (tailBytes[i] == 0x50.toByte() &&
                tailBytes[i + 1] == 0x4b.toByte() &&
                tailBytes[i + 2] == 0x05.toByte() &&
                tailBytes[i + 3] == 0x06.toByte()
            ) {
                val commentLen = buf.getShort(i + 20).toInt() and 0xFFFF
                if (i + 22 + commentLen <= tailBytes.size) {
                    eocdPos = i
                    break
                }
            }
        }
        if (eocdPos < 0) return null

        var cdSize = buf.getInt(eocdPos + 12).toLong() and 0xFFFFFFFFL
        var cdOffset = buf.getInt(eocdPos + 16).toLong() and 0xFFFFFFFFL

        if ((cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) && eocdPos >= 20) {
            val locPos = eocdPos - 20
            if (buf.getInt(locPos) == 0x07064b50) {
                val z64EocdOffset = buf.getLong(locPos + 8)
                val z64Rel = (z64EocdOffset - tailFileOffset).toInt()
                if (z64Rel >= 0 && z64Rel + 56 <= tailBytes.size && buf.getInt(z64Rel) == 0x06064b50) {
                    cdSize = buf.getLong(z64Rel + 40)
                    cdOffset = buf.getLong(z64Rel + 48)
                }
            }
        }

        if (cdOffset < 0L || cdSize < 0L) return null
        return ZipCentralDirectoryRange(offset = cdOffset, size = cdSize)
    }

    /**
     * Parses ZIP Central Directory (`0x02014b50`) headers directly from an exact slice
     * of the Central Directory bytes without downloading the file payloads.
     */
    fun parseCentralDirectoryEntries(cdBytes: ByteArray): List<ZipEntryInfo> {
        if (cdBytes.size < 46) return emptyList()
        val buf = ByteBuffer.wrap(cdBytes).order(ByteOrder.LITTLE_ENDIAN)
        val result = mutableListOf<ZipEntryInfo>()
        var pos = 0
        while (pos + 46 <= cdBytes.size) {
            if (buf.getInt(pos) != 0x02014b50) break
            val gpFlag = buf.getShort(pos + 8).toInt() and 0xFFFF
            var compSize = buf.getInt(pos + 20).toLong() and 0xFFFFFFFFL
            var uncompSize = buf.getInt(pos + 24).toLong() and 0xFFFFFFFFL
            val nameLen = buf.getShort(pos + 28).toInt() and 0xFFFF
            val extraLen = buf.getShort(pos + 30).toInt() and 0xFFFF
            val commentLen = buf.getShort(pos + 32).toInt() and 0xFFFF
            val nextPos = pos + 46 + nameLen + extraLen + commentLen
            if (nextPos > cdBytes.size) break

            val name = String(cdBytes, pos + 46, nameLen, Charsets.UTF_8)
            if ((compSize == 0xFFFFFFFFL || uncompSize == 0xFFFFFFFFL) && extraLen >= 4) {
                var ePos = pos + 46 + nameLen
                val eEnd = ePos + extraLen
                while (ePos + 4 <= eEnd) {
                    val headerId = buf.getShort(ePos).toInt() and 0xFFFF
                    val dataSize = buf.getShort(ePos + 2).toInt() and 0xFFFF
                    if (headerId == 0x0001 && ePos + 4 + dataSize <= eEnd) {
                        var fieldOff = ePos + 4
                        if (uncompSize == 0xFFFFFFFFL && fieldOff + 8 <= ePos + 4 + dataSize) {
                            uncompSize = buf.getLong(fieldOff)
                            fieldOff += 8
                        }
                        if (compSize == 0xFFFFFFFFL && fieldOff + 8 <= ePos + 4 + dataSize) {
                            compSize = buf.getLong(fieldOff)
                        }
                        break
                    }
                    ePos += 4 + dataSize
                }
            }
            result += ZipEntryInfo(
                name = name,
                isDirectory = name.endsWith("/"),
                compressedSize = compSize,
                uncompressedSize = uncompSize,
                isEncrypted = (gpFlag and 0x0001) != 0,
            )
            pos = nextPos
        }
        return result
    }

    /**
     * Convenience wrapper that parses all Central Directory entries directly from [tailBytes]
     * when the Central Directory fits inside that tail slice; returns `null` if a larger
     * fetch at the returned [findCentralDirectoryRange] is needed.
     */
    fun parseCentralDirectoryFromTail(
        tailBytes: ByteArray,
        tailFileOffset: Long,
    ): List<ZipEntryInfo>? {
        val range = findCentralDirectoryRange(tailBytes, tailFileOffset) ?: return null
        val relStart = range.offset - tailFileOffset
        if (relStart < 0L || relStart + range.size > tailBytes.size) return null
        val slice = tailBytes.copyOfRange(relStart.toInt(), (relStart + range.size).toInt())
        return parseCentralDirectoryEntries(slice)
    }

    /**
     * Reads the bytes of up to [maxCount] unencrypted image entries in [zipPath]
     * (for single-cover or 2x2 collage ZIP thumbnails).
     */
    fun readPreviewImageEntriesBytes(
        zipPath: String,
        maxCount: Int = 4,
        maxBytesPerEntry: Int = 10 * 1024 * 1024,
        password: String? = null,
    ): List<ByteArray> {
        if (maxCount <= 0) return emptyList()
        return try {
            val zipFile = File(zipPath)
            if (!zipFile.isFile) return emptyList()
            val allowEncrypted = !password.isNullOrBlank()
            val openZip = if (allowEncrypted) {
                ZipFile(zipFile, password!!.toCharArray())
            } else {
                ZipFile(zipFile)
            }
            openZip.use { zip ->
                val headers = zip.fileHeaders.filter { h ->
                    !h.isDirectory &&
                        (!h.isEncrypted || allowEncrypted) &&
                        (h.uncompressedSize <= 0L || h.uncompressedSize <= maxBytesPerEntry.toLong()) &&
                        h.fileName.substringAfterLast('.', "").lowercase() in IMAGE_COVER_EXTS
                }.take(maxCount)
                val outList = ArrayList<ByteArray>(headers.size)
                for (header in headers) {
                    val bytes = zip.getInputStream(header).use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        var total = 0
                        var exceeded = false
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > maxBytesPerEntry) {
                                exceeded = true
                                break
                            }
                            out.write(buf, 0, n)
                        }
                        if (exceeded) null else out.toByteArray()
                    }
                    if (bytes != null && bytes.isNotEmpty()) {
                        outList.add(bytes)
                    }
                }
                outList
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Reads the bytes of the first unencrypted image entry in [zipPath] (for ZIP cover thumbnails).
     */
    fun readFirstImageEntryBytes(
        zipPath: String,
        maxBytes: Int = 10 * 1024 * 1024,
    ): ByteArray? = readPreviewImageEntriesBytes(zipPath, maxCount = 1, maxBytesPerEntry = maxBytes).firstOrNull()

    /**
     * Packs an arbitrary list of SAF [uris] into a single [outputFile] ZIP archive
     * (optionally AES-256 encrypted when [password] is non-blank).
     * Returns the list of entry names written into the archive.
     */
    fun zipUrisToFile(
        context: Context,
        uris: List<Uri>,
        outputFile: File,
        password: String? = null,
        compressionMode: ZipCompressionMode = ZipCompressionMode.SMART,
    ): List<String> {
        require(uris.isNotEmpty()) { "At least one URI is required" }
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()
        val encrypted = !password.isNullOrBlank()
        val written = mutableListOf<String>()
        val usedNames = HashSet<String>()
        try {
            val zos = if (encrypted) {
                Zip4jOutputStream(BufferedOutputStream(FileOutputStream(outputFile)), password!!.toCharArray())
            } else {
                Zip4jOutputStream(BufferedOutputStream(FileOutputStream(outputFile)))
            }
            zos.use { stream ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                uris.forEachIndexed { idx, uri ->
                    val rawName = resolveUriDisplayName(context, uri) ?: "file_${idx + 1}"
                    val entryName = deduplicateEntryName(sanitizeZipBaseName(rawName), usedNames)
                    val entrySize = resolveUriSize(context, uri)
                    val params = buildZipParameters(entryName, encrypted, compressionMode, entrySize)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        stream.putNextEntry(params)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            stream.write(buffer, 0, n)
                        }
                        stream.closeEntry()
                        written += entryName
                    } ?: throw IllegalStateException("Cannot open URI: $uri")
                }
            }
            return written
        } catch (t: Throwable) {
            runCatching { outputFile.delete() }
            throw t
        }
    }

    private fun resolveUriDisplayName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = c.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun resolveUriSize(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) {
                        val size = c.getLong(idx)
                        if (size >= 0L) return size
                    }
                }
            }
        }
        return 0L
    }

    private fun deduplicateEntryName(baseName: String, used: MutableSet<String>): String {
        val clean = baseName.ifBlank { "file" }
        if (used.add(clean)) return clean
        val dot = clean.lastIndexOf('.')
        val stem = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "${stem}_$i$ext"
            if (used.add(candidate)) return candidate
            i++
        }
    }

    private val IMAGE_COVER_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "gif")
}

data class ZipCentralDirectoryRange(
    val offset: Long,
    val size: Long,
)

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
