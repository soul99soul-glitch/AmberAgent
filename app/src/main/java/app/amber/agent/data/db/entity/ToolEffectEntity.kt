package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable Tool Effect Ledger — P1-02.
 *
 * One row per tool effect with a stable [effectId]. Written ahead of
 * execution (PREPARED) and advanced through STARTED → FINISHED / FAILED,
 * so a process death at any protocol step can be reconciled without
 * unconditionally re-running side effects.
 *
 * Recovery rules (plan §P1-02):
 *  - PREPARED: never executed — safe to re-enter approval/execution.
 *  - STARTED without FINISHED/FAILED: readOnly can be retried safely,
 *    idempotentWrite retries with its idempotency key, nonIdempotentWrite
 *    must be marked OUTCOME_UNKNOWN and wait for user confirmation.
 *  - FINISHED with the tool result not yet written to the conversation:
 *    replay [resultPayload] instead of re-executing.
 *
 * Column layout follows the existing `app/amber/agent/data/db` entities:
 * snake_case column names, explicit [ColumnInfo], indices on query keys.
 */
@Entity(
    tableName = "tool_effect",
    indices = [
        Index("run_id"),
        Index("tool_call_id"),
        Index("status"),
        Index(value = ["run_id", "tool_call_id"]),
    ],
)
data class ToolEffectEntity(
    @PrimaryKey
    @ColumnInfo(name = "effect_id") val effectId: String,
    @ColumnInfo(name = "run_id") val runId: String?,
    @ColumnInfo(name = "turn_id") val turnId: Int?,
    @ColumnInfo(name = "tool_call_id") val toolCallId: String,
    @ColumnInfo(name = "tool_name") val toolName: String,
    @ColumnInfo(name = "args_digest") val argsDigest: String,
    @ColumnInfo(name = "approval_digest") val approvalDigest: String?,
    @ColumnInfo(name = "effect_class") val effectClass: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "finished_at_ms") val finishedAtMs: Long?,
    @ColumnInfo(name = "result_summary") val resultSummary: String?,
    @ColumnInfo(name = "result_payload") val resultPayload: String?,
    @ColumnInfo(name = "error_category") val errorCategory: String?,
    @ColumnInfo(name = "message_persistence_cursor") val messagePersistenceCursor: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)
