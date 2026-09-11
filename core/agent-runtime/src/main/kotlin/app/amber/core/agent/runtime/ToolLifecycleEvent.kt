package app.amber.core.agent.runtime

import kotlinx.serialization.Serializable

/**
 * Tool lifecycle as protocol events (Step 3): every durable tool execution
 * leaves `ToolPrepared → ToolStarted → ToolFinished` rows in the run's event
 * stream, each carrying the ToolEffectLedger [effectId] so the event log and
 * the write-ahead ledger describe the same execution and can be cross-joined.
 *
 * Emission happens only on the durable path (ledger context active), because
 * [effectId] is minted by the ledger — a non-durable run has no ledger row to
 * align with and therefore stays silent here, exactly as it stays invisible
 * to cold-start recovery.
 *
 * Wire type names are explicit constants ("ToolPrepared" ...) rather than the
 * class simple names, which would be uselessly generic in the shared
 * `agent_event.type` column.
 */
sealed class ToolLifecycleEvent : AgentEventPayload.Final {

    abstract val effectId: String
    abstract val toolCallId: String
    abstract val toolName: String

    /**
     * The model requested the tool and the run validated + digested +
     * persisted the PREPARED effect — fired before any approval is shown.
     */
    @Serializable
    data class Prepared(
        override val effectId: String,
        override val toolCallId: String,
        override val toolName: String,
        val effectClass: String,
        val argsDigest: String,
    ) : ToolLifecycleEvent()

    /**
     * Execution actually began (approval still valid, no outcome-unknown /
     * abandoned block). [approvalDigest] binds the executed args to the
     * approval the user granted, mirroring the ledger's STARTED row.
     */
    @Serializable
    data class Started(
        override val effectId: String,
        override val toolCallId: String,
        override val toolName: String,
        val approvalDigest: String? = null,
    ) : ToolLifecycleEvent()

    /**
     * The effect reached a settled state: FINISHED (result stored), FAILED
     * (execution error, [errorCategory] set) or DENIED (approval refused —
     * the ledger records these as FAILED with category `approval_denied`).
     * A cancelled execution emits nothing: its effect stays STARTED and the
     * recovery rules classify it.
     */
    @Serializable
    data class Finished(
        override val effectId: String,
        override val toolCallId: String,
        override val toolName: String,
        val status: Status,
        val errorCategory: String? = null,
    ) : ToolLifecycleEvent() {

        @Serializable
        enum class Status { FINISHED, FAILED, DENIED }
    }

    companion object {
        const val TYPE_PREPARED = "ToolPrepared"
        const val TYPE_STARTED = "ToolStarted"
        const val TYPE_FINISHED = "ToolFinished"
    }
}
