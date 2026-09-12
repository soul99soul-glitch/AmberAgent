package app.amber.feature.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType

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

@Composable
fun workspaceColors(): WorkspaceColors {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalAmberTokens.current
    val success = Color(0xFF5E9C6E)
    val warning = if (tokens.isDark) Color(0xFFD8B575) else Color(0xFF9C6A26)
    val danger = Color(0xFFC2554E)
    return WorkspaceColors(
        canvas = scheme.background,
        paper = scheme.surface,
        row = scheme.surfaceVariant,
        note = scheme.surfaceContainer,
        ink = scheme.onSurface,
        muted = scheme.onSurfaceVariant,
        faint = tokens.ink3,
        hairline = scheme.outlineVariant,
        blue = tokens.accent,
        blueContainer = scheme.primaryContainer,
        green = success,
        greenContainer = success.copy(alpha = 0.12f).compositeOver(scheme.surface),
        amber = warning,
        amberContainer = warning.copy(alpha = 0.12f).compositeOver(scheme.surface),
        red = danger,
        redContainer = danger.copy(alpha = 0.12f).compositeOver(scheme.surface),
    )
}

@Composable
fun workspaceBorder(alpha: Float = 1f): BorderStroke =
    workspaceColors().let { colors ->
        BorderStroke(1.dp, colors.hairline.copy(alpha = colors.hairline.alpha * alpha))
    }

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
    // Accent tone follows the active Amber accent instead of a fixed blue.
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        WorkspaceTone.Neutral -> colors.row to colors.muted
        WorkspaceTone.Accent -> scheme.primaryContainer to scheme.primary
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
            style = LocalAmberType.current.meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun WorkspaceLeadingIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    iconSize: Dp = 17.dp,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
) {
    val colors = workspaceColors()
    val scheme = MaterialTheme.colorScheme
    val tint = when (tone) {
        WorkspaceTone.Neutral -> colors.ink
        WorkspaceTone.Accent -> scheme.primary
        WorkspaceTone.Success -> colors.green
        WorkspaceTone.Warning -> colors.amber
        WorkspaceTone.Danger -> colors.red
    }
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(9.dp),
        color = when (tone) {
            WorkspaceTone.Neutral -> colors.row
            WorkspaceTone.Accent -> scheme.primaryContainer
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

@Composable
fun WorkspaceIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 18.dp,
    showBorder: Boolean = true,
    containerColor: Color? = null,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
    icon: ImageVector,
    contentDescription: String?,
) {
    val colors = workspaceColors()
    val scheme = MaterialTheme.colorScheme
    val contentColor = when (tone) {
        WorkspaceTone.Neutral -> colors.ink
        WorkspaceTone.Accent -> scheme.primary
        WorkspaceTone.Success -> colors.green
        WorkspaceTone.Warning -> colors.amber
        WorkspaceTone.Danger -> colors.red
    }.copy(alpha = if (enabled) 1f else 0.36f)
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription?.let { this.contentDescription = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(size),
            shape = RoundedCornerShape(12.dp),
            color = containerColor ?: if (tone == WorkspaceTone.Accent) scheme.primaryContainer else colors.paper,
            contentColor = contentColor,
            border = if (showBorder) workspaceBorder(alpha = if (enabled) 1f else 0.48f) else null,
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
}

@Composable
fun WorkspaceTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
) {
    val colors = workspaceColors()
    val scheme = MaterialTheme.colorScheme
    val (container, contentColor) = when (tone) {
        WorkspaceTone.Neutral -> colors.row to colors.muted
        WorkspaceTone.Accent -> scheme.primaryContainer to scheme.primary
        WorkspaceTone.Success -> colors.greenContainer to colors.green
        WorkspaceTone.Warning -> colors.amberContainer to colors.amber
        WorkspaceTone.Danger -> colors.redContainer to colors.red
    }
    Surface(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = container,
        contentColor = contentColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.13f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Shared 56dp header from the Android redesign, with native status-bar insets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val workspace = workspaceColors()
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = LocalAmberType.current.screenTitle,
                color = workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        expandedHeight = 56.dp,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = workspace.canvas,
            scrolledContainerColor = workspace.canvas,
            titleContentColor = workspace.ink,
            navigationIconContentColor = workspace.muted,
            actionIconContentColor = workspace.muted,
        ),
    )
}
