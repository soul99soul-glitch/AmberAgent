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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class InProcessAgentRunnerTest {

    @Serializable
    private data class FakeInput(val value: String) : AgentInput

    @Serializable
    private data class FakeArtifact(val echoed: String) : AgentArtifact

    private class FakeAgent(
        private val onHandle: suspend () -> Unit = {},
    ) : Agent<FakeInput, FakeArtifact> {
        override val descriptor = AgentDescriptor(
            id = AgentDescriptorId("fake"),
            version = "1.0",
            displayName = "Fake",
            capabilities = setOf(AgentCapability.CHAT_TURN),
        )
        override val handler = AgentHandler<FakeInput, FakeArtifact> { input, _ ->
            onHandle()
            FakeArtifact(echoed = input.value)
        }
    }

    /** In-memory CAS store mirroring the Room store's protocol semantics. */
    private class RecordingEventStore : AgentEventStore {
        val runs = mutableMapOf<String, AgentRunRecord>()
        val events = mutableListOf<AgentEventRecord>()
        val interruptions = mutableListOf<Pair<AgentRunId, String>>()

        override suspend fun appendRun(run: AgentRunRecord) {
            runs.putIfAbsent(run.runId, run)
        }

        override suspend fun appendEvent(event: AgentEventRecord) {
            if (events.none { it.runId == event.runId && it.seq == event.seq }) {
                events += event
            }
        }

        override suspend fun appendEventAllocatingSeq(event: AgentEventRecord): AgentEventRecord {
            val next = (events.filter { it.runId == event.runId }.maxOfOrNull { it.seq } ?: 0L) + 1
            val stored = event.copy(seq = next)
            events += stored
            return stored
        }

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

        override suspend fun listEvents(runId: AgentRunId): List<AgentEventRecord> =
            events.filter { it.runId == runId.value }.sortedBy { it.seq }

        override suspend fun deleteEventsByType(runId: AgentRunId, type: String) {
            events.removeAll { it.runId == runId.value && it.type == type }
        }

        override suspend fun listUnfinishedRuns(): List<AgentRunRecord> =
            runs.values.filter { !it.status.isTerminal }

        override suspend fun markInterrupted(runId: AgentRunId, reason: String) {
            interruptions += runId to reason
        }
    }

    private fun registeredRunner(store: RecordingEventStore, onHandle: suspend () -> Unit = {}): InProcessAgentRunner {
        val registry = InMemoryAgentRegistry().apply {
            register(
                descriptor = FakeAgent().descriptor,
                inputClass = FakeInput::class,
                inputSerializer = FakeInput.serializer(),
                artifactSerializer = FakeArtifact.serializer(),
                factory = { FakeAgent(onHandle) },
            )
        }
        return InProcessAgentRunner(registry, store)
    }

    @Test
    fun `launch and complete transitions running to completed`() = runBlocking {
        val store = RecordingEventStore()
        val runner = registeredRunner(store)

        val result = runner.launch(AgentDescriptorId("fake"), FakeInput("hello"))
        assertTrue("launch should succeed", result.isSuccess)
        val handle = result.getOrThrow()
        assertNotNull(handle.runId)

        repeat(40) {
            if (store.runs[handle.runId.value]?.status == RunStatus.COMPLETED) return@repeat
            delay(50)
        }

        val record = store.runs.getValue(handle.runId.value)
        assertEquals(RunStatus.COMPLETED, record.status)
        assertEquals("descriptor id matches", "fake", record.agentDescriptorId)
        assertTrue("completed run has finishedAt", record.finishedAt != null)
    }

    @Test
    fun `launch with unknown descriptor returns failure`() = runBlocking {
        val store = RecordingEventStore()
        val registry = InMemoryAgentRegistry()
        val runner = InProcessAgentRunner(registry, store)

        val result = runner.launch(AgentDescriptorId("nonexistent"), FakeInput("x"))
        assertTrue("should fail for unknown descriptor", result.isFailure)
        assertTrue(store.runs.isEmpty())
    }

    @Test
    fun `launch and fail transitions to failed with reason`() = runBlocking {
        val store = RecordingEventStore()
        val handlerCallCount = AtomicInteger(0)
        val registry = InMemoryAgentRegistry().apply {
            register(
                descriptor = AgentDescriptor(
                    id = AgentDescriptorId("failing"),
                    version = "1.0",
                    displayName = "Failing",
                    capabilities = emptySet(),
                ),
                inputClass = FakeInput::class,
                inputSerializer = FakeInput.serializer(),
                artifactSerializer = FakeArtifact.serializer(),
                factory = {
                    FakeAgent {
                        handlerCallCount.incrementAndGet()
                        throw IllegalStateException("intentional failure")
                    }
                },
            )
        }
        val runner = InProcessAgentRunner(registry, store)

        val handle = runner.launch(AgentDescriptorId("failing"), FakeInput("x")).getOrThrow()

        repeat(40) {
            if (store.runs[handle.runId.value]?.status == RunStatus.FAILED) return@repeat
            delay(50)
        }

        assertEquals(1, handlerCallCount.get())
        val record = store.runs.getValue(handle.runId.value)
        assertEquals(RunStatus.FAILED, record.status)
        assertTrue(record.interruptedReason?.contains("intentional failure") == true)
    }

    @Test
    fun `observe returns snapshot for existing run`() = runBlocking {
        val store = RecordingEventStore()
        val runner = registeredRunner(store)

        val handle = runner.launch(AgentDescriptorId("fake"), FakeInput("y")).getOrThrow()
        val snapshot = runner.observe(handle.runId).value

        assertEquals(handle.runId, snapshot.runId)
        // Status should be RUNNING or COMPLETED depending on timing
        assertTrue(snapshot.status in setOf(RunStatus.RUNNING, RunStatus.COMPLETED))
    }

    @Test
    fun `completed run snapshot carries the handler artifact`() = runBlocking {
        val store = RecordingEventStore()
        val runner = registeredRunner(store)

        val handle = runner.launch(AgentDescriptorId("fake"), FakeInput("hello")).getOrThrow()
        repeat(40) {
            if (runner.observe(handle.runId).value.status == RunStatus.COMPLETED) return@repeat
            delay(50)
        }

        val snapshot = runner.observe(handle.runId).value
        assertEquals(RunStatus.COMPLETED, snapshot.status)
        assertEquals(FakeArtifact(echoed = "hello"), snapshot.artifact)
    }

    @Test
    fun `failed run snapshot has no artifact`() = runBlocking {
        val store = RecordingEventStore()
        val registry = InMemoryAgentRegistry().apply {
            register(
                descriptor = AgentDescriptor(
                    id = AgentDescriptorId("failing"),
                    version = "1.0",
                    displayName = "Failing",
                    capabilities = emptySet(),
                ),
                inputClass = FakeInput::class,
                inputSerializer = FakeInput.serializer(),
                artifactSerializer = FakeArtifact.serializer(),
                factory = { FakeAgent { throw IllegalStateException("boom") } },
            )
        }
        val runner = InProcessAgentRunner(registry, store)

        val handle = runner.launch(AgentDescriptorId("failing"), FakeInput("x")).getOrThrow()
        repeat(40) {
            if (runner.observe(handle.runId).value.status == RunStatus.FAILED) return@repeat
            delay(50)
        }

        assertNull(runner.observe(handle.runId).value.artifact)
    }

    @Test
    fun `completed runs are not reported as unfinished after cleanup`() = runBlocking {
        val store = RecordingEventStore()
        val runner = registeredRunner(store)

        val handle = runner.launch(AgentDescriptorId("fake"), FakeInput("done")).getOrThrow()
        repeat(40) {
            if (store.runs[handle.runId.value]?.status == RunStatus.COMPLETED) return@repeat
            delay(50)
        }
        // Let the runner's finally-block trim snapshots before asserting.
        repeat(20) {
            if (runner.listUnfinishedRuns().isEmpty()) return@repeat
            delay(50)
        }

        assertEquals(emptyList<AgentRunSnapshot>(), runner.listUnfinishedRuns())
    }

    @Test
    fun `relaunch under the same runId reuses the snapshot flow and resets to running`() = runBlocking {
        val store = RecordingEventStore()
        var gate = CompletableDeferred<Unit>()
        val runner = registeredRunner(store) { gate.await() }
        val runId = AgentRunId("resume-run")

        runner.launch(AgentDescriptorId("fake"), FakeInput("a"), requestedRunId = runId).getOrThrow()
        val flow = runner.observe(runId)
        assertEquals(RunStatus.RUNNING, flow.value.status)
        gate.complete(Unit)
        repeat(40) {
            if (flow.value.status == RunStatus.COMPLETED) return@repeat
            delay(50)
        }
        assertEquals(RunStatus.COMPLETED, flow.value.status)

        // Resume under the SAME runId (paused-run resume shape): the StateFlow
        // instance must be reused — flatMapLatest observers keep their
        // subscription — and reset to RUNNING for the new attempt.
        gate = CompletableDeferred()
        runner.launch(AgentDescriptorId("fake"), FakeInput("b"), requestedRunId = runId).getOrThrow()

        assertSame(flow, runner.observe(runId))
        assertEquals(RunStatus.RUNNING, flow.value.status)
        assertNull(flow.value.finishedAt)

        gate.complete(Unit)
        repeat(40) {
            if (flow.value.status == RunStatus.COMPLETED) return@repeat
            delay(50)
        }
        assertEquals(RunStatus.COMPLETED, flow.value.status)
    }

    @Test
    fun `handler-parked run survives handler return and the snapshot realigns to the pause`() = runBlocking {
        val store = RecordingEventStore()
        val runId = AgentRunId("parked-run")
        val runner = registeredRunner(store) {
            // Simulate the turn hooks parking the run for tool approval while
            // the generation flow is still returning (onTerminal already
            // persisted WAITING_USER).
            store.transitionRun(runId, RunStatus.LIVE_STATES, RunStatus.WAITING_USER)
        }

        runner.launch(AgentDescriptorId("fake"), FakeInput("park"), requestedRunId = runId).getOrThrow()

        repeat(40) {
            if (store.runs[runId.value]?.status == RunStatus.WAITING_USER &&
                runner.observe(runId).value.status == RunStatus.WAITING_USER
            ) {
                return@repeat
            }
            delay(50)
        }

        // The runner's post-handler COMPLETED CAS (expected CREATED/RUNNING)
        // was rejected by the parked row, and the optimistic in-memory
        // COMPLETED was realigned to the persisted pause.
        assertEquals(RunStatus.WAITING_USER, store.runs.getValue(runId.value).status)
        val snapshot = runner.observe(runId).value
        assertEquals(RunStatus.WAITING_USER, snapshot.status)
        assertNull(snapshot.finishedAt)
    }
}
