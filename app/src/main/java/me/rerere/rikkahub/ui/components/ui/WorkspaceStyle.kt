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

@Composable
fun workspaceColors(): WorkspaceColors {
    val scheme = MaterialTheme.colorScheme
    if (LocalAmoledDarkMode.current) {
        return WorkspaceColors(
            canvas = Color(0xFF000000),
            paper = Color(0xFF050505),
            row = Color(0xFF090909),
            note = Color(0xFF0D0D0D),
            ink = Color(0xFFF1F3F5),
            muted = Color(0xFFA7ABB2),
            faint = Color(0xFF666C74),
            hairline = Color(0xFF20242A),
            blue = Color(0xFF4EA6FF),
            blueContainer = Color(0xFF071B2E),
            green = Color(0xFF6DD58C),
            greenContainer = Color(0xFF071A10),
            amber = Color(0xFFE5B567),
            amberContainer = Color(0xFF1F170A),
            red = Color(0xFFFF8F86),
            redContainer = Color(0xFF21100F),
        )
    }
    return if (LocalDarkMode.current) {
        WorkspaceColors(
            canvas = scheme.surfaceContainerLowest,
            paper = scheme.surface,
            row = scheme.surfaceContainerLow,
            note = scheme.surfaceContainer,
            ink = scheme.onSurface,
            muted = scheme.onSurfaceVariant,
            faint = Color(0xFF70757E),
            hairline = Color(0xFF2B2F35),
            blue = Color(0xFF4EA6FF),
            blueContainer = Color(0xFF10263A),
            green = Color(0xFF6DD58C),
            greenContainer = Color(0xFF102A1A),
            amber = Color(0xFFE5B567),
            amberContainer = Color(0xFF352715),
            red = Color(0xFFFF8F86),
            redContainer = Color(0xFF3A1715),
        )
    } else {
        WorkspaceColors(
            canvas = Color(0xFFF7F7F5),
            paper = Color.White,
            row = Color(0xFFF7F7F5),
            note = Color(0xFFFBFBFA),
            ink = Color(0xFF1F1F1F),
            muted = Color(0xFF6B6761),
            faint = Color(0xFF9B9690),
            hairline = Color.Black.copy(alpha = 0.085f),
            blue = Color(0xFF2383E2),
            blueContainer = Color(0xFFEAF4FF),
            green = Color(0xFF168A2D),
            greenContainer = Color(0xFFEDF9EF),
            amber = Color(0xFFD37A00),
            amberContainer = Color(0xFFFFF4E8),
            red = Color(0xFFC5281C),
            redContainer = Color(0xFFFFEFED),
        )
    }
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
    Surface(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = containerColor ?: if (tone == WorkspaceTone.Accent) colors.blueContainer else colors.paper,
        contentColor = contentColor,
        border = if (showBorder) workspaceBorder(alpha = if (enabled) 1f else 0.48f) else null,
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

@Composable
fun WorkspaceTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
) {
    val colors = workspaceColors()
    val (container, contentColor) = when (tone) {
        WorkspaceTone.Neutral -> colors.row to colors.muted
        WorkspaceTone.Accent -> colors.blueContainer to colors.blue
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
