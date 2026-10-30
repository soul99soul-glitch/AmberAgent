package app.amber.feature.ui.pages.chat

import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessagePart
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.flow.collect
import app.amber.agent.BuildConfig
import app.amber.core.model.AssistantRegex
import app.amber.core.utils.ChatSendTransitionTracker
import app.amber.feature.ui.components.message.ChatMessageVirtualItem
import app.amber.feature.ui.utils.ChatPerfTrace
import java.util.Locale
import kotlin.uuid.Uuid

private const val CHAT_PERF_TAG = "AmberChatPerf"

@Composable
internal fun rememberChatTimelinePlan(
    conversation: Conversation,
    regexes: List<AssistantRegex>,
    showAssistantBubble: Boolean,
    loading: Boolean,
    activeGeneration: Boolean,
    hasHistoryLoadingItem: Boolean,
    pendingMessageCount: Int,
    listState: LazyListState? = null,
): ChatTimelinePlan {
    val virtualItemCache = remember(conversation.id) {
        ChatVirtualItemCache(parseMissingMarkdownSynchronously = false)
    }
    LaunchedEffect(virtualItemCache) {
        snapshotFlow { virtualItemCache.preparationRequests }.collect {
            virtualItemCache.preparePendingMarkdown()
        }
    }
    val planReuseCache = remember(conversation.id) { ChatTimelinePlanReuseCache() }
    val postSendState = remember(conversation.id, conversation.messageNodes, activeGeneration) {
        buildPostSendTimelineState(conversation, activeGeneration)
    }
    val timelineLoading = loading && !postSendState.waitingForAssistantContent
    val lastNode = conversation.messageNodes.lastOrNull()
    if (timelineLoading && lastNode?.currentMessage?.role == MessageRole.ASSISTANT) {
        virtualItemCache.observeStreamingAssistant(lastNode.id)
    }
    val protectedStreamingNodeIds = virtualItemCache.protectedStreamingNodeIds()
    val loadingNodeId = lastNode?.id?.takeIf { timelineLoading }
    val updatedLoadingNodeId by rememberUpdatedState(loadingNodeId)
    LaunchedEffect(virtualItemCache, listState) {
        if (listState != null) snapshotFlow {
            Triple(
                listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Uuid }.toSet(),
                updatedLoadingNodeId,
                virtualItemCache.activeVisualNodeIds(),
            )
        }.collect { (visibleIds, loadingId, _) ->
            if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                virtualItemCache.releaseCompletedOffscreen(visibleIds, loadingId)
            }
        }
    }
    return remember(
        conversation.messageNodes,
        regexes,
        showAssistantBubble,
        timelineLoading,
        hasHistoryLoadingItem,
        pendingMessageCount,
        postSendState,
        virtualItemCache,
        protectedStreamingNodeIds,
        virtualItemCache.preparedRevision,
        virtualItemCache.protectionRevision,
    ) {
        measureChatTimelinePlan(
            messageCount = conversation.messageNodes.size,
            cache = virtualItemCache,
        ) {
            planReuseCache.build(
                conversation = conversation,
                assistant = regexes,
                showAssistantBubble = showAssistantBubble,
                timelineLoading = timelineLoading,
                hasHistoryLoadingItem = hasHistoryLoadingItem,
                pendingMessageCount = pendingMessageCount,
                postSendState = postSendState,
                virtualItemCache = virtualItemCache,
                protectedStreamingNodeIds = protectedStreamingNodeIds,
            ).copy(visualActivityCache = virtualItemCache)
        }
    }
}

internal data class PostSendTimelineState(
    val sentUserMessageId: String?,
    val sentUserMessageIndex: Int?,
    val assistantMessageIndex: Int?,
    val hiddenAssistantMessageIndex: Int?,
    val waitingForAssistantContent: Boolean,
)

