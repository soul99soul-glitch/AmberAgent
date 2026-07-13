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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.BtnAccent
import app.amber.feature.ui.components.ds.BtnInk
import app.amber.feature.ui.components.ds.LiveDot
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.components.ui.WorkspaceStatusPill
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceBorder
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BookOpen01

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
}

/** Shared empty-state used by project list / workspace tabs. */
@Composable
fun NovelEmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = HugeIcons.BookOpen01,
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
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = container,
        border = workspaceBorder(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = text,
                style = type.secondary,
                color = content,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                NovelChipButton(
                    text = actionLabel,
                    selected = true,
                    onClick = onAction,
                    compact = true,
                )
            }
        }
    }
}

@Composable
fun NovelSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    AmberCard(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = type.sessionTitle,
                    color = workspace.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta != null) {
                    WorkspaceStatusPill(text = meta, tone = WorkspaceTone.Neutral)
                }
            }
            content()
        }
    }
}

@Composable
fun NovelChipButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val shape = RoundedCornerShape(999.dp)
    val targetBg = when {
        !enabled -> workspace.row.copy(alpha = 0.5f)
        selected -> tokens.ink
        else -> workspace.paper
    }
    val targetFg = when {
        !enabled -> workspace.faint
        selected -> tokens.bg
        else -> workspace.ink
    }
    val targetBorder = when {
        selected -> tokens.ink
        else -> workspace.hairline
    }
    val bg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(NovelMotion.FastMs, easing = FastOutSlowInEasing),
        label = "novelChipBg",
    )
    val fg by animateColorAsState(
        targetValue = targetFg,
        animationSpec = tween(NovelMotion.FastMs, easing = FastOutSlowInEasing),
        label = "novelChipFg",
    )
    val borderColor by animateColorAsState(
        targetValue = targetBorder,
        animationSpec = tween(NovelMotion.FastMs, easing = FastOutSlowInEasing),
        label = "novelChipBorder",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .then(if (enabled) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 6.dp else 8.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.meta.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun NovelChipRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun NovelSegmentedTabs(
    selectedIndex: Int,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(12.dp),
        color = workspace.row,
        border = workspaceBorder(),
    ) {
        Row(
            Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val bg by animateColorAsState(
                    targetValue = if (selected) workspace.paper else Color.Transparent,
                    animationSpec = tween(NovelMotion.MediumMs, easing = FastOutSlowInEasing),
                    label = "novelSegBg$index",
                )
                val border by animateColorAsState(
                    targetValue = if (selected) workspace.hairline else Color.Transparent,
                    animationSpec = tween(NovelMotion.MediumMs, easing = FastOutSlowInEasing),
                    label = "novelSegBorder$index",
                )
                val fg by animateColorAsState(
                    targetValue = if (selected) tokens.ink else workspace.muted,
                    animationSpec = tween(NovelMotion.FastMs, easing = FastOutSlowInEasing),
                    label = "novelSegFg$index",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(9.dp))
                        .pressable(onClick = { onSelect(index) })
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = type.body.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = fg,
                    )
                }
            }
        }
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
    val shape = RoundedCornerShape(12.dp)
    val fg = when {
        !enabled -> workspace.faint
        danger -> workspace.red
        else -> tokens.ink
    }
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, if (danger) workspace.red.copy(alpha = 0.35f) else workspace.hairline, shape)
            .background(if (danger) workspace.redContainer else workspace.paper)
            .then(if (enabled) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.meta.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
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
) {
    if (!enabled) {
        val workspace = workspaceColors()
        val type = LocalAmberType.current
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(15.dp))
                .background(workspace.row)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = workspace.faint, style = type.body.copy(fontWeight = FontWeight.SemiBold))
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
fun NovelGeneratingHeader() {
    val workspace = workspaceColors()
    val type = LocalAmberType.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiveDot()
        Text(
            text = "助手 · 生成中",
            style = type.meta,
            color = workspace.muted,
        )
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

/** Soft content body text used inside cards. */
@Composable
fun NovelBodyText(
    text: String,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    val workspace = workspaceColors()
    Text(
        text = text,
        modifier = modifier,
        style = LocalAmberType.current.body,
        color = if (muted) workspace.muted else workspace.ink,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

val NovelListContentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
val NovelComposerShape = RoundedCornerShape(18.dp)
val NovelBubbleShapeUser = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
val NovelBubbleShapeAssistant = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)

