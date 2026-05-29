package app.amber.core.context

import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation

/**
 * Char-equivalent length weighted by tokenization density. CJK ideographs cost
 * ~4× more tokens than ASCII under BPE tokenizers (GLM/Qwen tokenize Chinese at
 * 1-2 tokens per character; ASCII averages 0.25 tok/char). We apply the ×4
 * multiplier here so the same `/ 4` final divide downstream yields ≈1 token per
 * CJK char and ≈0.25 token per ASCII char.
 *
 * Lives at package level (not inside ContextFootprintEstimator object) so
 * ConversationContextPlanner.estimatedChars can call the same helper —
 * keeps the two estimators arithmetically consistent on the same input.
 */
internal fun String.weightedTokenChars(): Int {
    if (isEmpty()) return 0
    var total = 0
    for (c in this) {
        val cp = c.code
        // CJK Unified Ideographs (4E00-9FFF), Extension A (3400-4DBF),
        // CJK symbols/punctuation (3000-303F), Hiragana/Katakana (3040-30FF).
        total += if (cp in 0x4E00..0x9FFF ||
            cp in 0x3400..0x4DBF ||
            cp in 0x3000..0x30FF
        ) 4 else 1
    }
    return total
}

/**
 * UI-facing estimate of the input footprint that will be sent to the model on the next turn.
 *
 * 2026-05-15: rewrote to be active-compact-aware. The previous version read
 * `conversation.currentMessages` raw, which after a successful compaction would
 * still include every node verbatim — the compact summary lived only in
 * ConversationContextRepository and was substituted at send-time inside
 * prepareMessagesWithCompacts. Result: post-compact, the ring showed (e.g.)
 * 193K while the model actually received 25K. Users perceived a permanently-red
 * indicator and learned to ignore it. Now the estimator mirrors the same
 * compact-substitution logic that ConversationContextPlanner.prepareMessages
 * applies, so the ring reflects what the model is going to receive on the
 * next turn — both before AND after a compact.
 *
 * Token-counting model: CJK characters carry roughly 4× the token weight of
 * ASCII characters under modern BPE tokenizers (GLM/Qwen ≈ 1.5 tok/char, ASCII
 * ≈ 0.25 tok/char). We weight CJK by 4 in the char count, then divide the
 * whole sum by 4 to get a token estimate — net effect is "≈1 token per CJK
 * char, ≈0.25 token per ASCII char". This is an approximation but it's the
 * difference between "users see a meaningful pressure indicator" and "the
 * indicator is off by 4× on every Chinese conversation".
 */
object ContextFootprintEstimator {

    fun estimateConversationInputTokens(
        conversation: Conversation,
        activeCompacts: List<ConversationCompact> = emptyList(),
    ): Int = estimateMessagesWithCompacts(conversation.currentMessages, activeCompacts)

    fun inputFingerprint(messages: List<UIMessage>): Long {
        var acc = 1125899906842597L
        fun mix(value: Long) {
            acc = (acc * 31) xor value
        }
        messages.forEach { message ->
            mix(message.id.hashCode().toLong())
            mix(message.role.name.hashCode().toLong())
            mix(message.parts.size.toLong())
            message.parts.forEach { part ->
                mix(part.inputFootprintFingerprint())
            }
        }
        return acc
    }

    /**
     * Estimate footprint for the messages after compact-summary substitution.
     * Mirrors `ConversationContextPlanner.prepareMessages` so the UI estimate
     * stays consistent with what `prepareContext` actually sends.
     */
    private fun estimateMessagesWithCompacts(
        messages: List<UIMessage>,
        activeCompacts: List<ConversationCompact>,
    ): Int {
        if (activeCompacts.isEmpty()) return estimateMessages(messages)
        val existingMessageIds = messages.map { it.id.toString() }.toSet()
        val completedCompacts = CompactSummaryPayloads.validCompletedCompacts(activeCompacts, existingMessageIds)
        if (completedCompacts.isEmpty()) return estimateMessages(messages)

        val coveredMessageIds = completedCompacts.flatMap { it.sourceMessageIds }.toSet()
        val recentMessages = messages.filter { it.id.toString() !in coveredMessageIds }

        // Mirror ConversationContextPlanner.prepareMessages: old compacts
        // covered by the latest cumulative handoff are not injected again.
        val summaryMessages = CompactSummaryPayloads
            .selectCompactsForInjection(activeCompacts, existingMessageIds)
            .map { compact -> UIMessage.system(CompactSummaryPayloads.injectionText(compact)) }
        val summaryTokens = estimateMessages(summaryMessages)
        return summaryTokens + estimateMessages(recentMessages)
    }

    fun estimateMessages(messages: List<UIMessage>): Int {
        val weighted = messages.sumOf { message ->
            message.role.name.length + message.parts.sumOf { it.inputFootprintChars() }
        }
        return (weighted / 4).coerceAtLeast(messages.size * 4)
    }

    private fun UIMessagePart.inputFootprintChars(): Int = when (this) {
        is UIMessagePart.Text -> text.weightedTokenChars()
        is UIMessagePart.Reasoning -> reasoning.weightedTokenChars()
        is UIMessagePart.Tool -> {
            val outputChars = if (isExecuted) {
                output.sumOf { it.inputFootprintChars() }
            } else {
                0
            }
            input.weightedTokenChars() + outputChars
        }
        // Multimodal: per-image token cost is provider-specific (OpenAI vision
        // ~800-1500, Claude (w*h)/750 ≈ 1200 for typical photos, Gemini ~1300).
        // 4500 weighted-chars ÷ 4 = 1125 token estimate — mid-range. ASCII-
        // equivalent so we don't double-count CJK weight.
        is UIMessagePart.Image -> 4_500
        is UIMessagePart.Video -> 4_500
        is UIMessagePart.Audio -> 4_500
        is UIMessagePart.Document -> fileName.weightedTokenChars() + 80
        is UIMessagePart.MiniApp -> title.weightedTokenChars() + description.weightedTokenChars() + 120
    }

    /**
     * Fingerprint must change whenever the estimate result could change.
     * 2026-05-15: dropped the old `.coerceAtMost(2_000)` / `.coerceAtMost(8_000)`
     * caps that previously hid changes beyond the cap boundary — a tool output
     * growing from 12KB to 60KB would otherwise hash identically and the
     * `remember(fingerprint)` cache in ContextUsageIndicator would never
     * refresh, leaving the ring frozen. Reasoning fingerprint also needs to
     * track length now that the estimate counts it. Multimodal counts are
     * constant per part type so url-length disambiguation is sufficient.
     */
    private fun UIMessagePart.inputFootprintFingerprint(): Long = when (this) {
        is UIMessagePart.Text -> 1_000L + text.length
        is UIMessagePart.Reasoning -> 2_000L + reasoning.length
        is UIMessagePart.Tool -> {
            var acc = 3_000L
            acc = acc * 31 + toolCallId.hashCode()
            acc = acc * 31 + toolName.hashCode()
            acc = acc * 31 + input.length
            acc = acc * 31 + if (isExecuted) 1 else 0
            output.forEach { acc = acc * 31 + it.inputFootprintFingerprint() }
            acc
        }
        is UIMessagePart.Image -> 6_000L + url.length
        is UIMessagePart.Video -> 7_000L + url.length
        is UIMessagePart.Audio -> 8_000L + url.length + fileName.length
        is UIMessagePart.Document -> 9_000L + fileName.length + url.length
        is UIMessagePart.MiniApp -> 9_500L + appId.length + title.length + version
    }

}
