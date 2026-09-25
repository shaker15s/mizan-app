package app.mizan.design.component

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mizan.design.theme.LocalMizanColors
import app.mizan.design.theme.LocalReducedMotion
import app.mizan.design.token.MizanColors
import kotlin.math.abs

/**
 * The backdrop the glass is allowed to refract.
 *
 * Real backdrop blur needs the pixels behind the pane, and Compose does not
 * expose them. The honest equivalent is to draw the same backdrop *inside* the
 * pane and blur that copy. It is exact for a gradient -- and every backdrop
 * MIZAN ships is a gradient -- and it costs one extra draw pass per pane, not
 * a screenshot.
 *
 * Provide it once, at the root, and every glass surface under it frosts
 * automatically. Leave it null and the panes fall back to a heavier tint that
 * still reads as glass.
 */
val LocalMizanBackdrop: CompositionLocal<(@Composable () -> Unit)?> = compositionLocalOf { null }

/** Names the backdrop once so the whole tree can refract it. */
@Composable
fun ProvideMizanBackdrop(backdrop: @Composable () -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalMizanBackdrop provides backdrop,
        content = content,
    )
}

/** How much glass a surface is allowed to be. */
enum class GlassTone {
    /** Navigation bars and docks: the most frost, the strongest edge. */
    DOCK,

    /** Cards and panels. The default. */
    REGULAR,

    /** Chips, pills and small controls: barely there, no blur cost. */
    THIN,

    /** Dialogs and sheets: enough frost to separate from a busy screen. */
    OVERLAY,
}

private data class GlassRecipe(
    val tintAlpha: Float,
    val blur: Dp,
    val sheen: Float,
    val edge: Float,
    val grain: Float,
)

private fun recipeFor(tone: GlassTone, colors: MizanColors): GlassRecipe = when (tone) {
    GlassTone.DOCK -> GlassRecipe(
        tintAlpha = if (colors.isDark) 0.72f else 0.78f,
        blur = 26.dp,
        sheen = if (colors.isDark) 0.16f else 0.55f,
        edge = if (colors.isDark) 0.30f else 0.55f,
        grain = if (colors.isDark) 0.045f else 0.020f,
    )
    GlassTone.REGULAR -> GlassRecipe(
        tintAlpha = if (colors.isDark) 0.62f else 0.70f,
        blur = 20.dp,
        sheen = if (colors.isDark) 0.12f else 0.42f,
        edge = if (colors.isDark) 0.22f else 0.42f,
        grain = if (colors.isDark) 0.035f else 0.016f,
    )
    GlassTone.THIN -> GlassRecipe(
        tintAlpha = if (colors.isDark) 0.48f else 0.56f,
        blur = 0.dp,
        sheen = if (colors.isDark) 0.10f else 0.34f,
        edge = if (colors.isDark) 0.18f else 0.34f,
        grain = 0f,
    )
    GlassTone.OVERLAY -> GlassRecipe(
        tintAlpha = if (colors.isDark) 0.80f else 0.86f,
        blur = 34.dp,
        sheen = if (colors.isDark) 0.18f else 0.60f,
        edge = if (colors.isDark) 0.34f else 0.62f,
        grain = if (colors.isDark) 0.05f else 0.022f,
    )
}

/** True when the device can blur a layer with a render effect (Android 12+). */
private val canBlurLayers: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Glass: a frosted pane that refracts whatever is behind it.
 *
 * Four things make it read as glass rather than as a grey rectangle:
 *
 *  - a blurred copy of the backdrop, so the pane has depth and not just alpha
 *  - a vertical tint, lighter at the top, so the pane catches light
 *  - a specular sheen that follows the finger when the pane is interactive
 *  - a hairline edge that is brighter on the top-left than on the bottom-right
 *
 * Below Android 12 there is no render effect, so the blur is dropped and the
 * tint is raised: the pane stays legible instead of becoming a flat wash.
 */
