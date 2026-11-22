package com.synctra.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object SynctraColors {
    val Background = Color(0xFF0B0F17)
    val Surface = Color(0xFF131A26)
    val SurfaceElevated = Color(0xFF1B2434)
    val Border = Color(0xFF263248)

    val Primary = Color(0xFF38BDF8)
    val PrimaryContainer = Color(0xFF0F2D42)
    val OnPrimary = Color(0xFF05131E)

    val Success = Color(0xFF34D399)
    val SuccessContainer = Color(0xFF0D2E25)

    val Warning = Color(0xFFFBBF24)
    val WarningContainer = Color(0xFF33260C)

    val Error = Color(0xFFF87171)
    val ErrorContainer = Color(0xFF3A1519)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

private val SynctraDarkColorScheme = darkColorScheme(
    primary = SynctraColors.Primary,
    onPrimary = SynctraColors.OnPrimary,
    primaryContainer = SynctraColors.PrimaryContainer,
    onPrimaryContainer = SynctraColors.Primary,
    secondary = SynctraColors.Success,
    onSecondary = Color(0xFF042016),
    background = SynctraColors.Background,
    onBackground = SynctraColors.TextPrimary,
    surface = SynctraColors.Surface,
    onSurface = SynctraColors.TextPrimary,
    surfaceVariant = SynctraColors.SurfaceElevated,
    onSurfaceVariant = SynctraColors.TextSecondary,
    outline = SynctraColors.Border,
    error = SynctraColors.Error,
    errorContainer = SynctraColors.ErrorContainer,
    onErrorContainer = SynctraColors.Error,
)

private val SynctraTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
        color = SynctraColors.TextPrimary,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = SynctraColors.TextPrimary,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = SynctraColors.TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = SynctraColors.TextSecondary,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = SynctraColors.TextSecondary,
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
