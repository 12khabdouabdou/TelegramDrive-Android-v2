package com.tgdrive.feature.player

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.designsystem.theme.TgTypography
import kotlinx.coroutines.launch
import java.io.File

private data class GalleryItemRef(
    val chatId: Long,
    val messageId: Long,
    val entity: FileCacheEntity?,
)

/**
 * Swipeable full-screen image gallery viewer backed by HorizontalPager across
 * sibling images in the current chat, with per-page download tracking, auto-prefetch
 * of adjacent images, pinch/double-tap zoom, swipe-down dismiss, EXIF sheet, and quick actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePlayerScreen(
    messageId: Long,
    onBack: () -> Unit,
    chatId: Long = 0L,
    timeline: Boolean = false,
    albumId: String = "",
    onOpenVideo: (Long, Long) -> Unit = { _, _ -> },
    vm: VideoPlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val downloadState by vm.downloadManager.state.collectAsStateWithLifecycle()
    var siblings by remember(chatId, messageId, timeline, albumId) { mutableStateOf<List<GalleryItemRef>>(emptyList()) }
    var rotations by remember(chatId, messageId, timeline, albumId) { mutableStateOf<Map<Pair<Long, Long>, Float>>(emptyMap()) }
    var initialScrollApplied by remember(chatId, messageId, timeline, albumId) { mutableStateOf(false) }
    var showChrome by remember(chatId, messageId) { mutableStateOf(true) }
    var isCurrentZoomed by remember { mutableStateOf(false) }
    var showExifSheet by remember { mutableStateOf(false) }
    var showCropDialog by remember { mutableStateOf(false) }
    var exifInfo by remember { mutableStateOf<ImageExifInfo?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(chatId, messageId, timeline, albumId) {
        val target = vm.getCachedEntry(chatId, messageId)
        val loaded = when {
            albumId.isNotBlank() -> vm.loadAlbumMedia(albumId)
            timeline -> vm.loadTimelineMedia()
            chatId != 0L -> vm.loadSiblingMedia(chatId, includeLocked = target?.isLocked == true)
            else -> emptyList()
        }
        val matchesTarget = loaded.any {
            if (chatId != 0L) it.chatId == chatId && it.messageId == messageId else it.messageId == messageId
        }
        siblings = if (matchesTarget) {
            loaded.map { GalleryItemRef(it.chatId, it.messageId, it) }
        } else {
            val combined = if (target != null) listOf(target) + loaded else loaded
            if (combined.isNotEmpty()) {
                combined.distinctBy { it.chatId to it.messageId }.map { GalleryItemRef(it.chatId, it.messageId, it) }
            } else {
                listOf(GalleryItemRef(chatId, messageId, null))
            }
        }
    }

    val pages = siblings.ifEmpty { listOf(GalleryItemRef(chatId, messageId, null)) }
    val targetIdx = remember(pages, chatId, messageId) {
        pages.indexOfFirst {
            if (chatId != 0L) it.chatId == chatId && it.messageId == messageId else it.messageId == messageId
        }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = targetIdx) { pages.size }

    LaunchedEffect(targetIdx, pages.size, siblings.isNotEmpty()) {
        if (!initialScrollApplied && siblings.isNotEmpty() && targetIdx < pages.size) {
            if (pagerState.currentPage != targetIdx) {
                pagerState.scrollToPage(targetIdx)
            }
            initialScrollApplied = true
        }
    }

    LaunchedEffect(pagerState.currentPage, pages) {
        isCurrentZoomed = false
        dragOffsetY = 0f
        val cur = pages.getOrNull(pagerState.currentPage)
        cur?.entity?.let { entry ->
            val isVid = classifyFile(entry.mimeType, entry.fileName) == FileBucket.VIDEOS
            if (!isVid &&
                vm.getLocalPath(entry.messageId, entry.chatId) == null &&
                vm.downloadManager.findItem(entry.chatId, entry.messageId) == null
            ) {
                vm.ensureDownloaded(entry)
            }
        }
        for (neighborIdx in listOf(pagerState.currentPage + 1, pagerState.currentPage - 1)) {
            val neighbor = pages.getOrNull(neighborIdx)?.entity ?: continue
            val neighborIsVid = classifyFile(neighbor.mimeType, neighbor.fileName) == FileBucket.VIDEOS
            if (!neighborIsVid &&
                vm.getLocalPath(neighbor.messageId, neighbor.chatId) == null &&
                vm.downloadManager.findItem(neighbor.chatId, neighbor.messageId) == null &&
                neighbor.fileSize <= 15L * 1024 * 1024
            ) {
                vm.ensureDownloaded(neighbor)
            }
        }
    }

    val activeRef = pages.getOrNull(pagerState.currentPage) ?: pages.first()
    SecureWindowForSensitiveEntry(
        isSensitive = activeRef.entity?.isLocked == true || activeRef.entity?.isEncryptedVault == true,
    )
    val activePath = remember(activeRef, downloadState.items) {
        vm.getLocalPath(activeRef.messageId, activeRef.chatId)
    }

    LaunchedEffect(showExifSheet, activePath, activeRef) {
        if (showExifSheet) {
            exifInfo = vm.readImageExif(activePath, activeRef.entity)
        }
    }

    val bgAlpha = if (dragOffsetY > 0f) (1f - (dragOffsetY / 500f)).coerceIn(0.35f, 1f) else 1f
    val shareChooserTitle = stringResource(R.string.player_share_chooser)
    val trashedMsg = stringResource(R.string.player_trashed_toast)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = bgAlpha))
            .pointerInput(pagerState.currentPage, isCurrentZoomed) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        when {
                            !isCurrentZoomed && dragOffsetY > 180f -> onBack()
                            !isCurrentZoomed && dragOffsetY < -140f -> showExifSheet = true
                        }
                        dragOffsetY = 0f
                    },
                    onDragCancel = { dragOffsetY = 0f },
                ) { _, dragAmount ->
                    if (!isCurrentZoomed) {
                        dragOffsetY = (dragOffsetY + dragAmount).coerceIn(-220f, 500f)
                    }
                }
            },
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            userScrollEnabled = !isCurrentZoomed && pages.size > 1,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dragOffsetY.coerceAtLeast(0f)
                },
        ) { page ->
            val ref = pages[page]
            val isVideoPage = ref.entity?.let {
                classifyFile(it.mimeType, it.fileName) == FileBucket.VIDEOS
            } ?: false
            val pagePath = vm.getLocalPath(ref.messageId, ref.chatId)
            val downloadItem = downloadState.items.find {
                it.id == ref.messageId && (ref.chatId == 0L || it.chatId == 0L || it.chatId == ref.chatId)
            }
            val isDownloading =
                downloadItem?.status == DownloadStatus.DOWNLOADING ||
                    downloadItem?.status == DownloadStatus.QUEUED
            val isFailed = downloadItem?.status == DownloadStatus.FAILED
            val downloadProgress = downloadItem?.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            val fileReady = pagePath != null

            when {
                isVideoPage -> {
                    val thumbFile = remember(ref.entity?.thumbnailPath) {
                        ref.entity?.thumbnailPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { showChrome = !showChrome },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumbFile != null) {
                            AsyncImage(
                                model = thumbFile,
                                contentDescription = ref.entity?.fileName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        IconButton(
                            onClick = { onOpenVideo(ref.chatId, ref.messageId) },
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f)),
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.player_video_play),
                                tint = Color.White,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                }
                pagePath != null && fileReady -> {
                    ZoomableImagePage(
                        path = pagePath,
                        isActive = page == pagerState.currentPage,
                        onZoomChanged = { zoomed ->
                            if (page == pagerState.currentPage) isCurrentZoomed = zoomed
                        },
                        onTap = { showChrome = !showChrome },
                        rotationDegrees = rotations[ref.chatId to ref.messageId] ?: 0f,
                    )
                }
                isFailed -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = downloadItem?.errorMessage ?: stringResource(R.string.player_download_failed),
                            color = Color.White,
                            style = TgTypography.Display.bodyMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { downloadItem?.let { vm.downloadManager.enqueue(it) } }) {
                            Text(stringResource(R.string.player_retry))
                        }
                    }
                }
                isDownloading || ref.entity != null -> {
                    val previewPath = ref.entity?.thumbnailPath?.takeIf {
                        it.isNotBlank() && !it.endsWith(".tgethumb", ignoreCase = true)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { showChrome = !showChrome },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (previewPath != null) {
                            AsyncImage(
                                model = remember(previewPath, context) {
                                    ImageRequest.Builder(context)
                                        .data(File(previewPath))
                                        .crossfade(true)
                                        .build()
                                },
                                contentDescription = ref.entity?.fileName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Surface(
                            color = Color.Black.copy(alpha = 0.68f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.padding(24.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (previewPath == null) {
                                    Icon(
                                        Icons.Filled.Image,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(40.dp),
                                    )
                                }
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp),
                                )
                                Text(
                                    text = stringResource(R.string.player_downloading_pct, (downloadProgress * 100).toInt()),
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                )
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = Color.White.copy(alpha = 0.25f),
                                    modifier = Modifier.fillMaxWidth(0.55f).clip(RoundedCornerShape(4.dp)),
                                )
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.Image,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.player_image_error),
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }

        // Top Chrome Bar
        AnimatedVisibility(
            visible = showChrome || activePath == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent),
                        ),
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.player_back),
                        tint = Color.White,
                    )
                }
                if (activeRef.entity?.fileName != null) {
                    Text(
                        text = activeRef.entity.fileName,
                        color = Color.White,
                        style = TgTypography.Display.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                val activeIsVideo = activeRef.entity?.let {
                    classifyFile(it.mimeType, it.fileName) == FileBucket.VIDEOS
                } ?: false
                if (!activeIsVideo && activePath != null) {
                    IconButton(
                        onClick = { showCropDialog = true },
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f)),
                    ) {
                        Icon(
                            Icons.Filled.Crop,
                            contentDescription = stringResource(R.string.player_crop_title),
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = {
                            val key = activeRef.chatId to activeRef.messageId
                            val next = (rotations[key] ?: 0f) + 90f
                            rotations = rotations + (key to next)
                        },
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f)),
                    ) {
                        Icon(
                            Icons.Filled.RotateRight,
                            contentDescription = stringResource(R.string.player_image_rotate),
                            tint = Color.White,
                        )
                    }
                }
                if (pages.size > 1) {
                    Text(
                        text = stringResource(R.string.player_image_counter, pagerState.currentPage + 1, pages.size),
                        color = Color.White.copy(alpha = 0.9f),
                        style = TgTypography.Caption,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                }
            }
        }

        // Bottom Google-Photos-style Quick Action Bar
        val entity = activeRef.entity
        if (entity != null) {
            AnimatedVisibility(
                visible = showChrome,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                            ),
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerActionItem(
                        icon = Icons.Filled.Share,
                        label = stringResource(R.string.player_action_share),
                        enabled = activePath != null,
                        onClick = {
                            val local = activePath ?: return@ViewerActionItem
                            runCatching {
                                val file = File(local)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = entity.mimeType ?: "image/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, shareChooserTitle))
                            }
                        },
                    )
                    ViewerActionItem(
                        icon = if (entity.isPinnedOffline) Icons.Filled.OfflinePin else Icons.Outlined.OfflinePin,
                        label = stringResource(
                            if (entity.isPinnedOffline) R.string.player_action_pinned else R.string.player_action_pin_offline,
                        ),
                        tint = if (entity.isPinnedOffline) MaterialTheme.colorScheme.tertiary else Color.White,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                val nextPinned = vm.togglePinOffline(entity)
                                siblings = siblings.map { ref ->
                                    if (ref.chatId == entity.chatId && ref.messageId == entity.messageId && ref.entity != null) {
                                        ref.copy(entity = ref.entity.copy(isPinnedOffline = nextPinned))
                                    } else {
                                        ref
                                    }
                                }
                            }
                        },
                    )
                    ViewerActionItem(
                        icon = if (entity.isStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        label = stringResource(
                            if (entity.isStarred) R.string.player_action_starred else R.string.player_action_star,
                        ),
                        tint = if (entity.isStarred) MaterialTheme.colorScheme.primary else Color.White,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                val nextStar = vm.toggleStar(entity)
                                siblings = siblings.map { ref ->
                                    if (ref.chatId == entity.chatId && ref.messageId == entity.messageId && ref.entity != null) {
                                        ref.copy(entity = ref.entity.copy(isStarred = nextStar))
                                    } else {
                                        ref
                                    }
                                }
                            }
                        },
                    )
                    ViewerActionItem(
                        icon = Icons.Filled.Info,
                        label = stringResource(R.string.player_action_info),
                        onClick = { showExifSheet = true },
                    )
                    ViewerActionItem(
                        icon = Icons.Filled.DeleteOutline,
                        label = stringResource(R.string.player_action_trash),
                        tint = MaterialTheme.colorScheme.error,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                vm.moveToTrash(entity)
                                Toast.makeText(context, trashedMsg, Toast.LENGTH_SHORT).show()
                                val remaining = siblings.filterNot {
                                    it.chatId == entity.chatId && it.messageId == entity.messageId
                                }
                                if (remaining.isEmpty()) {
                                    onBack()
                                } else {
                                    siblings = remaining
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showCropDialog && activePath != null) {
        var aspectChoice by remember { mutableStateOf<Float?>(null) }
        var saving by remember { mutableStateOf(false) }
        val activeRotation = rotations[activeRef.chatId to activeRef.messageId] ?: 0f
        AlertDialog(
            onDismissRequest = { if (!saving) showCropDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.player_crop_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "${activeRef.entity?.fileName ?: ""} · ${(activeRotation % 360f).toInt()}°",
                        style = TgTypography.Mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            null to stringResource(R.string.player_crop_original),
                            1f to "1:1",
                            4f / 3f to "4:3",
                            16f / 9f to "16:9",
                        ).forEach { (ratio, label) ->
                            FilterChip(
                                selected = aspectChoice == ratio,
                                onClick = { aspectChoice = ratio },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            val outName = vm.saveCroppedCopyToDownloads(
                                sourcePath = activePath,
                                originalName = activeRef.entity?.fileName ?: "photo.jpg",
                                rotationDeg = activeRotation,
                                targetAspect = aspectChoice,
                            )
                            saving = false
                            showCropDialog = false
                            val msg = if (outName != null) {
                                context.getString(R.string.player_crop_saved, outName)
                            } else {
                                context.getString(R.string.player_crop_failed)
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Text(stringResource(R.string.player_crop_save_btn))
                }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { showCropDialog = false }) {
                    Text(stringResource(R.string.player_back))
                }
            },
        )
    }

    if (showExifSheet) {
        val currentEntity = activeRef.entity
        var noteDraft by remember(activeRef.chatId, activeRef.messageId, currentEntity?.userNote) {
            mutableStateOf(currentEntity?.userNote.orEmpty())
        }
        val noteSavedMsg = stringResource(R.string.player_exif_note_saved)
        ModalBottomSheet(
            onDismissRequest = { showExifSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            val info = exifInfo
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.player_exif_title),
                    style = TgTypography.Display.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                if (info != null) {
                    Text(
                        text = info.fileName,
                        style = TgTypography.Display.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (info.fileSize > 0L) {
                        ExifMetaRow(stringResource(R.string.player_exif_size), info.fileSize.toHumanSize())
                    }
                    if (info.width > 0 && info.height > 0) {
                        ExifMetaRow(stringResource(R.string.player_exif_resolution), "${info.width} × ${info.height}")
                    }
                    info.dateTaken?.let { ExifMetaRow(stringResource(R.string.player_exif_date), it) }
                    info.camera?.let { ExifMetaRow(stringResource(R.string.player_exif_camera), it) }
                    info.exposure?.let { ExifMetaRow(stringResource(R.string.player_exif_details), it) }
                    info.location?.let { locStr ->
                        val lat = info.latitude
                        val lng = info.longitude
                        if (lat != null && lng != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        runCatching {
                                            val uri = Uri.parse(
                                                "geo:$lat,$lng?q=$lat,$lng(${Uri.encode(info.fileName)})",
                                            )
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, uri)
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.player_exif_location),
                                    style = TgTypography.Display.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = locStr,
                                        style = TgTypography.Mono,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Icon(
                                        Icons.Filled.Map,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        } else {
                            ExifMetaRow(stringResource(R.string.player_exif_location), locStr)
                        }
                    }
                }
                if (currentEntity != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it },
                        label = { Text(stringResource(R.string.player_exif_note_label)) },
                        placeholder = { Text(stringResource(R.string.player_exif_note_hint)) },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val saved = vm.updateUserNote(currentEntity.chatId, currentEntity.messageId, noteDraft)
                                siblings = siblings.map { ref ->
                                    if (ref.chatId == currentEntity.chatId &&
                                        ref.messageId == currentEntity.messageId &&
                                        ref.entity != null
                                    ) {
                                        ref.copy(entity = ref.entity.copy(userNote = saved.ifEmpty { null }))
                                    } else {
                                        ref
                                    }
                                }
                                Toast.makeText(context, noteSavedMsg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.player_exif_note_save))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ViewerActionItem(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val effectiveTint = if (enabled) tint else tint.copy(alpha = 0.4f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = effectiveTint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = effectiveTint,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun ExifMetaRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TgTypography.Display.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = TgTypography.Mono,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
