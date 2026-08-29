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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pinning tests for the launch gate (P0 lifecycle gating): the handler runs
 * only when the durable store grants it — a fresh create (INSERT won), or a
 * pause→RUNNING resume CAS Applied. Everything else — an insert conflict
 * with a terminal row, an insert failure, a second activation against a live
 * RUNNING row — must never execute the handler.
 */
class InProcessAgentRunnerLaunchGateTest {

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

    /** In-memory CAS store mirroring the Room store's protocol semantics. */
    private open class RecordingEventStore : AgentEventStore {
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

    /** Store whose durable run-row create always fails (disk error shape). */
    private class FailingAppendStore : AgentEventStore by RecordingEventStore() {
        override suspend fun appendRun(run: AgentRunRecord): Boolean {
            throw IllegalStateException("durable create failed")
        }
    }

    /**
     * Store whose fresh-create (INSERT) suspends inside the gate until
     * [gate] completes — simulates durable store latency stretching the
     * gate window of a fresh launch.
     */
    private class GatedAppendStore : RecordingEventStore() {
        val gate = CompletableDeferred<Unit>()
        override suspend fun appendRun(run: AgentRunRecord): Boolean {
            gate.await()
            return super.appendRun(run)
        }
    }

    /**
     * Store whose resume CAS suspends inside the gate until [gate]
     * completes — simulates durable store latency stretching the gate
     * window of a paused-run relaunch.
     */
    private class GatedResumeStore : RecordingEventStore() {
        val gate = CompletableDeferred<Unit>()
        override suspend fun transitionRun(
            runId: AgentRunId,
            expected: Set<RunStatus>,
            to: RunStatus,
            reason: String?,
        ): RunTransitionResult {
            gate.await()
            return super.transitionRun(runId, expected, to, reason)
        }
    }

    private class Harness(
        val runner: InProcessAgentRunner,
        val invocations: AtomicInteger,
    )

    private fun runnerWithAgent(
        store: AgentEventStore,
        scope: CoroutineScope,
        onInvoke: suspend (invocation: Int) -> FakeArtifact = { FakeArtifact("ok") },
    ): Harness {
        val invocations = AtomicInteger(0)
        val registry = InMemoryAgentRegistry().apply {
            register(
                descriptor = descriptor,
                inputClass = FakeInput::class,
                inputSerializer = FakeInput.serializer(),
                artifactSerializer = FakeArtifact.serializer(),
                factory = {
                    object : Agent<FakeInput, FakeArtifact> {
                        override val descriptor = this@InProcessAgentRunnerLaunchGateTest.descriptor
                        override val handler = AgentHandler<FakeInput, FakeArtifact> { input, _ ->
                            onInvoke(invocations.incrementAndGet())
                        }
                    }
                },
            )
        }
        return Harness(InProcessAgentRunner(registry, store, scope = scope), invocations)
    }

    private fun runRecord(runId: AgentRunId, status: RunStatus) = AgentRunRecord(
        runId = runId.value,
        parentRunId = null,
        agentDescriptorId = descriptor.id.value,
        agentVersion = descriptor.version,
        conversationId = null,
        messageNodeId = null,
        producesMessageId = null,
        assistantId = null,
        status = status,
        inputDigest = "0",
        inputSnapshotRef = null,
        inputSchemaVersion = 1,
        startedAt = 0,
        finishedAt = null,
        interruptedReason = null,
    )

    @Test
    fun `appendRun conflict with a terminal row never runs the handler`() = runTest {
        val store = RecordingEventStore()
        val runId = AgentRunId("gate-terminal-run")
        // Pre-existing write-once terminal row (e.g. the run completed before
        // the process restarted). The INSERT conflicts — the gate must stand
        // down instead of re-executing the handler.
        store.runs[runId.value] = runRecord(runId, RunStatus.COMPLETED)
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val harness = runnerWithAgent(store, runnerScope)

        harness.runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()

        assertEquals(0, harness.invocations.get())
        assertEquals(RunStatus.COMPLETED, store.runs.getValue(runId.value).status)
        // The snapshot followed the persisted terminal instead of lingering
        // in the optimistic RUNNING reset.
        assertEquals(RunStatus.COMPLETED, harness.runner.observe(runId).value.status)
    }

