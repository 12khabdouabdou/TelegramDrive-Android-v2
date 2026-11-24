package com.tgdrive.app.nav

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.tgdrive.app.R
import com.tgdrive.app.RootViewModel
import com.tgdrive.app.ShareTargetFolder
import com.tgdrive.core.datastore.FileFilter
import com.tgdrive.core.zip.ZipCompressionMode
import com.tgdrive.feature.autobackup.AutoBackupScreen
import com.tgdrive.feature.browser.CategoryFilesScreen
import com.tgdrive.feature.browser.FolderScreen
import com.tgdrive.feature.browser.FoldersManagementScreen
import com.tgdrive.feature.browser.GlobalSearchScreen
import com.tgdrive.feature.browser.HomeScreen
import com.tgdrive.feature.browser.PhotosTimelineScreen
import com.tgdrive.feature.browser.analytics.StorageAnalyticsScreen
import com.tgdrive.feature.download.DownloadListScreen

private enum class Tab(val route: String, val labelRes: Int) {
    HOME("tab/home", R.string.tab_home),
    PHOTOS("tab/photos", R.string.tab_photos),
    FOLDERS("tab/folders", R.string.tab_folders),
    TRANSFERS("tab/transfers", R.string.tab_transfers),
    BACKUP("tab/backup", R.string.tab_backup),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainBottomNav(
    rootVm: RootViewModel,
    mainNav: NavHostController,
    onOpenVideo: (Long, Long) -> Unit,
    onOpenLocalVideo: (String) -> Unit = {},
    onOpenImage: (Long, Long) -> Unit = { _, _ -> },
    onOpenPdf: (Long) -> Unit = {},
    onOpenZip: (Long) -> Unit = {},
    onOpenZipEntry: (Long, Long, String) -> Unit = { _, msgId, _ -> onOpenZip(msgId) },
    onOpenText: (Long, Long) -> Unit = { _, _ -> },
    onOpenHistory: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenCacheSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenFreeUpSpace: () -> Unit = {},
) {
    val nav = mainNav
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Tab.HOME.route
    val isTopLevelTab = Tab.entries.any { it.route == current }
    val rootState by rootVm.state.collectAsStateWithLifecycle()

    BackHandler(enabled = !isTopLevelTab) {
        nav.popBackStack()
    }

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
                                        Tab.PHOTOS -> Icons.Filled.PhotoLibrary
                                        Tab.FOLDERS -> Icons.Filled.CreateNewFolder
                                        Tab.TRANSFERS -> Icons.Filled.SwapVert
                                        Tab.BACKUP -> Icons.Filled.CloudUpload
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
                    onOpenText = onOpenText,
                    onOpenAnalytics = { nav.navigate("analytics") },
                    onOpenSearch = { nav.navigate("search") },
                    onOpenCategory = { filter ->
                        if (filter == FileFilter.IMAGES) {
                            nav.navigate(Tab.PHOTOS.route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        } else {
                            nav.navigate("category/${filter.name}")
                        }
                    },
                    onOpenCategoryFolder = { chatId, title, filter ->
                        nav.navigate("folder/$chatId/${Uri.encode(title)}?filter=${filter.name}")
                    },
                    onOpenSettings = onOpenSettings,
                    onOpenTrash = onOpenTrash,
                    onOpenFreeUpSpace = onOpenFreeUpSpace,
                )
            }
            composable(Tab.PHOTOS.route) {
                PhotosTimelineScreen(
                    onOpenImage = onOpenImage,
                    onOpenVideo = onOpenVideo,
                    onOpenSearch = { nav.navigate("search") },
                    onOpenSettings = onOpenSettings,
                )
            }
            composable(Tab.TRANSFERS.route) {
                DownloadListScreen(
                    showBack = false,
                    onBack = { nav.popBackStack() },
                    onOpenLocalVideo = onOpenLocalVideo,
                )
            }
            composable(Tab.FOLDERS.route) {
                FoldersManagementScreen(
                    onOpenFolder = { chatId, title ->
                        nav.navigate("folder/$chatId/${Uri.encode(title)}")
                    },
                    onOpenTrash = onOpenTrash,
                )
            }
            composable(Tab.BACKUP.route) {
                AutoBackupScreen(
                    onOpenHistory = onOpenHistory,
                    onOpenFreeUpSpace = onOpenFreeUpSpace,
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
                    onOpenFolder = { chatId, folderTitle ->
                        nav.navigate("folder/$chatId/${Uri.encode(folderTitle)}")
                    },
                    onOpenVideo = onOpenVideo,
                    onOpenImage = onOpenImage,
                    onOpenPdf = onOpenPdf,
                    onOpenZip = onOpenZip,
                    onOpenText = onOpenText,
                    onOpenZipEntry = onOpenZipEntry,
                )
            }

