package app.amber.core.agent.runtime.impl

import app.amber.core.agent.runtime.Agent
import app.amber.core.agent.runtime.AgentArtifact
import app.amber.core.agent.runtime.AgentCapability
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentEventRecord
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentHandler
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.agent.runtime.RunStatusTransitions
import app.amber.core.agent.runtime.RunTransitionResult
import app.amber.core.agent.runtime.TraceSpanRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for superseded activations: a run relaunched under the
 * same runId while the previous activation's coroutine is still unwinding.
 *
 * The stale activation must not write its terminal state (terminal rows are
 * write-once — one stale from=RUNNING CAS poisons the row forever, rejecting
 * recovery and the live activation's own terminal write as illegal), and its
 * finally must not unregister the live activation's job (cancel(runId)
 * would silently no-op).
 */
class InProcessAgentRunnerSupersededActivationTest {

    @Serializable
    private data class FakeInput(val value: String) : AgentInput

    @Serializable
    private data class FakeArtifact(val echoed: String) : AgentArtifact

    private val descriptor = AgentDescriptor(
        id = AgentDescriptorId("fake"),
        version = "1.0",
        displayName = "Fake",
        capabilities = setOf(AgentCapability.CHAT_TURN),
    )

    /** Recorded store transition attempt with its CAS outcome. */
    private data class TransitionAttempt(
        val expected: Set<RunStatus>,
        val to: RunStatus,
        val result: RunTransitionResult,
    )

    /** In-memory CAS store mirroring the Room store's protocol semantics. */
    private class RecordingEventStore : AgentEventStore {
        val runs = mutableMapOf<String, AgentRunRecord>()

        override suspend fun appendRun(run: AgentRunRecord): Boolean =
            runs.putIfAbsent(run.runId, run) == null

        override suspend fun appendEvent(event: AgentEventRecord) {}
        override suspend fun appendEventAllocatingSeq(event: AgentEventRecord): AgentEventRecord = event

        override suspend fun transitionRun(
            runId: AgentRunId,
            expected: Set<RunStatus>,
            to: RunStatus,
            reason: String?,
        ): RunTransitionResult {
            val current = runs[runId.value] ?: return RunTransitionResult.UnknownRun(to)
            val from = current.status
            if (!RunStatusTransitions.canTransition(from, to)) {
                return RunTransitionResult.Rejected(from, to, illegal = true)
            }
            if (expected.isNotEmpty() && from !in expected) {
                return RunTransitionResult.Rejected(from, to, illegal = false)
            }
            runs[runId.value] = current.copy(
                status = to,
                finishedAt = if (to.isTerminal) System.currentTimeMillis() else current.finishedAt,
                interruptedReason = reason ?: current.interruptedReason,
            )
            return RunTransitionResult.Applied(from, to)
        }

        override suspend fun appendSpan(span: TraceSpanRecord) {}
        override fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot> = emptyFlow()
        override suspend fun listEvents(runId: AgentRunId): List<AgentEventRecord> = emptyList()
        override suspend fun deleteEventsByType(runId: AgentRunId, type: String) {}
        override suspend fun listUnfinishedRuns(): List<AgentRunRecord> =
            runs.values.filter { !it.status.isTerminal }
        override suspend fun markInterrupted(runId: AgentRunId, reason: String) {}
    }

    /**
     * CAS store wrapper with one adversarial instrument: it holds the
     * runner's post-handler terminal COMPLETED shape (expected
     * CREATED/RUNNING) inside the store until a pause->RUNNING resume CAS
     * has landed, so a superseded activation's stale write would
     * deterministically land mid-activation of the newer run — the exact
     * race window the epoch guard closes.
     */
    private class InstrumentedEventStore(
        private val delegate: RecordingEventStore,
    ) : AgentEventStore by delegate {
        val attempts = mutableListOf<TransitionAttempt>()
        private val resumeApplied = CompletableDeferred<Unit>()

        override suspend fun transitionRun(
            runId: AgentRunId,
            expected: Set<RunStatus>,
            to: RunStatus,
            reason: String?,
        ): RunTransitionResult {
            if (to == RunStatus.COMPLETED && expected == setOf(RunStatus.CREATED, RunStatus.RUNNING)) {
                resumeApplied.await()
            }
            val result = delegate.transitionRun(runId, expected, to, reason)
            attempts += TransitionAttempt(expected, to, result)
            val applied = result as? RunTransitionResult.Applied
            if (applied != null && applied.from in RunStatus.PAUSE_STATES && applied.to == RunStatus.RUNNING) {
                resumeApplied.complete(Unit)
            }
            return result
        }
    }

