package app.amber.feature.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import com.composables.icons.lucide.ArrowDownToLine
import com.composables.icons.lucide.ArrowUpToLine
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import app.amber.agent.R
import app.amber.core.context.ConversationCompact
import app.amber.core.settings.Settings
import app.amber.core.model.Conversation
import app.amber.feature.ui.components.ui.WorkspaceSearchField
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.core.utils.plus

@Composable
internal fun ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    contextCompacts: List<ConversationCompact> = emptyList(),
    settings: Settings,
    hazeState: HazeState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onJumpToMessage: (Int) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    // 过滤消息，同时保留原始 index 避免后续 O(n) indexOf 查找
    val filteredMessages = remember(conversation.messageNodes, searchQuery) {
        if (searchQuery.isBlank()) {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
        } else {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
                .filter { (_, node) -> node.currentMessage.toText().contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .padding(top = innerPadding.calculateTopPadding())
            .fillMaxSize(),
    ) {
        WorkspaceSearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = stringResource(R.string.history_page_search),
        )

        // 消息预览
        LazyColumn(
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { index, item -> item.second.id },
            ) { _, (originalIndex, node) ->
                val message = node.currentMessage
                val isUser = message.role == app.amber.ai.core.MessageRole.USER
                val workspace = workspaceColors()
                // Per-role light fill — user messages get the very-light blue
                // (blueContainer #EAF4FF), AI gets the very-light green
                // (greenContainer #EDF9EF). Border picks up the same hue at
                // ~22% alpha. Replaces the previous 3dp left-edge accent +
                // paper fill: a 3dp stripe was too subtle to read the role
                // at a glance.
                val previewContainer = if (isUser) workspace.blueContainer else workspace.greenContainer
                val previewBorder = if (isUser) {
                    workspace.blue.copy(alpha = 0.22f)
                } else {
                    workspace.green.copy(alpha = 0.22f)
                }
                Surface(
                    onClick = { onJumpToMessage(originalIndex) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    color = previewContainer,
                    contentColor = workspace.ink,
                    border = androidx.compose.foundation.BorderStroke(1.dp, previewBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                if (isUser) R.string.history_page_search_role_user
                                else R.string.history_page_search_role_assistant
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = workspace.muted,
                        )
                        val highlightColor = workspace.amberContainer
                        val highlightedText = remember(searchQuery, message) {
                            val fullText = message.toText().trim().ifBlank { "[...]" }
                            val messageText = extractMatchingSnippet(
                                text = fullText,
                                query = searchQuery
                            )
                            buildHighlightedText(
                                text = messageText,
                                query = searchQuery,
                                highlightColor = highlightColor
                            )
                        }
                        Text(
                            text = highlightedText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = workspace.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    suggestions: List<String>,
    onClickSuggestion: (String) -> Unit,
    onLongClickSuggestion: (String) -> Unit,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(
            items = suggestions,
            key = { it },
        ) { suggestion ->
            Surface(
                modifier = Modifier
                    .animateItem()
                    .clip(RoundedCornerShape(50))
                    .combinedClickable(
                        onClick = { onClickSuggestion(suggestion) },
                        onLongClick = { onLongClickSuggestion(suggestion) },
                    ),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ),
            ) {
                Text(
                    text = suggestion,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        )
    ) {
        // V3: 浮动导航卡。之前 border = outlineVariant@60% 在 Paper/Whisper 上看着是粗黑勾边,
        // 跟整体轻 hairline 体系冲突. 改 chatTheme.hair (8% ink) 极淡描边 + 抬升 shadow 到 8dp
        // 让它"浮"在 chat 上, 而不是靠 border 厚重感.
        val chatTheme = LocalChatTheme.current
        val dividerColor = chatTheme.hair
        val iconTint = chatTheme.inkSoft
        Surface(
            // width(IntrinsicSize.Min) is load-bearing here. Material3 HorizontalDivider
            // defaults to fillMaxWidth, which would otherwise propagate "fill the parent"
            // up through Column and stretch the floating card across the entire chat
            // area. Asking the Surface for its min-intrinsic width clamps Column to the
            // max of its children's minIntrinsicWidth — the 36dp IconButtons — which is
            // what we actually want the divider to inherit.
            modifier = Modifier
                .padding(8.dp)
                .width(IntrinsicSize.Min)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(12.dp),
                    clip = false,
                    ambientColor = chatTheme.composerShadow,
                    spotColor = chatTheme.composerShadow,
                ),
            shape = RoundedCornerShape(12.dp),
            color = chatTheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = chatTheme.surfaceEdge,
            ),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column {
                IconButton(
                    onClick = {
                        scope.launch {
                            state.scrollToItem(0)
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        // 跳到顶：arrow-up-to-line 用带横线的端点字形，比双 chevron 更
                        // 明确表达「到达边界」语义。
                        imageVector = Lucide.ArrowUpToLine,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                HorizontalDivider(
                    color = dividerColor,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            state.animateScrollToItem(
                                (state.firstVisibleItemIndex - 1).fastCoerceAtLeast(0)
                            )
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        // 上一条：chevron 无 stem 形态，与 ArrowUpToLine 区分「步进 vs 端点」
                        imageVector = Lucide.ChevronUp,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                HorizontalDivider(
                    color = dividerColor,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            state.animateScrollToItem(state.firstVisibleItemIndex + 1)
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Lucide.ChevronDown,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                HorizontalDivider(
                    color = dividerColor,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(
                    onClick = {
                        scope.launch {
                            state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Lucide.ArrowDownToLine,
                        contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                        tint = iconTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
