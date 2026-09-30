package app.amber.feature.ui.components.ds

import android.graphics.Color as AndroidColor
import android.graphics.ColorSpace as AndroidColorSpace
import android.graphics.RadialGradient as AndroidRadialGradient
import android.graphics.Shader as AndroidShader
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalGraphicsContext
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
import app.amber.feature.ui.theme.LocalThemeDesign
import kotlinx.coroutines.flow.collect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AmberDepthStyle { Chip, Card, Accent }

val LocalAmberHdrPress = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }

private data class DepthValues(
    /** 内侧顶部高光（白）峰值。 */
    val rimTop: Float,
    /** 外轮廓发丝线（ink）不透明度：在纹理画布上也要能勾出清晰边缘。 */
    val edgeAlpha: Float,
    val contactAlpha: Float,
    val ambientAlpha: Float,
    val contactRadius: Float,
    val ambientRadius: Float,
    val contactY: Float,
    val ambientY: Float,
)

// iOS 式层次：外沿一条冷静的发丝线定义形状，内侧一条白色高光表示受光，
// 投影拆成「贴边接触影 + 大半径低浓度环境影」，而不是一团浓重的模糊。
private fun depthValues(style: AmberDepthStyle, dark: Boolean): DepthValues = when (style) {
    AmberDepthStyle.Chip -> if (dark) DepthValues(.10f, .12f, 0f, 0f, 0f, 0f, 0f, 0f)
        else DepthValues(.85f, .10f, 0f, 0f, 0f, 0f, 0f, 0f)
    AmberDepthStyle.Card -> if (dark) DepthValues(.07f, .10f, .28f, .22f, 1.5f, 18f, .5f, 6f)
        else DepthValues(.90f, .075f, .045f, .055f, 1.5f, 18f, .5f, 6f)
    AmberDepthStyle.Accent -> if (dark) DepthValues(.16f, .30f, .28f, .20f, 2f, 14f, 1f, 5f)
        else DepthValues(.26f, .16f, .10f, .16f, 2f, 14f, 1f, 5f)
}

/** 发丝线：0.5dp 取整到物理像素（4x 屏为 2px），不再用 1dp 的粗边。 */
private fun Density.rimStrokePx(): Float = max(1, (.5f * density).roundToInt()).toFloat()

/** HDR 按压峰值（线性亮度倍数），与窗口按压时请求的 headroom 一致。 */
const val AMBER_HDR_PRESS_HEADROOM = 4f

/**
 * 首页可见时的空闲 headroom。保持 1.0（不预热）：预热到 2.0 时系统会抬高背光并压暗
 * SDR 内容，离开首页撤掉 HDR 时两者恢复不同步，暗色模式下亮度会明显跳一下。
 * 代价是按下时 headroom 要从 1.0 爬升，高光起亮略慢。
 */
