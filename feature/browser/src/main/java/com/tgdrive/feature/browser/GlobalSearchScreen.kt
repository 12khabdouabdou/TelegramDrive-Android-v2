package com.tgdrive.feature.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.common.FileBucket
import com.tgdrive.core.common.classifyFile
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.common.toLocalShort
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.db.entity.ZipEntryEntity
import com.tgdrive.designsystem.editorial.EditorialEmptyState
import com.tgdrive.designsystem.editorial.SectionLabel
import com.tgdrive.designsystem.theme.TgShapes
import com.tgdrive.designsystem.theme.TgTypography
import com.tgdrive.feature.browser.search.SearchScopeFilter
import com.tgdrive.feature.browser.search.SmartSearchHit

@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onOpenFile: (chatId: Long, folderTitle: String, messageId: Long) -> Unit,
    onOpenZipEntry: (chatId: Long, zipMessageId: Long, entryPath: String) -> Unit = { _, _, _ -> },
    vm: GlobalSearchViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val activeScope by vm.scopeFilter.collectAsStateWithLifecycle()
    val recentQueries by vm.recentQueries.collectAsStateWithLifecycle()
    val folderNames by vm.folderNames.collectAsStateWithLifecycle()
    val zipIndexing by vm.zipIndexing.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
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
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.search("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.browser_clear),
                            )
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

        // Scope filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
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

        // Optional remote ZIP tail-index banner
        if (zipIndexing.running || zipIndexing.unindexedCount > 0) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
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
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        when (val s = state) {
            is GlobalSearchUiState.Idle -> {
                if (recentQueries.isNotEmpty()) {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
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
                        items(recentQueries) { recent ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { vm.search(recent, immediate = true) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
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
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EditorialEmptyState(
                            icon = Icons.Filled.Search,
                            title = stringResource(R.string.browser_search),
                            description = stringResource(R.string.browser_search_idle),
                        )
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
                                    is SmartSearchHit.ZipMemberHit -> "z_${hit.entry.chatId}_${hit.entry.zipMessageId}_${hit.entry.entryPath}"
                                }
                            },
                        ) { hit ->
                            when (hit) {
                                is SmartSearchHit.CloudFileHit -> {
                                    val msg = hit.entity
                                    val folderTitle = folderNames[msg.chatId]
                                        ?: stringResource(R.string.browser_saved_messages)
                                    CloudFileResultRow(msg, folderTitle) {
                                        onOpenFile(msg.chatId, folderTitle, msg.messageId)
                                    }
                                }
                                is SmartSearchHit.ZipMemberHit -> {
                                    val entry = hit.entry
                                    val folderTitle = folderNames[entry.chatId]
                                        ?: stringResource(R.string.browser_saved_messages)
                                    ZipMemberResultRow(entry, folderTitle) {
                                        onOpenZipEntry(entry.chatId, entry.zipMessageId, entry.entryPath)
                                    }
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

private fun scopeLabelRes(scope: SearchScopeFilter): Int = when (scope) {
    SearchScopeFilter.ALL -> R.string.search_scope_all
    SearchScopeFilter.IN_ZIPS -> R.string.search_scope_zips
    SearchScopeFilter.IMAGES -> R.string.search_scope_images
    SearchScopeFilter.VIDEOS -> R.string.search_scope_videos
    SearchScopeFilter.AUDIO -> R.string.search_scope_audio
    SearchScopeFilter.DOCS -> R.string.search_scope_docs
    SearchScopeFilter.OFFLINE -> R.string.search_scope_offline
    SearchScopeFilter.STARRED -> R.string.search_scope_starred
}

@Composable
private fun CloudFileResultRow(
    msg: FileCacheEntity,
    folderTitle: String,
    onClick: () -> Unit,
) {
    val (icon, tint) = searchVisual(msg.mimeType, msg.fileName)
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
            Text(
                msg.fileName,
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
        }
    }
}

@Composable
private fun ZipMemberResultRow(
    entry: ZipEntryEntity,
    folderTitle: String,
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
                    entry.entryName,
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
