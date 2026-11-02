package app.amber.feature.ui.pages.chat

import app.amber.core.settings.Settings
import app.amber.core.service.ConversationTimelineLoadState
import app.amber.core.recap.RecapState
import app.amber.core.model.Conversation
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import app.amber.core.recap.freshnessForWindow
import app.amber.core.recap.isJumpableInWindow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Clock
import app.amber.agent.R
import app.amber.core.service.PendingUserMessage
import app.amber.core.service.PendingUserMessageDisplayCopy
import app.amber.core.service.PendingUserMessageMode
import app.amber.core.service.previewText
import app.amber.feature.ui.components.ui.WorkspaceIconButton
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceColors
import kotlin.uuid.Uuid

/** Collects the live conversation only while the recap panel is shown. */
@Composable
internal fun ChatRecapPanelHost(
    conversationFlow: StateFlow<Conversation>,
    recapStateFlow: StateFlow<RecapState>,
    timelineLoadState: StateFlow<ConversationTimelineLoadState>,
    onRefresh: () -> Unit,
    visible: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onJump: (TimelineJumpTarget) -> Unit,
    onUseNextStep: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val newSessionLabel = stringResource(R.string.chat_page_new_session)
    ChatTopPanel(
        visible = visible,
        onDismiss = onDismiss,
        transformOriginX = 0.5f,
        modifier = modifier,
    ) {
        val conversation by conversationFlow.collectAsStateWithLifecycle()
        val recapState by recapStateFlow.collectAsStateWithLifecycle()
        val freshness = recapState.recap?.freshnessForWindow(conversation)
        ChatRecapPanelContent(
            title = conversation.title.ifBlank { newSessionLabel },
            state = recapState,
            freshness = freshness,
            isJumpable = { node -> node.isJumpableInWindow(conversation, timelineLoadState.value.isFullyLoaded) },
            onRename = onRename,
            onRefresh = onRefresh,
            onJump = { node ->
                node.nodeId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                    ?.let { onJump(TimelineJumpTarget(it, node.messageId)) }
            },
            onUseNextStep = onUseNextStep,
        )
    }
}

/** 跨对话活动面板：复用顶栏面板外壳，按 kind 展示等待/失败/完成/运行。 */
@Composable
internal fun ChatActivityPanelHost(
    visible: Boolean,
    notices: List<app.amber.feature.runtime.ConversationActivityNotice>,
    onDismiss: () -> Unit,
    onOpen: (app.amber.feature.runtime.ConversationActivityNotice) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChatTopPanel(
        visible = visible,
        onDismiss = onDismiss,
        transformOriginX = 1f,
        modifier = modifier,
    ) {
        ChatActivityPanelContent(notices = notices, onOpen = onOpen)
    }
}

/**
 * Shelf items are derived only while the panel is shown, from the full conversation
 * loaded off the paged window (the timeline itself is not widened). They refresh when
 * a message is added or the newest message changes.
 */
