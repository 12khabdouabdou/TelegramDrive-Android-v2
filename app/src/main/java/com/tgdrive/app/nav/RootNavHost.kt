package com.tgdrive.app.nav

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tgdrive.app.RootState
import com.tgdrive.app.RootViewModel
import com.tgdrive.app.onboarding.OnboardingScreen
import com.tgdrive.feature.auth.AuthFlow
import com.tgdrive.feature.autobackup.BackupHistoryScreen
import com.tgdrive.feature.autobackup.FreeUpSpaceScreen
import com.tgdrive.feature.autobackup.SyncLogDetailsScreen
import com.tgdrive.feature.browser.TrashScreen
import com.tgdrive.feature.browser.VaultScreen
import com.tgdrive.feature.browser.ZipBrowserScreen
import com.tgdrive.feature.download.DownloadListScreen
import com.tgdrive.feature.player.AudioPlayerScaffold
import com.tgdrive.feature.player.ImagePlayerScreen
import com.tgdrive.feature.player.PdfViewerScreen
import com.tgdrive.feature.player.TextViewerScreen
import com.tgdrive.feature.player.VideoPlayerScreen
import com.tgdrive.feature.settings.AboutScreen
import com.tgdrive.feature.settings.CacheSettingsScreen
import com.tgdrive.feature.settings.SettingsScreen

/**
 * Root navigation host.
 */
