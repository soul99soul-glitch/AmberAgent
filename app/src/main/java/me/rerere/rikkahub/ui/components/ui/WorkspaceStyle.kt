package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.LocalAmoledDarkMode
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Immutable
data class WorkspaceColors(
    val canvas: Color,
    val paper: Color,
    val row: Color,
    val note: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val hairline: Color,
    val blue: Color,
    val blueContainer: Color,
    val green: Color,
    val greenContainer: Color,
    val amber: Color,
    val amberContainer: Color,
    val red: Color,
    val redContainer: Color,
)

enum class WorkspaceTone {
    Neutral,
    Accent,
    Success,
    Warning,
    Danger,
}

/**
 * Pulse-pivoted WorkspaceColors: every token now derives from
 * MaterialTheme.colorScheme (which is wired to PulseThemePreset's
 * chartreuse + sport-orange + cream/ink palette via Phase 0+1).
 *
 * The original Codex implementation hardcoded blue/green/amber/red
 * values that bypassed the theme, so flipping to the Pulse preset only
 * affected components that already consumed colorScheme directly. By
 * routing every workspace token through the active scheme here, all
 * 18+ downstream files that consume workspaceColors() / WorkspaceTone
 * automatically inherit Pulse without per-file edits.
 *
 * Tone-collapse rationale (intentional, not a bug):
 *   - blue   ("Accent")  → primary    (chartreuse — the "active" color)
 *   - green  ("Success") → primary    (chartreuse — Pulse treats success
 *                                       and active as the same hue family)
 *   - amber  ("Warning") → secondary  (sport-orange — "needs attention")
 *   - red    ("Danger")  → error      (sport-orange — Pulse design has
 *                                       no separate red; warning and
 *                                       danger share hue but error is
 *                                       its own M3 slot for downstream
 *                                       components that style errors
 *                                       differently)
 *
 * AMOLED branch: keep pure-black canvas for OLED battery savings, then
 * pull accent colors from the dark Pulse scheme so chartreuse and
 * sport-orange still pop on the true-black ground.
 */
@Composable
fun workspaceColors(): WorkspaceColors {
    val scheme = MaterialTheme.colorScheme
    if (LocalAmoledDarkMode.current) {
        // True-black canvas (#000) for OLED, layered surfaces a few notches
        // brighter so cards still read as discrete from background. Accent
        // hues come from the active Pulse dark scheme so the brand stays
        // recognisable even with maximum contrast.
        return WorkspaceColors(
            canvas = Color(0xFF000000),
            paper = Color(0xFF050505),
            row = Color(0xFF0A0A0A),
            note = Color(0xFF0E0E0E),
            ink = scheme.onSurface,
            muted = scheme.onSurfaceVariant,
            faint = scheme.onSurfaceVariant.copy(alpha = 0.55f),
            hairline = scheme.outlineVariant,
            blue = scheme.primary,
            blueContainer = scheme.primaryContainer,
            green = scheme.primary,
            greenContainer = scheme.primaryContainer,
            amber = scheme.secondary,
            amberContainer = scheme.secondaryContainer,
            red = scheme.error,
            redContainer = scheme.errorContainer,
        )
    }
    // Both light and dark Pulse schemes carry the same semantic mapping;
    // colorScheme.* picks the right variant for each mode automatically.
    return WorkspaceColors(
        canvas = scheme.background,
        paper = scheme.surface,
        row = scheme.surfaceVariant,
        note = scheme.surfaceContainerLow,
        ink = scheme.onSurface,
        muted = scheme.onSurfaceVariant,
        faint = scheme.onSurfaceVariant.copy(alpha = 0.65f),
        hairline = scheme.outlineVariant,
        blue = scheme.primary,
        blueContainer = scheme.primaryContainer,
        green = scheme.primary,
        greenContainer = scheme.primaryContainer,
        amber = scheme.secondary,
        amberContainer = scheme.secondaryContainer,
        red = scheme.error,
        redContainer = scheme.errorContainer,
    )
}

@Composable
fun workspaceBorder(alpha: Float = 1f): BorderStroke =
    BorderStroke(1.dp, workspaceColors().hairline.copy(alpha = workspaceColors().hairline.alpha * alpha))

@Composable
fun WorkspaceDivider(
    modifier: Modifier = Modifier,
) {
    val colors = workspaceColors()
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.hairline)
    )
}