const val AMBER_HDR_IDLE_HEADROOM = 1f

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
    val themeBorderWidth = if (style == AmberDepthStyle.Card) {
        LocalThemeDesign.current?.components?.borderWidth?.toFloat()?.dp
    } else {
        null
    }
    return drawWithCache {
        val hairline = rimStrokePx()
        // 主题的 borderWidth 只能加粗边，不能把轮廓整条去掉：无边的浅色卡片在纹理底上会糊。
        val stroke = max(hairline, themeBorderWidth?.toPx() ?: 0f)
        val outline = shape.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this)
        // 高光沿轮廓内缩一条边的位置，和外沿发丝线分开，二者都清晰可辨。
        val innerInset = stroke + hairline / 2f
        val innerOutline = shape.createOutline(
            Size(size.width - innerInset * 2f, size.height - innerInset * 2f),
            layoutDirection,
            this,
        )
        val base = when {
            style == AmberDepthStyle.Accent && tokens.isDark -> Color.White.copy(alpha = values.edgeAlpha * .5f)
            style == AmberDepthStyle.Accent -> Color.Black.copy(alpha = values.edgeAlpha)
            else -> tokens.ink.copy(alpha = values.edgeAlpha)
        }
        val highlight = Brush.verticalGradient(
            0f to Color.White.copy(alpha = values.rimTop),
            .28f to Color.White.copy(alpha = values.rimTop * .25f),
            .5f to Color.Transparent,
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
            ) {
                drawContext.canvas.save()
                drawContext.canvas.clipRect(left, top, right, bottomEdge)
                if (topLight) {
                    drawContext.canvas.save()
                    drawContext.canvas.translate(innerInset, innerInset)
                    drawOutline(innerOutline, brush = highlight, style = Stroke(hairline))
                    drawContext.canvas.restore()
                }
                drawContext.canvas.translate(stroke / 2f, stroke / 2f)
                drawOutline(outline, color = base, style = Stroke(stroke))
                drawContext.canvas.restore()
            }
            if (drawTop && drawBottom) {
                paintRim(0f, 0f, size.width, size.height, topLight = true)
            } else {
                // 侧边贯穿整行；首尾行各自只画属于自己的圆角端。
                paintRim(0f, 0f, stroke, size.height, topLight = false)
                paintRim(size.width - stroke, 0f, size.width, size.height, topLight = false)
                if (drawTop) paintRim(stroke, 0f, size.width - stroke, size.height / 2f, topLight = true)
                if (drawBottom) paintRim(stroke, size.height / 2f, size.width - stroke, size.height, topLight = false)
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
    /**
     * The surface drawn over this shadow is fully opaque, so only the bands the surface
     * leaves uncovered are painted. A stack of list rows then no longer fills two
     * blurred row-sized layers per row on every scrolled frame.
     */
    opaqueContent: Boolean = false,
): Modifier {
    val tokens = LocalAmberTokens.current
    val values = depthValues(style, tokens.isDark)
    val themeComponents = if (style == AmberDepthStyle.Card) LocalThemeDesign.current?.components else null
    // 主题给的是单层阴影（opacity + radius）。直接照搬会得到一团浓重的模糊；
    // 这里按同一强度拆成更柔的环境影 + 贴边接触影，总观感轻而轮廓仍落地。
    val themeOpacity = themeComponents?.shadowOpacity?.toFloat()
    val themeRadius = themeComponents?.shadowRadius?.toFloat()
    val ambientRadius = themeRadius?.let { (it * 1.6f).dp } ?: values.ambientRadius.dp
    val ambientAlpha = themeOpacity?.let { it * .35f } ?: values.ambientAlpha
    val contactAlpha = themeOpacity?.let { it * .28f } ?: values.contactAlpha
    val ambientY = themeRadius?.let { (it * .5f).dp } ?: values.ambientY.dp
    val color = tint ?: if (style == AmberDepthStyle.Accent) tokens.accent else Color.Black
    val reach = ambientRadius * 2f + ambientY
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
    if (opaqueContent) {
        val shadowContext = LocalGraphicsContext.current.shadowContext
        val painters = remember(shadowContext, shadowShape, color, ambientRadius, ambientAlpha, ambientY, contactAlpha, values) {
            buildList {
                if (ambientAlpha > 0f) add(
                    shadowContext.createDropShadowPainter(
                        shadowShape,
                        Shadow(radius = ambientRadius, color = color.copy(alpha = ambientAlpha), offset = DpOffset(0.dp, ambientY)),
                    )
                )
                if (contactAlpha > 0f) add(
                    shadowContext.createDropShadowPainter(
                        shadowShape,
                        Shadow(
                            radius = values.contactRadius.dp,
                            color = color.copy(alpha = contactAlpha),
                            offset = DpOffset(0.dp, values.contactY.dp),
                        ),
                    )
                )
            }
        }
        return this.then(edgeClip).drawBehind {
            // Rect clips map to GPU scissors, so each pass only fills the visible band:
            // both sides of every row, plus the rounded ends of the first and last row.
            val outside = reach.toPx()
            val endInset = OpaqueEndInset.toPx().coerceAtMost(size.height / 2f)
            fun band(left: Float, top: Float, right: Float, bottom: Float) {
                clipRect(left, top, right, bottom) {
                    painters.forEach { painter -> with(painter) { draw(this@drawBehind.size) } }
                }
            }
            band(-outside, -outside, 0f, size.height + outside)
            band(size.width, -outside, size.width + outside, size.height + outside)
            if (drawTop) band(0f, -outside, size.width, endInset)
            if (drawBottom) band(0f, size.height - endInset, size.width, size.height + outside)
        }
    }
    var result = this.then(edgeClip)
    if (ambientAlpha > 0f) result = result.dropShadow(
        shadowShape,
        Shadow(
            radius = ambientRadius,
            color = color.copy(alpha = ambientAlpha),
            offset = DpOffset(0.dp, ambientY),
        ),
    )
    if (contactAlpha > 0f) result = result.dropShadow(
        shadowShape,
        Shadow(
            radius = values.contactRadius.dp,
            color = color.copy(alpha = contactAlpha),
            offset = DpOffset(0.dp, values.contactY.dp),
        ),
    )
    return result
}

