package com.tgdrive.feature.player

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.DownloadStatus
import com.tgdrive.designsystem.theme.TgTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

private const val MAX_INLINE_TEXT_BYTES = 1024 * 1024

private data class LoadedTextContent(
    val lines: List<String>,
    val fullText: String,
    val totalFileBytes: Long,
    val truncated: Boolean,
)

/**
 * In-app viewer for plain text, Markdown, JSON, logs, CSV, YAML, and source code files
 * with line-number toggle, horizontal scroll / line wrap toggle, in-file search, copy, and share.
 */
@Composable
fun TextViewerScreen(
    messageId: Long,
    onBack: () -> Unit,
    chatId: Long = 0L,
    vm: VideoPlayerViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val downloadState by vm.downloadManager.state.collectAsStateWithLifecycle()
    var cachedEntry by remember(chatId, messageId) { mutableStateOf<FileCacheEntity?>(null) }
    var localPath by remember(chatId, messageId) { mutableStateOf<String?>(vm.getLocalPath(messageId, chatId)) }
    var content by remember(chatId, messageId) { mutableStateOf<LoadedTextContent?>(null) }
    var readError by remember(chatId, messageId) { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showLineNumbers by remember { mutableStateOf(true) }
    var wrapLines by remember { mutableStateOf(false) }

    val downloadItem = downloadState.items.find {
        it.id == messageId && (chatId == 0L || it.chatId == 0L || it.chatId == chatId)
    }
    val isDownloading =
        downloadItem?.status == DownloadStatus.DOWNLOADING ||
            downloadItem?.status == DownloadStatus.QUEUED
    val isFailed = downloadItem?.status == DownloadStatus.FAILED
    val downloadProgress = downloadItem?.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

    LaunchedEffect(chatId, messageId) {
        val entry = vm.getCachedEntry(chatId, messageId)
        cachedEntry = entry
        if (vm.getLocalPath(messageId, chatId) == null && entry != null && vm.downloadManager.findItem(chatId, messageId) == null) {
            vm.ensureDownloaded(entry)
        }
    }

    LaunchedEffect(chatId, messageId, downloadState.items) {
        val path = vm.getLocalPath(messageId, chatId)
        if (path != null && path != localPath) {
            localPath = path
            readError = null
        }
    }

    LaunchedEffect(localPath) {
        val path = localPath ?: return@LaunchedEffect
        readError = null
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                val totalLen = file.length()
                val readCap = minOf(totalLen, MAX_INLINE_TEXT_BYTES.toLong()).toInt()
                val bytes = ByteArray(readCap)
                RandomAccessFile(file, "r").use { raf ->
                    raf.readFully(bytes)
                }
                val text = bytes.toString(Charsets.UTF_8)
                LoadedTextContent(
                    lines = text.split('\n'),
                    fullText = text,
                    totalFileBytes = totalLen,
                    truncated = totalLen > MAX_INLINE_TEXT_BYTES,
                )
            }
        }
        loaded.onSuccess { content = it }
            .onFailure { err ->
                Log.w("TextViewerScreen: failed to read $path", err)
                readError = err.message ?: "Read error"
            }
    }

    val copiedMsg = stringResource(R.string.player_text_copied)
    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val filteredLines = remember(content, query) {
        val all = content?.lines ?: emptyList()
        if (query.isBlank()) {
            all.mapIndexed { idx, line -> idx + 1 to line }
        } else {
            val q = query.trim()
            all.mapIndexedNotNull { idx, line ->
                if (line.contains(q, ignoreCase = true)) (idx + 1 to line) else null
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState, modifier = Modifier.padding(bottom = navPad)) },
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.player_back),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = cachedEntry?.fileName ?: downloadItem?.fileName ?: "Document",
                    style = TgTypography.Display.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showSearch = !showSearch; if (!showSearch) query = "" }) {
                    Icon(
                        if (showSearch) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = stringResource(R.string.player_text_search_hint),
                    )
                }
                if (content != null) {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(content!!.fullText))
                        scope.launch { snackbarHostState.showSnackbar(copiedMsg) }
                    }) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.player_text_copy),
                        )
                    }
                }
                if (localPath != null) {
                    IconButton(onClick = {
                        val file = File(localPath!!)
                        if (file.exists()) {
                            runCatching {
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = cachedEntry?.mimeType ?: "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(intent, cachedEntry?.fileName ?: file.name))
                            }
                        }
                    }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.player_text_share),
                        )
                    }
                }
            }

            if (showSearch) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.player_text_search_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.player_text_matches, filteredLines.size),
                                style = TgTypography.Caption,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 10.dp),
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (content != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = showLineNumbers,
                        onClick = { showLineNumbers = !showLineNumbers },
                        label = { Text(stringResource(R.string.player_text_line_numbers)) },
                    )
                    FilterChip(
                        selected = wrapLines,
                        onClick = { wrapLines = !wrapLines },
                        label = { Text(stringResource(R.string.player_text_wrap)) },
                    )
                    if (content!!.truncated) {
                        Text(
                            text = stringResource(
                                R.string.player_text_truncated,
                                MAX_INLINE_TEXT_BYTES / 1024,
                                content!!.totalFileBytes.toHumanSize(),
                            ),
                            style = TgTypography.Caption,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val loaded = content
                when {
                    loaded != null -> {
                        val hScroll = rememberScrollState()
                        val listModifier = if (wrapLines) {
                            Modifier.fillMaxSize().padding(horizontal = 14.dp)
                        } else {
                            Modifier.fillMaxSize().horizontalScroll(hScroll).padding(horizontal = 14.dp)
                        }
                        SelectionContainer {
                            LazyColumn(
                                modifier = listModifier,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    top = 8.dp,
                                    bottom = navPad + 24.dp,
                                ),
                            ) {
                                itemsIndexed(filteredLines, key = { _, pair -> pair.first }) { _, (lineNo, lineText) ->
                                    Row(
                                        modifier = Modifier.padding(vertical = 1.dp),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        if (showLineNumbers) {
                                            Text(
                                                text = lineNo.toString().padStart(4, ' '),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                modifier = Modifier.padding(end = 10.dp),
                                            )
                                        }
                                        Text(
                                            text = lineText,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            lineHeight = 19.sp,
                                            softWrap = wrapLines,
                                            color = MaterialTheme.colorScheme.onBackground,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    readError != null -> {
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
                                text = readError ?: stringResource(R.string.player_text_error),
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
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
                                color = MaterialTheme.colorScheme.onBackground,
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
                                Icons.Filled.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp),
                            )
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text(
                                text = stringResource(R.string.player_downloading_pct, (downloadProgress * 100).toInt()),
                                fontWeight = FontWeight.Medium,
                            )
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth(0.7f).clip(RoundedCornerShape(4.dp)),
                            )
                        }
                    }
                    else -> {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}
