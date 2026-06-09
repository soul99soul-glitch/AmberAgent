package app.amber.feature.ui.components.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.BuildConfig
import app.amber.feature.ui.components.richtext.LocalStreamingTailActive
import app.amber.feature.ui.components.richtext.StreamingRenderProbe

// Dev-only profiler overlay for the batch-reveal streaming pipeline.
// Gated on BuildConfig.DEBUG — not shown in release variants.

@Composable
fun StreamProfilerOverlay(modifier: Modifier = Modifier) {
    if (!BuildConfig.DEBUG) return

    var avgFrameDeltaNanos by remember { mutableLongStateOf(16_666_666L) }
    var prevFrameNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                if (prevFrameNanos > 0L) {
                    val delta = now - prevFrameNanos
                    if (delta in 1_000_000L..200_000_000L) {
                        avgFrameDeltaNanos = (avgFrameDeltaNanos * 7 + delta) / 8
                    }
                }
                prevFrameNanos = now
            }
        }
    }

    val fps = if (avgFrameDeltaNanos > 0L) {
        1_000_000_000f / avgFrameDeltaNanos.toFloat()
    } else {
        60f
    }

    val tailActive = LocalStreamingTailActive.current != null
    val motionClaims = StreamingRenderProbe.motionClaimCount
    val displayBacklog = StreamingRenderProbe.displayBacklog
    val liveSuffixLen = StreamingRenderProbe.liveSuffixLength
    val parseTicks = StreamingRenderProbe.parseTickCount

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Column {
            Text(
                text = "fps ${fps.toInt()}",
                color = if (fps < 45f) Color(0xFFFF6E6E)
                else if (fps < 55f) Color(0xFFFFC66D)
                else Color(0xFF8FE388),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (tailActive) {
                Text(
                    text = "tail suffix=$liveSuffixLen buf=$displayBacklog",
                    color = if (displayBacklog > 600) Color(0xFFFF6E6E)
                    else if (displayBacklog > 200) Color(0xFFFFC66D)
                    else Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "motion=$motionClaims parse=$parseTicks",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
