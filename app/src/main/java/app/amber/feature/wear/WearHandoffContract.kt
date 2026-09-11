package app.amber.feature.wear

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * E01 (Phase 8): phone-side handoff envelope contract. This is the pure
 * protocol layer the Wear companion will speak over whichever transport is
 * eventually approved (RFCOMM after real-device validation, HTTP with a real
 * endpoint, or the official Data Layer with new dependencies).
 *
 * The transport decision is deliberately out of scope here: what this layer
 * pins is the idempotent handoff semantics both sides must share — the watch
 * persists the requestId BEFORE sending, retries with the SAME id, and the
 * phone registers the id atomically before executing, so a duplicate request
 * returns the already-persisted state instead of double-executing.
 */
@Serializable
data class WearTaskEnvelope(
    /** Stable id minted by the watch; retries MUST reuse it verbatim. */
    val requestId: String,
    val operation: WearOperation,
    val createdAtEpochMs: Long,
    /** Free-form payload for prompts/notes; bounded by the phone on intake. */
    val payload: String = "",
) {
    init {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(payload.length <= MAX_PAYLOAD_CHARS) { "payload exceeds $MAX_PAYLOAD_CHARS chars" }
    }

    companion object {
        const val MAX_PAYLOAD_CHARS = 8_000
    }
}

@Serializable
enum class WearOperation(val wireName: String) {
    @SerialName("ask")
    ASK("ask"),

    @SerialName("quick_note")
    QUICK_NOTE("quick_note"),

    @SerialName("cancel_task")
    CANCEL_TASK("cancel_task"),

    @SerialName("fetch_result")
    FETCH_RESULT("fetch_result");
}

/** Phone-side durable state for a received envelope. */
@Serializable
data class WearHandoffRecord(
    val requestId: String,
    val operation: WearOperation,
    val status: WearHandoffStatus,
    val receivedAtEpochMs: Long,
    /** Agent task id once the handoff has been dispatched to the task owner. */
    val agentTaskId: String? = null,
    val resultReference: String? = null,
    val errorCategory: String? = null,
)

@Serializable
enum class WearHandoffStatus(val wireName: String) {
    @SerialName("accepted")
    ACCEPTED("accepted"),

    @SerialName("pending")
    PENDING("pending"),

    @SerialName("complete")
    COMPLETE("complete"),

    @SerialName("failed")
    FAILED("failed"),
}

/**
 * Idempotent intake decision for an incoming envelope. A duplicate requestId
 * must resolve to Duplicate — never to a second execution — and a malformed
 * envelope is rejected at the boundary instead of entering the task system.
 */
object WearHandoffPolicy {
    sealed interface Intake {
        /** New request: register, then dispatch to the task owner. */
        data class Accept(val envelope: WearTaskEnvelope) : Intake

        /** Same requestId already registered: return the persisted state. */
        data class Duplicate(val record: WearHandoffRecord) : Intake

        /** Invalid request: reject with a reason, nothing is persisted. */
        data class Reject(val reason: String) : Intake
    }

    fun intake(
        envelope: WearTaskEnvelope?,
        existing: WearHandoffRecord?,
        nowEpochMs: Long,
    ): Intake {
        if (envelope == null) return Intake.Reject("envelope is null")
        if (envelope.requestId.isBlank()) return Intake.Reject("requestId is blank")
        if (envelope.createdAtEpochMs > nowEpochMs + CLOCK_SKEW_MS) {
            return Intake.Reject("envelope is from the future beyond clock skew")
        }
        // A retry for a request the phone has already registered is answered
        // from the durable record — whatever its current status.
        existing?.let { return Intake.Duplicate(it) }
        return Intake.Accept(envelope)
    }

    /**
     * A complete/failed record is terminal: retries for it never re-dispatch.
     * accepted/pending records may be polled with fetch_result.
     */
    fun isTerminal(record: WearHandoffRecord): Boolean =
        record.status == WearHandoffStatus.COMPLETE || record.status == WearHandoffStatus.FAILED

    private const val CLOCK_SKEW_MS = 5 * 60_000L
}
