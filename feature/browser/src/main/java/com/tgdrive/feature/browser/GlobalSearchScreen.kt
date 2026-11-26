package com.tgdrive.feature.browser

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.Log
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.isTextPreviewable
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.common.toLocalShort
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.ZipEntryEntity
import com.tgdrive.core.download.EncryptedThumbRequest
import com.tgdrive.designsystem.editorial.EditorialEmptyState
import com.tgdrive.designsystem.editorial.SectionLabel
import com.tgdrive.designsystem.theme.TgShapes
import com.tgdrive.designsystem.theme.TgTypography
import com.tgdrive.feature.browser.search.SearchScopeFilter
import com.tgdrive.feature.browser.search.SearchSortOrder
import com.tgdrive.feature.browser.search.SmartSearchEngine
import com.tgdrive.feature.browser.search.SmartSearchHit
import com.tgdrive.feature.player.ImageViewer
import com.tgdrive.feature.upload.DecryptFileDialog
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onOpenFolder: (chatId: Long, folderTitle: String) -> Unit,
    onOpenVideo: (Long, Long) -> Unit = { _, _ -> },
    onOpenImage: (Long, Long) -> Unit = { _, _ -> },
    onOpenPdf: (Long, Long) -> Unit = { _, _ -> },
    onOpenZip: (Long) -> Unit = {},
    onOpenText: (Long, Long) -> Unit = { _, _ -> },
    onOpenZipEntry: (chatId: Long, zipMessageId: Long, entryPath: String) -> Unit = { _, _, _ -> },
    onOpenLocalVideo: (String) -> Unit = {},
    onOpenLocalPdf: (String, String) -> Unit = { _, _ -> },
    onOpenLocalText: (String, String) -> Unit = { _, _ -> },
    onPlayLocalAudio: (String, String) -> Unit = { _, _ -> },
    vm: GlobalSearchViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val activeScope by vm.scopeFilter.collectAsStateWithLifecycle()
    val criteria by vm.filterCriteria.collectAsStateWithLifecycle()
    val recentQueries by vm.recentQueries.collectAsStateWithLifecycle()
    val folderNames by vm.folderNames.collectAsStateWithLifecycle()
    val zipIndexing by vm.zipIndexing.collectAsStateWithLifecycle()
    val aiIndexing by vm.aiIndexing.collectAsStateWithLifecycle()
    val decryptDialog by vm.decryptDialog.collectAsStateWithLifecycle()
    val hasSavedBackupPassphrase by vm.hasSavedBackupPassphrase.collectAsStateWithLifecycle()
    val passphraseFingerprint by vm.passphraseFingerprint.collectAsStateWithLifecycle()
    val decryptedOpenEvent by vm.decryptedOpenEvent.collectAsStateWithLifecycle()
    val snackbarMessage by vm.snackbar.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val noAppMsg = stringResource(R.string.browser_no_app_error)
    var showFiltersRow by remember { mutableStateOf(false) }
    var decryptedImageViewerPath by remember { mutableStateOf<String?>(null) }

    val parsed = remember(query, folderNames) {
        SmartSearchEngine.parseQuery(query, knownFolderNames = folderNames.values)
    }

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.clearSnackbar()
    }

    LaunchedEffect(decryptedOpenEvent?.id) {
        val ev = decryptedOpenEvent ?: return@LaunchedEffect
        vm.clearDecryptedOpenEvent()
        val file = ev.outcome.file
        val ext = file.extension.lowercase()
        val isVideo = ev.outcome.mimeType.startsWith("video/", ignoreCase = true) ||
            ext in setOf("mp4", "m4v", "mkv", "mov", "webm", "avi", "3gp", "ts", "flv")
        val isAudio = ev.outcome.mimeType.startsWith("audio/", ignoreCase = true) ||
            ext in setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "aiff")
        val isPdf = ev.outcome.mimeType.equals("application/pdf", ignoreCase = true) || ext == "pdf"
        val isText = isTextPreviewable(ev.outcome.mimeType, ev.outcome.displayName)
        when {
            ev.outcome.mimeType.startsWith("image/", ignoreCase = true) -> {
                decryptedImageViewerPath = file.absolutePath
            }
            isVideo -> onOpenLocalVideo(file.absolutePath)
            isAudio -> onPlayLocalAudio(file.absolutePath, ev.outcome.displayName)
            isPdf -> onOpenLocalPdf(file.absolutePath, ev.outcome.displayName)
            isText -> onOpenLocalText(file.absolutePath, ev.outcome.displayName)
            else -> {
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, ev.outcome.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (t: Throwable) {
                    Log.w("GlobalSearchScreen: failed opening decrypted file", t)
                    vm.showMessage(noAppMsg)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.browser_back),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.search(it) },
                    placeholder = { Text(stringResource(R.string.browser_search_all_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showFiltersRow = !showFiltersRow }) {
                                Icon(
                                    Icons.Filled.FilterList,
                                    contentDescription = stringResource(R.string.search_filters_toggle),
                                    tint = if (showFiltersRow || criteria.hasUiFilters) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { vm.search("") }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.browser_clear),
                                    )
                                }
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            vm.search(query, immediate = true)
                        },
                    ),
                    shape = TgShapes.XLarge,
                    modifier = Modifier.weight(1f),
                )
            }

            // Natural-language interpretation chips ("Understood as:")
            if (parsed.interpretedChips.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.search_understood_as),
                        style = TgTypography.Mono,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    parsed.interpretedChips.forEach { chipLabel ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = chipLabel,
                                style = TgTypography.Mono,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            // Scope filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchScopeFilter.entries.forEach { scopeItem ->
                    FilterChip(
                        selected = activeScope == scopeItem,
                        onClick = { vm.setScope(scopeItem) },
                        label = { Text(stringResource(scopeLabelRes(scopeItem))) },
                    )
                }
            }

            // Structured Filters & Sort bar (toggleable or when active)
            if (showFiltersRow || criteria.hasUiFilters) {
                SearchAdvancedFilterBar(
                    folderNames = folderNames,
                    selectedFolderId = criteria.folderChatId,
                    minSizeBytes = criteria.minSizeBytes,
                    afterEpochMs = criteria.afterEpochMs,
                    sortOrder = criteria.sortOrder,
                    onSelectFolder = vm::setFolderFilter,
                    onSelectMinSize = vm::setMinSizePreset,
                    onSelectAfterDate = vm::setAfterDatePreset,
                    onSelectSort = vm::setSortOrder,
                    onClearAll = vm::clearAdvancedFilters,
                )
            }

            // On-device AI / OCR & EXIF indexing banner
            if (aiIndexing.running || aiIndexing.unindexedCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (aiIndexing.running) {
                                    stringResource(R.string.search_index_ai_progress, aiIndexing.done, aiIndexing.total)
                                } else {
                                    stringResource(R.string.search_index_ai_banner, aiIndexing.unindexedCount)
                                },
                                style = TgTypography.Display.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            if (!aiIndexing.running) {
                                TextButton(onClick = vm::indexMediaAi) {
                                    Text(stringResource(R.string.search_index_ai_action), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (aiIndexing.running && aiIndexing.total > 0) {
                            LinearProgressIndicator(
                                progress = { aiIndexing.done.toFloat() / aiIndexing.total.toFloat() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            // Optional remote ZIP tail-index banner
            if (zipIndexing.running || zipIndexing.unindexedCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.FolderZip,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (zipIndexing.running) {
                                    stringResource(R.string.search_index_zips_progress, zipIndexing.done, zipIndexing.total)
                                } else {
                                    stringResource(R.string.search_index_zips_banner, zipIndexing.unindexedCount)
                                },
                                style = TgTypography.Display.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            if (!zipIndexing.running) {
                                TextButton(onClick = vm::indexUncachedZips) {
                                    Text(stringResource(R.string.search_index_zips_action), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (zipIndexing.running && zipIndexing.total > 0) {
                            LinearProgressIndicator(
                                progress = { zipIndexing.done.toFloat() / zipIndexing.total.toFloat() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            when (val s = state) {
                is GlobalSearchUiState.Idle -> {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item {
                            SectionLabel(text = stringResource(R.string.search_quick_presets_title))
                            Spacer(Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AssistChip(
                                    onClick = { vm.search("encrypted photos", immediate = true) },
                                    label = { Text(stringResource(R.string.search_preset_encrypted_photos)) },
                                )
                                AssistChip(
                                    onClick = { vm.search("pdfs from last month", immediate = true) },
                                    label = { Text(stringResource(R.string.search_preset_recent_pdfs)) },
                                )
                                AssistChip(
                                    onClick = { vm.search("size:>10mb", immediate = true) },
                                    label = { Text(stringResource(R.string.search_preset_large_files)) },
                                )
                                AssistChip(
                                    onClick = { vm.search("ext:pdf", immediate = true) },
                                    label = { Text("ext:pdf") },
                                )
                                AssistChip(
                                    onClick = { vm.setScope(SearchScopeFilter.IN_ZIPS) },
                                    label = { Text(stringResource(R.string.search_scope_zips)) },
                                )
                                AssistChip(
                                    onClick = { vm.setScope(SearchScopeFilter.STARRED) },
                                    label = { Text(stringResource(R.string.search_scope_starred)) },
                                )
                                AssistChip(
                                    onClick = { vm.setScope(SearchScopeFilter.ENCRYPTED) },
                                    label = { Text(stringResource(R.string.search_scope_encrypted)) },
                                )
                            }
                            Text(
                                text = stringResource(R.string.search_operators_hint),
                                style = TgTypography.Mono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }

                        if (recentQueries.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SectionLabel(text = stringResource(R.string.search_recent_title))
                                    TextButton(onClick = vm::clearRecentSearches) {
                                        Text(stringResource(R.string.search_recent_clear))
                                    }
                                }
                            }
                            items(recentQueries, key = { it }) { recent ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { vm.search(recent, immediate = true) }
                                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.History,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = recent,
                                        style = TgTypography.Display.bodyMedium,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 12.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(
                                        onClick = { vm.removeRecentSearch(recent) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.browser_clear),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        } else {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 36.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    EditorialEmptyState(
                                        icon = Icons.Filled.Search,
                                        title = stringResource(R.string.browser_search),
                                        description = stringResource(R.string.browser_search_idle),
                                    )
                                }
                            }
                        }
                    }
                }
                is GlobalSearchUiState.Searching -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                is GlobalSearchUiState.Results -> {
                    if (s.hits.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EditorialEmptyState(
                                icon = Icons.Filled.Search,
                                title = stringResource(R.string.browser_empty_filtered),
                                description = stringResource(R.string.browser_search_empty, query),
                            )
                        }
                    } else {
                        val highlightTokens = parsed.highlightTokens
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item {
                                val label = if (s.hits.size == 1) {
                                    stringResource(R.string.browser_search_results_one)
                                } else {
                                    stringResource(R.string.browser_search_results_many, s.hits.size)
                                }
                                SectionLabel(text = label)
                            }
                            items(
                                s.hits,
                                key = { hit ->
                                    when (hit) {
                                        is SmartSearchHit.CloudFileHit -> "f_${hit.entity.chatId}_${hit.entity.messageId}"
                                        is SmartSearchHit.ZipMemberHit ->
                                            "z_${hit.entry.chatId}_${hit.entry.zipMessageId}_${hit.entry.entryPath}"
                                    }
                                },
                                contentType = { hit ->
                                    when (hit) {
                                        is SmartSearchHit.CloudFileHit -> "search_cloud_hit"
                                        is SmartSearchHit.ZipMemberHit -> "search_zip_hit"
                                    }
                                },
                            ) { hit ->
                                when (hit) {
                                    is SmartSearchHit.CloudFileHit -> {
                                        val msg = hit.entity
                                        val folderTitle = folderNames[msg.chatId]
                                            ?: stringResource(R.string.browser_saved_messages)
                                        CloudFileResultRow(
                                            msg = msg,
                                            folderTitle = folderTitle,
                                            passphraseFingerprint = passphraseFingerprint,
                                            matchHint = hit.matchHint,
                                            highlightTokens = highlightTokens,
                                            onClick = {
                                                vm.openCloudFile(
                                                    file = msg,
                                                    folderTitle = folderTitle,
                                                    onOpenVideo = onOpenVideo,
                                                    onOpenImage = onOpenImage,
                                                    onOpenPdf = onOpenPdf,
                                                    onOpenZip = onOpenZip,
                                                    onOpenText = onOpenText,
                                                    onOpenFolder = onOpenFolder,
                                                )
                                            },
                                            onShowInFolder = { onOpenFolder(msg.chatId, folderTitle) },
                                        )
                                    }
                                    is SmartSearchHit.ZipMemberHit -> {
                                        val entry = hit.entry
                                        val folderTitle = folderNames[entry.chatId]
                                            ?: stringResource(R.string.browser_saved_messages)
                                        ZipMemberResultRow(
                                            entry = entry,
                                            folderTitle = folderTitle,
                                            highlightTokens = highlightTokens,
                                            onClick = {
                                                vm.onZipMemberClicked()
                                                onOpenZipEntry(entry.chatId, entry.zipMessageId, entry.entryPath)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                is GlobalSearchUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(s.message, style = TgTypography.Mono, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = vm::retry) {
                            Text(stringResource(R.string.browser_retry))
                        }
                    }
                }
            }
        }
    }

    decryptDialog?.let { dlg ->
        DecryptFileDialog(
            fileName = dlg.item.fileName,
            detectedFormat = dlg.format,
            hasSavedBackupPassphrase = hasSavedBackupPassphrase,
            inProgress = dlg.downloading || dlg.decrypting,
            progressMessage = if (dlg.downloading) {
                stringResource(R.string.browser_downloading_file, dlg.item.fileName)
            } else {
                null
            },
            errorMessage = dlg.error,
            onDecryptAndOpen = { pwd -> vm.decryptTarget(pwd, saveToDownloads = false) },
            onDecryptAndSave = { pwd -> vm.decryptTarget(pwd, saveToDownloads = true) },
            onDismiss = vm::dismissDecryptDialog,
            onSaveAsVaultPassphrase = vm::saveVaultPassphrase,
        )
    }

    decryptedImageViewerPath?.let { path ->
        ImageViewer(
            imagePaths = listOf(path),
            onDismiss = { decryptedImageViewerPath = null },
        )
    }
}

@Composable
private fun SearchAdvancedFilterBar(
    folderNames: Map<Long, String>,
    selectedFolderId: Long?,
    minSizeBytes: Long?,
    afterEpochMs: Long?,
    sortOrder: SearchSortOrder,
    onSelectFolder: (Long?) -> Unit,
    onSelectMinSize: (Long?) -> Unit,
    onSelectAfterDate: (Long?) -> Unit,
    onSelectSort: (SearchSortOrder) -> Unit,
    onClearAll: () -> Unit,
) {
    var folderMenu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    var dateMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val nowMs = remember { System.currentTimeMillis() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FilterChip(
                selected = selectedFolderId != null,
                onClick = { folderMenu = true },
                label = {
                    Text(
                        selectedFolderId?.let { folderNames[it] }
                            ?: stringResource(R.string.search_filter_folder),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_filter_any_folder)) },
                    onClick = {
                        folderMenu = false
                        onSelectFolder(null)
                    },
                )
                folderNames.entries.sortedBy { it.value.lowercase() }.forEach { (id, title) ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            folderMenu = false
                            onSelectFolder(id)
                        },
                    )
                }
            }
        }

        Box {
            FilterChip(
                selected = minSizeBytes != null,
                onClick = { sizeMenu = true },
                label = {
                    Text(
                        when (minSizeBytes) {
                            1_048_576L -> "> 1 MB"
                            10_485_760L -> "> 10 MB"
                            104_857_600L -> "> 100 MB"
                            else -> stringResource(R.string.search_filter_size)
                        },
                    )
                },
            )
            DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                listOf(
                    null to stringResource(R.string.search_filter_any_size),
                    1_048_576L to "> 1 MB",
                    10_485_760L to "> 10 MB",
                    104_857_600L to "> 100 MB",
                ).forEach { (bytes, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            sizeMenu = false
                            onSelectMinSize(bytes)
                        },
                    )
                }
            }
        }

        Box {
            FilterChip(
                selected = afterEpochMs != null,
                onClick = { dateMenu = true },
                label = {
                    Text(
                        if (afterEpochMs != null) {
                            stringResource(R.string.search_filter_date_active)
                        } else {
                            stringResource(R.string.search_filter_date)
                        },
                    )
                },
            )
            DropdownMenu(expanded = dateMenu, onDismissRequest = { dateMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_filter_any_time)) },
                    onClick = {
                        dateMenu = false
                        onSelectAfterDate(null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_filter_last_7d)) },
                    onClick = {
                        dateMenu = false
                        onSelectAfterDate(nowMs - 7L * 86_400_000L)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_filter_last_30d)) },
                    onClick = {
                        dateMenu = false
                        onSelectAfterDate(nowMs - 30L * 86_400_000L)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.search_filter_last_year)) },
                    onClick = {
                        dateMenu = false
                        onSelectAfterDate(nowMs - 365L * 86_400_000L)
                    },
                )
            }
        }

        Box {
            FilterChip(
                selected = sortOrder != SearchSortOrder.RELEVANCE,
                onClick = { sortMenu = true },
                label = { Text(stringResource(sortLabelRes(sortOrder))) },
            )
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                SearchSortOrder.entries.forEach { order ->
                    DropdownMenuItem(
                        text = { Text(stringResource(sortLabelRes(order))) },
                        onClick = {
                            sortMenu = false
                            onSelectSort(order)
                        },
                    )
                }
            }
        }

        if (selectedFolderId != null || minSizeBytes != null || afterEpochMs != null || sortOrder != SearchSortOrder.RELEVANCE) {
            TextButton(onClick = onClearAll) {
                Text(stringResource(R.string.browser_clear))
            }
        }
    }
}

