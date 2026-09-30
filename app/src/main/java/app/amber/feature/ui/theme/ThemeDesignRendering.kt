package app.amber.feature.ui.theme

import androidx.compose.material3.Shapes
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import app.amber.core.settings.ThemeDesign
import app.amber.core.settings.ThemePackDocument
import app.amber.core.settings.themeRgb
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Active portable theme recipe, provided only inside the app's Compose theme. */
val LocalThemeDesign = compositionLocalOf<ThemeDesign?> { null }

/** Live portable document; uses the try-on candidate until it is committed or restored. */
val LocalThemePack = compositionLocalOf<ThemePackDocument?> { null }

/** Top-level canvas preset from the portable document; null keeps the pre-document default. */
val LocalThemeCanvasStyle = compositionLocalOf<String?> { null }

internal fun themeShapes(design: ThemeDesign?): Shapes {
    val components = design?.components ?: return AmberShapes
    val control = components.controlRadius?.toFloat()?.dp
    val card = components.cardRadius?.toFloat()?.dp
    if (control == null && card == null) return AmberShapes
    return Shapes(
        extraSmall = RoundedCornerShape(control ?: 6.dp),
        small = RoundedCornerShape(control ?: 12.dp),
        medium = RoundedCornerShape(card ?: 14.dp),
        large = RoundedCornerShape(card ?: 18.dp),
        extraLarge = RoundedCornerShape(card ?: 22.dp),
    )
}

internal fun themeGradientBrush(
    gradient: ThemeDesign.Gradient?,
    isDark: Boolean,
    size: Size,
): Brush? {
    gradient ?: return null
    val rawColors = if (isDark) gradient.darkColors else gradient.colors
    val colors = rawColors.mapNotNull { raw -> themeRgb(raw)?.let(::opaqueColor) }
    if (colors.size != rawColors.size || colors.size < 2 || size.width <= 0f || size.height <= 0f) return null

    val radians = Math.toRadians(gradient.angle % 360.0)
    val dx = cos(radians).toFloat()
    val dy = sin(radians).toFloat()
    val center = Offset(size.width / 2f, size.height / 2f)
    // v1 angles use iOS UnitPoint coordinates: each axis scales by its own extent.
    val direction = Offset(dx * size.width / 2f, dy * size.height / 2f)
    return Brush.linearGradient(
        colors = colors,
        start = center - direction,
        end = center + direction,
    )
}

