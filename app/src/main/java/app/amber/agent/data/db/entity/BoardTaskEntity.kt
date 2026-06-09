package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object BoardTaskState {
    const val IN_PROGRESS = "in_progress"
    const val WAITING_USER = "waiting_user"
    const val BLOCKED = "blocked"
    const val DONE = "done"
    const val DISMISSED = "dismissed"

    val terminal: Set<String> = setOf(DONE, DISMISSED)
    val rolling: Set<String> = setOf(IN_PROGRESS, WAITING_USER, BLOCKED)
}

object BoardTaskEventType {
    const val CREATED = "created"
    const val UPDATED = "updated"
    const val DISPATCHED = "dispatched"
    const val PROGRESS = "progress"
    const val USER_REPLIED = "user_replied"
    const val CANCELLED = "cancelled"
    const val DONE = "done"
    const val DISMISSED = "dismissed"
    const val WAITING_USER = "waiting_user"
    const val BLOCKED = "blocked"
    const val ARTIFACT_READY = "artifact_ready"
}

@Entity(
    tableName = "board_task",
    indices = [
        Index(value = ["source_type", "source_ref"], unique = true),
        Index("state"),
        Index("display_board_date"),
        Index("updated_at"),
    ],
)
data class BoardTaskEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("source_type")
    val sourceType: String,
    @ColumnInfo("source_ref")
    val sourceRef: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("summary")
    val summary: String,
    @ColumnInfo("state")
    val state: String = BoardTaskState.IN_PROGRESS,
    @ColumnInfo("risk_level")
    val riskLevel: String = "low",
    @ColumnInfo("chip_text")
    val chipText: String = BoardTaskChip.inProgress,
    @ColumnInfo("display_board_date")
    val displayBoardDate: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long = createdAt,
    /**
     * Latest finished artifact for this task (serialized [BoardTaskArtifact]), or null while the
     * task is still running / has not produced one. Single-slot: a new run round overwrites it,
     * and resetting the task to in_progress clears it so the card never shows a stale artifact.
     */
    @ColumnInfo("artifact_json")
    val artifactJson: String? = null,
)

/**
 * Structured final output produced by [BoardTaskRunner] via the `board_task_finish` tool.
 * Deserialized defensively (all fields default) so malformed model arguments degrade to an
 * empty/partial artifact rather than throwing.
 */
@Serializable
data class BoardTaskArtifact(
    val kind: String = "",
    val title: String = "",
    val sections: List<BoardTaskArtifactSection> = emptyList(),
)

@Serializable
data class BoardTaskArtifactSection(
    val heading: String = "",
    val body: String = "",
    val sources: List<String> = emptyList(),
    @SerialName("old_value")
    val oldValue: String? = null,
    @SerialName("new_value")
    val newValue: String? = null,
    @SerialName("upstream_source")
    val upstreamSource: String? = null,
    @SerialName("suggested_rewrite")
    val suggestedRewrite: String? = null,
)

@Entity(
    tableName = "board_task_event",
    indices = [
        Index(value = ["task_id", "ts"]),
        Index("type"),
        Index("ts"),
    ],
)
data class BoardTaskEventEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("task_id")
    val taskId: String,
    @ColumnInfo("type")
    val type: String,
    @ColumnInfo("message")
    val message: String,
    @ColumnInfo("metadata_json")
    val metadataJson: String = "{}",
    @ColumnInfo("ts")
    val ts: Long,
)

data class BoardTaskEventReview(
    @ColumnInfo("task_id")
    val taskId: String,
    @ColumnInfo("task_title")
    val taskTitle: String,
    @ColumnInfo("task_state")
    val taskState: String,
    @ColumnInfo("type")
    val type: String,
    @ColumnInfo("message")
    val message: String,
    @ColumnInfo("ts")
    val ts: Long,
)

object BoardTaskChip {
    const val inProgress = "已经派发"
    const val waitingUser = "等待确认"
    const val blocked = "遇到阻碍"
    const val done = "任务完成"
    const val dismissed = "已忽略"
}

fun stableBoardTaskId(sourceType: String, sourceRef: String): String {
    val input = "$sourceType|$sourceRef"
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(32)
}

fun boardTaskChipForState(state: String): String = when (state) {
    BoardTaskState.IN_PROGRESS -> BoardTaskChip.inProgress
    BoardTaskState.WAITING_USER -> BoardTaskChip.waitingUser
    BoardTaskState.BLOCKED -> BoardTaskChip.blocked
    BoardTaskState.DONE -> BoardTaskChip.done
    BoardTaskState.DISMISSED -> BoardTaskChip.dismissed
    else -> BoardTaskChip.inProgress
}
