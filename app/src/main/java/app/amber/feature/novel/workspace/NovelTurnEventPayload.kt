package app.amber.feature.novel.workspace

import app.amber.core.agent.runtime.AgentEventPayload
import kotlinx.serialization.Serializable

/**
 * Novel workspace turn domain events (Step 3). Novel turns deliberately run
 * the bare non-durable loop — the workspace keeps its own git-style ledger
 * with proposal/rollback semantics, so the ToolEffectLedger's write-ahead
 * would be a parallel accounting that nothing reads. These events are the
 * turn's trail in the run's event stream, mapped 1:1 from the runtime's
 * TurnEvent stream (no invented semantics); Delta/ReasoningDelta stay
 * caller-side render hints and are not persisted.
 */
sealed interface NovelTurnEventPayload : AgentEventPayload.Final {

    /** The model invoked a workspace tool (first sighting per tool call). */
    @Serializable
    data class ToolActivity(
        val toolName: String,
    ) : NovelTurnEventPayload

    /** The turn settled successfully; [proposalId] references the pending write proposal when one was produced. */
    @Serializable
    data class TurnCompleted(
        val finalTextLength: Int,
        val proposalId: String?,
        val proposalEntryCount: Int,
    ) : NovelTurnEventPayload

    @Serializable
    data class TurnFailed(
        val message: String,
    ) : NovelTurnEventPayload

    companion object {
        const val TYPE_TOOL_ACTIVITY = "NovelToolActivity"
        const val TYPE_TURN_COMPLETED = "NovelTurnCompleted"
        const val TYPE_TURN_FAILED = "NovelTurnFailed"
    }
}
