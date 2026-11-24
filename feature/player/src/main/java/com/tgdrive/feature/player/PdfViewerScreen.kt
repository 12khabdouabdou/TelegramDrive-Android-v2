package com.tgdrive.feature.player

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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
    val pageCount: Int,
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
    initialLocalPath: String? = null,
    initialTitle: String? = null,
    vm: VideoPlayerViewModel = hiltViewModel(),
) {
    var pageCount by remember(messageId, initialLocalPath) { mutableIntStateOf(0) }
    var session by remember(messageId, initialLocalPath) { mutableStateOf<PdfRendererSession?>(null) }
    var loading by remember(messageId, initialLocalPath) { mutableStateOf(true) }
    var error by remember(messageId, initialLocalPath) { mutableStateOf<String?>(null) }
    var localPath by remember(messageId, initialLocalPath) {
        mutableStateOf<String?>(initialLocalPath?.takeIf { it.isNotBlank() } ?: vm.getLocalPath(messageId))
    }

    val downloadState by vm.downloadManager.state.collectAsStateWithLifecycle()
    val downloadItem = downloadState.items.find { it.id == messageId }
    val isDownloading =
        initialLocalPath.isNullOrBlank() && (
            downloadItem?.status == DownloadStatus.DOWNLOADING ||
                downloadItem?.status == DownloadStatus.QUEUED
            )
    val isFailed = initialLocalPath.isNullOrBlank() && downloadItem?.status == DownloadStatus.FAILED

    LaunchedEffect(messageId, initialLocalPath, downloadState.items) {
        if (!initialLocalPath.isNullOrBlank()) return@LaunchedEffect
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
                    val count = renderer.pageCount
                    PdfRendererSession(fd = fd, renderer = renderer, pageCount = count)
                } catch (t: Throwable) {
                    runCatching { fd.close() }
                    throw t
                }
            }
            session = opened
            pageCount = opened.pageCount
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
    var scale by remember(messageId, initialLocalPath) { mutableFloatStateOf(1f) }
    var offset by remember(messageId, initialLocalPath) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var nightMode by remember { mutableStateOf(false) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var showOcrSearch by remember { mutableStateOf(false) }
    var ocrQuery by remember { mutableStateOf("") }
    var ocrScanning by remember { mutableStateOf(false) }
    var ocrScannedCount by remember(messageId, initialLocalPath) { mutableIntStateOf(0) }
    val ocrTextByPage = remember(messageId, initialLocalPath) { mutableStateMapOf<Int, String>() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val bottomNavPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val displayTitle = initialTitle?.takeIf { it.isNotBlank() }
        ?: downloadItem?.fileName
        ?: localPath?.let { File(it).name }
        ?: "PDF"
    val savedMsg = stringResource(R.string.player_saved_downloads)
    val saveFailMsg = stringResource(R.string.player_save_failed)
    val matchingPages = remember(ocrQuery, ocrTextByPage.toMap()) {
        val q = ocrQuery.trim()
        if (q.isBlank()) {
            emptyList()
        } else {
            ocrTextByPage.entries
                .filter { it.value.contains(q, ignoreCase = true) }
                .map { it.key }
                .sorted()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.padding(bottom = bottomNavPad)) },
    ) { _ ->
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
                        text = displayTitle,
                        style = TgTypography.Display.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (pageCount > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { showJumpDialog = true },
                        ) {
                            Text(
                                text = stringResource(R.string.player_pdf_page_counter, currentPage, pageCount),
                                style = TgTypography.Caption,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (pageCount > 0 && session != null) {
                    IconButton(onClick = { showOcrSearch = !showOcrSearch }) {
                        Icon(
                            imageVector = if (showOcrSearch) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = stringResource(R.string.player_pdf_ocr_search),
                            tint = if (showOcrSearch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { nightMode = !nightMode }) {
                        Icon(
                            imageVector = Icons.Filled.DarkMode,
                            contentDescription = stringResource(R.string.player_pdf_night_mode),
                            tint = if (nightMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (!localPath.isNullOrBlank()) {
                    IconButton(onClick = {
                        val src = File(localPath!!)
                        scope.launch {
                            val saved = vm.saveFileToPublicDownloads(
                                source = src,
                                displayName = displayTitle.let { if (it.endsWith(".pdf", true)) it else "$it.pdf" },
                                mimeType = "application/pdf",
                            )
                            snackbarHostState.showSnackbar(if (saved != null) savedMsg else saveFailMsg)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.SaveAlt,
                            contentDescription = stringResource(R.string.player_pdf_save_downloads),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = {
                        val file = File(localPath!!)
                        if (file.exists()) {
                            runCatching {
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(share, displayTitle))
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.player_pdf_share),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            if (showOcrSearch && session != null && pageCount > 0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = ocrQuery,
                        onValueChange = { ocrQuery = it },
                        placeholder = { Text(stringResource(R.string.player_pdf_ocr_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = ocrScanning,
                            onClick = {
                                if (ocrScanning) return@FilterChip
                                val activeSession = session ?: return@FilterChip
                                ocrScanning = true
                                scope.launch {
                                    try {
                                        val limit = minOf(pageCount, 40)
                                        for (pIdx in 0 until limit) {
                                            if (ocrTextByPage.containsKey(pIdx)) continue
                                            val rendered = withContext(Dispatchers.IO) {
                                                activeSession.mutex.withLock {
                                                    if (activeSession.isClosed) return@withLock null
                                                    val p = activeSession.renderer.openPage(pIdx)
                                                    try {
                                                        val w = (p.width * 1.4f).toInt().coerceIn(1, 1600)
                                                        val aspect = p.height.toFloat() / p.width.coerceAtLeast(1).toFloat()
                                                        val h = (w * aspect).toInt().coerceIn(1, 2200)
                                                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                                        Canvas(bmp).drawColor(Color.WHITE)
                                                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                                        bmp
                                                    } finally {
                                                        p.close()
                                                    }
                                                }
                                            }
                                            if (rendered != null) {
                                                val txt = try {
                                                    vm.recognizeBitmapText(rendered)
                                                } finally {
                                                    if (!rendered.isRecycled) rendered.recycle()
                                                }
                                                ocrTextByPage[pIdx] = txt
                                                ocrScannedCount = ocrTextByPage.size
                                            }
                                        }
                                    } finally {
                                        ocrScanning = false
                                    }
                                }
                            },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.player_pdf_ocr_scan_all,
                                        ocrScannedCount,
                                        minOf(pageCount, 40),
                                    ),
                                )
                            },
                        )
                        if (ocrQuery.isNotBlank()) {
                            Text(
                                text = if (matchingPages.isEmpty()) {
                                    stringResource(R.string.player_pdf_ocr_no_matches)
                                } else {
                                    "${matchingPages.size} page(s)"
                                },
                                style = TgTypography.Caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (matchingPages.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (pageIdx in matchingPages) {
                                AssistChip(
                                    onClick = { scope.launch { listState.scrollToItem(pageIdx) } },
                                    label = { Text("p.${pageIdx + 1}") },
                                )
                            }
                        }
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
                    val clamped = downloadItem?.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
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
                pageCount == 0 || session == null || localPath == null -> {
                    Text(
                        text = stringResource(R.string.player_pdf_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    val activeSession = session
                    val path = localPath
                    if (activeSession != null && path != null) {
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
                                PdfPage(
                                    session = activeSession,
                                    path = path,
                                    pageIndex = pageIndex,
                                    nightMode = nightMode,
                                )
                            }
                        }
                    }
                }
            }
        }
        }

        if (showJumpDialog && pageCount > 0) {
            var jumpDraft by remember(currentPage) { mutableStateOf(currentPage.toString()) }
            val parsed = jumpDraft.trim().toIntOrNull()
            val validPage = parsed != null && parsed in 1..pageCount
            AlertDialog(
                onDismissRequest = { showJumpDialog = false },
                title = { Text(stringResource(R.string.player_pdf_jump_title)) },
                text = {
                    OutlinedTextField(
                        value = jumpDraft,
                        onValueChange = { jumpDraft = it.filter { ch -> ch.isDigit() } },
                        label = { Text(stringResource(R.string.player_pdf_jump_hint, pageCount)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = validPage,
                        onClick = {
                            val targetIdx = (parsed!! - 1).coerceIn(0, pageCount - 1)
                            showJumpDialog = false
                            scope.launch { listState.scrollToItem(targetIdx) }
                        },
                    ) {
                        Text(stringResource(R.string.player_pdf_jump_go))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showJumpDialog = false }) {
                        Text(stringResource(R.string.player_back))
                    }
                },
            )
        }
    }
}

private val NIGHT_INVERT_COLOR_FILTER by lazy {
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

@Composable
private fun PdfPage(
    session: PdfRendererSession,
    path: String,
    pageIndex: Int,
    nightMode: Boolean = false,
) {
    var bitmap by remember(session, path, pageIndex) { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(session, path, pageIndex) {
        onDispose {
            bitmap = null
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
            colorFilter = if (nightMode) NIGHT_INVERT_COLOR_FILTER else null,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (nightMode) {
                        androidx.compose.ui.graphics.Color(0xFF121212)
                    } else {
                        androidx.compose.ui.graphics.Color.White
                    },
                ),
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
