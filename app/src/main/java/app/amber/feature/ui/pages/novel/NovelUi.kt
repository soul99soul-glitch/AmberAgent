package app.amber.feature.ui.pages.novel

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.components.ds.BtnAccent
import app.amber.feature.ui.components.ds.BtnInk
import app.amber.feature.ui.components.ds.LiveDot
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceBorder
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.BookOpenText

/** Shared motion timings for novel pages — keep transitions soft and short. */
object NovelMotion {
    const val FastMs = 160
    const val MediumMs = 240
    const val SlowMs = 320

    fun horizontalPage(forward: Boolean): ContentTransform {
        val enterOffset = { w: Int -> if (forward) w / 10 else -w / 10 }
        val exitOffset = { w: Int -> if (forward) -w / 12 else w / 12 }
        return (
            slideInHorizontally(
                animationSpec = tween(MediumMs, easing = FastOutSlowInEasing),
                initialOffsetX = enterOffset,
            ) + fadeIn(animationSpec = tween(MediumMs))
            ) togetherWith (
            slideOutHorizontally(
                animationSpec = tween(FastMs, easing = FastOutSlowInEasing),
                targetOffsetX = exitOffset,
            ) + fadeOut(animationSpec = tween(FastMs))
            )
    }

    fun fadeScale(): ContentTransform =
        (
            fadeIn(tween(MediumMs)) + scaleIn(initialScale = 0.96f, animationSpec = tween(MediumMs))
            ) togetherWith (
            fadeOut(tween(FastMs)) + scaleOut(targetScale = 0.98f, animationSpec = tween(FastMs))
            )

    fun verticalSwap(): ContentTransform =
        (
            slideInVertically(
                animationSpec = tween(MediumMs, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 16 },
            ) + fadeIn(tween(MediumMs))
            ) togetherWith (
            slideOutVertically(
                animationSpec = tween(FastMs),
                targetOffsetY = { -it / 20 },
            ) + fadeOut(tween(FastMs))
            )

    fun horizontalByIndex(initialIndex: Int, targetIndex: Int): ContentTransform =
        horizontalPage(forward = targetIndex >= initialIndex)

    /** List → detail (push from trailing edge). */
    fun pushDetail(): ContentTransform =
        (
            slideInHorizontally(
                animationSpec = tween(MediumMs, easing = FastOutSlowInEasing),
                initialOffsetX = { it / 6 },
            ) + fadeIn(tween(MediumMs))
            ) togetherWith (
            slideOutHorizontally(
                animationSpec = tween(FastMs, easing = FastOutSlowInEasing),
                targetOffsetX = { -it / 14 },
            ) + fadeOut(tween(FastMs))
            )

    /** Detail → list (pop toward trailing edge). */
    fun popDetail(): ContentTransform =
        (
            slideInHorizontally(
                animationSpec = tween(MediumMs, easing = FastOutSlowInEasing),
                initialOffsetX = { -it / 14 },
            ) + fadeIn(tween(MediumMs))
            ) togetherWith (
            slideOutHorizontally(
                animationSpec = tween(MediumMs, easing = FastOutSlowInEasing),
                targetOffsetX = { it / 6 },
            ) + fadeOut(tween(FastMs))
            )
}

/** Shared empty-state used by project list / workspace tabs. */
@Composable
fun NovelEmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Lucide.BookOpenText,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(tokens.accent.copy(alpha = 0.12f))
                .border(1.dp, tokens.accent.copy(alpha = 0.22f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = tokens.accent,
            )
        }
        Text(
            text = title,
            style = type.sessionTitle,
            color = workspace.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            style = type.secondary,
            color = workspace.muted,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            BtnAccent(text = actionLabel, onClick = onAction)
        }
    }
}

/**
 * Shared novel control metrics — keep primary / ghost / quiet / chip on one grid so
 * paired actions never look different sizes at the same hierarchy.
 */
object NovelControl {
    val RadiusCompact = 12.dp
    val RadiusPrimary = 15.dp
    val ChipRadius = 999.dp
    val CompactHPad = 14.dp
    val CompactVPad = 8.dp
    val QuietHPad = 10.dp
    val QuietVPad = 8.dp
    val ChipHPad = 14.dp
    val ChipVPad = 8.dp
    val IconTap = 48.dp
    val IconGlyph = 18.dp
    val MinTouch = 48.dp
}

