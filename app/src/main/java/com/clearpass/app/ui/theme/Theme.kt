package com.clearpass.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val CyberBlack = Color(0xFF0A0A0A)
val CyberDark = Color(0xFF111111)
val CyberSurface = Color(0xFF161616)
val CyberSurfaceVariant = Color(0xFF1E1E1E)

val NeonGreen = Color(0xFF00FF9F)
val NeonCyan = Color(0xFF00E5FF)
val NeonPink = Color(0xFFFF2E97)
val NeonYellow = Color(0xFFFFD600)

val TextPrimary = Color(0xFFE0E0E0)
val TextSecondary = Color(0xFFAAAAAA)
val TextMuted = Color(0xFF666666)
val ErrorRed = Color(0xFFFF5555)

private val CyberDarkColorScheme = darkColorScheme(
    primary = NeonGreen,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF003D2E),
    onPrimaryContainer = NeonGreen,
    secondary = NeonCyan,
    onSecondary = Color.Black,
    background = CyberBlack,
    onBackground = TextPrimary,
    surface = CyberDark,
    onSurface = TextPrimary,
    surfaceVariant = CyberSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = Color.Black,
    outline = Color(0xFF333333)
)

private val CyberTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp)
)

@Composable
fun ClearPassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CyberDarkColorScheme,
        typography = CyberTypography,
        content = content
    )
}
