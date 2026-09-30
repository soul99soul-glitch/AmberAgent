package app.amber.feature.ui.components.ds

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.max
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmoledDarkMode
import app.amber.feature.ui.theme.LocalThemeCanvasStyle
import app.amber.feature.ui.theme.LocalThemeDesign
import app.amber.feature.ui.theme.LocalThemePack
import app.amber.feature.ui.theme.LocalThemePageChrome
import app.amber.feature.ui.theme.allowsThemeCanvasOverlay
import app.amber.feature.ui.theme.drawThemeCanvasStyle
import app.amber.feature.ui.theme.drawThemePattern
import app.amber.feature.ui.theme.themeGradientBrush
import app.amber.core.settings.ThemeDesign

/**
 * iOS 点阵 · 陶土 lattice; opaque cards and reader paper paint over the texture.
 *
 * Theme patterns can be hundreds of strokes and circles. They never move, so they are
 * rasterised once per window size and theme into a shared bitmap; each frame only
 * blits that texture. Scrolling content (and Haze sources that re-record the page every
 * frame) no longer replays the whole pattern on the RenderThread and GPU.
 */
@Composable
fun Modifier.amberCanvas(): Modifier {
    val tokens = LocalAmberTokens.current
    val amoled = LocalAmoledDarkMode.current
    val design = LocalThemeDesign.current
    val canvasStyle = LocalThemeCanvasStyle.current
    val themePack = LocalThemePack.current
    val drawThemeOverlay = allowsThemeCanvasOverlay(themePack, LocalThemePageChrome.current)
    val windowSize = LocalWindowInfo.current.containerSize
    return background(tokens.bg).drawWithCache {
        val canvasSize = size
        val legacyCanvas = design == null && canvasStyle == null
        val hasTexture = !amoled && drawThemeOverlay &&
            (legacyCanvas || !design?.patterns.isNullOrEmpty() || canvasStyle != null)
        // Sized to the window, not the node, so an IME resize or a short page reuses it.
        val textureWidth = max(ceil(canvasSize.width).toInt(), windowSize.width)
        val textureHeight = max(ceil(canvasSize.height).toInt(), windowSize.height)
        val texture = if (hasTexture && textureWidth > 0 && textureHeight > 0) {
            AmberCanvasTextures.get(
                AmberCanvasTextureKey(
                    width = textureWidth,
                    height = textureHeight,
                    density = density,
                    isDark = tokens.isDark,
                    legacyCanvas = legacyCanvas,
                    patterns = design?.patterns,
                    canvasStyle = canvasStyle,
                ),
                layoutDirection = layoutDirection,
            )
        } else {
            null
        }
        val gradientBrush = if (amoled || !drawThemeOverlay) null
        else themeGradientBrush(design?.gradient, tokens.isDark, canvasSize)
        onDrawBehind {
            if (!amoled && drawThemeOverlay) {
                gradientBrush?.let { drawRect(it) }
                texture?.let { clipRect { drawImage(it) } }
            }
        }
    }
}

private data class AmberCanvasTextureKey(
    val width: Int,
    val height: Int,
    val density: Float,
    val isDark: Boolean,
    val legacyCanvas: Boolean,
    val patterns: List<ThemeDesign.Pattern>?,
    val canvasStyle: String?,
)

/** Shared across screens: one theme needs one texture, the previous theme is kept for back navigation. */
private object AmberCanvasTextures {
    private const val MAX_ENTRIES = 2
    private val entries = LinkedHashMap<AmberCanvasTextureKey, ImageBitmap>(MAX_ENTRIES, 0.75f, true)

    @Synchronized
    fun get(key: AmberCanvasTextureKey, layoutDirection: LayoutDirection): ImageBitmap {
        entries[key]?.let { return it }
        val bitmap = render(key, layoutDirection)
        entries[key] = bitmap
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
        return bitmap
    }

    private fun render(key: AmberCanvasTextureKey, layoutDirection: LayoutDirection): ImageBitmap {
        val bitmap = ImageBitmap(key.width, key.height)
        val size = Size(key.width.toFloat(), key.height.toFloat())
        CanvasDrawScope().draw(Density(key.density), layoutDirection, Canvas(bitmap), size) {
            if (key.legacyCanvas) {
                drawLegacyDots(key.isDark)
            } else {
                key.patterns?.forEach { drawThemePattern(it) }
                key.canvasStyle?.let { drawThemeCanvasStyle(it, key.isDark) }
            }
        }
        return bitmap
    }

    private fun DrawScope.drawLegacyDots(isDark: Boolean) {
        val spacing = 18.dp.toPx()
        val area = size
        val points = buildList {
            var y = spacing / 2f
            while (y < area.height) {
                var x = spacing / 2f
                while (x < area.width) {
                    add(Offset(x, y))
                    x += spacing
                }
                y += spacing
            }
        }
        val dotColor = if (isDark) {
            Color(0xFFF4F1ED).copy(alpha = 0.08f)
        } else {
            Color(0xFF281F14).copy(alpha = 0.055f)
        }
        drawPoints(
            points = points,
            pointMode = PointMode.Points,
            color = dotColor,
            strokeWidth = 1.4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
