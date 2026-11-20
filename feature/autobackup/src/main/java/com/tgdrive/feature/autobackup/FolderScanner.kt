package com.tgdrive.feature.autobackup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tgdrive.core.common.Log
import com.tgdrive.feature.autobackup.diff.LocalFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walks SAF (Storage Access Framework) document trees to enumerate files
 * that need to be backed up.
 *
 * ## Critical performance notes
 *
 * SAF operations (`listFiles()`, `isDirectory`, `isFile`, `name`, `length()`,
 * `lastModified()`, `type`) are ALL blocking IPC calls to the SAF provider
 * (usually a separate process). For a folder with 5,000 files, that's
 * 25,000+ cross-process round-trips. On a slow device, this can take
 * 5–10 minutes.
 *
 * To prevent the UI from freezing:
 *
 * 1. The entire scan runs on [Dispatchers.IO] via [withContext], so the
 *    blocking IPC calls don't starve the Default dispatcher.
 * 2. We call [yield] every [YIELD_INTERVAL] files. Unlike `ensureActive()`,
 *    `yield` actually SUSPENDS — letting the UI thread collect StateFlow
 *    updates and show progress.
 * 3. A [onProgress] callback fires every [YIELD_INTERVAL] files so the
 *    orchestrator can update the [BackupPhase.Scanning] phase with the
 *    current file count.
 *
 * ## Cancellation
 *
 * `yield` also checks for cancellation, so the scan exits within ~50ms of
 * the user tapping Cancel.
 */
@Singleton
class FolderScanner @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    companion object {
        const val MAX_DEPTH = 15
        const val MAX_FILES = 50_000
        const val YIELD_INTERVAL = 50
    }

    /**
     * Walk [folderUri] recursively and return all readable files.
     *
     * @param onProgress Called every [YIELD_INTERVAL] files with the current
     *   file count and folder index. The caller can use this to update the
     *   UI phase so the user sees activity during a long scan.
     */
    suspend fun scan(
        folderUri: String,
        onProgress: (filesFound: Int) -> Unit = {},
    ): List<LocalFile> = withContext(Dispatchers.IO) {
        Log.i("FolderScanner: scanning '$folderUri'")
        val uri = try { Uri.parse(folderUri) } catch (t: Throwable) {
            Log.w("FolderScanner: failed to parse URI", t)
            return@withContext emptyList()
        }

        val root = try {
            DocumentFile.fromTreeUri(ctx, uri)
        } catch (t: Throwable) {
            Log.w("FolderScanner: fromTreeUri threw", t)
            return@withContext emptyList()
        } ?: run {
            Log.w("FolderScanner: fromTreeUri returned null")
            return@withContext emptyList()
        }
        if (!root.isDirectory) {
            Log.w("FolderScanner: root is not a directory")
            return@withContext emptyList()
        }
        if (!root.canRead()) {
            Log.w("FolderScanner: root is not readable (permission revoked?)")
            return@withContext emptyList()
        }

        val results = ArrayList<LocalFile>(256)
        var dirCount = 0
        var fileCount = 0
        var skippedCount = 0
        var errorCount = 0
        var hitLimit = false

        suspend fun walk(dir: DocumentFile, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (results.size >= MAX_FILES) { hitLimit = true; return }

            val children = try {
                dir.listFiles()
            } catch (t: Throwable) {
                Log.w("FolderScanner: listFiles failed at depth $depth", t)
                return
            }

            for (child in children) {
                // Cooperative cancellation + yield: `yield()` both checks
                // for cancellation AND suspends, letting the UI thread
                // collect StateFlow updates. `ensureActive()` does NOT
                // suspend — it only checks cancellation — which is why
                // the old code froze the UI.
                if (fileCount % YIELD_INTERVAL == 0) {
                    yield()
                    onProgress(results.size)
                }

                if (results.size >= MAX_FILES) { hitLimit = true; return }

                try {
                    if (child.isDirectory) {
                        dirCount++
                        walk(child, depth + 1)
                    } else if (child.isFile) {
                        fileCount++
                        val name = child.name ?: continue
                        val size = child.length()
                        if (size <= 0) { skippedCount++; continue }
                        results.add(
                            LocalFile(
                                absolutePath = child.uri.toString(),
                                name = name,
                                size = size,
                                lastModified = child.lastModified(),
                                mimeType = child.type ?: "application/octet-stream",
                                sha256 = null,
                            )
                        )
                    }
                } catch (t: Throwable) {
                    errorCount++
                }
            }
        }

        try {
            walk(root, 0)
        } catch (t: kotlinx.coroutines.CancellationException) {
            Log.i("FolderScanner: scan cancelled after ${results.size} files")
            throw t
        } catch (t: OutOfMemoryError) {
            Log.w("FolderScanner: OOM during walk — returning ${results.size} files scanned so far", t)
        } catch (t: Throwable) {
            Log.w("FolderScanner: walk failed", t)
        }

        if (hitLimit) {
            Log.w("FolderScanner: hit MAX_FILES limit ($MAX_FILES) — further files ignored.")
        }

        Log.i("FolderScanner: scan complete — $fileCount files, $dirCount dirs, ${results.size} recorded, $skippedCount skipped, $errorCount errors")
        results
    }
}