@Composable
internal fun ChatShelfPanelHost(
    conversationFlow: StateFlow<Conversation>,
    settingsFlow: StateFlow<Settings>,
    loadFullConversation: suspend () -> Conversation,
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpen: (ShelfItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChatTopPanel(
        visible = visible,
        onDismiss = onDismiss,
        transformOriginX = 1f,
        modifier = modifier,
    ) {
        val refreshKeyFlow = remember(conversationFlow) {
            conversationFlow
                .map { conversation ->
                    conversation.messageNodes.size to conversation.messageNodes.lastOrNull()
                        ?.let { it.messages.getOrNull(it.selectIndex) }
                }
                .distinctUntilChanged { old, new -> old.first == new.first && old.second === new.second }
        }
        val refreshKey by refreshKeyFlow.collectAsStateWithLifecycle(initialValue = null)
        val settings by settingsFlow.collectAsStateWithLifecycle()
        val generativeUiSetting = settings.agentRuntime.generativeUi
        val items by produceState<List<ShelfItem>?>(initialValue = null, refreshKey, generativeUiSetting) {
            // Streaming replaces the newest message every flush; let bursts settle first.
            if (value != null) delay(400)
            value = runCatching {
                val full = loadFullConversation()
                withContext(Dispatchers.Default) { full.collectShelfItems(generativeUiSetting) }
            }.getOrElse { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                value.orEmpty()
            }
        }
        ChatShelfPanelContent(items = items, onOpen = onOpen)
    }
}

@Composable
internal fun UserMessageEditConfirmBar(
    generating: Boolean,
    onSaveOnly: () -> Unit,
    onSaveAndRegenerate: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    val workspace = workspaceColors()
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(if (generating) R.string.chat_page_edit_generating else R.string.edit),
            style = if (generating) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = workspace.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = true)
                .align(Alignment.CenterVertically),
        )
        TextButton(
            onClick = onCancelEdit,
            modifier = Modifier.align(Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.chat_page_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(
            onClick = onSaveOnly,
            enabled = !generating,
            modifier = Modifier.align(Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.chat_page_save_only), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(
            onClick = onSaveAndRegenerate,
            enabled = !generating,
            modifier = Modifier.align(Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.chat_page_save_and_regenerate), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun PendingUserMessageQueueDialog(
    messages: List<PendingUserMessage>,
    onDismiss: () -> Unit,
    onCancelMessage: (String) -> Unit,
    onMoveMessage: (String, Int) -> Unit,
    onClear: () -> Unit,
    onResumeQueue: () -> Unit,
    onMoveToInput: () -> Unit,
) {
    val workspace = workspaceColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_page_queue_title))
        },
        text = {
            if (messages.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_page_queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = workspace.muted,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    messages.forEachIndexed { index, message ->
                        PendingUserMessageQueueRow(
                            index = index,
                            total = messages.size,
                            message = message,
                            onCancel = { onCancelMessage(message.id) },
                            onMoveUp = { onMoveMessage(message.id, -1) },
                            onMoveDown = { onMoveMessage(message.id, 1) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            // P1-06: Stop 后会话进入 idle，队列保留；用户显式选择继续队列
            // 或把第一条移回输入框编辑。
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (messages.isNotEmpty()) {
                    TextButton(onClick = onMoveToInput) {
                        Text(stringResource(R.string.chat_page_queue_move_to_input))
                    }
                    TextButton(onClick = onResumeQueue) {
                        Text(stringResource(R.string.chat_page_queue_resume))
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_page_close))
                }
                if (messages.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }
        },
    )
}

@Composable
private fun PendingUserMessageQueueRow(
    index: Int,
    total: Int,
    message: PendingUserMessage,
    onCancel: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val context = LocalContext.current
    val workspace = workspaceColors()
    val modeLabel = when (message.mode) {
        PendingUserMessageMode.FOLLOWUP -> stringResource(R.string.chat_page_queue_mode_followup)
        PendingUserMessageMode.STEER -> stringResource(R.string.chat_page_queue_mode_steer)
        PendingUserMessageMode.COLLECT -> stringResource(R.string.chat_page_queue_mode_collect)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = workspace.paper,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, workspace.hairline),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "#${index + 1} · $modeLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = workspace.muted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onMoveUp,
                        enabled = index > 0,
                    ) {
                        Text(stringResource(R.string.chat_page_queue_move_up))
                    }
                    TextButton(
                        onClick = onMoveDown,
                        enabled = index < total - 1,
                    ) {
                        Text(stringResource(R.string.chat_page_queue_move_down))
                    }
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.chat_page_cancel))
                    }
                }
            }
            Text(
                text = message.previewText(
                    maxChars = 280,
                    copy = PendingUserMessageDisplayCopy.from(context),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = workspace.ink,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * P1-02 OutcomeUnknown prompt card — reuses the approval-card visual
 * language (warning pill + icon buttons). A non-idempotent tool effect whose
 * outcome was lost after an interruption needs an explicit user decision:
 * "确认重试" re-executes the tool; "放弃" writes a structured rejection and
 * continues the run.
 */
@Composable
internal fun OutcomeUnknownCard(
    prompt: app.amber.feature.runtime.OutcomeUnknownPrompt,
    onRetry: () -> Unit,
    onAbandon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    Surface(
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = workspace.row,
        contentColor = workspace.ink,
        border = androidx.compose.foundation.BorderStroke(1.dp, workspace.hairline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Lucide.Clock,
                contentDescription = null,
                tint = workspace.amber,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_page_tool_result_unknown),
                    style = MaterialTheme.typography.labelMedium,
                    color = workspace.ink,
                )
                Text(
                    text = stringResource(
                        R.string.chat_page_tool_result_unknown_detail,
                        prompt.toolName,
                        prompt.effectId.take(8),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WorkspaceIconButton(
                onClick = onAbandon,
                modifier = Modifier.size(48.dp),
                size = 28.dp,
                iconSize = 14.dp,
                tone = WorkspaceTone.Danger,
                icon = Lucide.X,
                contentDescription = stringResource(R.string.chat_page_abandon),
            )
            WorkspaceIconButton(
                onClick = onRetry,
                modifier = Modifier.size(48.dp),
                size = 28.dp,
                iconSize = 14.dp,
                tone = WorkspaceTone.Success,
                icon = Lucide.RefreshCw,
                contentDescription = stringResource(R.string.chat_page_confirm_retry),
            )
        }
    }
}
