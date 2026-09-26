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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import app.amber.core.settings.ThemeDesign
import app.amber.core.settings.themeRgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Active portable theme recipe, provided only inside the app's Compose theme. */
val LocalThemeDesign = compositionLocalOf<ThemeDesign?> { null }

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

    val radians = Math.toRadians(gradient.angle)
    val dx = cos(radians).toFloat()
    val dy = sin(radians).toFloat()
    val halfLength = abs(dx) * size.width / 2f + abs(dy) * size.height / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val direction = Offset(dx * halfLength, dy * halfLength)
    return Brush.linearGradient(
        colors = colors,
        start = center - direction,
        end = center + direction,
    )
}

internal fun DrawScope.drawThemePattern(pattern: ThemeDesign.Pattern) {
    val color = themeRgb(pattern.color)?.let(::opaqueColor)?.copy(alpha = pattern.opacity.toFloat()) ?: return
    val spacing = pattern.spacing.toFloat().dp.toPx()
    val size = pattern.size.toFloat().dp.toPx()
    if (spacing <= 0f || size <= 0f) return
    val stroke = size.coerceAtLeast(0.5f)
    val canvasWidth = this.size.width
    val canvasHeight = this.size.height

    when (pattern.kind) {
        "dots" -> {
            val points = buildList {
                var y = spacing / 2f
                while (y < canvasHeight) {
                    var x = spacing / 2f
                    while (x < canvasWidth) {
                        add(Offset(x, y))
                        x += spacing
                    }
                    y += spacing
                }
            }
            drawPoints(points, PointMode.Points, color, strokeWidth = size, cap = StrokeCap.Round)
        }

        "grid" -> {
            var x = 0f
            while (x <= canvasWidth) {
                drawLine(color, Offset(x, 0f), Offset(x, canvasHeight), strokeWidth = stroke)
                x += spacing
            }
            var y = 0f
            while (y <= canvasHeight) {
                drawLine(color, Offset(0f, y), Offset(canvasWidth, y), strokeWidth = stroke)
                y += spacing
            }
        }

        "diagonal" -> {
            var x = -canvasHeight
            while (x < canvasWidth) {
                drawLine(color, Offset(x, 0f), Offset(x + canvasHeight, canvasHeight), strokeWidth = stroke)
                x += spacing
            }
        }

        "crosses" -> {
            val arm = size
            var y = spacing / 2f
            while (y < canvasHeight) {
                var x = spacing / 2f
                while (x < canvasWidth) {
                    drawLine(color, Offset(x - arm, y), Offset(x + arm, y), strokeWidth = stroke)
                    drawLine(color, Offset(x, y - arm), Offset(x, y + arm), strokeWidth = stroke)
                    x += spacing
                }
                y += spacing
            }
        }

        "waves" -> {
            val amplitude = size
            val wavelength = spacing * 2f
            val step = (spacing / 8f).coerceAtLeast(1f)
            val waveStroke = stroke.coerceAtMost(1.dp.toPx())
            var baseY = spacing / 2f
            while (baseY < canvasHeight) {
                val path = Path()
                var x = 0f
                path.moveTo(0f, baseY)
                while (x < canvasWidth) {
                    val y = baseY + amplitude * sin(2f * PI.toFloat() * x / wavelength)
                    path.lineTo(x, y)
                    x += step
                }
                drawPath(path, color, style = Stroke(width = waveStroke, cap = StrokeCap.Round))
                baseY += spacing
            }
        }

        "rings" -> {
            val radius = size
            val ringStroke = max(0.5f, stroke / 2f)
            var y = spacing / 2f
            while (y < canvasHeight) {
                var x = spacing / 2f
                while (x < canvasWidth) {
                    drawCircle(color, radius, Offset(x, y), style = Stroke(width = ringStroke))
                    x += spacing
                }
                y += spacing
            }
        }
    }
}

internal fun DrawScope.drawThemeCanvasStyle(style: String?, isDark: Boolean) {
    val ink = if (isDark) "#F4F1ED" else "#281F14"
    when (style) {
        "flat" -> Unit
        "lineGrid" -> {
            val lineOpacity = if (isDark) 0.11 else 0.08
            val dotOpacity = if (isDark) 0.14 else 0.10
            drawThemePattern(ThemeDesign.Pattern("grid", ink, lineOpacity, 18.0, 1.0))
            drawThemePattern(ThemeDesign.Pattern("dots", ink, dotOpacity, 18.0, 1.3))
        }
        "paperGrain" -> drawPaperGrain(ink, if (isDark) 0.065 else 0.04)
        "dotGrid", null -> drawThemePattern(
            ThemeDesign.Pattern("dots", ink, if (isDark) 0.08 else 0.055, 18.0, 1.4),
        )
    }
}

private fun DrawScope.drawPaperGrain(ink: String, opacity: Double) {
    val rgb = themeRgb(ink) ?: return
    val color = opaqueColor(rgb).copy(alpha = opacity.toFloat())
    val cell = 4.dp.toPx()
    val canvasWidth = size.width
    val canvasHeight = size.height
    val points = buildList {
        var y = cell / 2f
        var row = 0
        while (y < canvasHeight + cell) {
            var x = cell / 2f
            var column = 0
            while (x < canvasWidth + cell) {
                val hash = column * 374_761 + row * 668_265
                if ((hash and 0x7) == 0) add(Offset(x, y))
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
