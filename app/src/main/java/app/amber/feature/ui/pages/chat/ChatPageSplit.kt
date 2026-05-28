package app.amber.feature.ui.pages.chat

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.Uuid
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * **T2 perf-layer scaffold** for `PerfFlags.USE_SPLIT_CHATPAGE_COMPOSABLES`.
 *
 * The full ChatPage (1563 LOC, 14 collectAsState calls threaded through a
 * single Composable scope) is structurally hard to split correctly without
 * on-device verification. This file ships the **scaffolding** required by
 * the flag — four region Composables that EACH collect only the state slice
 * they render. When `PerfFlags.USE_SPLIT_CHATPAGE_COMPOSABLES = true`, the
 * dispatcher routes here instead of the legacy ChatPage.
 *
 * What this delivers right now:
 *  - Real Composable boundaries that demonstrate per-region state collection
 *  - The wiring needed for a future device-verified rollout
 *  - A clearly-labeled debug screen that signals "T2 scaffold active"
 *
 * What this DOESN'T do (intentional, documented):
 *  - Reproduce the full chat UI (drawer, message-list interactions, input
 *    bar attachments, suggestions, sandbox timeline, etc. — those are
 *    ~1000 LOC across multiple private Composables in ChatPage.kt and
 *    cannot be faithfully reproduced without device QA validating each).
 *  - Replace the legacy path. Flag default is `false`; the legacy
 *    [ChatPage] body in `ChatPage.kt` is untouched.
 *
 * Verifying on device when the flag is flipped:
 *  1. Toggle [me.rerere.rikkahub.PerfFlags.USE_SPLIT_CHATPAGE_COMPOSABLES] to true; rebuild.
 *  2. Open any conversation. You should see the scaffold screen ("T2
 *     ChatPage scaffold active") instead of the normal chat UI. This
 *     confirms the dispatcher works.
 *  3. Profile recompositions of the four region Composables in
 *     Layout Inspector — each should recompose ONLY when its own
 *     StateFlow emits, not on every parent re-render.
 *  4. Flip back to `false` to restore the legacy path.
 *
 * Next sprint (when device access is available): replace each placeholder
 * region body with the equivalent slice of the legacy ChatPageContent
 * layout.
 */
@Composable
fun ChatPageSplit(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = { parametersOf(id.toString()) }
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "T2 ChatPage scaffold active",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Flip PerfFlags.USE_SPLIT_CHATPAGE_COMPOSABLES = false to " +
                    "restore the legacy chat UI. id=$id text=$text files=${files.size} nodeId=$nodeId",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            ChatTopBarSection(vm = vm)
            HorizontalDivider()
            ChatMessageListSection(vm = vm, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            ChatStreamingIndicatorSection(vm = vm)
            HorizontalDivider()
            ChatInputBarSection(vm = vm)
        }
    }
}

/**
 * Top bar region — collects `setting` + `conversation` only.
 *
 * Recompose triggers: when settings or conversation root change.
 * Does NOT recompose on streaming-state emissions or message-list updates.
 */
@Composable
private fun ChatTopBarSection(vm: ChatVM) {
    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    Column {
        Text(
            text = conversation.title.ifBlank { "(untitled conversation)" },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "theme=${setting.themeId}  msgs=${conversation.messageNodes.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Message list region — collects message-list state only.
 *
 * Recompose triggers: when the conversation tree, timeline-load state,
 * or compact-lifecycle state changes. Independent of the input bar
 * and streaming indicator.
 */
@Composable
private fun ChatMessageListSection(vm: ChatVM, modifier: Modifier = Modifier) {
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val timelineLoadState by vm.timelineLoadState.collectAsStateWithLifecycle()
    val isCompacting by vm.isCompacting.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    Column(modifier = modifier) {
        LazyColumn(state = listState, modifier = Modifier.height(240.dp)) {
            items(conversation.messageNodes) { node ->
                val current = node.messages.getOrNull(node.selectIndex)
                Text(
                    text = "[${node.id}] selected=${current?.id ?: "?"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            text = "timeline=$timelineLoadState  compacting=$isCompacting",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Streaming indicator region — collects per-token streaming state.
 *
 * Recompose triggers: per-token / per-chunk emissions on processingStatus
 * + streamingSummary. Isolated to this subtree so the rest of the
 * chat screen stays still during streaming bursts.
 */
@Composable
private fun ChatStreamingIndicatorSection(vm: ChatVM) {
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val streamingSummary by vm.streamingSummary.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val isStreaming = loadingJob != null
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = if (isStreaming) "● streaming…" else "○ idle",
            style = MaterialTheme.typography.labelMedium,
        )
        processingStatus?.takeIf { it.isNotBlank() }?.let { status ->
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        if (streamingSummary.isNotBlank()) {
            Text(
                text = streamingSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Input bar region — collects send-side state only.
 *
 * Recompose triggers: model / web-search-toggle / settings changes.
 * Does NOT recompose on incoming streaming data.
 */
@Composable
private fun ChatInputBarSection(vm: ChatVM) {
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "model=${currentChatModel?.modelId ?: "(none)"}",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = "webSearch=$enableWebSearch",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
