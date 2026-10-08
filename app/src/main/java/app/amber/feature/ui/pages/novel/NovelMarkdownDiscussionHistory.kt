package app.amber.feature.ui.pages.novel

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart

/** Branch-scoped dialogue, without audit reports, interrupted output or the current input. */
internal fun novelMarkdownDiscussionHistory(
    messages: List<NovelMarkdownMessageUi>,
    currentMessageId: String,
): List<UIMessage> = messages.filter {
    it.id != currentMessageId && it.kind in setOf("userInput", "discussion") &&
        it.role in setOf(MessageRole.USER, MessageRole.ASSISTANT)
}.map {
    UIMessage(role = it.role, parts = listOf(UIMessagePart.Text(it.content)))
}
