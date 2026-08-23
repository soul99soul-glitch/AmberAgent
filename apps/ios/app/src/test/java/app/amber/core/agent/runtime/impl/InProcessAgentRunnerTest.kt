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
import app.amber.core.agent.runtime.AgentRunEvent
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunStatus
import app.amber.core.agent.runtime.TraceSpanRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private fun fakeRegistry(
        descriptor: AgentDescriptor = FakeAgent().descriptor,
        factory: () -> FakeAgent = { FakeAgent() },
    ) = InMemoryAgentRegistry().apply {
        register(
            descriptor = descriptor,
            inputClass = FakeInput::class,
            inputSerializer = FakeInput.serializer(),
            artifactSerializer = FakeArtifact.serializer(),
            factory = factory,
        )
    }

    private class RecordingEventStore(
        private val beforeTransition: suspend () -> Unit = {},
    ) : AgentEventStore {
        val runs = mutableListOf<AgentRunRecord>()
        val events = mutableListOf<AgentEventRecord>()
        private val currentRuns = mutableMapOf<String, AgentRunRecord>()

        override suspend fun startRun(run: AgentRunRecord): Boolean {
            if (currentRuns.containsKey(run.runId)) return false
            currentRuns[run.runId] = run
            runs += run
            return true
        }

        override suspend fun getRun(runId: AgentRunId): AgentRunRecord? = currentRuns[runId.value]

        override suspend fun transitionRun(
            runId: AgentRunId,
            expectedStatus: AgentRunStatus,
            status: AgentRunStatus,
            inputSnapshotRef: String?,
            detail: String?,
            at: Long,
        ): Boolean {
            beforeTransition()
            val current = currentRuns[runId.value] ?: return false
            if (current.status != expectedStatus || !expectedStatus.canTransitionTo(status)) return false
            val updated = current.copy(
                status = status,
                inputSnapshotRef = inputSnapshotRef,
                finishedAt = at.takeIf { status.isTerminal },
                interruptedReason = detail,
            )
            currentRuns[runId.value] = updated
            runs += updated
            return true
        }

        override suspend fun appendRunEvent(runId: AgentRunId, event: AgentRunEvent): Boolean =
            currentRuns.containsKey(runId.value)

        override suspend fun listRunEvents(runId: AgentRunId): List<AgentEventRecord> = events

        override suspend fun appendSpan(span: TraceSpanRecord) {}

        override fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot> = emptyFlow()

        override suspend fun listRecoverableRuns(descriptorIds: List<String>): List<AgentRunRecord> =
            currentRuns.values.filter { it.agentDescriptorId in descriptorIds && it.status.isRecoverable }
    }

    @Test
    fun `launch and complete writes running then completed`() = runBlocking {
        val store = RecordingEventStore()
        val runner = InProcessAgentRunner(fakeRegistry(), store)

        val result = runner.launch(AgentDescriptorId("fake"), FakeInput("hello"))
        assertTrue("launch should succeed", result.isSuccess)
        val handle = result.getOrThrow()
        assertNotNull(handle.runId)
        val snapshot = runner.observe(handle.runId).value
        assertEquals(handle.runId, snapshot.runId)
        assertTrue(snapshot.status in setOf(AgentRunStatus.RUNNING, AgentRunStatus.COMPLETED))

        // Wait for completion (handler is synchronous in fake)
        repeat(20) {
            if (store.runs.size >= 2) return@repeat
            delay(50)
        }

        assertTrue("expected at least 2 run records", store.runs.size >= 2)
        assertEquals(AgentRunStatus.RUNNING, store.runs[0].status)
        if (store.runs.last().status != AgentRunStatus.COMPLETED) {
            error("expected last status to be COMPLETED, got '${store.runs.last().status}' reason='${store.runs.last().interruptedReason}'")
        }
        assertEquals("descriptor id matches", "fake", store.runs[0].agentDescriptorId)
    }

    @Test
    fun `terminal snapshot is published only after durable transition`() = runBlocking {
        val transitionEntered = CompletableDeferred<Unit>()
        val releaseTransition = CompletableDeferred<Unit>()
        val store = RecordingEventStore {
            transitionEntered.complete(Unit)
            releaseTransition.await()
        }
        val runner = InProcessAgentRunner(fakeRegistry(), store)
        val handle = runner.launch(AgentDescriptorId("fake"), FakeInput("hello")).getOrThrow()

        transitionEntered.await()
        assertEquals(AgentRunStatus.RUNNING, runner.observe(handle.runId).value.status)

        releaseTransition.complete(Unit)
        repeat(20) {
            if (runner.observe(handle.runId).value.status == AgentRunStatus.COMPLETED) return@repeat
            delay(25)
        }
        assertEquals(AgentRunStatus.COMPLETED, runner.observe(handle.runId).value.status)
    }

    @Test
    fun `cancel after completion does not overwrite terminal snapshot`() = runBlocking {
        val store = RecordingEventStore()
        val runner = InProcessAgentRunner(fakeRegistry(), store)
        val handle = runner.launch(AgentDescriptorId("fake"), FakeInput("hello")).getOrThrow()
        repeat(20) {
            if (runner.observe(handle.runId).value.status == AgentRunStatus.COMPLETED) return@repeat
            delay(25)
        }

        runner.cancel(handle.runId)
        delay(50)

        assertEquals(AgentRunStatus.COMPLETED, runner.observe(handle.runId).value.status)
        assertEquals(AgentRunStatus.COMPLETED, store.runs.last().status)
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
    fun `launch and fail writes failed status`() = runBlocking {
        val store = RecordingEventStore()
        val handlerCallCount = AtomicInteger(0)
        val registry = fakeRegistry(
            descriptor = AgentDescriptor(
                id = AgentDescriptorId("failing"),
                version = "1.0",
                displayName = "Failing",
                capabilities = emptySet(),
            ),
            factory = {
                FakeAgent {
                    handlerCallCount.incrementAndGet()
                    throw IllegalStateException("intentional failure")
                }
            },
        )
        val runner = InProcessAgentRunner(registry, store)

        runner.launch(AgentDescriptorId("failing"), FakeInput("x"))

        repeat(20) {
            if (store.runs.size >= 2) return@repeat
            delay(50)
        }

        assertEquals(1, handlerCallCount.get())
        assertTrue("expected at least 2 run records", store.runs.size >= 2)
        assertEquals(AgentRunStatus.RUNNING, store.runs[0].status)
        assertEquals(AgentRunStatus.FAILED, store.runs.last().status)
        assertTrue(store.runs.last().interruptedReason?.contains("intentional failure") == true)
    }

    @Test
    fun `cancel settles persisted run as cancelled`() = runBlocking {
        val store = RecordingEventStore()
        val registry = fakeRegistry(factory = { FakeAgent { delay(Long.MAX_VALUE) } })
        val runner = InProcessAgentRunner(registry, store)
        val handle = runner.launch(AgentDescriptorId("fake"), FakeInput("x")).getOrThrow()

        repeat(20) {
            if (store.runs.isNotEmpty()) return@repeat
            delay(25)
        }
        runner.cancel(handle.runId)
        repeat(20) {
            if (store.runs.lastOrNull()?.status == AgentRunStatus.CANCELLED) return@repeat
            delay(25)
        }

        assertEquals(AgentRunStatus.CANCELLED, store.runs.last().status)
        assertEquals("cancelled", store.runs.last().interruptedReason)
        assertNotNull(store.runs.last().finishedAt)
    }

    // A run must own a durable parent before provider/tool work begins.
    @Test
    fun `durable start failure is published and does not execute handler`() = runBlocking {
        val store = ThrowingEventStore()
        val handlerCallCount = AtomicInteger(0)
        val registry = fakeRegistry(
            factory = { FakeAgent { handlerCallCount.incrementAndGet() } },
        )
        val ledgerErrors = java.util.concurrent.CopyOnWriteArrayList<Pair<AgentRunId, Throwable>>()
        val runner = InProcessAgentRunner(
            registry,
            store,
            onLedgerError = { runId, error -> ledgerErrors.add(runId to error) },
        )

        val handle = runner.launch(AgentDescriptorId("fake"), FakeInput("x")).getOrThrow()

        repeat(20) {
            if (ledgerErrors.isNotEmpty()) return@repeat
            delay(50)
        }

        assertEquals("handler must not execute without a durable run", 0, handlerCallCount.get())
        assertTrue("ledger failure must be published, not swallowed", ledgerErrors.isNotEmpty())
        assertEquals(handle.runId, ledgerErrors.first().first)
        assertTrue(ledgerErrors.first().second.message?.contains("db broken") == true)
        assertEquals(AgentRunStatus.FAILED, runner.observe(handle.runId).value.status)
    }

    /// 账本每次写都抛错（模拟 Room 写失败），其余方法与 RecordingEventStore 相同。
    private class ThrowingEventStore : AgentEventStore {
        val events = mutableListOf<AgentEventRecord>()
        override suspend fun startRun(run: AgentRunRecord): Boolean {
            throw IllegalStateException("db broken")
        }

        override suspend fun getRun(runId: AgentRunId): AgentRunRecord? = null

        override suspend fun transitionRun(
            runId: AgentRunId,
            expectedStatus: AgentRunStatus,
            status: AgentRunStatus,
            inputSnapshotRef: String?,
            detail: String?,
            at: Long,
        ): Boolean = throw IllegalStateException("db broken")

        override suspend fun appendRunEvent(runId: AgentRunId, event: AgentRunEvent): Boolean = true

        override suspend fun listRunEvents(runId: AgentRunId): List<AgentEventRecord> = events

        override suspend fun appendSpan(span: TraceSpanRecord) {}

        override fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot> = emptyFlow()

        override suspend fun listRecoverableRuns(descriptorIds: List<String>): List<AgentRunRecord> = emptyList()
    }
}
