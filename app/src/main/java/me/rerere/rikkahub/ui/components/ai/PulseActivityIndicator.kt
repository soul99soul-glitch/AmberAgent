package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pulse Performance signature streaming-state indicator.
 *
 * Renders an ink mini-card containing a horizontal "pulse track" of seven
 * thin chartreuse vertical bars whose heights animate in a phase-shifted
 * sinusoidal pattern, plus an optional ALL-CAPS label to the right ("PULSE
 * ACTIVE" by default). Designed as a louder, more on-brand replacement for
 * generic CircularProgressIndicator / dot-spinner affordances during model
 * streaming or tool execution.
 *
 * Animation runs on a single [androidx.compose.animation.core.InfiniteTransition]
 * driving a 0..2π phase value at a 1.2s linear cycle; each bar samples its
 * height from a sin curve offset by its index so the seven bars read as a
 * single travelling wave rather than seven independent oscillators.
 *
 * Contrast: bars are colorScheme.primary (chartreuse) on a colorScheme.tertiary
 * (ink spotlight) surface, mirroring the "active operation" hierarchy used
 * for the floating bottom-nav active pill and the tool capsule running stripe.
 */
@Composable
fun PulseActivityIndicator(
    modifier: Modifier = Modifier,
    label: String? = "PULSE ACTIVE",
    barCount: Int = 7,
) {
    // Single shared phase driver so all bars stay in sync; cheaper than one
    // animation per bar and produces a coherent left-to-right wave.
    val transition = rememberInfiniteTransition(label = "pulseActivity")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulseActivityPhase",
    )

    // Cache a per-bar phase offset so the eye reads the bars as a wave
    // travelling left-to-right; spacing is 2π / barCount so the full track
    // shows one complete cycle at any given frame.
    val barOffsets = remember(barCount) {
        FloatArray(barCount) { i -> i * (2f * PI / barCount).toFloat() }
    }

    Row(
        modifier = modifier
            .wrapContentHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.tertiary)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The track itself: a fixed-height container in which the bars
        // anchor to baseline and grow upward. 14dp tall feels right for
        // an inline message-list indicator; bumps to 18dp would crowd
        // the surrounding 8dp item-spacing.
        Row(
            modifier = Modifier
                .height(14.dp)
                .padding(vertical = 1.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (i in 0 until barCount) {
                // Bar height = 30% baseline + 70% modulation. Lowest a bar
                // ever falls is 30% of the track height, so the row never
                // appears "empty"; the modulation gives the wave its life.
                val raw = sin(phase + barOffsets[i])
                val normalized = (abs(raw) * 0.7f) + 0.3f
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight(fraction = normalized)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        if (label != null) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.10.em,
                ),
            )
        } else {
            // No label: lock a tiny end-padding so the track doesn't kiss the
            // mini-card's right edge.
            Spacer(modifier = Modifier.size(2.dp))
        }
    }
}
