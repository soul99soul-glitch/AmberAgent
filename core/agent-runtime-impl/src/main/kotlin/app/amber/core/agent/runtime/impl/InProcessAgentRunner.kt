package app.amber.core.agent.runtime.impl

import android.util.Log
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentRegistry
import app.amber.core.agent.runtime.AgentRunHandle
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import app.amber.core.agent.runtime.RunTransitionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "AgentRunner"

class InProcessAgentRunner(
    private val registry: AgentRegistry,
    private val eventStore: AgentEventStore,
    private val runScopeFactory: (AgentRunId, AgentInput) -> app.amber.core.agent.runtime.RunScope = { id, _ ->
        LegacyRunScope(runId = id)
    },
    /**
     * Coroutine scope handler jobs run on. Tests pass a virtual-time scope
     * (StandardTestDispatcher) so `withTimeout` callers under runTest and the
     * handler share one clock.
     */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AgentRunner {

    private val runnerScope = scope
    private val snapshots = ConcurrentHashMap<AgentRunId, MutableStateFlow<AgentRunSnapshot>>()
    private val jobs = ConcurrentHashMap<AgentRunId, Job>()

    /**
     * Cancel intents recorded while no activation job was registered for the
     * runId — i.e. a [cancel] racing an activation still inside its launch-gate
     * window (jobs[runId] is written only at gate approval). The approved
     * activation consumes the intent right after its ownership transfer and
     * settles through the normal cancel path, so a user stop can never be
     * silently swallowed by the gate. Entries are consumed by exactly one
     * activation (or the racing cancel's own re-check); an intent left behind
     * by a gate-rejected activation is inert — a runId whose durable row is
     * terminal can never win a later approval anyway.
     */
    private val pendingCancels: MutableSet<AgentRunId> = ConcurrentHashMap.newKeySet()

    /**
     * Monotonic activation counter per runId — a process-local ownership
     * token for superseded activations (a run relaunched under the same id
     * while the previous activation's coroutine is still unwinding).
     *
     * Mechanism:
     * - The increment happens at gate approval, inside the activation
     *   coroutine: right after a fresh INSERT win, or inside the per-runId
     *   terminal-mutex critical section immediately after an Applied
     *   pause→RUNNING resume CAS. Either way it happens-before any older
     *   activation's late epoch re-reads (the resume path through the shared
     *   terminal mutex; the fresh path because no live predecessor can exist
     *   when an INSERT wins a runId that has no row).
     * - Each terminal path (COMPLETED/CANCELLED/FAILED) runs inside a
     *   per-runId [kotlinx.coroutines.sync.Mutex] critical section:
     *   epoch check → terminal CAS (suspending store I/O under
     *   NonCancellable) → epoch recheck → snapshot publish. Without the
     *   mutex, a relaunch's ownership bump plus its resume CAS could
     *   interleave between the stale activation's epoch check and its CAS,
     *   poisoning the write-once terminal row (a stale from=RUNNING CAS
     *   landing mid-activation of the newer run rejects both recovery and
     *   the live activation's own terminal write) and/or clobbering the live
     *   activation's snapshot. The snapshot publish follows the CAS outcome
     *   (terminal snapshot on Applied, rejection-sync on Rejected) so the
     *   observable state never precedes the persisted truth. The resume CAS
     *   shares the same mutex but needs no epoch check: a stale activation's
     *   resume CAS can only apply from a pause state or be rejected as a
     *   no-op, so it is harmless.
     *
     * Ownership is process-local by construction: the store has no fencing
     * column, so these epochs cannot fence a terminal CAS issued by another
     * process. Cross-process fencing would need an agent_run epoch column —
     * deliberately out of scope.
     */
    private val activationEpochs = ConcurrentHashMap<AgentRunId, AtomicLong>()

    /**
     * Per-runId mutex serializing the {epoch check → terminal CAS → epoch
     * recheck → snapshot publish} critical sections (and the resume CAS) of
     * consecutive activations of one run. A kotlinx Mutex — suspending and
     * thread-safe; never a ReentrantLock, which must not be held across
     * suspensions.
     */
    private val terminalLocks = ConcurrentHashMap<AgentRunId, Mutex>()

    override fun <I : AgentInput> launch(
        descriptorId: AgentDescriptorId,
        input: I,
        requestedRunId: AgentRunId?,
    ): Result<AgentRunHandle> {
        val registered = registry.resolve(descriptorId)
            ?: return Result.failure(IllegalArgumentException("No agent registered for $descriptorId"))

        val runId = requestedRunId ?: AgentRunId.new()
        val now = System.currentTimeMillis()
        val handle = AgentRunHandle(runId, descriptorId)

        // Reuse the StateFlow instance per runId: observers subscribe once
        // (e.g. flatMapLatest on the id) and must keep seeing updates when a
        // paused run resumes under the SAME runId. The flow is created here
        // (launch is non-suspending) but its RUNNING reset happens only at
        // gate approval — an activation the gate stands down must leave the
        // live activation's observable state untouched.
        val snapshot = snapshots.getOrPut(runId) {
            MutableStateFlow(
                AgentRunSnapshot(
                    runId = runId,
                    parentRunId = null,
                    descriptorId = descriptorId,
                    status = RunStatus.RUNNING,
                    startedAt = now,
                    finishedAt = null,
                )
            )
        }

        val job = runnerScope.launch(start = CoroutineStart.LAZY) {
            // The map value for this runId (identity-equal to the outer
            // `job` once assigned) — used by the conditional finally remove.
            val self = coroutineContext.job
            // Ownership token handed out by the gate; -1 means this
            // activation never took ownership of the run (the gate stood it
            // down or failed closed) — its catch paths must then never write
            // durable terminal states to a row they do not own.
            var mine = -1L
            val record = AgentRunRecord(
                runId = runId.value,
                parentRunId = null,
                agentDescriptorId = descriptorId.value,
                agentVersion = registered.descriptor.version,
                conversationId = null,
                messageNodeId = null,
                producesMessageId = null,
                assistantId = null,
                status = RunStatus.RUNNING,
                inputDigest = input.hashCode().toString(),
                inputSnapshotRef = null,
                inputSchemaVersion = 1,
                startedAt = now,
                finishedAt = null,
                interruptedReason = null,
            )
            try {
                // ---- Launch gate: the durable row, not the in-memory
                // snapshot, decides whether this activation may execute the
                // handler (see [gateHandler]). Ownership transfer — epoch
                // bump, jobs registration, the RUNNING snapshot reset — is
                // PART of the approval: from the gate's verdict (contiguous,
                // suspension-free) to the transfer below nothing can
                // interleave within this coroutine, so a gate-rejected
                // activation perturbs none of {epoch, jobs} and leaves a live
                // activation's running snapshot untouched — but it MAY sync
                // the persisted durable truth into the shared snapshot
                // (syncSnapshotOnRejection, e.g. a terminal winner).
                mine = gateHandler(record, runId, snapshot) ?: return@launch
                jobs[runId] = self
                // A cancel that raced this gate window found no job to cancel
                // and recorded its intent in [pendingCancels]. Ownership has
                // just transferred to this activation — consume the intent
                // HERE, before the RUNNING reset or the handler can publish
                // anything observable, and settle through the normal cancel
                // path: cancelling the now-registered job routes into the
                // catch below, whose terminal CAS lands CANCELLED on the
                // durable row. (Consume via remove() so exactly one of {this
                // check, the racing cancel's own re-check} delivers the
                // cancel.)
                if (pendingCancels.remove(runId)) {
                    self.cancel()
                    throw CancellationException("Run $runId cancelled during launch gate")
                }
                snapshot.value = AgentRunSnapshot(
                    runId = runId,
                    parentRunId = null,
                    descriptorId = descriptorId,
                    status = RunStatus.RUNNING,
                    startedAt = now,
                    finishedAt = null,
                )

                @Suppress("UNCHECKED_CAST")
                val agent = registered.factory() as app.amber.core.agent.runtime.Agent<I, *>
                val runScope = runScopeFactory(runId, input)
                val artifact = agent.handler.handle(input, runScope)

                val lock = terminalLocks.getOrPut(runId) { Mutex() }
                lock.withLock {
                    // Epoch re-read as late as possible, inside the critical
                    // section: if a newer activation (same runId relaunch)
                    // took ownership while this coroutine was unwinding, skip
                    // both the terminal CAS and any snapshot mutation — the
                    // newer activation owns the run's observable state and
                    // the write-once terminal write.
                    if (activationEpochs[runId]?.get() != mine) return@withLock
                    // Only an actively-executing run may complete: if the handler
                    // already moved the persisted state to a pause (e.g.
                    // WAITING_USER for tool approval), the CAS is rejected and
                    // the pause survives the handler's return.
                    val result = transitionTerminal(
                        runId,
                        RunStatus.COMPLETED,
                        expected = setOf(RunStatus.CREATED, RunStatus.RUNNING),
                    )
                    // Superseded while the CAS suspended across store I/O:
                    // publish nothing — neither a stale terminal snapshot nor
                    // the persisted winner of a rejection-sync.
                    if (activationEpochs[runId]?.get() != mine) return@withLock
                    // The observable snapshot follows the persisted outcome,
                    // never precedes it.
                    if (result is RunTransitionResult.Applied) {
                        val finishedAt = System.currentTimeMillis()
                        snapshot.value = snapshot.value.copy(
                            status = RunStatus.COMPLETED,
                            finishedAt = finishedAt,
                            artifact = artifact,
                        )
                        runCatching { Log.i(TAG, "Run $runId completed (${finishedAt - now}ms)") }
                    } else {
                        result.syncSnapshotOnRejection(snapshot)
                    }
                }

            } catch (e: CancellationException) {
                // Cancellation must always propagate; only the observable
                // writes below stand down when superseded.
                if (mine == -1L) {
                    // Cancelled inside the gate window, before ownership:
                    // the durable row (if any) belongs to no activation of
                    // ours — never write terminal states to a row we do not
                    // own; settle only the observable snapshot so no fake
                    // RUNNING lingers in listUnfinishedRuns. A row whose
                    // INSERT raced the cancellation is reaped by cold-start
                    // recovery (INTERRUPTED).
                    snapshot.value = snapshot.value.copy(
                        status = RunStatus.CANCELLED,
                        finishedAt = System.currentTimeMillis(),
                        error = e,
                    )
                    throw e
                }
                val lock = terminalLocks.getOrPut(runId) { Mutex() }
                lock.withLock {
                    if (activationEpochs[runId]?.get() != mine) return@withLock
                    // A user/runner cancel is CANCELLED — INTERRUPTED is reserved
                    // for process-death recovery.
                    val result = transitionTerminal(runId, RunStatus.CANCELLED)
                    if (activationEpochs[runId]?.get() != mine) return@withLock
                    // A row-less run (the gate's INSERT never landed) has
                    // nothing durable to settle — this activation was
                    // cancelled regardless: publish CANCELLED instead of
                    // failing the snapshot.
                    if (result is RunTransitionResult.Applied ||
                        result is RunTransitionResult.UnknownRun
                    ) {
                        snapshot.value = snapshot.value.copy(
                            status = RunStatus.CANCELLED,
                            finishedAt = System.currentTimeMillis(),
                            error = e,
                        )
                    } else {
                        result.syncSnapshotOnRejection(snapshot)
                    }
                }
                throw e
            } catch (e: Exception) {
                if (mine == -1L) {
                    // Pre-ownership failure outside the gate's own
                    // fail-closed paths (defensive): the row is not ours to
                    // settle durably — align the snapshot and stop.
                    snapshot.value = snapshot.value.copy(
                        status = RunStatus.FAILED,
                        finishedAt = System.currentTimeMillis(),
                        error = e,
                    )
                    runCatching { Log.e(TAG, "Run $runId failed before ownership", e) }
                } else {
                    val lock = terminalLocks.getOrPut(runId) { Mutex() }
                    lock.withLock {
                        if (activationEpochs[runId]?.get() != mine) return@withLock
                        val result = transitionTerminal(runId, RunStatus.FAILED, e.message?.take(500))
                        if (activationEpochs[runId]?.get() != mine) return@withLock
                        if (result is RunTransitionResult.Applied) {
                            snapshot.value = snapshot.value.copy(
                                status = RunStatus.FAILED,
                                finishedAt = System.currentTimeMillis(),
                                error = e,
                            )
                        } else {
                            result.syncSnapshotOnRejection(snapshot)
                        }
                        runCatching { Log.e(TAG, "Run $runId failed", e) }
                    }
                }
            } finally {
                // Conditional remove: a superseded job's finally must not
                // unregister the newer activation's live job — cancel(runId)
                // would silently no-op against the map. A gate-rejected
                // activation never registered, so this remove is a no-op for
                // it.
                jobs.remove(runId, self)
                trimCompletedSnapshots()
            }
        }
        job.start()

        return Result.success(handle)
    }

    override fun observe(runId: AgentRunId): StateFlow<AgentRunSnapshot> {
        return snapshots[runId] ?: MutableStateFlow(
            AgentRunSnapshot(
                runId = runId,
                parentRunId = null,
                descriptorId = AgentDescriptorId("unknown"),
                status = RunStatus.INTERRUPTED,
                startedAt = 0,
                finishedAt = null,
            )
        )
    }

    override fun cancel(runId: AgentRunId) {
        // jobs[runId] is written only at gate approval (never for an
        // activation the gate stands down), so a cancel racing the gate
        // window can observe no entry yet. In that case record the intent in
        // [pendingCancels] for the approved activation to consume right after
        // its ownership transfer — a user stop must never be silently
        // swallowed by the gate window. Registering the job itself earlier
        // would re-open the duplicated-launch corruption this gate guards
        // against.
        if (jobs[runId]?.cancel() == null) {
            pendingCancels.add(runId)
            // Re-check after recording: if the activation registered its job
            // between the null read above and this point, its own
            // pendingCancels check already ran and missed the add — deliver
            // the cancel here instead. Whichever side observes the intent
            // last, exactly one remove() wins and the job is cancelled.
            jobs[runId]?.let { job ->
                if (pendingCancels.remove(runId)) {
                    job.cancel()
                }
            }
        }
        snapshots[runId]?.let { snapshot ->
            // A late cancel never rewrites a published terminal state: the
            // cancelled job's own epoch-guarded terminal path settles both
            // the row and the snapshot.
            if (!snapshot.value.status.isTerminal) {
                snapshot.value = snapshot.value.copy(
                    status = RunStatus.CANCELLED,
                    finishedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    override suspend fun listUnfinishedRuns(): List<AgentRunSnapshot> {
        return snapshots.values
            .map { it.value }
            .filter { !it.status.isTerminal }
    }

    /**
     * Durable terminal write under NonCancellable: a cancelled coroutine must
     * still be able to settle its own run record. The CAS refuses to land
     * when the run already reached a terminal state (write-once) — or, for
     * [RunStatus.COMPLETED], when the handler parked the run at a pause.
     * Returns the transition outcome (null when the store call itself
     * failed) so callers can realign the in-memory snapshot with the
     * persisted winner.
     */
    private suspend fun transitionTerminal(
        runId: AgentRunId,
        to: RunStatus,
        reason: String? = null,
        expected: Set<RunStatus> = RunStatus.LIVE_STATES,
    ): app.amber.core.agent.runtime.RunTransitionResult? {
        return try {
            withContext(NonCancellable) {
                eventStore.transitionRun(
                    runId = runId,
                    expected = expected,
                    to = to,
                    reason = reason,
                )
            }
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Failed to persist terminal $to for $runId", e) }
            null
        }
    }

    /**
     * The optimistic terminal snapshot above assumed the CAS lands. When the
     * persisted run rejected it (handler parked the run at a pause, or a
     * winner already settled it), republish the snapshot with the persisted
     * truth so observers never see COMPLETED for a run that is in fact
     * parked at WAITING_USER. [RunTransitionResult.UnknownRun] means no
     * durable row backs the run at all: the in-memory RUNNING is a fake —
     * fail it so it cannot linger in [listUnfinishedRuns] forever.
     */
    private fun app.amber.core.agent.runtime.RunTransitionResult?.syncSnapshotOnRejection(
        snapshot: MutableStateFlow<AgentRunSnapshot>,
    ) {
        when (this) {
            is app.amber.core.agent.runtime.RunTransitionResult.Rejected -> {
                val winner = current ?: return
                if (snapshot.value.status == winner) return
                snapshot.value = snapshot.value.copy(
                    status = winner,
                    finishedAt = if (winner.isTerminal) {
                        snapshot.value.finishedAt ?: System.currentTimeMillis()
                    } else {
                        null
                    },
                )
            }
            is app.amber.core.agent.runtime.RunTransitionResult.UnknownRun -> {
                snapshot.value = snapshot.value.copy(
                    status = RunStatus.FAILED,
                    finishedAt = snapshot.value.finishedAt ?: System.currentTimeMillis(),
                )
            }
            else -> Unit
        }
    }

    /**
     * The durable launch gate: decides whether this activation may execute
     * the handler, and — on approval — transfers ownership (the epoch bump)
     * to it. Runs inside the launch coroutine (launch() itself is
     * non-suspending), so the persisted store — not the in-memory snapshot —
     * is the authority. Returns the activation's epoch token, or null when
     * the activation was stood down (it then perturbs neither the jobs map
     * nor the epoch nor a live activation's running snapshot; it may still
     * sync the persisted durable truth into the shared snapshot via
     * [syncSnapshotOnRejection]):
     *
     *  - Fresh run: the handler runs only after the run row is durably
     *    INSERTed (create-only). An insert failure fails the snapshot — a
     *    launch never executes against a run the store does not have. The
     *    INSERT winning means no live activation can exist for this runId,
     *    so the epoch bump right after it needs no lock.
     *  - Existing row (the insert reported a conflict, so the fresh/resume
     *    distinction is atomic by construction): the run must re-enter
     *    RUNNING through the pause→RUNNING CAS, under the per-runId terminal
     *    mutex — and the ownership bump happens inside that same critical
     *    section immediately after an Applied resume, so an older
     *    activation's {epoch check → CAS → epoch recheck} either ran
     *    entirely before the resume (its CAS met the still-paused row and
     *    was rejected) or entirely after (it sees the bumped epoch and
     *    stands down before its CAS). Applied → the resume wins the handler.
     *    Rejected → the row is terminal (write-once, the handler never
     *    re-runs) or already RUNNING (a live activation owns this run; the
     *    duplicate leaves epoch, jobs and that live RUNNING untouched) — it
     *    may only sync the persisted truth into the shared snapshot.
     *    UnknownRun → the row
     *    vanished between conflict and CAS (no production delete path):
     *    re-assert the fresh INSERT once; a second conflict means another
     *    activation created the row in between and owns the run.
     *
     * Cancellation propagates so the caller's catch(CancellationException)
     * settles the observable state.
     */
    private suspend fun gateHandler(
        record: AgentRunRecord,
        runId: AgentRunId,
        snapshot: MutableStateFlow<AgentRunSnapshot>,
    ): Long? {
        val epochs = activationEpochs.getOrPut(runId) { AtomicLong(0) }
        val created = try {
            eventStore.appendRun(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail-closed: without a durable run row the handler never runs.
            snapshot.value = snapshot.value.copy(
                status = RunStatus.FAILED,
                finishedAt = System.currentTimeMillis(),
                error = e,
            )
            runCatching { Log.w(TAG, "Run $runId not started: failed to persist run record", e) }
            return null
        }
        if (created) return epochs.incrementAndGet()

        var verdict: RunTransitionResult? = null
        val owned = terminalLocks.getOrPut(runId) { Mutex() }.withLock {
            val cas = try {
                eventStore.transitionRun(runId, RunStatus.PAUSE_STATES, RunStatus.RUNNING)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fail-closed: an unresolved resume never runs the handler.
                runCatching { Log.w(TAG, "Run $runId resume CAS failed; standing down", e) }
                null
            }
            verdict = cas
            // Ownership transfers HERE, inside the same critical section as
            // the Applied resume CAS (see the KDoc for why this placement is
            // load-bearing).
            if (cas is RunTransitionResult.Applied) epochs.incrementAndGet() else null
        }
        if (owned != null) return owned

        return when (val outcome = verdict) {
            is RunTransitionResult.Rejected -> {
                outcome.syncSnapshotOnRejection(snapshot)
                null
            }
            is RunTransitionResult.UnknownRun -> try {
                if (eventStore.appendRun(record)) epochs.incrementAndGet() else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            else -> null
        }
    }

    private fun trimCompletedSnapshots() {
        val completed = snapshots.values
            .map { it.value }
            .filter { it.status.isTerminal }
            .sortedByDescending { it.finishedAt ?: Long.MAX_VALUE }
        completed.drop(MAX_COMPLETED_SNAPSHOTS).forEach {
            snapshots.remove(it.runId)
            // Best-effort reclamation of the per-run bookkeeping — subject to
            // the same acceptable relaunch race as the snapshot trim above.
            activationEpochs.remove(it.runId)
            terminalLocks.remove(it.runId)
        }
    }

    private companion object {
        const val MAX_COMPLETED_SNAPSHOTS = 100
    }
}
