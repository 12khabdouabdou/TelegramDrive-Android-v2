package com.tgdrive.feature.autobackup.diff

import android.content.Context
import android.net.Uri
import com.tgdrive.core.common.Log
import com.tgdrive.core.db.entity.SyncedFileEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Input for the diff engine — one entry per file discovered on the device.
 */
data class LocalFile(
    val absolutePath: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val sha256: String? = null,  // populated lazily by DiffEngine when strict mode is on
    val relativePath: String = "",
)

/**
 * Output of the diff engine.
 *
 * - [toUpload]: files that need to be uploaded (no match in the synced-files registry).
 * - [toSkip]: files that match an existing synced-file record (by path+size, or
 *   by SHA-256 if strict mode is on).
 * - [staleRemotePaths]: synced-file records whose local path was not present in
 *   the scanned set. The caller can delete these rows so the registry doesn't
 *   grow unboundedly with deleted local files.
 */
data class MovedLocalFile(
    val localFile: LocalFile,
    val previousRecord: SyncedFileEntity,
)

data class DiffResult(
    val toUpload: List<LocalFile>,
    val toSkip: List<LocalFile>,
    val staleRemotePaths: List<String>,
    val movedFiles: List<MovedLocalFile> = emptyList(),
)

/**
 * SHA-256 calculator. Injected by Hilt in production.
 *
 * Computes the hash by streaming the file in 64KB chunks — never loads the
 * whole file into memory.
 *
 * **Thread safety:** Each call to [compute] allocates its own chunk buffer
 * (64KB) on the stack/JVM heap. The class itself holds no mutable state,
 * so concurrent calls are safe. Previously the buffer was a shared field,
 * which caused data corruption (and wrong hashes) if two coroutines ever
 * called `compute()` simultaneously.
 */
@Singleton
class Sha256Calculator @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    /** Returns the hex-encoded SHA-256 of the file at [uriStr], or null on I/O error. */
    suspend fun compute(uriStr: String): String? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriStr)
            // Allocate a LOCAL buffer per call — never share across calls.
            // 64KB is small enough that per-call allocation is negligible,
            // and it eliminates the race condition that existed when the
            // buffer was a shared field.
            val chunk = ByteArray(64 * 1024)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val md = MessageDigest.getInstance("SHA-256")
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(chunk)
                    if (n <= 0) break
                    md.update(chunk, 0, n)
                }
                md.digest().toHex()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) {
            Log.w("Sha256Calculator: failed for '$uriStr'", t)
            null
        }
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(HEX[(b.toInt() ushr 4) and 0x0F])
            sb.append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
    }
}

/**
 * Compares local files against the synced-files registry to decide what
 * needs uploading.
 *
 * ## Default strategy
 *
 * SKIP if filename + size match an existing synced file.
 *
 * ## Strict mode
 *
 * Additionally compare SHA-256. The hash is computed LAZILY, only for files
 * whose path+size matches an existing record (so we don't hash every file
 * in a 50k-file library — only the ones that look like duplicates).
 *
 * Also, in strict mode, if a [LocalFile] already has a precomputed
 * [LocalFile.sha256] (e.g. from a previous run), we check it against all
 * existing synced records — this catches the case where the user moved
 * a file (path changed but content is identical to something already uploaded).
 *
 * Previously the strict-mode flag was silently a no-op: the scanner never
 * populated `LocalFile.sha256`, so the strict branch never matched. Now
 * DiffEngine owns the SHA computation via [sha256Provider].
 *
 * ## Cancellation
 *
 * [compute] is a `suspend` function that calls `ensureActive()` every
 * [YIELD_INTERVAL] files. This is critical: previously `compute()` was
 * a blocking function that ran for seconds (strict mode + many matches)
 * without yielding, which meant the user's "Cancel" tap was ignored
 * until the diff completed, and the WorkManager thread was blocked.
 *
 * ## Construction
 *
 * In production, Hilt provides a singleton DiffEngine via [AutoBackupModule].
 * In tests, construct `DiffEngine { _ -> null }` (or with a fake provider)
 * and call `compute()` inside `runTest { ... }`.
 *
 * @param sha256Provider A function that computes the SHA-256 of a SAF URI,
 *   returning null on failure. Defaults to a no-op (returns null), which
 *   effectively disables strict-mode verification — useful in tests.
 */
