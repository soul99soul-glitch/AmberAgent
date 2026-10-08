package app.amber.feature.ui.pages.novel

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.feature.ui.theme.LocalAmberTokens
import kotlin.math.cos
import kotlin.math.sin

/** Honors the system "remove animations" switch; decorative motion gates on this. */
fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

/**
 * The app icon's seal: a vermilion block with 「文」 in white, like the iOS
 * `NovelSealMark` (which cuts the glyph out of the block — same silhouette here).
 */
@Composable
fun NovelSealMark(size: Dp, modifier: Modifier = Modifier) {
    val accent = LocalAmberTokens.current.accent
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                shadowElevation = 10.dp.toPx()
                shape = RoundedCornerShape(size * 0.085f)
                clip = true
                ambientShadowColor = accent.copy(alpha = 0.3f)
                spotShadowColor = accent.copy(alpha = 0.3f)
            }
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size * 0.85f)
                .border(size * 0.028f, Color.White, RoundedCornerShape(size * 0.045f)),
        )
        Text(
            "文",
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.5f).sp,
            color = Color.White,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        )
    }
}

/** Line-art pen nib in accent ink — the easter-egg trigger mark. */
@Composable
fun NovelNibMark(size: Dp, modifier: Modifier = Modifier) {
    val accent = LocalAmberTokens.current.accent
    androidx.compose.foundation.Canvas(modifier.size(width = size * 0.66f, height = size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = 2.dp.toPx())
        val nib = Path().apply {
            moveTo(w * 0.5f, h * 0.96f)
            lineTo(w * 0.12f, h * 0.42f)
            lineTo(w * 0.2f, h * 0.06f)
            lineTo(w * 0.8f, h * 0.06f)
            lineTo(w * 0.88f, h * 0.42f)
            close()
        }
        drawPath(nib, accent, style = stroke)
        drawLine(accent, Offset(w * 0.5f, h * 0.6f), Offset(w * 0.5f, h * 0.92f), strokeWidth = stroke.width)
        drawCircle(accent, radius = w * 0.09f, center = Offset(w * 0.5f, h * 0.55f), style = stroke)
    }
}

/**
 * Vermilion drops bursting out from a center point. Deterministic layout like the
 * iOS `NovelInkSplash`; re-fires whenever [trigger] increments.
 */
@Composable
fun NovelInkSplash(trigger: Int, modifier: Modifier = Modifier) {
    val accent = LocalAmberTokens.current.accent
    val progress = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(620))
    }
    androidx.compose.foundation.Canvas(modifier.size(160.dp, 120.dp)) {
        if (progress.value <= 0f || progress.value >= 1f) return@Canvas
        val p = progress.value
        val cx = this.size.width / 2f
        val cy = this.size.height * 0.5f
        for (i in 0 until 14) {
            val angle = i / 14.0 * 2.0 * Math.PI + (if (i % 2 == 0) 0.2 else -0.15)
            val distance = (38 + (i * 7) % 26).dp.toPx()
            val dot = (4 + (i * 5) % 7).dp.toPx() / 2f
            val fall = 22.dp.toPx() * p * p
            val fade = (1f - p).coerceIn(0f, 1f)
            drawCircle(
                color = accent.copy(alpha = 0.85f * fade),
                radius = dot * (0.4f + 0.6f * (1f - p)),
                center = Offset(
                    cx + (cos(angle) * distance * p).toFloat(),
                    cy + (sin(angle) * distance * p).toFloat() + fall,
                ),
            )
        }
    }
}