@Composable
fun RootNavHost(
    state: RootState,
    rootVm: RootViewModel,
) {
    if (state.isInitializing) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(32.dp),
            )
        }
        return
    }

    val nav = rememberNavController()
    val mainNav = rememberNavController()
    val targetRoute = when {
        !state.onboardingCompleted -> "onboarding"
        !state.isAuthorized -> "auth"
        else -> "main"
    }
    val initialRoute = remember { targetRoute }

    LaunchedEffect(targetRoute) {
        val current = nav.currentDestination?.route
        val inAuthorizedArea = current != null && current != "onboarding" && current != "auth"
        if (targetRoute == "main" && inAuthorizedArea) {
            return@LaunchedEffect
        }
        if (current != targetRoute) {
            nav.navigate(targetRoute) {
                popUpTo(nav.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    val navContent: @Composable () -> Unit = {
        val openLocalVideo: (String) -> Unit = { path ->
            nav.navigate("local_video?path=${Uri.encode(path)}")
        }
        val openLocalPdf: (String, String) -> Unit = { path, title ->
            nav.navigate("local_pdf?path=${Uri.encode(path)}&title=${Uri.encode(title)}")
        }
        val openLocalText: (String, String) -> Unit = { path, title ->
            nav.navigate("local_text?path=${Uri.encode(path)}&title=${Uri.encode(title)}")
        }
        val playLocalAudio: (String, String) -> Unit = { path, name ->
            rootVm.playLocalAudio(path, name)
        }
        NavHost(navController = nav, startDestination = initialRoute) {
            composable("onboarding") {
                OnboardingScreen(
                    onDone = {
                        nav.navigate("auth") {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable("auth") { AuthFlow() }

            composable("main") {
                MainBottomNav(
                    rootVm = rootVm,
                    mainNav = mainNav,
                    onOpenVideo = { chatId, msgId -> nav.navigate("video/$msgId?chatId=$chatId") },
                    onOpenLocalVideo = openLocalVideo,
                    onOpenLocalPdf = openLocalPdf,
                    onOpenLocalText = openLocalText,
                    onPlayLocalAudio = playLocalAudio,
                    onOpenImage = { chatId, msgId -> nav.navigate("image/$msgId?chatId=$chatId") },
                    onOpenTimelineImage = { chatId, msgId, albumId ->
                        val encodedAlbum = albumId?.let { Uri.encode(it) }.orEmpty()
                        nav.navigate("image/$msgId?chatId=$chatId&timeline=true&albumId=$encodedAlbum")
                    },
                    onOpenPdf = { chatId, msgId -> nav.navigate("pdf/$msgId?chatId=$chatId") },
                    onOpenZip = { msgId -> nav.navigate("zip/$msgId") },
                    onOpenZipEntry = { chatId, zipMsgId, entryPath ->
                        nav.navigate("zip/$zipMsgId?chatId=$chatId&entry=${Uri.encode(entryPath)}")
                    },
                    onOpenText = { chatId, msgId -> nav.navigate("text/$msgId?chatId=$chatId") },
                    onOpenHistory = { nav.navigate("history") },
                    onOpenDownloads = { nav.navigate("downloads") },
                    onOpenCacheSettings = { nav.navigate("cache_settings") },
                    onOpenAbout = { nav.navigate("about") },
                    onOpenSettings = { nav.navigate("settings") },
                    onOpenTrash = { nav.navigate("trash") },
                    onOpenFreeUpSpace = { nav.navigate("free_up_space") },
                    onOpenVault = { nav.navigate("vault") },
                )
            }

            composable("history") {
                BackupHistoryScreen(
                    onBack = { nav.popBackStack() },
                    onOpenDetails = { jobId -> nav.navigate("synclog/$jobId") },
                )
            }

            composable(
                "synclog/{jobId}",
                arguments = listOf(navArgument("jobId") { type = NavType.StringType }),
            ) { entry ->
                val jobId = entry.arguments?.getString("jobId") ?: return@composable
                SyncLogDetailsScreen(jobId = jobId, onBack = { nav.popBackStack() })
            }

            composable(
                "video/{msgId}?chatId={chatId}",
                arguments = listOf(
                    navArgument("msgId") { type = NavType.LongType },
                    navArgument("chatId") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                ),
            ) { entry ->
                val msgId = entry.arguments?.getLong("msgId") ?: return@composable
                val chatId = entry.arguments?.getLong("chatId") ?: 0L
                VideoPlayerScreen(messageId = msgId, chatId = chatId, onBack = { nav.popBackStack() })
            }

            composable(
                "local_video?path={path}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
                if (path.isNotBlank()) {
                    DisposableEffect(path) {
                        onDispose { rootVm.onTempViewerClosed(path) }
                    }
                    VideoPlayerScreen(
                        messageId = 0L,
                        chatId = 0L,
                        initialLocalPath = path,
                        onBack = {
                            rootVm.onTempViewerClosed(path)
                            nav.popBackStack()
                        },
                    )
                }
            }

            composable(
                "local_pdf?path={path}&title={title}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("title") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
                val title = Uri.decode(entry.arguments?.getString("title").orEmpty()).takeIf { it.isNotBlank() }
                if (path.isNotBlank()) {
                    DisposableEffect(path) {
                        onDispose { rootVm.onTempViewerClosed(path) }
                    }
                    PdfViewerScreen(
                        messageId = 0L,
                        initialLocalPath = path,
                        initialTitle = title,
                        onBack = {
                            rootVm.onTempViewerClosed(path)
                            nav.popBackStack()
                        },
                    )
                }
            }

            composable(
                "local_text?path={path}&title={title}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("title") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
                val title = Uri.decode(entry.arguments?.getString("title").orEmpty()).takeIf { it.isNotBlank() }
                if (path.isNotBlank()) {
                    DisposableEffect(path) {
                        onDispose { rootVm.onTempViewerClosed(path) }
                    }
                    TextViewerScreen(
                        messageId = 0L,
                        chatId = 0L,
                        initialLocalPath = path,
                        initialTitle = title,
                        onBack = {
                            rootVm.onTempViewerClosed(path)
                            nav.popBackStack()
                        },
                    )
                }
            }

            composable("vault") {
                VaultScreen(
                    onBack = { nav.popBackStack() },
                    onOpenZip = { msgId -> nav.navigate("zip/$msgId") },
                    onOpenVideo = { chatId, msgId -> nav.navigate("video/$msgId?chatId=$chatId") },
                    onOpenImage = { chatId, msgId -> nav.navigate("image/$msgId?chatId=$chatId") },
                    onOpenPdf = { chatId, msgId -> nav.navigate("pdf/$msgId?chatId=$chatId") },
                    onOpenText = { chatId, msgId -> nav.navigate("text/$msgId?chatId=$chatId") },
                    onOpenLocalVideo = openLocalVideo,
                    onOpenLocalPdf = openLocalPdf,
                    onOpenLocalText = openLocalText,
                    onPlayLocalAudio = playLocalAudio,
                )
            }

            composable(
                "pdf/{msgId}?chatId={chatId}",
                arguments = listOf(
                    navArgument("msgId") { type = NavType.LongType },
                    navArgument("chatId") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                ),
            ) { entry ->
                val msgId = entry.arguments?.getLong("msgId") ?: return@composable
                val chatId = entry.arguments?.getLong("chatId") ?: 0L
                PdfViewerScreen(messageId = msgId, chatId = chatId, onBack = { nav.popBackStack() })
            }

            composable(
                "image/{msgId}?chatId={chatId}&timeline={timeline}&albumId={albumId}",
                arguments = listOf(
                    navArgument("msgId") { type = NavType.LongType },
                    navArgument("chatId") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                    navArgument("timeline") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                    navArgument("albumId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val msgId = entry.arguments?.getLong("msgId") ?: return@composable
                val chatId = entry.arguments?.getLong("chatId") ?: 0L
                val timeline = entry.arguments?.getBoolean("timeline") ?: false
                val albumId = Uri.decode(entry.arguments?.getString("albumId").orEmpty())
                ImagePlayerScreen(
                    messageId = msgId,
                    chatId = chatId,
                    timeline = timeline,
                    albumId = albumId,
                    onOpenVideo = { vChatId, vMsgId -> nav.navigate("video/$vMsgId?chatId=$vChatId") },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(
                "text/{msgId}?chatId={chatId}",
                arguments = listOf(
                    navArgument("msgId") { type = NavType.LongType },
                    navArgument("chatId") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                ),
            ) { entry ->
                val msgId = entry.arguments?.getLong("msgId") ?: return@composable
                val chatId = entry.arguments?.getLong("chatId") ?: 0L
                TextViewerScreen(messageId = msgId, chatId = chatId, onBack = { nav.popBackStack() })
            }

            composable(
                "zip/{msgId}?chatId={chatId}&entry={entry}",
                arguments = listOf(
                    navArgument("msgId") { type = NavType.LongType },
                    navArgument("chatId") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                    navArgument("entry") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val msgId = entry.arguments?.getLong("msgId") ?: return@composable
                val chatId = entry.arguments?.getLong("chatId") ?: 0L
                val entryPath = entry.arguments?.getString("entry").orEmpty()
                ZipBrowserScreen(
                    messageId = msgId,
                    chatId = chatId,
                    initialEntryPath = Uri.decode(entryPath),
                    onOpenLocalVideo = openLocalVideo,
                    onOpenLocalPdf = openLocalPdf,
                    onOpenLocalText = openLocalText,
                    onPlayLocalAudio = playLocalAudio,
                    onBack = { nav.popBackStack() },
                )
            }

            composable("downloads") {
                DownloadListScreen(
                    showBack = true,
                    onBack = { nav.popBackStack() },
                    onOpenLocalVideo = openLocalVideo,
                    onOpenLocalPdf = openLocalPdf,
                    onOpenLocalText = openLocalText,
                    onPlayLocalAudio = playLocalAudio,
                )
            }

            composable("settings") {
                SettingsScreen(
                    showBack = true,
                    onBack = { nav.popBackStack() },
                    onOpenCache = { nav.navigate("cache_settings") },
                    onOpenAbout = { nav.navigate("about") },
                )
            }

            composable("trash") {
                TrashScreen(onBack = { nav.popBackStack() })
            }

            composable("free_up_space") {
                FreeUpSpaceScreen(onBack = { nav.popBackStack() })
            }

            composable("cache_settings") {
                CacheSettingsScreen(onBack = { nav.popBackStack() })
            }

            composable("about") {
                AboutScreen(onBack = { nav.popBackStack() })
            }
        }
    }

    if (state.isAuthorized) {
        AudioPlayerScaffold(content = navContent)
    } else {
        navContent()
    }
}