class DiffEngine(
    private val sha256Provider: suspend (String) -> String? = { _ -> null },
) {

    suspend fun compute(
        local: List<LocalFile>,
        already: List<SyncedFileEntity>,
        strict: Boolean = false,
    ): DiffResult {
        // Index existing synced records by (path, size) — the primary match key.
        val byPathAndSize = HashMap<Pair<String, Long>, SyncedFileEntity>(already.size)
        val bySha = HashMap<String, SyncedFileEntity>()
        for (a in already) {
            byPathAndSize[a.localPath to a.size] = a
            val aSha = a.sha256
            if (aSha != null) bySha[aSha] = a
        }

        val localPaths = HashSet<String>(local.size)
        for (l in local) localPaths.add(l.absolutePath)

        // Pre-index stale records by (leafName, size, lastModified) for safe non-strict move detection.
        val staleByMeta = HashMap<Triple<String, Long, Long>, MutableList<SyncedFileEntity>>()
        for (a in already) {
            if (a.localPath !in localPaths && a.lastModified > 0L) {
                val leaf = extractLeafName(a.localPath).lowercase()
                if (leaf.isNotEmpty()) {
                    staleByMeta.getOrPut(Triple(leaf, a.size, a.lastModified)) { ArrayList(1) }.add(a)
                }
            }
        }
        val unmatchedLocalMetaCounts = HashMap<Triple<String, Long, Long>, Int>()
        for (f in local) {
            if (byPathAndSize[f.absolutePath to f.size] == null && f.lastModified > 0L) {
                val key = Triple(f.name.lowercase(), f.size, f.lastModified)
                unmatchedLocalMetaCounts[key] = (unmatchedLocalMetaCounts[key] ?: 0) + 1
            }
        }

        val toUpload = mutableListOf<LocalFile>()
        val toSkip = mutableListOf<LocalFile>()
        val staleRemotePaths = mutableListOf<String>()
        val movedFiles = mutableListOf<MovedLocalFile>()
        val matchedMovedOldPaths = HashSet<String>()

        var processed = 0
        for (f in local) {
            if (processed % YIELD_INTERVAL == 0) {
                coroutineContext.ensureActive()
            }
            processed++

            val matched = byPathAndSize[f.absolutePath to f.size]
            if (matched != null) {
                if (!strict) {
                    val mtimeChanged =
                        matched.lastModified > 0L &&
                            f.lastModified > 0L &&
                            kotlin.math.abs(f.lastModified - matched.lastModified) > MTIME_TOLERANCE_MS
                    if (mtimeChanged) {
                        toUpload.add(f)
                    } else {
                        toSkip.add(f)
                    }
                    continue
                }
                // Strict mode fast path: when recorded mtime & sha256 match unchanged file, skip hashing.
                val mtimeStable = matched.sha256 != null &&
                    matched.lastModified > 0L &&
                    f.lastModified > 0L &&
                    kotlin.math.abs(f.lastModified - matched.lastModified) <= MTIME_TOLERANCE_MS
                if (mtimeStable) {
                    toSkip.add(f.copy(sha256 = matched.sha256))
                    continue
                }
                val fileSha = f.sha256 ?: sha256Provider(f.absolutePath)
                when {
                    fileSha == null -> toSkip.add(f)  // SHA failed → conservative skip
                    matched.sha256 == fileSha -> toSkip.add(f.copy(sha256 = fileSha))
                    else -> toUpload.add(f.copy(sha256 = fileSha))
                }
                continue
            }

            // No path+size match. In strict mode, compute SHA-256 and
            // check the global SHA index — catches moved/renamed duplicates.
            val fSha = if (strict) (f.sha256 ?: sha256Provider(f.absolutePath)) else f.sha256
            val shaMatch = if (strict && fSha != null) bySha[fSha] else null
            if (shaMatch != null) {
                val updatedLocal = f.copy(sha256 = fSha)
                toSkip.add(updatedLocal)
                if (shaMatch.localPath !in localPaths && matchedMovedOldPaths.add(shaMatch.localPath)) {
                    movedFiles.add(MovedLocalFile(updatedLocal, shaMatch))
                }
                continue
            }

            // Non-strict move detection: unique (leafName, size, lastModified) match among disappeared files.
            if (!strict && f.lastModified > 0L) {
                val moveKey = Triple(f.name.lowercase(), f.size, f.lastModified)
                val staleCandidates = staleByMeta[moveKey]
                if (unmatchedLocalMetaCounts[moveKey] == 1 &&
                    staleCandidates != null &&
                    staleCandidates.size == 1 &&
                    matchedMovedOldPaths.add(staleCandidates[0].localPath)
                ) {
                    movedFiles.add(MovedLocalFile(f, staleCandidates[0]))
                    toSkip.add(f)
                    continue
                }
            }

            toUpload.add(if (fSha != null && f.sha256 == null) f.copy(sha256 = fSha) else f)
        }

        for (a in already) {
            if (a.localPath !in localPaths && a.localPath !in matchedMovedOldPaths) {
                staleRemotePaths.add(a.localPath)
            }
        }

        return DiffResult(
            toUpload = toUpload,
            toSkip = toSkip,
            staleRemotePaths = staleRemotePaths,
            movedFiles = movedFiles,
        )
    }

    private fun extractLeafName(rawPath: String): String {
        val decoded = try {
            java.net.URLDecoder.decode(rawPath, "UTF-8")
        } catch (_: Throwable) {
            rawPath
        }
        return decoded.substringAfterLast('/').substringAfterLast(':').trim()
    }

    private companion object {
        /** Yield to the coroutine dispatcher every N files to allow cancellation. */
        const val YIELD_INTERVAL = 100
        const val MTIME_TOLERANCE_MS = 2_000L
    }
}
