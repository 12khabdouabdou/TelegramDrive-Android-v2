package com.tgdrive.feature.browser

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tgdrive.core.common.DispatchersProvider
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.PublicDownloadsExporter
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.datastore.AppPreferences
import com.tgdrive.core.db.dao.AlbumDao
import com.tgdrive.core.db.dao.FileCacheDao
import com.tgdrive.core.db.dao.TrashEntryDao
import com.tgdrive.core.db.entity.AlbumEntity
import com.tgdrive.core.db.entity.AlbumItemEntity
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.TrashEntryEntity
import com.tgdrive.core.download.DownloadItem
import com.tgdrive.core.download.DownloadManager
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.core.download.MediaThumbnailCache
import com.tgdrive.core.download.OfflinePinStore
import com.tgdrive.core.download.VideoDurationCache
import com.tgdrive.core.download.ai.MediaAiIndexer
import com.tgdrive.core.download.ai.SimilarPhotoDetector
import com.tgdrive.core.download.ai.SimilarPhotoGroup
import com.tgdrive.core.download.sync.CloudSyncManager
import com.tgdrive.core.telegram.TelegramClient
import com.tgdrive.core.telegram.TelegramFolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

enum class PhotosViewMode {
    TIMELINE,
    ALBUMS,
    PLACES,
}

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
    val monthKey: String,
    val items: List<FileCacheEntity>,
)

data class MemoryHighlight(
    val title: String,
    val subtitle: String,
    val items: List<FileCacheEntity>,
)

data class AlbumCollection(
    val id: String,
    val title: String,
    val isCustom: Boolean,
    val items: List<FileCacheEntity>,
    val coverItem: FileCacheEntity? = items.firstOrNull(),
)

data class PlaceCluster(
    val id: String,
    val title: String,
    val lat: Double,
    val lng: Double,
    val items: List<FileCacheEntity>,
)

data class PhotosTimelineUiState(
    val viewMode: PhotosViewMode = PhotosViewMode.TIMELINE,
    val sections: List<MediaDateSection> = emptyList(),
    val memories: List<MemoryHighlight> = emptyList(),
    val albums: List<AlbumCollection> = emptyList(),
    val customAlbums: List<AlbumCollection> = emptyList(),
    val openedAlbumId: String? = null,
    val places: List<PlaceCluster> = emptyList(),
    val openedPlaceId: String? = null,
    val filter: MediaFilter = MediaFilter.ALL,
    val columns: Int = 3,
    val selectedIds: Set<Pair<Long, Long>> = emptySet(),
    val totalCount: Int = 0,
    val cloudSyncing: Boolean = false,
    val isRefreshing: Boolean = false,
    val scanningExif: Boolean = false,
    val videoDurations: Map<Pair<Long, Long>, String> = emptyMap(),
    val similarScanRunning: Boolean = false,
    val similarGroups: List<SimilarPhotoGroup>? = null,
    val snackbarEvent: FolderSnackbarEvent? = null,
) {
    val activeAlbum: AlbumCollection?
        get() = openedAlbumId?.let { id -> albums.firstOrNull { it.id == id } }

    val activePlace: PlaceCluster?
        get() = openedPlaceId?.let { id -> places.firstOrNull { it.id == id } }
}

