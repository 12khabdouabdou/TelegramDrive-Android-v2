package com.tgdrive.feature.autobackup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.Log
import com.tgdrive.core.db.dao.SyncedFileDao
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

data class ReclaimableFile(
    val id: Long,
    val localPath: String,
    val displayName: String,
    val size: Long,
    val syncedAt: Long,
)

data class FreeUpSpaceUiState(
    val scanning: Boolean = true,
    val cleaning: Boolean = false,
    val items: List<ReclaimableFile> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val freedCount: Int = 0,
    val freedBytes: Long = 0L,
    val error: String? = null,
)

@HiltViewModel
class FreeUpSpaceViewModel @Inject constructor(
    private val syncedFileDao: SyncedFileDao,
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
                _state.update {
                    it.copy(
                        scanning = false,
                        items = found,
                        selectedIds = found.mapTo(HashSet()) { item -> item.id },
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

    private suspend fun findReclaimableItems(): List<ReclaimableFile> {
        val synced = syncedFileDao.getAll().distinctBy { it.localPath }
        val result = ArrayList<ReclaimableFile>()
        for (entry in synced) {
            val path = entry.localPath
            if (path.startsWith("content://")) {
                val uri = runCatching { Uri.parse(path) }.getOrNull() ?: continue
                val doc = runCatching { DocumentFile.fromSingleUri(ctx, uri) }.getOrNull()
                if (doc != null && doc.exists()) {
                    val name = doc.name?.takeIf { it.isNotBlank() }
                        ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
                        ?: "file_${entry.id}"
                    val length = if (entry.size > 0L) entry.size else doc.length().coerceAtLeast(0L)
                    result += ReclaimableFile(
                        id = entry.id,
                        localPath = path,
                        displayName = name,
                        size = length,
                        syncedAt = entry.syncedAt,
                    )
                }
            } else {
                val file = File(path)
                if (file.isFile) {
                    result += ReclaimableFile(
                        id = entry.id,
                        localPath = path,
                        displayName = file.name,
                        size = if (entry.size > 0L) entry.size else file.length(),
                        syncedAt = entry.syncedAt,
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
            try {
                for (item in targets) {
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
                val remaining = findReclaimableItems()
                _state.update {
                    it.copy(
                        cleaning = false,
                        items = remaining,
                        selectedIds = remaining.mapTo(HashSet()) { r -> r.id },
                        freedCount = it.freedCount + freedCount,
                        freedBytes = it.freedBytes + freedBytes,
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
