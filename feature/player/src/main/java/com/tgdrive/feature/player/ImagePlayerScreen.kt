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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.designsystem.theme.TgTypography
import java.io.File

/**
 * Image player screen — watches download state, handles failure/retry,
 * toggles overlay chrome on tap, and delegates rendering to [ImageViewer].
 */
@Composable
fun ImagePlayerScreen(
    messageId: Long,
    onBack: () -> Unit,
    vm: VideoPlayerViewModel = hiltViewModel(),
) {
    val downloadState by vm.downloadManager.state.collectAsStateWithLifecycle()
    var localPath by remember(messageId) { mutableStateOf<String?>(vm.getLocalPath(messageId)) }
    var showChrome by remember(messageId) { mutableStateOf(true) }

    val downloadItem = downloadState.items.find { it.id == messageId }
    val isDownloading =
        downloadItem?.status == DownloadStatus.DOWNLOADING ||
            downloadItem?.status == DownloadStatus.QUEUED
    val isFailed = downloadItem?.status == DownloadStatus.FAILED
    val downloadProgress = downloadItem?.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

    LaunchedEffect(messageId, downloadState.items) {
        val path = vm.getLocalPath(messageId)
        if (path != null && path != localPath) {
            localPath = path
        }
    }

    val resolvedPath = localPath
    val fileReady = remember(resolvedPath) {
        resolvedPath != null && runCatching { File(resolvedPath).exists() }.getOrDefault(false)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            resolvedPath != null && fileReady -> {
                ImageViewer(
                    imagePaths = listOf(resolvedPath),
                    onDismiss = onBack,
                    onToggleChrome = { showChrome = !showChrome },
                )
            }
            isFailed -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
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
            isDownloading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = stringResource(R.string.player_downloading_pct, (downloadProgress * 100).toInt()),
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                    )
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
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
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

        AnimatedVisibility(
            visible = showChrome || localPath == null,
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
            }
        }
    }
}
