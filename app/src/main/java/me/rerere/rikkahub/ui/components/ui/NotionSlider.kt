package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Slim slider in the project's Notion-flavoured visual language.
 *
 *  - thumb is a small donut (20dp outer / 8dp inner concentric circles, primary fill),
 *    matching `ReasoningPicker`
 *  - track is a 3dp line; stop dots are suppressed via `drawStopIndicator = null`
 *  - `thumbTrackGapSize = 0.dp` removes the Material3 1.3+ visible gap that reads as
 *    a glitch in this thinner style
 *  - drag is continuous; on release we snap and only THEN propagate via
 *    [onValueChangeFinished] — keeps stored state on a clean grid and avoids spamming
 *    the parent on every drag tick
 *  - `live` state is bound to a drag interaction-source so an asynchronous external
 *    update (e.g. `StateFlow` round-trip after our own commit) cannot snap the thumb
 *    back mid-drag
 *
 * @param snapStep   if non-null, on release the value snaps to the nearest multiple of
 *                   this step inside `valueRange`. Pass `null` for fully continuous.
 * @param valueLabel renders the right-side label (e.g. `"100%"`, `"1500"`). Receives the
 *                   *live* (during drag) or persisted (idle) value, whichever is current,
 *                   so the label tracks the thumb in real time.
 */
@Composable
fun NotionSlider(
    value: Float,
    onValueChangeFinished: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    snapStep: Float? = null,
    valueLabel: (@Composable RowScope.(Float) -> Unit)? = null,
    enabled: Boolean = true,
    trackHeight: Dp = 3.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    var live by remember { mutableStateOf(value) }
    LaunchedEffect(value, isDragged) {
        if (!isDragged) live = value
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = {
                val snapped = if (snapStep != null) {
                    // Round to the nearest multiple of snapStep. Single formula handles
                    // both sub-1 steps (0.05, 0.25 → percentages) and >=1 steps (100 →
                    // paste threshold). The earlier `1f/snapStep` inverse approach
                    // collapsed for step=100 because (1/100).roundToInt() = 0 (div0).
                    // Float drift on sub-1 steps is bounded by ~1e-6 — invisible after
                    // the display roundToInt() and bit-stable across save/load round trips
                    // (same float math both sides), so equality checks are still safe.
                    ((live / snapStep).roundToInt().toFloat() * snapStep)
                        .coerceIn(valueRange.start, valueRange.endInclusive)
                } else {
                    live
                }
                live = snapped
                if (snapped != value) onValueChangeFinished(snapped)
            },
            valueRange = valueRange,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = Modifier.weight(1f),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimary)
                    )
                }
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                    modifier = Modifier.height(trackHeight),
                )
            },
        )
        if (valueLabel != null) {
            valueLabel(live)
        }
    }
}

/** Common right-side label preset: percentage of `value` (0..1+ → "N%"). */
@Composable
fun RowScope.PercentLabel(value: Float) {
    Text(
        text = "${(value * 100f).roundToInt()}%",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Common right-side label preset: integer raw value (e.g. paste threshold "1000"). */
@Composable
fun RowScope.IntLabel(value: Float) {
    Text(
        text = value.roundToInt().toString(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