    /**
     * CAS store wrapper for the mid-CAS interleaving: it holds the FIRST
     * terminal COMPLETED-shaped transitionRun INSIDE the store (its caller —
     * the stale activation — holds the runner's per-runId terminal mutex
     * while suspended) until released. A relaunch during that window makes
     * the new activation's resume CAS queue on the same mutex behind the
     * stale critical section, so the stale CAS deterministically
     * re-evaluates against the still-WAITING_USER row — the exact
     * {epoch check → suspending CAS → epoch recheck → publish} window the
     * guarded terminal paths close.
     */
    private class MidCasInstrumentedEventStore(
        private val delegate: RecordingEventStore,
    ) : AgentEventStore by delegate {
        val attempts = mutableListOf<TransitionAttempt>()
        val staleTerminalEntered = CompletableDeferred<Unit>()
        val releaseStaleTerminal = CompletableDeferred<Unit>()
        private val completedShapedCalls = AtomicInteger(0)

        override suspend fun transitionRun(
            runId: AgentRunId,
            expected: Set<RunStatus>,
            to: RunStatus,
            reason: String?,
        ): RunTransitionResult {
            val isStaleTerminal =
                to == RunStatus.COMPLETED &&
                    expected == setOf(RunStatus.CREATED, RunStatus.RUNNING) &&
                    completedShapedCalls.incrementAndGet() == 1
            if (isStaleTerminal) {
                staleTerminalEntered.complete(Unit)
                releaseStaleTerminal.await()
            }
            val result = delegate.transitionRun(runId, expected, to, reason)
            attempts += TransitionAttempt(expected, to, result)
            return result
        }
    }

    /** Handler invocation counter, exposed for gate pinning assertions. */
    private val invocations = AtomicInteger(0)

    private fun runnerWithAgent(
        store: AgentEventStore,
        scope: CoroutineScope,
        onInvoke: suspend (invocation: Int) -> FakeArtifact,
    ): InProcessAgentRunner {
        invocations.set(0)
        val registry = InMemoryAgentRegistry().apply {
            register(
                descriptor = descriptor,
                inputClass = FakeInput::class,
                inputSerializer = FakeInput.serializer(),
                artifactSerializer = FakeArtifact.serializer(),
                factory = {
                    object : Agent<FakeInput, FakeArtifact> {
                        override val descriptor = this@InProcessAgentRunnerSupersededActivationTest.descriptor
                        override val handler = AgentHandler<FakeInput, FakeArtifact> { input, _ ->
                            onInvoke(invocations.incrementAndGet())
                        }
                    }
                },
            )
        }
        return InProcessAgentRunner(registry, store, scope = scope)
    }

    @Test
    fun `stale completion CAS cannot corrupt a resumed run`() = runTest {
        val delegate = RecordingEventStore()
        val store = InstrumentedEventStore(delegate)
        val firstMayReturn = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val runId = AgentRunId("epoch-resume-run")
        // The runner's jobs share the test's virtual clock and only move
        // when the test advances the scheduler — fully deterministic.
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = runnerWithAgent(store, runnerScope) { invocation ->
            when (invocation) {
                1 -> {
                    // Park the run for approval (the turn-hook shape), then
                    // hold inside the handler until the test has relaunched
                    // the same runId — the original bug's window.
                    store.transitionRun(runId, RunStatus.LIVE_STATES, RunStatus.WAITING_USER)
                    firstMayReturn.await()
                    FakeArtifact("first")
                }
                else -> {
                    secondRelease.await()
                    FakeArtifact("second")
                }
            }
        }

        // Activation 1 parks the run, then holds inside the handler.
        runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(RunStatus.WAITING_USER, delegate.runs.getValue(runId.value).status)

        // Relaunch under the same runId while activation 1 is unwinding.
        // The relaunch's resume CAS lands and ownership (the epoch) transfers
        // here, inside the per-runId terminal mutex — before activation 1's
        // post-handler code runs again.
        runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()

        // Mid-activation of launch 2: its resume CAS re-entered RUNNING and
        // no terminal COMPLETED write has been attempted.
        assertEquals(RunStatus.RUNNING, delegate.runs.getValue(runId.value).status)
        assertEquals(RunStatus.RUNNING, runner.observe(runId).value.status)
        assertTrue(store.attempts.none { it.to == RunStatus.COMPLETED })

        // Activation 1 unwinds: its post-handler path must stand down —
        // neither an optimistic COMPLETED snapshot nor a terminal CAS.
        firstMayReturn.complete(Unit)
        advanceUntilIdle()

        assertTrue(store.attempts.none { it.to == RunStatus.COMPLETED })
        assertEquals(RunStatus.RUNNING, delegate.runs.getValue(runId.value).status)
        assertEquals(RunStatus.RUNNING, runner.observe(runId).value.status)

        // Activation 2 finishes: exactly one COMPLETED lands, from RUNNING —
        // the live activation's own write.
        secondRelease.complete(Unit)
        advanceUntilIdle()

        val completed = store.attempts.filter { it.to == RunStatus.COMPLETED }
        assertEquals(1, completed.size)
        val applied = completed.single().result as RunTransitionResult.Applied
        assertEquals(RunStatus.RUNNING, applied.from)
        assertEquals(
            1,
            store.attempts.count {
                it.result is RunTransitionResult.Applied &&
                    (it.result as RunTransitionResult.Applied).from == RunStatus.RUNNING &&
                    it.to == RunStatus.COMPLETED
            },
        )
        assertEquals(RunStatus.COMPLETED, delegate.runs.getValue(runId.value).status)
        assertEquals(RunStatus.COMPLETED, runner.observe(runId).value.status)
        assertEquals(FakeArtifact("second"), runner.observe(runId).value.artifact)
    }

