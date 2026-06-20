package app.amber.feature.modelcouncil

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

// ─────────────────────────────────────────────────────────────────────────────
// Council Room — full-featured "host-led group chat room" data model.
//
// This is intentionally a PARALLEL subsystem to the legacy ModelCouncil batch
// tool path (ModelCouncilManager + ModelCouncilModels). The legacy path stays
// untouched; this file describes a long-lived, mutable state machine that can
// be opened/invited/switched/synthesized at runtime.
//
// The legacy types (ModelCouncilSeatRunner, ExternalCliToolRegistry, etc.) are
// REUSED as building blocks — only the orchestration model is new.
// ─────────────────────────────────────────────────────────────────────────────

/** Hard cap on participants in a Room (host counts toward this). */
const val DEFAULT_COUNCIL_ROOM_MAX_PARTICIPANTS = 8
// Default auto-deliberation rounds. User-configurable range is [2, 6]; 3 gives
// one opening round + at least one cross-exchange round before synthesis.
const val DEFAULT_COUNCIL_ROOM_MAX_ROUNDS = 3
const val DEFAULT_COUNCIL_ROOM_OUTPUT_BUDGET_CHARS = 12_000
const val DEFAULT_COUNCIL_ROOM_SEAT_TIMEOUT_MS = 180_000L
const val DEFAULT_COUNCIL_ROOM_TOTAL_TIMEOUT_MS = 8 * 60_000L

/** Reserved author id for the host participant. */
const val COUNCIL_ROOM_HOST_ID = "host"

/** Reserved author id for the user participant. */
const val COUNCIL_ROOM_USER_ID = "user"

/**
 * Room discussion mode.
 *
 * - [EXPLORE]: free-form group chat / divergence. Goal: broaden information,
 *   surface possibilities, discover hidden questions. Host acts as facilitator.
 *   Soft labels: idea / signal / question / possibility.
 * - [DEBATE]: adversarial / convergence. Goal: challenge assumptions, find
 *   blind spots, reduce risk. Host acts as moderator/chair. Sharp labels:
 *   claim / counterpoint / risk / evidence.
 * - [SYNTHESIZE]: host collects conclusions and produces the final verdict.
 *   Not a "free" discussion mode — it's the explicit synthesis phase.
 */
@Serializable
enum class CouncilRoomMode {
    @SerialName("explore") EXPLORE,
    @SerialName("debate") DEBATE,
    @SerialName("synthesize") SYNTHESIZE,
}

/** High-level lifecycle of a Room. */
@Serializable
enum class CouncilRoomStatus {
    @SerialName("idle") IDLE,
    @SerialName("exploring") EXPLORING,
    @SerialName("debating") DEBATING,
    @SerialName("finalizing") FINALIZING,
    @SerialName("finalized") FINALIZED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("failed") FAILED,
    @SerialName("interrupted") INTERRUPTED,
}

/** True iff the Room is currently producing turns. */
val CouncilRoomStatus.running: Boolean
    get() = this == CouncilRoomStatus.EXPLORING ||
        this == CouncilRoomStatus.DEBATING ||
        this == CouncilRoomStatus.FINALIZING

/** True iff the Room has reached a terminal state and won't accept more turns. */
val CouncilRoomStatus.terminal: Boolean
    get() = this == CouncilRoomStatus.FINALIZED ||
        this == CouncilRoomStatus.CANCELLED ||
        this == CouncilRoomStatus.FAILED

/** Distinguishes the host (the conversation's main Assistant) from invited guests. */
@Serializable
enum class CouncilParticipantKind {
    @SerialName("host") HOST,
    @SerialName("guest") GUEST,
}

/**
 * Per-participant live status. Drives the roster pill colors:
 * - HOST  → brown "主持"
 * - IDLE  → red "未发言"
 * - INVITED → light-blue "已邀请"
 * - SPEAKING → green "发言中" (pulse animation)
 * - WAITING → gray "等待"
 * - SPOKEN → gray "已发言"
 * - DISMISSED → hidden
 */
@Serializable
enum class CouncilParticipantStatus {
    @SerialName("idle") IDLE,
    @SerialName("invited") INVITED,
    @SerialName("speaking") SPEAKING,
    @SerialName("waiting") WAITING,
    @SerialName("spoken") SPOKEN,
    @SerialName("dismissed") DISMISSED,
}

