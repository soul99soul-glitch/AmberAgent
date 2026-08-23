package app.amber.feature.ui.pages.councilroom

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Press-scale feedback (the design's pervasive `.pressable:active { scale .97 }`).
 *
 * Unlike the codebase's [app.amber.feature.ui.components.ds.pressable], this keeps
 * the host's own click handling + ripple intact: pass the SAME
 * [MutableInteractionSource] to the clickable surface (so ripple still draws) and
 * to this modifier (so it reads the press state and scales). Apply it BEFORE the
 * background/clip so the whole chip — ripple included — scales together.
 */
@Composable
fun Modifier.councilPressBounce(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "council-press-scale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
