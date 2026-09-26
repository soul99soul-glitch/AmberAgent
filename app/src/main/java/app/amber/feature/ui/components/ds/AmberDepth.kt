package app.amber.feature.ui.components.ds

import android.graphics.Color as AndroidColor
import android.graphics.ColorSpace as AndroidColorSpace
import android.graphics.RadialGradient as AndroidRadialGradient
import android.graphics.Shader as AndroidShader
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.theme.LocalAmberTokens
import kotlinx.coroutines.flow.collect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AmberDepthStyle { Chip, Card, Accent }

val LocalAmberHdrPress = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }

private data class DepthValues(
    val rimTop: Float,
    val rimBottom: Float,
    val contactAlpha: Float,
    val ambientAlpha: Float,
    val contactRadius: Int,
    val ambientRadius: Int,
    val contactY: Int,
    val ambientY: Int,
)

private fun depthValues(style: AmberDepthStyle, dark: Boolean): DepthValues = when (style) {
    AmberDepthStyle.Chip -> if (dark) DepthValues(.12f, .08f, .25f, .16f, 2, 7, 1, 3)
        else DepthValues(.60f, .07f, .12f, .09f, 2, 7, 1, 3)
    AmberDepthStyle.Card -> if (dark) DepthValues(.14f, .09f, .30f, .20f, 3, 11, 2, 5)
        else DepthValues(.62f, .07f, .11f, .09f, 3, 11, 2, 5)
    AmberDepthStyle.Accent -> if (dark) DepthValues(.20f, .10f, .35f, .24f, 4, 16, 2, 6)
        else DepthValues(.50f, .08f, .23f, .17f, 4, 16, 2, 6)
}

/** 上下端可独立关闭；分行卡片仍沿同一轮廓绘制两侧。 */
@Composable
fun Modifier.amberRim(
    shape: Shape,
    style: AmberDepthStyle,
    drawTop: Boolean = true,
    drawBottom: Boolean = true,
    surfaceSheen: Boolean = false,
): Modifier {
    val tokens = LocalAmberTokens.current
    val values = depthValues(style, tokens.isDark)
    return drawWithCache {
        // 描边中心落在半像素；宽度取整像素，避免 0.75dp 落在两个像素之间。
        val stroke = max(1, 1.dp.toPx().roundToInt()).toFloat()
        val insetSize = Size(size.width - stroke, size.height - stroke)
        val outline = shape.createOutline(insetSize, layoutDirection, this)
        val base = when {
            style == AmberDepthStyle.Accent -> tokens.accent
            style == AmberDepthStyle.Card && !tokens.isDark -> tokens.line.copy(alpha = .72f)
            else -> tokens.line
        }
        val highlight = Brush.verticalGradient(
            0f to Color.White.copy(alpha = values.rimTop),
            .62f to Color.White.copy(alpha = if (tokens.isDark) .02f else 0f),
            1f to Color.Transparent,
            startY = 0f,
            endY = size.height,
        )
        val bottom = Brush.verticalGradient(
            0f to Color.Transparent,
            .5f to Color.Transparent,
            1f to Color.Black.copy(alpha = values.rimBottom),
            startY = 0f,
            endY = size.height,
        )
        onDrawWithContent {
            drawContent()
            if (surfaceSheen) {
                drawContext.canvas.save()
                drawContext.canvas.translate(stroke / 2f, stroke / 2f)
                drawOutline(
                    outline,
                    brush = Brush.verticalGradient(colors = listOf(
                        Color.White.copy(alpha = if (tokens.isDark) .02f else .015f),
                        Color.Black.copy(alpha = .015f),
                    )),
                )
                drawContext.canvas.restore()
            }
            fun paintRim(
                left: Float,
                top: Float,
                right: Float,
                bottomEdge: Float,
                topLight: Boolean,
                bottomDark: Boolean,
            ) {
                drawContext.canvas.save()
                drawContext.canvas.clipRect(left, top, right, bottomEdge)
                drawContext.canvas.translate(stroke / 2f, stroke / 2f)
                drawOutline(outline, color = base, style = Stroke(stroke))
                if (topLight) drawOutline(outline, brush = highlight, style = Stroke(stroke))
                if (bottomDark) drawOutline(outline, brush = bottom, style = Stroke(stroke))
                drawContext.canvas.restore()
            }
            if (drawTop && drawBottom) {
                paintRim(0f, 0f, size.width, size.height, topLight = true, bottomDark = true)
            } else {
                // 侧边贯穿整行；首尾行各自只画属于自己的圆角端。
                paintRim(0f, 0f, stroke, size.height, topLight = false, bottomDark = false)
                paintRim(size.width - stroke, 0f, size.width, size.height, topLight = false, bottomDark = false)
                if (drawTop) paintRim(stroke, 0f, size.width - stroke, size.height / 2f, true, false)
                if (drawBottom) paintRim(stroke, size.height / 2f, size.width - stroke, size.height, false, true)
            }
        }
    }
}

