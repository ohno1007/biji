package com.biji.notes.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vertical alpha gradient that mimics the deep blur fade-out of the chat
 * page – content scrolling underneath dissolves smoothly into the page
 * background colour before reaching the floating top app bar / bottom
 * navigation. Pure gradient (no [androidx.compose.ui.draw.blur]) so it
 * works on every device and doesn't capture the underlying graphics
 * layer.
 */
@Composable
fun TopFade(
    height: Dp = 96.dp,
    modifier: Modifier = Modifier
) {
    val bg = MaterialTheme.colorScheme.background
    val density = LocalDensity.current
    val statusBar = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    Box(
        modifier
            .fillMaxWidth()
            .height(statusBar + height)
            .background(
                Brush.verticalGradient(
                    0.0f to bg,
                    0.55f to bg.copy(alpha = 0.92f),
                    1.0f to bg.copy(alpha = 0f)
                )
            )
    )
}

@Composable
fun BottomFade(
    height: Dp = 180.dp,
    modifier: Modifier = Modifier
) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    0.0f to bg.copy(alpha = 0f),
                    0.45f to bg.copy(alpha = 0.92f),
                    1.0f to bg
                )
            )
    )
}
