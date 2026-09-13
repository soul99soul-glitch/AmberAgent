package app.amber.feature.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.feature.subagent.SubAgentRunStatus
import app.amber.feature.ui.components.ui.SubAgentAvatar
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.delay
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

/**
 * Cross-conversation subagent capsule rail, intended to be mounted immediately above the chat
 * composer. The state holder is process-wide; this composable owns only transient expansion and
 * sheet presentation state.
 */
@Composable
fun SubAgentStatusDock(
    currentConversationId: Uuid,
    modifier: Modifier = Modifier,
    onOpenConversation: (Uuid) -> Unit,
) {
    val state: SubAgentDockState = koinInject()
    val dockState by state.uiState.collectAsState()
    val tasks = dockState.tasks
    if (tasks.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<SubAgentDockRunKey?>(null) }
    val selected = tasks.firstOrNull { it.key == selectedKey }
    val nowMs = rememberSubAgentDockNow(tasks)
    val runningCount = tasks.count { it.status == SubAgentDockStatus.RUNNING }
    val otherConversationCount = tasks.count {
        it.sourceConversationId != null && it.sourceConversationId != currentConversationId
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalAmberTokens.current.bg)
            .padding(bottom = 7.dp),
    ) {
        SubAgentDockHeader(
            runningCount = runningCount,
            taskCount = tasks.size,
            otherConversationCount = otherConversationCount,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )

        if (expanded) {
            val visibleRows = minOf(3, (tasks.size + 1) / 2).coerceAtLeast(1)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .height((visibleRows * 56 + 8).dp)
                    .heightIn(max = 210.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 7.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(items = tasks, key = { it.key }) { task ->
                    SubAgentDockPill(
                        task = task,
                        nowMs = nowMs,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { selectedKey = task.key },
                    )
                }
            }
        } else {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems(items = tasks, key = { it.key }) { task ->
                    SubAgentDockPill(
                        task = task,
                        nowMs = nowMs,
                        modifier = Modifier.width(166.dp),
                        onClick = { selectedKey = task.key },
                    )
                }
            }
        }
    }

    selected?.let { task ->
        SubAgentDockDetailsSheet(
            task = task,
            nowMs = nowMs,
            onDismiss = { selectedKey = null },
            onOpenSourceConversation = { sourceConversationId ->
                selectedKey = null
                onOpenConversation(sourceConversationId)
            },
            onDismissTask = {
                state.dismiss(task.key)
                selectedKey = null
            },
        )
    }
}