@Composable
fun WorkspaceStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
    maxWidth: Dp = Dp.Unspecified,
) {
    val colors = workspaceColors()
    val (container, content) = when (tone) {
        WorkspaceTone.Neutral -> colors.row to colors.muted
        WorkspaceTone.Accent -> colors.blueContainer to colors.blue
        WorkspaceTone.Success -> colors.greenContainer to colors.green
        WorkspaceTone.Warning -> colors.amberContainer to colors.amber
        WorkspaceTone.Danger -> colors.redContainer to colors.red
    }
    Surface(
        modifier = modifier.then(if (maxWidth != Dp.Unspecified) Modifier.width(maxWidth) else Modifier),
        shape = CircleShape,
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, content.copy(alpha = 0.13f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun WorkspaceLeadingIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    iconSize: Dp = 17.dp,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
) {
    val colors = workspaceColors()
    val tint = when (tone) {
        WorkspaceTone.Neutral -> colors.ink
        WorkspaceTone.Accent -> colors.blue
        WorkspaceTone.Success -> colors.green
        WorkspaceTone.Warning -> colors.amber
        WorkspaceTone.Danger -> colors.red
    }
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(6.dp),
        color = when (tone) {
            WorkspaceTone.Neutral -> Color.Transparent
            WorkspaceTone.Accent -> colors.blueContainer
            WorkspaceTone.Success -> colors.greenContainer
            WorkspaceTone.Warning -> colors.amberContainer
            WorkspaceTone.Danger -> colors.redContainer
        },
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * Pulse circular icon button.
 *
 * Replaces the old 6dp-rounded-square workspace icon button with a
 * fully circular surface (mockup-faithful — every icon-button in the
 * Pulse mockup is a circle). Shape and color treatment depend on tone
 * + showBorder:
 *
 *   Neutral + showBorder=true    → cream surface with 1.5dp ink hairline
 *                                   (default top-bar action affordance)
 *   Neutral + showBorder=false   → transparent surface, ink icon
 *                                   (text-button-equivalent for icons)
 *   Accent (tone)                → chartreuse-tinted container, ink icon
 *   Success / Warning / Danger   → matching tone container + content
 *                                   (collapses to chartreuse / orange via
 *                                   the Pulse workspace token mapping)
 *
 * Container override (containerColor param) bypasses tone — used when
 * the call site needs a specific surface (e.g. ChatPage's TopBar
 * "New Message" button overrides container to paper for visual rest).
 */
@Composable
fun WorkspaceIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 32.dp,
    iconSize: Dp = 15.dp,
    showBorder: Boolean = true,
    containerColor: Color? = null,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
    icon: ImageVector,
    contentDescription: String?,
) {
    val colors = workspaceColors()
    val contentColor = when (tone) {
        WorkspaceTone.Neutral -> colors.ink
        WorkspaceTone.Accent -> colors.blue
        WorkspaceTone.Success -> colors.green
        WorkspaceTone.Warning -> colors.amber
        WorkspaceTone.Danger -> colors.red
    }.copy(alpha = if (enabled) 1f else 0.36f)
    val resolvedContainer = containerColor ?: when (tone) {
        WorkspaceTone.Neutral -> colors.paper
        WorkspaceTone.Accent -> colors.blueContainer
        WorkspaceTone.Success -> colors.greenContainer
        WorkspaceTone.Warning -> colors.amberContainer
        WorkspaceTone.Danger -> colors.redContainer
    }
    Surface(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = resolvedContainer,
        contentColor = contentColor,
        border = if (showBorder) {
            // 1.5dp ink hairline matches the Pulse mockup's outlined
            // circular button treatment (e.g. top-bar back / search /
            // info icons). Subtler than M3's default 1dp variant.
            BorderStroke(
                width = 1.5.dp,
                color = colors.ink.copy(alpha = if (enabled) 0.85f else 0.36f),
            )
        } else null,
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

/**
 * Pulse pill text button.
 *
 * The mockup's secondary text affordances ("View all", "See more",
 * inline labels) all sit inside a pill shape with a subtle outline.
 * Switched from the old 6dp rounded square to CircleShape (which
 * Compose interprets as a fully-rounded pill at the row's height) and
 * bumped padding so the touch target reads as a deliberate button
 * rather than a tappable text label.
 */
@Composable
fun WorkspaceTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
) {
    val colors = workspaceColors()
    val contentColor = when (tone) {
        WorkspaceTone.Neutral -> colors.ink
        WorkspaceTone.Accent -> colors.blue
        WorkspaceTone.Success -> colors.green
        WorkspaceTone.Warning -> colors.amber
        WorkspaceTone.Danger -> colors.red
    }
    Surface(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = colors.paper,
        contentColor = contentColor,
        border = BorderStroke(
            width = 1.5.dp,
            color = colors.ink.copy(alpha = 0.18f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}
