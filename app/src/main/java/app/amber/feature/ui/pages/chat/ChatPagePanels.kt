package app.amber.feature.ui.pages.chat

import app.amber.core.settings.Settings
import app.amber.core.service.ConversationTimelineLoadState
import app.amber.core.recap.RecapState
import app.amber.core.model.Conversation
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
        ChatShelfPanelContent(items = items, onOpen = onOpen, onClose = onDismiss)
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
