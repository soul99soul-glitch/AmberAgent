package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * Pulse Performance button vocabulary.
 *
 * The Pulse mockup uses a small set of distinct button shapes/colors
 * with deliberate semantic roles. This file exposes them as composable
 * helpers so call sites can pick by intent rather than hand-tuning
 * shape + color + typography for every Button() invocation:
 *
 *   PulsePrimaryButton    chartreuse asymmetric squircle, ink content
 *                         — the "go" / primary action (Send, Start, Confirm)
 *
 *   PulseSecondaryButton  sport-orange pill, cream content
 *                         — critical/featured CTAs (Upgrade, Add Provider,
 *                           Get Started, Save Dashboard)
 *
 *   PulseGhostButton      transparent pill with 1.5dp ink hairline
 *                         — neutral secondary action (Cancel, Wait, Dismiss)
 *
 *   PulseDarkButton       ink filled pill, cream content
 *                         — strong neutral action (Always Allow, Confirm Sign Out)
 *
 * All variants share:
 *   - 0.92x spring scale on press for tactile feedback
 *   - Bold ALL-CAPS labels with 0.05em letter-spacing (eyebrow vibe)
 *   - 14dp / 12dp padding tuned to the Pulse pill density
 *
 * Icon support: each variant accepts a leading ImageVector for the
 * common label-with-icon CTA pattern from the mockup.
 */

private enum class PulseButtonShape { Squircle, Pill }

@Composable
fun PulsePrimaryButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    PulseButtonImpl(
        onClick = onClick,
        text = text,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        container = MaterialTheme.colorScheme.primary,         // chartreuse
        content = MaterialTheme.colorScheme.onPrimary,         // ink
        border = null,
        shape = PulseButtonShape.Squircle,
    )
}

@Composable
fun PulseSecondaryButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    PulseButtonImpl(
        onClick = onClick,
        text = text,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        container = MaterialTheme.colorScheme.secondary,       // sport-orange
        content = MaterialTheme.colorScheme.onSecondary,       // cream
        border = null,
        shape = PulseButtonShape.Pill,
    )
}

@Composable
fun PulseGhostButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    PulseButtonImpl(
        onClick = onClick,
        text = text,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        container = MaterialTheme.colorScheme.surface,         // cream/transparent
        content = MaterialTheme.colorScheme.onSurface,         // ink
        border = BorderStroke(
            width = 1.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        ),
        shape = PulseButtonShape.Pill,
    )
}

@Composable
fun PulseDarkButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    PulseButtonImpl(
        onClick = onClick,
        text = text,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        container = MaterialTheme.colorScheme.tertiary,        // ink
        content = MaterialTheme.colorScheme.onTertiary,        // cream
        border = null,
        shape = PulseButtonShape.Pill,
    )
}

@Composable
private fun PulseButtonImpl(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier,
    enabled: Boolean,
    leadingIcon: ImageVector?,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    border: BorderStroke?,
    shape: PulseButtonShape,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pulseButtonScale",
    )
    // Asymmetric squircle is the Pulse "go" shape (matches the composer
    // send button); pill is for secondary/ghost/dark CTAs that want a
    // calmer geometry. The shape choice is deliberately enum-encoded
    // here rather than a free Shape param so misuse is impossible from
    // call sites — pick a button variant, get the right shape.
    val resolvedShape = when (shape) {
        PulseButtonShape.Squircle -> RoundedCornerShape(
            topStart = 20.dp, topEnd = 6.dp, bottomEnd = 20.dp, bottomStart = 6.dp,
        )
        PulseButtonShape.Pill -> CircleShape
    }
    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(resolvedShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            ),
        shape = resolvedShape,
        color = if (enabled) container else container.copy(alpha = 0.38f),
        contentColor = if (enabled) content else content.copy(alpha = 0.50f),
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(
                PaddingValues(
                    horizontal = if (leadingIcon != null) 16.dp else 18.dp,
                    vertical = 12.dp,
                )
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(16.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.05.em,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Pulse compact dialog button — same Pulse vocabulary as the four
 * primary variants above but with reduced vertical padding (6dp vs.
 * 12dp) so it slots into AlertDialog footers without making the
 * dialog feel chunkier than M3's default TextButton-based footer.
 * Pill shape across all variants since dialog buttons sit in a flat
 * Row and the squircle would clash with adjacent peers.
 *
 * The variant param picks color treatment:
 *   Primary    chartreuse + ink     → "Save", "Apply", non-destructive confirm
 *   Secondary  sport-orange + cream → destructive confirm ("Delete", "Reset")
 *   Ghost      transparent + ink    → dismiss / cancel
 */
@Composable
fun PulseDialogButton(
    onClick: () -> Unit,
    text: String,
    variant: PulseDialogVariant = PulseDialogVariant.Primary,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val container: androidx.compose.ui.graphics.Color
    val content: androidx.compose.ui.graphics.Color
    val border: BorderStroke?
    when (variant) {
        PulseDialogVariant.Primary -> {
            container = MaterialTheme.colorScheme.primary
            content = MaterialTheme.colorScheme.onPrimary
            border = null
        }
        PulseDialogVariant.Secondary -> {
            container = MaterialTheme.colorScheme.secondary
            content = MaterialTheme.colorScheme.onSecondary
            border = null
        }
        PulseDialogVariant.Ghost -> {
            container = MaterialTheme.colorScheme.surface
            content = MaterialTheme.colorScheme.onSurface
            border = BorderStroke(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pulseDialogButtonScale",
    )

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            ),
        shape = CircleShape,
        color = if (enabled) container else container.copy(alpha = 0.38f),
        contentColor = if (enabled) content else content.copy(alpha = 0.50f),
        border = border,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.05.em,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

enum class PulseDialogVariant { Primary, Secondary, Ghost }

/**
 * Pulse circular icon button — the canonical top-bar / inline action
 * affordance from the Pulse mockup. Cream surface, 1.5dp ink hairline,
 * ink icon. Same press-spring as the Pulse buttons above so all
 * tappable affordances share a tactile rhythm.
 *
 * Different from WorkspaceIconButton (which is the workspace-style
 * legacy abstraction) only in that this one defaults to a 36dp size
 * and clean ink-on-cream styling without tone branching, matching the
 * mockup's simple "outlined circle around an icon" pattern. Use this
 * for new code; WorkspaceIconButton remains the right choice for
 * existing call sites that need tone semantics.
 */
@Composable
fun PulseIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 36.dp,
    iconSize: androidx.compose.ui.unit.Dp = 16.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pulseIconButtonScale",
    )
    Surface(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = 1.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.85f else 0.36f),
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
