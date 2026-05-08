package me.rerere.rikkahub.data.context

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.model.MessageNode

object ConversationContextPlanner {
    private const val DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000

    fun estimateTokens(messages: List<UIMessage>): Int {
        val chars = messages.sumOf { message ->
            message.role.name.length + message.parts.sumOf { it.estimatedChars() }
        }
        return (chars / 4).coerceAtLeast(messages.size * 4)
    }

    fun estimateContextWindow(modelContextWindowTokens: Int?): Int {
        return modelContextWindowTokens?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS
    }

    fun planCompaction(
        nodes: List<MessageNode>,
        activeCompacts: List<ConversationCompact>,
        policy: CompactPolicy,
        modelContextWindowTokens: Int?,
        extraTokenEstimate: Int = 0,
    ): CompactPlan {
        if (!policy.enabled || nodes.isEmpty()) {
            return skipped("disabled", nodes, modelContextWindowTokens)
        }
        val messages = nodes.map { it.currentMessage }
        val estimatedTokens = estimateTokens(messages) + extraTokenEstimate.coerceAtLeast(0)
        val contextWindow = estimateContextWindow(modelContextWindowTokens)
        val ratio = estimatedTokens.toFloat() / contextWindow.toFloat()
        if (ratio < policy.precompactRatio) {
            return CompactPlan(false, "below_threshold", estimatedTokens, contextWindow, 0, -1, emptyList())
        }

        val keepCount = (policy.keepRecentTurns * 2).coerceAtLeast(2)
        val sourceEnd = (nodes.lastIndex - keepCount).coerceAtMost(nodes.lastIndex)
        if (sourceEnd < 1) {
            return CompactPlan(false, "not_enough_history", estimatedTokens, contextWindow, 0, -1, emptyList())
        }

        val latestCoveredEnd = activeCompacts
            .filter { it.status == "completed" }
            .maxOfOrNull { it.sourceEndIndex }
            ?: -1
        if (latestCoveredEnd >= sourceEnd) {
            return CompactPlan(false, "already_compacted", estimatedTokens, contextWindow, 0, -1, emptyList())
        }

        var start = (latestCoveredEnd + 1).coerceAtLeast(0)
        var end = sourceEnd
        while (start <= end && nodes[start].currentMessage.role == MessageRole.ASSISTANT &&
            nodes[start].currentMessage.getTools().any { it.isExecuted }
        ) {
            start++
        }
        while (end >= start && nodes[end].currentMessage.getTools().any { !it.isExecuted }) {
            end--
        }
        if (end - start + 1 < 2) {
            return CompactPlan(false, "not_enough_new_history", estimatedTokens, contextWindow, 0, -1, emptyList())
        }

        return CompactPlan(
            shouldCompact = true,
            reason = if (ratio >= policy.forceRatio) "force_threshold" else "precompact_threshold",
            estimatedTokens = estimatedTokens,
            contextWindowTokens = contextWindow,
            sourceStartIndex = start,
            sourceEndIndex = end,
            sourceMessageIds = nodes.subList(start, end + 1).map { it.currentMessage.id.toString() },
        )
    }

    fun prepareMessages(
        messages: List<UIMessage>,
        activeCompacts: List<ConversationCompact>,
        policy: CompactPolicy,
        contextMessageSize: Int,
    ): List<UIMessage> {
        if (!policy.enabled || activeCompacts.isEmpty()) {
            return messages.limitContext(contextMessageSize)
        }
        val existingMessageIds = messages.map { it.id.toString() }.toSet()
        val completedCompacts = activeCompacts.filter { compact ->
            compact.status == "completed" &&
                compact.sourceMessageIds.isNotEmpty() &&
                compact.sourceMessageIds.all { it in existingMessageIds }
        }
        if (completedCompacts.isEmpty()) return messages.limitContext(contextMessageSize)

        val compactSummaryMessages = completedCompacts.map { compact ->
            UIMessage.system(
                """
                [Conversation compact summary: ${compact.id}]
                Source message ids: ${compact.sourceMessageIds.joinToString(", ")}
                ${compact.summary}
                """.trimIndent()
            )
        }
        val coveredMessageIds = completedCompacts.flatMap { it.sourceMessageIds }.toSet()
        val recentMessages = messages.filter { it.id.toString() !in coveredMessageIds }
        val keepLimit = if (contextMessageSize > 0) {
            contextMessageSize
        } else {
            (policy.keepRecentTurns * 2).coerceAtLeast(12)
        }
        return compactSummaryMessages + recentMessages.limitContext(keepLimit)
    }

