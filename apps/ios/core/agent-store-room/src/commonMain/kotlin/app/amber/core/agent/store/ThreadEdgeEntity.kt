package app.amber.core.agent.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 线程边状态（wire 字符串，与 `status` 列同值；新状态追加即兼容）。 */
object ThreadEdgeStatus {
    const val OPEN = "Open"
    const val CLOSED = "Closed"
}

/**
 * P1-c: 线程编排的 spawn 边（会话 = 线程）。
 *
 * - 线程元数据全在 `thread_edge`，**不动** Conversation schema / Android
 *   ConversationEntity——fork 出的会话仍是普通 Conversation，边单独记账。
 * - `childThreadId`/`parentThreadId` = conversationId hex-dash 字符串
 *   （与 `agent_run.conversation_id` 同格式）。
 * - `agentPath`：`/root/{task_name}`（根线程直属）或 `/root/{parent_task}/{task_name}`
 *   （孙线程）；根线程本身无 edge 行，路径约定为 `/root`。
 * - `forkTurns` 记录 spawn 时的 fork 模式（"none"|"all"|"N"），审计用。
 * - `status` Open/Closed；interrupt 不改变状态（线程保留可再派活），显式
 *   followup/清理才可能 Close（P1-d 消费）。
 */
@Entity(
    tableName = "thread_edge",
    indices = [
        Index("parent_thread_id"),
        Index("agent_path"),
    ],
)
data class ThreadEdgeEntity(
    @PrimaryKey
    @ColumnInfo(name = "child_thread_id") val childThreadId: String,
    @ColumnInfo(name = "parent_thread_id") val parentThreadId: String,
    @ColumnInfo(name = "agent_path") val agentPath: String,
    val nickname: String?,
    @ColumnInfo(name = "role_assistant_id") val roleAssistantId: String?,
    @ColumnInfo(name = "fork_turns") val forkTurns: String,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