private fun scopeLabelRes(scope: SearchScopeFilter): Int = when (scope) {
    SearchScopeFilter.ALL -> R.string.search_scope_all
    SearchScopeFilter.IN_ZIPS -> R.string.search_scope_zips
    SearchScopeFilter.IMAGES -> R.string.search_scope_images
    SearchScopeFilter.VIDEOS -> R.string.search_scope_videos
    SearchScopeFilter.AUDIO -> R.string.search_scope_audio
    SearchScopeFilter.DOCS -> R.string.search_scope_docs
    SearchScopeFilter.OFFLINE -> R.string.search_scope_offline
    SearchScopeFilter.STARRED -> R.string.search_scope_starred
    SearchScopeFilter.ENCRYPTED -> R.string.search_scope_encrypted
}

private fun sortLabelRes(sort: SearchSortOrder): Int = when (sort) {
    SearchSortOrder.RELEVANCE -> R.string.search_sort_relevance
    SearchSortOrder.NEWEST -> R.string.search_sort_newest
    SearchSortOrder.LARGEST -> R.string.search_sort_largest
    SearchSortOrder.NAME -> R.string.search_sort_name
}

@Composable
private fun rememberHighlightedText(
    text: String,
    tokens: List<String>,
): AnnotatedString {
    val highlightBg = MaterialTheme.colorScheme.primaryContainer
    val highlightFg = MaterialTheme.colorScheme.onPrimaryContainer
    return remember(text, tokens, highlightBg, highlightFg) {
        if (tokens.isEmpty()) {
            AnnotatedString(text)
        } else {
            val lower = text.lowercase()
            buildAnnotatedString {
                append(text)
                val style = SpanStyle(
                    background = highlightBg,
                    color = highlightFg,
                    fontWeight = FontWeight.Bold,
                )
                for (tok in tokens) {
                    if (tok.length < 2) continue
                    var startIdx = 0
                    while (true) {
                        val found = lower.indexOf(tok, startIndex = startIdx)
                        if (found < 0) break
                        addStyle(style, found, found + tok.length)
                        startIdx = found + tok.length
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudFileResultRow(
    msg: FileCacheEntity,
    folderTitle: String,
    passphraseFingerprint: String?,
    matchHint: String?,
    highlightTokens: List<String>,
    onClick: () -> Unit,
    onShowInFolder: () -> Unit,
) {
    val isEncrypted = remember(msg) { SmartSearchEngine.isEncrypted(msg) }
    val (icon, tint) = searchVisual(
        if (isEncrypted) null else msg.mimeType,
        SmartSearchEngine.effectiveFileName(msg.fileName),
    )
    val encThumbRequest = remember(msg.chatId, msg.messageId, msg.thumbnailPath, isEncrypted, passphraseFingerprint) {
        if (isEncrypted && passphraseFingerprint != null) {
            EncryptedThumbRequest(
                chatId = msg.chatId,
                messageId = msg.messageId,
                localPath = msg.thumbnailPath,
                passphraseFingerprint = passphraseFingerprint,
            )
        } else {
            null
        }
    }
    val context = LocalContext.current
    var loadFailed by remember(msg.thumbnailPath) { mutableStateOf(false) }
    val plainThumbRequest = remember(msg.thumbnailPath, context) {
        msg.thumbnailPath
            ?.takeIf { it.isNotBlank() && !it.endsWith(".tgethumb", ignoreCase = true) }
            ?.let { path ->
                ImageRequest.Builder(context)
                    .data(File(path))
                    .size(Size(160, 160))
                    .crossfade(false)
                    .build()
            }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(TgShapes.Large)
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            val imageModel: Any? = if (loadFailed) null else (encThumbRequest ?: plainThumbRequest)
            if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = msg.fileName,
                    contentScale = ContentScale.Crop,
                    onError = { loadFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            if (isEncrypted) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f))
                        .padding(2.dp),
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = rememberHighlightedText(msg.fileName, highlightTokens),
                style = TgTypography.Display.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${msg.fileSize.toHumanSize()} · ${msg.date.toLocalShort()} · $folderTitle",
                style = TgTypography.Mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!matchHint.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        text = matchHint,
                        style = TgTypography.Mono,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        IconButton(onClick = onShowInFolder) {
            Icon(
                Icons.Filled.FolderOpen,
                contentDescription = stringResource(R.string.search_show_in_folder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ZipMemberResultRow(
    entry: ZipEntryEntity,
    folderTitle: String,
    highlightTokens: List<String>,
    onClick: () -> Unit,
) {
    val (icon, tint) = searchVisual(null, entry.entryName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(TgShapes.Large)
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rememberHighlightedText(entry.entryName, highlightTokens),
                    style = TgTypography.Display.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.isEncrypted) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            val sizePrefix = if (entry.entrySize > 0L) "${entry.entrySize.toHumanSize()} · " else ""
            Text(
                "$sizePrefix${stringResource(R.string.search_in_zip_badge, entry.zipFileName)} · $folderTitle",
                style = TgTypography.Mono,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun searchVisual(mimeType: String?, fileName: String): Pair<ImageVector, Color> =
    when (classifyFile(mimeType, fileName)) {
        FileBucket.IMAGES -> Icons.Filled.Image to MaterialTheme.colorScheme.secondary
        FileBucket.VIDEOS -> Icons.Filled.Movie to MaterialTheme.colorScheme.primary
        FileBucket.AUDIO -> Icons.Filled.AudioFile to MaterialTheme.colorScheme.tertiary
        FileBucket.PDF -> Icons.Filled.PictureAsPdf to MaterialTheme.colorScheme.error
        FileBucket.ARCHIVES -> Icons.Filled.FolderZip to MaterialTheme.colorScheme.tertiary
        FileBucket.DOCUMENTS -> Icons.Filled.Description to MaterialTheme.colorScheme.onSurfaceVariant
        FileBucket.OTHER -> Icons.Filled.InsertDriveFile to MaterialTheme.colorScheme.primary
    }
