package app.amber.feature.ui.components.message

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/**
 * Bubble slot that caps its content at [maxWidthFraction] of the incoming max
 * width, keeps the slot full-width, and aligns the content to the start/end.
 *
 * This is the `BoxWithConstraints { Surface(Modifier.widthIn(max = maxWidth * f)) }`
 * pattern without the nested [androidx.compose.ui.layout.SubcomposeLayout].
 * Chat history mounts one bubble per visible Markdown slice, so the extra
 * subcomposition used to dominate the first measure pass of a cold entry.
 *
 * Unbounded incoming width (previews, intrinsic measurement) falls back to the
 * child's own width, matching `maxWidth == Dp.Infinity` in the old code.
 */
internal fun Modifier.chatBubbleWidth(
    maxWidthFraction: Float,
    alignEnd: Boolean = false,
): Modifier {
    require(maxWidthFraction in 0f..1f) {
        "maxWidthFraction must be in 0f..1f, was $maxWidthFraction"
    }
    return layout { measurable, constraints ->
        val cappedMaxWidth = if (constraints.hasBoundedWidth) {
            cappedBubbleWidth(constraints.maxWidth, maxWidthFraction)
        } else {
            constraints.maxWidth
        }
        val placeable = measurable.measure(
            constraints.copy(
                // Box/BoxWithConstraints let the child wrap below the parent's
                // min width; only the max edge is capped here.
                minWidth = 0,
                maxWidth = cappedMaxWidth,
                minHeight = 0,
            ),
        )
        val layoutWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            placeable.width
        }
        val layoutHeight = placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        // placeRelative mirrors the offset for RTL, so "end" stays the visual
        // end in both directions (same as contentAlignment TopEnd).
        val x = if (alignEnd) (layoutWidth - placeable.width).coerceAtLeast(0) else 0
        layout(layoutWidth, layoutHeight) {
            placeable.placeRelative(x, 0)
        }
    }
}

/** Rounded, clamped width cap used by [chatBubbleWidth]. Pure for unit tests. */
internal fun cappedBubbleWidth(parentWidth: Int, fraction: Float): Int =
    (parentWidth * fraction).roundToInt().coerceIn(0, parentWidth)
