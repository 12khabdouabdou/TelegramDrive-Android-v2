package com.tgdrive.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tgdrive.app.nav.RootNavHost
import com.tgdrive.core.datastore.ThemeMode
import com.tgdrive.core.security.BiometricHelper
import com.tgdrive.designsystem.components.OfflineBanner
import com.tgdrive.designsystem.theme.TelegramDriveTheme
import com.tgdrive.designsystem.theme.TgTypography
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var biometricHelper: BiometricHelper
    @Inject lateinit var pipController: com.tgdrive.core.common.pip.PipController

    private var rootVmRef: RootViewModel? = null
    private var currentIntent by mutableStateOf<Intent?>(null)

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        currentIntent = intent

        setContent {
            val rootVm: RootViewModel = hiltViewModel()
            rootVmRef = rootVm
            val state by rootVm.state.collectAsStateWithLifecycle()

            LaunchedEffect(currentIntent) {
                currentIntent?.let { rootVm.handleShareIntent(it) }
            }

            val darkTheme = when (state.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            val view = LocalView.current
            SideEffect {
                val controller = WindowInsetsControllerCompat(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }

            TelegramDriveTheme(darkTheme = darkTheme) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    OfflineBanner(visible = !state.isOnline, queuedOps = 0)
                    val contentModifier = if (!state.isOnline) {
                        Modifier
                            .weight(1f)
                            .consumeWindowInsets(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                    } else {
                        Modifier.weight(1f)
                    }
                    Box(modifier = contentModifier) {
                        RootNavHost(state = state, rootVm = rootVm)

                        if (state.biometricRequired && !state.biometricUnlocked) {
                            BiometricGate(
                                onUnlock = { rootVm.onBiometricUnlocked() },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentIntent = intent
    }

    override fun onStop() {
        super.onStop()
        rootVmRef?.onAppBackgrounded()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipController.shouldEnterPip()) {
            pipController.enterPip(this)
        }
    }

    override fun onStart() {
        super.onStart()
        rootVmRef?.onAppForegrounded()
    }

    @Composable
    private fun BiometricGate(onUnlock: () -> Unit) {
        var error by remember { mutableStateOf<String?>(null) }
        var retryTrigger by remember { mutableIntStateOf(0) }
        val promptTitle = stringResource(R.string.biometric_prompt_title)
        val promptSubtitle = stringResource(R.string.biometric_prompt_subtitle)

        DisposableEffect(Unit) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        LaunchedEffect(retryTrigger) {
            try {
                if (biometricHelper.availability() != BiometricHelper.Availability.AVAILABLE) {
                    onUnlock()
                    return@LaunchedEffect
                }
                biometricHelper.authenticate(
                    activity = this@MainActivity,
                    title = promptTitle,
                    subtitle = promptSubtitle,
                    onSuccess = { onUnlock() },
                    onError = { msg -> error = msg },
                )
            } catch (t: Throwable) {
                error = t.message
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Fingerprint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(36.dp),
                    )
                }
                Text(
                    stringResource(R.string.biometric_locked_title),
                    style = TgTypography.Display.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.biometric_locked_subtitle),
                    style = TgTypography.Display.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, style = TgTypography.Display.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    error = null
                    retryTrigger++
                }) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}
