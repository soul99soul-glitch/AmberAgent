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