    @Test
    fun `superseded finally does not unregister the live job`() = runTest {
        val store = RecordingEventStore()
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        var secondObservedCancellation = false
        val runId = AgentRunId("epoch-cancel-run")
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = runnerWithAgent(store, runnerScope) { invocation ->
            when (invocation) {
                1 -> {
                    // Park the run for approval (the turn-hook shape), then
                    // unwind while activation 2 is live — the pause-window
                    // supersede: since the launch gate, a relaunch only runs
                    // a handler through the pause→RUNNING resume CAS.
                    store.transitionRun(runId, RunStatus.LIVE_STATES, RunStatus.WAITING_USER)
                    firstRelease.await()
                    FakeArtifact("first")
                }
                else -> {
                    try {
                        secondRelease.await()
                    } catch (e: CancellationException) {
                        secondObservedCancellation = true
                        throw e
                    }
                    FakeArtifact("second")
                }
            }
        }

        // Activation 1 parks the run, then holds inside the handler.
        runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(RunStatus.WAITING_USER, store.runs.getValue(runId.value).status)

        // Activation 2 resumes the paused run under the same runId: the
        // pause→RUNNING CAS applies and it blocks in its handler. jobs[runId]
        // now points at activation 2.
        runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(RunStatus.RUNNING, store.runs.getValue(runId.value).status)

        // Supersede activation 1 while activation 2 is live: its post-handler
        // path stands down (epoch) and its finally must not unregister the
        // live job (conditional remove), leaving cancel(runId) effective.
        firstRelease.complete(Unit)
        advanceUntilIdle()
        assertEquals(RunStatus.RUNNING, runner.observe(runId).value.status)

        runner.cancel(runId)
        advanceUntilIdle()

        // The LIVE job observed the cancellation: its own cancellation path
        // persisted CANCELLED (cancel(runId) only mutates the snapshot; the
        // durable write proves jobs[runId] still resolved to the live job).
        assertTrue("live job observed cancellation", secondObservedCancellation)
        assertEquals(RunStatus.CANCELLED, store.runs.getValue(runId.value).status)
        assertEquals(RunStatus.CANCELLED, runner.observe(runId).value.status)
    }

