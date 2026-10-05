package com.setbd.vibeshare.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * VibeShare (SETBD) visual identity:
 * deep-space navy surfaces, electric violet primary, neon cyan accent,
 * soft gradients and careful glow. Original design - not derived from any
 * other sharing app.
 */
object VibeColors {
    val Violet = Color(0xFF7C5CFF)
    val VioletDeep = Color(0xFF5B3DF5)
    val Cyan = Color(0xFF22E4FF)
    val Mint = Color(0xFF3AF0B6)
    val Red = Color(0xFFFF5C7A)
    val Amber = Color(0xFFFFC24B)

    val SpaceBg = Color(0xFF0B0E1A)
    val SpaceSurface = Color(0xFF141931)
    val SpaceSurfaceHigh = Color(0xFF1B2140)
    val SpaceOutline = Color(0xFF2A3160)

    val AmoledBg = Color(0xFF000000)
    val AmoledSurface = Color(0xFF0A0A12)
    val AmoledSurfaceHigh = Color(0xFF12121E)

    val LightBg = Color(0xFFF5F6FF)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceHigh = Color(0xFFEDEFFF)
    val LightOutline = Color(0xFFD5D8F2)
}

private fun darkScheme(amoled: Boolean): ColorScheme = darkColorScheme(
    primary = VibeColors.Violet,
    onPrimary = Color.White,
    primaryContainer = if (amoled) VibeColors.AmoledSurfaceHigh else VibeColors.SpaceSurfaceHigh,
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = VibeColors.Cyan,
    onSecondary = Color(0xFF00232B),
    secondaryContainer = VibeColors.VioletDeep,
    onSecondaryContainer = Color(0xFFD8F7FF),
    tertiary = VibeColors.Mint,
    background = if (amoled) VibeColors.AmoledBg else VibeColors.SpaceBg,
    onBackground = Color(0xFFEDEFFF),
    surface = if (amoled) VibeColors.AmoledSurface else VibeColors.SpaceSurface,
    onSurface = Color(0xFFEDEFFF),
    surfaceVariant = if (amoled) VibeColors.AmoledSurfaceHigh else VibeColors.SpaceSurfaceHigh,
    onSurfaceVariant = Color(0xFFA8AECF),
    outline = if (amoled) Color(0xFF252538) else VibeColors.SpaceOutline,
    error = VibeColors.Red,
    onError = Color.White,
)

private val lightScheme: ColorScheme = lightColorScheme(
    primary = VibeColors.VioletDeep,
    onPrimary = Color.White,
    primaryContainer = VibeColors.LightSurfaceHigh,
    onPrimaryContainer = Color(0xFF231054),
    secondary = Color(0xFF006A78),
    onSecondary = Color.White,
    tertiary = Color(0xFF006B58),
    background = VibeColors.LightBg,
    onBackground = Color(0xFF171A33),
    surface = VibeColors.LightSurface,
    onSurface = Color(0xFF171A33),
    surfaceVariant = VibeColors.LightSurfaceHigh,
    onSurfaceVariant = Color(0xFF565A7C),
    outline = VibeColors.LightOutline,
    error = Color(0xFFB3261E),
    onError = Color.White,
)

val VibeTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

/** App theme honoring user appearance preference including true AMOLED. */
@Composable
fun VibeShareTheme(
    themeMode: com.setbd.vibeshare.domain.model.ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        com.setbd.vibeshare.domain.model.ThemeMode.SYSTEM ->
            androidx.compose.foundation.isSystemInDarkTheme()
        com.setbd.vibeshare.domain.model.ThemeMode.DARK,
        com.setbd.vibeshare.domain.model.ThemeMode.AMOLED,
        -> true
        com.setbd.vibeshare.domain.model.ThemeMode.LIGHT -> false
    }
    val amoled = themeMode == com.setbd.vibeshare.domain.model.ThemeMode.AMOLED
    val colors = if (dark) darkScheme(amoled) else lightScheme
    MaterialTheme(
        colorScheme = colors,
        typography = VibeTypography,
        content = content,
    )
}
