package app.amber.feature.ui.pages.chat

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessagePart
import app.amber.agent.BuildConfig
import app.amber.core.model.Assistant
import app.amber.core.model.AssistantAffectScope
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.feature.ui.components.message.ChatMessageVirtualItem
import app.amber.feature.ui.components.message.MessageRenderCache
import app.amber.feature.ui.components.message.buildChatMessageVirtualItems
import app.amber.core.utils.ChatSendTransitionTracker
import java.util.Locale
import kotlin.uuid.Uuid

private const val CHAT_TIMELINE_PLAN_CACHE_MAX_ENTRIES = 2048
private const val CHAT_TIMELINE_PLAN_CACHE_MAX_MARKDOWN_CHARS = 1_200_000
private const val CHAT_PERF_TAG = "AmberChatPerf"

/**
 * 提取包含搜索词的文本片段，确保匹配词在开头可见
 */
internal fun extractMatchingSnippet(
    text: String,
    query: String
): String {
    if (query.isBlank()) {
        return text
    }

    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) {
        return text
    }

    // 直接从匹配词开始显示，确保匹配词在最前面
    val snippet = text.substring(matchIndex)

    // 只在前面有内容时添加省略号
    return if (matchIndex > 0) {
        "...$snippet"
    } else {
        snippet
    }
}

internal fun LazyListState.isNearListEnd(bufferItems: Int = 2): Boolean {
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return true
    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return true
    return lastVisibleIndex >= totalItems - 1 - bufferItems
}

internal fun LazyListState.isAtTimelineBottom(bufferPx: Int = 0): Boolean {
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return true
    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    if (lastVisibleItem.index < totalItems - 1) return false
    val contentBottom = lastVisibleItem.offset + lastVisibleItem.size + layoutInfo.afterContentPadding
    return contentBottom <= layoutInfo.viewportEndOffset + bufferPx
}

internal fun Conversation.latestRenderToken(): String {
    val message = messageNodes.lastOrNull()?.currentMessage ?: return "${messageNodes.size}:empty"
    val part = message.parts.lastOrNull()
    return buildString {
        append(messageNodes.size)
        append(':')
        append(message.id)
        append(':')
        append(message.parts.size)
        append(':')
        append(part?.compactRenderToken().orEmpty())
    }
}

private fun UIMessagePart.compactRenderToken(): String = when (this) {
    is UIMessagePart.Text -> "text:${text.length}:${text.takeLast(16)}"
    is UIMessagePart.Reasoning -> "reasoning:${reasoning.length}:${finishedAt != null}"
    is UIMessagePart.Tool -> {
        val outputToken = output.lastOrNull()?.compactRenderToken().orEmpty()
        "tool:$toolCallId:$toolName:$isExecuted:${approvalState.compactRenderToken()}:${output.size}:$outputToken"
    }

    is UIMessagePart.Image -> "image:${url.length}:${metadata.hashCode()}"
    is UIMessagePart.Video -> "video:${url.length}:${metadata.hashCode()}"
    is UIMessagePart.Audio -> "audio:${url.length}:${metadata.hashCode()}"
    is UIMessagePart.Document -> "document:$fileName:${url.length}:${metadata.hashCode()}"
    is UIMessagePart.MiniApp -> "mini_app:$appId:$version:${htmlHash.orEmpty()}:${metadata.hashCode()}"
}

private fun ToolApprovalState.compactRenderToken(): String = when (this) {
    ToolApprovalState.Auto -> "auto"
    ToolApprovalState.Pending -> "pending"
    ToolApprovalState.Approved -> "approved"
    is ToolApprovalState.Denied -> "denied:${reason.length}"
    is ToolApprovalState.Answered -> "answered:${answer.length}:${answer.takeLast(16)}"
}

internal fun LazyListState.markdownPrewarmTexts(
    messageNodes: List<MessageNode>,
    assistant: Assistant?,
    loadingLastMessage: Boolean,
    timelinePlan: ChatTimelinePlan,
): List<String> {
    if (messageNodes.isEmpty()) return emptyList()
    val visibleMessageIndexes = layoutInfo.visibleItemsInfo
        .mapNotNull { timelinePlan.messageIndexAt(it.index) }
        .filter { it in messageNodes.indices }
        .distinct()
    if (visibleMessageIndexes.isEmpty()) return emptyList()

    val start = (visibleMessageIndexes.minOrNull()!! - MarkdownPrewarmBeforeItems)
        .coerceAtLeast(0)
    val end = (visibleMessageIndexes.maxOrNull()!! + MarkdownPrewarmAfterItems)
        .coerceAtMost(messageNodes.lastIndex)

    return buildList {
        for (index in start..end) {
            if (loadingLastMessage && index == messageNodes.lastIndex) continue
            addAll(messageNodes[index].markdownPrewarmTexts(assistant))
            if (size >= MarkdownPrewarmMaxTexts) break
        }
    }.take(MarkdownPrewarmMaxTexts)
}