@Composable
fun NovelBanner(
    text: String,
    tone: WorkspaceTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val (container, content) = when (tone) {
        WorkspaceTone.Danger -> workspace.redContainer to workspace.red
        WorkspaceTone.Warning -> workspace.amberContainer to workspace.amber
        WorkspaceTone.Success -> workspace.greenContainer to workspace.green
        WorkspaceTone.Accent -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        WorkspaceTone.Neutral -> workspace.row to workspace.muted
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(12.dp),
        color = container,
        border = workspaceBorder(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = text,
                style = type.secondary,
                color = content,
            )
            if (actionLabel != null && onAction != null) {
                NovelPrimaryButton(
                    text = actionLabel,
                    onClick = onAction,
                    modifier = Modifier.align(Alignment.End),
                    accent = true,
                    compact = true,
                )
            }
        }
    }
}

/** Borderless muted action — 取消 / 全选 / 清空 / TopBar text actions. */
@Composable
fun NovelQuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    val fg = when {
        !enabled -> workspace.faint
        danger -> workspace.red
        else -> workspace.muted
    }
    Box(
        modifier = modifier
            .heightIn(min = NovelControl.MinTouch)
            .clip(RoundedCornerShape(NovelControl.RadiusCompact))
            .pressable(onClick = onClick, enabled = enabled)
            .padding(horizontal = NovelControl.QuietHPad, vertical = NovelControl.QuietVPad),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

/** Square icon hit target used in cards / reader chrome. */
@Composable
fun NovelIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val workspace = workspaceColors()
    val resolved = (tint ?: workspace.muted).copy(alpha = if (enabled) 1f else 0.4f)
    Box(
        modifier = modifier
            .size(NovelControl.IconTap)
            .clip(RoundedCornerShape(10.dp))
            .pressable(onClick = onClick, enabled = enabled),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = resolved,
            modifier = Modifier.size(NovelControl.IconGlyph),
        )
    }
}

@Composable
fun NovelGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    // Same footprint as compact primary — pairs never misalign.
    val shape = RoundedCornerShape(NovelControl.RadiusCompact)
    val fg = when {
        !enabled -> workspace.faint
        danger -> workspace.red
        else -> tokens.ink
    }
    Box(
        modifier = modifier
            .heightIn(min = NovelControl.MinTouch)
            .clip(shape)
            .border(
                1.dp,
                when {
                    !enabled -> workspace.hairline.copy(alpha = 0.55f)
                    danger -> workspace.red.copy(alpha = 0.35f)
                    else -> workspace.hairline
                },
                shape,
            )
            .background(
                when {
                    !enabled -> workspace.paper.copy(alpha = 0.7f)
                    danger -> workspace.redContainer
                    else -> workspace.paper
                },
            )
            .pressable(onClick = onClick, enabled = enabled)
            .padding(horizontal = NovelControl.CompactHPad, vertical = NovelControl.CompactVPad),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun NovelPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    /**
     * Compact footprint matches [NovelGhostButton] (action bars, proposal cards, dialogs).
     * Full size uses design-system BtnInk/BtnAccent (sheet confirm, fork hero).
     */
    compact: Boolean = false,
) {
    val tokens = LocalAmberTokens.current
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    if (compact) {
        val shape = RoundedCornerShape(NovelControl.RadiusCompact)
        val bg = when {
            !enabled -> workspace.row
            accent -> tokens.accent
            else -> tokens.ink
        }
        val fg = when {
            !enabled -> workspace.faint
            accent -> tokens.accentInk
            else -> tokens.bg
        }
        Box(
            modifier = modifier
                .heightIn(min = NovelControl.MinTouch)
                .clip(shape)
                .background(bg)
                .pressable(onClick = onClick, enabled = enabled)
                .padding(horizontal = NovelControl.CompactHPad, vertical = NovelControl.CompactVPad),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = fg,
                style = type.meta.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    // Full primary — same radius/padding as BtnInk/BtnAccent whether enabled or not.
    val shape = RoundedCornerShape(NovelControl.RadiusPrimary)
    if (!enabled) {
        Box(
            modifier = modifier
                .heightIn(min = NovelControl.MinTouch)
                .clip(shape)
                .background(workspace.row)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color = workspace.faint,
                style = type.body.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    if (accent) {
        BtnAccent(text = text, modifier = modifier, onClick = onClick)
    } else {
        BtnInk(text = text, modifier = modifier, onClick = onClick)
    }
}

@Composable
fun NovelIconCircle(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Boolean = true,
) {
    val tokens = LocalAmberTokens.current
    val workspace = workspaceColors()
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (accent) tokens.accent.copy(alpha = 0.12f) else workspace.row)
            .border(
                1.dp,
                if (accent) tokens.accent.copy(alpha = 0.22f) else workspace.hairline,
                RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (accent) tokens.accent else workspace.ink,
        )
    }
}
val NovelBubbleShapeUser = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
val NovelBubbleShapeAssistant = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
