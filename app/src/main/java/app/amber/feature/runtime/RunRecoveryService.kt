package app.amber.feature.runtime

import android.util.Log
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.ResponseResumeStore
import app.amber.ai.provider.providers.openai.StoredResponseState
import app.amber.ai.ui.MessageStreamAccumulator
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.MessageNode
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.agent.runtime.RunTransitionResult
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.feature.tools.ToolEffectClass
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private const val TAG = "RunRecoveryService"

/** Terminal effects older than this are pruned at cold start (M1 retention). */
private const val TERMINAL_EFFECT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

/** One actionable outcome-unknown prompt shown in a conversation (P1-02 #5). */
data class OutcomeUnknownPrompt(
    val effectId: String,
    val runId: String,
    val conversationId: String,
    val toolCallId: String,
    val toolName: String,
    val resultSummary: String?,
)

/**
 * Cold-start recovery for the durable runtime (P1-02 + P1-03 + P6-01).
 *
 * Runs at app launch (before ChatEventProjector.replayUnfinished so that
 * ledger-replayed results win over the interrupted-tool projection):
 *
 *  - P6-01: runs with a stored server-side OpenAI Response (resume cursor)
 *    are resolved against the server first — completed responses fetch only
 *    the missing events and finish COMPLETED with the same runId; cancelled
 *    / failed responses settle the terminal; in-progress responses stay
 *    RESUMABLE for the in-process resume path. Server unreachable at cold
 *    start keeps pause states resumable and falls back to Phase 1 for
 *    RUNNING runs.
 *  - RUNNING / WAITING_EXTERNAL / RESUMABLE runs (no stored response) are
 *    process-death victims: they become INTERRUPTED and their ledger effects
 *    are reconciled:
 *      - PREPARED                    → untouched (re-enters approval/execution)
 *      - STARTED, readOnly           → untouched (safe to retry)
 *      - STARTED, idempotentWrite    → untouched (retried with idempotency key)
 *      - STARTED, nonIdempotentWrite → OUTCOME_UNKNOWN, waits for the user
 *      - FINISHED                    → result replayed into the conversation if
 *                                       the tool part was never persisted
 *  - WAITING_USER / OUTCOME_UNKNOWN runs are pauses, not crashes: their state
 *    survives so approval can resume the same runId after restart.
 */