@Composable
private fun SubAgentDockHeader(
    runningCount: Int,
    taskCount: Int,
    otherConversationCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val workspace = workspaceColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = if (runningCount > 0) {
                    stringResource(R.string.subagent_dock_header_running, runningCount)
                } else {
                    stringResource(R.string.subagent_dock_header_tasks, taskCount)
                },
                style = MaterialTheme.typography.labelMedium,
                color = workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (otherConversationCount > 0) {
                Text(
                    text = stringResource(
                        R.string.subagent_dock_header_other_conversations,
                        otherConversationCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = stringResource(
                if (expanded) R.string.subagent_dock_collapse else R.string.subagent_dock_expand,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = workspace.muted,
        )
        Icon(
            imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = workspace.muted,
        )
    }
}

@Composable
private fun SubAgentDockPill(
    task: SubAgentDockTask,
    nowMs: Long,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val workspace = workspaceColors()
    val pillShape = RoundedCornerShape(999.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .shadow(5.dp, pillShape),
        shape = pillShape,
        color = workspace.paper,
        contentColor = workspace.ink,
        border = BorderStroke(1.dp, workspace.hairline.copy(alpha = 0.48f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 9.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(workspace.row, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                SubAgentAvatar(
                    id = task.key.taskId,
                    name = task.title,
                    avatarSize = 28.dp,
                    status = task.status.toAvatarStatus(),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .background(task.status.dotColor(), CircleShape),
                    )
                    Text(
                        text = "${task.status.label()} · ${formatSubAgentDockElapsed(task.elapsedAt(nowMs))}",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        color = workspace.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubAgentDockDetailsSheet(
    task: SubAgentDockTask,
    nowMs: Long,
    onDismiss: () -> Unit,
    onOpenSourceConversation: (Uuid) -> Unit,
    onDismissTask: () -> Unit,
) {
    val workspace = workspaceColors()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.subagent_dock_details_title),
                style = MaterialTheme.typography.labelMedium,
                color = workspace.muted,
            )
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleLarge,
                color = workspace.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(task.status.dotColor(), CircleShape),
                )
                Text(
                    text = task.status.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = workspace.ink,
                )
                Text(
                    text = formatSubAgentDockElapsed(task.elapsedAt(nowMs)),
                    style = MaterialTheme.typography.labelMedium,
                    color = workspace.muted,
                )
            }
            task.sourceConversationId?.let { sourceConversationId ->
                FilledTonalButton(
                    onClick = { onOpenSourceConversation(sourceConversationId) },
                ) {
                    Icon(
                        imageVector = Lucide.MessageSquare,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.subagent_dock_source_conversation),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (task.status.canDismiss) {
                TextButton(onClick = onDismissTask) {
                    Text(stringResource(R.string.subagent_dock_dismiss))
                }
            }
        }
    }
}

@Composable
private fun SubAgentDockStatus.label(): String = when (this) {
    SubAgentDockStatus.QUEUED -> stringResource(R.string.subagent_dock_status_queued)
    SubAgentDockStatus.RUNNING -> stringResource(R.string.chat_message_subagent_status_working)
    SubAgentDockStatus.APPROVAL_REQUIRED -> stringResource(R.string.chat_message_subagent_status_waiting_approval)
    SubAgentDockStatus.COMPLETED -> stringResource(R.string.chat_message_subagent_status_completed)
    SubAgentDockStatus.FAILED -> stringResource(R.string.chat_message_subagent_status_failed)
    SubAgentDockStatus.CANCELLED -> stringResource(R.string.chat_message_subagent_status_cancelled)
    SubAgentDockStatus.TIMED_OUT -> stringResource(R.string.chat_message_subagent_status_timed_out)
    SubAgentDockStatus.INTERRUPTED -> stringResource(R.string.chat_message_subagent_status_interrupted)
}

@Composable
private fun SubAgentDockStatus.dotColor() = when (this) {
    SubAgentDockStatus.QUEUED -> workspaceColors().muted
    SubAgentDockStatus.RUNNING -> workspaceColors().blue
    SubAgentDockStatus.APPROVAL_REQUIRED -> workspaceColors().amber
    SubAgentDockStatus.COMPLETED -> workspaceColors().green
    SubAgentDockStatus.FAILED,
    SubAgentDockStatus.TIMED_OUT,
    -> workspaceColors().red
    SubAgentDockStatus.CANCELLED,
    SubAgentDockStatus.INTERRUPTED,
    -> workspaceColors().muted
}

private fun SubAgentDockStatus.toAvatarStatus(): SubAgentRunStatus? = when (this) {
    SubAgentDockStatus.QUEUED -> null
    SubAgentDockStatus.RUNNING -> SubAgentRunStatus.RUNNING
    SubAgentDockStatus.APPROVAL_REQUIRED -> SubAgentRunStatus.APPROVAL_REQUIRED
    SubAgentDockStatus.COMPLETED -> SubAgentRunStatus.COMPLETED
    SubAgentDockStatus.FAILED -> SubAgentRunStatus.FAILED
    SubAgentDockStatus.CANCELLED -> SubAgentRunStatus.CANCELLED
    SubAgentDockStatus.TIMED_OUT -> SubAgentRunStatus.TIMED_OUT
    SubAgentDockStatus.INTERRUPTED -> SubAgentRunStatus.INTERRUPTED
}

@Composable
private fun rememberSubAgentDockNow(tasks: List<SubAgentDockTask>): Long {
    val liveKeys = remember(tasks) {
        tasks.filter { it.status.keepsDockVisible }.map { it.key to it.status }
    }
    var nowMs by remember(liveKeys) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(liveKeys) {
        if (liveKeys.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    return nowMs
}

internal fun formatSubAgentDockElapsed(elapsedMs: Long): String {
    val seconds = (elapsedMs.coerceAtLeast(0L) / 1_000L)
    return when {
        seconds < 60L -> "${seconds}s"
        seconds < 3_600L -> "${seconds / 60L}m ${seconds % 60L}s"
        else -> "${seconds / 3_600L}h ${(seconds % 3_600L) / 60L}m"
    }
}
