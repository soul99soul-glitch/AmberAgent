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
    suspend fun completeSynthesis(conversationId: Uuid, synthesis: String, warnings: List<String>)
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
                coroutineScope {
                    // Unbounded so the synchronous onChunk callback never blocks.
                    // A consumer coroutine on this dispatcher drains the channel
                    // and forwards cumulative text to the suspending sink.
                    val chunkChannel = Channel<String>(Channel.UNLIMITED)
                    val consumer = launch {
                        for (cumulative in chunkChannel) {
                            streamingSafeUpdate(room.conversationId, messageId, cumulative)
                        }
                    }
                    val textResult = try {
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
                                    onChunk = { cumulative -> chunkChannel.trySend(cumulative) },
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
                                    onChunk = { cumulative -> chunkChannel.trySend(cumulative) },
                                )
                                ModelCouncilTextResult(text = text.take(budget))
                            }
                        }
                    } finally {
                        // Signal the consumer no more chunks are coming. Generation
                        // cancellation also lands here: the scope tears down and the
                        // for-loop exits via its own cancellation.
                        chunkChannel.close()
                    }
                    // Drain any buffered streaming updates before finalizing so the
                    // timeline never shows a stale partial above the final row.
                    consumer.join()
                    textResult
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
     * Generate the host's synthesis. Uses [synthesizePrompt]; writes the final
     * verdict via [RoomMutationSink.completeSynthesis], which transitions the
     * room to FINALIZED.
     *
     * The host runs on the host assistant's model (resolved from settings by the
     * caller); [hostModelId] is what the caller resolved.
     */
    suspend fun generateSynthesis(
        room: CouncilRoom,
        hostModelId: Uuid,
        hostSystemPrompt: String,
        settings: Settings,
    ) = withContext(dispatcher) {
        val budget = room.outputBudgetChars
        val result = runCatching {
            withTimeoutOrNull(room.seatTimeoutMs.coerceAtLeast(1_000L)) {
                modelRunner.generate(
                    settings = settings,
                    modelId = hostModelId,
                    systemPrompt = hostSystemPrompt,
                    userPrompt = CouncilRoomPrompts.synthesize(room),
                    outputBudgetChars = budget,
                    reasoningLevel = ReasoningLevel.OFF,
                    temperature = null,
                    onChunk = { /* synthesis streams to room.synthesis only on completion */ },
                )
            } ?: ModelCouncilTextResult(
                text = "（综合超时）",
                warnings = listOf("Host synthesis timed out after ${room.seatTimeoutMs}ms."),
            )
        }
        result.fold(
            onSuccess = { textResult ->
                sink.completeSynthesis(room.conversationId, textResult.text, textResult.warnings)
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                sink.completeSynthesis(
                    room.conversationId,
                    "综合失败：${error.message ?: error::class.java.simpleName}",
                    listOf("Synthesis failed: ${error.message}"),
                )
            },
        )
    }

    /**
     * Generate a HOST turn (opening / steer) and STREAM it back through the sink,
     * exactly like a guest turn — so the host's proposition types in live instead
     * of popping in whole. The host has no own modelId; [hostModelId] is the
     * caller-resolved conversation Assistant model, and [systemPrompt]/[userPrompt]
     * are the host-specific prompts.
     */
    suspend fun generateHostTurn(
        room: CouncilRoom,
        hostModelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        messageId: String,
        settings: Settings,
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
        val result = runCatching {
            withTimeoutOrNull(room.seatTimeoutMs.coerceAtLeast(1_000L)) {
                coroutineScope {
                    val chunkChannel = Channel<String>(Channel.UNLIMITED)
                    val consumer = launch {
                        for (cumulative in chunkChannel) {
                            streamingSafeUpdate(room.conversationId, messageId, cumulative)
                        }
                    }
                    val textResult = try {
                        modelRunner.generate(
                            settings = settings,
                            modelId = hostModelId,
                            systemPrompt = systemPrompt,
                            userPrompt = userPrompt,
                            outputBudgetChars = budget,
                            reasoningLevel = ReasoningLevel.OFF,
                            temperature = null,
                            userImageParts = room.userImageParts(),
                            onChunk = { cumulative -> chunkChannel.trySend(cumulative) },
                        )
                    } finally {
                        chunkChannel.close()
                    }
                    consumer.join()
                    textResult
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
 * Priority: room.hostModelIdOverride → host assistant's chatModelId →
 * settings.chatModelId. Returns null only if none resolves to a real model
 * (caller surfaces the error to the user).
 */
fun resolveHostModelId(room: CouncilRoom, settings: Settings): Uuid? {
    room.hostModelIdOverride?.let { if (settings.findModelById(it) != null) return it }
    val assistant = settings.assistants.firstOrNull { it.id == room.hostAssistantId }
    val candidateId = assistant?.chatModelId ?: settings.chatModelId
    return settings.findModelById(candidateId)?.id
}
