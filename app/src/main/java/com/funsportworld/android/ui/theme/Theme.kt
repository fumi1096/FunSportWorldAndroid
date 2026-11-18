package com.funsportworld.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BrandGreen = Color(0xFF0E7A5F)
val BrandGreenDark = Color(0xFF075C46)
val BrandGreenSoft = Color(0xFFD7EFE7)
val BrandAmber = Color(0xFFFFC857)
val BrandBlue = Color(0xFF2F6FED)
val BrandRed = Color(0xFFB13946)
val Ink = Color(0xFF17221E)
val InkMuted = Color(0xFF5B6B65)
val SurfaceSoft = Color(0xFFF4F7F5)
val LineSoft = Color(0xFFE1E8E4)

private val LightColors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    primaryContainer = BrandGreenSoft,
    onPrimaryContainer = BrandGreenDark,
    secondary = BrandAmber,
    onSecondary = Ink,
    secondaryContainer = Color(0xFFFFEDBE),
    onSecondaryContainer = Color(0xFF5B4300),
    tertiary = BrandBlue,
    onTertiary = Color.White,
    background = Color(0xFFF7FAF8),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = SurfaceSoft,
    onSurfaceVariant = InkMuted,
    outline = LineSoft,
    error = BrandRed,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FD3B4),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513D),
    onPrimaryContainer = Color(0xFFD7EFE7),
    secondary = BrandAmber,
    onSecondary = Color(0xFF3B2F00),
    tertiary = Color(0xFFA9C5FF),
    onTertiary = Color(0xFF002E6B),
    background = Color(0xFF0F1513),
    onBackground = Color(0xFFE4EBE7),
    surface = Color(0xFF151C19),
    onSurface = Color(0xFFE4EBE7),
    surfaceVariant = Color(0xFF1E2723),
    onSurfaceVariant = Color(0xFFB4C3BC),
    outline = Color(0xFF34423C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun FunSportTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