internal fun buildPostSendTimelineState(
    conversation: Conversation,
    activeGeneration: Boolean,
): PostSendTimelineState {
    val conversationId = conversation.id.toString()
    val latestMessage = conversation.messageNodes.lastOrNull()?.currentMessage
    val latestMessageId = latestMessage?.id?.toString()
    val latestIsPreSendTail = ChatSendTransitionTracker.isPreSendLatestMessage(
        conversationId = conversationId,
        messageId = latestMessageId,
    )
    val latestIsSentUserTail = latestMessage?.role == MessageRole.USER &&
        ChatSendTransitionTracker.isSentUserMessage(
            conversationId = conversationId,
            messageId = latestMessageId,
        )
    val tailWaitingForAssistant = activeGeneration && (latestIsPreSendTail || latestIsSentUserTail)
    val sentUserMessageId = ChatSendTransitionTracker.sentUserMessageId(conversationId)
    val sentUserMessageUuid = sentUserMessageId?.let { id ->
        // The old string comparison matched only the canonical spelling.
        runCatching { Uuid.parse(id) }.getOrNull()?.takeIf { it.toString() == id }
    }
    val sentUserMessageIndex = sentUserMessageUuid?.let { id ->
        conversation.messageNodes
            .indexOfLast { node -> node.currentMessage.id == id }
            .takeIf { it >= 0 }
    }
    val assistantMessageIndex = sentUserMessageIndex?.let { userIndex ->
        val assistantIndex = userIndex + 1
        val assistantNode = conversation.messageNodes.getOrNull(assistantIndex)
        assistantIndex.takeIf { assistantNode?.currentMessage?.role == MessageRole.ASSISTANT }
    }
    val assistantHasVisibleContent = assistantMessageIndex?.let { index ->
        conversation.messageNodes
            .getOrNull(index)
            ?.currentMessage
            ?.parts
            ?.hasVisibleTimelineContent() == true
    } == true
    val hiddenAssistantMessageIndex = assistantMessageIndex?.takeIf { index ->
        index == conversation.messageNodes.lastIndex && !assistantHasVisibleContent
    }
    val waitingForAssistantContent = activeGeneration &&
        sentUserMessageIndex != null &&
        !assistantHasVisibleContent &&
        (tailWaitingForAssistant || assistantMessageIndex != null)
    return PostSendTimelineState(
        sentUserMessageId = sentUserMessageId,
        sentUserMessageIndex = sentUserMessageIndex,
        assistantMessageIndex = assistantMessageIndex,
        hiddenAssistantMessageIndex = hiddenAssistantMessageIndex,
        waitingForAssistantContent = waitingForAssistantContent,
    )
}

internal data class ChatTimelinePlan(
    val entries: List<ChatTimelineEntry>,
    val lazyItemMessageIndexes: List<Int?>,
    private val lazyIndexByMessageIndex: Map<Int, Int>,
    val postSendState: PostSendTimelineState,
    val timelineLoading: Boolean,
    val visualActivityCache: ChatVirtualItemCache? = null,
) {
    fun messageIndexAt(lazyIndex: Int): Int? = lazyItemMessageIndexes.getOrNull(lazyIndex)

    /** Lazy index of a message's visual-bottom slice, for jump targets. */
    fun lazyIndexForMessage(messageIndex: Int): Int? = lazyIndexByMessageIndex[messageIndex]
}

internal sealed interface ChatTimelineEntry {
    val messageIndex: Int?

    data object HistoryLoading : ChatTimelineEntry {
        override val messageIndex: Int? = null
    }

    data class Message(
        override val messageIndex: Int,
        val node: MessageNode,
    ) : ChatTimelineEntry

    data class VirtualMessage(
        override val messageIndex: Int,
        val node: MessageNode,
        val item: ChatMessageVirtualItem,
        val virtualIndex: Int,
        val virtualCount: Int,
    ) : ChatTimelineEntry

    data class PostSendHiddenAssistant(
        override val messageIndex: Int,
        val node: MessageNode,
    ) : ChatTimelineEntry

    data object PostSendWaitingAssistant : ChatTimelineEntry {
        override val messageIndex: Int? = null
    }

    data class Pending(val pendingIndex: Int) : ChatTimelineEntry {
        override val messageIndex: Int? = null
    }

    /**
     * Tail compact divider markers anchored at the newest message. Always
     * present in the plan so lazy indexes stay 1:1 with emitted items — the
     * section renders a zero-size placeholder when no markers apply. Reading
     * order places it directly below the newest message (between it and the
     * post-send waiting / pending entries).
     */
    data object TailCompactMarkers : ChatTimelineEntry {
        override val messageIndex: Int? = null
    }

