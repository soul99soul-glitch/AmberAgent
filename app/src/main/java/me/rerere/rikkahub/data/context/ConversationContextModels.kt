package me.rerere.rikkahub.data.context

import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessage

@Serializable
data class ConversationCompact(
    val id: String,
    val conversationId: String,
    val summary: String,
    val level: Int,
    val sourceStartIndex: Int,
    val sourceEndIndex: Int,
    val sourceMessageIds: List<String>,
    val tokenEstimate: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
)

data class CompactPolicy(
    val enabled: Boolean = true,
    val notifyOnly: Boolean = false,
    val precompactRatio: Float = 0.70f,
    val forceRatio: Float = 0.85f,
    val keepRecentTurns: Int = 12,
    val maxSummaryTokens: Int = 2_000,
)

data class PreparedContext(
    val messages: List<UIMessage>,
    val tokenEstimate: Int,
    val compressionApplied: Boolean,
    val summaryIds: List<String>,
)

data class CompactResult(
    val status: String,
    val summaryId: String? = null,
    val sourceMessageCount: Int = 0,
    val estimatedTokensBefore: Int = 0,
    val estimatedTokensAfter: Int = 0,
    val error: String? = null,
)

data class ContextSearchResult(
    val source: String,
    val id: String,
    val preview: String,
    val nodeIndex: Int? = null,
)

data class CompactPlan(
    val shouldCompact: Boolean,
    val reason: String,
    val estimatedTokens: Int,
    val contextWindowTokens: Int,
    val sourceStartIndex: Int,
    val sourceEndIndex: Int,
    val sourceMessageIds: List<String>,
) {
    val sourceMessageCount: Int
        get() = if (shouldCompact) sourceEndIndex - sourceStartIndex + 1 else 0
}
