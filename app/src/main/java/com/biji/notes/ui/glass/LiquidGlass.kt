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
import androidx.compose.ui.geometry.CornerRadius
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

/**
 * Real liquid glass: the shared backdrop graphics layer is re-drawn with a
 * runtime [BlurEffect] applied, translated by the surface's onscreen offset
 * so the slice of background directly behind this surface shows through.
 * On top of the live blur we paint:
 *  - a faint tint wash (kept very transparent so the picture stays visible),
 *  - a top-down sheen for the curved-glass highlight,
 *  - a soft radial kicker on one corner,
 *  - a brush-stroked rim border that brightens at the top edge, mimicking
 *    the chromatic refraction of light bending around the bezel.
 */
fun Modifier.liquidGlass(
    state: LiquidGlassState?,
    shape: Shape = RoundedCornerShape(28.dp),
    blurRadius: Dp = 40.dp,
    tint: Color = Color.White.copy(alpha = 0.10f),
    cornerRadius: Dp = 28.dp,
    rim: Boolean = true
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
                val r = cornerRadius.toPx()
                onDrawBehind {
                    if (size.width <= 0f || size.height <= 0f) return@onDrawBehind

                    backdrop.renderEffect = BlurEffect(px, px, TileMode.Clamp)
                    translate(left = -offset.x, top = -offset.y) {
                        drawLayer(backdrop)
                    }

                    // Faint frosted tint.
                    drawRect(tint)

                    // Top-down vertical sheen: bright sliver at the top edge,
                    // mid-fade through the body, faint glow at the bottom.
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.00f to Color.White.copy(alpha = 0.32f),
                            0.18f to Color.White.copy(alpha = 0.06f),
                            0.55f to Color.Transparent,
                            0.92f to Color.White.copy(alpha = 0.05f),
                            1.00f to Color.White.copy(alpha = 0.14f)
                        )
                    )

                    // Diagonal specular kicker.
                    drawRect(
                        brush = Brush.linearGradient(
                            0.0f to Color.White.copy(alpha = 0.18f),
                            0.5f to Color.Transparent,
                            1.0f to Color.Transparent,
                            start = Offset.Zero,
                            end = Offset(size.width * 0.55f, size.height * 0.55f)
                        )
                    )

                    if (rim) {
                        // Chromatic-feel rim: gradient stroke that runs from
                        // very bright at the top through translucent at the
                        // sides and a soft glow at the bottom – the look of
                        // light refracting around the bezel.
                        val rimBrush = Brush.linearGradient(
                            0.00f to Color.White.copy(alpha = 0.90f),
                            0.35f to Color.White.copy(alpha = 0.35f),
                            0.65f to Color.White.copy(alpha = 0.20f),
                            1.00f to Color.White.copy(alpha = 0.55f),
                            start = Offset(size.width * 0.5f, 0f),
                            end = Offset(size.width * 0.5f, size.height)
                        )
                        drawRoundRect(
                            brush = rimBrush,
                            cornerRadius = CornerRadius(r, r),
                            style = Stroke(width = 1.5f)
                        )
                        // Inner inset rim, slightly darker, gives that
                        // "double-pane" depth.
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                0f to Color.White.copy(alpha = 0.22f),
                                1f to Color.Transparent,
                                start = Offset.Zero,
                                end = Offset(0f, size.height * 0.35f)
                            ),
                            topLeft = Offset(2f, 2f),
                            size = Size(size.width - 4f, size.height - 4f),
                            cornerRadius = CornerRadius(r - 2f, r - 2f),
                            style = Stroke(width = 0.8f)
                        )
                    }
                }
            }
    }

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
        Color(0xFF0A0820), Color(0xFF1A1240),
        Color(0xFF6B3FCC), Color(0xFFE05599), Color(0xFF2F8EE0)
    ) else listOf(
        Color(0xFFDEE5FF), Color(0xFFFFE0EE),
        Color(0xFF9F8CFF), Color(0xFFFFA1C9), Color(0xFF8AD2FF)
    )
}
