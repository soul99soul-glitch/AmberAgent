package app.amber.feature.modelcouncil

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import kotlin.uuid.Uuid

/**
 * Every image the user has attached across the room's user turns, flattened into
 * provider image parts. Passed multimodally to member/host generations so they
 * can actually see what the user shared. (Document attachments are inlined as
 * text into the prompt upstream, not here.)
 */
private fun CouncilRoom.userImageParts(): List<UIMessagePart.Image> =
    messages.asSequence()
        .filter { it.authorId == COUNCIL_ROOM_USER_ID }
        .flatMap { it.attachments.asSequence() }
        .filterIsInstance<UIMessagePart.Image>()
        .toList()

/**
 * Sink contract the executor uses to write streaming progress back into the
 * room state machine. Implemented by [CouncilRoomManager] so the executor stays
 * free of state-machine concerns (and testable with a fake sink).
 *
 * All calls are suspending and serialized by the manager's per-room mutex.
 */
interface RoomMutationSink {
    /** Insert/replace a streaming message and mark its author SPEAKING. */
    suspend fun upsertStreamingMessage(conversationId: Uuid, message: CouncilMessage)

    /** Finalize a message (COMPLETED/FAILED/TIMED_OUT) and update author status. */
    suspend fun completeMessage(
        conversationId: Uuid,
        messageId: String,
        status: CouncilMessageStatus,
        text: String,
        warnings: List<String>,
        error: String,
        authorId: String,
        authorStatus: CouncilParticipantStatus,
    )

    /** Finalize synthesis: write [synthesis] and transition room to FINALIZED. */
    suspend fun completeSynthesis(
        conversationId: Uuid,
        synthesisMessageId: String,
        synthesis: String,
        warnings: List<String>,
    )
}

/**
 * Executes a single participant's generation turn (guest or host) and streams
 * the result back through [RoomMutationSink].
 *
 * Two backends:
 * - PROVIDER_MODEL → [modelRunner] (ProviderModelCouncilTextRunner under the hood)
 * - EXTERNAL_CLI → [externalCliRunner] (shell out to Gemini/Claude/Codex/etc.)
 *
 * The External CLI path adapts [CouncilParticipant] → [ModelCouncilSeat] on the
 * fly (the CLI runner was written for the legacy seat model; we don't fork it).
 *
 * All generation is cancellable: a [withTimeoutOrNull] bounds it per the room's
 * seatTimeoutMs, and the manager's close() can cancel the launching coroutine.
 *
 * Streaming back-pressure: provider/CLI stream callbacks are plain synchronous
 * `(String) -> Unit` lambdas and know nothing about coroutines. We hand them a
 * lambda that only `trySend`s into an unbounded [Channel]; a consumer coroutine
 * on [dispatcher] drains the channel and invokes the suspending sink. This keeps
 * the callback non-blocking (so a single-threaded test scheduler, or a blocked
 * provider thread, cannot deadlock) while preserving strict per-chunk ordering
 * and cancellation (closing/cancelling the scope tears the consumer down).
 */