    @Test
    fun `superseded mid-CAS terminal cannot publish stale state`() = runTest {
        val delegate = RecordingEventStore()
        val store = MidCasInstrumentedEventStore(delegate)
        val firstMayReturn = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val runId = AgentRunId("epoch-midcas-run")
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = runnerWithAgent(store, runnerScope) { invocation ->
            when (invocation) {
                1 -> {
                    // Park the run for approval, then hold inside the handler
                    // until the test has armed the mid-CAS window.
                    store.transitionRun(runId, RunStatus.LIVE_STATES, RunStatus.WAITING_USER)
                    firstMayReturn.await()
                    FakeArtifact("first")
                }
                else -> {
                    secondRelease.await()
                    FakeArtifact("second")
                }
            }
        }

        // Activation 1 parks the run, then holds inside the handler.
        runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(RunStatus.WAITING_USER, delegate.runs.getValue(runId.value).status)

        // Observe every status the live snapshot publishes from here on.
        val observed = mutableListOf<RunStatus>()
        val collector = launch {
            runner.observe(runId).collect { observed.add(it.status) }
        }
        advanceUntilIdle()
        assertEquals(listOf(RunStatus.RUNNING), observed)

        // Activation 1 unwinds INTO its terminal CAS and suspends INSIDE the
        // store call — holding the runner's terminal mutex while suspended.
        firstMayReturn.complete(Unit)
        advanceUntilIdle()
        assertTrue("stale terminal CAS entered the store", store.staleTerminalEntered.isCompleted)

        // Relaunch under the same runId: the new activation's resume CAS
        // mutex-queues behind the stale critical section, so the row is
        // still WAITING_USER when the stale CAS resumes, and ownership (the
        // epoch) has not transferred yet.
        runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(RunStatus.WAITING_USER, delegate.runs.getValue(runId.value).status)

        // Release the stale CAS: it re-evaluates against the still-WAITING_USER
        // row and is REJECTED — the write-once terminal row is never poisoned.
        // Ownership has not transferred yet, so the stale activation's
        // post-CAS recheck still passes and it publishes the persisted pause
        // (the truthful rejection-sync) — a transient state the live
        // activation's reset below overwrites when its own resume applies.
        store.releaseStaleTerminal.complete(Unit)
        advanceUntilIdle()

        // The stale activation recorded no Applied COMPLETED: its CAS was
        // rejected by the still-paused row...
        val completedAttempts = store.attempts.filter { it.to == RunStatus.COMPLETED }
        assertEquals(1, completedAttempts.size)
        val stale = completedAttempts.single().result as RunTransitionResult.Rejected
        assertEquals(RunStatus.WAITING_USER, stale.current)
        // ...and only afterwards did the live activation's resume CAS land.
        assertEquals(RunStatus.RUNNING, delegate.runs.getValue(runId.value).status)
        // The stale activation never published a TERMINAL state: the only
        // thing it may publish is the persisted truth of its own rejection.
        // (Conflation may hide the transient pause/RUNNING values from the
        // collector; whatever it observed stays non-terminal.)
        assertTrue("no terminal published before the live activation's own", observed.none { it.isTerminal })

        // Activation 2 finishes: exactly one Applied COMPLETED, from RUNNING —
        // the live activation's own write.
        secondRelease.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            1,
            store.attempts.count {
                it.to == RunStatus.COMPLETED && it.result is RunTransitionResult.Applied
            },
        )
        val applied = store.attempts
            .map { it.result }
            .filterIsInstance<RunTransitionResult.Applied>()
            .single { it.to == RunStatus.COMPLETED }
        assertEquals(RunStatus.RUNNING, applied.from)
        assertEquals(RunStatus.COMPLETED, delegate.runs.getValue(runId.value).status)
        assertEquals(RunStatus.COMPLETED, runner.observe(runId).value.status)
        assertEquals(FakeArtifact("second"), runner.observe(runId).value.artifact)
        // Exactly one COMPLETED emission, and only at the very end: the
        // stale activation never published anything.
        assertEquals(1, observed.count { it == RunStatus.COMPLETED })
        assertEquals(RunStatus.COMPLETED, observed.last())
        collector.cancel()
    }

    @Test
    fun `cancel after completion keeps the published terminal`() = runTest {
        val store = RecordingEventStore()
        val runId = AgentRunId("cancel-after-complete-run")
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = runnerWithAgent(store, runnerScope) { _ -> FakeArtifact("done") }

        runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(RunStatus.COMPLETED, store.runs.getValue(runId.value).status)
        assertEquals(RunStatus.COMPLETED, runner.observe(runId).value.status)

        // A late cancel must not rewrite the published terminal state (and
        // the store row is write-once COMPLETED regardless).
        runner.cancel(runId)
        advanceUntilIdle()

        assertEquals(RunStatus.COMPLETED, runner.observe(runId).value.status)
        assertEquals(RunStatus.COMPLETED, store.runs.getValue(runId.value).status)
    }

    @Test
    fun `completed run leaves no stale job and relaunches cleanly`() = runTest {
        val store = RecordingEventStore()
        val runId = AgentRunId("fast-complete-relaunch-run")
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val runner = runnerWithAgent(store, runnerScope) { invocation ->
            FakeArtifact("run-$invocation")
        }

        // Launch 1 runs to full completion on the scheduler — with lazy
        // registration its finally always finds its own jobs entry, so no
        // stale entry survives.
        runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(RunStatus.COMPLETED, runner.observe(runId).value.status)
        assertEquals(1, invocations.get())

        // A subsequent cancel is therefore a clean no-op: it must NOT flip
        // the published terminal snapshot.
        runner.cancel(runId)
        advanceUntilIdle()
        assertEquals(RunStatus.COMPLETED, runner.observe(runId).value.status)

        // A second launch of the same runId is gated by the write-once
        // COMPLETED row: the handler never re-runs, the snapshot and the
        // store stay COMPLETED, and the StateFlow instance is reused.
        val flow = runner.observe(runId)
        runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(1, invocations.get())
        assertSame(flow, runner.observe(runId))
        assertEquals(RunStatus.COMPLETED, runner.observe(runId).value.status)
        assertEquals(RunStatus.COMPLETED, store.runs.getValue(runId.value).status)
    }
}
