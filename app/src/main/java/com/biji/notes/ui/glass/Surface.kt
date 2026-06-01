package com.biji.notes.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Solid filled surface used everywhere as the "card" primitive. No border,
 * no shadow, no glass – the gap to surrounding background is the divider
 * ("无缝镂空分隔").
 *
 * Pass the colour you want explicitly; default is the MD3
 * `surfaceContainer` token which sits just above the background and gives
 * a clean, high-contrast slab look.
 */
fun Modifier.mdSurface(
    color: Color,
    shape: Shape = RoundedCornerShape(20.dp)
): Modifier = this
    .clip(shape)
    .background(color)

@Composable
@ReadOnlyComposable
fun cardContainerColor(level: Int = 1): Color = when (level) {
    0 -> MaterialTheme.colorScheme.surfaceContainerLow
    1 -> MaterialTheme.colorScheme.surfaceContainer
    2 -> MaterialTheme.colorScheme.surfaceContainerHigh
    else -> MaterialTheme.colorScheme.surfaceContainerHighest
}

fun cornerRadius(): Dp = 22.dp
