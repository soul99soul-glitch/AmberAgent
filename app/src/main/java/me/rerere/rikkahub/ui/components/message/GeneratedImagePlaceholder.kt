package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import kotlin.math.absoluteValue

/**
 * Loading-state placeholder for the `generate_image` tool.
 *
 * Matches [GeneratedImageCarousel]'s ImageCard styling (workspace.paper +
 * hairline + 16dp rounded corners) so the swap-in animation in
 * ChatMessageTools feels like the same card morphing rather than two
 * different elements crossfading.
 *
 * The card hosts an evenly-spaced dot grid; each dot's alpha is modulated
 * by a diagonal traveling wave (~2.2s period, linear easing). Reads as
 * "something is computing" without claiming a fake progress percentage,
 * mirroring ChatGPT's image-gen loading affordance.
 *
 * [aspectRatio] should match the requested image aspect (1.0 / 16:9 / 9:16)
 * so the placeholder pre-allocates the right shape and the AnimatedContent
 * crossfade into the real image doesn't reflow the surrounding column.
 */
@Composable
fun GeneratedImagePlaceholder(
    aspectRatio: Float,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val transition = rememberInfiniteTransition(label = "imageGenLoading")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Surface(
        modifier = modifier.aspectRatio(aspectRatio),
        shape = RoundedCornerShape(16.dp),
        color = workspace.paper,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
        ) {
            val spacing = 14.dp.toPx()
            val dotRadius = 1.6.dp.toPx()
            val cols = (size.width / spacing).toInt().coerceAtLeast(1)
            val rows = (size.height / spacing).toInt().coerceAtLeast(1)
            // Center the grid inside the available canvas so dots are
            // equidistant from all edges regardless of aspect ratio.
            val xOffset = (size.width - cols * spacing) / 2f
            val yOffset = (size.height - rows * spacing) / 2f
            val diagonalRange = (cols + rows).toFloat().coerceAtLeast(1f)

            for (col in 0..cols) {
                for (row in 0..rows) {
                    val x = xOffset + col * spacing
                    val y = yOffset + row * spacing
                    // Diagonal-traveling wave: dots near the upper-left peak
                    // first as `phase` advances from 0 → 1.
                    val diagonal = (col + row).toFloat() / diagonalRange
                    val wavePhase = (phase + diagonal) % 1f
                    // Triangle wave: 0 at peak, 1 at trough. Smoother than
                    // sine here because the gradient feels more "sweeping".
                    val distance = ((wavePhase - 0.5f).absoluteValue) * 2f
                    val alpha = (0.12f + 0.52f * (1f - distance)).coerceIn(0.08f, 0.64f)
                    drawCircle(
                        color = workspace.muted.copy(alpha = alpha),
                        radius = dotRadius,
                        center = Offset(x, y),
                    )
                }
            }
        }
    }
}

/** Parse the tool's `aspect_ratio` argument into a Compose-friendly float. */
fun parseAspectRatioFloat(argValue: String?): Float = when (argValue) {
    "16:9" -> 16f / 9f
    "9:16" -> 9f / 16f
    else -> 1f
}
