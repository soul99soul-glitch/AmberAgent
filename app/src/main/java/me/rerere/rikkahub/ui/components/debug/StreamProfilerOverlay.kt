package me.rerere.rikkahub.ui.components.debug

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
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.components.richtext.LocalCharRevealController

// Phase B / B4 — dev-only profiler overlay.
//
// Surfaces the streaming-render metrics that smooth-streaming-rendering
// -guide §14 calls for: a quick at-a-glance signal of whether the
// reveal pipeline is keeping up. NOT meant for end users — gated on
// BuildConfig.DEBUG so it disappears in release variants and the
// notion buildType has DEBUG=true so the developer sees it.
//
// Currently shows:
//   - FPS  (independent EWMA from withFrameNanos, not the reveal
//     controller's — so we still get a number when no reveal is
//     active)
//   - reveal queue depth (per CharRevealController.queueDepth)
//   - reveal degraded? (currentFps below the controller's degrade
//     threshold)
//
// Future additions on the same surface (B4 follow-up if needed):
//   - amberTraceMeasure rolling commit cost
//   - chunk inter-arrival ms

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
                        // EWMA alpha = 1/8, ~125ms half-life
                        avgFrameDeltaNanos = (avgFrameDeltaNanos * 7 + delta) / 8
                    }
                }
                prevFrameNanos = now
            }
        }
    }

    val fps = if (avgFrameDeltaNanos > 0L)
        1_000_000_000f / avgFrameDeltaNanos.toFloat()
    else 60f

    val controller = LocalCharRevealController.current
    val queueDepth = controller?.queueDepth() ?: 0
    val controllerFps = controller?.currentFps
    val isReveal = controller != null

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
            if (isReveal) {
                Text(
                    text = "rev q=$queueDepth",
                    color = if (queueDepth > 80) Color(0xFFFF6E6E)
                    else if (queueDepth > 30) Color(0xFFFFC66D)
                    else Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
                if (controllerFps != null) {
                    Text(
                        text = "rev fps ${controllerFps.toInt()}",
                        color = if (controllerFps < 45f) Color(0xFFFF6E6E)
                        else Color.White.copy(alpha = 0.6f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
