package com.tgdrive.feature.browser

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.TrashEntryEntity
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.sync.CloudSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

enum class MediaFilter {
    ALL,
    PHOTOS,
    VIDEOS,
    FAVORITES,
    OFFLINE,
}

data class MediaDateSection(
    val key: String,
    val label: String,
    val monthLabel: String,
    val items: List<FileCacheEntity>,
)

data class MemoryHighlight(
    val title: String,
    val subtitle: String,
    val items: List<FileCacheEntity>,
)

data class PhotosTimelineUiState(
    val sections: List<MediaDateSection> = emptyList(),
    val memories: List<MemoryHighlight> = emptyList(),
    val filter: MediaFilter = MediaFilter.ALL,
    val columns: Int = 3,
    val selectedIds: Set<Pair<Long, Long>> = emptySet(),
    val totalCount: Int = 0,
    val cloudSyncing: Boolean = false,
    val snackbarEvent: FolderSnackbarEvent? = null,
)

@HiltViewModel
class PhotosTimelineViewModel @Inject constructor(
    private val fileCacheDao: FileCacheDao,
    private val trashEntryDao: TrashEntryDao,
    private val downloadManager: DownloadManager,
    private val offlinePinStore: OfflinePinStore,
    private val cloudSyncManager: CloudSyncManager,
    private val dispatchers: DispatchersProvider,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _filter = MutableStateFlow(MediaFilter.ALL)
    private val _columns = MutableStateFlow(3)
    private val _selected = MutableStateFlow<Set<Pair<Long, Long>>>(emptySet())
    private val _snackbarEvent = MutableStateFlow<FolderSnackbarEvent?>(null)

    private var latestItemsByKey: Map<Pair<Long, Long>, FileCacheEntity> = emptyMap()
    private var lastTrashedBatch: List<TrashEntryEntity> = emptyList()

    private data class TimelineControls(
        val filter: MediaFilter,
        val columns: Int,
        val selected: Set<Pair<Long, Long>>,
        val syncing: Boolean,
        val snackbar: FolderSnackbarEvent?,
    )

    val state: StateFlow<PhotosTimelineUiState> = combine(
        combine(
            fileCacheDao.observeMediaItems(),
            trashEntryDao.observeAll(),
        ) { media, trashed ->
            val trashedKeys = trashed.mapTo(HashSet()) { it.chatId to it.messageId }
            media.filter { (it.chatId to it.messageId) !in trashedKeys }
        },
        combine(
            _filter,
            _columns,
            _selected,
            cloudSyncManager.syncing,
            _snackbarEvent,
        ) { filter, cols, selected, syncing, snack ->
            TimelineControls(filter, cols, selected, syncing, snack)
        },
    ) { activeMedia, ctrl ->
        latestItemsByKey = activeMedia.associateBy { it.chatId to it.messageId }

        val todayLabel = appContext.getString(R.string.photos_today)
        val yesterdayLabel = appContext.getString(R.string.photos_yesterday)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
        val monthFmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())

        val filtered = when (ctrl.filter) {
            MediaFilter.ALL -> activeMedia
            MediaFilter.PHOTOS -> activeMedia.filter { classifyFile(it.mimeType, it.fileName) == FileBucket.IMAGES }
            MediaFilter.VIDEOS -> activeMedia.filter { classifyFile(it.mimeType, it.fileName) == FileBucket.VIDEOS }
            MediaFilter.FAVORITES -> activeMedia.filter { it.isStarred }
            MediaFilter.OFFLINE -> activeMedia.filter { it.isPinnedOffline }
        }

        val sectionsByDate = LinkedHashMap<LocalDate, MutableList<FileCacheEntity>>()
        for (item in filtered) {
            val localDate = toLocalDate(item.date, zone)
            sectionsByDate.getOrPut(localDate) { mutableListOf() }.add(item)
        }
        val sections = sectionsByDate.entries
            .sortedByDescending { it.key }
            .map { (date, list) ->
                val label = when (date) {
                    today -> todayLabel
                    yesterday -> yesterdayLabel
                    else -> dateFmt.format(date)
                }
                MediaDateSection(
                    key = date.toString(),
                    label = label,
                    monthLabel = monthFmt.format(date),
                    items = list,
                )
            }

        val memories = buildMemories(activeMedia, today, zone, monthFmt)

        PhotosTimelineUiState(
            sections = sections,
            memories = memories,
            filter = ctrl.filter,
            columns = ctrl.columns,
            selectedIds = ctrl.selected,
            totalCount = activeMedia.size,
            cloudSyncing = ctrl.syncing,
            snackbarEvent = ctrl.snackbar,
        )
    }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PhotosTimelineUiState())

    private fun buildMemories(
        allMedia: List<FileCacheEntity>,
        today: LocalDate,
        zone: ZoneId,
        monthFmt: DateTimeFormatter,
    ): List<MemoryHighlight> {
        if (allMedia.isEmpty()) return emptyList()

        val onThisDay = allMedia
            .map { it to toLocalDate(it.date, zone) }
            .filter { (_, date) ->
                date.month == today.month && date.dayOfMonth == today.dayOfMonth && date.year < today.year
            }
            .groupBy { (_, date) -> today.year - date.year }
            .entries
            .sortedBy { it.key }
            .map { (yearsAgo, pairs) ->
                val title = if (yearsAgo == 1) "1 year ago" else "$yearsAgo years ago"
                val subtitle = "${pairs.size} memories"
                MemoryHighlight(title = title, subtitle = subtitle, items = pairs.map { it.first })
            }
        if (onThisDay.isNotEmpty()) return onThisDay

        return allMedia
            .map { it to toLocalDate(it.date, zone) }
            .groupBy { (_, date) -> YearMonth.from(date) }
            .entries
            .filter { it.value.size >= 3 }
            .sortedByDescending { it.key }
            .take(5)
            .map { (_, pairs) ->
                val firstDate = pairs.first().second
                MemoryHighlight(
                    title = monthFmt.format(firstDate),
                    subtitle = "${pairs.size} items",
                    items = pairs.map { it.first },
                )
            }
    }

    private fun toLocalDate(rawEpoch: Long, zone: ZoneId): LocalDate {
        val millis = when {
            rawEpoch <= 0L -> 0L
            rawEpoch < 10_000_000_000L -> rawEpoch * 1000L
            else -> rawEpoch
        }
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    }

    fun setFilter(filter: MediaFilter) {
        _filter.value = filter
    }

    fun setColumns(columns: Int) {
        _columns.value = columns.coerceIn(2, 5)
    }

    fun cycleColumns() {
        _columns.update { current ->
            when (current) {
                2 -> 3
                3 -> 4
                4 -> 5
                else -> 2
            }
        }
    }

    fun toggleSelect(chatId: Long, messageId: Long) {
        val key = chatId to messageId
        _selected.update { if (key in it) it - key else it + key }
    }

    fun selectItem(chatId: Long, messageId: Long) {
        val key = chatId to messageId
        _selected.update { if (key in it) it else it + key }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun clearSnackbar() {
        _snackbarEvent.value = null
    }

    fun starSelected() {
        val keys = _selected.value.toList()
        if (keys.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            try {
                val items = keys.mapNotNull { latestItemsByKey[it] ?: fileCacheDao.getById(it.first, it.second) }
                if (items.isEmpty()) return@launch
                val targetStar = !items.all { it.isStarred }
                for (item in items) {
                    fileCacheDao.setStarred(item.chatId, item.messageId, targetStar)
                    cloudSyncManager.noteStarChanged(item.chatId, item.messageId, targetStar)
                }
                _selected.value = emptySet()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("PhotosTimelineViewModel: starSelected failed", t)
            }
        }
    }

    fun pinSelectedOffline() {
        val keys = _selected.value.toList()
        if (keys.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            try {
                val items = keys.mapNotNull { latestItemsByKey[it] ?: fileCacheDao.getById(it.first, it.second) }
                for (item in items) {
                    val dlLocal = downloadManager.findItem(item.chatId, item.messageId)
                        ?.takeIf { it.status == DownloadStatus.DONE }
                        ?.localPath
                        ?.takeIf { File(it).exists() }
                    if (dlLocal != null) {
                        offlinePinStore.pinFile(item.chatId, item.messageId, dlLocal, item.fileName)
                    } else if (item.remoteId.isNotEmpty()) {
                        downloadManager.enqueue(
                            DownloadItem(
                                id = item.messageId,
                                chatId = item.chatId,
                                remoteId = item.remoteId,
                                fileName = item.fileName,
                                totalBytes = item.fileSize,
                                pinOfflineWhenDone = true,
                            ),
                        )
                        fileCacheDao.setPinnedOffline(item.chatId, item.messageId, true)
                    }
                }
                _selected.value = emptySet()
                _snackbarEvent.value = FolderSnackbarEvent(
                    id = System.nanoTime(),
                    message = appContext.getString(R.string.details_pin_offline),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("PhotosTimelineViewModel: pinSelectedOffline failed", t)
            }
        }
    }

    fun trashSelected() {
        val keys = _selected.value.toList()
        if (keys.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            try {
                val items = keys.mapNotNull { latestItemsByKey[it] ?: fileCacheDao.getById(it.first, it.second) }
                if (items.isEmpty()) return@launch
                val now = System.currentTimeMillis()
                val trashed = items.map { item ->
                    TrashEntryEntity(
                        messageId = item.messageId,
                        chatId = item.chatId,
                        fileName = item.fileName,
                        fileSize = item.fileSize,
                        mimeType = item.mimeType,
                        thumbnailPath = item.thumbnailPath,
                        remoteId = item.remoteId,
                        originalDate = item.date,
                        isStarred = item.isStarred,
                        deletedAt = now,
                    )
                }
                trashEntryDao.upsertAll(trashed)
                for (item in items) {
                    fileCacheDao.deleteById(item.chatId, item.messageId)
                }
                _selected.value = emptySet()
                lastTrashedBatch = trashed
                cloudSyncManager.schedulePushMetadata()
                _snackbarEvent.value = FolderSnackbarEvent(
                    id = System.nanoTime(),
                    message = appContext.getString(R.string.trash_moved_snackbar, trashed.size),
                    canUndoTrash = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("PhotosTimelineViewModel: trashSelected failed", t)
            }
        }
    }

    fun undoLastTrash() {
        val batch = lastTrashedBatch
        if (batch.isEmpty()) return
        lastTrashedBatch = emptyList()
        viewModelScope.launch(dispatchers.io) {
            try {
                fileCacheDao.upsertAll(
                    batch.map { entry ->
                        FileCacheEntity(
                            messageId = entry.messageId,
                            chatId = entry.chatId,
                            fileName = entry.fileName,
                            fileSize = entry.fileSize,
                            mimeType = entry.mimeType,
                            thumbnailPath = entry.thumbnailPath,
                            remoteId = entry.remoteId,
                            date = entry.originalDate,
                            isStarred = entry.isStarred,
                        )
                    },
                )
                for (entry in batch) {
                    trashEntryDao.deleteById(entry.chatId, entry.messageId)
                    cloudSyncManager.noteTrashRemoved(entry.chatId, entry.messageId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("PhotosTimelineViewModel: undoLastTrash failed", t)
            }
        }
    }

    fun openMedia(
        item: FileCacheEntity,
        onOpenImage: (Long, Long) -> Unit,
        onOpenVideo: (Long, Long) -> Unit,
    ) {
        viewModelScope.launch(dispatchers.io) {
            try {
                fileCacheDao.updateLastAccessed(item.chatId, item.messageId, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
        val bucket = classifyFile(item.mimeType, item.fileName)
        if (bucket == FileBucket.VIDEOS) {
            onOpenVideo(item.chatId, item.messageId)
        } else {
            val hasLocal = offlinePinStore.getPinnedFilePathSync(item.chatId, item.messageId)?.let {
                File(it).exists()
            } == true || downloadManager.findItem(item.chatId, item.messageId)
                ?.takeIf { it.status == DownloadStatus.DONE }
                ?.localPath
                ?.let { File(it).exists() } == true

            if (!hasLocal && item.remoteId.isNotEmpty()) {
                downloadManager.enqueue(
                    DownloadItem(
                        id = item.messageId,
                        chatId = item.chatId,
                        remoteId = item.remoteId,
                        fileName = item.fileName,
                        totalBytes = item.fileSize,
                    ),
                )
            }
            onOpenImage(item.chatId, item.messageId)
        }
    }
}