    @Test
    fun `appendRun failure fails the snapshot and never runs the handler`() = runTest {
        val store = FailingAppendStore()
        val runId = AgentRunId("gate-append-failure-run")
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val harness = runnerWithAgent(store, runnerScope)

        harness.runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()

        assertEquals(0, harness.invocations.get())
        val snapshot = harness.runner.observe(runId).value
        assertEquals(RunStatus.FAILED, snapshot.status)
        assertNotNull(snapshot.error)
        // The fake RUNNING never lingers in the unfinished list.
        assertTrue(harness.runner.listUnfinishedRuns().isEmpty())
    }

    @Test
    fun `paused row runs the handler only through an Applied resume CAS`() = runTest {
        val store = RecordingEventStore()
        val runId = AgentRunId("gate-pause-resume-run")
        store.runs[runId.value] = runRecord(runId, RunStatus.WAITING_USER)
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val harness = runnerWithAgent(store, runnerScope)

        harness.runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()

        // The pause→RUNNING CAS applied: the handler ran exactly once and the
        // run settled through its own terminal write (proving this activation
        // owned the run).
        assertEquals(1, harness.invocations.get())
        assertEquals(RunStatus.COMPLETED, store.runs.getValue(runId.value).status)
        assertEquals(RunStatus.COMPLETED, harness.runner.observe(runId).value.status)
        assertEquals(FakeArtifact("ok"), harness.runner.observe(runId).value.artifact)
    }

    @Test
    fun `second launch against a live RUNNING run never disturbs the live activation`() = runTest {
        val store = RecordingEventStore()
        val runId = AgentRunId("gate-live-run")
        val firstMayReturn = CompletableDeferred<Unit>()
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val harness = runnerWithAgent(store, runnerScope) { invocation ->
            if (invocation == 1) firstMayReturn.await()
            FakeArtifact("run-$invocation")
        }

        // Activation 1 blocks in its handler; the row is RUNNING under it.
        harness.runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(RunStatus.RUNNING, store.runs.getValue(runId.value).status)
        val startedAtBefore = harness.runner.observe(runId).value.startedAt
        // Real-clock gap: a buggy relaunch that reset the snapshot would
        // stamp a strictly later startedAt, so the pin below cannot pass by
        // millisecond coincidence.
        Thread.sleep(5)

        // A second launch against the live run must not run the handler and
        // must not touch anything the live activation owns: its gate resume
        // CAS is rejected by the RUNNING row (a live activation owns the
        // run), and since ownership transfer (epoch bump, jobs registration,
        // snapshot reset) is part of gate approval, the duplicate leaves
        // {snapshot, epoch, jobs} exactly as it found them. The API result
        // itself cannot reflect the durable verdict synchronously (launch is
        // non-suspending; the store is the authority) — the observable
        // contract below is what production depends on.
        harness.runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId)
        advanceUntilIdle()
        assertEquals(1, harness.invocations.get())
        assertEquals(RunStatus.RUNNING, store.runs.getValue(runId.value).status)
        assertEquals(RunStatus.RUNNING, harness.runner.observe(runId).value.status)
        // The rejected duplicate must not have reset the live snapshot.
        assertEquals(startedAtBefore, harness.runner.observe(runId).value.startedAt)

