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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        // V3 修复：浅色分支镜像深色分支，从 MaterialTheme.colorScheme 读取核心 surface/text。
        // Theme.kt 已经把 colorScheme 按 chatTheme override (background=chatTheme.bg,
        // surface=chatTheme.paper, surfaceContainerLowest/Low/Mid/High/Highest 全套)，
        // 所以二级页面（settings / providers / history）自动跟随 Whisper/Plain/Paper/Midnight。
        // 之前硬编码白底导致用户切到 Paper 时 settings 页仍是白底+灰，体感不一致。
        WorkspaceColors(
            canvas = scheme.surfaceContainerLowest,
            paper = scheme.surface,
            row = scheme.surfaceContainerLow,
            note = scheme.surfaceContainer,
            ink = scheme.onSurface,
            muted = scheme.onSurfaceVariant,
            faint = Color(0xFF9B9690),                       // 半灰，跨主题保持
            hairline = scheme.outlineVariant,                // chatTheme.outlineSoft (12% ink)
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
    // V3: Accent tone 跟主题 accent（Paper 砖红 / Plain 黑 / Midnight 靛蓝），不再硬蓝
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
        shape = RoundedCornerShape(6.dp),
        color = when (tone) {
            WorkspaceTone.Neutral -> Color.Transparent
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
    size: Dp = 32.dp,
    iconSize: Dp = 15.dp,
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
    Surface(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = containerColor ?: if (tone == WorkspaceTone.Accent) scheme.primaryContainer else colors.paper,
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

/**
 * V3 settings-shell.jsx SubHeader 设计稿：
 *   - title 22sp W500 ink letterSpacing 0.3 lineHeight 1
 *   - 14dp 上下 padding + 18dp 左右 padding (TopAppBar 默认就是 64dp 高，刚好近似)
 *   - 32dp chevron-back box 内 22dp icon stroke 1.6
 *   - 无 elevation (M3 TopAppBar 默认就是 0)
 *
 * 对外暴露与 [TopAppBar] 一致的 nav/actions/scrollBehavior 接口，
 * 调用方仅传 title 字符串即可统一字号字重；样式 paddings/elevation 走 M3 默认 (已对齐 spec)。
 */
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
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
                lineHeight = 22.sp,
                color = workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = workspace.canvas,
            scrolledContainerColor = workspace.canvas,
            titleContentColor = workspace.ink,
            navigationIconContentColor = workspace.muted,
            // V3: action icon 跟主题 accent (Paper 砖红 / Whisper 天蓝 等). 之前硬 workspace.blue.
            actionIconContentColor = MaterialTheme.colorScheme.primary,
        ),
    )
}
