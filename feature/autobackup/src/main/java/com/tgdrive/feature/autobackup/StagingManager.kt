package com.tgdrive.feature.autobackup

import android.content.Context
import android.net.Uri
import com.tgdrive.core.common.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralizes the lifecycle of "staging" files — temporary copies of SAF
 * documents that TDLib needs (TDLib requires a real filesystem path, not a
 * content:// URI).
 *
 * Previously, [FolderScanner.copyToStaging] created staging files with
 * UUID-based names and only cleaned them up on success. If a backup was
 * cancelled mid-run, the staging directory grew unboundedly.
 *
 * Now:
 *   - [stage] creates a staging file.
 *   - [release] deletes it (called in a `finally` block per file).
 *   - [purgeStale] is called at the START of every run, deleting anything
 *     older than 1 hour — picking up files left behind by a crashed run.
 */
@Singleton
class StagingManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    private val stagingDir: File by lazy {
        File(ctx.cacheDir, "backup_staging").apply { mkdirs() }
    }

    /**
     * Copy a SAF document to a unique staging file. Returns the absolute
     * path, or null on failure (e.g. URI no longer accessible).
     */
    fun stage(uriStr: String, displayName: String): String? {
        val uri = try { Uri.parse(uriStr) } catch (_: Throwable) { return null }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        val target = File(stagingDir, "${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}_$safeName")
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: run {
                Log.w("StagingManager: openInputStream returned null for '$displayName'")
                return null
            }
            input.use { src ->
                FileOutputStream(target).use { dst -> src.copyTo(dst) }
            }
            target.absolutePath
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) {
            Log.w("StagingManager: stage failed for '$displayName'", t)
            runCatching { target.delete() }
            null
        }
    }

    /** Delete a staging file. Safe to call with null or non-existent path. */
    fun release(path: String?) {
        if (path == null) return
        runCatching { File(path).delete() }
    }

    /**
     * Delete all staging files older than [maxAgeMs]. Called at the start
     * of every backup run to clean up files left behind by crashed runs.
     */
    fun purgeStale(maxAgeMs: Long = 60L * 60 * 1000) {
        if (!stagingDir.exists()) return
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var purged = 0
        stagingDir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) {
                if (f.delete()) purged++
            }
        }
        if (purged > 0) Log.i("StagingManager: purged $purged stale staging file(s)")
    }

    /** Delete every staging file. Called when the user disables AutoBackup. */
    fun purgeAll() {
        if (!stagingDir.exists()) return
        var purged = 0
        stagingDir.listFiles()?.forEach { f ->
            if (f.isFile && f.delete()) purged++
        }
        if (purged > 0) Log.i("StagingManager: purged all ($purged) staging file(s)")
    }
}
