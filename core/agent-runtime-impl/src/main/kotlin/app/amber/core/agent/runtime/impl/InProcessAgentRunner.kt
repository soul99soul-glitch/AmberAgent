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
     * Monotonic activation counter per runId — a process-local ownership
     * token for superseded activations (a run relaunched under the same id
     * while the previous activation's coroutine is still unwinding).
     *
     * Mechanism:
     * - The increment is lock-free and synchronous inside [launch]
     *   (AtomicLong.incrementAndGet), before the runner coroutine is even
     *   created; it happens-before any older activation's late epoch
     *   re-reads.
     * - Each terminal path (COMPLETED/CANCELLED/FAILED) runs inside a
     *   per-runId [kotlinx.coroutines.sync.Mutex] critical section:
     *   epoch check → terminal CAS (suspending store I/O under
     *   NonCancellable) → epoch recheck → snapshot publish. Without the
     *   mutex, a relaunch's synchronous bump plus its resume CAS could
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
        // paused run resumes under the SAME runId. A (re)launch resets the
        // observable state to RUNNING.
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
        snapshot.value = AgentRunSnapshot(
            runId = runId,
            parentRunId = null,
            descriptorId = descriptorId,
            status = RunStatus.RUNNING,
            startedAt = now,
            finishedAt = null,
        )

        // Synchronous bump: it happens-before any completion path of an
        // older activation still unwinding for this runId, which only
        // re-reads the epoch (below) after this line has run.
        val mine = activationEpochs.getOrPut(runId) { AtomicLong(0) }.incrementAndGet()

        // Registration before start (CoroutineStart.LAZY): the coroutine body
        // cannot run before it is in `jobs`, so the finally's conditional
        // remove always sees its own entry — a fast-completing job can never
        // leave a stale, undeletable map entry behind.
        val job = runnerScope.launch(start = CoroutineStart.LAZY) {
            // The map value for this runId (identity-equal to the outer
            // `job` once assigned) — used by the conditional finally remove.
            val self = coroutineContext.job
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
                // handler (see [gateHandler]). The optimistic RUNNING reset
                // above is corrected by the gate whenever the persisted truth
                // disagrees — observers may transiently see RUNNING before
                // the in-coroutine verdict lands, never after.
                if (!gateHandler(record, runId, snapshot)) return@launch

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
            } finally {
                // Conditional remove: a superseded job's finally must not
                // unregister the newer activation's live job — cancel(runId)
                // would silently no-op against the map.
                jobs.remove(runId, self)
                trimCompletedSnapshots()
            }
        }
        jobs[runId] = job
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
        jobs[runId]?.cancel()
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
     * the handler. Runs inside the launch coroutine (launch() itself is
     * non-suspending), so the persisted store — not the in-memory snapshot —
     * is the authority:
     *
     *  - Fresh run: the handler runs only after the run row is durably
     *    INSERTed (create-only). An insert failure fails the snapshot — a
     *    launch never executes against a run the store does not have.
     *  - Existing row (the insert reported a conflict, so the fresh/resume
     *    distinction is atomic by construction): the run must re-enter
     *    RUNNING through the pause→RUNNING CAS, under the per-runId terminal
     *    mutex (a stale activation's resume CAS needs no epoch check — it can
     *    only apply from a pause state or be rejected as a no-op — but it
     *    must not interleave with another activation's terminal critical
     *    section). Applied → the resume wins the handler. Rejected → the row
     *    is terminal (write-once, the handler never re-runs) or already
     *    RUNNING (a live activation owns this run; the second activation
     *    stands down) — the snapshot is synced to the persisted truth.
     *    UnknownRun → the row vanished between conflict and CAS (no
     *    production delete path): re-assert the fresh INSERT once; a second
     *    conflict means another activation created the row in between and
     *    owns the run.
     *
     * Cancellation propagates so the caller's catch(CancellationException)
     * settles the observable state.
     */
    private suspend fun gateHandler(
        record: AgentRunRecord,
        runId: AgentRunId,
        snapshot: MutableStateFlow<AgentRunSnapshot>,
    ): Boolean {
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
            return false
        }
        if (created) return true

        val resume = terminalLocks.getOrPut(runId) { Mutex() }.withLock {
            try {
                eventStore.transitionRun(runId, RunStatus.PAUSE_STATES, RunStatus.RUNNING)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fail-closed: an unresolved resume never runs the handler.
                runCatching { Log.w(TAG, "Run $runId resume CAS failed; standing down", e) }
                null
            }
        }
        return when (resume) {
            is RunTransitionResult.Applied -> true
            is RunTransitionResult.Rejected -> {
                resume.syncSnapshotOnRejection(snapshot)
                false
            }
            is RunTransitionResult.UnknownRun -> try {
                eventStore.appendRun(record)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            null -> false
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