@Composable
fun MizanGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = ShapeCard,
    tone: GlassTone = GlassTone.REGULAR,
    edge: Boolean = true,
    interactive: Boolean = false,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalMizanColors.current
    val reduced = LocalReducedMotion.current
    val backdrop = LocalMizanBackdrop.current
    val recipe = recipeFor(tone, colors)

    // Where the light is coming from. It starts at the top-left and follows the
    // finger while the pane is touched, which is what makes the surface feel
    // liquid rather than laminated.
    var focusX by remember { mutableFloatStateOf(0.18f) }
    var focusY by remember { mutableFloatStateOf(0f) }
    var touched by remember { mutableFloatStateOf(0f) }
    val animatedX by animateFloatAsState(
        targetValue = focusX,
        animationSpec = spring(stiffness = 220f, dampingRatio = 0.72f),
        label = "glass-sheen-x",
    )
    val animatedY by animateFloatAsState(
        targetValue = focusY,
        animationSpec = spring(stiffness = 220f, dampingRatio = 0.72f),
        label = "glass-sheen-y",
    )
    val animatedTouch by animateFloatAsState(
        targetValue = touched,
        animationSpec = spring(stiffness = 320f, dampingRatio = 0.6f),
        label = "glass-sheen-touch",
    )

    val blurRadius = if (canBlurLayers && !reduced && recipe.blur > 0.dp && backdrop != null) {
        recipe.blur
    } else {
        0.dp
    }

    val pointerModifier = if (interactive && !reduced) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                do {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull()
                    when (event.type) {
                        PointerEventType.Press,
                        PointerEventType.Move,
                        -> {
                            if (change != null) {
                                val width = size.width.coerceAtLeast(1)
                                val height = size.height.coerceAtLeast(1)
                                focusX = (change.position.x / width).coerceIn(-0.4f, 1.4f)
                                focusY = (change.position.y / height).coerceIn(-0.4f, 1.4f)
                            }
                            touched = 1f
                        }
                        PointerEventType.Release -> {
                            focusX = 0.18f
                            focusY = 0f
                            touched = 0f
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .clip(shape)
            .then(pointerModifier),
        contentAlignment = contentAlignment,
    ) {
        // 1. The backdrop, blurred inside the pane.
        if (blurRadius > 0.dp && backdrop != null) {
            val radiusPx = with(LocalDensity.current) { blurRadius.toPx() }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayerBlur(radiusPx),
            ) {
                backdrop()
            }
        }

        // 2. The tint. Vertical, because light comes from above.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(colors.glassTintBrush(recipe.tintAlpha)),
        )

        // 3. Sheen, grain and the inner shadow that gives the pane thickness.
        Box(
            modifier = Modifier
                .matchParentSize()
                .mizanGlassSheen(
                    shape = shape,
                    colors = colors,
                    recipe = recipe,
                    focusX = animatedX,
                    focusY = animatedY,
                    touch = animatedTouch,
                ),
        )

        content()

        // 4. The edge, drawn last so nothing can cover it.
        if (edge) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(0.8.dp, colors.glassEdgeBrush(recipe.edge), shape),
            )
        }
    }
}

/**
 * Frosts a layer with a real render effect.
 *
 * `Offscreen` compositing is required: without it the effect is applied to a
 * clipped tile instead of to what the layer drew.
 */
private fun Modifier.graphicsLayerBlur(radiusPx: Float): Modifier = this.then(
    graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        renderEffect = RenderEffect.createBlurEffect(radiusPx, radiusPx, TileMode.Decal)
    },
)

private fun MizanColors.glassTintBrush(alpha: Float): Brush {
    val top = if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.72f)
    val bottom = if (isDark) Color.Black.copy(alpha = 0.16f) else Color(0xFFF2F6FA).copy(alpha = 0.86f)
    return Brush.verticalGradient(
        listOf(
            glass.copy(alpha = (glass.alpha * alpha + top.alpha * (1f - alpha)).coerceIn(0f, 1f)),
            glass.copy(alpha = alpha),
            surface.copy(alpha = (alpha * 0.92f).coerceIn(0f, 1f)).let { base ->
                // Keep a hint of the page tone at the bottom so the pane sits in
                // the screen instead of floating above it.
                if (isDark) base else base.copy(alpha = (base.alpha + bottom.alpha * 0.25f).coerceIn(0f, 1f))
            },
        ),
    )
}

/** Bright on the top-left, almost gone on the bottom-right. */
private fun MizanColors.glassEdgeBrush(strength: Float): Brush = Brush.linearGradient(
    listOf(
        Color.White.copy(alpha = strength),
        Color.White.copy(alpha = strength * 0.18f),
        (if (isDark) Color.White else Color.Black).copy(alpha = strength * 0.42f),
    ),
    start = Offset.Zero,
    end = Offset.Infinite,
)

/**
 * Specular sheen, grain and inner shadow.
 *
 * Drawn with a clip so the rounded corners stay crisp, and with a cache so the
 * brushes are built once per size instead of once per frame.
 */