@Composable
internal fun rememberChatTimelinePlan(
    conversation: Conversation,
    assistant: Assistant?,
    showAssistantBubble: Boolean,
    loading: Boolean,
    activeGeneration: Boolean,
    hasHistoryLoadingItem: Boolean,
    pendingMessageCount: Int,
): ChatTimelinePlan {
    val virtualItemCache = remember(conversation.id) { ChatVirtualItemCache() }
    val postSendState = remember(conversation.id, conversation.messageNodes, activeGeneration) {
        buildPostSendTimelineState(conversation, activeGeneration)
    }
    val timelineLoading = loading && !postSendState.waitingForAssistantContent
    return remember(
        conversation.messageNodes,
        assistant,
        showAssistantBubble,
        timelineLoading,
        hasHistoryLoadingItem,
        pendingMessageCount,
        postSendState,
        virtualItemCache,
    ) {
        measureChatTimelinePlan(
            messageCount = conversation.messageNodes.size,
            cache = virtualItemCache,
        ) {
            buildChatTimelinePlan(
                conversation = conversation,
                assistant = assistant,
                showAssistantBubble = showAssistantBubble,
                timelineLoading = timelineLoading,
                hasHistoryLoadingItem = hasHistoryLoadingItem,
                pendingMessageCount = pendingMessageCount,
                postSendState = postSendState,
                virtualItemCache = virtualItemCache,
            )
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
    val sentUserMessageIndex = sentUserMessageId?.let { id ->
        conversation.messageNodes
            .indexOfFirst { node -> node.currentMessage.id.toString() == id }
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
    private val firstLazyIndexByMessageIndex: Map<Int, Int>,
    val postSendState: PostSendTimelineState,
    val timelineLoading: Boolean,
) {
    val lastIndex: Int get() = entries.lastIndex

    fun messageIndexAt(lazyIndex: Int): Int? = lazyItemMessageIndexes.getOrNull(lazyIndex)

    fun firstLazyIndexForMessage(messageIndex: Int): Int? = firstLazyIndexByMessageIndex[messageIndex]
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

    data object Loading : ChatTimelineEntry {
        override val messageIndex: Int? = null
    }

    data object ScrollBottom : ChatTimelineEntry {
        override val messageIndex: Int? = null
    }
}

internal class ChatVirtualItemCache(
    private val maxEntries: Int = CHAT_TIMELINE_PLAN_CACHE_MAX_ENTRIES,
    private val maxMarkdownChars: Int = CHAT_TIMELINE_PLAN_CACHE_MAX_MARKDOWN_CHARS,
) {
    private val entries = object : LinkedHashMap<ChatVirtualItemCacheKey, List<ChatMessageVirtualItem>?>(
        maxEntries.coerceAtLeast(1),
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ChatVirtualItemCacheKey, List<ChatMessageVirtualItem>?>,
        ): Boolean = false
    }
    private var totalMarkdownChars: Int = 0
    var hits: Int = 0
        private set
    var misses: Int = 0
        private set

    fun getOrBuild(
        node: MessageNode,
        assistant: Assistant?,
        showAssistantBubble: Boolean,
        loading: Boolean,
        lastMessage: Boolean,
    ): List<ChatMessageVirtualItem>? {
        val key = ChatVirtualItemCacheKey.of(
            node = node,
            assistant = assistant,
            showAssistantBubble = showAssistantBubble,
            loading = loading,
            lastMessage = lastMessage,
        )
        if (entries.containsKey(key)) {
            hits++
            return entries[key]
        }
        misses++
        val value = buildChatMessageVirtualItems(
            node = node,
            assistant = assistant,
            showAssistantBubble = showAssistantBubble,
            loading = loading,
            lastMessage = lastMessage,
        )
        val markdownChars = value.markdownCharCost()
        if (markdownChars > maxMarkdownChars) {
            return value
        }
        entries[key] = value
        totalMarkdownChars += markdownChars
        trimToBudget()
        return value
    }

    private fun trimToBudget() {
        while (
            entries.size > maxEntries ||
            totalMarkdownChars > maxMarkdownChars
        ) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next()
            totalMarkdownChars -= eldest.value.markdownCharCost()
            iterator.remove()
        }
    }
}

private fun List<ChatMessageVirtualItem>?.markdownCharCost(): Int {
    if (this == null) return 0
    var cost = 0
    val seen = HashSet<String>()
    for (item in this) {
        if (item is ChatMessageVirtualItem.MarkdownChild && seen.add(item.content)) {
            cost += item.content.length
        }
    }
    return cost
}

private data class ChatVirtualItemCacheKey(
    val nodeId: Uuid,
    val messageId: Uuid,
    val messageIdentity: Int,
    val partsIdentity: Int,
    val assistantSignature: Int,
    val showAssistantBubble: Boolean,
    val loading: Boolean,
    val lastMessage: Boolean,
) {
    companion object {
        fun of(
            node: MessageNode,
            assistant: Assistant?,
            showAssistantBubble: Boolean,
            loading: Boolean,
            lastMessage: Boolean,
        ): ChatVirtualItemCacheKey {
            val message = node.currentMessage
            return ChatVirtualItemCacheKey(
                nodeId = node.id,
                messageId = message.id,
                messageIdentity = System.identityHashCode(message),
                partsIdentity = System.identityHashCode(message.parts),
                assistantSignature = assistant.renderSignature(),
                showAssistantBubble = showAssistantBubble,
                loading = loading,
                lastMessage = lastMessage,
            )
        }
    }
}

private fun Assistant?.renderSignature(): Int {
    if (this == null) return 0
    var result = id.hashCode()
    regexes.forEach { regex ->
        result = 31 * result + regex.id.hashCode()
        result = 31 * result + regex.enabled.hashCode()
        result = 31 * result + regex.findRegex.hashCode()
        result = 31 * result + regex.replaceString.hashCode()
        result = 31 * result + regex.affectingScope.hashCode()
        result = 31 * result + regex.visualOnly.hashCode()
    }
    return result
}

internal fun buildChatTimelinePlan(
    conversation: Conversation,
    assistant: Assistant?,
    showAssistantBubble: Boolean,
    timelineLoading: Boolean,
    hasHistoryLoadingItem: Boolean,
    pendingMessageCount: Int,
    postSendState: PostSendTimelineState,
    virtualItemCache: ChatVirtualItemCache,
): ChatTimelinePlan {
    val entries = buildList {
        if (hasHistoryLoadingItem) add(ChatTimelineEntry.HistoryLoading)
        conversation.messageNodes.forEachIndexed { index, node ->
            if (index == postSendState.hiddenAssistantMessageIndex) {
                add(ChatTimelineEntry.PostSendHiddenAssistant(index, node))
                return@forEachIndexed
            }
            val isLastMessage = index == conversation.messageNodes.lastIndex
            val isLoadingMessage = timelineLoading && isLastMessage
            val virtualItems = virtualItemCache.getOrBuild(
                node = node,
                assistant = assistant,
                showAssistantBubble = showAssistantBubble,
                loading = isLoadingMessage,
                lastMessage = isLastMessage,
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
        if (postSendState.waitingForAssistantContent && postSendState.assistantMessageIndex == null) {
            add(ChatTimelineEntry.PostSendWaitingAssistant)
        }
        repeat(pendingMessageCount) { index ->
            add(ChatTimelineEntry.Pending(index))
        }
        if (timelineLoading) add(ChatTimelineEntry.Loading)
        add(ChatTimelineEntry.ScrollBottom)
    }
    val lazyItemMessageIndexes = entries.map { entry -> entry.messageIndex }
    val firstLazyIndexByMessageIndex = buildMap {
        lazyItemMessageIndexes.forEachIndexed { lazyIndex, messageIndex ->
            if (messageIndex != null && !containsKey(messageIndex)) {
                put(messageIndex, lazyIndex)
            }
        }
    }
    return ChatTimelinePlan(
        entries = entries,
        lazyItemMessageIndexes = lazyItemMessageIndexes,
        firstLazyIndexByMessageIndex = firstLazyIndexByMessageIndex,
        postSendState = postSendState,
        timelineLoading = timelineLoading,
    )
}

private inline fun measureChatTimelinePlan(
    messageCount: Int,
    cache: ChatVirtualItemCache,
    block: () -> ChatTimelinePlan,
): ChatTimelinePlan {
    val startedAt = if (BuildConfig.DEBUG) System.nanoTime() else 0L
    val plan = block()
    if (BuildConfig.DEBUG) {
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
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

internal fun buildLazyItemMessageIndexMap(
    messageNodes: List<MessageNode>,
    assistant: Assistant?,
    showAssistantBubble: Boolean,
    loading: Boolean,
    hasHistoryLoadingItem: Boolean,
    pendingMessageCount: Int,
    hasPostSendWaitingPlaceholder: Boolean = false,
): List<Int?> = buildList {
    if (hasHistoryLoadingItem) add(null)
    messageNodes.forEachIndexed { index, node ->
        val itemCount = buildChatMessageVirtualItems(
            node = node,
            assistant = assistant,
            showAssistantBubble = showAssistantBubble,
            loading = loading && index == messageNodes.lastIndex,
            lastMessage = index == messageNodes.lastIndex,
        )?.size ?: 1
        repeat(itemCount) { add(index) }
    }
    if (hasPostSendWaitingPlaceholder) add(null)
    repeat(pendingMessageCount) { add(null) }
    if (loading) add(null)
    add(null)
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

internal fun List<Int?>.firstLazyIndexForMessage(messageIndex: Int): Int? {
    return indexOfFirst { it == messageIndex }.takeIf { it >= 0 }
}

internal fun ChatMessageVirtualItem.isAdjacentMarkdownChild(next: ChatMessageVirtualItem?): Boolean {
    return this is ChatMessageVirtualItem.MarkdownChild &&
        next is ChatMessageVirtualItem.MarkdownChild &&
        block.index == next.block.index
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
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (enabled) {
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
        }
        Box(
            modifier = Modifier.weight(1f)
        ) {
            content()
        }
    }
}

private fun MessageNode.markdownPrewarmTexts(assistant: Assistant?): List<String> {
    val message = currentMessage
    val scope = if (message.role == MessageRole.USER) {
        AssistantAffectScope.USER
    } else {
        AssistantAffectScope.ASSISTANT
    }
    return message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .mapNotNull { part ->
            MessageRenderCache.visualRegexText(
                text = part.text,
                assistant = assistant,
                scope = scope,
            )
                .takeIf { it.isNotBlank() }
        }
}

internal fun Conversation.actionSuggestionTexts(): List<String> {
    val lastMessage = messageNodes.lastOrNull()?.currentMessage
    if (lastMessage?.role != MessageRole.ASSISTANT) return emptyList()

    val explicitOptions = lastMessage.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .extractAssistantActionOptions()

    return (explicitOptions + chatSuggestions)
        .mapNotNull { it.normalizedActionSuggestionOrNull() }
        .distinctBy { it.compactSuggestionKey() }
        .take(10)
}

private fun String.extractAssistantActionOptions(): List<String> {
    val lines = lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
    val cueIndex = lines.indexOfLast { it.isActionOptionCueLine() }
    if (cueIndex < 0) return emptyList()

    val options = mutableListOf<String>()
    for (line in lines.drop(cueIndex + 1).take(12)) {
        val option = ActionOptionLineRegex.matchEntire(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.normalizedActionSuggestionOrNull()
        if (option == null) {
            if (options.isNotEmpty()) break
            continue
        }
        options += option
        if (options.size >= 8) break
    }
    return options
}

private fun String.isActionOptionCueLine(): Boolean {
    if (ActionOptionCuePhrases.any { phrase -> contains(phrase, ignoreCase = true) }) {
        return true
    }
    val asksForNextAction = contains("接下来") || contains("下一步")
    val hasChoiceSignal = contains("?") ||
        contains("？") ||
        contains("哪") ||
        contains("选") ||
        contains("想") ||
        contains("要")
    return asksForNextAction && hasChoiceSignal
}

private fun String.normalizedActionSuggestionOrNull(): String? {
    val rawText = trim()
    val text = (ActionOptionLineRegex.matchEntire(rawText)?.groupValues?.getOrNull(1) ?: rawText)
        .removePrefix("[ ]")
        .removePrefix("[x]")
        .removePrefix("[X]")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()
        .trim('：', ':', '。', '，', ',', '；', ';')
        .replace(Regex("""\s+"""), " ")
        .takeIf { it.length in 2..80 }
        ?: return null
    if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
        return null
    }
    return text
}

private fun String.compactSuggestionKey(): String {
    return replace(Regex("""\s+"""), "").lowercase()
}

internal fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)

        while (index >= 0) {
            // 添加高亮前的文本
            append(text.substring(startIndex, index))

            // 添加高亮文本
            withStyle(
                style = SpanStyle(
                    background = highlightColor,
                    color = Color.Black
                )
            ) {
                append(text.substring(index, index + query.length))
            }

            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }

        // 添加剩余文本
        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
    }
}
