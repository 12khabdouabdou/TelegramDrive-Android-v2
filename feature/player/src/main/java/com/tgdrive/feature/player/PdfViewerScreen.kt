package com.tgdrive.feature.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.common.Log
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.designsystem.theme.TgTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private class PdfRendererSession(
    val fd: ParcelFileDescriptor,
    val renderer: PdfRenderer,
    val mutex: Mutex = Mutex(),
) {
    @Volatile var isClosed = false

    fun closeAsync() {
        isClosed = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            mutex.withLock {
                runCatching { renderer.close() }
                runCatching { fd.close() }
            }
        }
    }
}

/**
 * In-app PDF viewer with IO-threaded PdfRenderer, white page backing,
 * pinch/double-tap zoom, live page counter, and download retry state.
 */
@Composable
fun PdfViewerScreen(
    messageId: Long,
    onBack: () -> Unit,
    vm: VideoPlayerViewModel = hiltViewModel(),
) {
    var pageCount by remember(messageId) { mutableIntStateOf(0) }
    var session by remember(messageId) { mutableStateOf<PdfRendererSession?>(null) }
    var loading by remember(messageId) { mutableStateOf(true) }
    var error by remember(messageId) { mutableStateOf<String?>(null) }
    var localPath by remember(messageId) { mutableStateOf<String?>(vm.getLocalPath(messageId)) }

    val downloadState by vm.downloadManager.state.collectAsStateWithLifecycle()
    val downloadItem = downloadState.items.find { it.id == messageId }
    val isDownloading =
        downloadItem?.status == DownloadStatus.DOWNLOADING ||
            downloadItem?.status == DownloadStatus.QUEUED
    val isFailed = downloadItem?.status == DownloadStatus.FAILED

    LaunchedEffect(messageId, downloadState.items) {
        val path = vm.getLocalPath(messageId)
        if (path != null && path != localPath) {
            localPath = path
            error = null
        }
    }

    DisposableEffect(session) {
        val current = session
        onDispose { current?.closeAsync() }
    }

    LaunchedEffect(localPath) {
        val path = localPath ?: return@LaunchedEffect
        loading = true
        error = null
        session = null
        try {
            val file = File(path)
            if (!file.exists()) {
                error = "PDF file is missing on disk"
                loading = false
                return@LaunchedEffect
            }
            val opened = withContext(Dispatchers.IO) {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    val renderer = PdfRenderer(fd)
                    PdfRendererSession(fd = fd, renderer = renderer)
                } catch (t: Throwable) {
                    runCatching { fd.close() }
                    throw t
                }
            }
            session = opened
            pageCount = opened.renderer.pageCount
            loading = false
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w("PdfViewer: failed to open PDF", t)
            error = t.message
            loading = false
        }
    }

    val listState = rememberLazyListState()
    val currentPage by remember(pageCount) {
        derivedStateOf {
            if (pageCount <= 0) 0 else (listState.firstVisibleItemIndex + 1).coerceIn(1, pageCount)
        }
    }
    var scale by remember(messageId) { mutableFloatStateOf(1f) }
    var offset by remember(messageId) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val bottomNavPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.player_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = downloadItem?.fileName ?: "PDF",
                    style = TgTypography.Display.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (pageCount > 0) {
                    Text(
                        text = stringResource(R.string.player_pdf_page_counter, currentPage, pageCount),
                        style = TgTypography.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { containerSize = it },
            contentAlignment = Alignment.Center,
        ) {
            when {
                localPath == null && isFailed -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
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
                            color = MaterialTheme.colorScheme.error,
                            style = TgTypography.Display.bodyMedium,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { downloadItem?.let { vm.downloadManager.enqueue(it) } }) {
                            Text(stringResource(R.string.player_retry))
                        }
                    }
                }
                localPath == null || isDownloading -> {
                    val clamped = (downloadItem?.progress ?: 0f).coerceIn(0f, 1f)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.player_downloading_pct, (clamped * 100).toInt()),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { clamped },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth(0.7f).clip(RoundedCornerShape(4.dp)),
                        )
                    }
                }
                loading -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                error != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Icon(
                            Icons.Filled.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = error ?: stringResource(R.string.player_pdf_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                pageCount == 0 || session == null -> {
                    Text(
                        text = stringResource(R.string.player_pdf_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    val activeSession = session!!
                    val path = localPath!!
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(messageId) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (scale > 1.05f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2f
                                        }
                                    },
                                )
                            }
                            .pointerInput(messageId) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val pointerCount = event.changes.count { it.pressed }
                                        if (pointerCount >= 2 || scale > 1.02f) {
                                            val zoom = event.calculateZoom()
                                            val pan = event.calculatePan()
                                            val nextScale = (scale * zoom).coerceIn(1f, 4f)
                                            val maxX = ((containerSize.width * (nextScale - 1f)) / 2f).coerceAtLeast(0f)
                                            val maxY = ((containerSize.height * (nextScale - 1f)) / 2f).coerceAtLeast(0f)
                                            scale = nextScale
                                            offset = if (nextScale <= 1.02f) {
                                                Offset.Zero
                                            } else {
                                                Offset(
                                                    x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                                    y = (offset.y + pan.y).coerceIn(-maxY, maxY),
                                                )
                                            }
                                            if (pointerCount >= 2) {
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            top = 12.dp,
                            end = 12.dp,
                            bottom = bottomNavPad + 16.dp,
                        ),
                    ) {
                        items(pageCount) { pageIndex ->
                            PdfPage(session = activeSession, path = path, pageIndex = pageIndex)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(session: PdfRendererSession, path: String, pageIndex: Int) {
    var bitmap by remember(session, path, pageIndex) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(session, path, pageIndex) {
        onDispose {
            val old = bitmap
            bitmap = null
            if (old != null && !old.isRecycled) old.recycle()
        }
    }

    LaunchedEffect(session, path, pageIndex) {
        val bmp = withContext(Dispatchers.IO) {
            try {
                session.mutex.withLock {
                    if (session.isClosed) return@withContext null
                    val page = session.renderer.openPage(pageIndex)
                    try {
                        val targetW = (page.width * 1.5f).toInt().coerceIn(1, 2048)
                        val aspect = page.height.toFloat() / page.width.coerceAtLeast(1).toFloat()
                        val targetH = (targetW * aspect).toInt().coerceIn(1, 3072)
                        val b = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        Canvas(b).drawColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    } finally {
                        page.close()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("PdfPage: render error on page $pageIndex", t)
                null
            }
        }
        bitmap = bmp
    }

    val bmp = bitmap
    if (bmp != null && !bmp.isRecycled) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = stringResource(R.string.player_pdf_page_desc, pageIndex + 1),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(androidx.compose.ui.graphics.Color.White),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
