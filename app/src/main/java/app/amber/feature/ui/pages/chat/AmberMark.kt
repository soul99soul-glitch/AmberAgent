package app.amber.feature.ui.pages.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * AmberMark —— "Refined Chat Screens" 空白态中心宝石。
 *
 * 几何编织：三个 vesica（杏仁形）镜片以 0/120/240° 旋转叠加，每个镜片
 * 由两条镜像贝塞尔弧构成。中心夜光核 + 周围 ambient bloom，复刻设计稿
 * (/tmp/amber-design/phone-screen.jsx AmberMark)。
 *
 * 颜色梯度按 viewBox 64×64 推导：
 *   弧线 stroke linearGradient：端点 stops[2] @0.25 → 中段 stops[1] @1.0 → 端点 stops[2] @0.25
 *   中心 core radialGradient：白@0 → stops[0]@0.45 → 透明@1
 *   bloom 外圈使用 glow 颜色
 */
@Composable
fun AmberMark(
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    // stops[0] = bright tint, stops[1] = mid accent, stops[2] = deep
    stops: Triple<Color, Color, Color> = Triple(
        Color(0xFFDCEBFF), // bright sky tint
        Color(0xFF6FA0E8), // mid mid-blue
        Color(0xFF1E3A8A), // deep navy
    ),
    glow: Color = Color(0x666FA0E8), // ~40% alpha bloom
) {
    Canvas(
        modifier = modifier.size(size)
    ) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        // viewBox 0..64; scale factor:
        val s = w / 64f

        // ── 1. ambient bloom — radial halo extending well past gem bounds
        val bloomR = w * (1f + 0.55f * 2f) / 2f  // matches CSS inset:-size*0.55
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to glow,
                    0.72f to Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = bloomR,
            ),
            radius = bloomR,
            center = Offset(cx, cy),
        )

        // ── 2. luminous nucleus — soft white core into tint
        val coreR = 10f * s
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to Color.White.copy(alpha = 0.85f),
                    0.55f to stops.first.copy(alpha = 0.45f),
                    1.00f to Color.Transparent,
                ),
                center = Offset(cx, cy),
                radius = coreR,
            ),
            radius = coreR,
            center = Offset(cx, cy),
        )

        // ── 3. three vesica lenses
        // Arc geometry from design: (32,8) → (32,56), one bulges right, one left
        fun arcRight() = Path().apply {
            moveTo(cx, cy - 24f * s)                       // 32,8
            cubicTo(
                cx + 16f * s, cy - 14f * s,                // 48,18
                cx + 22f * s, cy,                          // 54,32
                cx, cy + 24f * s                           // 32,56
            )
        }
        fun arcLeft() = Path().apply {
            moveTo(cx, cy - 24f * s)
            cubicTo(
                cx - 16f * s, cy - 14f * s,                // 16,18
                cx - 22f * s, cy,                          // 10,32
                cx, cy + 24f * s
            )
        }

        // Linear gradient along the arc axis (top → bottom):
        // - top:    stops[2] @0.25
        // - middle: stops[1] @1.00
        // - bottom: stops[2] @0.25
        val strokeBrush = Brush.linearGradient(
            colorStops = arrayOf(
                0.00f to stops.third.copy(alpha = 0.25f),
                0.50f to stops.second,
                1.00f to stops.third.copy(alpha = 0.25f),
            ),
            start = Offset(cx, cy - 24f * s),
            end = Offset(cx, cy + 24f * s),
        )

        val strokeWidthPx = 2.6f * s
        val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)

        for (deg in listOf(0f, 120f, 240f)) {
            rotate(degrees = deg, pivot = Offset(cx, cy)) {
                drawPath(path = arcRight(), brush = strokeBrush, style = stroke)
                drawPath(path = arcLeft(), brush = strokeBrush, style = stroke)
            }
        }

        // ── 4. focal dot at exact center
        drawCircle(
            color = stops.first.copy(alpha = 0.95f),
            radius = 1.8f * s,
            center = Offset(cx, cy),
        )
    }
}
