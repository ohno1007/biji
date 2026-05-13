package com.biji.notes.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * High-contrast MD3 palette. Background is pure white (light) or pure black
 * (dark), text is the opposite extreme, and a small ladder of surface
 * containers gives the "seamless cutout" feel: cards sit on a near-background
 * tone and the background colour bleeds through the gaps between them,
 * removing the need for any drawn divider lines.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3A3CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E3FF),
    onPrimaryContainer = Color(0xFF0E1066),
    secondary = Color(0xFFFF3D74),
    onSecondary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF050505),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF050505),
    surfaceVariant = Color(0xFFF1F1F4),
    onSurfaceVariant = Color(0xFF3D3D44),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F9),
    surfaceContainer = Color(0xFFF1F1F4),
    surfaceContainerHigh = Color(0xFFE9E9ED),
    surfaceContainerHighest = Color(0xFFE2E2E7),
    outline = Color(0xFFCFCFD5),
    outlineVariant = Color(0xFFE3E3E8),
    error = Color(0xFFD81B4F)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7B7FF),
    onPrimary = Color(0xFF050530),
    primaryContainer = Color(0xFF2024B8),
    onPrimaryContainer = Color(0xFFE6E6FF),
    secondary = Color(0xFFFF7BA0),
    onSecondary = Color(0xFF3A0014),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF8F8F8),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF8F8F8),
    surfaceVariant = Color(0xFF1A1A1D),
    onSurfaceVariant = Color(0xFFB8B8C2),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0B0B0D),
    surfaceContainer = Color(0xFF131316),
    surfaceContainerHigh = Color(0xFF1B1B1F),
    surfaceContainerHighest = Color(0xFF24242A),
    outline = Color(0xFF3A3A42),
    outlineVariant = Color(0xFF24242A),
    error = Color(0xFFFF6B8A)
)

@Composable
fun BijiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(colorScheme = colors, typography = BijiTypography, content = content)
}