/**
 * A Room participant.
 *
 * - Host: [id] = [COUNCIL_ROOM_HOST_ID], [modelId] = null (resolved from the
 *   conversation's Assistant at generation time), [kind] = HOST.
 * - Guest: bound to a provider model OR an external CLI runner, [kind] = GUEST.
 *
 * [avatarSeed] feeds the deterministic pixel-avatar generator (mirrors
 * SubAgentAvatar's approach — derived from id+name when blank).
 */
@Serializable
data class CouncilParticipant(
    val id: String,
    val name: String,
    val role: String,
    val kind: CouncilParticipantKind,
    @SerialName("model_id") val modelId: Uuid? = null,
    @SerialName("runner_type") val runnerType: ModelCouncilSeatRunner = ModelCouncilSeatRunner.PROVIDER_MODEL,
    val status: CouncilParticipantStatus = CouncilParticipantStatus.IDLE,
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("avatar_seed") val avatarSeed: String = "",
    @SerialName("output_budget_chars") val outputBudgetChars: Int = DEFAULT_COUNCIL_ROOM_OUTPUT_BUDGET_CHARS,
    @SerialName("reasoning_level") val reasoningLevel: ReasoningLevel? = null,
    val temperature: Float? = null,
    @SerialName("external_tool") val externalTool: String = "",
    @SerialName("external_runtime") val externalRuntime: String = "",
    @SerialName("external_model") val externalModel: String = "",
    @SerialName("provider_name") val providerName: String = "",
    @SerialName("model_name") val modelName: String = "",
)

/** Live status of a single Room message (turn). */
@Serializable
enum class CouncilMessageStatus {
    @SerialName("pending") PENDING,
    @SerialName("streaming") STREAMING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("timed_out") TIMED_OUT,
}

val CouncilMessageStatus.running: Boolean
    get() = this == CouncilMessageStatus.PENDING || this == CouncilMessageStatus.STREAMING

/**
 * Optional discriminator for special host messages. Default null = a normal
 * turn (the overwhelmingly common case). [ASK_USER] marks a host message that
 * is actually a question to the user — the timeline renders an answer card
 * instead of a bubble, and [CouncilRoomManager.runAutoOrchestration] is
 * suspended until the user answers.
 */
@Serializable
enum class CouncilMessageKind {
    @SerialName("ask_user") ASK_USER,
    /** Host started a review/steer but decided "no comment" — shown as a withdrawn bubble ("主持人撤回发言"). */
    @SerialName("withdrawn") WITHDRAWN,
}

/**
 * One entry in the Room timeline. This is NOT a UIMessage — it's an internal
 * event-stream entry owned by the Room state machine, persisted inside
 * ConversationEntity.council_state.
 *
 * Guest-to-guest reference graph (the key new capability vs the legacy flat
 * turns list):
 * - [replyToMessageId]: direct reply to another message ("回复 X")
 * - [continuesFromMessageId]: continues/elaborates another guest's point ("延续 X 的论点")
 * - [invitedBy]: the participant id that invited this author ("由 Host 邀请")
 *
 * [role] is the author's role label snapshot at send time (for export/stability
 * even if the participant is later dismissed).
 */
@Serializable
data class CouncilMessage(
    val id: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("author_name") val authorName: String,
    val role: String,
    val round: Int,
    val mode: CouncilRoomMode,
    val text: String,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("reply_to_message_id") val replyToMessageId: String? = null,
    @SerialName("continues_from_message_id") val continuesFromMessageId: String? = null,
    @SerialName("invited_by") val invitedBy: String? = null,
    val status: CouncilMessageStatus = CouncilMessageStatus.COMPLETED,
    val warnings: List<String> = emptyList(),
    val error: String = "",
    /**
     * User-attached media (Image + Document parts) carried on a user message.
     * Images are passed to members multimodally; documents are shown as chips.
     * Defaulted empty so old persisted council_state decodes without migration.
     */
    val attachments: List<UIMessagePart> = emptyList(),
    /**
     * Text extracted from [attachments] documents at send time (the council's
     * generation path has no document parser), injected into member prompts so
     * they can read file contents. NOT shown in the bubble (the chip is).
     */
    @SerialName("attachment_text") val attachmentText: String = "",
    /**
     * Optional message discriminator. null = a normal turn. [CouncilMessageKind.ASK_USER]
     * = the host is asking the user a question (timeline renders an answer card, the
     * orchestration is suspended). Defaulted null so old persisted council_state
     * decodes without migration.
     */
    val kind: CouncilMessageKind? = null,
)

