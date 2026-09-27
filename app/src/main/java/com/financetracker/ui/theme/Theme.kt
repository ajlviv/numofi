package com.financetracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.financetracker.data.settings.ThemeMode

private val FinanceTrackerColorPalette = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3F2FD),
    secondary = Color(0xFF00BCD4),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF212121),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF212121),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF)
)

private val FinanceTrackerDarkColorPalette = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF00497F),
    secondary = Color(0xFF4DD0E1),
    onSecondary = Color(0xFF00363B),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFE0E0E0),
    error = Color(0xFFEF9A9A),
    onError = Color(0xFF4A1113)
)

private val FinanceTrackerTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp)
)

@Composable
fun FinanceTrackerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) {
            FinanceTrackerDarkColorPalette
        } else {
            FinanceTrackerColorPalette
        },
        typography = FinanceTrackerTypography,
        content = content
    )
}