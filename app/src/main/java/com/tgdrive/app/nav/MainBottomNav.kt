package com.tgdrive.app.nav

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tgdrive.app.R
import com.tgdrive.app.RootViewModel
import com.tgdrive.core.datastore.FileFilter
import com.tgdrive.feature.autobackup.AutoBackupScreen
import com.tgdrive.feature.browser.FolderScreen
import com.tgdrive.feature.browser.FoldersManagementScreen
import com.tgdrive.feature.browser.GlobalSearchScreen
import com.tgdrive.feature.browser.HomeScreen
import com.tgdrive.feature.browser.analytics.StorageAnalyticsScreen
import com.tgdrive.feature.download.DownloadListScreen
import com.tgdrive.feature.settings.SettingsScreen

private enum class Tab(val route: String, val labelRes: Int) {
    HOME("tab/home", R.string.tab_home),
    FOLDERS("tab/folders", R.string.tab_folders),
    TRANSFERS("tab/transfers", R.string.tab_transfers),
    BACKUP("tab/backup", R.string.tab_backup),
    SETTINGS("tab/settings", R.string.tab_settings),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainBottomNav(
    rootVm: RootViewModel,
    onOpenVideo: (Long) -> Unit,
    onOpenImage: (String) -> Unit = {},
    onOpenPdf: (Long) -> Unit = {},
    onOpenZip: (Long) -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenCacheSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Tab.HOME.route
    val isTopLevelTab = Tab.entries.any { it.route == current }
    val rootState by rootVm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (isTopLevelTab) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f))
                        .navigationBarsPadding(),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tab.entries.forEach { tab ->
                            val isSelected = current == tab.route
                            val label = stringResource(tab.labelRes)
                            val tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .selectable(
                                        selected = isSelected,
                                        role = Role.Tab,
                                        onClick = {
                                            nav.navigate(tab.route) {
                                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                    )
                                    .padding(vertical = 4.dp),
                            ) {
                                Icon(
                                    imageVector = when (tab) {
                                        Tab.HOME -> Icons.Filled.Folder
                                        Tab.FOLDERS -> Icons.Filled.CreateNewFolder
                                        Tab.TRANSFERS -> Icons.Filled.SwapVert
                                        Tab.BACKUP -> Icons.Filled.CloudUpload
                                        Tab.SETTINGS -> Icons.Filled.Settings
                                    },
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = tint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.HOME.route,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            composable(Tab.HOME.route) {
                HomeScreen(
                    onOpenFolder = { chatId, title ->
                        nav.navigate("folder/$chatId/${Uri.encode(title)}")
                    },
                    onOpenVideo = onOpenVideo,
                    onOpenImage = onOpenImage,
                    onOpenPdf = onOpenPdf,
                    onOpenZip = onOpenZip,
                    onOpenAnalytics = { nav.navigate("analytics") },
                    onOpenSearch = { nav.navigate("search") },
                    onOpenCategoryFolder = { chatId, title, filter ->
                        nav.navigate("folder/$chatId/${Uri.encode(title)}?filter=${filter.name}")
                    },
                )
            }
            composable(Tab.TRANSFERS.route) {
                DownloadListScreen(showBack = false, onBack = { nav.popBackStack() })
            }
            composable(Tab.FOLDERS.route) {
                FoldersManagementScreen(
                    onOpenFolder = { chatId, title ->
                        nav.navigate("folder/$chatId/${Uri.encode(title)}")
                    },
                )
            }
            composable(Tab.BACKUP.route) {
                AutoBackupScreen(onOpenHistory = onOpenHistory)
            }
            composable(Tab.SETTINGS.route) {
                SettingsScreen(
                    onOpenCache = onOpenCacheSettings,
                    onOpenAbout = onOpenAbout,
                )
            }

            composable("analytics") {
                StorageAnalyticsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenFolder = { chatId, title ->
                        nav.navigate("folder/$chatId/${Uri.encode(title)}")
                    },
                )
            }

            composable("search") {
                GlobalSearchScreen(
                    onBack = { nav.popBackStack() },
                    onOpenFile = { chatId, folderTitle, _ ->
                        nav.navigate("folder/$chatId/${Uri.encode(folderTitle)}")
                    },
                )
            }

            composable(
                "folder/{chatId}/{title}?filter={filter}",
                arguments = listOf(
                    navArgument("chatId") { type = NavType.LongType },
                    navArgument("title") { type = NavType.StringType },
                    navArgument("filter") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val chatId = entry.arguments?.getLong("chatId") ?: return@composable
                val rawTitle = entry.arguments?.getString("title").orEmpty()
                val title = Uri.decode(rawTitle)
                val filterName = entry.arguments?.getString("filter").orEmpty()
                val initialFilter = runCatching { FileFilter.valueOf(filterName) }.getOrNull()
                FolderScreen(
                    chatId = chatId,
                    title = title,
                    initialFilter = initialFilter,
                    onOpenVideo = onOpenVideo,
                    onOpenImage = onOpenImage,
                    onOpenPdf = onOpenPdf,
                    onOpenZip = onOpenZip,
                    onBack = { nav.popBackStack() },
                )
            }
        }

        if (rootState.pendingShareUris.isNotEmpty()) {
            ShareUploadDialog(
                count = rootState.pendingShareUris.size,
                inProgress = rootState.shareUploadInProgress,
                message = rootState.shareUploadMessage,
                onConfirm = rootVm::confirmShareUpload,
                onDismiss = rootVm::cancelShareUpload,
            )
        }
    }
}

@Composable
private fun ShareUploadDialog(
    count: Int,
    inProgress: Boolean,
    message: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Text(
                if (count == 1) {
                    stringResource(R.string.share_upload_title_one)
                } else {
                    stringResource(R.string.share_upload_title_many, count)
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (count == 1) {
                        stringResource(R.string.share_upload_body_one)
                    } else {
                        stringResource(R.string.share_upload_body_many, count)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (inProgress) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (message != null) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !inProgress,
            ) {
                Text(stringResource(R.string.share_upload_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !inProgress,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
