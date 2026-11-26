package com.tgdrive.core.download

import android.content.Context
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.PublicDownloadsExporter
import com.tgdrive.core.db.dao.FileCacheDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages files pinned for permanent offline access in `filesDir/offline_pins/`.
 * Unlike `cacheDir/downloads`, pinned files are preserved across temporary cache clears.
 */
@Singleton
class OfflinePinStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val fileCacheDao: FileCacheDao,
    private val dispatchers: DispatchersProvider,
) {
    private val pinsDir: File
        get() = File(ctx.filesDir, "offline_pins").apply { mkdirs() }

    private fun prefixFor(chatId: Long, messageId: Long): String = "${chatId}_${messageId}_"

    private fun sanitizeFileName(raw: String): String =
        PublicDownloadsExporter.sanitizeFileName(raw, fallback = "file").take(120)

    /** Fast synchronous lookup for an existing offline-pinned copy on disk. */
    fun getPinnedFilePathSync(chatId: Long, messageId: Long): String? {
        if (chatId == 0L || messageId == 0L) return null
        val prefix = prefixFor(chatId, messageId)
        val match = pinsDir.listFiles()?.firstOrNull { f ->
            f.isFile && f.name.startsWith(prefix) && !f.name.endsWith(".tmp") && f.length() > 0L
        }
        return match?.absolutePath
    }

    /** Copy [sourcePath] into persistent offline storage and mark the DB row pinned. */
    suspend fun pinFile(
        chatId: Long,
        messageId: Long,
        sourcePath: String,
        fileName: String,
    ): String? = withContext(dispatchers.io) {
        val src = File(sourcePath)
        if (!src.isFile || src.length() <= 0L) return@withContext null
        val dir = pinsDir
        val prefix = prefixFor(chatId, messageId)
        val safeName = sanitizeFileName(fileName)
        val target = File(dir, "$prefix$safeName")
        val tmp = File(dir, "$prefix$safeName.tmp")
        try {
            dir.listFiles()?.forEach { existing ->
                if (existing.isFile && existing.name.startsWith(prefix) && existing.absolutePath != target.absolutePath) {
                    existing.delete()
                }
            }
            if (target.absolutePath != src.absolutePath) {
                src.inputStream().use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            fileCacheDao.setPinnedOffline(chatId, messageId, true)
            Log.i("OfflinePinStore: pinned ($chatId/$messageId) → ${target.absolutePath}")
            target.absolutePath
        } catch (t: Throwable) {
            tmp.delete()
            Log.w("OfflinePinStore: pinFile failed", t)
            null
        }
    }

    /** Remove any pinned offline copy and clear the DB flag. */
    suspend fun unpinFile(chatId: Long, messageId: Long) = withContext(dispatchers.io) {
        val prefix = prefixFor(chatId, messageId)
        pinsDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(prefix)) {
                runCatching { f.delete() }
            }
        }
        runCatching { fileCacheDao.setPinnedOffline(chatId, messageId, false) }
    }

    /** Remove all offline-pinned files belonging to [chatId]. */
    suspend fun unpinChat(chatId: Long) = withContext(dispatchers.io) {
        val chatPrefix = "${chatId}_"
        pinsDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith(chatPrefix)) {
                runCatching { f.delete() }
            }
        }
    }

    /** Return `(count, totalBytes)` of all pinned files on disk. */
    suspend fun stats(): Pair<Int, Long> = withContext(dispatchers.io) {
        val files = pinsDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: emptyList()
        files.size to files.sumOf { it.length() }
    }

    /** Remove all offline-pinned files and clear DB pin flags (used on logout or explicit clear). */
    suspend fun clearAll() = withContext(dispatchers.io) {
        val pinnedRows = runCatching { fileCacheDao.getPinnedOffline() }.getOrDefault(emptyList())
        pinnedRows.forEach { row ->
            runCatching { fileCacheDao.setPinnedOffline(row.chatId, row.messageId, false) }
        }
        pinsDir.listFiles()?.forEach { f -> runCatching { f.delete() } }
    }
}
