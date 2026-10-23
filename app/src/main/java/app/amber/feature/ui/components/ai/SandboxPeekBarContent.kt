package app.amber.feature.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Clock
import app.amber.agent.R
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import app.amber.feature.ui.components.ui.workspaceColors

@Composable
internal fun AgentOperationPreviewPeek(
    activity: SandboxActivityUiState,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewUrl = activity.operationPreviewUrl()
    val workspace = workspaceColors()
    // V3 convo-tool-result.jsx PreviewThumb: 8dp 圆角 + previewBg surface + 1dp hair edge + 单层柔影 0.06
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onOpen() },
        shape = RoundedCornerShape(8.dp),
        color = workspace.paper,
        contentColor = workspace.ink,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        if (previewUrl != null) {
            WebOperationPreviewThumbnail(
                url = previewUrl,
                toolCallId = activity.toolCallId,
                live = activity.status == ToolActivityStatus.RUNNING ||
                    activity.status == ToolActivityStatus.WAITING_FOR_PERMISSION,
                onOpen = onOpen,
            )
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = activity.operationPreviewKind(),
                    color = workspace.amber,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (activity.toolName == "agent_idle") {
                        stringResource(R.string.chat_preview_standby)
                    } else activity.operationPreviewText(context),
                    color = workspace.muted,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun SandboxStepPeek(
    activity: SandboxActivityUiState,
    onOpen: () -> Unit,
    onCancel: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val workspace = workspaceColors()
    val theme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    // V3 convo-tool-result.jsx ResultPill spec:
    //   高度 22dp (3dp 上下 padding + 16dp leading badge)
    //   999 圆角 fillMaxWidth + toolPillBg + 1dp toolPillEdge
    //   inline "tool · query" 11.5sp letter 0.2 W500/W400
    //   右侧 9dp chevrons + 10.5sp tabular-nums
    Surface(
        modifier = modifier
            .clip(CircleShape)
            .clickable { onOpen() },
        shape = CircleShape,
        color = theme.toolPillBg,
        contentColor = workspace.ink,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, theme.toolPillEdge),
    ) {
        Row(
            modifier = Modifier.padding(start = 3.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SandboxStepStatusIcon(
                status = activity.status,
                statusDescription = if (activity.toolName == "agent_idle") stringResource(R.string.chat_preview_standby) else null,
            )
            Text(
                text = activity.title,
                modifier = Modifier.weight(1f),
                fontSize = 10.5.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                letterSpacing = 0.2.sp,
                color = theme.toolLabelInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onCancel != null) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(18.dp),
                ) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = stringResource(R.string.stop),
                        tint = workspace.amber,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SandboxStepArrow(
                    enabled = onPrevious != null,
                    onClick = onPrevious,
                    left = true,
                )
                Text(
                    text = activity.stepProgressText(context),
                    fontSize = 9.5.sp,
                    letterSpacing = 0.4.sp,
                    color = theme.inkSoft,
                    maxLines = 1,
                )
                SandboxStepArrow(
                    enabled = onNext != null,
                    onClick = onNext,
                    left = false,
                )
            }
        }
    }
}

@Composable
internal fun SandboxStepArrow(
    enabled: Boolean,
    onClick: (() -> Unit)?,
    left: Boolean,
) {
    val theme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    // V3 spec: 9dp chevrons stroke 2.4 inkSoft
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (left) Lucide.ArrowLeft else Lucide.ArrowRight,
            contentDescription = null,
            modifier = Modifier.size(9.dp),
            tint = theme.inkSoft.copy(alpha = if (enabled) 1f else 0.28f),
        )
    }
}

@Composable
internal fun SandboxStepStatusIcon(status: ToolActivityStatus, statusDescription: String? = null) {
    // V3 ResultPill spec: 16dp accent 实心圆 + 10dp 白勾。失败/取消保留状态色映射。
    val context = LocalContext.current
    val theme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    val workspace = workspaceColors()
    val (bg, ink) = when (status) {
        ToolActivityStatus.SUCCEEDED -> theme.toolDoneBg to theme.toolDoneBadgeInk
        ToolActivityStatus.FAILED -> workspace.red to Color.White
        ToolActivityStatus.TIMED_OUT -> workspace.red to Color.White
        ToolActivityStatus.INTERRUPTED -> workspace.amber to Color(0xFF1B1C20)
        ToolActivityStatus.CANCELLED -> workspace.muted to Color.White
        else -> theme.toolDoneBg to theme.toolDoneBadgeInk // RUNNING / WAITING 用 accent 表示进行中
    }
    Surface(
        modifier = Modifier
            .size(16.dp)
            .semantics { stateDescription = statusDescription ?: sandboxStatusLabel(context, status) },
        shape = CircleShape,
        color = bg,
        contentColor = ink,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when (status) {
                    ToolActivityStatus.SUCCEEDED -> Lucide.Check
                    ToolActivityStatus.FAILED,
                    ToolActivityStatus.TIMED_OUT,
                    ToolActivityStatus.CANCELLED -> Lucide.X
                    ToolActivityStatus.INTERRUPTED -> Lucide.Clock
                    else -> Lucide.Check // 运行中也显示勾 (表示已开始/在进行)
                },
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}
