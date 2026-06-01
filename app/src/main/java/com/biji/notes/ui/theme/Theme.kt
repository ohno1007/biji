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
    primary = Color(0xFF1F6FEB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE9FF),
    onPrimaryContainer = Color(0xFF0A1F4D),
    secondary = Color(0xFFFF3D74),
    onSecondary = Color.White,
    // Neutral charcoal — the missing mid-tone the palette needed; used by
    // the send dot, dark chips, primary call-to-action contrast.
    tertiary = Color(0xFF26262C),
    onTertiary = Color(0xFFF5F4F0),
    tertiaryContainer = Color(0xFFE5E4E0),
    onTertiaryContainer = Color(0xFF1A1A1F),
    // Soft cream background; the surfaceContainer card is brighter than it,
    // which gives the "镂空" feel of iOS settings groups.
    background = Color(0xFFF4F1EB),
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFEDEAE3),
    onSurfaceVariant = Color(0xFF55524C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFAF7),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1EFEA),
    surfaceContainerHighest = Color(0xFFE8E5DE),
    outline = Color(0xFFC9C5BE),
    outlineVariant = Color(0xFFE3E0D8),
    error = Color(0xFFB42E3F)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FBDFF),
    onPrimary = Color(0xFF06143A),
    primaryContainer = Color(0xFF1F3F8E),
    onPrimaryContainer = Color(0xFFDDE7FF),
    secondary = Color(0xFFFF7BA0),
    onSecondary = Color(0xFF3A0014),
    tertiary = Color(0xFFE7E7EA),
    onTertiary = Color(0xFF1A1A1F),
    tertiaryContainer = Color(0xFF2A2A2E),
    onTertiaryContainer = Color(0xFFE7E7EA),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFF1F0EC),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFF1F0EC),
    surfaceVariant = Color(0xFF1F1F22),
    onSurfaceVariant = Color(0xFFBAB6AE),
    surfaceContainerLowest = Color(0xFF050505),
    surfaceContainerLow = Color(0xFF111113),
    surfaceContainer = Color(0xFF15161A),
    surfaceContainerHigh = Color(0xFF1D1E22),
    surfaceContainerHighest = Color(0xFF26272B),
    outline = Color(0xFF44454A),
    outlineVariant = Color(0xFF26272B),
    error = Color(0xFFFF8092)
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
