package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Typed Run Terminal — P1-03.
 *
 * One row per generation run, holding the typed [RunTerminalState] and an
 * optional [PauseReason]. WAITING_USER / WAITING_EXTERNAL / RESUMABLE are
 * pauses — not completions and not failures — and the row survives process
 * death so the same runId can be resumed after a cold start.
 *
 * COMPLETED is only published after the assistant result, the messages and
 * the terminal state are all persisted (plan §P1-03 principle). STEP_LIMIT
 * is a terminal state that must never be mapped to COMPLETED.
 */
@Entity(
    tableName = "run_terminal",
    indices = [
        Index("conversation_id"),
        Index("state"),
    ],
)
data class RunTerminalEntity(
    @PrimaryKey
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "assistant_id") val assistantId: String?,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "pause_reason") val pauseReason: String?,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    @ColumnInfo(name = "finished_at_ms") val finishedAtMs: Long?,
)