@HiltViewModel
class PhotosTimelineViewModel @Inject constructor(
    private val fileCacheDao: FileCacheDao,
    private val albumDao: AlbumDao,
    private val trashEntryDao: TrashEntryDao,
    private val downloadManager: DownloadManager,
    private val offlinePinStore: OfflinePinStore,
    private val cloudSyncManager: CloudSyncManager,
    private val mediaThumbnailCache: MediaThumbnailCache,
    private val mediaAiIndexer: MediaAiIndexer,
    private val similarPhotoDetector: SimilarPhotoDetector,
    private val videoDurationCache: VideoDurationCache,
    private val telegramClient: TelegramClient,
    private val preferences: AppPreferences,
    private val dispatchers: DispatchersProvider,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val attemptedThumbKeys = Collections.synchronizedSet(HashSet<Pair<Long, Long>>())
    private val thumbChannel = Channel<FileCacheEntity>(capacity = 64)
    private val thumbSemaphore = Semaphore(2)
    private val attemptedDurationKeys = HashSet<Pair<Long, Long>>()

    private val _viewMode = MutableStateFlow(PhotosViewMode.TIMELINE)
    private val _filter = MutableStateFlow(MediaFilter.ALL)
    private val _openedAlbumId = MutableStateFlow<String?>(null)
    private val _openedPlaceId = MutableStateFlow<String?>(null)
    private val _selected = MutableStateFlow<Set<Pair<Long, Long>>>(emptySet())
    private val _isRefreshing = MutableStateFlow(false)
    private val _scanningExif = MutableStateFlow(false)
    private val _videoDurations = MutableStateFlow<Map<Pair<Long, Long>, String>>(emptyMap())
    private val _similarScanRunning = MutableStateFlow(false)
    private val _similarGroups = MutableStateFlow<List<SimilarPhotoGroup>?>(null)
    private val _snackbarEvent = MutableStateFlow<FolderSnackbarEvent?>(null)

    private var latestItemsByKey: Map<Pair<Long, Long>, FileCacheEntity> = emptyMap()
    private var latestActiveMedia: List<FileCacheEntity> = emptyList()
    private var lastTrashedBatch: List<TrashEntryEntity> = emptyList()

    init {
        viewModelScope.launch(dispatchers.io) {
            for (entity in thumbChannel) {
                launch {
                    thumbSemaphore.withPermit {
                        try {
                            mediaThumbnailCache.ensureThumbnail(entity)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {}
                    }
                }
            }
        }
    }

    fun requestThumbnail(entity: FileCacheEntity) {
        if (!mediaThumbnailCache.needsThumbnailUpgrade(entity.thumbnailPath)) return
        if (attemptedThumbKeys.add(entity.chatId to entity.messageId)) {
            thumbChannel.trySend(entity)
        }
    }

    private data class ModeControls(
        val viewMode: PhotosViewMode,
        val filter: MediaFilter,
        val openedAlbumId: String?,
        val openedPlaceId: String?,
        val columns: Int,
    )

    private data class ActionControls(
        val selected: Set<Pair<Long, Long>>,
        val syncing: Boolean,
        val refreshing: Boolean,
        val scanningExif: Boolean,
        val durations: Map<Pair<Long, Long>, String>,
    )

    private data class OverlayControls(
        val similarRunning: Boolean,
        val similarGroups: List<SimilarPhotoGroup>?,
        val snackbar: FolderSnackbarEvent?,
    )

    private val mediaAndAlbumsFlow = combine(
        fileCacheDao.observeActiveMediaItems(),
        albumDao.observeAll(),
        albumDao.observeAllItems(),
    ) { activeMedia, albums, albumItems ->
        var queued = 0
        for (item in activeMedia) {
            if (queued >= 24) break
            if (mediaThumbnailCache.needsThumbnailUpgrade(item.thumbnailPath) &&
                attemptedThumbKeys.add(item.chatId to item.messageId)
            ) {
                thumbChannel.trySend(item)
                queued++
            }
        }
        Triple(activeMedia, albums, albumItems)
    }

    private val modeControlsFlow = combine(
        _viewMode,
        _filter,
        _openedAlbumId,
        _openedPlaceId,
        preferences.data.map { it.photosColumns.coerceIn(2, 5) }.distinctUntilChanged(),
    ) { mode, filter, albumId, placeId, cols ->
        ModeControls(mode, filter, albumId, placeId, cols)
    }

    private val actionControlsFlow = combine(
        _selected,
        cloudSyncManager.syncing,
        _isRefreshing,
        _scanningExif,
        _videoDurations,
    ) { selected, syncing, refreshing, scanningExif, durations ->
        ActionControls(selected, syncing, refreshing, scanningExif, durations)
    }

    private val overlayControlsFlow = combine(
        _similarScanRunning,
        _similarGroups,
        _snackbarEvent,
    ) { running, groups, snackbar ->
        OverlayControls(running, groups, snackbar)
    }

    val state: StateFlow<PhotosTimelineUiState> = combine(
        mediaAndAlbumsFlow,
        modeControlsFlow,
        actionControlsFlow,
        overlayControlsFlow,
    ) { (activeMedia, dbAlbums, dbAlbumItems), modeCtrl, actCtrl, ovCtrl ->
        val itemsByKey = activeMedia.associateBy { it.chatId to it.messageId }
        latestItemsByKey = itemsByKey
        latestActiveMedia = activeMedia

        val todayLabel = appContext.getString(R.string.photos_today)
        val yesterdayLabel = appContext.getString(R.string.photos_yesterday)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
        val monthFmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())

        val filtered = when (modeCtrl.filter) {
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
                    monthKey = YearMonth.from(date).toString(),
                    items = list,
                )
            }

        val memories = buildMemories(activeMedia, today, zone, monthFmt)
        val smartAlbums = buildSmartCollections(activeMedia)
        val itemsByAlbumId = dbAlbumItems.groupBy { it.albumId }
        val customAlbums = dbAlbums.map { alb ->
            val albMedia = itemsByAlbumId[alb.id].orEmpty()
                .mapNotNull { itemsByKey[it.chatId to it.messageId] }
                .sortedByDescending { it.date }
            val explicitCover = if (alb.coverChatId != null && alb.coverMessageId != null) {
                itemsByKey[alb.coverChatId!! to alb.coverMessageId!!]
            } else {
                null
            }
            AlbumCollection(
                id = alb.id,
                title = alb.name,
                isCustom = true,
                items = albMedia,
                coverItem = explicitCover ?: albMedia.firstOrNull(),
            )
        }
        val allAlbums = customAlbums + smartAlbums.filter { it.items.isNotEmpty() }
        val places = buildPlaceClusters(activeMedia)

        PhotosTimelineUiState(
            viewMode = modeCtrl.viewMode,
            sections = sections,
            memories = memories,
            albums = allAlbums,
            customAlbums = customAlbums,
            openedAlbumId = modeCtrl.openedAlbumId,
            places = places,
            openedPlaceId = modeCtrl.openedPlaceId,
            filter = modeCtrl.filter,
            columns = modeCtrl.columns,
            selectedIds = actCtrl.selected,
            totalCount = activeMedia.size,
            cloudSyncing = actCtrl.syncing,
            isRefreshing = actCtrl.refreshing,
            scanningExif = actCtrl.scanningExif,
            videoDurations = actCtrl.durations,
            similarScanRunning = ovCtrl.similarRunning,
            similarGroups = ovCtrl.similarGroups,
            snackbarEvent = ovCtrl.snackbar,
        )
    }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PhotosTimelineUiState())

    private fun buildSmartCollections(allMedia: List<FileCacheEntity>): List<AlbumCollection> {
        val screenshots = allMedia.filter {
            val lower = it.fileName.lowercase()
            lower.contains("screenshot") || lower.contains("screen_") || lower.startsWith("scr_")
        }
        val gifs = allMedia.filter {
            val lower = it.fileName.lowercase()
            it.mimeType.equals("image/gif", ignoreCase = true) || lower.endsWith(".gif")
        }
        val largeVideos = allMedia.filter {
            classifyFile(it.mimeType, it.fileName) == FileBucket.VIDEOS &&
                it.fileSize >= 50L * 1024L * 1024L
        }
        val withNotes = allMedia.filter { !it.userNote.isNullOrBlank() }
        val favorites = allMedia.filter { it.isStarred }
        val offline = allMedia.filter { it.isPinnedOffline }
        val geotagged = allMedia.filter { it.exifLat != null && it.exifLng != null }

        return listOf(
            AlbumCollection("smart_favorites", appContext.getString(R.string.photos_filter_favorites), false, favorites),
            AlbumCollection("smart_screenshots", appContext.getString(R.string.photos_album_screenshots), false, screenshots),
            AlbumCollection("smart_large_videos", appContext.getString(R.string.photos_album_large_videos), false, largeVideos),
            AlbumCollection("smart_gifs", appContext.getString(R.string.photos_album_gifs), false, gifs),
            AlbumCollection("smart_notes", appContext.getString(R.string.photos_album_with_notes), false, withNotes),
            AlbumCollection("smart_offline", appContext.getString(R.string.photos_filter_offline), false, offline),
            AlbumCollection("smart_geotagged", appContext.getString(R.string.photos_album_geotagged), false, geotagged),
        )
    }

    private fun buildPlaceClusters(allMedia: List<FileCacheEntity>): List<PlaceCluster> {
        val geoItems = allMedia.filter { it.exifLat != null && it.exifLng != null }
        if (geoItems.isEmpty()) return emptyList()
        val grouped = geoItems.groupBy { item ->
            val latBucket = ((item.exifLat ?: 0.0) * 10.0).roundToInt()
            val lngBucket = ((item.exifLng ?: 0.0) * 10.0).roundToInt()
            "${latBucket}_$lngBucket"
        }
        return grouped.entries.map { (bucketKey, list) ->
            val sorted = list.sortedByDescending { it.date }
            val avgLat = sorted.mapNotNull { it.exifLat }.average()
            val avgLng = sorted.mapNotNull { it.exifLng }.average()
            val latStr = String.format(Locale.US, "%.2f", avgLat)
            val lngStr = String.format(Locale.US, "%.2f", avgLng)
            PlaceCluster(
                id = "place_$bucketKey",
                title = "$latStr, $lngStr",
                lat = avgLat,
                lng = avgLng,
                items = sorted,
            )
        }.sortedByDescending { it.items.size }
    }

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

    fun setViewMode(mode: PhotosViewMode) {
        _viewMode.value = mode
        _openedAlbumId.value = null
        _openedPlaceId.value = null
        clearSelection()
    }

    fun openAlbum(albumId: String?) {
        _openedAlbumId.value = albumId
        clearSelection()
    }

    fun openPlace(placeId: String?) {
        _openedPlaceId.value = placeId
        clearSelection()
    }

    fun setFilter(filter: MediaFilter) {
        _filter.value = filter
    }

    fun setColumns(columns: Int) {
        val clamped = columns.coerceIn(2, 5)
        viewModelScope.launch(dispatchers.io) {
            preferences.update { it.copy(photosColumns = clamped) }
        }
    }

    fun cycleColumns() {
        val current = state.value.columns
        val next = when (current) {
            2 -> 3
            3 -> 4
            4 -> 5
            else -> 2
        }
        setColumns(next)
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch(dispatchers.io) {
            _isRefreshing.value = true
            try {
                runCatching { cloudSyncManager.syncNow() }
                val sysId = runCatching { telegramClient.getOrCreateSystemChannel() }.getOrNull()
                val excluded = sysId?.let { setOf(it) } ?: emptySet()
                runCatching { mediaAiIndexer.scanLocalExifLocations(excluded) }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun scanLocalExifLocations() {
        if (_scanningExif.value) return
        viewModelScope.launch(dispatchers.io) {
            _scanningExif.value = true
            try {
                val sysId = runCatching { telegramClient.getOrCreateSystemChannel() }.getOrNull()
                val excluded = sysId?.let { setOf(it) } ?: emptySet()
                val found = runCatching { mediaAiIndexer.scanLocalExifLocations(excluded) }.getOrDefault(0)
                showMessage(
                    if (found > 0) {
                        appContext.getString(R.string.photos_places_found, found)
                    } else {
                        appContext.getString(R.string.photos_places_none_new)
                    },
                )
            } finally {
                _scanningExif.value = false
            }
        }
    }

    fun ensureVideoDuration(entity: FileCacheEntity) {
        val key = entity.chatId to entity.messageId
        if (key in _videoDurations.value || !attemptedDurationKeys.add(key)) return
        viewModelScope.launch(dispatchers.io) {
            val ms = videoDurationCache.resolveDurationMs(entity) ?: return@launch
            val formatted = VideoDurationCache.formatDuration(ms)
            _videoDurations.update { cur -> cur + (key to formatted) }
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

    fun selectSection(section: MediaDateSection) {
        val keys = section.items.map { it.chatId to it.messageId }
        _selected.update { cur ->
            val allSelected = keys.isNotEmpty() && keys.all { it in cur }
            if (allSelected) cur - keys.toSet() else cur + keys
        }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun clearSnackbar() {
        _snackbarEvent.value = null
    }

    private fun showMessage(msg: String) {
        _snackbarEvent.value = FolderSnackbarEvent(id = System.nanoTime(), message = msg)
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
                showMessage(appContext.getString(R.string.details_pin_offline))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("PhotosTimelineViewModel: pinSelectedOffline failed", t)
            }
        }
    }

    fun createAlbum(name: String, addSelection: Boolean = false) {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return
        val keys = if (addSelection) _selected.value.toList() else emptyList()
        viewModelScope.launch(dispatchers.io) {
            try {
                val now = System.currentTimeMillis()
                val firstItem = keys.firstOrNull()
                val album = AlbumEntity(
                    id = "alb_${UUID.randomUUID().toString().take(8)}",
                    name = cleaned,
                    coverChatId = firstItem?.first,
                    coverMessageId = firstItem?.second,
                    createdAt = now,
                    updatedAt = now,
                )
                albumDao.upsert(album)
                cloudSyncManager.noteAlbumUpserted(album)
                if (keys.isNotEmpty()) {
                    val albumItems = keys.map { (chatId, msgId) ->
                        AlbumItemEntity(albumId = album.id, chatId = chatId, messageId = msgId, addedAt = now)
                    }
                    albumDao.addItems(albumItems)
                    albumItems.forEach {
                        cloudSyncManager.noteAlbumItemChanged(album.id, it.chatId, it.messageId, added = true)
                    }
                    _selected.value = emptySet()
                }
                showMessage(appContext.getString(R.string.photos_album_created, cleaned))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("PhotosTimelineViewModel: createAlbum failed", t)
            }
        }
    }

    fun deleteCustomAlbum(albumId: String) {
        viewModelScope.launch(dispatchers.io) {
            runCatching {
                albumDao.deleteById(albumId)
                cloudSyncManager.noteAlbumDeleted(albumId)
                if (_openedAlbumId.value == albumId) {
                    _openedAlbumId.value = null
                }
            }
        }
    }

    fun addSelectedToAlbum(albumId: String) {
        val keys = _selected.value.toList()
        if (keys.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            try {
                val now = System.currentTimeMillis()
                val entities = keys.map { (chatId, msgId) ->
                    AlbumItemEntity(albumId = albumId, chatId = chatId, messageId = msgId, addedAt = now)
                }
                albumDao.addItems(entities)
                val existing = albumDao.getById(albumId)
                if (existing != null) {
                    val updated = existing.copy(
                        coverChatId = existing.coverChatId ?: keys.first().first,
                        coverMessageId = existing.coverMessageId ?: keys.first().second,
                        updatedAt = now,
                    )
                    albumDao.upsert(updated)
                    cloudSyncManager.noteAlbumUpserted(updated)
                }
                entities.forEach {
                    cloudSyncManager.noteAlbumItemChanged(albumId, it.chatId, it.messageId, added = true)
                }
                _selected.value = emptySet()
                showMessage(appContext.getString(R.string.photos_added_to_album, keys.size))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("PhotosTimelineViewModel: addSelectedToAlbum failed", t)
            }
        }
    }

    fun removeSelectedFromAlbum(albumId: String) {
        val keys = _selected.value.toList()
        if (keys.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            for ((chatId, msgId) in keys) {
                albumDao.removeItem(albumId, chatId, msgId)
                cloudSyncManager.noteAlbumItemChanged(albumId, chatId, msgId, added = false)
            }
            _selected.value = emptySet()
            showMessage(appContext.getString(R.string.photos_removed_from_album, keys.size))
        }
    }

    fun saveMemoryAsAlbum(memory: MemoryHighlight) {
        if (memory.items.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            try {
                val now = System.currentTimeMillis()
                val lead = memory.items.first()
                val album = AlbumEntity(
                    id = "alb_${UUID.randomUUID().toString().take(8)}",
                    name = memory.title,
                    coverChatId = lead.chatId,
                    coverMessageId = lead.messageId,
                    createdAt = now,
                    updatedAt = now,
                )
                albumDao.upsert(album)
                cloudSyncManager.noteAlbumUpserted(album)
                val entries = memory.items.map { item ->
                    AlbumItemEntity(albumId = album.id, chatId = item.chatId, messageId = item.messageId, addedAt = now)
                }
                albumDao.addItems(entries)
                entries.forEach {
                    cloudSyncManager.noteAlbumItemChanged(album.id, it.chatId, it.messageId, added = true)
                }
                showMessage(appContext.getString(R.string.photos_album_created, memory.title))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("PhotosTimelineViewModel: saveMemoryAsAlbum failed", t)
            }
        }
    }

    fun saveSelectedToDownloads() {
        val keys = _selected.value.toList()
        if (keys.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            var savedCount = 0
            val items = keys.mapNotNull { latestItemsByKey[it] ?: fileCacheDao.getById(it.first, it.second) }
            for (item in items) {
                val localPath = resolveLocalFilePath(item) ?: continue
                if (copyFileToDownloads(File(localPath), item.fileName, item.mimeType)) {
                    savedCount++
                }
            }
            _selected.value = emptySet()
            if (savedCount > 0) {
                showMessage(appContext.getString(R.string.photos_saved_downloads, savedCount))
            } else {
                showMessage(appContext.getString(R.string.photos_download_first_hint))
            }
        }
    }

    fun shareSelected(uiContext: Context) {
        val keys = _selected.value.toList()
        if (keys.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            val items = keys.mapNotNull { latestItemsByKey[it] ?: fileCacheDao.getById(it.first, it.second) }
            val localFiles = items.mapNotNull { resolveLocalFilePath(it)?.let(::File) }
            if (localFiles.isEmpty()) {
                showMessage(appContext.getString(R.string.photos_download_first_hint))
                return@launch
            }
            withContext(dispatchers.main) {
                runCatching {
                    val uris = ArrayList(
                        localFiles.map { f ->
                            FileProvider.getUriForFile(uiContext, "${uiContext.packageName}.fileprovider", f)
                        },
                    )
                    val intent = if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND).apply {
                            type = "*/*"
                            putExtra(Intent.EXTRA_STREAM, uris.first())
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "*/*"
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    uiContext.startActivity(
                        Intent.createChooser(intent, uiContext.getString(R.string.photos_action_share)),
                    )
                }.onFailure {
                    Log.w("PhotosTimelineViewModel: shareSelected failed", it)
                }
            }
        }
    }

    suspend fun listFoldersForMove(): List<TelegramFolder> =
        try {
            telegramClient.listFolders()
        } catch (t: Throwable) {
            Log.w("PhotosTimelineViewModel: listFoldersForMove failed", t)
            emptyList()
        }

    fun moveSelectedToFolder(targetChatId: Long) {
        val keys = _selected.value.toList()
        if (keys.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            var moved = 0
            try {
                for ((chatId, msgId) in keys) {
                    if (chatId == targetChatId) continue
                    runCatching {
                        telegramClient.moveFile(chatId, msgId, targetChatId)
                        fileCacheDao.deleteById(chatId, msgId)
                        moved++
                    }
                }
                _selected.value = emptySet()
                showMessage(appContext.getString(R.string.photos_moved_folder, moved))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("PhotosTimelineViewModel: moveSelectedToFolder failed", t)
            }
        }
    }

    fun scanSimilarShots() {
        if (_similarScanRunning.value) return
        viewModelScope.launch(dispatchers.io) {
            _similarScanRunning.value = true
            try {
                val groups = similarPhotoDetector.findSimilarGroups(latestActiveMedia)
                _similarGroups.value = groups
            } finally {
                _similarScanRunning.value = false
            }
        }
    }

    fun dismissSimilarShots() {
        _similarGroups.value = null
    }

    fun trashSimilarDuplicates() {
        val groups = _similarGroups.value.orEmpty()
        val duplicates = groups.flatMap { it.items.drop(1) }
        _similarGroups.value = null
        if (duplicates.isEmpty()) return
        trashEntities(duplicates)
    }

    fun trashSelected() {
        val keys = _selected.value.toList()
        if (keys.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            val items = keys.mapNotNull { latestItemsByKey[it] ?: fileCacheDao.getById(it.first, it.second) }
            if (items.isNotEmpty()) {
                trashEntitiesInternal(items)
            }
        }
    }

    private fun trashEntities(items: List<FileCacheEntity>) {
        viewModelScope.launch(dispatchers.io) {
            trashEntitiesInternal(items)
        }
    }

    private suspend fun trashEntitiesInternal(items: List<FileCacheEntity>) {
        try {
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
            Log.w("PhotosTimelineViewModel: trashEntities failed", t)
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
            runCatching {
                fileCacheDao.updateLastAccessed(item.chatId, item.messageId, System.currentTimeMillis())
            }
        }
        val bucket = classifyFile(item.mimeType, item.fileName)
        if (bucket == FileBucket.VIDEOS) {
            onOpenVideo(item.chatId, item.messageId)
        } else {
            val hasLocal = resolveLocalFilePath(item) != null
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

    private fun resolveLocalFilePath(item: FileCacheEntity): String? {
        offlinePinStore.getPinnedFilePathSync(item.chatId, item.messageId)?.let { p ->
            if (File(p).isFile) return p
        }
        return downloadManager.findItem(item.chatId, item.messageId)
            ?.takeIf { it.status == DownloadStatus.DONE }
            ?.localPath
            ?.takeIf { File(it).isFile }
    }

    private fun copyFileToDownloads(source: File, fileName: String, mimeType: String?): Boolean =
        PublicDownloadsExporter.saveToPublicDownloads(
            ctx = appContext,
            source = source,
            displayName = fileName,
            mimeType = mimeType,
        ) != null
}
