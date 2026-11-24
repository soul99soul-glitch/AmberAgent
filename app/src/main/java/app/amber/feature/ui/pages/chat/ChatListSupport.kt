package app.amber.feature.ui.pages.chat

import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessageAnnotation
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.feature.ui.components.message.ChatMessageVirtualItem

internal fun Conversation.latestRenderToken(): String {
    val message = messageNodes.lastOrNull()?.currentMessage ?: return "${messageNodes.size}:empty"
    return buildString {
        append(messageNodes.size)
        append(':')
        append(message.id)
        append(':')
        append(message.parts.size)
        append(':')
        append(message.annotations.compactRenderToken())
        append(':')
        append(message.parts.joinToString(separator = "|") { it.compactRenderToken() })
    }
}

private fun UIMessagePart.compactRenderToken(): String = when (this) {
    is UIMessagePart.Text -> "text:${text.length}:${text.takeLast(16)}:${text.hashCode()}"
    is UIMessagePart.Reasoning -> "reasoning:${reasoning.length}:${reasoning.takeLast(16)}:${reasoning.hashCode()}:${finishedAt != null}"
    is UIMessagePart.Tool -> {
        val outputToken = output.joinToString(separator = ",") { it.compactRenderToken() }.hashCode()
        "tool:$toolCallId:$toolName:${input.length}:${input.takeLast(16)}:${input.hashCode()}:" +
            "$isExecuted:${approvalState.compactRenderToken()}:${output.size}:$outputToken"
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

private fun List<UIMessageAnnotation>.compactRenderToken(): String {
    if (isEmpty()) return "0"
    val annotationToken = joinToString(separator = "|") { annotation ->
        when (annotation) {
            is UIMessageAnnotation.UrlCitation -> "url:${annotation.title}:${annotation.url}"
            is UIMessageAnnotation.GenerationInterrupted -> "interrupted:${annotation.reason}"
            is UIMessageAnnotation.LiveInterjection -> "interjection:${annotation.kind}:${annotation.targetTaskId}"
        }
    }.hashCode()
    return "$size:$annotationToken"
}

internal fun ChatMessageVirtualItem.isAdjacentMarkdownChild(next: ChatMessageVirtualItem?): Boolean {
    return this is ChatMessageVirtualItem.MarkdownChild &&
        next is ChatMessageVirtualItem.MarkdownChild &&
        block.index == next.block.index
}
