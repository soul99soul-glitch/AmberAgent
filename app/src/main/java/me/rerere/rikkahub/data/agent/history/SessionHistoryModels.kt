package me.rerere.rikkahub.data.agent.history

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionAccessGrant(
    @SerialName("grant_id")
    val grantId: String,
    @SerialName("session_ids")
    val sessionIds: Set<String>,
    @SerialName("query_scope")
    val queryScope: String = "selected_sessions",
    @SerialName("max_sessions")
    val maxSessions: Int,
    @SerialName("max_chars")
    val maxChars: Int,
    val purpose: String,
    @SerialName("expires_at")
    val expiresAt: Long,
    @SerialName("source_conversation_id")
    val sourceConversationId: String,
    @SerialName("assigned_subagent_run_id")
    val assignedSubagentRunId: String? = null,
    @SerialName("used_chars")
    val usedChars: Int = 0,
)

@Serializable
data class SessionHistoryShard(
    @SerialName("session_ids")
    val sessionIds: List<String>,
    @SerialName("history_query")
    val historyQuery: String = "",
    @SerialName("shard_index")
    val shardIndex: Int = 0,
    @SerialName("shard_count")
    val shardCount: Int = 1,
)

@Serializable
data class SessionHistoryWorkerResult(
    val summary: String,
    val questions: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    @SerialName("open_items")
    val openItems: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
    @SerialName("source_message_ids")
    val sourceMessageIds: List<String> = emptyList(),
    val uncertainties: List<String> = emptyList(),
)

@Serializable
data class SessionHistorySynthesisResult(
    val summary: String,
    val themes: List<String> = emptyList(),
    val timeline: List<String> = emptyList(),
    @SerialName("open_items")
    val openItems: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
    @SerialName("failed_shards")
    val failedShards: List<String> = emptyList(),
)
