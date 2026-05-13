package com.biji.notes.ui.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * State holder shared between the backdrop and the glass surfaces. The
 * [backdrop] layer captures the live pixels of the screen background each
 * frame; glass surfaces re-draw it with a [BlurEffect] applied and translated
 * by their own root offset, so the slice of background directly behind each
 * surface shows through – real backdrop blur, not a painted gradient.
 */
@Stable
class LiquidGlassState internal constructor(
    val backdrop: GraphicsLayer
)

val LocalLiquidGlass = compositionLocalOf<LiquidGlassState?> { null }

@Composable
fun LiquidGlassScaffold(
    background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val layer = rememberGraphicsLayer()
    val state = remember(layer) { LiquidGlassState(layer) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }
        ) { background() }

        CompositionLocalProvider(LocalLiquidGlass provides state) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

fun Modifier.liquidGlass(
    state: LiquidGlassState?,
    shape: Shape = RoundedCornerShape(24.dp),
    blurRadius: Dp = 32.dp,
    tint: Color = Color.White.copy(alpha = 0.22f),
    borderColor: Color = Color.White.copy(alpha = 0.55f)
): Modifier = this
    .clip(shape)
    .composed {
        if (state == null) return@composed Modifier
        val backdrop = state.backdrop
        var offset by remember { mutableStateOf(Offset.Zero) }
        var size by remember { mutableStateOf(Size.Zero) }

        Modifier
            .onGloballyPositioned { c ->
                offset = c.positionInRoot()
                size = Size(c.size.width.toFloat(), c.size.height.toFloat())
            }
            .drawWithCache {
                val px = blurRadius.toPx().coerceAtLeast(0.1f)
                onDrawBehind {
                    if (size.width <= 0f || size.height <= 0f) return@onDrawBehind

                    backdrop.renderEffect = BlurEffect(px, px, TileMode.Clamp)
                    translate(left = -offset.x, top = -offset.y) {
                        drawLayer(backdrop)
                    }
                    drawRect(tint)
                    drawRect(
                        brush = Brush.linearGradient(
                            0.0f to Color.White.copy(alpha = 0.35f),
                            0.45f to Color.White.copy(alpha = 0.06f),
                            1.0f to Color.Transparent,
                            start = Offset.Zero,
                            end = Offset(size.width * 0.9f, size.height * 0.9f)
                        )
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            0.0f to Color.White.copy(alpha = 0.18f),
                            1.0f to Color.Transparent,
                            center = Offset(size.width * 0.85f, size.height * 0.95f),
                            radius = maxOf(size.width, size.height) * 0.6f
                        )
                    )
                    drawRect(color = borderColor, style = Stroke(width = 1.5f))
                }
            }
    }

/**
 * Animated colorful canvas behind the glass. The runtime backdrop blur picks
 * these pixels up and stirs them, which is what gives the glass its life.
 */
@Composable
fun AnimatedAuroraBackground(
    modifier: Modifier = Modifier,
    colors: List<Color> = defaultAuroraColors()
) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val t by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "auroraT"
    )

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { clip = true }
            .drawWithCache {
                val w = size.width
                val h = size.height
                onDrawBehind {
                    drawRect(
                        Brush.linearGradient(
                            0f to colors[0],
                            1f to colors[1],
                            start = Offset.Zero,
                            end = Offset(w, h)
                        )
                    )
                    fun orb(cx: Float, cy: Float, r: Float, c: Color) {
                        drawRect(
                            Brush.radialGradient(
                                0f to c.copy(alpha = 0.85f),
                                1f to Color.Transparent,
                                center = Offset(cx, cy),
                                radius = r
                            )
                        )
                    }
                    orb(
                        cx = w * (0.15f + 0.25f * t),
                        cy = h * (0.20f + 0.15f * t),
                        r = w * 0.55f,
                        c = colors[2]
                    )
                    orb(
                        cx = w * (0.85f - 0.20f * t),
                        cy = h * (0.30f + 0.25f * (1f - t)),
                        r = w * 0.50f,
                        c = colors[3]
                    )
                    orb(
                        cx = w * (0.50f + 0.10f * (1f - t)),
                        cy = h * (0.80f - 0.10f * t),
                        r = w * 0.60f,
                        c = colors[4]
                    )
                }
            }
    )
}

@Composable
private fun defaultAuroraColors(): List<Color> {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return if (dark) listOf(
        Color(0xFF0B0B1F), Color(0xFF1A1336),
        Color(0xFF5B3FA8), Color(0xFFC04A8A), Color(0xFF2F7DCC)
    ) else listOf(
        Color(0xFFE7E1FF), Color(0xFFFFE2EE),
        Color(0xFFB7A8FF), Color(0xFFFFB1D2), Color(0xFFA9DFFF)
    )
}