        // The live activation owns the run end to end: its terminal CAS
        // lands (epoch untouched by the duplicate) and the durable row
        // settles COMPLETED — the exact cold-start-mislabels-INTERRUPTED
        // regression this pins.
        firstMayReturn.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, harness.invocations.get())
        assertEquals(RunStatus.COMPLETED, store.runs.getValue(runId.value).status)
        val snapshot = harness.runner.observe(runId).value
        assertEquals(RunStatus.COMPLETED, snapshot.status)
        assertEquals(FakeArtifact("run-1"), snapshot.artifact)
    }

    @Test
    fun `cancel reaches the live activation after a rejected duplicate launch`() = runTest {
        val store = RecordingEventStore()
        val runId = AgentRunId("gate-live-cancel-run")
        val firstMayReturn = CompletableDeferred<Unit>()
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val harness = runnerWithAgent(store, runnerScope) { invocation ->
            if (invocation == 1) firstMayReturn.await()
            FakeArtifact("run-$invocation")
        }

        harness.runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(RunStatus.RUNNING, store.runs.getValue(runId.value).status)

        // A duplicate launch must not steal the jobs map slot: cancel(runId)
        // still reaches the first (and only) activation while it runs, and
        // its own cancellation path settles the durable row.
        harness.runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId)
        advanceUntilIdle()
        harness.runner.cancel(runId)
        advanceUntilIdle()

        assertEquals(1, harness.invocations.get())
        assertEquals(RunStatus.CANCELLED, store.runs.getValue(runId.value).status)
        assertEquals(RunStatus.CANCELLED, harness.runner.observe(runId).value.status)
    }

    @Test
    fun `cancel arriving inside the fresh-launch gate window is not swallowed`() = runTest {
        // The store's INSERT suspends, stretching the gate window: the cancel
        // below lands BEFORE the activation registers its job (jobs[runId] is
        // written only at gate approval). Regression pin: the old code only
        // wrote snapshot=CANCELLED here; the approval path then reset the
        // snapshot to RUNNING and the never-cancelled job ran the handler to
        // COMPLETED — the user stop was silently swallowed and the durable
        // row never settled CANCELLED.
        val store = GatedAppendStore()
        val runId = AgentRunId("gate-window-cancel-fresh-run")
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val harness = runnerWithAgent(store, runnerScope)

        harness.runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(0, harness.invocations.get()) // still suspended inside the gate

        harness.runner.cancel(runId)
        // The cancel marked the observable intent immediately...
        assertEquals(RunStatus.CANCELLED, harness.runner.observe(runId).value.status)

        // ...the gate then approves, and the approved activation must consume
        // the pending cancel: no handler invocation, durable row CANCELLED.
        store.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, harness.invocations.get())
        assertEquals(RunStatus.CANCELLED, store.runs.getValue(runId.value).status)
        val snapshot = harness.runner.observe(runId).value
        assertEquals(RunStatus.CANCELLED, snapshot.status)
        assertNotNull(snapshot.finishedAt)
        assertTrue(harness.runner.listUnfinishedRuns().isEmpty())
    }

    @Test
    fun `cancel arriving inside the resume-CAS gate window is not swallowed`() = runTest {
        // Same regression on the paused-run relaunch path: the cancel lands
        // while the resume CAS is suspended in-flight, before ownership
        // transfer. The approved activation must settle the durable row
        // CANCELLED instead of resurrecting a RUNNING snapshot.
        val store = GatedResumeStore()
        val runId = AgentRunId("gate-window-cancel-resume-run")
        store.runs[runId.value] = runRecord(runId, RunStatus.WAITING_USER)
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val harness = runnerWithAgent(store, runnerScope)

        harness.runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(0, harness.invocations.get()) // still suspended inside the gate

        harness.runner.cancel(runId)
        assertEquals(RunStatus.CANCELLED, harness.runner.observe(runId).value.status)

        store.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, harness.invocations.get())
        assertEquals(RunStatus.CANCELLED, store.runs.getValue(runId.value).status)
        val snapshot = harness.runner.observe(runId).value
        assertEquals(RunStatus.CANCELLED, snapshot.status)
        assertNotNull(snapshot.finishedAt)
        assertTrue(harness.runner.listUnfinishedRuns().isEmpty())
    }

    @Test
    fun `gate window without a cancel runs the handler normally`() = runTest {
        // Control for the two pins above: the same gated store with no
        // cancel in the window must behave exactly as before — one handler
        // run, durable COMPLETED, snapshot COMPLETED with the artifact.
        val store = GatedAppendStore()
        val runId = AgentRunId("gate-window-no-cancel-run")
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val harness = runnerWithAgent(store, runnerScope)

        harness.runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(0, harness.invocations.get()) // still suspended inside the gate

        store.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, harness.invocations.get())
        assertEquals(RunStatus.COMPLETED, store.runs.getValue(runId.value).status)
        val snapshot = harness.runner.observe(runId).value
        assertEquals(RunStatus.COMPLETED, snapshot.status)
        assertEquals(FakeArtifact("ok"), snapshot.artifact)
    }
}
