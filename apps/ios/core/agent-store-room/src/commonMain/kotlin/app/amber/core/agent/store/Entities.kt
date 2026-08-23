package app.amber.core.agent.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_run",
    indices = [
        Index("status"),
        Index("agent_descriptor_id"),
        Index("conversation_id"),
        Index("message_node_id"),
        Index("assistant_id"),
    ],
)
data class AgentRunEntity(
    @PrimaryKey
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "parent_run_id") val parentRunId: String?,
    @ColumnInfo(name = "agent_descriptor_id") val agentDescriptorId: String,
    @ColumnInfo(name = "agent_version") val agentVersion: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String?,
    @ColumnInfo(name = "message_node_id") val messageNodeId: String?,
    @ColumnInfo(name = "produces_message_id") val producesMessageId: String?,
    @ColumnInfo(name = "assistant_id") val assistantId: String?,
    val status: String,
    @ColumnInfo(name = "input_digest") val inputDigest: String,
    @ColumnInfo(name = "input_snapshot_ref") val inputSnapshotRef: String?,
    @ColumnInfo(name = "input_schema_version") val inputSchemaVersion: Int,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    @ColumnInfo(name = "interrupted_reason") val interruptedReason: String?,
)

@Entity(
    tableName = "agent_event",
    indices = [
        Index(value = ["run_id", "seq"], unique = true),
        Index("run_id"),
        Index("ts"),
    ],
)
data class AgentEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "parent_run_id") val parentRunId: String?,
    val seq: Long,
    val type: String,
    @ColumnInfo(name = "payload_type") val payloadType: String,
    val payload: String,
    @ColumnInfo(name = "payload_schema_version") val payloadSchemaVersion: Int,
    @ColumnInfo(name = "agent_descriptor_id") val agentDescriptorId: String,
    @ColumnInfo(name = "agent_version") val agentVersion: String,
    @ColumnInfo(name = "is_final") val isFinal: Boolean,
    val ts: Long,
)

@Entity(
    tableName = "trace_span",
    indices = [
        Index("run_id"),
        Index("parent_span_id"),
        Index("kind"),
        Index("started_at"),
    ],
)
data class TraceSpanEntity(
    @PrimaryKey
    @ColumnInfo(name = "span_id") val spanId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "parent_span_id") val parentSpanId: String?,
    val name: String,
    val kind: String,
    val status: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "attributes_json") val attributesJson: String,
)

@Entity(
    tableName = "permission_intent",
    indices = [
        Index("run_id"),
        Index("decision"),
        Index("created_at"),
    ],
)
data class PermissionIntentEntity(
    @PrimaryKey
    @ColumnInfo(name = "intent_id") val intentId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    val kind: String,
    @ColumnInfo(name = "tool_id") val toolId: String?,
    @ColumnInfo(name = "payload_digest") val payloadDigest: String,
    val reason: String,
    val channel: String,
    val decision: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "decided_at") val decidedAt: Long?,
    @ColumnInfo(name = "decided_by") val decidedBy: String?,
)

/**
 * P1-b: mailbox 信封（线程编排的投递单元）。
 *
 * - 地址 = conversationId（现有会话都是根线程；agentPath 树形别名 P1-c 才引入）。
 * - `deliveredAt` 为 null 表示未投递；投递后信封保留（清理/裁剪策略留 follow-up）。
 * - `type` 为 wire 字符串（`MailboxEnvelopeType.wireName`），扩展新类型不动 schema。
 * - `triggerTurn` 为 true 时投递给 idle 线程应唤醒一轮 run（P1-d 消费）。
 */
@Entity(
    tableName = "mailbox_envelope",
    indices = [
        Index(value = ["recipient_thread_id", "delivered_at", "created_at"]),
    ],
)
data class MailboxEnvelopeEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "author_thread_id") val authorThreadId: String,
    @ColumnInfo(name = "recipient_thread_id") val recipientThreadId: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "trigger_turn") val triggerTurn: Boolean,
    @ColumnInfo(name = "parent_turn_id") val parentTurnId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "delivered_at") val deliveredAt: Long?,
)