    fun fitMessagesToTokenBudget(
        messages: List<UIMessage>,
        maxTokens: Int,
    ): List<UIMessage> {
        if (maxTokens <= 0 || messages.isEmpty()) return messages.takeLast(1)
        if (estimateTokens(messages) <= maxTokens) return messages

        val systemMessages = messages.filter { it.role == MessageRole.SYSTEM }
        val tail = messages.filter { it.role != MessageRole.SYSTEM }
        val selected = mutableListOf<UIMessage>()

        for (message in tail.asReversed()) {
            selected.add(0, message)
            val candidate = systemMessages + selected
            if (estimateTokens(candidate) > maxTokens) {
                selected.removeAt(0)
                if (selected.isEmpty()) {
                    selected.add(0, message)
                }
                break
            }
        }

        return systemMessages + selected
    }

    fun buildCompressionInput(messages: List<UIMessage>): String {
        return messages.joinToString("\n\n") { message ->
            buildString {
                appendLine("message_id: ${message.id}")
                appendLine("role: ${message.role.name.lowercase()}")
                message.parts.forEach { part ->
                    appendLine(part.summaryLine())
                }
            }.trimEnd()
        }
    }

    private fun skipped(reason: String, nodes: List<MessageNode>, modelContextWindowTokens: Int?): CompactPlan {
        val messages = nodes.map { it.currentMessage }
        return CompactPlan(
            shouldCompact = false,
            reason = reason,
            estimatedTokens = estimateTokens(messages),
            contextWindowTokens = estimateContextWindow(modelContextWindowTokens),
            sourceStartIndex = 0,
            sourceEndIndex = -1,
            sourceMessageIds = emptyList(),
        )
    }

    private fun UIMessagePart.estimatedChars(): Int = when (this) {
        is UIMessagePart.Text -> text.length
        is UIMessagePart.Reasoning -> reasoning.length.coerceAtMost(256)
        is UIMessagePart.Tool -> input.length.coerceAtMost(2_000) + output.sumOf { it.estimatedChars() }.coerceAtMost(8_000)
        is UIMessagePart.ToolCall -> arguments.toString().length.coerceAtMost(2_000)
        is UIMessagePart.ToolResult -> content.toString().length.coerceAtMost(8_000)
        is UIMessagePart.Image -> 80
        is UIMessagePart.Video -> 80
        is UIMessagePart.Audio -> 80
        is UIMessagePart.Document -> fileName.length + 80
        UIMessagePart.Search -> 20
    }

    private fun UIMessagePart.summaryLine(): String = when (this) {
        is UIMessagePart.Text -> "text: ${text.takeMiddle(8_000)}"
        is UIMessagePart.Reasoning -> "reasoning_marker: ${reasoning.length} chars"
        is UIMessagePart.Tool -> {
            val outputText = ToolResultCompactor.summarize(output)
            "tool: $toolName id=$toolCallId executed=$isExecuted input=${input.takeMiddle(2_000)} output=$outputText"
        }
        is UIMessagePart.ToolCall -> "tool_call: $toolName id=$toolCallId input=${arguments.takeMiddle(2_000)}"
        is UIMessagePart.ToolResult -> "tool_result: $toolName id=$toolCallId content=${content.toString().takeMiddle(8_000)}"
        is UIMessagePart.Image -> "image: ${url.takeLast(80)}"
        is UIMessagePart.Video -> "video: ${url.takeLast(80)}"
        is UIMessagePart.Audio -> "audio: ${url.takeLast(80)}"
        is UIMessagePart.Document -> "document: $fileName mime=$mime"
        UIMessagePart.Search -> "search_marker"
    }
}

fun String.takeMiddle(maxChars: Int): String {
    if (length <= maxChars) return this
    val half = (maxChars - 40).coerceAtLeast(16) / 2
    return take(half) + "\n... [${length - half * 2} chars omitted] ...\n" + takeLast(half)
}
