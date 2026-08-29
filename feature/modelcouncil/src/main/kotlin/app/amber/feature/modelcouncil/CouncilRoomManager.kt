package app.amber.feature.modelcouncil

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.ui.UIMessagePart
import app.amber.core.infra.AppScope
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.feature.task.AgentTaskSnapshot
import app.amber.feature.task.AgentTaskStatus
import app.amber.feature.task.AgentTaskQueueState
import kotlin.uuid.Uuid

/**
 * Host-action vocabulary — explicit runtime affordances for the room's host
 * (the conversation's canonical Amber identity). Each variant maps 1:1 to a host message
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
    // Tool-augmented host turns (FULL mode). Defaults to NoOp so STANDARD mode
    // and unit tests are unaffected; DI injects AppCouncilHostToolProvider.
    private val toolProvider: CouncilHostToolProvider = NoOpCouncilHostToolProvider,
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

    /**
     * Conversations whose automatic deliberation loop is in flight. Guarded by
     * [jobsLock]. Set synchronously before launching the auto-run job so two
     * near-simultaneous user messages can't start two parallel runs.
     */
    private val autoRunConversationIds = mutableSetOf<Uuid>()

    /**
     * Conversations whose council is currently being auto-assembled by the host
     * (the host model is picking role lenses + models for an empty room). Guarded
     * by [jobsLock] so two near-simultaneous first messages can't double-assemble.
     */
    private val assemblingConversationIds = mutableSetOf<Uuid>()

    /**
     * Conversations where the user explicitly picked a discussion mode (via the
     * top-bar dropdown → [switchMode]). Auto-detect won't override these. Guarded
     * by [jobsLock]; cleared when a fresh room opens.
     */
    private val userModeOverrideIds = mutableSetOf<Uuid>()

    /**
     * Per-conversation high-water-mark (createdAtMs) of the last user message the
     * auto-run loop has already consumed. New user messages with a later timestamp
     * are mid-run interjections the loop handles between turns. Guarded by
     * [jobsLock]; cleared when a fresh room opens.
     */
    private val interjectionWatermarks = mutableMapOf<Uuid, Long>()

    /**
     * Per-conversation pending ask_user awaiter. When the host asks the user a
     * question (FULL mode), [runAutoOrchestration] suspends on
     * [awaitUserAnswer], which parks a [CompletableDeferred] here. The user's
     * answer via [resumeAfterUserAnswer] completes it, un-suspending the loop.
     * [close] cancels it so a closing room tears the awaiter down cleanly. Guarded
     * by [jobsLock].
     */
    private val pendingAskUser = mutableMapOf<Uuid, kotlinx.coroutines.CompletableDeferred<String>>()

    /** Per-conversation mutation lock; prevents races between host actions / user input. */
    private val locks = mutableMapOf<Uuid, Mutex>()
    private val locksLock = Mutex()

    private suspend fun lockFor(conversationId: Uuid): Mutex = locksLock.withLock {
        locks.getOrPut(conversationId) { Mutex() }
    }

    /**
     * Open a Room for a conversation. Fails if one already exists and is not
     * terminal. The host participant is materialized from [hostAssistantId]
     * (the conversation's canonical Amber identity).
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
        hostModelIdOverride: Uuid? = null,
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
            // Resolve the host's model display name so the host bubble can show it.
            // When a hostModelIdOverride is supplied it takes priority (matches
            // resolveHostModelId()'s resolution order), so the bubble shows the model
            // the host will ACTUALLY run on, not a stale room-level model.
            val settings = settingsFlow.value
            val hostModelName = run {
                val modelId = hostModelIdOverride?.let { settings.findModelById(it)?.id }
                    ?: settings.chatModelId
                settings.findModelById(modelId)?.displayName.orEmpty()
            }
            val host = CouncilParticipant(
                id = COUNCIL_ROOM_HOST_ID,
                name = hostName.ifBlank { "Host" },
                role = "host",
                kind = CouncilParticipantKind.HOST,
                status = CouncilParticipantStatus.IDLE,
                modelName = hostModelName,
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
                    label = modeLabel(initialMode) + " · Opening",
                    mode = initialMode,
                    createdAtMs = now,
                )),
                maxRounds = maxRounds.coerceIn(1, MAX_ROUNDS_CAP),
                maxParticipants = effectiveMaxParticipants,
                hostModelIdOverride = hostModelIdOverride,
                createdAtMs = now,
                updatedAtMs = now,
            )
            store.upsertRoom(room)
            registerTask(room)
            // Fresh room → reset auto-detect + interjection bookkeeping.
            jobsLock.withLock {
                userModeOverrideIds.remove(conversationId)
                interjectionWatermarks.remove(conversationId)
            }
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
        synthesisMessageId: String,
        synthesis: String,
        warnings: List<String>,
    ) {
        mutate(conversationId) { room ->
            if (room.status.terminal) {
                return@mutate CouncilRoomOpResult.Ok(room)
            }
            val now = nowMs()
            // The synthesis was streamed into a host message row (mode ==
            // SYNTHESIZE) by generateSynthesis; finalize that row in place
            // rather than appending a duplicate. Fall back to inserting a new
            // row if the streaming seed is missing (hostless fixtures, legacy
            // callers) so the conclusion still surfaces inline.
            val host = room.participants.firstOrNull { it.id == COUNCIL_ROOM_HOST_ID }
            val existing = room.messages.firstOrNull { it.id == synthesisMessageId }
            val messages = when {
                existing != null -> room.messages.map { m ->
                    if (m.id == synthesisMessageId) {
                        m.copy(
                            text = synthesis.take(MAX_MESSAGE_CHARS),
                            status = CouncilMessageStatus.COMPLETED,
                            warnings = warnings,
                        )
                    } else {
                        m
                    }
                }
                synthesis.isNotBlank() && host != null -> room.messages + CouncilMessage(
                    id = synthesisMessageId.ifBlank { msgId() },
                    authorId = host.id,
                    authorName = host.name,
                    role = host.role,
                    round = room.round,
                    mode = CouncilRoomMode.SYNTHESIZE,
                    text = synthesis.take(MAX_MESSAGE_CHARS),
                    createdAtMs = now,
                    status = CouncilMessageStatus.COMPLETED,
                )
                else -> room.messages
            }
            CouncilRoomOpResult.Ok(room.copy(
                mode = CouncilRoomMode.SYNTHESIZE,
                status = CouncilRoomStatus.FINALIZED,
                synthesis = synthesis,
                warnings = warnings,
                messages = messages,
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
                label = "${guest.name} joined",
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
    ): CouncilRoomOpResult {
        // User-driven mode pick — record it so the auto-detector won't override.
        jobsLock.withLock { userModeOverrideIds.add(conversationId) }
        return mutate(conversationId) { room ->
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
                label = "Switched to ${modeLabel(mode)}",
                mode = mode,
                createdAtMs = now,
            ),
            updatedAtMs = now,
        ))
        }
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
        attachments: List<UIMessagePart> = emptyList(),
        attachmentText: String = "",
    ): CouncilRoomOpResult {
        val result = mutate(conversationId) { room ->
            if (room.status.terminal) {
                return@mutate CouncilRoomOpResult.Err("room_terminal", "Room has ended; cannot send messages.")
            }
            // An attachment-only message (image / file, no text) is still valid.
            if (text.isBlank() && attachments.isEmpty()) {
                return@mutate CouncilRoomOpResult.Err("empty_message", "Message text is empty.")
            }
            val now = nowMs()
            val isFirstUserMessage = room.messages.none { it.authorId == COUNCIL_ROOM_USER_ID }
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
                attachments = attachments,
                attachmentText = attachmentText.take(MAX_CONTEXT_CHARS),
            )
            // The first user message IS the topic — promote it to the room
            // objective so every member/synthesis prompt deliberates on it. For an
            // attachment-only first message (no text), derive a topic from the
            // attachment so mode-detection and host framing aren't left blank.
            val objective = if (isFirstUserMessage) {
                text.take(MAX_OBJECTIVE_CHARS).ifBlank {
                    val docName = attachments.filterIsInstance<UIMessagePart.Document>()
                        .firstOrNull()?.fileName
                    when {
                        docName != null -> "Discuss the file provided by the user: $docName"
                        attachments.any { it is UIMessagePart.Image } -> "Discuss the image provided by the user"
                        else -> room.objective
                    }
                }
            } else {
                room.objective
            }
            CouncilRoomOpResult.Ok(room.copy(
                objective = objective,
                messages = room.messages + userMessage,
                updatedAtMs = now,
            ))
        }
        if (result is CouncilRoomOpResult.Ok) {
            val current = peekRoom(conversationId)
            // STANDARD mode: assemble the council immediately (the original path).
            // FULL mode: DEFER assembly to runAutoOrchestration, so it runs AFTER the
            // host's pre-topic research turn — that way the bespoke roles are tailored
            // to the facts the host actually found, not generic presets. The deferred
            // call lives in runAutoOrchestration (gated on activeGuests.isEmpty()).
            val isFullMode = settingsFlow.value.agentRuntime.modelCouncil.councilPowerMode ==
                CouncilPowerMode.FULL
            if (current != null && !current.status.terminal &&
                current.activeGuests.isEmpty() && current.objective.isNotBlank() &&
                !isFullMode
            ) {
                autoAssembleSeats(conversationId, current.objective, settingsFlow.value)
            }
            // Fire the automatic deliberation: members speak across rounds, then
            // the host synthesizes — no manual turn-by-turn driving. No-op if a
            // run is already in flight (mid-run interjection is handled elsewhere).
            maybeStartAutoRun(conversationId)
        }
        return result
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
                "${guest.name}, please speak."
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
                    text = "${guest.name}, please respond to ${seed.authorName}'s contribution.".take(MAX_MESSAGE_CHARS),
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
                    text = "Please add to or challenge ${seed.authorName}'s contribution.".take(MAX_MESSAGE_CHARS),
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
                text = "${guest.name}, consider this direction: ${action.newDirection}".take(MAX_MESSAGE_CHARS),
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
                val councilSetting = settings.agentRuntime.modelCouncil
                executor.generateSynthesis(
                    room = updatedRoom,
                    hostModelId = resolvedHostModelId,
                    hostSystemPrompt = CouncilRoomPrompts.hostSystemPrompt(updatedRoom),
                    settings = settings,
                    reasoningLevel = councilSetting.hostReasoningLevel ?: ReasoningLevel.OFF,
                    extraSystemPrompt = councilSetting.hostSystemPrompt,
                    synthesisMessageId = msgId(),
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

    // ── ask_user (FULL mode HITL) ───────────────────────────────────────────

    /**
     * FULL mode: suspend the orchestration until the user answers a host question.
     * Called from [runAutoOrchestration] when the host emits an ask_user (either
     * during the pre-topic research turn or the end-of-round review). Parks a
     * [CompletableDeferred] in [pendingAskUser]; [resumeAfterUserAnswer] completes
     * it; [close] cancels it.
     *
     * The host's question is already persisted as a COMPLETED CouncilMessage with
     * [CouncilMessageKind.ASK_USER] (so the timeline shows the answer card) BEFORE
     * this is called — this function only does the waiting.
     *
     * Bounded by the room's total timeout as a safety net so a deferred that's
     * never completed (e.g. a crash in the answer path) can't leak the coroutine
     * forever. Returns null on timeout; the caller treats that as "no answer" and
     * continues the council.
     */
    private suspend fun awaitUserAnswer(conversationId: Uuid): String? {
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        jobsLock.withLock { pendingAskUser[conversationId] = deferred }
        return try {
            val room = peekRoom(conversationId)
            val bound = room?.totalTimeoutMs?.coerceAtLeast(60_000L) ?: Long.MAX_VALUE / 2
            kotlinx.coroutines.withTimeoutOrNull(bound) { deferred.await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // close() cancelled the awaiting job — propagate so the loop dies.
            throw e
        } finally {
            jobsLock.withLock { pendingAskUser.remove(conversationId) }
        }
    }

    /**
     * Resume a council paused on [awaitUserAnswer] by submitting the user's answer.
     * Appends the answer as a normal USER CouncilMessage (so the next round's
     * buildAutoPrompt / appendSteeringNote pick it up automatically), then completes
     * the parked deferred to un-suspend [runAutoOrchestration]. Also flips the room
     * status back from INTERRUPTED to the mode-appropriate running status.
     *
     * No-op (returns Err) if no ask_user is pending for this conversation.
     */
    suspend fun resumeAfterUserAnswer(
        conversationId: Uuid,
        answer: String,
    ): CouncilRoomOpResult {
        val deferred = jobsLock.withLock { pendingAskUser.remove(conversationId) }
        // Timeout / process death: no parked deferred. Still accept the answer as a
        // USER message and try to wake orchestration so the reply is never silent-dropped.
        val result = mutate(conversationId) { room ->
            if (room.status.terminal) {
                return@mutate CouncilRoomOpResult.Err("room_terminal", "Room has ended.")
            }
            if (deferred == null &&
                room.status != CouncilRoomStatus.INTERRUPTED &&
                !room.status.running
            ) {
                return@mutate CouncilRoomOpResult.Err(
                    "no_pending_question",
                    "No pending ask_user for this room.",
                )
            }
            val now = nowMs()
            val askMessage = room.messages.lastOrNull { it.kind == CouncilMessageKind.ASK_USER }
            val userMessage = CouncilMessage(
                id = msgId(),
                authorId = COUNCIL_ROOM_USER_ID,
                authorName = "You",
                role = "user",
                round = room.round,
                mode = room.mode,
                text = answer.take(MAX_MESSAGE_CHARS),
                createdAtMs = now,
                replyToMessageId = askMessage?.id,
                status = CouncilMessageStatus.COMPLETED,
            )
            CouncilRoomOpResult.Ok(room.copy(
                messages = room.messages + userMessage,
                // Flip back from INTERRUPTED (set when the host asked) to the
                // running status for the current mode so the UI shows live again.
                status = if (room.status == CouncilRoomStatus.INTERRUPTED) {
                    statusForMode(room.mode)
                } else {
                    room.status
                },
                updatedAtMs = now,
            ))
        }
        if (result is CouncilRoomOpResult.Err) return result
        if (deferred != null) {
            deferred.complete(answer)
        } else {
            // Recovery path: restart auto-run after appending the answer.
            maybeStartAutoRun(conversationId)
        }
        return result
    }

    // ── automatic orchestration ─────────────────────────────────────────────

    /**
     * Start the automatic deliberation if the room is live with guests and no run
     * is already in flight. The whole pipeline (rounds + synthesis) runs as one
     * cancellable job registered in [generationJobs], so [close] tears it down.
     *
     * Dedupe is synchronous via [autoRunConversationIds] under [jobsLock]: two
     * near-simultaneous user messages can't both pass and launch parallel runs.
     */
    private suspend fun maybeStartAutoRun(conversationId: Uuid) {
        val room = peekRoom(conversationId) ?: return
        if (room.status.terminal) return
        // FULL mode defers roster assembly into runAutoOrchestration (after the
        // pre-topic research), so a FULL-mode room legitimately has no guests yet at
        // this point — it must still be allowed to start the orchestration, which
        // will research → assemble → run. STANDARD mode assembles eagerly in
        // sendUserMessage, so for it the empty-guests guard still means "nothing to
        // run" and we bail. Without this distinction FULL-mode rooms deadlock:
        // empty → don't start → never assemble.
        val isFullMode = settingsFlow.value.agentRuntime.modelCouncil.councilPowerMode ==
            CouncilPowerMode.FULL
        if (!isFullMode && room.activeGuests.isEmpty()) return
        val claimed = jobsLock.withLock {
            if (conversationId in autoRunConversationIds || conversationId in closingConversationIds) {
                false
            } else {
                autoRunConversationIds.add(conversationId)
                true
            }
        }
        if (!claimed) return
        val job = launchGuestJob(conversationId) {
            try {
                runAutoOrchestration(conversationId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                android.util.Log.e(TAG, "Council auto-run failed", error)
                failActiveRoom(
                    conversationId = conversationId,
                    message = "Council auto-run failed: ${error.message ?: error::class.java.simpleName}",
                )
            } finally {
                jobsLock.withLock { autoRunConversationIds.remove(conversationId) }
            }
        }
        if (job == null) {
            // The closing gate blocked the launch — release the dedupe slot so a
            // later (re)open can run.
            jobsLock.withLock { autoRunConversationIds.remove(conversationId) }
        }
    }

    /**
     * The deliberation loop. For each of the configured rounds, every active guest
     * speaks once in roster order — awaited one at a time so turns stream serially.
     * Round 1 is independent openings; rounds 2..N use the mode-specific response
     * prompts (EXPLORE adds breadth; DEBATE cross-rebuts, and its last round asks
     * for a final position). After the final round the host synthesizes.
     *
     * Every step re-reads the room and bails on a terminal status, so [close]
     * (which cancels this job AND flips the room terminal) stops it promptly.
     */
    private suspend fun runAutoOrchestration(conversationId: Uuid) {
        val settings = settingsFlow.value
        val initial = peekRoom(conversationId) ?: return
        val totalRounds = initial.maxRounds.coerceIn(1, MAX_ROUNDS_CAP)

        // FULL mode: gather external facts BEFORE any deliberation. The research
        // summary lands in room.context (the "背景" field every member/host/
        // synthesis prompt renders) and as a host message (so appendSteeringNote
        // injects it into later rounds). Runs before roster assembly so the bespoke
        // roles can be tailored to what the host actually found. STANDARD mode and
        // no-search-provider both no-op here. See runPreTopicResearch.
        if (initial.activeGuests.isEmpty()) {
            // FULL mode defers roster assembly to here (after research); STANDARD
            // already assembled in sendUserMessage. autoAssembleSeats is itself a
            // no-op once members exist, so this is safe for both paths.
            runPreTopicResearch(conversationId, settings)
            autoAssembleSeats(conversationId, initial.objective, settings)
        }

        val seeded = peekRoom(conversationId) ?: return
        val guestIds = seeded.activeGuests.map { it.id }
        if (guestIds.isEmpty()) {
            failActiveRoom(conversationId, "No council guests are available to run.")
            return
        }

        // Interjection watermark = the topic message; anything the user sends AFTER
        // this is a mid-run interjection handled between turns.
        val startWatermark = seeded.messages
            .filter { it.authorId == COUNCIL_ROOM_USER_ID }
            .maxOfOrNull { it.createdAtMs } ?: 0L
        jobsLock.withLock { interjectionWatermarks[conversationId] = startWatermark }

        // Auto-detect the discussion mode from the topic — unless the user already
        // picked one via the dropdown (recorded in [userModeOverrideIds]). Done on
        // the post-research room so detection can use the gathered facts too.
        val userPickedMode = jobsLock.withLock { conversationId in userModeOverrideIds }
        if (!userPickedMode) {
            classifyMode(seeded, settings)?.let { detected ->
                mutateRoomOrNull(conversationId) { room ->
                    if (room.mode == detected) {
                        room
                    } else {
                        room.copy(
                            mode = detected,
                            status = statusForMode(detected),
                            updatedAtMs = nowMs(),
                        )
                    }
                }
            }
        }

        // Host opening: the host receives the topic and frames the core proposition
        // for the members BEFORE round 1 (the "主持承接命题" turn). Members pick the
        // framing up via appendSteeringNote on their first turn.
        val openingRoom = peekRoom(conversationId) ?: return
        if (!openingRoom.status.terminal) {
            resolveHostModelId(openingRoom, settings)?.let { hostModelId ->
                generateHostOpening(openingRoom, hostModelId, settings)
            }
        }

        for (round in 1..totalRounds) {
            val roundReady = mutateRoomOrNull(conversationId) { room ->
                room.copy(
                    round = round,
                    status = statusForMode(room.mode),
                    phaseMarkers = room.phaseMarkers + CouncilPhaseMarker(
                        id = msgId(),
                        label = "Round $round · ${modeShortLabel(room.mode)}",
                        mode = room.mode,
                        createdAtMs = nowMs(),
                    ),
                    updatedAtMs = nowMs(),
                )
            } ?: return // null = terminal/missing → stop

            for (gid in guestIds) {
                val current = peekRoom(conversationId) ?: return
                if (current.status.terminal) return
                val guest = current.participantById(gid) ?: continue
                if (guest.status == CouncilParticipantStatus.DISMISSED) continue
                // Suspends until this guest's turn completes (or times out); the
                // executor streams + finalizes the message via the sink.
                executor.generateGuestTurn(
                    room = current,
                    guest = guest,
                    messageId = msgId(),
                    userPrompt = buildAutoPrompt(current, guest, round, totalRounds),
                    invitedBy = COUNCIL_ROOM_HOST_ID,
                    settings = settings,
                )
                // Let the user steer between turns: @member → that member replies;
                // no @ → the host produces a redirect the next members will see.
                handleInterjections(conversationId, settings)
            }

            // FULL mode end-of-round host review: after all guests have spoken,
            // the host decides whether the round warrants commentary. A real
            // review (pointing out contradictions / drift / next-round focus) is
            // appended as a host message and auto-flows into the next round's
            // guest prompts via appendSteeringNote — giving the iterative
            // "accumulate & refine" feel. Skipped on the final round (synthesis
            // follows immediately) and when the host emits NO_COMMENT_SENTINEL.
            // Pure host text here (no tools) to keep round pacing tight; research
            // tools run once before synthesis (see the synthesis block below).
            if (settings.agentRuntime.modelCouncil.councilPowerMode == CouncilPowerMode.FULL &&
                round < totalRounds
            ) {
                val reviewRoom = peekRoom(conversationId) ?: return
                if (!reviewRoom.status.terminal) {
                    val question = runHostReviewTurn(conversationId, reviewRoom, round, totalRounds, settings)
                    // Host wants to ask the user a clarifying question. Persist it
                    // as an ASK_USER host message, flip the room to INTERRUPTED, and
                    // suspend until the user answers (resumeAfterUserAnswer appends
                    // the answer as a USER message + flips status back). The next
                    // round's buildAutoPrompt / appendSteeringNote then see the
                    // answer for free — no extra wiring.
                    if (question != null) {
                        val askRoom = peekRoom(conversationId) ?: return
                        val host = askRoom.host ?: return
                        mutateRoomOrNull(conversationId) { r ->
                            r.copy(
                                messages = r.messages + CouncilMessage(
                                    id = msgId(),
                                    authorId = host.id,
                                    authorName = host.name,
                                    role = host.role,
                                    round = round,
                                    mode = r.mode,
                                    text = question,
                                    createdAtMs = nowMs(),
                                    status = CouncilMessageStatus.COMPLETED,
                                    kind = CouncilMessageKind.ASK_USER,
                                ),
                                status = CouncilRoomStatus.INTERRUPTED,
                                updatedAtMs = nowMs(),
                            )
                        } ?: return
                        awaitUserAnswer(conversationId)
                    }
                }
            }
        }

        // Drain any final interjection before synthesizing.
        handleInterjections(conversationId, settings)

        // Final synthesis — inline (awaited) in this same job so close() cancels
        // it too. We ALWAYS drive the room to a terminal state here (never leave it
        // stuck mid-run): the only bare returns below are when the room is already
        // terminal/evicted. If the host model can't be resolved, finalize with an
        // honest note instead of silently stopping.
        val finalRoom = peekRoom(conversationId) ?: return
        if (finalRoom.status.terminal) return
        val hostModelId = resolveHostModelId(finalRoom, settings)
        if (hostModelId == null) {
            completeSynthesis(
                conversationId = conversationId,
                synthesisMessageId = msgId(),
                synthesis = "Unable to synthesize: no host model is configured. Configure a primary model for the current assistant in Settings.",
                warnings = listOf("Host model not found; synthesis skipped."),
            )
            return
        }
        val synthRoom = mutateRoomOrNull(conversationId) { room ->
            room.copy(
                mode = CouncilRoomMode.SYNTHESIZE,
                status = CouncilRoomStatus.FINALIZING,
                phaseMarkers = room.phaseMarkers + CouncilPhaseMarker(
                    id = msgId(),
                    label = "Host synthesis",
                    mode = CouncilRoomMode.SYNTHESIZE,
                    createdAtMs = nowMs(),
                ),
                updatedAtMs = nowMs(),
            )
        } ?: return
        val councilSetting = settings.agentRuntime.modelCouncil
        // Synthesis. External facts were already gathered BEFORE the deliberation
        // (FULL mode's runPreTopicResearch, which wrote them into room.context — the
        // "背景" field synthesize() renders). So there's no pre-synthesis research
        // turn here any more: discussing first and gathering facts only at synthesis
        // left the whole deliberation fact-free, which is why the pre-synthesis
        // research was removed. Synthesis just reads the (fact-enriched) record.
        val finalSynthRoom = peekRoom(conversationId) ?: return
        executor.generateSynthesis(
            room = finalSynthRoom,
            hostModelId = hostModelId,
            hostSystemPrompt = CouncilRoomPrompts.hostSystemPrompt(finalSynthRoom),
            settings = settings,
            reasoningLevel = councilSetting.hostReasoningLevel ?: ReasoningLevel.OFF,
            extraSystemPrompt = councilSetting.hostSystemPrompt,
            synthesisMessageId = msgId(),
        )
    }

    /** Mutate that returns the new room, or null if the room is terminal/missing. */
    private suspend fun mutateRoomOrNull(
        conversationId: Uuid,
        transform: (CouncilRoom) -> CouncilRoom,
    ): CouncilRoom? {
        val result = mutate(conversationId) { room ->
            if (room.status.terminal) {
                CouncilRoomOpResult.Err("room_terminal", "Room has ended.")
            } else {
                CouncilRoomOpResult.Ok(transform(room))
            }
        }
        return (result as? CouncilRoomOpResult.Ok)?.room
    }

    private suspend fun failActiveRoom(
        conversationId: Uuid,
        message: String,
    ) {
        mutate(conversationId) { room ->
            if (room.status.terminal) {
                CouncilRoomOpResult.Ok(room)
            } else {
                val now = nowMs()
                CouncilRoomOpResult.Ok(room.copy(
                    status = CouncilRoomStatus.FAILED,
                    warnings = room.warnings + message,
                    finishedAtMs = now,
                    updatedAtMs = now,
                ))
            }
        }
    }

    /**
     * Pick the round/mode-appropriate guest prompt. This is where the EXPLORE vs
     * DEBATE difference is actually executed (wiring the previously-unused
     * exploreResponse / debateResponse / debateFinalPosition templates).
     */
    private fun buildAutoPrompt(
        room: CouncilRoom,
        guest: CouncilParticipant,
        round: Int,
        totalRounds: Int,
    ): String {
        val priorGuestMessages = room.messages
            .filter {
                it.authorId != COUNCIL_ROOM_HOST_ID &&
                    it.authorId != COUNCIL_ROOM_USER_ID &&
                    it.authorId != guest.id &&
                    it.status == CouncilMessageStatus.COMPLETED
            }
            .takeLast(6)
            .reversed() // newest-first, matching the prompt templates' expectation
        val base = when (room.mode) {
            CouncilRoomMode.EXPLORE ->
                if (round <= 1 || priorGuestMessages.isEmpty()) {
                    CouncilRoomPrompts.exploreOpening(room, guest)
                } else {
                    CouncilRoomPrompts.exploreResponse(room, guest, priorGuestMessages)
                }

            CouncilRoomMode.DEBATE -> when {
                round <= 1 || priorGuestMessages.isEmpty() ->
                    CouncilRoomPrompts.debateOpening(room, guest)

                round >= totalRounds -> CouncilRoomPrompts.debateFinalPosition(
                    room = room,
                    guest = guest,
                    ownPriorMessages = room.messages.filter {
                        it.authorId == guest.id && it.status == CouncilMessageStatus.COMPLETED
                    },
                )

                else -> CouncilRoomPrompts.debateResponse(
                    room = room,
                    guest = guest,
                    priorMessages = priorGuestMessages,
                    referenceMessage = priorGuestMessages.firstOrNull(),
                )
            }

            // Not reached mid-loop (synthesis is handled separately), but keep total.
            CouncilRoomMode.SYNTHESIZE -> CouncilRoomPrompts.exploreOpening(room, guest)
        }
        return appendSteeringNote(room, base + userAttachmentContext(room))
    }

    /**
     * Inline the text extracted from user-attached documents so members can read
     * file contents (the council generation path can't see Document parts; images
     * ride multimodally instead). Empty when the user attached nothing / only
     * images. Bounded to keep per-turn prompt size sane.
     */
    private fun userAttachmentContext(room: CouncilRoom): String {
        val docs = room.messages
            .filter { it.authorId == COUNCIL_ROOM_USER_ID && it.attachmentText.isNotBlank() }
            .joinToString("\n\n") { it.attachmentText }
        if (docs.isBlank()) return ""
        return "\n\n---\nFile content provided by the user (use it when responding):\n" + docs.take(MAX_CONTEXT_CHARS)
    }

    /**
     * Append the latest user interjection or host redirect (anything newer than the
     * topic, authored by the user or host) so members actually adjust course. Both
     * stay public; the most recent steer wins.
     */
    private fun appendSteeringNote(room: CouncilRoom, base: String): String {
        val firstUserMessageId = room.messages.firstOrNull { it.authorId == COUNCIL_ROOM_USER_ID }?.id
        val steer = room.messages.lastOrNull {
            it.text.isNotBlank() &&
                it.status == CouncilMessageStatus.COMPLETED &&
                it.id != firstUserMessageId &&
                (it.authorId == COUNCIL_ROOM_USER_ID || it.authorId == COUNCIL_ROOM_HOST_ID)
        } ?: return base
        return buildString {
            append(base)
            append("\n\n---\nLatest guidance from ${steer.authorName} (prioritize it when adjusting your contribution):\n")
            append(steer.text.take(600))
        }
    }

    private fun modeShortLabel(mode: CouncilRoomMode): String = when (mode) {
        CouncilRoomMode.EXPLORE -> "Explore"
        CouncilRoomMode.DEBATE -> "Debate"
        CouncilRoomMode.SYNTHESIZE -> "Synthesize"
    }

    /**
     * Ask the host model to classify the topic as EXPLORE (open / divergent /
     * brainstorm) or DEBATE (clear stances / decision / stress-test). Returns null
     * on any failure (no host model, timeout, unparseable) → caller keeps the
     * current mode, so auto-detect is a best-effort enhancement, never a blocker.
     */
    private suspend fun classifyMode(room: CouncilRoom, settings: Settings): CouncilRoomMode? {
        val hostModelId = resolveHostModelId(room, settings) ?: return null
        val prompt = buildString {
            appendLine("Choose the more suitable multi-model discussion mode for the topic below. Reply with one English word only:")
            appendLine("- explore: open and divergent; brainstorm when the direction is not settled and more angles or possibilities are needed.")
            appendLine("- debate: clear disagreement or a decision is required; challenge positions and pressure-test the options.")
            appendLine()
            appendLine("Topic: ${room.objective}")
            appendLine()
            append("Reply with explore or debate only; do not explain.")
        }
        val result = runCatching {
            withTimeoutOrNull(20_000L) {
                modelRunner.generate(
                    settings = settings,
                    modelId = hostModelId,
                    systemPrompt = "You are a concise classifier. Output one English word only.",
                    userPrompt = prompt,
                    outputBudgetChars = 50,
                    reasoningLevel = ReasoningLevel.OFF,
                    temperature = 0f,
                    onChunk = {},
                )
            }
        }.getOrElse { error ->
            // Let close()'s cancellation propagate; only soft-fail real errors.
            if (error is CancellationException) throw error
            null
        } ?: return null
        val text = result.text.lowercase()
        return when {
            "debate" in text -> CouncilRoomMode.DEBATE
            "explore" in text -> CouncilRoomMode.EXPLORE
            else -> null
        }
    }

    /**
     * Build a council for a host-only room from [objective]. The three core seats
     * (supporter/opponent/judge) are always included; the host model additionally
     * picks the domain lenses that fit the topic (best-effort). Each role is
     * assigned a chat model from the user's providers — the default model first,
     * then the rest round-robin so a multi-model setup yields a diverse council.
     * Members are added via [inviteParticipant] so the roster fills in live. No-op
     * if no chat models are configured or an assembly is already running.
     */
    private suspend fun autoAssembleSeats(conversationId: Uuid, objective: String, settings: Settings) {
        val claimed = jobsLock.withLock {
            if (conversationId in assemblingConversationIds) {
                false
            } else {
                assemblingConversationIds.add(conversationId)
                true
            }
        }
        if (!claimed) return
        try {
            val room = peekRoom(conversationId) ?: return
            if (room.status.terminal || room.activeGuests.isNotEmpty()) return

            val chatModels = settings.providers
                .flatMap { it.models }
                .filter { it.type == ModelType.CHAT }
            if (chatModels.isEmpty()) return

            // Let the user see the host is working before the (network) pick begins.
            mutateRoomOrNull(conversationId) { r ->
                r.copy(
                    phaseMarkers = r.phaseMarkers + CouncilPhaseMarker(
                        id = msgId(),
                        label = "Host is assembling a topic-specific council…",
                        mode = r.mode,
                        createdAtMs = nowMs(),
                    ),
                    updatedAtMs = nowMs(),
                )
            }

            // Cap so host + guests stay within the roster limit.
            val maxGuests = (room.maxParticipants - 1).coerceAtLeast(1)
            // The host DESIGNS the roster for this specific objective — bespoke
            // roles (name + perspective), not a fixed preset list. Falls back to the
            // classic supporter/opponent/judge trio only if generation fails.
            val roles = planRoles(room, objective, settings, maxGuests)
                .ifEmpty { ModelCouncilRolePresets.coreSeats.map { it.name to it.prompt } }
                .distinctBy { it.first }
                .take(maxGuests)

            val defaultModel = settings.findModelById(settings.chatModelId)
            val ordered: List<Model> =
                (listOfNotNull(defaultModel) + chatModels.filter { it.id != defaultModel?.id })
                    .ifEmpty { chatModels }

            roles.forEachIndexed { index, (name, perspective) ->
                val model = ordered[index % ordered.size]
                val invited = inviteParticipant(
                    conversationId,
                    CouncilParticipant(
                        id = "auto-${Uuid.random()}",
                        name = name,
                        role = "auto",
                        kind = CouncilParticipantKind.GUEST,
                        modelId = model.id,
                        systemPrompt = perspective.ifBlank {
                            "From your role's perspective, give a clear, testable, and focused judgment on the topic."
                        },
                        modelName = model.displayName,
                    ),
                )
                // Roster full or room ended — stop adding more.
                if (invited is CouncilRoomOpResult.Err) return
            }
        } finally {
            jobsLock.withLock { assemblingConversationIds.remove(conversationId) }
        }
    }

    /**
     * Let the host model DESIGN the council roster for [objective] — bespoke roles
     * tailored to this specific topic (a short role name + a one-line perspective),
     * NOT a fixed preset list. Returns (name, perspective) pairs. Best-effort:
     * empty on any failure so the caller falls back to a sensible default. Mirrors
     * [classifyMode]'s lightweight call.
     */
    private suspend fun planRoles(
        room: CouncilRoom,
        objective: String,
        settings: Settings,
        maxGuests: Int,
    ): List<Pair<String, String>> {
        val hostModelId = resolveHostModelId(room, settings) ?: return emptyList()
        val prompt = buildString {
            appendLine("You are the host of a multi-model council. For the topic below, design the best 2-$maxGuests participant roles independently.")
            appendLine("Do not reuse a fixed template. Tailor the roles to this topic: decisions or disputes may need opposing or review roles, while exploration, creative, or factual topics need the perspectives or expertise they actually require.")
            appendLine("Give each role two fields:")
            appendLine("- name: a short role name (4-12 words) that conveys its position, expertise, or perspective")
            appendLine("- perspective: one sentence describing the angle this role brings and what it should focus on")
            appendLine()
            appendLine("Topic: $objective")
            // FULL mode: if the host already ran a pre-topic research turn, the
            // gathered facts live in room.context. Feed them to the roster planner so
            // the bespoke roles are tailored to what was actually found (e.g. once the
            // host has glm5.2 benchmark data, "代码推理手"/"中文体验派" become grounded
            // choices instead of generic "基准评测派"/"产品落地官").
            if (room.context.isNotBlank()) {
                appendLine()
                appendLine("Key facts already gathered (use them to choose the most relevant analytical perspectives rather than generic roles):")
                appendLine(room.context.take(2000))
            }
            appendLine()
            append("Output a JSON array only, for example: [{\"name\":\"Cost Analyst\",\"perspective\":\"Evaluate whether the option is worthwhile over the long term based on inputs, outputs, and total cost.\"}]. Do not explain or use a code block.")
        }
        val result = runCatching {
            withTimeoutOrNull(25_000L) {
                modelRunner.generate(
                    settings = settings,
                    modelId = hostModelId,
                    systemPrompt = "Output a JSON array only, with no extra text.",
                    userPrompt = prompt,
                    outputBudgetChars = 800,
                    reasoningLevel = ReasoningLevel.OFF,
                    temperature = 0.4f,
                    onChunk = {},
                )
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            null
        } ?: return emptyList()
        return parseGeneratedRoles(result.text, maxGuests)
    }

    /** Tolerant parse of the host's role JSON — strips surrounding prose / fences. */
    private fun parseGeneratedRoles(raw: String, maxGuests: Int): List<Pair<String, String>> = runCatching {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        json.parseToJsonElement(raw.substring(start, end + 1)).jsonArray.mapNotNull { el ->
            val obj = el.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val perspective = obj["perspective"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) null else name to perspective
        }.take(maxGuests)
    }.getOrElse { emptyList() }

    /**
     * Process user messages that arrived since the watermark (mid-run interjections).
     * @-mentioned members reply directly to the user message (public); an
     * un-targeted message goes to the host, who emits a redirect the subsequent
     * members will see. Advances the watermark per message. Everything stays
     * public — no private side-channels. Called between turns by the auto-run loop.
     */
    private suspend fun handleInterjections(conversationId: Uuid, settings: Settings) {
        while (true) {
            val room = peekRoom(conversationId) ?: return
            if (room.status.terminal) return
            val watermark = jobsLock.withLock { interjectionWatermarks[conversationId] ?: 0L }
            val pending = room.messages
                .filter { it.authorId == COUNCIL_ROOM_USER_ID && it.createdAtMs > watermark }
                .minByOrNull { it.createdAtMs }
                ?: return
            // Consume it up front so a failure below can't re-process it forever.
            jobsLock.withLock { interjectionWatermarks[conversationId] = pending.createdAtMs }

            val mentioned = mentionedGuests(room, pending.text)
            if (mentioned.isNotEmpty()) {
                for (guest in mentioned) {
                    val current = peekRoom(conversationId) ?: return
                    if (current.status.terminal) return
                    if (current.participantById(guest.id)?.status == CouncilParticipantStatus.DISMISSED) continue
                    executor.generateGuestTurn(
                        room = current,
                        guest = guest,
                        messageId = msgId(),
                        userPrompt = CouncilRoomPrompts.followUpPrompt(current, guest, pending),
                        replyToMessageId = pending.id,
                        invitedBy = COUNCIL_ROOM_USER_ID,
                        settings = settings,
                    )
                }
            } else {
                val current = peekRoom(conversationId) ?: return
                if (current.status.terminal) return
                val hostModelId = resolveHostModelId(current, settings) ?: continue
                generateHostSteer(current, pending, hostModelId, settings)
            }
        }
    }

    /** Active guests whose `@name` appears in [text] at a word boundary. */
    private fun mentionedGuests(room: CouncilRoom, text: String): List<CouncilParticipant> =
        room.activeGuests.filter { it.name.isNotBlank() && containsMention(text, it.name) }

    /**
     * True iff `@name` occurs in [text] not immediately followed by another name
     * character — so "@GPT-4" matches the guest "GPT-4" but NOT the guest "GPT".
     */
    private fun containsMention(text: String, name: String): Boolean {
        val token = "@$name"
        var idx = text.indexOf(token)
        while (idx >= 0) {
            val next = text.getOrNull(idx + token.length)
            val isBoundary = next == null || !(next.isLetterOrDigit() || next == '-' || next == '_')
            if (isBoundary) return true
            idx = text.indexOf(token, idx + 1)
        }
        return false
    }

    /**
     * Host redirect for an un-targeted interjection: generate a short steering
     * message off the host model and append it as a host turn. Members pick it up
     * via [buildAutoPrompt]'s steering block on their next turn.
     */
    private suspend fun generateHostSteer(
        room: CouncilRoom,
        userMessage: CouncilMessage,
        hostModelId: Uuid,
        settings: Settings,
    ) {
        val councilSetting = settings.agentRuntime.modelCouncil
        executor.generateHostTurn(
            room = room,
            hostModelId = hostModelId,
            systemPrompt = CouncilRoomPrompts.hostSystemPrompt(room),
            userPrompt = CouncilRoomPrompts.hostInterjectionPrompt(room, userMessage.text),
            messageId = msgId(),
            settings = settings,
            reasoningLevel = councilSetting.hostReasoningLevel ?: ReasoningLevel.OFF,
            extraSystemPrompt = councilSetting.hostSystemPrompt,
        )
    }

    /**
     * FULL mode: PRE-TOPIC host research turn. Runs BEFORE mode classification,
     * roster assembly, and the host opening — so external facts (recent releases,
     * benchmarks, project status) enter the room BEFORE any member speaks. This is
     * the fix for "members discuss glm5.2 with no training-data knowledge": the
     * host gathers the facts first.
     *
     * The research summary is persisted into [CouncilRoom.context] (the "背景"
     * field every member opening / host opening / synthesis prompt renders), AND it
     * is already appended as a host message by [runHostToolTurn], so
     * [appendSteeringNote] also injects it into later-round member prompts — two
     * channels covering the whole deliberation.
     *
     * No-op (returns without side effects) when:
     *   - power mode != FULL, or
     *   - no search provider is configured ([toolProvider.isAvailable] gate), or
     *   - the host produced no usable summary (the host judged the topic self-
     *     contained; an honest placeholder message still lands in the timeline).
     *
     * @param conversationId the room to research into.
     * @return the research summary text, or null if none was produced. The caller
     *   (runAutoOrchestration) does NOT need this — context is already written —
     *   but it is returned for testability.
     */
    private suspend fun runPreTopicResearch(
        conversationId: Uuid,
        settings: Settings,
    ): String? {
        val councilSetting = settings.agentRuntime.modelCouncil
        if (councilSetting.councilPowerMode != CouncilPowerMode.FULL) return null
        if (!toolProvider.isAvailable(settings)) return null

        val room = peekRoom(conversationId) ?: return null
        if (room.status.terminal) return null
        val hostModelId = resolveHostModelId(room, settings) ?: return null

        // Surface to the user that the host is gathering facts before discussing.
        mutateRoomOrNull(conversationId) { r ->
            r.copy(
                phaseMarkers = r.phaseMarkers + CouncilPhaseMarker(
                    id = msgId(),
                    label = "Host is researching current facts…",
                    mode = r.mode,
                    createdAtMs = nowMs(),
                ),
                updatedAtMs = nowMs(),
            )
        } ?: return null

        val researchRoom = peekRoom(conversationId) ?: return null
        val summary = runHostToolTurn(
            conversationId = conversationId,
            hostModelId = hostModelId,
            systemPrompt = CouncilRoomPrompts.hostSystemPrompt(researchRoom, hasTools = true),
            userPrompt = CouncilRoomPrompts.hostPreTopicResearchPrompt(researchRoom),
            settings = settings,
            // Give the host latitude for several search+scrape cycles so it can
            // cover multiple dimensions of a complex topic (e.g. for a new model:
            // overview → benchmark → specific capability → detail-page scrape).
            // 2 rounds was too shallow — the host could only "search once, maybe
            // scrape once", missing whole dimensions. With ~30 results/round (the
            // user's searchCommonOptions.resultSize), 4 rounds give the host plenty
            // of room to build a complete fact base before the council deliberates.
            maxToolRounds = 4,
            kindLabel = "Host research",
        ) ?: return null

        // Fold the summary into room.context — the shared fact base that every
        // member opening / host opening / synthesis prompt renders. Truncated to
        // MAX_CONTEXT_CHARS so a very long research digest can't blow up prompt size.
        if (summary.isNotBlank()) {
            mutateRoomOrNull(conversationId) { r ->
                // Only set if blank: never overwrite an existing non-empty context
                // (defensive — there is no UI that sets it today, but a future caller
                // might pass an explicit background, and we shouldn't clobber it).
                val merged = if (r.context.isBlank()) {
                    summary.trim().take(MAX_CONTEXT_CHARS)
                } else {
                    (r.context.trim() + "\n\n--- Host research supplement ---\n" + summary.trim()).take(MAX_CONTEXT_CHARS)
                }
                r.copy(context = merged, updatedAtMs = nowMs())
            }
        }
        return summary
    }

    /**
     * Host's opening turn — generated off the host model and appended as a host
     * message. The host receives the topic and frames the core proposition before
     * the members deliberate (they pick it up via [appendSteeringNote]).
     */
    private suspend fun generateHostOpening(
        room: CouncilRoom,
        hostModelId: Uuid,
        settings: Settings,
    ) {
        val councilSetting = settings.agentRuntime.modelCouncil
        executor.generateHostTurn(
            room = room,
            hostModelId = hostModelId,
            systemPrompt = CouncilRoomPrompts.hostSystemPrompt(room),
            userPrompt = CouncilRoomPrompts.hostOpeningPrompt(room),
            messageId = msgId(),
            settings = settings,
            reasoningLevel = councilSetting.hostReasoningLevel ?: ReasoningLevel.OFF,
            extraSystemPrompt = councilSetting.hostSystemPrompt,
        )
    }

    /**
     * FULL mode: run a host turn that may call read-only tools (search/scrape),
     * streaming the host's own text into the timeline as a normal host message.
     * Used for the pre-synthesis RESEARCH turn and (potentially) end-of-round
     * review. Gated on [CouncilHostToolProvider.isAvailable] by the caller.
     *
     * Returns the host's final text (already persisted as a COMPLETED host
     * message), or null if the provider returned empty/no usable text (caller
     * then skips — no empty bubble enters the timeline).
     *
     * `ask_user` outcomes are NOT handled here yet (step 4); for now an AskUser
     * result is treated as "no usable research text" and the turn is skipped,
     * so the council can still complete synthesis without the research context.
     */
    private suspend fun runHostToolTurn(
        conversationId: Uuid,
        hostModelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        settings: Settings,
        maxToolRounds: Int,
        kindLabel: String,
    ): String? {
        val room = peekRoom(conversationId) ?: return null
        val councilSetting = settings.agentRuntime.modelCouncil
        val messageId = msgId()
        val host = room.host ?: return null
        val now = nowMs()
        // Seed a streaming host message so the user sees the host "working".
        upsertStreamingMessage(
            conversationId,
            CouncilMessage(
                id = messageId,
                authorId = host.id,
                authorName = host.name,
                role = host.role,
                round = room.round,
                mode = room.mode,
                text = "",
                createdAtMs = now,
                status = CouncilMessageStatus.STREAMING,
            ),
        )
        val outcome = runCatching {
            // Bridge the sync onChunk callback to the suspend sink via a channel +
            // consumer coroutine, exactly like generateGuestTurn does. Avoids
            // runBlocking on the generation dispatcher.
            kotlinx.coroutines.coroutineScope {
                val chunkChannel = kotlinx.coroutines.channels.Channel<String>(
                    kotlinx.coroutines.channels.Channel.UNLIMITED,
                )
                val consumer = launch {
                    for (cumulative in chunkChannel) {
                        runCatching {
                            upsertStreamingMessage(
                                conversationId,
                                CouncilMessage(
                                    id = messageId,
                                    authorId = host.id,
                                    authorName = host.name,
                                    role = host.role,
                                    round = room.round,
                                    mode = room.mode,
                                    text = cumulative,
                                    createdAtMs = now,
                                    status = CouncilMessageStatus.STREAMING,
                                ),
                            )
                        }
                    }
                }
                try {
                    toolProvider.generateWithTools(
                        settings = settings,
                        modelId = hostModelId,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        reasoningLevel = councilSetting.hostReasoningLevel ?: ReasoningLevel.OFF,
                        maxToolRounds = maxToolRounds,
                        timeoutMs = room.seatTimeoutMs.coerceAtLeast(1_000L),
                        outputBudgetChars = room.outputBudgetChars,
                        onChunk = { cumulative -> chunkChannel.trySend(cumulative) },
                    )
                } finally {
                    chunkChannel.close()
                    consumer.join()
                }
            }
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            completeMessage(
                conversationId = conversationId,
                messageId = messageId,
                status = CouncilMessageStatus.FAILED,
                text = "",
                warnings = emptyList(),
                error = "$kindLabel failed: ${error.message ?: error::class.java.simpleName}",
                authorId = host.id,
                authorStatus = CouncilParticipantStatus.IDLE,
            )
            return null
        }
        return when (outcome) {
            is HostToolOutcome.Done -> {
                val finalText = outcome.text.trim()
                if (finalText.isEmpty()) {
                    // The host ran the research turn but produced no summary text —
                    // meaning it judged the discussion self-contained (no tools needed,
                    // or tools returned nothing usable). Rather than leave an empty
                    // bubble, finalize the placeholder with a one-line honest note so
                    // the timeline reads naturally. Returns null so the caller knows
                    // there is no research summary to fold into synthesis.
                    completeMessage(
                        conversationId = conversationId,
                        messageId = messageId,
                        status = CouncilMessageStatus.COMPLETED,
                        text = "The host found the discussion self-contained; no additional external research was needed.",
                        warnings = outcome.warnings,
                        error = "",
                        authorId = host.id,
                        authorStatus = CouncilParticipantStatus.IDLE,
                    )
                    null
                } else {
                    completeMessage(
                        conversationId = conversationId,
                        messageId = messageId,
                        status = CouncilMessageStatus.COMPLETED,
                        text = finalText,
                        warnings = outcome.warnings,
                        error = "",
                        authorId = host.id,
                        authorStatus = CouncilParticipantStatus.IDLE,
                    )
                    finalText
                }
            }
            is HostToolOutcome.AskUser -> {
                // The host wants to ask the user a clarifying question during
                // research. Surface it as an ASK_USER CouncilMessage (timeline
                // shows the answer card), flip the room to INTERRUPTED so the UI
                // reflects "waiting", then suspend until the user answers. The
                // answer is appended as a normal USER message (picked up by the
                // subsequent roster/opening/rounds via appendSteeringNote) and the
                // room resumes its running status. We return null (no research
                // summary) — the user's clarification IS the contribution here.
                val question = outcome.displayQuestion.trim().ifBlank {
                    "Please clarify your request."
                }
                completeMessage(
                    conversationId = conversationId,
                    messageId = messageId,
                    status = CouncilMessageStatus.COMPLETED,
                    text = question,
                    warnings = emptyList(),
                    error = "",
                    authorId = host.id,
                    authorStatus = CouncilParticipantStatus.IDLE,
                )
                // Re-mark the just-completed message with kind = ASK_USER so the
                // timeline renders the answer card instead of a plain bubble.
                mutate(conversationId) { r ->
                    val updated = r.messages.map { m ->
                        if (m.id == messageId) m.copy(kind = CouncilMessageKind.ASK_USER) else m
                    }
                    CouncilRoomOpResult.Ok(r.copy(
                        messages = updated,
                        status = CouncilRoomStatus.INTERRUPTED,
                        updatedAtMs = nowMs(),
                    ))
                }
                awaitUserAnswer(conversationId)
                null
            }
        }
    }

    /**
     * FULL mode end-of-round host review. Pure text (no tools, to keep round
     * pacing tight): the host reads this round's guest turns and either writes a
     * pointed review + next-round steer (appended as a host message, which
     * [appendSteeringNote] then injects into the next round's guest prompts) or
     * emits [CouncilRoomPrompts.NO_COMMENT_SENTINEL] (in which case nothing is
     * appended — no empty/noise bubble).
     *
     * STREAMED like a guest turn: a STREAMING host message is inserted first so
     * the user sees the review type in live (instead of a frozen screen), then
     * finalized or removed after the sentinel check. A "no comment" decision
     * removes the streaming message, leaving the timeline clean.
     */
    private suspend fun runHostReviewTurn(
        conversationId: Uuid,
        room: CouncilRoom,
        round: Int,
        totalRounds: Int,
        settings: Settings,
    ): String? {
        val councilSetting = settings.agentRuntime.modelCouncil
        val hostModelId = resolveHostModelId(room, settings) ?: return null
        val host = room.host ?: return null
        val reviewMessageId = msgId()
        val now = nowMs()
        // Seed a STREAMING host review message so the user sees live progress
        // ("主持人正在点评") instead of a frozen screen while the review generates.
        mutate(conversationId) { r ->
            if (r.status.terminal) return@mutate CouncilRoomOpResult.Ok(r)
            CouncilRoomOpResult.Ok(r.copy(
                messages = r.messages + CouncilMessage(
                    id = reviewMessageId,
                    authorId = host.id,
                    authorName = host.name,
                    role = host.role,
                    round = round,
                    mode = r.mode,
                    text = "",
                    createdAtMs = now,
                    status = CouncilMessageStatus.STREAMING,
                ),
                updatedAtMs = now,
            ))
        }
        // Stream the review text into the seeded message, exactly like a guest turn.
        val result = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(room.seatTimeoutMs.coerceAtLeast(1_000L)) {
                executor.streamIntoReview(
                    conversationId = conversationId,
                    messageId = reviewMessageId,
                    room = room,
                    hostModelId = hostModelId,
                    systemPrompt = CouncilRoomPrompts.hostSystemPrompt(room),
                    userPrompt = CouncilRoomPrompts.hostRoundReviewPrompt(room, round, totalRounds),
                    settings = settings,
                    reasoningLevel = councilSetting.hostReasoningLevel ?: ReasoningLevel.OFF,
                )
            }
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }
        val text = result?.text?.trim().orEmpty()
        // Sentinel or empty → host decided this round needs no commentary. Mark
        // the streaming message as WITHDRAWN (like a chat "撤回") instead of
        // deleting it: the user already saw the host "speaking", so a clean
        // removal would read as a visual jump. A withdrawn bubble keeps the
        // timeline continuous and signals "主持人 considered but had nothing to add".
        if (text.isEmpty() || CouncilRoomPrompts.NO_COMMENT_SENTINEL in text) {
            mutate(conversationId) { r ->
                CouncilRoomOpResult.Ok(r.copy(
                    messages = r.messages.map { m ->
                        if (m.id == reviewMessageId) {
                            m.copy(status = CouncilMessageStatus.COMPLETED, kind = CouncilMessageKind.WITHDRAWN)
                        } else {
                            m
                        }
                    },
                    updatedAtMs = nowMs(),
                ))
            }
            return null
        }
        // ask_user sentinel → host wants to ask the user a question. Mark the
        // streaming placeholder withdrawn; the caller persists the question as
        // ASK_USER separately.
        if (CouncilRoomPrompts.ASK_USER_SENTINEL in text) {
            mutate(conversationId) { r ->
                CouncilRoomOpResult.Ok(r.copy(
                    messages = r.messages.map { m ->
                        if (m.id == reviewMessageId) {
                            m.copy(status = CouncilMessageStatus.COMPLETED, kind = CouncilMessageKind.WITHDRAWN)
                        } else {
                            m
                        }
                    },
                    updatedAtMs = nowMs(),
                ))
            }
            val question = text.substringAfter(CouncilRoomPrompts.ASK_USER_SENTINEL).trim()
            return question.ifBlank { "Please clarify your request." }
        }
        // Normal review: finalize the streaming message in place with the full text.
        mutate(conversationId) { r ->
            if (r.status.terminal) return@mutate CouncilRoomOpResult.Ok(r)
            CouncilRoomOpResult.Ok(r.copy(
                messages = r.messages.map { m ->
                    if (m.id == reviewMessageId) {
                        m.copy(text = text, status = CouncilMessageStatus.COMPLETED)
                    } else {
                        m
                    }
                },
                updatedAtMs = nowMs(),
            ))
        }
        return null
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
            val (guestJobs, synthesisJob, askDeferred) = jobsLock.withLock {
                val guests = generationJobs.remove(conversationId) ?: emptyList()
                val synth = synthesisJobs.remove(conversationId)
                val ask = pendingAskUser.remove(conversationId)
                Triple(guests, synth, ask)
            }
            guestJobs.forEach { it.cancelAndJoin() }
            synthesisJob?.cancelAndJoin()
            // If the council was paused on an ask_user, cancel the parked awaiter
            // so the suspended runAutoOrchestration dies cleanly (its awaitUserAnswer
            // throws CancellationException, which the loop propagates to close).
            askDeferred?.cancel()

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
                // Sweep in-flight streaming rows / SPEAKING participants so a closed room
                // never reopens with permanent "正在发言" bubbles (isStreaming stay true).
                val messages = room.messages.map { m ->
                    if (m.status.running) {
                        m.copy(status = CouncilMessageStatus.FAILED, error = m.error.ifBlank { "interrupted" })
                    } else {
                        m
                    }
                }
                val participants = room.participants.map { p ->
                    if (p.status == CouncilParticipantStatus.SPEAKING) {
                        p.copy(status = CouncilParticipantStatus.IDLE)
                    } else {
                        p
                    }
                }
                val updated = room.copy(
                    status = nextStatus,
                    messages = messages,
                    participants = participants,
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
        lateinit var job: Job
        job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                jobsLock.withLock {
                    generationJobs[conversationId]?.remove(job)
                }
            }
        }
        val registered = jobsLock.withLock {
            if (conversationId in closingConversationIds) false else {
                generationJobs.getOrPut(conversationId) { mutableListOf() }.add(job)
                true
            }
        }
        if (!registered) {
            job.cancel()
            return null
        }
        job.start()
        return job
    }

    /**
     * Launch the host synthesis job on [appScope]. Like [launchGuestJob], it
     * registers itself synchronously and respects the closing gate.
     */
    private suspend fun launchSynthesisJob(conversationId: Uuid, block: suspend () -> Unit): Job? {
        lateinit var job: Job
        job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                jobsLock.withLock {
                    if (synthesisJobs[conversationId] === job) synthesisJobs.remove(conversationId)
                }
            }
        }
        val registered = jobsLock.withLock {
            if (conversationId in closingConversationIds) false else {
                synthesisJobs[conversationId] = job
                true
            }
        }
        if (!registered) {
            job.cancel()
            return null
        }
        job.start()
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

private fun noParticipant(room: CouncilRoom, id: String): CouncilRoomOpResult =
    CouncilRoomOpResult.Err("not_found", "Participant $id not in room.")

private fun preflightNoParticipant(id: String): PreflightResult.Err =
    PreflightResult.Err("not_found", "Participant $id not in room.")