/** 拼接行关闭内部端帽时，阴影轮廓向行外延伸，裁切后侧影仍连续。 */
@Composable
fun Modifier.amberShadow(
    shape: Shape,
    style: AmberDepthStyle,
    tint: Color? = null,
    drawTop: Boolean = true,
    drawBottom: Boolean = true,
): Modifier {
    val tokens = LocalAmberTokens.current
    val values = depthValues(style, tokens.isDark)
    val color = tint ?: if (style == AmberDepthStyle.Accent) tokens.accent else Color.Black
    val reach = (values.ambientRadius * 2 + values.ambientY).dp
    val shadowShape = remember(shape, drawTop, drawBottom, reach) {
        if (drawTop && drawBottom) shape else object : Shape {
            override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                val top = with(density) { if (drawTop) 0f else reach.toPx() }
                val bottom = with(density) { if (drawBottom) 0f else reach.toPx() }
                val extended = shape.createOutline(Size(size.width, size.height + top + bottom), layoutDirection, density)
                val path = Path().apply {
                    addOutline(extended)
                    translate(Offset(0f, -top))
                }
                return Outline.Generic(path)
            }
        }
    }
    val edgeClip = if (drawTop && drawBottom) Modifier else Modifier.drawWithCache {
        val outside = reach.toPx()
        onDrawWithContent {
            drawContext.canvas.save()
            drawContext.canvas.clipRect(
                -outside,
                if (drawTop) -outside else 0f,
                size.width + outside,
                if (drawBottom) size.height + outside else size.height,
            )
            drawContent()
            drawContext.canvas.restore()
        }
    }
    return this.then(edgeClip)
        .dropShadow(
            shadowShape,
            Shadow(
                radius = values.ambientRadius.dp,
                color = color.copy(alpha = values.ambientAlpha),
                offset = DpOffset(0.dp, values.ambientY.dp),
            ),
        )
        .dropShadow(
            shadowShape,
            Shadow(
                radius = values.contactRadius.dp,
                color = color.copy(alpha = values.contactAlpha),
                offset = DpOffset(0.dp, values.contactY.dp),
            ),
        )
}

/** pressOffset 是大点击区到视觉控件左上角的距离。 */
@Composable
fun Modifier.amberPressHighlight(
    interactionSource: MutableInteractionSource,
    shape: Shape,
    pressOffset: DpOffset = DpOffset.Zero,
    scaleOnPress: Boolean = true,
    hdrHighlight: Boolean = false,
): Modifier {
    val hdrPressChanged = if (hdrHighlight) LocalAmberHdrPress.current else null
    var press by remember(interactionSource) { mutableStateOf<PressInteraction.Press?>(null) }
    var position by remember(interactionSource) { mutableStateOf(Offset.Unspecified) }
    LaunchedEffect(interactionSource, hdrPressChanged) {
        var hdrPressed = false
        try {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        if (press == null && hdrPressChanged != null) {
                            hdrPressChanged(true)
                            hdrPressed = true
                        }
                        press = interaction
                        position = interaction.pressPosition
                    }
                    is PressInteraction.Release -> if (interaction.press == press) {
                        press = null
                        if (hdrPressed) hdrPressChanged?.invoke(false)
                        hdrPressed = false
                    }
                    is PressInteraction.Cancel -> if (interaction.press == press) {
                        press = null
                        if (hdrPressed) hdrPressChanged?.invoke(false)
                        hdrPressed = false
                    }
                }
            }
        } finally {
            if (hdrPressed) hdrPressChanged?.invoke(false)
            press = null
        }
    }
    val glow = animateFloatAsState(
        targetValue = if (press != null) 1f else 0f,
        animationSpec = tween(if (press != null) 90 else 220),
        label = "amber-press-glow",
    )
    val scale = if (scaleOnPress) animateFloatAsState(
        targetValue = if (press != null) .97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "amber-press-scale",
    ) else null
    return this
        .graphicsLayer {
            scaleX = scale?.value ?: 1f
            scaleY = scale?.value ?: 1f
        }
        .drawWithCache {
            val center = if (position.isSpecified) {
                position - Offset(pressOffset.x.toPx(), pressOffset.y.toPx())
            } else {
                Offset(size.width / 2f, size.height / 2f)
            }
            val radius = max(64.dp.toPx(), min(size.width, size.height) * 1.2f)
            val stroke = max(1, 1.dp.toPx().roundToInt()).toFloat()
            val rim = shape.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this)
            val glowClip = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val brush = if (hdrPressChanged == null) {
                Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = .19f), Color.Transparent),
                    center = center,
                    radius = radius,
                )
            } else {
                // Compose 的径向渐变会转成 int[]；ColorLong 才能保留 >1 的扩展范围分量。
                object : ShaderBrush() {
                    override fun createShader(size: Size): AndroidShader {
                        val colorSpace = AndroidColorSpace.get(AndroidColorSpace.Named.EXTENDED_SRGB)
                        return AndroidRadialGradient(
                            center.x,
                            center.y,
                            radius,
                            longArrayOf(
                                AndroidColor.pack(1.4f, 1.4f, 1.4f, .19f, colorSpace),
                                AndroidColor.pack(0f, 0f, 0f, 0f, colorSpace),
                            ),
                            null,
                            AndroidShader.TileMode.CLAMP,
                        )
                    }
                }
            }
            onDrawWithContent {
                drawContent()
                val glowAlpha = glow.value
                if (glowAlpha > 0f) {
                    drawContext.canvas.save()
                    drawContext.canvas.clipPath(glowClip)
                    drawRect(brush = brush, alpha = glowAlpha, blendMode = BlendMode.Plus)
                    drawContext.canvas.translate(stroke / 2f, stroke / 2f)
                    drawOutline(rim, color = Color.White.copy(alpha = .15f * glowAlpha), style = Stroke(stroke))
                    drawContext.canvas.restore()
                }
            }
        }
}
