package ru.sostav.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.sostav.R

private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE7FF),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF047857),
    onSecondary = Color.White,
    tertiary = Color(0xFF6D28D9),
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF334155),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFB91C1C),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF93C5FD),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBE7FF),
    secondary = Color(0xFF6EE7B7),
    onSecondary = Color(0xFF064E3B),
    tertiary = Color(0xFFDDD6FE),
    onTertiary = Color(0xFF2E1065),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF475569),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF7F1D1D),
)

private fun style(weight: FontWeight, size: Int, line: Int) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
)

private val AppType = Typography(
    headlineSmall = style(FontWeight.SemiBold, 24, 32),
    titleLarge = style(FontWeight.SemiBold, 22, 28),
    titleMedium = style(FontWeight.SemiBold, 18, 24),
    titleSmall = style(FontWeight.SemiBold, 16, 22),
    bodyLarge = style(FontWeight.Normal, 16, 24),
    bodyMedium = style(FontWeight.Normal, 14, 20),
    bodySmall = style(FontWeight.Normal, 12, 16),
    labelLarge = style(FontWeight.Medium, 14, 20),
    labelMedium = style(FontWeight.Medium, 12, 16),
)

@Composable
fun SostavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppType,
        content = content,
    )
}
