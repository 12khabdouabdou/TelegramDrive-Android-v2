package com.tgdrive.feature.autobackup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.tgdrive.core.common.Log
import com.tgdrive.feature.autobackup.diff.LocalFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

/** Detailed result of scanning one SAF folder tree. */
data class FolderScanResult(
    val files: List<LocalFile>,
    val folderName: String = "",
    val permissionLost: Boolean = false,
    val scanError: String? = null,
    val hitLimit: Boolean = false,
)

/**
 * Walks SAF (Storage Access Framework) document trees using a batched
 * `DocumentsContract` child cursor projection (one query per directory) so
 * large folders scan dramatically faster than per-file `DocumentFile` wrappers.
 *
 * Also checks persisted URI read permissions up front so permission revocation
 * reports [FolderScanResult.permissionLost] = true instead of silently returning
 * an empty file list and triggering stale-row deletion.
 */
@Singleton
class FolderScanner @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    companion object {
        const val MAX_DEPTH = 15
        const val MAX_FILES = 50_000
        const val YIELD_INTERVAL = 50

        private val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }

    suspend fun scan(
        folderUri: String,
        onProgress: (filesFound: Int) -> Unit = {},
    ): List<LocalFile> = scanDetailed(folderUri, emptyList(), onProgress).files

    suspend fun scanDetailed(
        folderUri: String,
        excludePatterns: List<String> = emptyList(),
        onProgress: (filesFound: Int) -> Unit = {},
    ): FolderScanResult = withContext(Dispatchers.IO) {
        Log.i("FolderScanner: scanning '$folderUri'")
        val uri = runCatching { Uri.parse(folderUri) }.getOrNull() ?: return@withContext FolderScanResult(
            files = emptyList(),
            scanError = "Invalid folder URI",
        )

        val persisted = runCatching {
            ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }.getOrDefault(true)
        if (!persisted) {
            Log.w("FolderScanner: persisted read permission missing for $folderUri")
            return@withContext FolderScanResult(
                files = emptyList(),
                permissionLost = true,
                scanError = "Folder permission lost",
            )
        }

        val rootDoc = try {
            DocumentFile.fromTreeUri(ctx, uri)
        } catch (se: SecurityException) {
            Log.w("FolderScanner: security exception opening $folderUri", se)
            return@withContext FolderScanResult(
                files = emptyList(),
                permissionLost = true,
                scanError = "Folder permission lost",
            )
        } catch (t: Throwable) {
            Log.w("FolderScanner: fromTreeUri threw for $folderUri", t)
            return@withContext FolderScanResult(
                files = emptyList(),
                scanError = t.message ?: "Failed to open folder",
            )
        }

        val folderName = rootDoc?.name
            ?: Uri.decode(folderUri).substringAfterLast(':').substringAfterLast('/').ifBlank { "Backup" }

        if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory || !rootDoc.canRead()) {
            Log.w("FolderScanner: root unreadable for $folderUri (permission revoked?)")
            return@withContext FolderScanResult(
                files = emptyList(),
                folderName = folderName,
                permissionLost = true,
                scanError = "Folder unreadable or permission lost",
            )
        }

        val results = ArrayList<LocalFile>(256)
        val scanLock = Any()
        val dirSemaphore = Semaphore(4)
        var dirCount = 0
        var fileCount = 0
        var skippedCount = 0
        var errorCount = 0
        var hitLimit = false
        var scanError: String? = null
        var permissionLost = false

        suspend fun walkWithCursor(treeUri: Uri, docId: String, relPath: String, depth: Int): Boolean {
            if (depth > MAX_DEPTH) return true
            synchronized(scanLock) {
                if (results.size >= MAX_FILES) {
                    hitLimit = true
                    return true
                }
            }

            val subDirs = ArrayList<Pair<String, String>>()
            val localFiles = ArrayList<LocalFile>()
            var localDirs = 0
            var localScannedFiles = 0
            var localSkipped = 0
            var limitReachedHere = false

            val queryWorked = dirSemaphore.withPermit {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val cursor = try {
                    ctx.contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
                } catch (se: SecurityException) {
                    synchronized(scanLock) {
                        if (depth == 0) {
                            permissionLost = true
                            scanError = "Folder permission lost"
                        } else {
                            errorCount++
                        }
                    }
                    return@withPermit true
                } catch (t: Throwable) {
                    Log.w("FolderScanner: cursor query failed at depth $depth", t)
                    synchronized(scanLock) {
                        if (depth == 0) scanError = t.message ?: "Cursor query failed"
                    }
                    return@withPermit false
                } ?: return@withPermit false

                cursor.use { c ->
                    val idxId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val idxName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val idxMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val idxSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val idxMtime = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    while (c.moveToNext()) {
                        if (localScannedFiles % YIELD_INTERVAL == 0) {
                            yield()
                            val countNow = synchronized(scanLock) { results.size + localFiles.size }
                            onProgress(countNow)
                        }
                        val totalNow = synchronized(scanLock) { results.size + localFiles.size }
                        if (totalNow >= MAX_FILES) {
                            limitReachedHere = true
                            break
                        }

                        val childId = if (idxId >= 0) c.getString(idxId) else null
                        val name = if (idxName >= 0) c.getString(idxName) else null
                        val mime = (if (idxMime >= 0) c.getString(idxMime) else null) ?: "application/octet-stream"
                        if (childId.isNullOrBlank() || name.isNullOrBlank()) continue

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (ExcludePatternMatcher.matchesDirectory(name, excludePatterns)) {
                                continue
                            }
                            localDirs++
                            val childRel = if (relPath.isEmpty()) name else "$relPath/$name"
                            subDirs.add(childId to childRel)
                        } else {
                            localScannedFiles++
                            val size = if (idxSize >= 0 && !c.isNull(idxSize)) c.getLong(idxSize) else -1L
                            if (size <= 0L) {
                                localSkipped++
                                continue
                            }
                            if (ExcludePatternMatcher.matchesPath(relPath, name, excludePatterns)) {
                                localSkipped++
                                continue
                            }
                            val lastModified = if (idxMtime >= 0 && !c.isNull(idxMtime)) c.getLong(idxMtime) else 0L
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            localFiles.add(
                                LocalFile(
                                    absolutePath = docUri.toString(),
                                    name = name,
                                    size = size,
                                    lastModified = lastModified,
                                    mimeType = mime,
                                    sha256 = null,
                                    relativePath = relPath,
                                ),
                            )
                        }
                    }
                }
                true
            }

            if (!queryWorked) return false

            synchronized(scanLock) {
                dirCount += localDirs
                fileCount += localScannedFiles
                skippedCount += localSkipped
                if (limitReachedHere) hitLimit = true
                val remaining = (MAX_FILES - results.size).coerceAtLeast(0)
                if (localFiles.size <= remaining) {
                    results.addAll(localFiles)
                } else {
                    results.addAll(localFiles.take(remaining))
                    hitLimit = true
                }
            }

            if (subDirs.isNotEmpty()) {
                coroutineScope {
                    subDirs.map { (subId, subRel) ->
                        async {
                            val atLimit = synchronized(scanLock) { results.size >= MAX_FILES }
                            if (atLimit) {
                                synchronized(scanLock) { hitLimit = true }
                            } else {
                                walkWithCursor(treeUri, subId, subRel, depth + 1)
                            }
                        }
                    }.awaitAll()
                }
            }
            return true
        }

        suspend fun walkFallbackDocumentFile(dir: DocumentFile, relPath: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (results.size >= MAX_FILES) {
                hitLimit = true
                return
            }
            val children = try {
                dir.listFiles()
            } catch (se: SecurityException) {
                if (depth == 0) {
                    permissionLost = true
                    scanError = "Folder permission lost"
                }
                return
            } catch (t: Throwable) {
                Log.w("FolderScanner: listFiles failed at depth $depth", t)
                if (depth == 0) scanError = t.message ?: "Scan failed"
                return
            }

            for (child in children) {
                if (fileCount % YIELD_INTERVAL == 0) {
                    yield()
                    onProgress(results.size)
                }
                if (results.size >= MAX_FILES) {
                    hitLimit = true
                    return
                }
                try {
                    if (child.isDirectory) {
                        val subName = child.name ?: continue
                        if (ExcludePatternMatcher.matchesDirectory(subName, excludePatterns)) continue
                        dirCount++
                        val childRel = if (relPath.isEmpty()) subName else "$relPath/$subName"
                        walkFallbackDocumentFile(child, childRel, depth + 1)
                    } else if (child.isFile) {
                        fileCount++
                        val name = child.name ?: continue
                        val size = child.length()
                        if (size <= 0L) {
                            skippedCount++
                            continue
                        }
                        if (ExcludePatternMatcher.matchesPath(relPath, name, excludePatterns)) {
                            skippedCount++
                            continue
                        }
                        results.add(
                            LocalFile(
                                absolutePath = child.uri.toString(),
                                name = name,
                                size = size,
                                lastModified = child.lastModified(),
                                mimeType = child.type ?: "application/octet-stream",
                                sha256 = null,
                                relativePath = relPath,
                            ),
                        )
                    }
                } catch (_: Throwable) {
                    errorCount++
                }
            }
        }

        try {
            val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            val cursorWorked = rootDocId != null && walkWithCursor(uri, rootDocId, "", 0)
            if (!cursorWorked && !permissionLost) {
                results.clear()
                dirCount = 0
                fileCount = 0
                skippedCount = 0
                walkFallbackDocumentFile(rootDoc, "", 0)
            }
        } catch (t: CancellationException) {
            Log.i("FolderScanner: scan cancelled after ${results.size} files")
            throw t
        } catch (t: OutOfMemoryError) {
            Log.w("FolderScanner: OOM during walk — returning ${results.size} files scanned so far", t)
        } catch (t: Throwable) {
            Log.w("FolderScanner: walk failed", t)
            if (scanError == null) scanError = t.message ?: "Walk failed"
        }

        if (hitLimit) {
            Log.w("FolderScanner: hit MAX_FILES limit ($MAX_FILES) — further files ignored.")
        }

        Log.i(
            "FolderScanner: scan complete — $fileCount files, $dirCount dirs, " +
                "${results.size} recorded, $skippedCount skipped, $errorCount errors",
        )
        FolderScanResult(
            files = results,
            folderName = folderName,
            permissionLost = permissionLost,
            scanError = scanError,
            hitLimit = hitLimit,
        )
    }
}
