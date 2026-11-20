package com.tgdrive.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Material Design 3 Expressive palette — "Deep Navy + Amber" theme,
 * with full Dark and Light color schemes.
 */
object TgColors {
    // ── Dark Backgrounds (Material 3 surface tonal palette) ──
    val Background = Color(0xFF031524)       // surface-dim (darkest)
    val Surface = Color(0xFF031524)          // surface
    val SurfaceBright = Color(0xFF2A3B4B)    // surface-bright
    val SurfaceContainer = Color(0xFF102131) // surface-container
    val SurfaceContainerHigh = Color(0xFF1B2B3B)
    val SurfaceContainerHighest = Color(0xFF263647) // surface-container-highest / surface-variant
    val SurfaceContainerLow = Color(0xFF0B1D2C)
    val SurfaceContainerLowest = Color(0xFF000F1E)

    // ── Glass panel (semi-transparent surface + hairline border) ──
    val GlassPanel = Color(0xB3263647)       // rgba(38, 54, 71, 0.70)
    val GlassPanelBorder = Color(0x14FFFFFF) // rgba(255, 255, 255, 0.08)

    // ── Cards (backward compat — maps to surface-container) ──
    val Card = SurfaceContainer
    val Border = Color(0x1FFFFFFF)           // rgba(255, 255, 255, 0.12)

    // ── Dark Accents ──
    val Primary = Color(0xFFFFD395)          // amber — main accent
    val PrimaryContainer = Color(0xFFFFAE00) // vivid amber
    val PrimaryContainerDark = Color(0xFF4E3400)
    val OnPrimary = Color(0xFF442B00)        // dark text on amber
    val Secondary = Color(0xFF9DCAFF)        // light blue — info
    val OnSecondary = Color(0xFF002F54)
    val SecondaryContainer = Color(0xFF0073BD)
    val Tertiary = Color(0xFF66F3B6)         // mint green — success/verified
    val OnTertiary = Color(0xFF003824)
    val TertiaryContainer = Color(0xFF44D69B)
    val Error = Color(0xFFFFB4AB)
    val OnError = Color(0xFF690005)
    val ErrorContainer = Color(0xFF93000A)
    val Success = Color(0xFF66F3B6)          // alias for Tertiary

    // ── Legacy accent aliases (backward compat) ──
    val AccentBlue = Secondary
    val AccentTeal = Tertiary
    val AccentAmber = Primary
    val ErrorRed = Error

    // ── Dark Text ──
    val TextPrimary = Color(0xFFD3E4FA)      // on-surface
    val TextMuted = Color(0xFFA9B8CA)        // accessible slate-muted
    val OnSurfaceVariant = Color(0xFFD7C4AC) // warm beige

    // ── Light Palette (WCAG AA compliant) ──
    val LightBackground = Color(0xFFF4F6FA)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceContainer = Color(0xFFFFFFFF)
    val LightSurfaceContainerHigh = Color(0xFFF1F5F9)
    val LightSurfaceContainerHighest = Color(0xFFE2E8F0)
    val LightSurfaceContainerLow = Color(0xFFF8FAFC)
    val LightGlassPanel = Color(0xE6FFFFFF)
    val LightGlassPanelBorder = Color(0x1F0F172A)
    val LightCard = LightSurfaceContainer
    val LightBorder = Color(0xFFCBD5E1)
    val LightTextPrimary = Color(0xFF111827)
    val LightTextMuted = Color(0xFF4B5563)
    val LightPrimary = Color(0xFFB45309)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightPrimaryContainer = Color(0xFFFEF3C7)
    val LightOnPrimaryContainer = Color(0xFF451A03)
    val LightSecondary = Color(0xFF1D4ED8)
    val LightOnSecondary = Color(0xFFFFFFFF)
    val LightTertiary = Color(0xFF047857)
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightError = Color(0xFFDC2626)
    val LightOnError = Color(0xFFFFFFFF)
    val LightErrorContainer = Color(0xFFFEE2E2)
    val LightOnErrorContainer = Color(0xFF7F1D1D)
}

/**
 * File-type tint map — maps to the accent colors.
 */
object FileTypeTints {
    val image = TgColors.Secondary      // blue for photos
    val video = TgColors.Primary        // amber for videos
    val audio = TgColors.Tertiary       // mint for audio
    val pdf = TgColors.Error            // red for PDFs
    val archive = TgColors.TertiaryContainer
    val doc = TgColors.OnSurfaceVariant
    val other = TgColors.Primary
}