class CouncilRoomExecutor(
    private val modelRunner: ModelCouncilTextRunner,
    private val externalCliRunner: ModelCouncilExternalCliRunner,
    private val sink: RoomMutationSink,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Generate a guest turn.
     *
     * @param room snapshot taken by the caller (manager) BEFORE launching — used
     *   to resolve systemPrompt / reference messages. Stale reads are OK because
     *   the caller holds the room mutex until this is launched.
     * @param guest the participant speaking.
     * @param userPrompt already-built prompt (from CouncilRoomPrompts).
     * @param replyToMessageId / [continuesFromMessageId] / [invitedBy] wired into
     *   the resulting [CouncilMessage] to form the guest-to-guest reference graph.
     * @param messageId pre-allocated id so streaming updates target a stable row.
     */
    suspend fun generateGuestTurn(
        room: CouncilRoom,
        guest: CouncilParticipant,
        messageId: String,
        userPrompt: String,
        replyToMessageId: String? = null,
        continuesFromMessageId: String? = null,
        invitedBy: String? = null,
        settings: Settings,
    ) = withContext(dispatcher) {
        val now = nowMs()
        val systemPrompt = CouncilRoomPrompts.guestSystemPrompt(room, guest)
        val streaming = CouncilMessage(
            id = messageId,
            authorId = guest.id,
            authorName = guest.name,
            role = guest.role,
            round = room.round,
            mode = room.mode,
            text = "",
            createdAtMs = now,
            replyToMessageId = replyToMessageId,
            continuesFromMessageId = continuesFromMessageId,
            invitedBy = invitedBy,
            status = CouncilMessageStatus.STREAMING,
        )
        sink.upsertStreamingMessage(room.conversationId, streaming)

        val budget = guest.outputBudgetChars.coerceAtLeast(1_000)
        val result = runCatching {
            withTimeoutOrNull(room.seatTimeoutMs.coerceAtLeast(1_000L)) {
                streamInto(room.conversationId, messageId) { onChunk ->
                    when (guest.runnerType) {
                        ModelCouncilSeatRunner.PROVIDER_MODEL -> {
                            val modelId = guest.modelId
                                ?: error("Guest ${guest.name} has no modelId for PROVIDER_MODEL runner.")
                            modelRunner.generate(
                                settings = settings,
                                modelId = modelId,
                                systemPrompt = systemPrompt,
                                userPrompt = userPrompt,
                                outputBudgetChars = budget,
                                reasoningLevel = guest.reasoningLevel ?: ReasoningLevel.OFF,
                                temperature = guest.temperature,
                                userImageParts = room.userImageParts(),
                                onChunk = onChunk,
                            )
                        }

                        ModelCouncilSeatRunner.EXTERNAL_CLI -> {
                            val seat = guest.toLegacySeat(systemPrompt, budget)
                            val text = externalCliRunner.generate(
                                seat = seat,
                                systemPrompt = systemPrompt,
                                userPrompt = userPrompt,
                                timeoutMs = room.seatTimeoutMs.coerceAtLeast(1_000L),
                                outputBudgetChars = budget,
                                onChunk = onChunk,
                            )
                            ModelCouncilTextResult(text = text.take(budget))
                        }
                    }
                }
            } ?: run {
                sink.completeMessage(
                    conversationId = room.conversationId,
                    messageId = messageId,
                    status = CouncilMessageStatus.TIMED_OUT,
                    text = "",
                    warnings = emptyList(),
                    error = "Guest ${guest.name} timed out after ${room.seatTimeoutMs}ms.",
                    authorId = guest.id,
                    authorStatus = CouncilParticipantStatus.IDLE,
                )
                return@withContext
            }
        }

        result.fold(
            onSuccess = { textResult ->
                sink.completeMessage(
                    conversationId = room.conversationId,
                    messageId = messageId,
                    status = CouncilMessageStatus.COMPLETED,
                    text = textResult.text,
                    warnings = textResult.warnings,
                    error = "",
                    authorId = guest.id,
                    authorStatus = CouncilParticipantStatus.SPOKEN,
                )
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                sink.completeMessage(
                    conversationId = room.conversationId,
                    messageId = messageId,
                    status = CouncilMessageStatus.FAILED,
                    text = "",
                    warnings = emptyList(),
                    error = error.message ?: error::class.java.simpleName,
                    authorId = guest.id,
                    authorStatus = CouncilParticipantStatus.IDLE,
                )
            },
        )
    }

    /**
     * Generate the host's synthesis. Streams the verdict live into the timeline
     * as a host message (mode == SYNTHESIZE) — same spine as [generateHostTurn]
     * — so the conclusion types in instead of popping in whole. The final text
     * is committed via [RoomMutationSink.completeSynthesis], which finalizes the
     * streaming row, writes [CouncilRoom.synthesis], and transitions FINALIZED.
     *
     * [synthesisMessageId] is pre-allocated by the caller (manager) so the
     * streaming row and the finalize call target the same id.
     *
     * The host runs on the host assistant's model (resolved from settings by the
     * caller); [hostModelId] is what the caller resolved.
     */
    suspend fun generateSynthesis(
        room: CouncilRoom,
        hostModelId: Uuid,
        hostSystemPrompt: String,
        settings: Settings,
        reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
        extraSystemPrompt: String = "",
        synthesisMessageId: String,
    ) = withContext(dispatcher) {
        val host = room.host
        val budget = room.outputBudgetChars
        val effectiveSystemPrompt = if (extraSystemPrompt.isBlank()) {
            hostSystemPrompt
        } else {
            "$hostSystemPrompt\n\n—— 主持人补充设定 ——\n${extraSystemPrompt.trim()}"
        }

        // Seed the streaming synthesis row up-front so the timeline shows the
        // host "speaking" the conclusion as it arrives. Only when there is a
        // host participant — otherwise we fall back to writing synthesis via
        // completeSynthesis alone (no inline row), matching the legacy path.
        if (host != null) {
            sink.upsertStreamingMessage(
                conversationId = room.conversationId,
                message = CouncilMessage(
                    id = synthesisMessageId,
                    authorId = host.id,
                    authorName = host.name,
                    role = host.role,
                    round = room.round,
                    mode = CouncilRoomMode.SYNTHESIZE,
                    text = "",
                    createdAtMs = nowMs(),
                    status = CouncilMessageStatus.STREAMING,
                ),
            )
        }

        val result = runCatching {
            withTimeoutOrNull(room.seatTimeoutMs.coerceAtLeast(1_000L)) {
                if (host != null) {
                    // Stream live into the seeded row, exactly like a host turn.
                    streamInto(room.conversationId, synthesisMessageId) { onChunk ->
                        modelRunner.generate(
                            settings = settings,
                            modelId = hostModelId,
                            systemPrompt = effectiveSystemPrompt,
                            userPrompt = CouncilRoomPrompts.synthesize(room),
                            outputBudgetChars = budget,
                            reasoningLevel = reasoningLevel,
                            temperature = null,
                            onChunk = onChunk,
                        )
                    }
                } else {
                    // No host participant: keep the non-streaming path so the
                    // room still finalizes (e.g. hostless test fixtures).
                    modelRunner.generate(
                        settings = settings,
                        modelId = hostModelId,
                        systemPrompt = effectiveSystemPrompt,
                        userPrompt = CouncilRoomPrompts.synthesize(room),
                        outputBudgetChars = budget,
                        reasoningLevel = reasoningLevel,
                        temperature = null,
                        onChunk = {},
                    )
                }
            } ?: ModelCouncilTextResult(
                text = "（综合超时）",
                warnings = listOf("Host synthesis timed out after ${room.seatTimeoutMs}ms."),
            )
        }
        result.fold(
            onSuccess = { textResult ->
                sink.completeSynthesis(
                    conversationId = room.conversationId,
                    synthesisMessageId = synthesisMessageId,
                    synthesis = textResult.text,
                    warnings = textResult.warnings,
                )
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                sink.completeSynthesis(
                    conversationId = room.conversationId,
                    synthesisMessageId = synthesisMessageId,
                    synthesis = "综合失败：${error.message ?: error::class.java.simpleName}",
                    warnings = listOf("Synthesis failed: ${error.message}"),
                )
            },
        )
    }

    /**
     * Generate a HOST turn (opening / steer) and STREAM it back through the sink,
     * exactly like a guest turn — so the host's proposition types in live instead
     * of popping in whole. The host has no own modelId; [hostModelId] is the
 * caller-resolved global Settings model, and [systemPrompt]/[userPrompt]
     * are the host-specific prompts.
     */
    suspend fun generateHostTurn(
        room: CouncilRoom,
        hostModelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        messageId: String,
        settings: Settings,
        reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
        extraSystemPrompt: String = "",
    ) = withContext(dispatcher) {
        val host = room.host ?: return@withContext
        val now = nowMs()
        val streaming = CouncilMessage(
            id = messageId,
            authorId = host.id,
            authorName = host.name,
            role = host.role,
            round = room.round,
            mode = room.mode,
            text = "",
            createdAtMs = now,
            status = CouncilMessageStatus.STREAMING,
        )
        sink.upsertStreamingMessage(room.conversationId, streaming)

        val budget = room.outputBudgetChars.coerceAtLeast(1_000)
        // The built-in host prompt carries dynamic context (topic/mode/duty/
        // hard boundaries); a user-supplied supplement is appended AFTER it as a
        // style/persona layer — never replaces it, mirroring how a guest seat's
        // own systemPrompt sits on top of the mode guidance.
        val effectiveSystemPrompt = if (extraSystemPrompt.isBlank()) {
            systemPrompt
        } else {
            "$systemPrompt\n\n—— 主持人补充设定 ——\n${extraSystemPrompt.trim()}"
        }
        val result = runCatching {
            withTimeoutOrNull(room.seatTimeoutMs.coerceAtLeast(1_000L)) {
                streamInto(room.conversationId, messageId) { onChunk ->
                    modelRunner.generate(
                        settings = settings,
                        modelId = hostModelId,
                        systemPrompt = effectiveSystemPrompt,
                        userPrompt = userPrompt,
                        outputBudgetChars = budget,
                        reasoningLevel = reasoningLevel,
                        temperature = null,
                        userImageParts = room.userImageParts(),
                        onChunk = onChunk,
                    )
                }
            } ?: run {
                sink.completeMessage(
                    conversationId = room.conversationId,
                    messageId = messageId,
                    status = CouncilMessageStatus.TIMED_OUT,
                    text = "",
                    warnings = emptyList(),
                    error = "主持发言超时。",
                    authorId = host.id,
                    authorStatus = CouncilParticipantStatus.IDLE,
                )
                return@withContext
            }
        }

        result.fold(
            onSuccess = { textResult ->
                sink.completeMessage(
                    conversationId = room.conversationId,
                    messageId = messageId,
                    status = CouncilMessageStatus.COMPLETED,
                    text = textResult.text,
                    warnings = textResult.warnings,
                    error = "",
                    authorId = host.id,
                    authorStatus = CouncilParticipantStatus.IDLE,
                )
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                sink.completeMessage(
                    conversationId = room.conversationId,
                    messageId = messageId,
                    status = CouncilMessageStatus.FAILED,
                    text = "",
                    warnings = emptyList(),
                    error = error.message ?: error::class.java.simpleName,
                    authorId = host.id,
                    authorStatus = CouncilParticipantStatus.IDLE,
                )
            },
        )
    }

    // ── internals ──────────────────────────────────────────────────────────

    /**
     * Best-effort streaming update. The streaming text is the authoritative
     * visible state during generation; the final completeMessage() call is what
     * the timeline persists long-term. Throttling is handled inside the runner.
     *
     * Runs inside the executor's consumer coroutine (on [dispatcher]); the sink
     * re-acquires the room mutex per write, so this never holds it across a
     * provider call.
     */
    private suspend fun streamingSafeUpdate(
        conversationId: Uuid,
        messageId: String,
        cumulativeText: String,
    ) {
        runCatching {
            sink.upsertStreamingMessage(
                conversationId = conversationId,
                message = CouncilMessage(
                    id = messageId,
                    authorId = "", // sink matches by id, not author
                    authorName = "",
                    role = "",
                    round = 0,
                    mode = CouncilRoomMode.EXPLORE,
                    text = cumulativeText,
                    createdAtMs = nowMs(),
                    status = CouncilMessageStatus.STREAMING,
                ),
            )
        }
    }

    /**
     * The shared streaming spine: bridge a synchronous `(String) -> Unit` chunk
     * callback (the kind [modelRunner] / [externalCliRunner] take) onto a suspending
     * sink, preserving per-chunk order and cancellation.
     *
     * - An unbounded [Channel] absorbs the synchronous callbacks (so they never
     *   block, even on a single-threaded test scheduler).
     * - A consumer coroutine on [dispatcher] drains the channel and forwards each
     *   cumulative text to [onCumulative] (which writes to the sink).
     * - [generate] receives the `onChunk` callback to hand to the runner. Whatever
     *   [generate] returns is the final result text; the channel is closed in its
     *   `finally` so the consumer drains remaining chunks before we return.
     *
     * Used by both [generateGuestTurn] and [generateHostTurn] to collapse what was
     * two copy-pasted `Channel + launch consumer + close + join` blocks.
     */
    private suspend fun <T> streamInto(
        conversationId: Uuid,
        messageId: String,
        generate: suspend (onChunk: (String) -> Unit) -> T,
    ): T = coroutineScope {
        val chunkChannel = Channel<String>(Channel.UNLIMITED)
        val consumer = launch {
            for (cumulative in chunkChannel) {
                streamingSafeUpdate(conversationId, messageId, cumulative)
            }
        }
        val textResult = try {
            generate { cumulative -> chunkChannel.trySend(cumulative) }
        } finally {
            chunkChannel.close()
        }
        consumer.join()
        textResult
    }

    /**
     * Stream a host review/steer into a pre-seeded STREAMING message (seeded by
     * the caller via [RoomMutationSink.upsertStreamingMessage] or a direct
     * mutate). Same spine as [generateHostTurn] but WITHOUT seeding or
     * finalizing the message — the caller controls the lifecycle so it can
     * remove the message if the review turns out to be a "no comment" sentinel.
     */
    suspend fun streamIntoReview(
        conversationId: Uuid,
        messageId: String,
        room: CouncilRoom,
        hostModelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        settings: Settings,
        reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    ): ModelCouncilTextResult = withContext(dispatcher) {
        streamInto(conversationId, messageId) { onChunk ->
            modelRunner.generate(
                settings = settings,
                modelId = hostModelId,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                outputBudgetChars = 800,
                reasoningLevel = reasoningLevel,
                temperature = null,
                onChunk = onChunk,
            )
        }
    }

    /** Adapt a CouncilParticipant to the legacy ModelCouncilSeat shape the CLI runner expects. */
    private fun CouncilParticipant.toLegacySeat(systemPrompt: String, budget: Int): ModelCouncilSeat =
        ModelCouncilSeat(
            seatId = id,
            name = name,
            role = role,
            modelId = modelId ?: Uuid.parse(MODEL_COUNCIL_EXTERNAL_MODEL_PLACEHOLDER),
            runnerType = ModelCouncilSeatRunner.EXTERNAL_CLI,
            systemPrompt = systemPrompt,
            outputBudgetChars = budget,
            reasoningLevel = reasoningLevel,
            temperature = temperature,
            externalTool = externalTool,
            externalRuntime = externalRuntime,
            externalModel = externalModel,
        )
}

private fun nowMs(): Long = System.currentTimeMillis()

/**
 * Resolve the host's model id for generation.
 * Priority: room.hostModelIdOverride → settings.chatModelId. Returns null only
 * if the setting does not resolve to a real model
 * (caller surfaces the error to the user).
 */
fun resolveHostModelId(room: CouncilRoom, settings: Settings): Uuid? {
    // Prefer the LIVE settings value first: the user can change the host model
    // in settings at any time, and that change must take effect immediately on
    // the active room — not only after a restart. The room's persisted
    // hostModelIdOverride is a snapshot from openRoom time and can be stale
    // (e.g. user opened the room before picking a host model).
    settings.agentRuntime.modelCouncil.hostModelId?.let { liveId ->
        if (settings.findModelById(liveId) != null) return liveId
    }
    // Fallback to the room-snapshot override (covers rooms opened with a host
    // model before the live setting existed, or settings cleared).
    room.hostModelIdOverride?.let { if (settings.findModelById(it) != null) return it }
    val candidateId = settings.chatModelId
    return settings.findModelById(candidateId)?.id
}
