package app.amber.core.context

import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.limitContext
import app.amber.core.model.MessageNode

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

    fun planForceCompaction(
        nodes: List<MessageNode>,
        activeCompacts: List<ConversationCompact>,
        policy: CompactPolicy,
        modelContextWindowTokens: Int?,
    ): CompactPlan {
        val turns = buildList {
            var currentTurns = policy.keepRecentTurns.coerceAtLeast(1)
            while (currentTurns > 1) {
                add(currentTurns)
                currentTurns = (currentTurns / 2).coerceAtLeast(1)
            }
            add(1)
        }.distinct()

        val contextWindow = estimateContextWindow(modelContextWindowTokens)
        val targetTokens = (contextWindow * policy.forceRatio)
            .toInt()
            .coerceAtLeast(1)
        var deepestPlan: CompactPlan? = null
        var lastPlan: CompactPlan? = null

        turns.forEach { keepRecentTurns ->
            val plan = planCompaction(
                nodes = nodes,
                activeCompacts = activeCompacts,
                policy = policy.copy(
                    enabled = true,
                    keepRecentTurns = keepRecentTurns,
                    precompactRatio = 0f,
                    forceRatio = Float.MAX_VALUE,
                ),
                modelContextWindowTokens = modelContextWindowTokens,
            )
            lastPlan = plan
            if (plan.shouldCompact) {
                deepestPlan = plan
                if (estimateAfterCompaction(nodes, activeCompacts, plan, policy.maxSummaryTokens) <= targetTokens) {
                    return plan.copy(reason = "force_threshold")
                }
            }
        }

        return deepestPlan?.copy(reason = "force_threshold")
            ?: lastPlan
            ?: planCompaction(
                nodes = nodes,
                activeCompacts = activeCompacts,
                policy = policy.copy(
                    enabled = true,
                    precompactRatio = 0f,
                    forceRatio = Float.MAX_VALUE,
                ),
                modelContextWindowTokens = modelContextWindowTokens,
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
        val completedCompacts = CompactSummaryPayloads.validCompletedCompacts(activeCompacts, existingMessageIds)
        if (completedCompacts.isEmpty()) return messages.limitContext(contextMessageSize)

        val compactSummaryMessages = CompactSummaryPayloads
            .selectCompactsForInjection(activeCompacts, existingMessageIds)
            .map { compact ->
                UIMessage.system(CompactSummaryPayloads.injectionText(compact))
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

    private fun estimateAfterCompaction(
        nodes: List<MessageNode>,
        activeCompacts: List<ConversationCompact>,
        plan: CompactPlan,
        maxSummaryTokens: Int,
    ): Int {
        if (!plan.shouldCompact) return plan.estimatedTokens
        val messages = nodes.map { it.currentMessage }
        val existingMessageIds = messages.map { it.id.toString() }.toSet()
        val completedCompacts = CompactSummaryPayloads.validCompletedCompacts(activeCompacts, existingMessageIds)
        val carriedCompacts = CompactSummaryPayloads.selectCompactsForInjection(activeCompacts, existingMessageIds)
        val carriedCompactIds = carriedCompacts.transitiveCompactIds()
        val remainingCompactSummaryMessages = completedCompacts
            .filter { it.id !in carriedCompactIds }
            .map { compact -> UIMessage.system(CompactSummaryPayloads.injectionText(compact)) }
        val coveredMessageIds = completedCompacts
            .flatMap { it.sourceMessageIds }
            .plus(plan.sourceMessageIds)
            .toSet()
        val recentMessages = messages.filter { it.id.toString() !in coveredMessageIds }
        return estimateTokens(remainingCompactSummaryMessages) +
            estimateTokens(recentMessages) +
            maxSummaryTokens.coerceAtLeast(256)
    }

    private fun List<ConversationCompact>.transitiveCompactIds(): Set<String> {
        if (isEmpty()) return emptySet()
        val byId = associateBy { it.id }
        val seen = linkedSetOf<String>()
        fun visit(id: String) {
            if (!seen.add(id)) return
            byId[id]?.let { compact ->
                CompactSummaryPayloads.parse(compact.summary)?.coveredCompactIds.orEmpty().forEach(::visit)
            }
        }
        forEach { visit(it.id) }
        return seen
    }

    private fun UIMessagePart.estimatedChars(): Int = when (this) {
        // 2026-05-15: dropped all the `.coerceAtMost(N)` caps + added CJK weight
        // (×4 for ideographs to match BPE tokenizer density). The old caps
        // (reasoning=256, tool input=2000, tool output=8000) made the estimate
        // wildly low for tool-heavy / reasoning-heavy conversations — e.g. a
        // model council run where each turn's tool_call output is ~30KB but
        // we counted only 8KB. UI showed 32K used / 200K budget while the
        // model actually saw 173K → forceRatio (85%) never triggered → user's
        // helper agent stalled mid-council with no compaction kicking in.
        //
        // CJK weighting (delegated to ContextFootprintEstimator.weightedTokenChars
        // so the planner and UI ring agree to the character): 1 CJK char ≈ 1-2
        // BPE tokens (GLM/Qwen), 1 ASCII char ≈ 0.25 tokens. With ×4 weight on
        // CJK chars + `/ 4` divide downstream, net effect is ~1 tok/CJK,
        // ~0.25 tok/ASCII. Without this, a Chinese conversation was still 3×
        // under-counted even after the caps came off.
        //
        // Caps were never load-bearing for performance (`String.length` is O(1));
        // they were a stab at "approximating what we'd actually send after
        // truncation," but the provider receives full payloads so the truer
        // estimate is just the raw weighted length.
        is UIMessagePart.Text -> text.weightedTokenChars()
        is UIMessagePart.Reasoning -> reasoning.weightedTokenChars()
        is UIMessagePart.Tool -> input.weightedTokenChars() + output.sumOf { it.estimatedChars() }
        // Multimodal inputs: rough provider-agnostic token costs for the input
        // representation that gets sent to the model. OpenAI vision: ~800-1500
        // tokens/image depending on tile resolution. Claude vision: tokens =
        // (w*h)/750, typical phone photos land around 1200 tokens. We can't
        // peek into the actual bitmap dimensions here cheaply, so pick a
        // mid-range constant. 4500 chars ≈ 1125 tokens at the same /4 ratio
        // used below — defensible average across providers.
        is UIMessagePart.Image -> 4_500
        is UIMessagePart.Video -> 4_500
        is UIMessagePart.Audio -> 4_500
        // Document is uploaded as a file ref; the actual content typically
        // gets injected as Text by the document-as-prompt transformer, so we
        // only count the inline metadata here.
        is UIMessagePart.Document -> fileName.length + 80
        is UIMessagePart.MiniApp -> title.weightedTokenChars() + description.weightedTokenChars() + 120
    }

    private fun UIMessagePart.summaryLine(): String = when (this) {
        is UIMessagePart.Text -> "text: ${text.takeMiddle(8_000)}"
        is UIMessagePart.Reasoning -> "reasoning_marker: ${reasoning.length} chars"
        is UIMessagePart.Tool -> {
            val outputText = ToolResultCompactor.summarize(output)
            "tool: $toolName id=$toolCallId executed=$isExecuted input=${input.takeMiddle(2_000)} output=$outputText"
        }
        is UIMessagePart.Image -> "image: ${url.takeLast(80)}"
        is UIMessagePart.Video -> "video: ${url.takeLast(80)}"
        is UIMessagePart.Audio -> "audio: ${url.takeLast(80)}"
        is UIMessagePart.Document -> "document: $fileName mime=$mime"
        is UIMessagePart.MiniApp -> "mini_app: $title id=$appId"
    }
}

fun String.takeMiddle(maxChars: Int): String {
    if (length <= maxChars) return this
    val half = (maxChars - 40).coerceAtLeast(16) / 2
    return take(half) + "\n... [${length - half * 2} chars omitted] ...\n" + takeLast(half)
}