/**
 * A system-event divider in the timeline. Examples:
 * - "Explore · Opening"
 * - "Debate · Cross-response"
 * - "DeepSeek 加入"
 * - "切换到 Debate"
 * - "Host synthesis"
 *
 * Merged into the timeline view alongside [CouncilMessage]s, sorted by createdAtMs.
 */
@Serializable
data class CouncilPhaseMarker(
    val id: String,
    val label: String,
    val mode: CouncilRoomMode,
    @SerialName("created_at_ms") val createdAtMs: Long,
)

/**
 * A host-led council room. One conversation has at most one Room.
 *
 * Long-lived mutable state machine — contrast with [ModelCouncilRun] which is
 * a one-shot batch. All mutations go through [CouncilRoomManager] which
 * enforces status transitions and persists to ConversationEntity.council_state.
 */
@Serializable
data class CouncilRoom(
    /** = conversationId.toString(); used as persistence key. */
    val id: String,
    @SerialName("conversation_id") val conversationId: Uuid,
    @SerialName("host_assistant_id") val hostAssistantId: Uuid,
    val objective: String,
    val context: String = "",
    val mode: CouncilRoomMode = CouncilRoomMode.EXPLORE,
    val status: CouncilRoomStatus = CouncilRoomStatus.IDLE,
    val participants: List<CouncilParticipant> = emptyList(),
    val messages: List<CouncilMessage> = emptyList(),
    @SerialName("phase_markers") val phaseMarkers: List<CouncilPhaseMarker> = emptyList(),
    val synthesis: String = "",
    val warnings: List<String> = emptyList(),
    val round: Int = 0,
    @SerialName("max_rounds") val maxRounds: Int = DEFAULT_COUNCIL_ROOM_MAX_ROUNDS,
    @SerialName("max_participants") val maxParticipants: Int = DEFAULT_COUNCIL_ROOM_MAX_PARTICIPANTS,
    @SerialName("output_budget_chars") val outputBudgetChars: Int = DEFAULT_COUNCIL_ROOM_OUTPUT_BUDGET_CHARS,
    @SerialName("seat_timeout_ms") val seatTimeoutMs: Long = DEFAULT_COUNCIL_ROOM_SEAT_TIMEOUT_MS,
    @SerialName("total_timeout_ms") val totalTimeoutMs: Long = DEFAULT_COUNCIL_ROOM_TOTAL_TIMEOUT_MS,
    /** Optional override; null = use the host Assistant's configured model. */
    @SerialName("host_model_id_override") val hostModelIdOverride: Uuid? = null,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("updated_at_ms") val updatedAtMs: Long = createdAtMs,
    @SerialName("finished_at_ms") val finishedAtMs: Long? = null,
) {
    /** The host participant, or null if not yet materialized. */
    val host: CouncilParticipant?
        get() = participants.firstOrNull { it.kind == CouncilParticipantKind.HOST }

    /** Active (non-dismissed) guest participants. */
    val activeGuests: List<CouncilParticipant>
        get() = participants.filter {
            it.kind == CouncilParticipantKind.GUEST && it.status != CouncilParticipantStatus.DISMISSED
        }

    /** Lookup helper that tolerates missing participants (e.g. after dismissal). */
    fun participantById(id: String): CouncilParticipant? =
        participants.firstOrNull { it.id == id }
}

/**
 * Map a configured [ModelCouncilSeat] (from the experimental Model Council
 * settings' `defaultSeats`) into a Room guest participant. This is the bridge
 * that makes "configure seats in settings → those seats join the Room" work:
 * the Council Room opens with the host + every configured seat as a guest.
 *
 * `providerName` / `modelName` are left blank here (display labels are resolved
 * from `modelId` at render time); everything the executor needs to run the turn
 * (modelId, runner type, prompt, budget, reasoning, temperature, external CLI
 * binding) is carried over verbatim.
 */
fun ModelCouncilSeat.toCouncilParticipant(): CouncilParticipant = CouncilParticipant(
    id = seatId,
    name = name,
    role = role,
    kind = CouncilParticipantKind.GUEST,
    modelId = modelId,
    runnerType = runnerType,
    status = CouncilParticipantStatus.IDLE,
    systemPrompt = systemPrompt,
    outputBudgetChars = outputBudgetChars,
    reasoningLevel = reasoningLevel,
    temperature = temperature,
    externalTool = externalTool,
    externalRuntime = externalRuntime,
    externalModel = externalModel,
)
