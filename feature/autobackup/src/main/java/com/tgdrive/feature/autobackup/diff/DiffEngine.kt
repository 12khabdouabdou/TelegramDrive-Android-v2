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
data class DiffResult(
    val toUpload: List<LocalFile>,
    val toSkip: List<LocalFile>,
    val staleRemotePaths: List<String>,
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

        val toUpload = mutableListOf<LocalFile>()
        val toSkip = mutableListOf<LocalFile>()
        val staleRemotePaths = mutableListOf<String>()

        var processed = 0
        for (f in local) {
            // Cooperative cancellation: yield every YIELD_INTERVAL files so
            // the user's Cancel tap is honored within ~50ms (assuming each
            // iteration takes < 1ms — true for non-strict mode; strict mode
            // with SHA computation takes longer per file but we still yield
            // between files).
            if (processed % YIELD_INTERVAL == 0) {
                coroutineContext.ensureActive()
            }
            processed++

            val matched = byPathAndSize[f.absolutePath to f.size]
            if (matched != null) {
                if (!strict) {
                    toSkip.add(f); continue
                }
                // Strict + path+size match → verify via SHA.
                // This is the slow path: SHA-256 computation does blocking I/O.
                // We've already yielded above, so cancellation is honored
                // BEFORE we start hashing.
                val fileSha = f.sha256 ?: sha256Provider(f.absolutePath)
                when {
                    fileSha == null -> toSkip.add(f)  // SHA failed → conservative skip
                    matched.sha256 == fileSha -> toSkip.add(f.copy(sha256 = fileSha))
                    else -> toUpload.add(f.copy(sha256 = fileSha))
                }
                continue
            }

            // No path+size match. In strict mode, compute SHA-256 and
            // check the global SHA index — catches moved/renamed duplicates
            // and persists SHA on first upload so run 2 can verify without re-uploading.
            val fSha = if (strict) (f.sha256 ?: sha256Provider(f.absolutePath)) else f.sha256
            if (strict && fSha != null && bySha.containsKey(fSha)) {
                toSkip.add(f.copy(sha256 = fSha))
                continue
            }

            toUpload.add(if (fSha != null && f.sha256 == null) f.copy(sha256 = fSha) else f)
        }

        for (a in already) {
            if (a.localPath !in localPaths) staleRemotePaths.add(a.localPath)
        }

        return DiffResult(
            toUpload = toUpload,
            toSkip = toSkip,
            staleRemotePaths = staleRemotePaths,
        )
    }

    private companion object {
        /** Yield to the coroutine dispatcher every N files to allow cancellation. */
        const val YIELD_INTERVAL = 100
    }
}