/** Rounded ends of an opaque row keep their shadow under the corner curve. */
private val OpaqueEndInset = 24.dp

/** 按压放大倍数；调用方需确保父布局不裁切这 6% 的外扩。 */
const val AMBER_PRESS_SCALE = 1.06f

/** pressOffset 是大点击区到视觉控件左上角的距离。 */
@Composable
fun Modifier.amberPressHighlight(
    interactionSource: MutableInteractionSource,
    shape: Shape,
    pressOffset: DpOffset = DpOffset.Zero,
    scaleOnPress: Boolean = true,
    hdrHighlight: Boolean = false,
    glowTint: Color = Color.White,
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
    // 按下微微放大（iOS 玻璃按钮的手感），松手带一点回弹落回原位。
    val scale = if (scaleOnPress) animateFloatAsState(
        targetValue = if (press != null) AMBER_PRESS_SCALE else 1f,
        animationSpec = if (press != null) {
            spring(dampingRatio = .72f, stiffness = 900f)
        } else {
            spring(dampingRatio = .48f, stiffness = 420f)
        },
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
            val stroke = rimStrokePx()
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
                // 按最亮通道归一到 headroom：深色 accent 直接乘倍数到不了 1.0 以上，HDR 屏不会提亮。
                object : ShaderBrush() {
                    override fun createShader(size: Size): AndroidShader {
                        val linear = AndroidColorSpace.get(AndroidColorSpace.Named.LINEAR_EXTENDED_SRGB)
                        val tint = AndroidColor.valueOf(glowTint.red, glowTint.green, glowTint.blue, 1f)
                            .convert(linear)
                        val scale = AMBER_HDR_PRESS_HEADROOM /
                            max(tint.red(), max(tint.green(), tint.blue())).coerceAtLeast(.05f)
                        fun stop(alpha: Float) = AndroidColor.pack(
                            tint.red() * scale, tint.green() * scale, tint.blue() * scale, alpha, linear,
                        )
                        return AndroidRadialGradient(
                            center.x,
                            center.y,
                            radius,
                            longArrayOf(stop(.6f), stop(.22f), stop(0f)),
                            floatArrayOf(0f, .45f, 1f),
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
                    // Plus 在 Skia 中截断到 1.0，会吃掉扩展范围；HDR 路径必须用 SrcOver。
                    drawRect(
                        brush = brush,
                        alpha = glowAlpha,
                        blendMode = if (hdrPressChanged == null) BlendMode.Plus else BlendMode.SrcOver,
                    )
                    drawContext.canvas.translate(stroke / 2f, stroke / 2f)
                    drawOutline(rim, color = Color.White.copy(alpha = .15f * glowAlpha), style = Stroke(stroke))
                    drawContext.canvas.restore()
                }
            }
        }
}
