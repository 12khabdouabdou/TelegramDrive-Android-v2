package com.tgdrive.feature.autobackup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.FolderDao
import com.tgdrive.core.db.dao.SyncedFileDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.feature.autobackup.diff.Sha256Calculator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class FreeUpAgeFilter { ALL, OLDER_7_DAYS, OLDER_30_DAYS }
enum class FreeUpMediaFilter { ALL, PHOTOS, VIDEOS, OTHER }

data class ReclaimableFile(
    val id: Long,
    val localPath: String,
    val displayName: String,
    val size: Long,
    val syncedAt: Long,
    val mimeType: String? = null,
    val sha256: String? = null,
    val verifiedInCloud: Boolean = false,
    val thumbnailUri: String? = null,
)

data class FreeUpSpaceUiState(
    val scanning: Boolean = true,
    val cleaning: Boolean = false,
    val allItems: List<ReclaimableFile> = emptyList(),
    val items: List<ReclaimableFile> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val ageFilter: FreeUpAgeFilter = FreeUpAgeFilter.ALL,
    val mediaFilter: FreeUpMediaFilter = FreeUpMediaFilter.ALL,
    val verifyShaBeforeDelete: Boolean = true,
    val skippedModifiedCount: Int = 0,
    val freedCount: Int = 0,
    val freedBytes: Long = 0L,
    val error: String? = null,
)

internal object FreeUpSpacePolicy {
    fun isVerifiedInCloud(
        folderChannelId: Long?,
        telegramMessageId: Long,
        cachedKeys: Set<Pair<Long, Long>>,
        cachedMsgIdsFallback: Set<Long>,
        trashedKeys: Set<Pair<Long, Long>>,
        trashedMsgIdsFallback: Set<Long>,
    ): Boolean {
        if (telegramMessageId == 0L || cachedKeys.isEmpty()) return false
        return if (folderChannelId != null) {
            val key = folderChannelId to telegramMessageId
            key in cachedKeys && key !in trashedKeys
        } else {
            telegramMessageId in cachedMsgIdsFallback && telegramMessageId !in trashedMsgIdsFallback
        }
    }

    fun shouldDeleteAfterShaCheck(
        verifyEnabled: Boolean,
        expectedSha: String?,
        actualSha: String?,
    ): Boolean {
        if (!verifyEnabled || expectedSha.isNullOrBlank()) return true
        return actualSha != null && actualSha.equals(expectedSha, ignoreCase = true)
    }
}

