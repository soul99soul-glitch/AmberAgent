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
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmoledDarkMode

/** iOS 点阵 · 陶土 lattice; opaque cards and reader paper paint over the texture. */
@Composable
fun Modifier.amberCanvas(): Modifier {
    val tokens = LocalAmberTokens.current
    val amoled = LocalAmoledDarkMode.current
    return background(tokens.bg).drawWithCache {
        val spacing = 18.dp.toPx()
        val canvasSize = size
        val points = buildList {
            if (!amoled) {
                var y = spacing / 2f
                while (y < canvasSize.height) {
                    var x = spacing / 2f
                    while (x < canvasSize.width) {
                        add(Offset(x, y))
                        x += spacing
                    }
                    y += spacing
                }
            }
        }
        val dotColor = if (tokens.isDark) {
            Color(0xFFF4F1ED).copy(alpha = 0.08f)
        } else {
            Color(0xFF281F14).copy(alpha = 0.055f)
        }
        val diameter = 1.4.dp.toPx()
        onDrawBehind {
            drawPoints(
                points = points,
                pointMode = PointMode.Points,
                color = dotColor,
                strokeWidth = diameter,
                cap = StrokeCap.Round,
            )
        }
    }
}