internal fun DrawScope.drawThemePattern(pattern: ThemeDesign.Pattern) {
    val color = themeRgb(pattern.color)?.let(::opaqueColor)?.copy(alpha = pattern.opacity.toFloat()) ?: return
    val spacing = pattern.spacing.toFloat().dp.toPx()
    val markSize = pattern.size.toFloat().dp.toPx()
    if (spacing <= 0f || markSize <= 0f) return
    // v1 size describes the mark, not the stroke or dot diameter.
    val stroke = max(0.5f.dp.toPx(), markSize * 0.32f)
    val canvasWidth = this.size.width
    val canvasHeight = this.size.height
    val columns = ceil(canvasWidth / spacing).toInt()
    val rows = ceil(canvasHeight / spacing).toInt()

    clipRect {
        when (pattern.kind) {
            "dots" -> {
                val points = buildList {
                    for (row in 0..rows) {
                        for (column in 0..columns) {
                            add(Offset(column * spacing, row * spacing))
                        }
                    }
                }
                drawPoints(points, PointMode.Points, color, strokeWidth = markSize * 2f, cap = StrokeCap.Round)
            }

            "grid" -> {
                val path = Path()
                for (column in 0..columns) {
                    val x = column * spacing
                    path.moveTo(x, 0f)
                    path.lineTo(x, canvasHeight)
                }
                for (row in 0..rows) {
                    val y = row * spacing
                    path.moveTo(0f, y)
                    path.lineTo(canvasWidth, y)
                }
                drawPath(path, color, style = Stroke(stroke))
            }

            "diagonal" -> {
                val path = Path()
                val count = ceil((canvasWidth + canvasHeight) / spacing).toInt() + 1
                for (index in 0..count) {
                    val x = -canvasHeight + index * spacing
                    path.moveTo(x, canvasHeight)
                    path.lineTo(x + canvasHeight, 0f)
                }
                drawPath(path, color, style = Stroke(stroke))
            }

            "crosses" -> {
                val path = Path()
                for (row in 0..rows) {
                    val y = row * spacing
                    for (column in 0..columns) {
                        val x = column * spacing
                        path.moveTo(x - markSize, y)
                        path.lineTo(x + markSize, y)
                        path.moveTo(x, y - markSize)
                        path.lineTo(x, y + markSize)
                    }
                }
                drawPath(path, color, style = Stroke(stroke))
            }

            "waves" -> {
                val amplitude = max(markSize * 1.5f, 0.75f.dp.toPx())
                val wavelength = spacing * 2f
                val sampleWidth = max(wavelength / 6f, 1.dp.toPx())
                val sampleCount = ceil(canvasWidth / sampleWidth).toInt().coerceIn(1, 512)
                for (row in 0..rows) {
                    val baseY = row * spacing
                    val path = Path()
                    path.moveTo(0f, baseY)
                    for (sample in 1..sampleCount) {
                        val x = sample.toFloat() / sampleCount * canvasWidth
                        val y = baseY + amplitude * sin(2f * PI.toFloat() * x / wavelength)
                        path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(stroke))
                }
            }

            "rings" -> {
                val radius = max(markSize * 2f, 0.5f.dp.toPx())
                for (row in 0..rows) {
                    for (column in 0..columns) {
                        drawCircle(color, radius, Offset(column * spacing, row * spacing), style = Stroke(stroke))
                    }
                }
            }
        }
    }
}

internal fun DrawScope.drawThemeCanvasStyle(style: String?, isDark: Boolean, opacityMultiplier: Float = 1f) {
    val ink = if (isDark) "#F4F1ED" else "#281F14"
    when (style) {
        "flat" -> Unit
        "lineGrid" -> {
            val lineOpacity = (if (isDark) 0.11 else 0.08) * opacityMultiplier
            val dotOpacity = (if (isDark) 0.14 else 0.10) * opacityMultiplier
            drawThemePattern(ThemeDesign.Pattern("grid", ink, lineOpacity, 18.0, 3.125))
            drawThemePattern(ThemeDesign.Pattern("dots", ink, dotOpacity, 18.0, 0.65))
        }
        "paperGrain" -> drawPaperGrain(ink, (if (isDark) 0.065 else 0.04) * opacityMultiplier)
        "dotGrid", null -> clipRect {
            translate(9.dp.toPx(), 9.dp.toPx()) {
                drawThemePattern(ThemeDesign.Pattern("dots", ink, (if (isDark) 0.08 else 0.055) * opacityMultiplier, 18.0, 0.7))
            }
        }
    }
}

private fun DrawScope.drawPaperGrain(ink: String, opacity: Double) {
    val rgb = themeRgb(ink) ?: return
    val color = opaqueColor(rgb).copy(alpha = opacity.toFloat())
    val cell = 4.dp.toPx()
    val canvasWidth = size.width
    val canvasHeight = size.height
    val points = buildList {
        var y = 0f
        var row = 0
        while (y < canvasHeight + cell) {
            var x = 0f
            var column = 0
            while (x < canvasWidth + cell) {
                val hash = column * 374_761 + row * 668_265
                if ((hash and 0x7) == 0) add(Offset(x + 0.5f.dp.toPx(), y + 0.5f.dp.toPx()))
                x += cell
                column++
            }
            y += cell
            row++
        }
    }
    drawPoints(points, PointMode.Points, color, strokeWidth = 1.dp.toPx(), cap = StrokeCap.Square)
}

internal fun opaqueColor(rgb: Int): Color = Color(0xFF000000L or rgb.toLong())
