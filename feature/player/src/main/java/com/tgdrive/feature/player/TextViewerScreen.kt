package com.tgdrive.feature.player

import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
    initialLocalPath: String? = null,
    initialTitle: String? = null,
    vm: VideoPlayerViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val downloadState by vm.downloadManager.state.collectAsStateWithLifecycle()
    var cachedEntry by remember(chatId, messageId) { mutableStateOf<FileCacheEntity?>(null) }
    val isEncryptedSource = cachedEntry?.isEncryptedVault == true ||
        cachedEntry?.fileName?.endsWith(".tgenc", ignoreCase = true) == true ||
        initialLocalPath?.contains("/decrypted/") == true
    SecureWindowForSensitiveEntry(
        isSensitive = cachedEntry?.isLocked == true || isEncryptedSource,
    )
    var localPath by remember(chatId, messageId, initialLocalPath) {
        mutableStateOf<String?>(initialLocalPath?.takeIf { it.isNotBlank() } ?: vm.getLocalPath(messageId, chatId))
    }
    var content by remember(chatId, messageId, initialLocalPath) { mutableStateOf<LoadedTextContent?>(null) }
    var readError by remember(chatId, messageId, initialLocalPath) { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showLineNumbers by remember { mutableStateOf(true) }
    var wrapLines by remember { mutableStateOf(false) }

    val downloadItem = downloadState.items.find {
        it.id == messageId && (chatId == 0L || it.chatId == 0L || it.chatId == chatId)
    }
    val isDownloading =
        initialLocalPath.isNullOrBlank() && (
            downloadItem?.status == DownloadStatus.DOWNLOADING ||
                downloadItem?.status == DownloadStatus.QUEUED
            )
    val isFailed = initialLocalPath.isNullOrBlank() && downloadItem?.status == DownloadStatus.FAILED
    val downloadProgress = downloadItem?.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

    LaunchedEffect(chatId, messageId, initialLocalPath) {
        if (!initialLocalPath.isNullOrBlank()) return@LaunchedEffect
        val entry = vm.getCachedEntry(chatId, messageId)
        cachedEntry = entry
        if (vm.getLocalPath(messageId, chatId) == null && entry != null && vm.downloadManager.findItem(chatId, messageId) == null) {
            vm.ensureDownloaded(entry)
        }
    }

    LaunchedEffect(chatId, messageId, initialLocalPath, downloadState.items) {
        if (!initialLocalPath.isNullOrBlank()) return@LaunchedEffect
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
                val isTruncated = totalLen > MAX_INLINE_TEXT_BYTES
                val text = decodeUtf8Safe(bytes, truncated = isTruncated)
                LoadedTextContent(
                    lines = text.split('\n'),
                    fullText = text,
                    totalFileBytes = totalLen,
                    truncated = isTruncated,
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
    val savedMsg = stringResource(R.string.player_saved_downloads)
    val saveFailMsg = stringResource(R.string.player_save_failed)
    val uploadQueuedMsg = stringResource(R.string.player_text_upload_queued)
    val resolvedTitle = initialTitle?.takeIf { it.isNotBlank() }
        ?: cachedEntry?.fileName
        ?: downloadItem?.fileName
        ?: localPath?.let { File(it).name }
        ?: "Document.txt"
    val ext = remember(resolvedTitle) { resolvedTitle.substringAfterLast('.', "").lowercase() }
    val isMarkdown = ext == "md" || ext == "markdown"
    val isJson = ext == "json"
    var fontSizeSp by remember { mutableFloatStateOf(13f) }
    var markdownMode by remember(resolvedTitle) { mutableStateOf(isMarkdown) }
    var jsonPrettyMode by remember(resolvedTitle) { mutableStateOf(isJson) }
    var editMode by remember { mutableStateOf(false) }
    var editDraft by remember(content) { mutableStateOf(content?.fullText.orEmpty()) }
    var showDiscardEditDialog by remember { mutableStateOf(false) }
    val hasUnsavedEdits = editMode && editDraft != content?.fullText.orEmpty()
    BackHandler(enabled = hasUnsavedEdits) {
        showDiscardEditDialog = true
    }
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
                IconButton(onClick = {
                    if (hasUnsavedEdits) {
                        showDiscardEditDialog = true
                    } else {
                        onBack()
                    }
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.player_back),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = initialTitle?.takeIf { it.isNotBlank() }
                        ?: cachedEntry?.fileName
                        ?: downloadItem?.fileName
                        ?: localPath?.let { File(it).name }
                        ?: "Document",
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
                    IconButton(onClick = { fontSizeSp = (fontSizeSp - 2f).coerceAtLeast(11f) }) {
                        Text(
                            text = "A-",
                            style = TgTypography.Caption,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    IconButton(onClick = { fontSizeSp = (fontSizeSp + 2f).coerceAtMost(24f) }) {
                        Text(
                            text = "A+",
                            style = TgTypography.Caption,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    if (!content!!.truncated) {
                        IconButton(onClick = {
                            if (!editMode) {
                                editDraft = content!!.fullText
                                editMode = true
                            } else if (hasUnsavedEdits) {
                                showDiscardEditDialog = true
                            } else {
                                editMode = false
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.player_text_edit),
                                tint = if (editMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                    IconButton(onClick = {
                        val copyText = if (editMode) editDraft else content!!.fullText
                        clipboard.setText(AnnotatedString(copyText))
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
                                ctx.startActivity(
                                    Intent.createChooser(
                                        intent,
                                        initialTitle?.takeIf { it.isNotBlank() } ?: cachedEntry?.fileName ?: file.name,
                                    ),
                                )
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
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!editMode) {
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
                        if (isMarkdown) {
                            FilterChip(
                                selected = markdownMode,
                                onClick = { markdownMode = !markdownMode },
                                label = { Text(stringResource(R.string.player_text_markdown)) },
                            )
                        }
                        if (isJson) {
                            FilterChip(
                                selected = jsonPrettyMode,
                                onClick = { jsonPrettyMode = !jsonPrettyMode },
                                label = { Text(stringResource(R.string.player_text_json_pretty)) },
                            )
                        }
                    } else {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val ok = vm.saveEditedTextToDownloads(resolvedTitle, editDraft)
                                snackbarHostState.showSnackbar(if (ok) savedMsg else saveFailMsg)
                            }
                        }) {
                            Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.player_text_save_copy))
                        }
                        val targetChatId = cachedEntry?.chatId ?: chatId
                        if (targetChatId != 0L) {
                            Button(onClick = {
                                vm.reuploadEditedText(
                                    chatId = targetChatId,
                                    fileName = resolvedTitle,
                                    text = editDraft,
                                    virtualPath = cachedEntry?.virtualPath.orEmpty(),
                                    mimeType = cachedEntry?.mimeType,
                                    encryptWithVaultKey = isEncryptedSource,
                                )
                                val updatedText = editDraft
                                content = content?.let { cur ->
                                    cur.copy(
                                        lines = updatedText.split('\n'),
                                        fullText = updatedText,
                                    )
                                }
                                editMode = false
                                scope.launch { snackbarHostState.showSnackbar(uploadQueuedMsg) }
                            }) {
                                Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(stringResource(R.string.player_text_reupload))
                            }
                        }
                    }
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
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val loaded = content
                when {
                    loaded != null && editMode -> {
                        OutlinedTextField(
                            value = editDraft,
                            onValueChange = { editDraft = it },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSizeSp.sp,
                                lineHeight = (fontSizeSp + 6f).sp,
                            ),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = navPad + 12.dp),
                        )
                    }
                    loaded != null && isMarkdown && markdownMode -> {
                        val renderedMd by produceState<AnnotatedString?>(
                            initialValue = null,
                            key1 = loaded.fullText,
                        ) {
                            value = withContext(Dispatchers.Default) {
                                renderSimpleMarkdown(loaded.fullText)
                            }
                        }
                        if (renderedMd == null) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            SelectionContainer {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        top = 8.dp,
                                        bottom = navPad + 24.dp,
                                    ),
                                ) {
                                    item {
                                        Text(
                                            text = renderedMd!!,
                                            fontSize = fontSizeSp.sp,
                                            lineHeight = (fontSizeSp + 7f).sp,
                                            color = MaterialTheme.colorScheme.onBackground,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    loaded != null && isJson && jsonPrettyMode -> {
                        val keyColor = MaterialTheme.colorScheme.primary
                        val strColor = MaterialTheme.colorScheme.tertiary
                        val numColor = MaterialTheme.colorScheme.secondary
                        val highlighted by produceState<AnnotatedString?>(
                            initialValue = null,
                            key1 = loaded.fullText,
                            key2 = keyColor,
                            key3 = strColor,
                        ) {
                            value = withContext(Dispatchers.Default) {
                                highlightJsonText(prettyPrintJson(loaded.fullText), keyColor, strColor, numColor)
                            }
                        }
                        if (highlighted == null) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            SelectionContainer {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        top = 8.dp,
                                        bottom = navPad + 24.dp,
                                    ),
                                ) {
                                    item {
                                        Text(
                                            text = highlighted!!,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = fontSizeSp.sp,
                                            lineHeight = (fontSizeSp + 6f).sp,
                                            softWrap = wrapLines,
                                            color = MaterialTheme.colorScheme.onBackground,
                                        )
                                    }
                                }
                            }
                        }
                    }
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
                                                fontSize = (fontSizeSp - 1f).coerceAtLeast(10f).sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                modifier = Modifier.padding(end = 10.dp),
                                            )
                                        }
                                        Text(
                                            text = lineText,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = fontSizeSp.sp,
                                            lineHeight = (fontSizeSp + 6f).sp,
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

    if (showDiscardEditDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardEditDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.player_text_discard_title)) },
            text = { Text(stringResource(R.string.player_text_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardEditDialog = false
                    editDraft = content?.fullText.orEmpty()
                    editMode = false
                }) {
                    Text(stringResource(R.string.player_text_discard_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardEditDialog = false }) {
                    Text(stringResource(R.string.player_text_keep_editing))
                }
            },
        )
    }
}

internal fun decodeUtf8Safe(bytes: ByteArray, truncated: Boolean): String {
    if (!truncated || bytes.isEmpty()) return bytes.decodeToString()
    var end = bytes.size
    var i = end - 1
    while (i >= 0 && (end - i) <= 4 && (bytes[i].toInt() and 0xC0) == 0x80) {
        i--
    }
    if (i >= 0 && (end - i) <= 4 && (bytes[i].toInt() and 0x80) != 0) {
        val lead = bytes[i].toInt() and 0xFF
        val expectedLen = when {
            (lead and 0xE0) == 0xC0 -> 2
            (lead and 0xF0) == 0xE0 -> 3
            (lead and 0xF8) == 0xF0 -> 4
            else -> 1
        }
        val actualLen = end - i
        if (expectedLen > 1 && actualLen < expectedLen) {
            end = i
        }
    }
    return bytes.decodeToString(0, end)
}

internal fun prettyPrintJson(raw: String): String = runCatching {
    val t = raw.trimStart('\uFEFF').trim()
    when {
        t.startsWith("{") -> org.json.JSONObject(t).toString(2)
        t.startsWith("[") -> org.json.JSONArray(t).toString(2)
        else -> raw
    }
}.getOrDefault(raw)

private fun highlightJsonText(
    pretty: String,
    keyColor: Color,
    stringColor: Color,
    numberColor: Color,
): AnnotatedString = buildAnnotatedString {
    val tokenRegex = Regex("""("(?:\\.|[^"\\])*")\s*:|("(?:\\.|[^"\\])*")|(\b-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|\btrue\b|\bfalse\b|\bnull\b)""")
    var cursor = 0
    for (m in tokenRegex.findAll(pretty)) {
        if (m.range.first > cursor) append(pretty.substring(cursor, m.range.first))
        when {
            m.groups[1] != null -> {
                val key = m.groups[1]!!.value
                withStyle(SpanStyle(color = keyColor, fontWeight = FontWeight.SemiBold)) { append(key) }
                append(m.value.removePrefix(key))
            }
            m.groups[2] != null -> {
                withStyle(SpanStyle(color = stringColor)) { append(m.value) }
            }
            else -> {
                withStyle(SpanStyle(color = numberColor)) { append(m.value) }
            }
        }
        cursor = m.range.last + 1
    }
    if (cursor < pretty.length) append(pretty.substring(cursor))
}

private fun renderSimpleMarkdown(md: String): AnnotatedString = buildAnnotatedString {
    val lines = md.lines()
    for ((idx, rawLine) in lines.withIndex()) {
        val trimmed = rawLine.trimStart()
        when {
            trimmed.startsWith("### ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Normal)) {
                    append(trimmed.removePrefix("### "))
                }
            }
            trimmed.startsWith("## ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(trimmed.removePrefix("## "))
                }
            }
            trimmed.startsWith("# ") -> {
                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) {
                    append(trimmed.removePrefix("# "))
                }
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                append("• ")
                appendInlineMarkdown(trimmed.drop(2))
            }
            else -> appendInlineMarkdown(rawLine)
        }
        if (idx < lines.lastIndex) append('\n')
    }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(line: String) {
    val inlineRegex = Regex("""(\*\*[^*]+\*\*|`[^`]+`)""")
    var cursor = 0
    for (m in inlineRegex.findAll(line)) {
        if (m.range.first > cursor) append(line.substring(cursor, m.range.first))
        val tok = m.value
        if (tok.startsWith("**") && tok.endsWith("**") && tok.length >= 4) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(tok.substring(2, tok.length - 2)) }
        } else if (tok.startsWith("`") && tok.endsWith("`") && tok.length >= 2) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(tok.substring(1, tok.length - 1)) }
        } else {
            append(tok)
        }
        cursor = m.range.last + 1
    }
    if (cursor < line.length) append(line.substring(cursor))
}
