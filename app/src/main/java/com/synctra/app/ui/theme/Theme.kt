package com.synctra.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Matte obsidian palette inspired by studio audio & cinema timecode tools.
 */
object SynctraColors {
    val Background = Color(0xFF0A0A0C)
    val Surface = Color(0xFF141418)
    val SurfaceSubtle = Color(0xFF1C1C22)
    val Stage = Color(0xFF0E0E12)
    val Hairline = Color(0xFF26262E)

    val InkPrimary = Color(0xFFF5F5F7)
    val InkSecondary = Color(0xFFA1A1AA)
    val InkMuted = Color(0xFF71717A)

    // High-contrast monochrome primary CTA
    val ActionBg = Color(0xFFF5F5F7)
    val ActionInk = Color(0xFF0A0A0C)

    // Single purposeful studio-amber accent for active timecode / audio selection
    val Accent = Color(0xFFF59E0B)
    val AccentDim = Color(0x22F59E0B)

    // Semantic status colors (used sparingly)
    val Synced = Color(0xFF4ADE80)
    val SyncedDim = Color(0x1E4ADE80)
    val Danger = Color(0xFFEF4444)
    val DangerDim = Color(0x24EF4444)
}

private val SynctraDarkColorScheme = darkColorScheme(
    primary = SynctraColors.ActionBg,
    onPrimary = SynctraColors.ActionInk,
    secondary = SynctraColors.Accent,
    onSecondary = SynctraColors.ActionInk,
    background = SynctraColors.Background,
    onBackground = SynctraColors.InkPrimary,
    surface = SynctraColors.Surface,
    onSurface = SynctraColors.InkPrimary,
    surfaceVariant = SynctraColors.SurfaceSubtle,
    onSurfaceVariant = SynctraColors.InkSecondary,
    outline = SynctraColors.Hairline,
    error = SynctraColors.Danger,
)

private val SynctraTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.6).sp,
        color = SynctraColors.InkPrimary,
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = SynctraColors.InkPrimary,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = SynctraColors.InkSecondary,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = SynctraColors.InkMuted,
    ),
)

@Composable
fun SynctraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SynctraDarkColorScheme,
        typography = SynctraTypography,
        content = content,
    )
}
