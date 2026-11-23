package com.tgdrive.feature.autobackup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.common.toLocalShort
import com.tgdrive.designsystem.editorial.EditorialEmptyState
import com.tgdrive.designsystem.editorial.EditorialSwitchRow
import com.tgdrive.designsystem.editorial.SuccessCallout
import com.tgdrive.designsystem.theme.TgTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeUpSpaceScreen(
    onBack: () -> Unit,
    vm: FreeUpSpaceViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showConfirmDialog by remember { mutableStateOf(false) }

    val selectedItems = remember(state.items, state.selectedIds) {
        state.items.filter { it.id in state.selectedIds }
    }
    val selectedBytes = remember(selectedItems) {
        selectedItems.sumOf { it.size.coerceAtLeast(0L) }
    }
    val allSelected = state.items.isNotEmpty() && state.selectedIds.size == state.items.size

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        text = stringResource(R.string.free_up_space_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.backup_back),
                        )
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        TextButton(onClick = vm::toggleSelectAll) {
                            Text(
                                stringResource(
                                    if (allSelected) {
                                        R.string.free_up_space_deselect_all
                                    } else {
                                        R.string.free_up_space_select_all
                                    },
                                ),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            if (state.items.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Button(
                        onClick = { showConfirmDialog = true },
                        enabled = selectedItems.isNotEmpty() && !state.cleaning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.free_up_space_action, selectedBytes.toHumanSize()),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (state.cleaning) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (state.freedCount > 0) {
                SuccessCallout(
                    message = stringResource(
                        R.string.free_up_space_done,
                        state.freedBytes.toHumanSize(),
                        state.freedCount,
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (state.skippedModifiedCount > 0) {
                Text(
                    text = stringResource(R.string.free_up_space_skipped_modified, state.skippedModifiedCount),
                    style = TgTypography.Caption,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            state.error?.let { err ->
                Text(
                    text = err,
                    style = TgTypography.Display.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Age & media filters (always shown once scanned if any local backed-up files exist)
            if (!state.scanning && state.allItems.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.ageFilter == FreeUpAgeFilter.ALL,
                            onClick = { vm.setAgeFilter(FreeUpAgeFilter.ALL) },
                            label = { Text(stringResource(R.string.free_up_filter_all_ages)) },
                        )
                        FilterChip(
                            selected = state.ageFilter == FreeUpAgeFilter.OLDER_7_DAYS,
                            onClick = { vm.setAgeFilter(FreeUpAgeFilter.OLDER_7_DAYS) },
                            label = { Text(stringResource(R.string.free_up_filter_7d)) },
                        )
                        FilterChip(
                            selected = state.ageFilter == FreeUpAgeFilter.OLDER_30_DAYS,
                            onClick = { vm.setAgeFilter(FreeUpAgeFilter.OLDER_30_DAYS) },
                            label = { Text(stringResource(R.string.free_up_filter_30d)) },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.mediaFilter == FreeUpMediaFilter.ALL,
                            onClick = { vm.setMediaFilter(FreeUpMediaFilter.ALL) },
                            label = { Text(stringResource(R.string.free_up_media_all)) },
                        )
                        FilterChip(
                            selected = state.mediaFilter == FreeUpMediaFilter.PHOTOS,
                            onClick = { vm.setMediaFilter(FreeUpMediaFilter.PHOTOS) },
                            label = { Text(stringResource(R.string.free_up_media_photos)) },
                        )
                        FilterChip(
                            selected = state.mediaFilter == FreeUpMediaFilter.VIDEOS,
                            onClick = { vm.setMediaFilter(FreeUpMediaFilter.VIDEOS) },
                            label = { Text(stringResource(R.string.free_up_media_videos)) },
                        )
                        FilterChip(
                            selected = state.mediaFilter == FreeUpMediaFilter.OTHER,
                            onClick = { vm.setMediaFilter(FreeUpMediaFilter.OTHER) },
                            label = { Text(stringResource(R.string.free_up_media_other)) },
                        )
                    }
                    EditorialSwitchRow(
                        icon = Icons.Filled.Fingerprint,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.free_up_verify_sha_title),
                        description = stringResource(R.string.free_up_verify_sha_desc),
                        checked = state.verifyShaBeforeDelete,
                        onChange = vm::setVerifyShaBeforeDelete,
                    )
                }
            }

            when {
                state.scanning -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text(
                                text = stringResource(R.string.free_up_space_scanning),
                                style = TgTypography.Display.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                state.items.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EditorialEmptyState(
                            icon = Icons.Filled.CleaningServices,
                            title = stringResource(R.string.free_up_space_none_title),
                            description = stringResource(R.string.free_up_space_none_desc),
                        )
                    }
                }
                else -> {
                    val totalBytes = remember(state.items) { state.items.sumOf { it.size.coerceAtLeast(0L) } }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(14.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.free_up_space_subtitle),
                            style = TgTypography.Display.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.free_up_space_summary,
                                state.items.size,
                                totalBytes.toHumanSize(),
                            ),
                            style = TgTypography.Mono,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.items, key = { it.id }) { item ->
                            val checked = item.id in state.selectedIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .clickable { vm.toggleSelect(item.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { vm.toggleSelect(item.id) },
                                )
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (!item.thumbnailUri.isNullOrBlank()) {
                                        AsyncImage(
                                            model = item.thumbnailUri,
                                            contentDescription = item.displayName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.InsertDriveFile,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 10.dp),
                                ) {
                                    Text(
                                        text = item.displayName,
                                        style = TgTypography.Display.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${item.size.toHumanSize()} · ${item.syncedAt.toLocalShort()}",
                                        style = TgTypography.Mono,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (item.verifiedInCloud) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Verified,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.tertiary,
                                                modifier = Modifier.size(12.dp),
                                            )
                                            Text(
                                                text = stringResource(R.string.free_up_verified_cloud),
                                                style = TgTypography.Caption,
                                                color = MaterialTheme.colorScheme.tertiary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    stringResource(R.string.free_up_space_confirm_title),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.free_up_space_confirm_msg,
                        selectedItems.size,
                        selectedBytes.toHumanSize(),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDialog = false
                        vm.deleteSelected()
                    },
                ) {
                    Text(
                        stringResource(R.string.free_up_space_action, selectedBytes.toHumanSize()),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
