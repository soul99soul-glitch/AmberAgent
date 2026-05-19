package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.message.ChatMessageVirtualItem
import me.rerere.rikkahub.ui.components.message.buildChatMessageVirtualItems
import kotlin.uuid.Uuid

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

internal fun LazyListState.markdownPrewarmTexts(
    messageNodes: List<MessageNode>,
    assistant: Assistant?,
    loadingLastMessage: Boolean,
    lazyItemMessageIndexes: List<Int?>,
): List<String> {
    if (messageNodes.isEmpty()) return emptyList()
    val visibleMessageIndexes = layoutInfo.visibleItemsInfo
        .mapNotNull { lazyItemMessageIndexes.getOrNull(it.index) }
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
            part.text
                .replaceRegexes(
                    assistant = assistant,
                    scope = scope,
                    visual = true,
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
