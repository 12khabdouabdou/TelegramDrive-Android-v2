package com.tgdrive.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tgdrive.designsystem.theme.LocalDarkTheme
import com.tgdrive.designsystem.theme.TgColors
import com.tgdrive.designsystem.theme.TgShapes

/**
 * Glassmorphism panel — theme-aware semi-transparent surface with hairline border.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isDark = LocalDarkTheme.current
    val bg = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
    } else {
        TgColors.LightGlassPanel
    }
    val border = if (isDark) TgColors.GlassPanelBorder else TgColors.LightGlassPanelBorder

    Surface(
        modifier = modifier,
        shape = TgShapes.XLarge,
        color = bg,
        border = BorderStroke(1.dp, border),
        content = content,
    )
}

/**
 * Glass panel with a specific background tint — for colored category cards
 * and accented banners.
 */
@Composable
fun TintedGlassPanel(
    tint: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isDark = LocalDarkTheme.current
    Surface(
        modifier = modifier,
        shape = TgShapes.XLarge,
        color = tint.copy(alpha = if (isDark) 0.14f else 0.10f),
        border = BorderStroke(1.dp, tint.copy(alpha = if (isDark) 0.28f else 0.22f)),
        content = content,
    )
}
