package com.tgdrive.app.nav

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
                    onOpenLocalVideo = { path -> nav.navigate("local_video?path=${Uri.encode(path)}") },
                    onOpenImage = { chatId, msgId -> nav.navigate("image/$msgId?chatId=$chatId") },
                    onOpenPdf = { msgId -> nav.navigate("pdf/$msgId") },
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
                    VideoPlayerScreen(
                        messageId = 0L,
                        chatId = 0L,
                        initialLocalPath = path,
                        onBack = { nav.popBackStack() },
                    )
                }
            }

            composable(
                "pdf/{msgId}",
                arguments = listOf(navArgument("msgId") { type = NavType.LongType }),
            ) { entry ->
                val msgId = entry.arguments?.getLong("msgId") ?: return@composable
                PdfViewerScreen(messageId = msgId, onBack = { nav.popBackStack() })
            }

            composable(
                "image/{msgId}?chatId={chatId}",
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
                ImagePlayerScreen(
                    messageId = msgId,
                    chatId = chatId,
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
                    onBack = { nav.popBackStack() },
                )
            }

            composable("downloads") {
                DownloadListScreen(
                    showBack = true,
                    onBack = { nav.popBackStack() },
                    onOpenLocalVideo = { path -> nav.navigate("local_video?path=${Uri.encode(path)}") },
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
