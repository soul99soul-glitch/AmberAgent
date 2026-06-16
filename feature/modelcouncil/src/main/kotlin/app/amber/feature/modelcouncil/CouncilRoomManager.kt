package app.amber.feature.modelcouncil

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import app.amber.core.infra.AppScope
import app.amber.core.settings.Settings
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskQueueState
import kotlin.uuid.Uuid

/**
 * Host-action vocabulary — explicit runtime affordances for the room's host
 * (the conversation's main Assistant). Each variant maps 1:1 to a host message
 * + a guest turn trigger. PR2 wires the generation side; PR1 only carries them
 * through the state machine and emits the host message.
 *
 * Mirrors the iOS design's "host control layer": Invite / Ask next / Let guests
 * respond / Switch to Debate / Synthesize / Stop.
 */
sealed interface HostAction {
    /** Invite an already-rostered guest to speak, optionally with a directive. */
    data class InviteNext(val participantId: String, val instruction: String = "") : HostAction

    /** Ask a guest to follow up on a specific prior message. */
    data class AskFollowUp(val toParticipantId: String, val aboutMessageId: String) : HostAction

    /** Let one or more guests respond to / continue a seed message. */
    data class LetGuestsRespond(val seedMessageId: String, val participantIds: List<String> = emptyList()) : HostAction

    /** Redirect a guest mid-discussion. */
    data class Redirect(val participantId: String, val newDirection: String) : HostAction

    data object SwitchToExplore : HostAction
    data object SwitchToDebate : HostAction
    data object Synthesize : HostAction
    data object Stop : HostAction
}

/** Result type for manager ops that can fail with a structured error. */
sealed interface CouncilRoomOpResult {
    data class Ok(val room: CouncilRoom) : CouncilRoomOpResult
    data class Err(val code: String, val message: String) : CouncilRoomOpResult
}

/**
 * Preflight result: either a structured error, or a [GuestTurnPlan] embedding
 * the next room state plus the generation parameters. Used by [CouncilRoomManager.mutatePreflight]
 * so host actions can persist the room under the lock, then launch generation
 * after releasing it (m3 fix — generation must not hold the room mutex).
 */
sealed interface PreflightResult {
    data class Err(val code: String, val message: String) : PreflightResult
    data class Plan(val plan: GuestTurnPlan) : PreflightResult
}

/**
 * Carries everything [CouncilRoomExecutor.generateGuestTurn] needs, captured
 * synchronously inside the room mutex. [extraGuests] supports multi-target
 * host actions (LetGuestsRespond) — each gets its own generation job.
 */
data class GuestTurnPlan(
    val room: CouncilRoom,
    val guest: CouncilParticipant,
    val userPrompt: String,
    val replyToMessageId: String? = null,
    val continuesFromMessageId: String? = null,
    val invitedBy: String? = null,
    val extraGuests: List<GuestTurnPlan> = emptyList(),
)

/**
 * Long-lived, mutable state machine for a host-led Council Room.
 *
 * Contrast with [ModelCouncilManager] which is a one-shot batch (start → fixed
 * seats/rounds/mode → finish). Here every dimension is mutable at runtime:
 * invite/dismiss participants, switch mode, inject user messages, trigger host
 * actions, synthesize. All mutations go through this manager, which enforces
 * status transitions and persists via [CouncilRoomStore].
 *
 * Concurrency: one in-flight mutation per conversation (per-room [Mutex]); the
 * generation coroutines launched by host actions run on [appScope] and append
 * turns back through the same mutex via [RoomMutationSink].
 *
 * Memory: active rooms are held in [store]'s in-memory cache; [close] evicts.
 */
