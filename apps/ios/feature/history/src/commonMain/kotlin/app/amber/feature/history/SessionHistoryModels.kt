package app.amber.feature.history

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