class RunRecoveryService(
    private val ledger: ToolEffectLedger,
    private val runTerminalStore: RunTerminalStore,
    private val conversationRepo: ConversationRepository,
    private val json: Json,
    // P6-01: stored OpenAI Response resolution — all nullable so the legacy
    // recovery path (and existing tests) stays untouched when the capability
    // is off or the wiring is absent.
    private val storedResponseGateway: StoredResponseGateway? = null,
    private val capabilityFlags: CapabilityFlags? = null,
    private val resumeStore: ResponseResumeStore? = null,
    // Step 3-4 dual-write convergence: when recovery settles a run_terminal
    // row it also moves the protocol run row to the same state, so the two
    // stores cannot diverge (e.g. stored-response COMPLETED while agent_run
    // would later be marked INTERRUPTED). Null keeps the pre-Step-3 single-
    // write behavior for tests that exercise the ledger rules in isolation.
    private val agentEventStore: AgentEventStore? = null,
) {
    /** P6-01: run states whose stored server response may still be live. */
    private val RESUME_RESOLVABLE_STATES = setOf(
        RunTerminalState.RUNNING,
        RunTerminalState.WAITING_EXTERNAL,
        RunTerminalState.RESUMABLE,
    )

    /**
     * Best-effort protocol-side mirror of a recovery settle: CAS the
     * agent_run row from [expected] to [to]. Never throws, never blocks the
     * run_terminal settle — a rejection means another writer already moved
     * the row (which is the convergence this aims for anyway).
     */
    private suspend fun casEventStoreStatus(
        runId: String,
        expected: Set<RunStatus>,
        to: RunStatus,
        reason: String,
    ) {
        val store = agentEventStore ?: return
        runCatching {
            store.transitionRun(AgentRunId(runId), expected, to, reason)
        }.onSuccess { result ->
            // An illegal rejection is a code bug (caller targeted a state the
            // transition table forbids), not a lost race — never swallow it.
            if (result is RunTransitionResult.Rejected && result.illegal) {
                Log.w(TAG, "recover: ILLEGAL event-store transition ${result.current} -> $to for $runId")
            }
        }.onFailure { error ->
            Log.w(TAG, "recover: event-store transition to $to failed for $runId", error)
        }
    }

    suspend fun recover() {
        // M1 retention: terminal effects older than 7 days are never needed
        // again (no replay, no reconciliation) — prune them at cold start so
        // the ledger does not keep full result payloads forever.
        runCatching { ledger.deleteTerminalOlderThan(TERMINAL_EFFECT_RETENTION_MS) }
            .onFailure { error ->
                Log.w(TAG, "recover: terminal effect cleanup failed", error)
            }
        // Step 5 retention: RequestSnapshot rows age on the SAME 7-day cutoff
        // — one ~8KB audit payload per wire call is not replay state, so
        // letting them accumulate forever would bloat agent_event. Best-
        // effort, same as the ledger prune.
        agentEventStore?.let { store ->
            runCatching {
                store.deleteEventsOfTypeOlderThan(
                    type = app.amber.feature.chat.api.ChatEventPayload.RequestSnapshot.TYPE,
                    cutoffMs = System.currentTimeMillis() - TERMINAL_EFFECT_RETENTION_MS,
                )
            }.onFailure { error ->
                Log.w(TAG, "recover: request snapshot cleanup failed", error)
            }
        }
        for (run in runTerminalStore.unfinished()) {
            runCatching {
                // P6-01: resolve stored server responses before the Phase 1
                // crash rules — the server knows what actually happened.
                val resumeResolved = storedResponseGateway != null &&
                    resumeStore != null &&
                    capabilityFlags?.isEnabled(Capability.OpenAIResponsesResume) == true &&
                    run.state in RESUME_RESOLVABLE_STATES &&
                    resolveStoredResponse(run)
                if (resumeResolved) return@runCatching
                when (run.state) {
                    RunTerminalState.WAITING_USER,
                    RunTerminalState.OUTCOME_UNKNOWN,
                    -> {
                        // Approval / confirmation entry survives a restart;
                        // a stray started effect (shouldn't happen while
                        // waiting) still gets the safe OUTCOME_UNKNOWN mark.
                        // The run stays PAUSED (never finish()): finish writes
                        // finishedAtMs and makes the terminal write-once, which
                        // would force reconcile to mint a fresh runId. A pause
                        // keeps the same runId resumable after user decision.
                        val escalated = reconcileStartedEffects(run.runId)
                        if (escalated && run.state == RunTerminalState.WAITING_USER) {
                            runTerminalStore.pause(
                                run.runId,
                                RunTerminalState.OUTCOME_UNKNOWN,
                                PauseReason.OUTCOME_UNKNOWN,
                            )
                        }
                        if (escalated || run.state == RunTerminalState.OUTCOME_UNKNOWN) {
                            // Dual-write convergence (Step 3): the event-store
                            // run row shows the same pause. The re-assert also
                            // converges legacy rows whose escalation predates
                            // the protocol (run_terminal OUTCOME_UNKNOWN while
                            // agent_run still RUNNING/WAITING_USER — otherwise
                            // replayUnfinished would stomp them INTERRUPTED).
                            casEventStoreStatus(
                                run.runId,
                                RunStatus.LIVE_STATES,
                                RunStatus.OUTCOME_UNKNOWN,
                                reason = "ledger_escalation",
                            )
                        }
                        replayFinishedResults(run.runId, run.conversationId)
                    }

                    RunTerminalState.RUNNING,
                    RunTerminalState.WAITING_EXTERNAL,
                    RunTerminalState.RESUMABLE,
                    -> {
                        // P6-01: this branch is only reached when the run has
                        // no resumable stored response (or it could not be
                        // resolved) — the cursor, if any, is orphaned now.
                        runCatching { resumeStore?.clear(run.runId) }
                            .onFailure { error ->
                                Log.w(TAG, "recover: cursor clear failed for ${run.runId}", error)
                            }
                        val escalated = reconcileStartedEffects(run.runId)
                        if (escalated) {
                            // OUTCOME_UNKNOWN is actionable and therefore a
                            // pause. It must remain unfinished so the same
                            // runId is discoverable by recovery/resume.
                            runTerminalStore.pause(
                                run.runId,
                                RunTerminalState.OUTCOME_UNKNOWN,
                                PauseReason.OUTCOME_UNKNOWN,
                            )
                            casEventStoreStatus(
                                run.runId,
                                RunStatus.LIVE_STATES,
                                RunStatus.OUTCOME_UNKNOWN,
                                reason = "ledger_escalation",
                            )
                        } else {
                            runTerminalStore.finish(
                                run.runId,
                                RunTerminalState.INTERRUPTED,
                                PauseReason.PROCESS_RESTART,
                            )
                            // agent_run CREATED/RUNNING rows are settled by
                            // ChatEventProjector.replayUnfinished (running
                            // right after this), which also projects the last
                            // stream checkpoint — do NOT transition those here.
                            // Pause-state rows (e.g. RESUMABLE from a resolved
                            // stored response) are skipped by replayUnfinished
                            // by design, so this store settles them directly.
                            casEventStoreStatus(
                                run.runId,
                                RunStatus.PAUSE_STATES,
                                RunStatus.INTERRUPTED,
                                reason = "process_restart",
                            )
                        }
                        replayFinishedResults(run.runId, run.conversationId)
                    }

                    else -> Unit
                }
            }.onFailure { error ->
                Log.w(TAG, "recover: failed for run ${run.runId}", error)
            }
        }
    }

    /**
     * P6-01: resolve a run that has a stored server-side OpenAI Response.
     *
     * Returns true when this function decided the run's fate; false when the
     * run should fall through to the Phase 1 crash rules.
     *
     * Server outcomes:
     *  - COMPLETED: fetch only the missing events (sequence > cursor), merge
     *    the final message into the conversation, then finish COMPLETED with
     *    the SAME runId (terminal publish rules identical to Phase 1:
     *    conversation durable before COMPLETED).
     *  - CANCELLED / FAILED: settle the terminal, clear the cursor.
     *  - IN_PROGRESS: pause RESUMABLE (never terminal) and keep the cursor —
     *    the in-process resume path re-attaches when the conversation is
     *    continued.
     * Server unreachable at cold start: pause states stay resumable;
     * RUNNING falls back to Phase 1 (INTERRUPTED) with the cursor cleared.
     */
    private suspend fun resolveStoredResponse(run: RunTerminal): Boolean {
        val gateway = storedResponseGateway ?: return false
        val store = resumeStore ?: return false
        val session = gateway.resolve(run.runId) ?: return false // no stored response
        if (session.api == null) {
            // Cursor exists but the response is no longer resolvable
            // (provider deleted / support revoked) — Phase 1 decides, and the
            // orphaned cursor is cleared there.
            return false
        }
        val providerSetting = session.providerSetting ?: return false
        // P6-01 MAJOR: the user switch gates the server lookup too — off
        // means the pre-P6-01 crash rules decide (Phase 1 clears the
        // orphaned cursor there); the server is never queried.
        if (!providerSetting.enableResponsesResume) return false
        val status = try {
            session.api.fetchStatus(providerSetting, session.cursor.responseId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Offline at cold start: keep pause states resumable (the user
            // resume path re-attaches later); RUNNING falls back to Phase 1.
            if (run.state == RunTerminalState.RUNNING) {
                runCatching { resumeStore?.clear(run.runId) }
                    .onFailure { error -> Log.w(TAG, "resolveStoredResponse: cursor clear failed", error) }
                return false
            }
            Log.w(TAG, "resolveStoredResponse: server unreachable for ${run.runId}; keeping ${run.state}", e)
            return true
        }
        when (status.state) {
            StoredResponseState.COMPLETED -> {
                val finalMessage = fetchMissingEvents(session, run, store)
                if (finalMessage != null) {
                    mergeFinalMessage(run.conversationId, finalMessage)
                }
                runCatching { resumeStore?.clear(run.runId) }
                    .onFailure { error -> Log.w(TAG, "resolveStoredResponse: cursor clear failed", error) }
                runTerminalStore.finish(run.runId, RunTerminalState.COMPLETED, null)
                // Dual-write convergence (Step 3): without this, the protocol
                // row would later be stomped INTERRUPTED by replayUnfinished
                // while run_terminal says COMPLETED. The row may be parked
                // (RESUMABLE from an earlier server_in_progress park, or
                // WAITING_USER/WAITING_EXTERNAL from an approval stop) and no
                // pause state transitions straight to COMPLETED — unpark to
                // RUNNING first, then settle.
                casEventStoreStatus(run.runId, RunStatus.PAUSE_STATES, RunStatus.RUNNING, "server_resume")
                casEventStoreStatus(run.runId, RunStatus.LIVE_STATES, RunStatus.COMPLETED, "server_completed")
                // Effects may still be unsettled (crash mid tool dispatch).
                reconcileStartedEffects(run.runId)
                replayFinishedResults(run.runId, run.conversationId)
            }

            StoredResponseState.CANCELLED -> {
                runCatching { resumeStore?.clear(run.runId) }
                    .onFailure { error -> Log.w(TAG, "resolveStoredResponse: cursor clear failed", error) }
                runTerminalStore.finish(run.runId, RunTerminalState.CANCELLED, PauseReason.USER_STOP)
                casEventStoreStatus(run.runId, RunStatus.LIVE_STATES, RunStatus.CANCELLED, "server_cancelled")
                reconcileStartedEffects(run.runId)
            }

            StoredResponseState.FAILED -> {
                runCatching { resumeStore?.clear(run.runId) }
                    .onFailure { error -> Log.w(TAG, "resolveStoredResponse: cursor clear failed", error) }
                runTerminalStore.finish(run.runId, RunTerminalState.FAILED, null)
                casEventStoreStatus(run.runId, RunStatus.LIVE_STATES, RunStatus.FAILED, "server_failed")
                reconcileStartedEffects(run.runId)
            }

            StoredResponseState.IN_PROGRESS -> {
                // Not terminal: the same runId stays resumable with the
                // cursor, so the in-process resume path continues the stream.
                runTerminalStore.pause(run.runId, RunTerminalState.RESUMABLE, PauseReason.PROCESS_RESTART)
                // Pause-state rows are skipped by replayUnfinished, so the
                // protocol row must be parked here or it would be marked
                // INTERRUPTED while run_terminal keeps the run resumable. The
                // row may already be parked (e.g. WAITING_USER from a partial
                // approval-park write) — pause -> RESUMABLE is not a legal
                // direct transition, so unpark to RUNNING first.
                casEventStoreStatus(run.runId, RunStatus.PAUSE_STATES, RunStatus.RUNNING, "server_resume")
                casEventStoreStatus(run.runId, RunStatus.LIVE_STATES, RunStatus.RESUMABLE, "server_in_progress")
            }
        }
        return true
    }

    /**
     * Fetch only the events missing locally (sequence > persisted cursor) and
     * accumulate them onto the conversation's last assistant message. Returns
     * the final message, or null when the conversation is gone.
     */
    private suspend fun fetchMissingEvents(
        session: StoredResponseGateway.StoredResponseSession,
        run: RunTerminal,
        store: ResponseResumeStore,
    ): UIMessage? {
        val conversation = runCatching { Uuid.parse(run.conversationId) }
            .getOrNull()
            ?.let { conversationRepo.getConversationById(it) }
            ?: return null
        val providerSetting = session.providerSetting ?: return null
        val api = session.api ?: return null
        val seed = conversation.currentMessages.ifEmpty { listOf(UIMessage.assistant("")) }
        val accumulator = MessageStreamAccumulator(initialMessages = seed, model = null)
        api.streamStored(
            providerSetting = providerSetting,
            responseId = session.cursor.responseId,
            cursor = session.cursor,
            store = store,
            runId = run.runId,
        ).collect { chunk ->
            accumulator.append(chunk)
        }
        return accumulator.snapshot().lastOrNull()
    }

    /**
     * Merge the recovered final message into the conversation: the last
     * assistant node gets the final message as the selected variant (the
     * interrupted partial stays as an earlier variant, mirroring the normal
     * streaming merge); a missing assistant node appends a new one.
     */
    private suspend fun mergeFinalMessage(conversationId: String, finalMessage: UIMessage) {
        val id = runCatching { Uuid.parse(conversationId) }.getOrNull() ?: return
        val conversation = conversationRepo.getConversationById(id) ?: return
        val nodes = conversation.messageNodes.toMutableList()
        val lastAssistantIndex = nodes.indexOfLast { it.currentMessage.role == MessageRole.ASSISTANT }
        val updated = if (lastAssistantIndex >= 0) {
            val node = nodes[lastAssistantIndex]
            val messages = node.messages.toMutableList()
            val existing = messages.indexOfFirst { it.id == finalMessage.id }
            if (existing >= 0) messages[existing] = finalMessage else messages.add(finalMessage)
            nodes[lastAssistantIndex] = node.copy(messages = messages, selectIndex = messages.lastIndex)
            conversation.copy(messageNodes = nodes)
        } else {
            conversation.copy(messageNodes = nodes + MessageNode(messages = listOf(finalMessage)))
        }
        conversationRepo.updateConversation(updated)
        Log.i(TAG, "Merged recovered final message into conversation $conversationId")
    }

    /**
     * Apply the P1-02 recovery rules to STARTED effects of a dead run.
     * Returns true when at least one effect was escalated to OUTCOME_UNKNOWN.
     *
     * Also used after a user stop / generation failure (CANCELLED / FAILED
     * terminal): a STARTED non-idempotent effect must not be silently retried,
     * so it is escalated to OUTCOME_UNKNOWN and waits for the user.
     */
    suspend fun reconcileStartedEffects(runId: String): Boolean {
        var escalated = false
        for (effect in ledger.listByRun(runId)) {
            when (effect.status) {
                ToolEffectStatus.PREPARED -> Unit // re-enters approval/execution

                ToolEffectStatus.STARTED -> when (effect.effectClass) {
                    // readOnly: safe to retry. idempotentWrite: retried with
                    // its idempotency key. Neither needs a user decision.
                    ToolEffectClass.READ_ONLY,
                    ToolEffectClass.IDEMPOTENT_WRITE,
                    -> Unit

                    // Non-idempotent: the external side effect may have
                    // happened — never re-execute without user confirmation.
                    ToolEffectClass.NON_IDEMPOTENT_WRITE -> {
                        ledger.markOutcomeUnknown(effect.effectId, "interrupted_mid_execution")
                        escalated = true
                    }
                }

                else -> Unit
            }
        }
        return escalated
    }

    /**
     * FINISHED effects whose tool result never reached the conversation are
     * replayed from the ledger — the tool is NOT re-executed.
     */
    private suspend fun replayFinishedResults(runId: String, conversationId: String) {
        val conversation = runCatching { Uuid.parse(conversationId) }
            .getOrNull()
            ?.let { conversationRepo.getConversationById(it) }
            ?: return
        val finished = ledger.listByRun(runId).filter { it.status == ToolEffectStatus.FINISHED }
        if (finished.isEmpty()) return
        val payloads = finished.associate { effect ->
            effect.toolCallId to effect.resultPayload
        }
        var changed = false
        val updatedNodes = conversation.messageNodes.map { node ->
            node.copy(
                messages = node.messages.map { message ->
                    message.copy(
                        parts = message.parts.map { part ->
                            if (part !is UIMessagePart.Tool || part.isExecuted) return@map part
                            val payload = payloads[part.toolCallId] ?: return@map part
                            changed = true
                            part.copy(
                                output = decodePayload(payload),
                                approvalState = app.amber.ai.ui.ToolApprovalState.Approved,
                            )
                        }
                    )
                }
            )
        }
        if (!changed) return
        conversationRepo.updateConversation(
            conversation.copy(messageNodes = updatedNodes)
        )
        // M1: the result is durable in the conversation now — drop the replay
        // payload (the replay window ends once the result lands).
        for (effect in finished) {
            runCatching { ledger.markResultPersisted(effect.effectId) }
                .onFailure { error ->
                    Log.w(TAG, "replayFinishedResults: payload clear failed for ${effect.effectId}", error)
                }
        }
        Log.i(TAG, "Replayed ${finished.size} finished effect result(s) into conversation $conversationId")
    }

    private fun decodePayload(payload: String?): List<UIMessagePart> {
        if (payload.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<UIMessagePart>>(payload)
        }.getOrElse { error ->
            Log.w(TAG, "Failed to decode replayed result payload", error)
            emptyList()
        }
    }
}
