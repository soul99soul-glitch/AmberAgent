package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

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