class CouncilRoomManager(
    private val appScope: AppScope,
    private val settingsFlow: StateFlow<Settings>,
    private val json: Json,
    private val modelRunner: ModelCouncilTextRunner,
    private val externalCliRunner: ModelCouncilExternalCliRunner,
    private val store: CouncilRoomStore,
    private val taskReporter: CouncilRoomTaskReporter,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RoomMutationSink {
    /** Lazy executor — constructed once with `this` as the sink. */
    private val executor: CouncilRoomExecutor by lazy {
        CouncilRoomExecutor(modelRunner, externalCliRunner, this, dispatcher)
    }

    /**
     * Per-conversation in-flight generation jobs and close-gate state.
     *
     * A single [jobsLock] protects the guest job list, the active synthesis job,
     * and the set of conversations currently being closed. The closing gate
     * prevents new generation jobs from starting after [close] begins, plugging
     * the race where [trackGenerationJob] used to be async and could miss a
     * concurrent close.
     */
    private val jobsLock = Mutex()
    private val generationJobs = mutableMapOf<Uuid, MutableList<Job>>()
    private val synthesisJobs = mutableMapOf<Uuid, Job>()
    private val closingConversationIds = mutableSetOf<Uuid>()

    /** Per-conversation mutation lock; prevents races between host actions / user input. */
    private val locks = mutableMapOf<Uuid, Mutex>()
    private val locksLock = Mutex()

    private suspend fun lockFor(conversationId: Uuid): Mutex = locksLock.withLock {
        locks.getOrPut(conversationId) { Mutex() }
    }

    /**
     * Open a Room for a conversation. Fails if one already exists and is not
     * terminal. The host participant is materialized from [hostAssistantId]
     * (the conversation's main Assistant).
     *
     * Returns the new Room id (= conversationId) on Ok.
     */
    suspend fun openRoom(
        conversationId: Uuid,
        hostAssistantId: Uuid,
        hostName: String,
        objective: String,
        context: String = "",
        initialMode: CouncilRoomMode = CouncilRoomMode.EXPLORE,
        initialGuests: List<CouncilParticipant> = emptyList(),
        maxRounds: Int = DEFAULT_COUNCIL_ROOM_MAX_ROUNDS,
        maxParticipants: Int = DEFAULT_COUNCIL_ROOM_MAX_PARTICIPANTS,
    ): CouncilRoomOpResult {
        // SYNTHESIZE is a terminal-ish phase reachable only via synthesize().
        if (initialMode == CouncilRoomMode.SYNTHESIZE) {
            return CouncilRoomOpResult.Err(
                code = "invalid_initial_mode",
                message = "Cannot open a Room in SYNTHESIZE mode; use EXPLORE or DEBATE.",
            )
        }
        val effectiveMaxParticipants = maxParticipants.coerceIn(2, MAX_PARTICIPANTS_CAP)
        // Roster overflow is an explicit error rather than silent truncation —
        // a caller passing more guests than capacity is almost certainly a bug.
        if (initialGuests.size + 1 > effectiveMaxParticipants) {
            return CouncilRoomOpResult.Err(
                code = "initial_roster_overflow",
                message = "initialGuests (${initialGuests.size}) + host exceeds maxParticipants ($effectiveMaxParticipants).",
            )
        }
        val mutex = lockFor(conversationId)
        return mutex.withLock {
            val flow = store.observeRoom(conversationId)
            val existing = flow.value
            if (existing != null && !existing.status.terminal) {
                return@withLock CouncilRoomOpResult.Err(
                    code = "room_already_open",
                    message = "A Council Room is already active for this conversation. Close it first.",
                )
            }
            val now = nowMs()
            val host = CouncilParticipant(
                id = COUNCIL_ROOM_HOST_ID,
                name = hostName.ifBlank { "Host" },
                role = "host",
                kind = CouncilParticipantKind.HOST,
                status = CouncilParticipantStatus.IDLE,
            )
            val participants = buildList {
                add(host)
                initialGuests.forEach { guest -> add(guest.copy(status = CouncilParticipantStatus.INVITED)) }
            }
            val room = CouncilRoom(
                id = conversationId.toString(),
                conversationId = conversationId,
                hostAssistantId = hostAssistantId,
                objective = objective.take(MAX_OBJECTIVE_CHARS),
                context = context.take(MAX_CONTEXT_CHARS),
                mode = initialMode,
                status = statusForMode(initialMode),
                participants = participants,
                phaseMarkers = listOf(CouncilPhaseMarker(
                    id = msgId(),
                    label = phaseOpenLabel(initialMode),
                    mode = initialMode,
                    createdAtMs = now,
                )),
                maxRounds = maxRounds.coerceIn(1, MAX_ROUNDS_CAP),
                maxParticipants = effectiveMaxParticipants,
                createdAtMs = now,
                updatedAtMs = now,
            )
            store.upsertRoom(room)
            registerTask(room)
            CouncilRoomOpResult.Ok(room)
        }
    }

    /** Observe the Room state. Cold-loads from storage on first access. */
    suspend fun observeRoom(conversationId: Uuid): StateFlow<CouncilRoom?> =
        store.observeRoom(conversationId)

    /** Synchronous snapshot; null if absent/not loaded. */
    fun peekRoom(conversationId: Uuid): CouncilRoom? = store.peekRoom(conversationId)

    // ── RoomMutationSink (writes streaming generation back into the room) ───

    override suspend fun upsertStreamingMessage(conversationId: Uuid, message: CouncilMessage) {
        mutate(conversationId) { room ->
            if (room.status.terminal) {
                return@mutate CouncilRoomOpResult.Ok(room)
            }
            val existing = room.messages.firstOrNull { it.id == message.id }
            val messages = if (existing != null) {
                // Streaming updates are sparse (only id + text); merge text into
                // the existing message so author/round/mode/reference graph stay.
                room.messages.map { m ->
                    if (m.id == message.id) {
                        m.copy(
                            text = message.text,
                            status = CouncilMessageStatus.STREAMING,
                        )
                    } else {
                        m
                    }
                }
            } else {
                room.messages + message
            }
            val authorId = existing?.authorId ?: message.authorId
            val participants = room.participants.map { p ->
                if (p.id == authorId) {
                    p.copy(status = CouncilParticipantStatus.SPEAKING)
                } else {
                    p
                }
            }
            CouncilRoomOpResult.Ok(room.copy(
                messages = messages,
                participants = participants,
                updatedAtMs = nowMs(),
            ))
        }
    }

    override suspend fun completeMessage(
        conversationId: Uuid,
        messageId: String,
        status: CouncilMessageStatus,
        text: String,
        warnings: List<String>,
        error: String,
        authorId: String,
        authorStatus: CouncilParticipantStatus,
    ) {
        mutate(conversationId) { room ->
            if (room.status.terminal) {
                return@mutate CouncilRoomOpResult.Ok(room)
            }
            val messages = room.messages.map { m ->
                if (m.id == messageId) {
                    m.copy(
                        status = status,
                        text = text,
                        warnings = warnings,
                        error = error,
                    )
                } else {
                    m
                }
            }
            val participants = room.participants.map { p ->
                if (p.id == authorId) p.copy(status = authorStatus) else p
            }
            CouncilRoomOpResult.Ok(room.copy(
                messages = messages,
                participants = participants,
                updatedAtMs = nowMs(),
            ))
        }
    }

    override suspend fun completeSynthesis(
        conversationId: Uuid,
        synthesis: String,
        warnings: List<String>,
    ) {
        mutate(conversationId) { room ->
            if (room.status.terminal) {
                return@mutate CouncilRoomOpResult.Ok(room)
            }
            val now = nowMs()
            CouncilRoomOpResult.Ok(room.copy(
                mode = CouncilRoomMode.SYNTHESIZE,
                status = CouncilRoomStatus.FINALIZED,
                synthesis = synthesis,
                warnings = warnings,
                finishedAtMs = now,
                updatedAtMs = now,
            ))
        }
    }

    /**
     * Invite a participant at runtime. No-op (Err) if the room is terminal or
     * the roster is full. Dedupes by participant id.
     */
    suspend fun inviteParticipant(
        conversationId: Uuid,
        participant: CouncilParticipant,
    ): CouncilRoomOpResult = mutate(conversationId) { room ->
        if (room.status.terminal) {
            return@mutate CouncilRoomOpResult.Err("room_terminal", "Room has ended; cannot invite.")
        }
        if (room.participants.any { it.id == participant.id }) {
            return@mutate CouncilRoomOpResult.Err("duplicate_participant", "Participant ${participant.id} already in room.")
        }
        if (room.participants.size >= room.maxParticipants) {
            return@mutate CouncilRoomOpResult.Err("roster_full", "Room is at max participants (${room.maxParticipants}).")
        }
        val guest = participant.copy(
            kind = CouncilParticipantKind.GUEST,
            status = CouncilParticipantStatus.INVITED,
        )
        val now = nowMs()
        CouncilRoomOpResult.Ok(room.copy(
            participants = room.participants + guest,
            phaseMarkers = room.phaseMarkers + CouncilPhaseMarker(
                id = msgId(),
                label = "${guest.name} 加入",
                mode = room.mode,
                createdAtMs = now,
            ),
            updatedAtMs = now,
        ))
    }

    /** Dismiss a participant (marks DISMISSED, keeps history). Host cannot be dismissed. */
    suspend fun dismissParticipant(
        conversationId: Uuid,
        participantId: String,
    ): CouncilRoomOpResult = mutate(conversationId) { room ->
        if (participantId == COUNCIL_ROOM_HOST_ID) {
            return@mutate CouncilRoomOpResult.Err("cannot_dismiss_host", "Host cannot be dismissed.")
        }
        val updated = room.participants.map { p ->
            if (p.id == participantId) p.copy(status = CouncilParticipantStatus.DISMISSED) else p
        }
        if (updated == room.participants) {
            return@mutate CouncilRoomOpResult.Err("not_found", "Participant $participantId not in room.")
        }
        CouncilRoomOpResult.Ok(room.copy(participants = updated, updatedAtMs = nowMs()))
    }

    /**
     * Switch discussion mode. Valid targets: EXPLORE ↔ DEBATE. SYNTHESIZE is
     * reached only via [synthesize]. Emits a phase marker. Resets round to 0
     * when entering DEBATE from EXPLORE (debate counts its own rounds).
     */
    suspend fun switchMode(
        conversationId: Uuid,
        mode: CouncilRoomMode,
    ): CouncilRoomOpResult = mutate(conversationId) { room ->
        if (room.status.terminal) {
            return@mutate CouncilRoomOpResult.Err("room_terminal", "Room has ended; cannot switch mode.")
        }
        if (mode == CouncilRoomMode.SYNTHESIZE) {
            return@mutate CouncilRoomOpResult.Err("invalid_mode_switch", "Use synthesize() to enter SYNTHESIZE.")
        }
        if (room.mode == mode) {
            return@mutate CouncilRoomOpResult.Ok(room)
        }
        val now = nowMs()
        CouncilRoomOpResult.Ok(room.copy(
            mode = mode,
            status = statusForMode(mode),
            round = if (mode == CouncilRoomMode.DEBATE) 0 else room.round,
            phaseMarkers = room.phaseMarkers + CouncilPhaseMarker(
                id = msgId(),
                label = "切换到 ${modeLabel(mode)}",
                mode = mode,
                createdAtMs = now,
            ),
            updatedAtMs = now,
        ))
    }

    /**
     * Append a user message into the room timeline. [mentionTargets] are
     * participant ids the user @-mentioned; PR2 uses these to route follow-ups.
     * Returns the updated room.
     */
    suspend fun userMessage(
        conversationId: Uuid,
        text: String,
        mentionTargets: List<String> = emptyList(),
    ): CouncilRoomOpResult = mutate(conversationId) { room ->
        if (room.status.terminal) {
            return@mutate CouncilRoomOpResult.Err("room_terminal", "Room has ended; cannot send messages.")
        }
        if (text.isBlank()) {
            return@mutate CouncilRoomOpResult.Err("empty_message", "Message text is empty.")
        }
        val now = nowMs()
        val userMessage = CouncilMessage(
            id = msgId(),
            authorId = COUNCIL_ROOM_USER_ID,
            authorName = "You",
            role = "user",
            round = room.round,
            mode = room.mode,
            text = text.take(MAX_MESSAGE_CHARS),
            createdAtMs = now,
            status = CouncilMessageStatus.COMPLETED,
        )
        CouncilRoomOpResult.Ok(room.copy(
            messages = room.messages + userMessage,
            updatedAtMs = now,
        ))
    }

    /**
     * Append a host message (the room owner speaking). Used by host-action
     * routing in PR2 (e.g. "DeepSeek，你先从推理角度判断"). The host's actual
     * text generation is PR2; PR1 exposes this so the wiring is testable.
     */
    suspend fun hostMessage(
        conversationId: Uuid,
        text: String,
    ): CouncilRoomOpResult = mutate(conversationId) { room ->
        if (room.status.terminal) {
            return@mutate CouncilRoomOpResult.Err("room_terminal", "Room has ended.")
        }
        if (text.isBlank()) return@mutate CouncilRoomOpResult.Ok(room)
        val now = nowMs()
        val host = room.host ?: return@mutate CouncilRoomOpResult.Err("no_host", "Host participant missing.")
        CouncilRoomOpResult.Ok(room.copy(
            messages = room.messages + CouncilMessage(
                id = msgId(),
                authorId = host.id,
                authorName = host.name,
                role = host.role,
                round = room.round,
                mode = room.mode,
                text = text.take(MAX_MESSAGE_CHARS),
                createdAtMs = now,
                status = CouncilMessageStatus.COMPLETED,
            ),
            updatedAtMs = now,
        ))
    }

    /**
     * Execute a host action. PR1 implements the bookkeeping (mode switches,
     * phase markers, host message echoes); PR2 adds the guest generation that
     * [InviteNext] / [AskFollowUp] / [LetGuestsRespond] trigger.
     */
    suspend fun hostAction(
        conversationId: Uuid,
        action: HostAction,
    ): CouncilRoomOpResult = when (action) {
        HostAction.SwitchToExplore -> switchMode(conversationId, CouncilRoomMode.EXPLORE)
        HostAction.SwitchToDebate -> switchMode(conversationId, CouncilRoomMode.DEBATE)
        HostAction.Synthesize -> synthesize(conversationId)
        HostAction.Stop -> close(conversationId, cancel = true)
        is HostAction.InviteNext -> hostActionInviteNext(conversationId, action)
        is HostAction.AskFollowUp -> hostActionAskFollowUp(conversationId, action)
        is HostAction.LetGuestsRespond -> hostActionLetGuestsRespond(conversationId, action)
        is HostAction.Redirect -> hostActionRedirect(conversationId, action)
    }

    private suspend fun hostActionInviteNext(
        conversationId: Uuid,
        action: HostAction.InviteNext,
    ): CouncilRoomOpResult {
        val preflight = mutatePreflight(conversationId) { room ->
            val guest = room.participantById(action.participantId)
                ?: return@mutatePreflight preflightNoParticipant(action.participantId)
            if (guest.kind != CouncilParticipantKind.GUEST) {
                return@mutatePreflight PreflightResult.Err("not_a_guest", "Only guests can be invited to speak.")
            }
            val now = nowMs()
            val hostEcho = if (action.instruction.isBlank()) {
                "${guest.name}，请发言。"
            } else {
                "${guest.name}，${action.instruction}"
            }
            val updated = room.copy(
                participants = room.participants.map { p ->
                    if (p.id == guest.id) p.copy(status = CouncilParticipantStatus.WAITING) else p
                },
                messages = room.messages + CouncilMessage(
                    id = msgId(),
                    authorId = COUNCIL_ROOM_HOST_ID,
                    authorName = room.host?.name ?: "Host",
                    role = "host",
                    round = room.round,
                    mode = room.mode,
                    text = hostEcho.take(MAX_MESSAGE_CHARS),
                    createdAtMs = now,
                    status = CouncilMessageStatus.COMPLETED,
                ),
                updatedAtMs = now,
            )
            PreflightResult.Plan(GuestTurnPlan(
                room = updated,
                guest = guest,
                userPrompt = CouncilRoomPrompts.invitedByHostPrompt(updated, guest, action.instruction),
                replyToMessageId = null,
                continuesFromMessageId = null,
                invitedBy = COUNCIL_ROOM_HOST_ID,
            ))
        }
        return launchGuestTurn(preflight, conversationId)
    }

    private suspend fun hostActionAskFollowUp(
        conversationId: Uuid,
        action: HostAction.AskFollowUp,
    ): CouncilRoomOpResult {
        val preflight = mutatePreflight(conversationId) { room ->
            val guest = room.participantById(action.toParticipantId)
                ?: return@mutatePreflight preflightNoParticipant(action.toParticipantId)
            val seed = room.messages.firstOrNull { it.id == action.aboutMessageId }
                ?: return@mutatePreflight PreflightResult.Err("seed_not_found", "Message ${action.aboutMessageId} not found.")
            if (guest.kind != CouncilParticipantKind.GUEST) {
                return@mutatePreflight PreflightResult.Err("not_a_guest", "Only guests can be asked.")
            }
            val now = nowMs()
            val hostName = room.host?.name ?: "Host"
            val updated = room.copy(
                participants = room.participants.map { p ->
                    if (p.id == guest.id) p.copy(status = CouncilParticipantStatus.WAITING) else p
                },
                messages = room.messages + CouncilMessage(
                    id = msgId(),
                    authorId = COUNCIL_ROOM_HOST_ID,
                    authorName = hostName,
                    role = "host",
                    round = room.round,
                    mode = room.mode,
                    text = "${guest.name}，针对 ${seed.authorName} 的发言补充回应。".take(MAX_MESSAGE_CHARS),
                    createdAtMs = now,
                    status = CouncilMessageStatus.COMPLETED,
                ),
                updatedAtMs = now,
            )
            PreflightResult.Plan(GuestTurnPlan(
                room = updated,
                guest = guest,
                userPrompt = CouncilRoomPrompts.followUpPrompt(updated, guest, seed),
                replyToMessageId = seed.id,
                continuesFromMessageId = null,
                invitedBy = COUNCIL_ROOM_HOST_ID,
            ))
        }
        return launchGuestTurn(preflight, conversationId)
    }

    private suspend fun hostActionLetGuestsRespond(
        conversationId: Uuid,
        action: HostAction.LetGuestsRespond,
    ): CouncilRoomOpResult {
        // Multi-target: preflight validates + writes the host echo, then we launch
        // one generation per target guest. Each gets its own continuesFromMessageId.
        val preflight = mutatePreflight(conversationId) { room ->
            val seed = room.messages.firstOrNull { it.id == action.seedMessageId }
                ?: return@mutatePreflight PreflightResult.Err("seed_not_found", "Message ${action.seedMessageId} not found.")
            val targets = action.participantIds.ifEmpty { room.activeGuests.map { it.id } }
            val validGuests = targets.mapNotNull { id -> room.participantById(id) }
                .filter { it.kind == CouncilParticipantKind.GUEST && it.status != CouncilParticipantStatus.DISMISSED }
            if (validGuests.isEmpty()) {
                return@mutatePreflight PreflightResult.Err("no_guests", "No guests to respond.")
            }
            val now = nowMs()
            val hostName = room.host?.name ?: "Host"
            val updated = room.copy(
                participants = room.participants.map { p ->
                    if (p.id in validGuests.map { it.id }) p.copy(status = CouncilParticipantStatus.WAITING) else p
                },
                messages = room.messages + CouncilMessage(
                    id = msgId(),
                    authorId = COUNCIL_ROOM_HOST_ID,
                    authorName = hostName,
                    role = "host",
                    round = room.round,
                    mode = room.mode,
                    text = "请各位针对 ${seed.authorName} 的发言补充或反驳。".take(MAX_MESSAGE_CHARS),
                    createdAtMs = now,
                    status = CouncilMessageStatus.COMPLETED,
                ),
                updatedAtMs = now,
            )
            // Multi-plan: return the first as the primary; launch rest separately.
            PreflightResult.Plan(GuestTurnPlan(
                room = updated,
                guest = validGuests.first(),
                userPrompt = CouncilRoomPrompts.followUpPrompt(updated, validGuests.first(), seed),
                replyToMessageId = null,
                continuesFromMessageId = seed.id,
                invitedBy = COUNCIL_ROOM_HOST_ID,
                extraGuests = validGuests.drop(1).map { g ->
                    GuestTurnPlan(
                        room = updated,
                        guest = g,
                        userPrompt = CouncilRoomPrompts.followUpPrompt(updated, g, seed),
                        replyToMessageId = null,
                        continuesFromMessageId = seed.id,
                        invitedBy = COUNCIL_ROOM_HOST_ID,
                    )
                },
            ))
        }
        return launchGuestTurn(preflight, conversationId)
    }

    private suspend fun hostActionRedirect(
        conversationId: Uuid,
        action: HostAction.Redirect,
    ): CouncilRoomOpResult = mutate(conversationId) { room ->
        val guest = room.participantById(action.participantId)
            ?: return@mutate noParticipant(room, action.participantId)
        if (guest.kind != CouncilParticipantKind.GUEST) {
            return@mutate CouncilRoomOpResult.Err("not_a_guest", "Only guests can be redirected.")
        }
        if (guest.status == CouncilParticipantStatus.DISMISSED) {
            return@mutate CouncilRoomOpResult.Err("participant_dismissed", "${guest.name} has been dismissed.")
        }
        val now = nowMs()
        val hostName = room.host?.name ?: "Host"
        CouncilRoomOpResult.Ok(room.copy(
            messages = room.messages + CouncilMessage(
                id = msgId(),
                authorId = COUNCIL_ROOM_HOST_ID,
                authorName = hostName,
                role = "host",
                round = room.round,
                mode = room.mode,
                text = "${guest.name}，换个方向：${action.newDirection}".take(MAX_MESSAGE_CHARS),
                createdAtMs = now,
                status = CouncilMessageStatus.COMPLETED,
            ),
            updatedAtMs = now,
        ))
    }

    /**
     * Trigger synthesis. Switches to SYNTHESIZE, marks FINALIZING, and launches
     * the host's synthesis generation on [appScope] *outside* the room mutex.
     * The final verdict is written back via [RoomMutationSink.completeSynthesis],
     * which transitions the room to FINALIZED.
     */
    suspend fun synthesize(conversationId: Uuid): CouncilRoomOpResult {
        val settings = settingsFlow.value
        // Capture the resolved host model id inside the validation mutate so we
        // don't flip to FINALIZING and then discover the host has no model.
        var hostModelId: Uuid? = null
        val result = mutate(conversationId) { room ->
            if (room.status.terminal) {
                return@mutate CouncilRoomOpResult.Err("room_terminal", "Room has ended; cannot synthesize.")
            }
            if (room.messages.none { it.authorId != COUNCIL_ROOM_HOST_ID && it.authorId != COUNCIL_ROOM_USER_ID }) {
                return@mutate CouncilRoomOpResult.Err("nothing_to_synthesize", "No guest messages to synthesize.")
            }
            if (room.host == null) {
                return@mutate CouncilRoomOpResult.Err("no_host", "Host participant missing.")
            }
            hostModelId = resolveHostModelId(room, settings)
                ?: return@mutate CouncilRoomOpResult.Err("no_host_model", "Host model not found.")
            val now = nowMs()
            CouncilRoomOpResult.Ok(room.copy(
                mode = CouncilRoomMode.SYNTHESIZE,
                status = CouncilRoomStatus.FINALIZING,
                phaseMarkers = room.phaseMarkers + CouncilPhaseMarker(
                    id = msgId(),
                    label = "Host synthesis",
                    mode = CouncilRoomMode.SYNTHESIZE,
                    createdAtMs = now,
                ),
                updatedAtMs = now,
            ))
        }
        if (result is CouncilRoomOpResult.Err) return result
        val updatedRoom = (result as CouncilRoomOpResult.Ok).room
        val resolvedHostModelId = hostModelId
            ?: return CouncilRoomOpResult.Err("no_host_model", "Host model not found.")

        val job = launchSynthesisJob(conversationId) {
            runCatching {
                executor.generateSynthesis(
                    room = updatedRoom,
                    hostModelId = resolvedHostModelId,
                    hostSystemPrompt = CouncilRoomPrompts.hostSystemPrompt(updatedRoom),
                    settings = settings,
                )
            }.onFailure { error ->
                if (error !is CancellationException) {
                    android.util.Log.e(TAG, "Host synthesis failed", error)
                }
            }
        }
        if (job == null) {
            // The closing gate blocked the synthesis job from starting. The room
            // is already persisted as FINALIZING; the concurrent close() will
            // finalize it (graceful close waits for the synthesis job, sees none,
            // and falls through to mark FINALIZED). We surface a distinct error
            // code so the caller knows synthesis did not actually start rather
            // than silently returning Ok.
            return CouncilRoomOpResult.Err(
                code = "room_closing",
                message = "Room is being closed; synthesis was not started.",
            )
        }
        return CouncilRoomOpResult.Ok(updatedRoom)
    }

    /**
     * Close the Room. Cancels all in-flight generation jobs, then: if [cancel],
     * mark CANCELLED; else mark FINALIZED. Persists the terminal state AND
     * evicts the in-memory entry atomically (via [CouncilRoomStore.closeAndEvict])
     * so no concurrent re-open can slip between persist and evict. Also releases
     * the per-conversation mutex from [locks] to bound memory. Idempotent.
     *
     * Graceful close ([cancel] = false) behaves specially when the room is in
     * [CouncilRoomStatus.FINALIZING]: it waits for the host synthesis job to
     * finish so the final verdict can be written back via
     * [RoomMutationSink.completeSynthesis]. If the synthesis does not finish
     * within [CouncilRoom.totalTimeoutMs] the wait times out, the job is
     * cancelled, and the room is finalized with whatever state has already been
     * written back.
     */
    suspend fun close(
        conversationId: Uuid,
        cancel: Boolean = false,
    ): CouncilRoomOpResult {
        // Gate: prevent any new generation job from starting for this conversation.
        // This closes the race between launching a job and cancelling existing ones.
        jobsLock.withLock { closingConversationIds.add(conversationId) }

        try {
            // Graceful close while synthesizing: give the host synthesis a chance
            // to complete naturally before we cancel it.
            if (!cancel) {
                val room = peekRoom(conversationId)
                if (room?.status == CouncilRoomStatus.FINALIZING) {
                    val synthesisJob = jobsLock.withLock { synthesisJobs[conversationId] }
                    if (synthesisJob != null && synthesisJob.isActive) {
                        val timeoutMs = room.totalTimeoutMs.coerceAtLeast(1_000L)
                        val completed = withTimeoutOrNull(timeoutMs) { synthesisJob.join() } != null
                        if (!completed) {
                            android.util.Log.w(
                                TAG,
                                "Graceful close timed out waiting for synthesis after ${timeoutMs}ms",
                            )
                        }
                    }
                }
            }

            // Cancel all in-flight jobs (guest + synthesis) without holding the
            // room mutex — the jobs may be blocked waiting to write back via
            // [RoomMutationSink].
            val (guestJobs, synthesisJob) = jobsLock.withLock {
                val guests = generationJobs.remove(conversationId) ?: emptyList()
                val synth = synthesisJobs.remove(conversationId)
                guests to synth
            }
            guestJobs.forEach { it.cancel() }
            synthesisJob?.cancel()

            val mutex = lockFor(conversationId)
            val result = mutex.withLock {
                val flow = store.observeRoom(conversationId)
                val room = flow.value
                    ?: return@withLock CouncilRoomOpResult.Err("not_found", "No room for this conversation.")
                if (room.status.terminal) {
                    // Already terminal: just evict any leftover in-memory state.
                    store.closeAndEvict(room)
                    return@withLock CouncilRoomOpResult.Ok(room)
                }
                val now = nowMs()
                val nextStatus = when {
                    cancel -> CouncilRoomStatus.CANCELLED
                    room.status == CouncilRoomStatus.FINALIZING || room.synthesis.isNotBlank() -> CouncilRoomStatus.FINALIZED
                    else -> CouncilRoomStatus.FINALIZED  // graceful close without synthesis
                }
                val updated = room.copy(
                    status = nextStatus,
                    finishedAtMs = now,
                    updatedAtMs = now,
                )
                updateTaskStatus(updated)
                // Atomic persist + evict under the store's lock — no window for a
                // concurrent upsert/open to race in and leave disk inconsistent.
                store.closeAndEvict(updated)
                CouncilRoomOpResult.Ok(updated)
            }
            // Release the per-conversation mutex slot now that the room is gone.
            // Done under the room mutex above would be ideal, but locksLock is a
            // separate lock acquired by lockFor; removing it here (after the room
            // mutex is released and the room is evicted) is safe because any
            // concurrent openRoom that grabs a fresh mutex will observe the
            // terminal/evicted state via observeRoom and refuse to proceed.
            locksLock.withLock { locks.remove(conversationId) }
            return result
        } finally {
            jobsLock.withLock { closingConversationIds.remove(conversationId) }
        }
    }

    // ── internal helpers ──────────────────────────────────────────────────

    /**
     * Mutate the room under its per-conversation lock. The [transform] returns
     * either the next room (will be persisted) or an Err (no-op). On success
     * the task status is also updated.
     */
    private suspend fun mutate(
        conversationId: Uuid,
        transform: suspend (CouncilRoom) -> CouncilRoomOpResult,
    ): CouncilRoomOpResult {
        val mutex = lockFor(conversationId)
        return mutex.withLock {
            val flow = store.observeRoom(conversationId)
            val room = flow.value
                ?: return@withLock CouncilRoomOpResult.Err("not_found", "No room for this conversation.")
            when (val result = transform(room)) {
                is CouncilRoomOpResult.Err -> result
                is CouncilRoomOpResult.Ok -> {
                    val now = nowMs()
                    val next = result.room.copy(updatedAtMs = now)
                    store.upsertRoom(next)
                    updateTaskStatus(next)
                    CouncilRoomOpResult.Ok(next)
                }
            }
        }
    }

    /**
     * Like [mutate], but the transform returns a [PreflightResult] embedding the
     * [GuestTurnPlan] (which itself carries the next room state). The next room
     * is persisted under the lock; the plan is returned to the caller so it can
     * launch generation *after* the lock is released (m3 fix — generation must
     * not hold the room mutex).
     */
    private suspend fun mutatePreflight(
        conversationId: Uuid,
        transform: suspend (CouncilRoom) -> PreflightResult,
    ): PreflightResult {
        val mutex = lockFor(conversationId)
        return mutex.withLock {
            val flow = store.observeRoom(conversationId)
            val room = flow.value
                ?: return@withLock PreflightResult.Err("not_found", "No room for this conversation.")
            when (val result = transform(room)) {
                is PreflightResult.Err -> result
                is PreflightResult.Plan -> {
                    val now = nowMs()
                    val next = result.plan.room.copy(updatedAtMs = now)
                    store.upsertRoom(next)
                    updateTaskStatus(next)
                    PreflightResult.Plan(result.plan.copy(room = next))
                }
            }
        }
    }

    /**
     * Launch generation for a [GuestTurnPlan] (the primary + any extraGuests).
     * Generation runs on [appScope] WITHOUT holding the room mutex — streaming
     * updates flow back via [RoomMutationSink], which re-acquires the mutex per
     * write. Validation errors from [PreflightResult.Err] are returned
     * synchronously.
     *
     * If the closing gate blocks some (but not all) plans — possible for
     * multi-guest [HostAction.LetGuestsRespond] — the blocked guests would
     * otherwise stay stuck in WAITING forever (no completeMessage to advance
     * them). We detect this and roll their status back to IDLE so the roster
     * pill doesn't lie. The host echo message is already persisted, so the
     * blocked turn just becomes a no-op for those guests.
     */
    private suspend fun launchGuestTurn(
        preflight: PreflightResult,
        conversationId: Uuid,
    ): CouncilRoomOpResult {
        if (preflight is PreflightResult.Err) {
            return CouncilRoomOpResult.Err(preflight.code, preflight.message)
        }
        val plan = (preflight as PreflightResult.Plan).plan
        val settings = settingsFlow.value
        val plans = listOf(plan) + plan.extraGuests
        val gatedGuestIds = mutableListOf<String>()
        plans.forEach { turnPlan ->
            val messageId = msgId()
            val launched = launchGuestJob(conversationId) {
                runCatching {
                    executor.generateGuestTurn(
                        room = turnPlan.room,
                        guest = turnPlan.guest,
                        messageId = messageId,
                        userPrompt = turnPlan.userPrompt,
                        replyToMessageId = turnPlan.replyToMessageId,
                        continuesFromMessageId = turnPlan.continuesFromMessageId,
                        invitedBy = turnPlan.invitedBy,
                        settings = settings,
                    )
                }.onFailure { error ->
                    if (error !is CancellationException) {
                        android.util.Log.e(TAG, "Guest turn failed for ${turnPlan.guest.name}", error)
                    }
                }
            }
            if (launched == null) {
                gatedGuestIds.add(turnPlan.guest.id)
            }
        }
        if (gatedGuestIds.isNotEmpty()) {
            // Roll blocked guests back to IDLE so they don't appear stuck
            // WAITING. Best-effort: if the room was evicted (close finished),
            // this mutate is a no-op (not_found), which is fine.
            mutate(conversationId) { room ->
                if (room.status.terminal) return@mutate CouncilRoomOpResult.Ok(room)
                val touched = room.participants.any { it.id in gatedGuestIds && it.status == CouncilParticipantStatus.WAITING }
                if (!touched) return@mutate CouncilRoomOpResult.Ok(room)
                CouncilRoomOpResult.Ok(room.copy(
                    participants = room.participants.map { p ->
                        if (p.id in gatedGuestIds && p.status == CouncilParticipantStatus.WAITING) {
                            p.copy(status = CouncilParticipantStatus.IDLE)
                        } else {
                            p
                        }
                    },
                    updatedAtMs = nowMs(),
                ))
            }
        }
        return CouncilRoomOpResult.Ok(plan.room)
    }

    /**
     * Launch a guest generation job on [appScope]. Returns immediately; the job
     * registers itself synchronously inside its own coroutine. If the room is
     * already closing, no job is launched.
     */
    private suspend fun launchGuestJob(conversationId: Uuid, block: suspend () -> Unit): Job? {
        val shouldLaunch = jobsLock.withLock { conversationId !in closingConversationIds }
        if (!shouldLaunch) return null

        val job = appScope.launch {
            val added = jobsLock.withLock {
                if (conversationId in closingConversationIds) {
                    false
                } else {
                    generationJobs.getOrPut(conversationId) { mutableListOf() }.add(coroutineContext[Job]!!)
                    true
                }
            }
            if (!added) return@launch

            try {
                block()
            } finally {
                jobsLock.withLock {
                    generationJobs[conversationId]?.remove(coroutineContext[Job]!!)
                }
            }
        }
        return job
    }

    /**
     * Launch the host synthesis job on [appScope]. Like [launchGuestJob], it
     * registers itself synchronously and respects the closing gate.
     */
    private suspend fun launchSynthesisJob(conversationId: Uuid, block: suspend () -> Unit): Job? {
        val shouldLaunch = jobsLock.withLock { conversationId !in closingConversationIds }
        if (!shouldLaunch) return null

        val job = appScope.launch {
            val added = jobsLock.withLock {
                if (conversationId in closingConversationIds) {
                    false
                } else {
                    synthesisJobs[conversationId] = coroutineContext[Job]!!
                    true
                }
            }
            if (!added) return@launch

            try {
                block()
            } finally {
                jobsLock.withLock {
                    synthesisJobs.remove(conversationId)
                }
            }
        }
        return job
    }

    private suspend fun registerTask(room: CouncilRoom) {
        // Awaiting registration inline (rather than fire-and-forget on appScope)
        // guarantees the cancel callback is attached before openRoom returns Ok,
        // so the room's stop button always works. A lost race with the first
        // updateTaskStatus would otherwise leave the task unregistered and the
        // cancel affordance dead.
        runCatching {
            taskReporter.register(
                snapshot = room.toTaskSnapshot(),
                cancel = {
                    close(room.conversationId, cancel = true)
                    true
                },
            )
        }.onFailure { error ->
            // Room is already persisted; a failed task registration means the
            // room won't surface in the task panel and its cancel callback is
            // dead. Log loudly so this isn't silently lost — the room itself
            // still works, but the user loses the cancel affordance.
            android.util.Log.e(TAG, "registerTask failed for room ${room.id}", error)
        }
    }

    private suspend fun updateTaskStatus(room: CouncilRoom) {
        runCatching {
            taskReporter.upsert(room.toTaskSnapshot())
        }.onFailure { error ->
            android.util.Log.w(TAG, "updateTaskStatus failed for room ${room.id}: ${error.message}")
        }
    }

    private fun CouncilRoom.toTaskSnapshot(): AgentTaskSnapshot = AgentTaskSnapshot(
        taskId = "council_room:${conversationId}",
        type = "council_room",
        title = "${mode.name.lowercase()} · ${objective.take(48)}",
        status = status.toTaskStatus(),
        queueState = status.toTaskStatus().toQueueState(),
        sourceToolName = "council_room_open",
        sourceConversationId = conversationId.toString(),
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        cancelCapability = status.running,
        summary = objective.take(1_000),
    )

    private fun CouncilRoomStatus.toTaskStatus(): AgentTaskStatus = when (this) {
        CouncilRoomStatus.IDLE -> AgentTaskStatus.QUEUED
        CouncilRoomStatus.EXPLORING,
        CouncilRoomStatus.DEBATING,
        CouncilRoomStatus.FINALIZING -> AgentTaskStatus.RUNNING
        CouncilRoomStatus.FINALIZED -> AgentTaskStatus.COMPLETED
        CouncilRoomStatus.CANCELLED -> AgentTaskStatus.CANCELLED
        CouncilRoomStatus.FAILED -> AgentTaskStatus.FAILED
        CouncilRoomStatus.INTERRUPTED -> AgentTaskStatus.INTERRUPTED
    }

    private fun AgentTaskStatus.toQueueState(): AgentTaskQueueState = when (this) {
        AgentTaskStatus.QUEUED -> AgentTaskQueueState.QUEUED
        AgentTaskStatus.RUNNING -> AgentTaskQueueState.ACTIVE
        AgentTaskStatus.COMPLETED,
        AgentTaskStatus.FAILED,
        AgentTaskStatus.CANCELLED,
        AgentTaskStatus.TIMED_OUT,
        AgentTaskStatus.INTERRUPTED -> AgentTaskQueueState.TERMINAL
    }

    companion object {
        const val MAX_OBJECTIVE_CHARS = 4_000
        const val MAX_CONTEXT_CHARS = 40_000
        const val MAX_MESSAGE_CHARS = 12_000
        const val MAX_ROUNDS_CAP = 10
        const val MAX_PARTICIPANTS_CAP = 12
        private const val TAG = "CouncilRoomManager"
    }
}

// ── module-local helpers (no Compose/Android deps) ──────────────────────────

private fun nowMs(): Long = System.currentTimeMillis()

/**
 * Unique message/phase-marker id. Uses a full UUID (not a truncated one) so
 * concurrent guest generations in PR2 can't collide via birthday paradox —
 * ids are timeline primary keys and reference-graph targets.
 */
private fun msgId(): String = "cm-${Uuid.random()}"

private fun statusForMode(mode: CouncilRoomMode): CouncilRoomStatus = when (mode) {
    CouncilRoomMode.EXPLORE -> CouncilRoomStatus.EXPLORING
    CouncilRoomMode.DEBATE -> CouncilRoomStatus.DEBATING
    CouncilRoomMode.SYNTHESIZE -> CouncilRoomStatus.FINALIZING
}

private fun modeLabel(mode: CouncilRoomMode): String = when (mode) {
    CouncilRoomMode.EXPLORE -> "Explore"
    CouncilRoomMode.DEBATE -> "Debate"
    CouncilRoomMode.SYNTHESIZE -> "Synthesize"
}

private fun phaseOpenLabel(mode: CouncilRoomMode): String = when (mode) {
    CouncilRoomMode.EXPLORE -> "Explore · Opening"
    CouncilRoomMode.DEBATE -> "Debate · Opening"
    CouncilRoomMode.SYNTHESIZE -> "Synthesize · Opening"
}

private fun noParticipant(room: CouncilRoom, id: String): CouncilRoomOpResult =
    CouncilRoomOpResult.Err("not_found", "Participant $id not in room.")

private fun preflightNoParticipant(id: String): PreflightResult.Err =
    PreflightResult.Err("not_found", "Participant $id not in room.")