    data object TimelineTail : ChatTimelineEntry {
        override val messageIndex: Int? = null
    }
}

internal fun buildChatTimelinePlan(
    conversation: Conversation,
    assistant: List<AssistantRegex>?,
    showAssistantBubble: Boolean,
    timelineLoading: Boolean,
    hasHistoryLoadingItem: Boolean,
    pendingMessageCount: Int,
    postSendState: PostSendTimelineState,
    virtualItemCache: ChatVirtualItemCache,
    protectedStreamingNodeIds: Set<Uuid> = emptySet(),
    reusedPrefix: ChatTimelinePrefix? = null,
): ChatTimelinePlan {
    val regexes = assistant.orEmpty()
    // Built in reading order (oldest → newest, slices in reading order), then
    // reversed once at the end: the timeline LazyColumn uses reverseLayout, so
    // lazy index 0 must be the visual bottom (newest content). A flat reversal
    // flips both the inter-message order and the intra-message virtual slice
    // order in one step.
    val tailEntries = if (reusedPrefix == null) {
        buildList {
            if (hasHistoryLoadingItem) add(ChatTimelineEntry.HistoryLoading)
            conversation.messageNodes.forEachIndexed { index, node ->
                addTimelineMessageEntries(
                    index, node, conversation.messageNodes.lastIndex, regexes,
                    showAssistantBubble, timelineLoading, postSendState,
                    virtualItemCache, protectedStreamingNodeIds,
                )
            }
            addTimelineTrailingEntries(postSendState, pendingMessageCount)
        }.reversed()
    } else {
        buildList {
            addTimelineMessageEntries(
                conversation.messageNodes.lastIndex, conversation.messageNodes.last(),
                conversation.messageNodes.lastIndex, regexes, showAssistantBubble,
                timelineLoading, postSendState, virtualItemCache, protectedStreamingNodeIds,
            )
            addTimelineTrailingEntries(postSendState, pendingMessageCount)
        }.reversed()
    }
    val tailIndexes = tailEntries.map { entry -> entry.messageIndex }
    val entries = reusedPrefix?.let { JoinedTimelineList(tailEntries, it.entries) } ?: tailEntries
    val lazyItemMessageIndexes = reusedPrefix?.let { JoinedTimelineList(tailIndexes, it.messageIndexes) } ?: tailIndexes
    // First occurrence in reversed order = the message's reading-last slice
    // (visual bottom). scrollToItem under reverseLayout pins the target at the
    // viewport's bottom edge, so jumping there makes the message unfold upward
    // into view — visually equivalent to the old forward-layout jump to a
    // message's first item at the top edge.
    val tailLookup = buildMap {
        tailIndexes.forEachIndexed { lazyIndex, messageIndex ->
            if (messageIndex != null && !containsKey(messageIndex)) {
                put(messageIndex, lazyIndex)
            }
        }
    }
    return ChatTimelinePlan(
        entries = entries,
        lazyItemMessageIndexes = lazyItemMessageIndexes,
        lazyIndexByMessageIndex = reusedPrefix?.let {
            ShiftedTimelineLookup(tailLookup, it.lazyIndexes, tailEntries.size)
        } ?: tailLookup,
        postSendState = postSendState,
        timelineLoading = timelineLoading,
    )
}

/** Immutable history storage shared by all pure tail updates, without copy chains. */
internal data class ChatTimelinePrefix(
    val entries: List<ChatTimelineEntry>,
    val messageIndexes: List<Int?>,
    val lazyIndexes: Map<Int, Int>,
)

internal fun ChatTimelinePlan.historyPrefix(start: Int): ChatTimelinePrefix {
    val indexes = lazyItemMessageIndexes.subList(start, entries.size)
    val lookup = buildMap {
        indexes.forEachIndexed { index, message ->
            if (message != null && !containsKey(message)) put(message, index)
        }
    }
    return ChatTimelinePrefix(entries.subList(start, entries.size), indexes, lookup)
}

internal class JoinedTimelineList<T>(
    internal val tail: List<T>, internal val history: List<T>,
) : AbstractList<T>(), java.util.RandomAccess {
    override val size: Int = tail.size + history.size
    override fun get(index: Int): T {
        if (index !in indices) throw IndexOutOfBoundsException("$index / $size")
        return if (index < tail.size) tail[index] else history[index - tail.size]
    }
}

