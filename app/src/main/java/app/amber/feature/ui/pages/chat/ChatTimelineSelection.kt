package app.amber.feature.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.MousePointer2
import com.composables.icons.lucide.Check
import app.amber.core.model.Conversation
import app.amber.core.service.ConversationTimelineLoadState
import app.amber.feature.ui.components.ui.Tooltip
import kotlin.uuid.Uuid
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.layout.BoxScope
import kotlinx.coroutines.CoroutineScope
import androidx.compose.material3.Checkbox

internal class TimelineSelectionState(
    val selectedItems: SnapshotStateList<Uuid>,
    selectingState: MutableState<Boolean>,
    exportSheetState: MutableState<Boolean>,
    exportConversationState: MutableState<Conversation?>,
) {
    var selecting by selectingState
    var showExportSheet by exportSheetState
    var exportConversation by exportConversationState
    val selectedItemSet by derivedStateOf { selectedItems.toSet() }

    fun toggle(nodeId: Uuid) {
        if (!selectedItems.contains(nodeId)) selectedItems.add(nodeId) else selectedItems.remove(nodeId)
    }

    fun shareThrough(conversation: Conversation, index: Int) {
        selecting = true
        selectedItems.clear()
        selectedItems.addAll(conversation.messageNodes.take(index + 1).map { it.id })
    }
}

@Composable
internal fun rememberTimelineSelectionState(conversationId: Uuid): TimelineSelectionState {
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    val selecting = remember { mutableStateOf(false) }
    val showExportSheet = remember { mutableStateOf(false) }
    val exportConversation = remember(conversationId) { mutableStateOf<Conversation?>(null) }
    return remember(selectedItems, selecting, showExportSheet, exportConversation) {
        TimelineSelectionState(selectedItems, selecting, showExportSheet, exportConversation)
    }
}

@Composable
internal fun BoxScope.ChatTimelineSelectionControls(
    selection: TimelineSelectionState,
    conversation: Conversation,
    timelineLoadState: ConversationTimelineLoadState,
    scope: CoroutineScope,
    onEnsureTimelineLoaded: suspend () -> Conversation,
) {
    val currentConversationState by rememberUpdatedState(conversation)
    val timelineLoadStateState by rememberUpdatedState(timelineLoadState)
    val ensureTimelineLoadedState by rememberUpdatedState(onEnsureTimelineLoaded)
    // 完成选择
    AnimatedVisibility(
        visible = selection.selecting,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .offset(y = -TimelineSelectionToolbarOffset),
        enter = slideInVertically(
            initialOffsetY = { it * 2 },
        ),
        exit = slideOutVertically(
            targetOffsetY = { it * 2 },
        ),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
        ) {
            Tooltip(
                tooltip = {
                    Text("Clear selection")
                }
            ) {
                IconButton(
                    onClick = {
                        selection.selecting = false
                        selection.selectedItems.clear()
                    }
                ) {
                    Icon(Lucide.X, null)
                }
            }
            Tooltip(
                tooltip = {
                    Text("Select all")
                }
            ) {
                IconButton(
                    onClick = {
                        if (selection.selectedItems.isNotEmpty()) {
                            selection.selectedItems.clear()
                        } else {
                            scope.launch {
                                val fullConversation = ensureTimelineLoadedState()
                                selection.selectedItems.clear()
                                selection.selectedItems.addAll(fullConversation.messageNodes.map { it.id })
                            }
                        }
                    }
                ) {
                    Icon(Lucide.MousePointer2, null)
                }
            }
            Tooltip(
                tooltip = {
                    Text("Confirm")
                }
            ) {
                FilledIconButton(
                    onClick = {
                        scope.launch {
                            val source = if (
                                timelineLoadStateState.initialized &&
                                timelineLoadStateState.isFullyLoaded &&
                                timelineLoadStateState.oldestLoadedIndex == 0
                            ) {
                                currentConversationState
                            } else {
                                ensureTimelineLoadedState()
                            }
                            selection.selecting = false
                            selection.exportConversation = source
                            val messages = source.messageNodes.filter { it.id in selection.selectedItemSet }
                            if (messages.isNotEmpty()) {
                                selection.showExportSheet = true
                            }
                        }
                    }
                ) {
                    Icon(Lucide.Check, null)
                }
            }
        }
    }

    // 导出对话框
    ChatExportSheet(
        visible = selection.showExportSheet,
        onDismissRequest = {
            selection.showExportSheet = false
            selection.exportConversation = null
            selection.selectedItems.clear()
        },
        conversation = selection.exportConversation ?: conversation,
        selectedMessages = (selection.exportConversation ?: conversation).messageNodes.filter { it.id in selection.selectedItemSet }
            .map { it.currentMessage }
    )

}

@Composable
internal fun TimelineSelectableMessageItem(
    key: Uuid,
    selectedKeys: List<Uuid>,
    onSelectChange: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showCheckbox: Boolean = true,
    content: @Composable () -> Unit
) {
    if (app.amber.agent.BuildConfig.DEBUG) android.os.Trace.beginSection("Amber selectable item")
    if (!enabled) {
        // Normal reading mode: no checkbox/weight row. Wrapping every message
        // in an extra Row+Box was pure per-item layout/applyChanges cost.
        Box(modifier = modifier.fillMaxWidth()) { content() }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (showCheckbox) {
                Checkbox(
                    checked = key in selectedKeys,
                    onCheckedChange = {
                        onSelectChange(key)
                    }
                )
            } else {
                Spacer(modifier = Modifier.size(48.dp))
            }
            Box(
                modifier = Modifier.weight(1f)
            ) {
                content()
            }
        }
    }
    if (app.amber.agent.BuildConfig.DEBUG) android.os.Trace.endSection()
}
