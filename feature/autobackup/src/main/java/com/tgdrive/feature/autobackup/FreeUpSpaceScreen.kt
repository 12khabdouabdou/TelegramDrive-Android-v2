package com.tgdrive.feature.autobackup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.core.common.toHumanSize
import com.tgdrive.core.common.toLocalShort
import com.tgdrive.designsystem.editorial.EditorialEmptyState
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
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
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
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(16.dp),
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
