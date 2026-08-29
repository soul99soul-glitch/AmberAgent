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

    /** Store whose durable run-row create always fails (disk error shape). */
    private class FailingAppendStore : AgentEventStore by RecordingEventStore() {
        override suspend fun appendRun(run: AgentRunRecord): Boolean {
            throw IllegalStateException("durable create failed")
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
    fun `second launch while the run is actively RUNNING does not run the handler`() = runTest {
        val store = RecordingEventStore()
        val runId = AgentRunId("gate-live-run")
        val firstMayReturn = CompletableDeferred<Unit>()
        val runnerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val harness = runnerWithAgent(store, runnerScope) { invocation ->
            if (invocation == 1) firstMayReturn.await()
            FakeArtifact("run-$invocation")
        }

        // Activation 1 blocks in its handler; the row is RUNNING.
        harness.runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(RunStatus.RUNNING, store.runs.getValue(runId.value).status)

        // A second launch against the live run is rejected by the resume CAS
        // (RUNNING is not a pause): no handler run, no second activation —
        // the row and the snapshot stay RUNNING under the live activation.
        harness.runner.launch(descriptor.id, FakeInput("v"), requestedRunId = runId).getOrThrow()
        advanceUntilIdle()
        assertEquals(1, harness.invocations.get())
        assertEquals(RunStatus.RUNNING, store.runs.getValue(runId.value).status)
        assertEquals(RunStatus.RUNNING, harness.runner.observe(runId).value.status)

        // Documented trade-off: the rejected activation still bumped the
        // activation epoch, so the live activation stands down at its
        // terminal path and the row stays RUNNING until cold-start recovery
        // settles it. Production never relaunches a RUNNING row (ChatService
        // gates on isGenerating; crash-left RUNNING rows are marked
        // INTERRUPTED by recovery) — pin the no-double-execution behavior.
        firstMayReturn.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, harness.invocations.get())
        assertEquals(RunStatus.RUNNING, store.runs.getValue(runId.value).status)
    }
}
