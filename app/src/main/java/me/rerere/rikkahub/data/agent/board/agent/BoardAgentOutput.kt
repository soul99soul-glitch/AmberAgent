package me.rerere.rikkahub.data.agent.board.agent

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.db.entity.BoardItemEntity

/**
 * Parsed board output from the agent, before validation and persistence.
 *
 * Mirrors the JSON schema declared in [BoardPrompt]. Kept deliberately close to the
 * wire shape so parser/validator stay simple; we transform to [BoardItemEntity] only
 * after validation passes.
 */
@Serializable
data class BoardAgentOutput(
    val summary: String = "",
    val items: List<BoardAgentItem> = emptyList(),
)

@Serializable
data class BoardAgentItem(
    val id: String = "",
    val title: String = "",
    val source_type: String = "",
    val source_ref: String = "",
    val urgency: String = "medium",
    val category: String = "attention",
    val reason: String = "",
    val suggestion: String = "",
    val signal_time: Long = 0L,
)

/**
 * Convert a validated [BoardAgentItem] into a persisted [BoardItemEntity].
 *
 * The entity id is **deterministically derived** from `(boardDate, source_ref, category)`
 * so re-running the agent on the same signal set produces the same id. Combined with
 * `OnConflictStrategy.REPLACE` in [BoardItemDAO], this gives free idempotency: worker
 * retries, crash recovery, and manual re-triggers all converge on the same row instead
 * of accumulating duplicates.
 *
 * [sourceContent] comes from the original signal's content (not the agent's echo), so
 * the "聊一下" flow can show the full original context rather than the agent's summary.
 */
fun BoardAgentItem.toEntity(
    sourceContent: String,
    boardDate: String,
    nowMs: Long = System.currentTimeMillis(),
): BoardItemEntity = BoardItemEntity(
    id = stableItemId(boardDate, source_ref, category),
    title = title.take(200),
    sourceType = source_type,
    sourceRef = source_ref,
    sourceContent = sourceContent.take(4_000),
    urgency = urgency,
    category = category,
    reason = reason.take(500),
    suggestion = suggestion.take(500),
    signalTime = signal_time,
    status = "active",
    completedAt = null,
    dismissedAt = null,
    boardDate = boardDate,
    createdAt = nowMs,
)

/**
 * Derive a stable item id from (boardDate, source_ref, category). Same triple always
 * yields the same id; different triples (different days, different sources, or different
 * categories of the same signal) get distinct ids.
 *
 * SHA-256 truncated to 32 hex chars — same convention used elsewhere in the codebase
 * for deterministic ids on TEXT primary-key tables.
 */
private fun stableItemId(boardDate: String, sourceRef: String, category: String): String {
    val input = "$boardDate|$sourceRef|$category"
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(32)
}
