package com.snowball.taskwt.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.snowball.taskwt.core.ThemePreference

val BrandBlue = Color(0xFF2563EB)
val BrandBlueDark = Color(0xFF1D4ED8)
val SuccessGreen = Color(0xFF16A34A)
val WarningAmber = Color(0xFFD97706)
val DangerRed = Color(0xFFDC2626)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF475569),
    background = Color(0xFFF6F8FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F5F9),
    outline = Color(0xFFD9E1EC),
    error = DangerRed,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF082F6B),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF94A3B8),
    background = Color(0xFF0B1120),
    surface = Color(0xFF111827),
    surfaceVariant = Color(0xFF1E293B),
    outline = Color(0xFF334155),
    error = Color(0xFFF87171),
)

@Composable
fun TaskWtTheme(
    preference: ThemePreference,
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}

@Composable
fun ColorScheme.statusColor(status: String): Color = when {
    status.contains("READY") || status == "SUCCESS" -> SuccessGreen
    status == "FAILED" || status == "CONFLICT" -> error
    status == "PARTIAL" || status.contains("WARNING") -> WarningAmber
    else -> primary
}
