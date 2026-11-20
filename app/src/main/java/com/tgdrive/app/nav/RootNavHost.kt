package com.tgdrive.app.nav

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
import com.tgdrive.feature.autobackup.SyncLogDetailsScreen
import com.tgdrive.feature.browser.ZipBrowserScreen
import com.tgdrive.feature.download.DownloadListScreen
import com.tgdrive.feature.player.AudioPlayerScaffold
import com.tgdrive.feature.player.ImagePlayerScreen
import com.tgdrive.feature.player.PdfViewerScreen
import com.tgdrive.feature.player.VideoPlayerScreen
import com.tgdrive.feature.settings.AboutScreen
import com.tgdrive.feature.settings.CacheSettingsScreen

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
                    onOpenVideo = { msgId -> nav.navigate("video/$msgId") },
                    onOpenImage = { msgId -> nav.navigate("image/$msgId") },
                    onOpenPdf = { msgId -> nav.navigate("pdf/$msgId") },
                    onOpenZip = { msgId -> nav.navigate("zip/$msgId") },
                    onOpenHistory = { nav.navigate("history") },
                    onOpenDownloads = { nav.navigate("downloads") },
                    onOpenCacheSettings = { nav.navigate("cache_settings") },
                    onOpenAbout = { nav.navigate("about") },
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
                "video/{msgId}",
                arguments = listOf(navArgument("msgId") { type = NavType.LongType }),
            ) { entry ->
                val msgId = entry.arguments?.getLong("msgId") ?: return@composable
                VideoPlayerScreen(messageId = msgId, onBack = { nav.popBackStack() })
            }

            composable(
                "pdf/{msgId}",
                arguments = listOf(navArgument("msgId") { type = NavType.LongType }),
            ) { entry ->
                val msgId = entry.arguments?.getLong("msgId") ?: return@composable
                PdfViewerScreen(messageId = msgId, onBack = { nav.popBackStack() })
            }

            composable(
                "image/{msgId}",
                arguments = listOf(navArgument("msgId") { type = NavType.LongType }),
            ) { entry ->
                val msgId = entry.arguments?.getLong("msgId") ?: return@composable
                ImagePlayerScreen(messageId = msgId, onBack = { nav.popBackStack() })
            }

            composable(
                "zip/{msgId}",
                arguments = listOf(navArgument("msgId") { type = NavType.LongType }),
            ) { entry ->
                val msgId = entry.arguments?.getLong("msgId") ?: return@composable
                ZipBrowserScreen(messageId = msgId, onBack = { nav.popBackStack() })
            }

            composable("downloads") {
                DownloadListScreen(showBack = true, onBack = { nav.popBackStack() })
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
