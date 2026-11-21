package com.tgdrive.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgdrive.core.common.CrashLogStore
import com.tgdrive.designsystem.editorial.EditorialCard
import com.tgdrive.designsystem.editorial.EditorialKeyValueRow
import com.tgdrive.designsystem.theme.TgTypography

/**
 * About screen pushed over [RootNavHost] with Back navigation, dynamic version,
 * license info, and clickable source URL.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    var crashLog by remember(context) { mutableStateOf(CrashLogStore.read(context)) }
    var crashLogCopied by remember { mutableStateOf(false) }
    val versionName = remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.3"
        } catch (_: Throwable) {
            "1.0.3"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_about_back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = stringResource(R.string.settings_about_title),
                style = TgTypography.Display.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.settings_about_app_name),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = stringResource(R.string.settings_version, versionName),
                    style = TgTypography.Mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = stringResource(R.string.settings_about_tagline),
                    style = TgTypography.Display.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            EditorialCard {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                try {
                                    uriHandler.openUri("https://github.com/TelegramDrive")
                                } catch (_: Throwable) {}
                            },
                    ) {
                        EditorialKeyValueRow(
                            label = stringResource(R.string.settings_about_source_label),
                            value = stringResource(R.string.settings_about_source_value),
                            valueTint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    EditorialKeyValueRow(
                        label = stringResource(R.string.settings_about_license_label),
                        value = stringResource(R.string.settings_about_license_value),
                    )
                }
            }

            crashLog?.let { logText ->
                EditorialCard {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_crash_log_title),
                            style = TgTypography.Display.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = logText.take(1200),
                            style = TgTypography.Mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(logText))
                                    crashLogCopied = true
                                },
                            ) {
                                Text(
                                    stringResource(
                                        if (crashLogCopied) {
                                            R.string.settings_crash_log_copied
                                        } else {
                                            R.string.settings_crash_log_copy
                                        },
                                    ),
                                )
                            }
                            TextButton(
                                onClick = {
                                    CrashLogStore.clear(context)
                                    crashLog = null
                                    crashLogCopied = false
                                },
                            ) {
                                Text(stringResource(R.string.settings_crash_log_clear))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
