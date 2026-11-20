package com.tgdrive.designsystem.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Composable
fun TelegramDriveTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkColorScheme else LightColorScheme
    val spec = tween<Color>(durationMillis = 300)

    val colors = base.copy(
        background = animateColorAsState(base.background, spec, label = "bg").value,
        surface = animateColorAsState(base.surface, spec, label = "sf").value,
        surfaceVariant = animateColorAsState(base.surfaceVariant, spec, label = "sv").value,
        surfaceContainer = animateColorAsState(base.surfaceContainer, spec, label = "sc").value,
        surfaceContainerHigh = animateColorAsState(base.surfaceContainerHigh, spec, label = "sch").value,
        surfaceContainerHighest = animateColorAsState(base.surfaceContainerHighest, spec, label = "sche").value,
        surfaceContainerLow = animateColorAsState(base.surfaceContainerLow, spec, label = "scl").value,
        primary = animateColorAsState(base.primary, spec, label = "p").value,
        onPrimary = animateColorAsState(base.onPrimary, spec, label = "op").value,
        secondary = animateColorAsState(base.secondary, spec, label = "s").value,
        onSecondary = animateColorAsState(base.onSecondary, spec, label = "os").value,
        tertiary = animateColorAsState(base.tertiary, spec, label = "t").value,
        onTertiary = animateColorAsState(base.onTertiary, spec, label = "ot").value,
        onBackground = animateColorAsState(base.onBackground, spec, label = "obg").value,
        onSurface = animateColorAsState(base.onSurface, spec, label = "osf").value,
        onSurfaceVariant = animateColorAsState(base.onSurfaceVariant, spec, label = "osv").value,
        outline = animateColorAsState(base.outline, spec, label = "o").value,
    )

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colors,
            typography = TgTypography.Display,
            shapes = TgShapes.MaterialShapes,
            content = content,
        )
    }
}

private val DarkColorScheme = darkColorScheme(
    primary = TgColors.Primary,
    onPrimary = TgColors.OnPrimary,
    primaryContainer = TgColors.PrimaryContainerDark,
    onPrimaryContainer = TgColors.Primary,
    secondary = TgColors.Secondary,
    onSecondary = TgColors.OnSecondary,
    secondaryContainer = TgColors.SecondaryContainer,
    onSecondaryContainer = TgColors.TextPrimary,
    tertiary = TgColors.Tertiary,
    onTertiary = TgColors.OnTertiary,
    background = TgColors.Background,
    onBackground = TgColors.TextPrimary,
    surface = TgColors.Surface,
    onSurface = TgColors.TextPrimary,
    surfaceVariant = TgColors.SurfaceContainer,
    onSurfaceVariant = TgColors.TextMuted,
    surfaceContainer = TgColors.SurfaceContainer,
    surfaceContainerHigh = TgColors.SurfaceContainerHigh,
    surfaceContainerHighest = TgColors.SurfaceContainerHighest,
    surfaceContainerLow = TgColors.SurfaceContainerLow,
    surfaceContainerLowest = TgColors.SurfaceContainerLowest,
    error = TgColors.Error,
    onError = TgColors.OnError,
    errorContainer = TgColors.ErrorContainer,
    onErrorContainer = TgColors.Error,
    outline = TgColors.Border,
    outlineVariant = TgColors.GlassPanelBorder,
)

private val LightColorScheme = lightColorScheme(
    primary = TgColors.LightPrimary,
    onPrimary = TgColors.LightOnPrimary,
    primaryContainer = TgColors.LightPrimaryContainer,
    onPrimaryContainer = TgColors.LightOnPrimaryContainer,
    secondary = TgColors.LightSecondary,
    onSecondary = TgColors.LightOnSecondary,
    tertiary = TgColors.LightTertiary,
    onTertiary = TgColors.LightOnTertiary,
    background = TgColors.LightBackground,
    onBackground = TgColors.LightTextPrimary,
    surface = TgColors.LightSurface,
    onSurface = TgColors.LightTextPrimary,
    surfaceVariant = TgColors.LightSurfaceContainerHigh,
    onSurfaceVariant = TgColors.LightTextMuted,
    surfaceContainer = TgColors.LightSurfaceContainer,
    surfaceContainerHigh = TgColors.LightSurfaceContainerHigh,
    surfaceContainerHighest = TgColors.LightSurfaceContainerHighest,
    surfaceContainerLow = TgColors.LightSurfaceContainerLow,
    surfaceContainerLowest = TgColors.LightSurface,
    error = TgColors.LightError,
    onError = TgColors.LightOnError,
    errorContainer = TgColors.LightErrorContainer,
    onErrorContainer = TgColors.LightOnErrorContainer,
    outline = TgColors.LightBorder,
    outlineVariant = TgColors.LightGlassPanelBorder,
)

val LocalDarkTheme = staticCompositionLocalOf { true }