private fun Modifier.mizanGlassSheen(
    shape: Shape,
    colors: MizanColors,
    recipe: GlassRecipe,
    focusX: Float,
    focusY: Float,
    touch: Float,
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val width = size.width
        val height = size.height
        val corner = (size.minDimension * 0.22f)
        val sheen = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = recipe.sheen),
                Color.White.copy(alpha = recipe.sheen * 0.16f),
                Color.Transparent,
            ),
            startY = 0f,
            endY = height * 0.62f,
        )
        val innerShadow = Brush.verticalGradient(
            listOf(
                Color.Transparent,
                Color.Black.copy(alpha = if (colors.isDark) 0.20f else 0.06f),
            ),
            startY = height * 0.72f,
            endY = height,
        )
        val grain = if (recipe.grain > 0f) grainPoints(width, height) else emptyList()
        onDrawBehind {
            drawRect(brush = sheen)
            drawRect(brush = innerShadow)

            // A soft highlight that leans toward the finger. This is the part
            // that makes the surface feel like a liquid rather than a sheet.
            val center = Offset(width * focusX, height * focusY)
            if (center.isSpecified) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = recipe.sheen * (0.55f + 0.45f * touch)),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = size.minDimension * (0.85f + 0.25f * touch),
                    ),
                )
            }

            // Light bends most where the glass is thickest: a bright top edge
            // and a dim bottom one.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = recipe.edge * 0.9f),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = height * 0.28f,
                ),
                cornerRadius = CornerRadius(corner, corner),
                style = Stroke(width = 1.1f),
            )

            if (grain.isNotEmpty()) {
                drawPoints(
                    points = grain,
                    pointMode = PointMode.Points,
                    color = Color.White.copy(alpha = recipe.grain),
                    strokeWidth = 1f,
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }

/**
 * A deterministic scatter of points so the frost is not mathematically flat.
 *
 * A tiny linear congruential generator keeps this allocation-free per frame
 * and identical from run to run, which matters for screenshot tests.
 */
private fun DrawScope.grainPoints(width: Float, height: Float): List<Offset> {
    val count = ((width * height) / 900f).toInt().coerceIn(0, 420)
    if (count == 0) return emptyList()
    val spanX = width.toInt().coerceAtLeast(1)
    val spanY = height.toInt().coerceAtLeast(1)
    val points = ArrayList<Offset>(count)
    var seed = 0x9E3779B9.toInt()
    repeat(count) {
        seed = seed * 1664525 + 1013904223
        val x = (seed ushr 8 and 0x7FFFFF) % spanX
        seed = seed * 1664525 + 1013904223
        val y = (seed ushr 8 and 0x7FFFFF) % spanY
        points.add(Offset(x.toFloat(), y.toFloat()))
    }
    return points
}

/**
 * The highlight that makes a solid surface look lit.
 *
 * Gradients on their own read as flat colour. A bright top edge and a sheen
 * that fades out by the middle is what the eye reads as a curved, glossy
 * object -- the same trick the glass panes use, without the blur.
 */
fun Modifier.mizanLiquidSheen(
    shape: Shape = ShapePill,
    strength: Float = 0.26f,
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val sheen = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = strength),
                Color.White.copy(alpha = strength * 0.22f),
                Color.Transparent,
            ),
            startY = 0f,
            endY = size.height * 0.72f,
        )
        val bottom = Brush.verticalGradient(
            listOf(
                Color.Transparent,
                Color.Black.copy(alpha = strength * 0.30f),
            ),
            startY = size.height * 0.55f,
            endY = size.height,
        )
        onDrawBehind {
            drawRect(brush = sheen)
            drawRect(brush = bottom)
        }
    }

/** The bottom navigation: a floating pane of glass. */
@Composable
fun MizanGlassDock(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    MizanGlassSurface(
        modifier = modifier,
        shape = ShapeFloating,
        tone = GlassTone.DOCK,
        interactive = true,
        content = content,
    )
}

/** The top bar: glass that dissolves into the page as it scrolls. */
@Composable
fun MizanGlassTopBar(
    modifier: Modifier = Modifier,
    scrolled: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    MizanGlassSurface(
        modifier = modifier,
        shape = if (scrolled) ShapeContainer else androidx.compose.ui.graphics.RectangleShape,
        tone = if (scrolled) GlassTone.DOCK else GlassTone.THIN,
        edge = scrolled,
        content = content,
    )
}

/** Chips and pills. No blur: there is nothing behind them worth frosting. */
@Composable
fun MizanGlassChip(
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalMizanColors.current
    MizanGlassSurface(
        modifier = modifier,
        shape = ShapePill,
        tone = GlassTone.THIN,
        edge = true,
        interactive = enabled,
        content = {
            if (selected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(colors.accent.copy(alpha = if (colors.isDark) 0.30f else 0.20f)),
                )
            }
            content()
        },
    )
}

/** Dialogs and sheets sit above the page, so they frost the most. */
@Composable
fun MizanGlassOverlay(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    MizanGlassSurface(
        modifier = modifier,
        shape = ShapeFloating,
        tone = GlassTone.OVERLAY,
        interactive = true,
        content = content,
    )
}

/** The wash every glass pane refracts. Provided once, at the root. */
@Composable
fun MizanBackdrop(modifier: Modifier = Modifier) {
    val colors = LocalMizanColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backdropBrush()),
    )
}

/**
 * Two soft lights behind the page, so the glass has something to bend.
 *
 * They are radial gradients, not blur: a blurred layer would cost a render
 * effect on every frame, and a gradient is free.
 */
@Composable
fun MizanBackdropLights(modifier: Modifier = Modifier) {
    val colors = LocalMizanColors.current
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val top = Brush.radialGradient(
                        listOf(colors.accentSecondary.copy(alpha = if (colors.isDark) 0.20f else 0.30f), Color.Transparent),
                        center = Offset(size.width * 0.16f, size.height * 0.06f),
                        radius = size.maxDimension * 0.62f,
                    )
                    val bottom = Brush.radialGradient(
                        listOf(colors.accentTertiary.copy(alpha = if (colors.isDark) 0.16f else 0.24f), Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.88f),
                        radius = size.maxDimension * 0.58f,
                    )
                    onDrawBehind {
                        drawRect(top)
                        drawRect(bottom)
                    }
                },
        )
    }
}

/** Small helper so callers can ask "is this pane frosted or tinted?". */
@Stable
val isGlassBlurAvailable: Boolean
    get() = canBlurLayers