            composable(
                "category/{filter}",
                arguments = listOf(navArgument("filter") { type = NavType.StringType }),
            ) { entry ->
                val raw = entry.arguments?.getString("filter").orEmpty()
                val filter = runCatching { FileFilter.valueOf(raw) }.getOrNull() ?: FileFilter.VIDEOS
                CategoryFilesScreen(
                    filter = filter,
                    onBack = { nav.popBackStack() },
                    onOpenVideo = onOpenVideo,
                    onOpenImage = onOpenImage,
                    onOpenPdf = onOpenPdf,
                    onOpenZip = onOpenZip,
                    onOpenText = onOpenText,
                    onOpenFolder = { chatId, folderTitle ->
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
                    onOpenLocalVideo = onOpenLocalVideo,
                    onOpenImage = onOpenImage,
                    onOpenPdf = onOpenPdf,
                    onOpenZip = onOpenZip,
                    onOpenText = onOpenText,
                    onBack = { nav.popBackStack() },
                )
            }
        }

        if (rootState.pendingShareUris.isNotEmpty()) {
            ShareUploadDialog(
                count = rootState.pendingShareUris.size,
                folders = rootState.shareFolders,
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
    folders: List<ShareTargetFolder>,
    inProgress: Boolean,
    message: String?,
    onConfirm: (
        targetChatId: Long?,
        packAsZip: Boolean,
        zipName: String?,
        zipPassword: String?,
        encryptFiles: Boolean,
        useVaultKey: Boolean,
        compressionMode: ZipCompressionMode,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedChatId by remember(folders) {
        mutableLongStateOf(folders.firstOrNull()?.chatId ?: 0L)
    }
    var packAsZip by remember { mutableStateOf(false) }
    var zipName by remember { mutableStateOf("") }
    var compressionMode by remember { mutableStateOf(ZipCompressionMode.SMART) }
    var encryptFiles by remember { mutableStateOf(false) }
    var useVaultKey by remember { mutableStateOf(true) }
    var zipPassword by remember { mutableStateOf("") }

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
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.share_pick_destination),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (folders.isEmpty()) {
                    Text(
                        if (count == 1) {
                            stringResource(R.string.share_upload_body_one)
                        } else {
                            stringResource(R.string.share_upload_body_many, count)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(modifier = Modifier.selectableGroup()) {
                        folders.forEach { folder ->
                            val selected = selectedChatId == folder.chatId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .selectable(
                                        selected = selected,
                                        enabled = !inProgress,
                                        role = Role.RadioButton,
                                        onClick = { selectedChatId = folder.chatId },
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selected,
                                    enabled = !inProgress,
                                    onClick = null,
                                )
                                Text(
                                    text = folder.title,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !inProgress) { packAsZip = !packAsZip }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = packAsZip,
                        enabled = !inProgress,
                        onCheckedChange = null,
                    )
                    Text(
                        text = stringResource(R.string.share_pack_zip),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (packAsZip) {
                    OutlinedTextField(
                        value = zipName,
                        onValueChange = { zipName = it },
                        label = { Text(stringResource(R.string.share_zip_name_label)) },
                        singleLine = true,
                        enabled = !inProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = compressionMode == ZipCompressionMode.SMART,
                            onClick = { compressionMode = ZipCompressionMode.SMART },
                            label = { Text("Smart") },
                        )
                        FilterChip(
                            selected = compressionMode == ZipCompressionMode.STORE,
                            onClick = { compressionMode = ZipCompressionMode.STORE },
                            label = { Text("Store") },
                        )
                        FilterChip(
                            selected = compressionMode == ZipCompressionMode.DEFLATE,
                            onClick = { compressionMode = ZipCompressionMode.DEFLATE },
                            label = { Text("Deflate") },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !inProgress) { encryptFiles = !encryptFiles }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = encryptFiles,
                        enabled = !inProgress,
                        onCheckedChange = null,
                    )
                    Text(
                        text = stringResource(R.string.share_encrypt_files),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (encryptFiles) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = useVaultKey,
                            onClick = { useVaultKey = true },
                            label = { Text(stringResource(R.string.share_encrypt_vault_key)) },
                        )
                        FilterChip(
                            selected = !useVaultKey,
                            onClick = { useVaultKey = false },
                            label = { Text(stringResource(R.string.share_encrypt_custom_password)) },
                        )
                    }
                    if (!useVaultKey) {
                        OutlinedTextField(
                            value = zipPassword,
                            onValueChange = { zipPassword = it },
                            label = { Text(stringResource(R.string.share_zip_password_label)) },
                            singleLine = true,
                            enabled = !inProgress,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (inProgress) {
                    Spacer(Modifier.height(4.dp))
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
                onClick = {
                    onConfirm(
                        selectedChatId.takeIf { it != 0L },
                        packAsZip,
                        zipName.takeIf { it.isNotBlank() },
                        zipPassword.takeIf { it.isNotBlank() },
                        encryptFiles,
                        useVaultKey,
                        compressionMode,
                    )
                },
                enabled = !inProgress && (!encryptFiles || useVaultKey || zipPassword.length >= 4),
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
