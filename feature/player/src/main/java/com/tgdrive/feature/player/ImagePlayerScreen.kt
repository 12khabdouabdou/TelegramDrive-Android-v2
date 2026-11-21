package com.tgdrive.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.designsystem.theme.TgTypography
import java.io.File

private data class GalleryItemRef(
    val chatId: Long,
    val messageId: Long,
    val entity: FileCacheEntity?,
)

/**
 * Swipeable full-screen image gallery viewer backed by HorizontalPager across
 * sibling images in the current chat, with per-page download tracking, auto-prefetch
 * of adjacent images, and pinch/double-tap zoom.
 */
@Composable
fun ImagePlayerScreen(
    messageId: Long,
    onBack: () -> Unit,
    chatId: Long = 0L,
    vm: VideoPlayerViewModel = hiltViewModel(),
) {
    val downloadState by vm.downloadManager.state.collectAsStateWithLifecycle()
    var siblings by remember(chatId, messageId) { mutableStateOf<List<GalleryItemRef>>(emptyList()) }
    var showChrome by remember(chatId, messageId) { mutableStateOf(true) }
    var isCurrentZoomed by remember { mutableStateOf(false) }

    LaunchedEffect(chatId, messageId) {
        val loaded = if (chatId != 0L) vm.loadSiblingImages(chatId) else emptyList()
        siblings = if (loaded.any { it.messageId == messageId }) {
            loaded.map { GalleryItemRef(it.chatId, it.messageId, it) }
        } else {
            val single = vm.getCachedEntry(chatId, messageId)
            listOf(GalleryItemRef(chatId, messageId, single))
        }
    }

    val pages = siblings.ifEmpty { listOf(GalleryItemRef(chatId, messageId, null)) }
    val targetIdx = remember(pages, messageId) {
        pages.indexOfFirst { it.messageId == messageId }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = targetIdx) { pages.size }

    LaunchedEffect(targetIdx, pages.size) {
        if (pagerState.currentPage != targetIdx && targetIdx < pages.size) {
            pagerState.scrollToPage(targetIdx)
        }
    }

    LaunchedEffect(pagerState.currentPage, pages) {
        isCurrentZoomed = false
        val cur = pages.getOrNull(pagerState.currentPage)
        cur?.entity?.let { entry ->
            if (vm.getLocalPath(entry.messageId, entry.chatId) == null &&
                vm.downloadManager.findItem(entry.chatId, entry.messageId) == null
            ) {
                vm.ensureDownloaded(entry)
            }
        }
        val next = pages.getOrNull(pagerState.currentPage + 1)
        next?.entity?.let { nextEntry ->
            if (vm.getLocalPath(nextEntry.messageId, nextEntry.chatId) == null &&
                vm.downloadManager.findItem(nextEntry.chatId, nextEntry.messageId) == null &&
                nextEntry.fileSize <= 15L * 1024 * 1024
            ) {
                vm.ensureDownloaded(nextEntry)
            }
        }
    }

    val activeRef = pages.getOrNull(pagerState.currentPage) ?: pages.first()
    val activePath = remember(activeRef, downloadState.items) {
        vm.getLocalPath(activeRef.messageId, activeRef.chatId)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isCurrentZoomed && pages.size > 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val ref = pages[page]
            val pagePath = vm.getLocalPath(ref.messageId, ref.chatId)
            val downloadItem = downloadState.items.find {
                it.id == ref.messageId && (ref.chatId == 0L || it.chatId == 0L || it.chatId == ref.chatId)
            }
            val isDownloading =
                downloadItem?.status == DownloadStatus.DOWNLOADING ||
                    downloadItem?.status == DownloadStatus.QUEUED
            val isFailed = downloadItem?.status == DownloadStatus.FAILED
            val downloadProgress = downloadItem?.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            val fileReady = pagePath != null && runCatching { File(pagePath).exists() }.getOrDefault(false)

            when {
                pagePath != null && fileReady -> {
                    ZoomableImagePage(
                        path = pagePath,
                        isActive = page == pagerState.currentPage,
                        onZoomChanged = { zoomed ->
                            if (page == pagerState.currentPage) isCurrentZoomed = zoomed
                        },
                        onTap = { showChrome = !showChrome },
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
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.player_downloading_pct, (downloadProgress * 100).toInt()),
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.2f),
                            modifier = Modifier.fillMaxWidth(0.7f).clip(RoundedCornerShape(4.dp)),
                        )
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
    }
}