@HiltViewModel
class FreeUpSpaceViewModel @Inject constructor(
    private val syncedFileDao: SyncedFileDao,
    private val folderDao: FolderDao,
    private val fileCacheDao: FileCacheDao,
    private val trashEntryDao: TrashEntryDao,
    private val sha256Calculator: Sha256Calculator,
    private val dispatchers: DispatchersProvider,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(FreeUpSpaceUiState())
    val state: StateFlow<FreeUpSpaceUiState> = _state.asStateFlow()

    init {
        scan()
    }

    fun scan() {
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(scanning = true, error = null) }
            try {
                val found = findReclaimableItems()
                _state.update { cur ->
                    val filtered = applyFilters(found, cur.ageFilter, cur.mediaFilter)
                    cur.copy(
                        scanning = false,
                        allItems = found,
                        items = filtered,
                        selectedIds = filtered.mapTo(HashSet()) { it.id },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("FreeUpSpaceViewModel: scan failed", t)
                _state.update {
                    it.copy(
                        scanning = false,
                        error = t.message ?: "Scan failed",
                    )
                }
            }
        }
    }

    fun setAgeFilter(filter: FreeUpAgeFilter) {
        _state.update { cur ->
            val filtered = applyFilters(cur.allItems, filter, cur.mediaFilter)
            cur.copy(
                ageFilter = filter,
                items = filtered,
                selectedIds = filtered.mapTo(HashSet()) { it.id },
            )
        }
    }

    fun setMediaFilter(filter: FreeUpMediaFilter) {
        _state.update { cur ->
            val filtered = applyFilters(cur.allItems, cur.ageFilter, filter)
            cur.copy(
                mediaFilter = filter,
                items = filtered,
                selectedIds = filtered.mapTo(HashSet()) { it.id },
            )
        }
    }

    fun setVerifyShaBeforeDelete(verify: Boolean) {
        _state.update { it.copy(verifyShaBeforeDelete = verify) }
    }

    private fun applyFilters(
        source: List<ReclaimableFile>,
        ageFilter: FreeUpAgeFilter,
        mediaFilter: FreeUpMediaFilter,
    ): List<ReclaimableFile> {
        val now = System.currentTimeMillis()
        val ageCutoff = when (ageFilter) {
            FreeUpAgeFilter.ALL -> Long.MAX_VALUE
            FreeUpAgeFilter.OLDER_7_DAYS -> now - 7L * 86_400_000L
            FreeUpAgeFilter.OLDER_30_DAYS -> now - 30L * 86_400_000L
        }
        return source.filter { item ->
            val ageOk = if (ageFilter == FreeUpAgeFilter.ALL) true else item.syncedAt <= ageCutoff
            if (!ageOk) return@filter false
            val bucket = classifyFile(item.mimeType, item.displayName)
            when (mediaFilter) {
                FreeUpMediaFilter.ALL -> true
                FreeUpMediaFilter.PHOTOS -> bucket == FileBucket.IMAGES
                FreeUpMediaFilter.VIDEOS -> bucket == FileBucket.VIDEOS
                FreeUpMediaFilter.OTHER -> bucket != FileBucket.IMAGES && bucket != FileBucket.VIDEOS
            }
        }
    }

    private suspend fun findReclaimableItems(): List<ReclaimableFile> {
        val folderChannelById = runCatching {
            folderDao.getAll().associate { it.id to it.channelId }
        }.getOrDefault(emptyMap())
        val cachedEntities = runCatching { fileCacheDao.getAll() }.getOrDefault(emptyList())
        val cachedByKey = cachedEntities.associateBy { it.chatId to it.messageId }
        val cachedByMsgIdFallback = cachedEntities.associateBy { it.messageId }
        val trashedEntities = runCatching { trashEntryDao.getAll() }.getOrDefault(emptyList())
        val trashedKeys = trashedEntities.mapTo(HashSet()) { it.chatId to it.messageId }
        val trashedMsgIdsFallback = trashedEntities.mapTo(HashSet()) { it.messageId }

        val synced = syncedFileDao.getAll().distinctBy { it.localPath }
        val result = ArrayList<ReclaimableFile>()
        for (entry in synced) {
            val folderChannelId = entry.folderId?.let { folderChannelById[it] }
            val verified = FreeUpSpacePolicy.isVerifiedInCloud(
                folderChannelId = folderChannelId,
                telegramMessageId = entry.telegramMessageId,
                cachedKeys = cachedByKey.keys,
                cachedMsgIdsFallback = cachedByMsgIdFallback.keys,
                trashedKeys = trashedKeys,
                trashedMsgIdsFallback = trashedMsgIdsFallback,
            )
            if (!verified) continue
            val cached = if (folderChannelId != null) {
                cachedByKey[folderChannelId to entry.telegramMessageId]
            } else {
                cachedByMsgIdFallback[entry.telegramMessageId]
            } ?: continue

            val path = entry.localPath
            if (path.startsWith("content://")) {
                val uri = runCatching { Uri.parse(path) }.getOrNull() ?: continue
                val doc = runCatching { DocumentFile.fromSingleUri(ctx, uri) }.getOrNull()
                if (doc != null && doc.exists()) {
                    val name = doc.name?.takeIf { it.isNotBlank() }
                        ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
                        ?: "file_${entry.id}"
                    val mime = cached?.mimeType ?: doc.type
                    val bucket = classifyFile(mime, name)
                    val thumb = cached?.thumbnailPath?.takeIf { File(it).isFile }
                        ?: if (bucket == FileBucket.IMAGES || bucket == FileBucket.VIDEOS) path else null
                    val length = if (entry.size > 0L) entry.size else doc.length().coerceAtLeast(0L)
                    result += ReclaimableFile(
                        id = entry.id,
                        localPath = path,
                        displayName = name,
                        size = length,
                        syncedAt = entry.syncedAt,
                        mimeType = mime,
                        sha256 = entry.sha256,
                        verifiedInCloud = cached != null,
                        thumbnailUri = thumb,
                    )
                }
            } else {
                val file = File(path)
                if (file.isFile) {
                    val mime = cached?.mimeType
                    val bucket = classifyFile(mime, file.name)
                    val thumb = cached?.thumbnailPath?.takeIf { File(it).isFile }
                        ?: if (bucket == FileBucket.IMAGES || bucket == FileBucket.VIDEOS) path else null
                    result += ReclaimableFile(
                        id = entry.id,
                        localPath = path,
                        displayName = file.name,
                        size = if (entry.size > 0L) entry.size else file.length(),
                        syncedAt = entry.syncedAt,
                        mimeType = mime,
                        sha256 = entry.sha256,
                        verifiedInCloud = cached != null,
                        thumbnailUri = thumb,
                    )
                }
            }
        }
        return result
    }

    fun toggleSelect(id: Long) {
        _state.update { cur ->
            val next = if (id in cur.selectedIds) cur.selectedIds - id else cur.selectedIds + id
            cur.copy(selectedIds = next)
        }
    }

    fun toggleSelectAll() {
        _state.update { cur ->
            val allSelected = cur.items.isNotEmpty() && cur.selectedIds.size == cur.items.size
            val next = if (allSelected) emptySet() else cur.items.mapTo(HashSet()) { it.id }
            cur.copy(selectedIds = next)
        }
    }

    fun deleteSelected() {
        val current = _state.value
        val targets = current.items.filter { it.id in current.selectedIds }
        if (targets.isEmpty() || current.cleaning) return

        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(cleaning = true, error = null) }
            var freedCount = 0
            var freedBytes = 0L
            var skippedModified = 0
            try {
                for (item in targets) {
                    if (!item.verifiedInCloud) {
                        skippedModified++
                        continue
                    }
                    if (current.verifyShaBeforeDelete && !item.sha256.isNullOrBlank()) {
                        val actualSha = sha256Calculator.compute(item.localPath)
                        if (!FreeUpSpacePolicy.shouldDeleteAfterShaCheck(true, item.sha256, actualSha)) {
                            Log.w("FreeUpSpace: skipping ${item.displayName} (SHA mismatch or unreadable local file)")
                            skippedModified++
                            continue
                        }
                    }

                    val deleted = try {
                        if (item.localPath.startsWith("content://")) {
                            val uri = Uri.parse(item.localPath)
                            runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
                                .getOrDefault(false) ||
                                (runCatching { DocumentFile.fromSingleUri(ctx, uri)?.delete() }.getOrNull() == true)
                        } else {
                            File(item.localPath).delete()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w("FreeUpSpaceViewModel: failed deleting ${item.displayName}", t)
                        false
                    }
                    if (deleted) {
                        freedCount++
                        freedBytes += item.size.coerceAtLeast(0L)
                    }
                }
                val remainingAll = findReclaimableItems()
                _state.update { cur ->
                    val remainingFiltered = applyFilters(remainingAll, cur.ageFilter, cur.mediaFilter)
                    cur.copy(
                        cleaning = false,
                        allItems = remainingAll,
                        items = remainingFiltered,
                        selectedIds = remainingFiltered.mapTo(HashSet()) { r -> r.id },
                        skippedModifiedCount = cur.skippedModifiedCount + skippedModified,
                        freedCount = cur.freedCount + freedCount,
                        freedBytes = cur.freedBytes + freedBytes,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("FreeUpSpaceViewModel: deleteSelected failed", t)
                withContext(NonCancellable) {
                    _state.update {
                        it.copy(
                            cleaning = false,
                            error = t.message ?: "Cleanup failed",
                        )
                    }
                }
            }
        }
    }
}
