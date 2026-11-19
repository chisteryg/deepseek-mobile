package com.deepseek.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App-specific colour roles that Material's scheme does not model: the muted
 * page background, chat bubbles, hairline borders and status inks. Components
 * read these through [LocalDsPalette] so both themes stay in one place.
 */
data class DsPalette(
    val background: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val brand: Color,
    val brandSoft: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val codeBackground: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
)

private val LightPalette = DsPalette(
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF2F4F8),
    border = Color(0xFFE7EAF1),
    textPrimary = Color(0xFF14161C),
    textSecondary = Color(0xFF6B7280),
    textTertiary = Color(0xFF9AA1AE),
    brand = Color(0xFF4D6BFE),
    brandSoft = Color(0xFFE9EDFF),
    userBubble = Color(0xFFEFF3FF),
    userBubbleText = Color(0xFF1B2333),
    codeBackground = Color(0xFFF5F6FA),
    success = Color(0xFF1F9D63),
    warning = Color(0xFFB27A00),
    danger = Color(0xFFD14343),
)

private val DarkPalette = DsPalette(
    background = Color(0xFF0E1015),
    surface = Color(0xFF15181F),
    surfaceMuted = Color(0xFF1D212A),
    border = Color(0xFF262B36),
    textPrimary = Color(0xFFE9EBF1),
    textSecondary = Color(0xFF9BA3B4),
    textTertiary = Color(0xFF6E7687),
    brand = Color(0xFF8CA0FF),
    brandSoft = Color(0xFF1E2746),
    userBubble = Color(0xFF1F2946),
    userBubbleText = Color(0xFFE7EBF7),
    codeBackground = Color(0xFF1A1E27),
    success = Color(0xFF4ECB8F),
    warning = Color(0xFFE0AD4A),
    danger = Color(0xFFFF8A80),
)

val LocalDsPalette = staticCompositionLocalOf { LightPalette }

private val LightColors = lightColorScheme(
    primary = Color(0xFF4D6BFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9EDFF),
    onPrimaryContainer = Color(0xFF15235C),
    secondary = Color(0xFF5A6280),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDF0F7),
    onSecondaryContainer = Color(0xFF2A3040),
    tertiary = Color(0xFF1F9D63),
    tertiaryContainer = Color(0xFFE4F5EC),
    onTertiaryContainer = Color(0xFF0D3A25),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF14161C),
    surface = Color.White,
    onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFF2F4F8),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFE7EAF1),
    outlineVariant = Color(0xFFEFF2F7),
    error = Color(0xFFD14343),
    errorContainer = Color(0xFFFBE9E9),
    onErrorContainer = Color(0xFF5C1414),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CA0FF),
    onPrimary = Color(0xFF0B1533),
    primaryContainer = Color(0xFF1E2746),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFB9C0D0),
    onSecondary = Color(0xFF14161C),
    secondaryContainer = Color(0xFF232833),
    onSecondaryContainer = Color(0xFFE3E7F0),
    tertiary = Color(0xFF4ECB8F),
    tertiaryContainer = Color(0xFF16302A),
    onTertiaryContainer = Color(0xFFCFF5E3),
    background = Color(0xFF0E1015),
    onBackground = Color(0xFFE9EBF1),
    surface = Color(0xFF15181F),
    onSurface = Color(0xFFE9EBF1),
    surfaceVariant = Color(0xFF1D212A),
    onSurfaceVariant = Color(0xFF9BA3B4),
    outline = Color(0xFF262B36),
    outlineVariant = Color(0xFF1F242E),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF3A1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 27.sp,
        letterSpacing = 0.2.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun DeepSeekTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDsPalette provides if (darkTheme) DarkPalette else LightPalette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