private class ShiftedTimelineLookup(
    private val tail: Map<Int, Int>,
    private val history: Map<Int, Int>,
    private val shift: Int,
) : AbstractMap<Int, Int>() {
    override fun get(key: Int): Int? = tail[key] ?: history[key]?.plus(shift)
    override val entries: Set<Map.Entry<Int, Int>> by lazy {
        buildMap {
            history.forEach { (message, index) -> put(message, index + shift) }
            putAll(tail)
        }.entries
    }
}

private fun MutableList<ChatTimelineEntry>.addTimelineMessageEntries(
    index: Int,
    node: MessageNode,
    lastIndex: Int,
    regexes: List<AssistantRegex>,
    showAssistantBubble: Boolean,
    timelineLoading: Boolean,
    postSendState: PostSendTimelineState,
    virtualItemCache: ChatVirtualItemCache,
    protectedStreamingNodeIds: Set<Uuid>,
) {
    val keepAsSingleItem = node.id in protectedStreamingNodeIds ||
        (index == lastIndex && index == postSendState.hiddenAssistantMessageIndex)
    if (index == postSendState.hiddenAssistantMessageIndex && !keepAsSingleItem) {
        add(ChatTimelineEntry.PostSendHiddenAssistant(index, node))
        return
    }
    val isLastMessage = index == lastIndex
    val isLoadingMessage = timelineLoading && isLastMessage
    val virtualItems = virtualItemCache.getOrBuild(
        node = node,
        regexes = regexes,
        showAssistantBubble = showAssistantBubble,
        loading = isLoadingMessage,
        lastMessage = isLastMessage,
        forceSingleItem = keepAsSingleItem,
    )
    if (virtualItems == null) {
        add(ChatTimelineEntry.Message(index, node))
    } else {
        virtualItems.forEachIndexed { virtualIndex, virtualItem ->
            add(
                ChatTimelineEntry.VirtualMessage(
                    messageIndex = index,
                    node = node,
                    item = virtualItem,
                    virtualIndex = virtualIndex,
                    virtualCount = virtualItems.size,
                )
            )
        }
    }
}

private fun MutableList<ChatTimelineEntry>.addTimelineTrailingEntries(
    postSendState: PostSendTimelineState,
    pendingMessageCount: Int,
) {
    // Reading order: below the newest message and above waiting / pending entries.
    add(ChatTimelineEntry.TailCompactMarkers)
    if (postSendState.waitingForAssistantContent && postSendState.assistantMessageIndex == null) {
        add(ChatTimelineEntry.PostSendWaitingAssistant)
    }
    repeat(pendingMessageCount) { index -> add(ChatTimelineEntry.Pending(index)) }
    add(ChatTimelineEntry.TimelineTail)
}

private inline fun measureChatTimelinePlan(
    messageCount: Int,
    cache: ChatVirtualItemCache,
    block: () -> ChatTimelinePlan,
): ChatTimelinePlan {
    val startedAt = System.nanoTime()
    val plan = block()
    val elapsedNanos = System.nanoTime() - startedAt
    ChatPerfTrace.recordPlanBuild(elapsedNanos / 1_000_000.0)
    if (BuildConfig.DEBUG) {
        val elapsedMs = elapsedNanos / 1_000_000.0
        if (elapsedMs >= 1.0 || messageCount >= 200) {
            Log.d(
                CHAT_PERF_TAG,
                "timelinePlan messages=$messageCount items=${plan.entries.size} " +
                    "elapsedMs=${String.format(Locale.US, "%.2f", elapsedMs)} " +
                    "cacheHits=${cache.hits} cacheMisses=${cache.misses}",
            )
        }
    }
    return plan
}

internal fun List<UIMessagePart>.hasVisibleTimelineContent(): Boolean {
    return any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image -> part.url.isNotBlank()
            is UIMessagePart.Document -> part.url.isNotBlank()
            is UIMessagePart.Video -> part.url.isNotBlank()
            is UIMessagePart.Audio -> part.url.isNotBlank()
            is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
            is UIMessagePart.Tool -> true
            else -> false
        }
    }
}
