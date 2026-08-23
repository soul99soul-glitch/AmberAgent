package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * P4-02 (capability parity plan): persistent thread graph.
 *
 * `thread_node` — one row per child thread (a subagent run). Fields follow the
 * plan model: threadId / parentThreadId / rootRunId / conversationId / status /
 * task. `task` carries a JSON payload with the subagent definition + task spec
 * so a thread can be continued (followup_task) after a cold start.
 *
 * `thread_message` — queued → delivered → persisted messages between the
 * parent run and a thread (followup_task / send_message). A message is never
 * dropped between dequeue and conversation persistence: it stays in the table
 * until the thread's result lands (persisted), so no silent loss (plan §P4-02
 * persistence semantics; first version does not claim exactly-once).
 *
 * `thread_result` — the thread's final answer, artifacts and terminal reason,
 * readable by the parent run after a restart (child final answer back to the
 * parent run).
 *
 * All tables are additive (rollback rules §17.2: disabling thread_graph_v2
 * keeps the tables and their data).
 */
@Entity(
    tableName = "thread_node",
    indices = [
        Index("root_run_id"),
        Index("parent_thread_id"),
    ],
)
data class ThreadNodeEntity(
    @PrimaryKey
    @ColumnInfo(name = "thread_id") val threadId: String,
    @ColumnInfo(name = "parent_thread_id") val parentThreadId: String?,
    @ColumnInfo(name = "root_run_id") val rootRunId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "status") val status: String,
    /** JSON: { "definition": <SubAgentDefinition>, "task": <SubAgentTaskSpec> }. */
    @ColumnInfo(name = "task") val task: String,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(
    tableName = "thread_message",
    indices = [
        Index("thread_id"),
        Index("delivery_state"),
    ],
)
data class ThreadMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "thread_id") val threadId: String,
    @ColumnInfo(name = "sender") val sender: String,
    @ColumnInfo(name = "recipient") val recipient: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "payload") val payload: String,
    /** SHA-256 hex of [payload] — never trust the payload text alone. */
    @ColumnInfo(name = "payload_digest") val payloadDigest: String,
    /** "queued" | "delivered" | "persisted". */
    @ColumnInfo(name = "delivery_state") val deliveryState: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(tableName = "thread_result")
data class ThreadResultEntity(
    @PrimaryKey
    @ColumnInfo(name = "thread_id") val threadId: String,
    @ColumnInfo(name = "final_answer") val finalAnswer: String,
    /** JSON of the structured subagent artifacts (findings/evidence/risks/…). */
    @ColumnInfo(name = "artifacts_json") val artifactsJson: String,
    /** Terminal reason: completed | failed | cancelled | timed_out | interrupted. */
    @ColumnInfo(name = "terminal_reason") val terminalReason: String,
    @ColumnInfo(name = "finished_at_ms") val finishedAtMs: Long,
)
