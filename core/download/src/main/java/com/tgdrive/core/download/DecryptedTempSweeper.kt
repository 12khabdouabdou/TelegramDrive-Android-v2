package com.tgdrive.core.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages automatic TTL expiration and on-demand cleanup of temporary
 * plaintext decrypted (`cacheDir/decrypted/`) and extracted ZIP
 * (`cacheDir/zip_extracts/`) files on disk.
 */
@Singleton
class DecryptedTempSweeper @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val tempDirs: List<File>
        get() = listOf(
            File(ctx.cacheDir, DECRYPTED_DIR),
            File(ctx.cacheDir, ZIP_EXTRACTS_DIR),
        )

    /**
     * Removes expired temporary files from `decrypted/` and `zip_extracts/`.
     * - `ttlMinutes < 0`: manual cleanup only -> no-op.
     * - `ttlMinutes == 0`: immediate cleanup of everything except [excludePaths].
     * - `ttlMinutes > 0`: deletes files whose `lastModified` is older than `ttlMinutes`.
     */
    fun sweepExpired(
        ttlMinutes: Int,
        excludePaths: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        if (ttlMinutes < 0) return 0
        val cutoffMs = if (ttlMinutes == 0) Long.MAX_VALUE else nowMs - ttlMinutes * 60_000L
        val excludedCanonical = excludePaths.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet()
        var removed = 0
        tempDirs.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            removed += sweepDirRecursive(dir, cutoffMs, excludedCanonical, isRoot = true)
        }
        return removed
    }

    fun wipeAll(excludePaths: Set<String> = emptySet()): Int =
        sweepExpired(ttlMinutes = 0, excludePaths = excludePaths)

    /**
     * Safely deletes [path] only if its canonical location resides inside
     * `cacheDir/decrypted/`, preventing accidental deletion of Downloads or Pins.
     */
    fun deleteIfTempDecrypted(path: String): Boolean {
        val decryptedRoot = runCatching { File(ctx.cacheDir, DECRYPTED_DIR).canonicalFile }.getOrNull() ?: return false
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        if (!target.isFile) return false
        val rootPath = decryptedRoot.path.trimEnd(File.separatorChar) + File.separatorChar
        return if (target.path.startsWith(rootPath)) {
            runCatching { target.delete() }.getOrDefault(false)
        } else {
            false
        }
    }

    fun tempCacheStats(): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        fun countTree(file: File) {
            if (file.isFile) {
                count++
                bytes += file.length().coerceAtLeast(0L)
            } else if (file.isDirectory) {
                file.listFiles()?.forEach(::countTree)
            }
        }
        tempDirs.forEach { dir -> if (dir.isDirectory) countTree(dir) }
        return count to bytes
    }

    private fun sweepDirRecursive(
        dir: File,
        cutoffMs: Long,
        excludedCanonical: Set<String>,
        isRoot: Boolean,
    ): Int {
        var removed = 0
        val children = dir.listFiles().orEmpty()
        for (child in children) {
            if (child.isDirectory) {
                removed += sweepDirRecursive(child, cutoffMs, excludedCanonical, isRoot = false)
                if (!child.listFiles().isNullOrEmpty()) continue
                runCatching { child.delete() }
            } else if (child.isFile) {
                val canonical = runCatching { child.canonicalPath }.getOrNull() ?: child.absolutePath
                if (canonical in excludedCanonical) continue
                if (child.lastModified() <= cutoffMs) {
                    if (runCatching { child.delete() }.getOrDefault(false)) {
                        removed++
                    }
                }
            }
        }
        if (!isRoot && dir.listFiles().isNullOrEmpty()) {
            runCatching { dir.delete() }
        }
        return removed
    }

    companion object {
        const val DECRYPTED_DIR = "decrypted"
        const val ZIP_EXTRACTS_DIR = "zip_extracts"
    }
}
