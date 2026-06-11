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
import app.amber.core.agent.runtime.AgentRunStatus
import app.amber.core.agent.runtime.TraceSpanRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    private class RecordingEventStore : AgentEventStore {
        val runs = mutableListOf<AgentRunRecord>()
        val events = mutableListOf<AgentEventRecord>()
        val interruptions = mutableListOf<Pair<AgentRunId, String>>()

        override suspend fun appendRun(run: AgentRunRecord) {
            runs += run
        }

        override suspend fun appendEvent(event: AgentEventRecord) {
            events += event
        }

        override suspend fun appendSpan(span: TraceSpanRecord) {}

        override fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot> = emptyFlow()

        override suspend fun listUnfinishedRuns(): List<AgentRunRecord> =
            runs.filter { it.status == "running" }

        override suspend fun markInterrupted(runId: AgentRunId, reason: String) {
            interruptions += runId to reason
        }
    }

    @Test
    fun `launch and complete writes running then completed`() = runBlocking {
        val store = RecordingEventStore()
        val registry = InMemoryAgentRegistry().apply {
            register(
                descriptor = FakeAgent().descriptor,
                inputClass = FakeInput::class,
                inputSerializer = FakeInput.serializer(),
                artifactSerializer = FakeArtifact.serializer(),
                factory = { FakeAgent() },
            )
        }
        val runner = InProcessAgentRunner(registry, store)

        val result = runner.launch(AgentDescriptorId("fake"), FakeInput("hello"))
        assertTrue("launch should succeed", result.isSuccess)
        val handle = result.getOrThrow()
        assertNotNull(handle.runId)

        // Wait for completion (handler is synchronous in fake)
        repeat(20) {
            if (store.runs.size >= 2) return@repeat
            delay(50)
        }

        assertTrue("expected at least 2 run records", store.runs.size >= 2)
        assertEquals("running", store.runs[0].status)
        if (store.runs.last().status != "completed") {
            error("expected last status to be 'completed', got '${store.runs.last().status}' reason='${store.runs.last().interruptedReason}'")
        }
        assertEquals("descriptor id matches", "fake", store.runs[0].agentDescriptorId)
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

        runner.launch(AgentDescriptorId("failing"), FakeInput("x"))

        repeat(20) {
            if (store.runs.size >= 2) return@repeat
            delay(50)
        }

        assertEquals(1, handlerCallCount.get())
        assertTrue("expected at least 2 run records", store.runs.size >= 2)
        assertEquals("running", store.runs[0].status)
        assertEquals("failed", store.runs.last().status)
        assertTrue(store.runs.last().interruptedReason?.contains("intentional failure") == true)
    }

    @Test
    fun `observe returns snapshot for existing run`() = runBlocking {
        val store = RecordingEventStore()
        val registry = InMemoryAgentRegistry().apply {
            register(
                descriptor = FakeAgent().descriptor,
                inputClass = FakeInput::class,
                inputSerializer = FakeInput.serializer(),
                artifactSerializer = FakeArtifact.serializer(),
                factory = { FakeAgent() },
            )
        }
        val runner = InProcessAgentRunner(registry, store)

        val handle = runner.launch(AgentDescriptorId("fake"), FakeInput("y")).getOrThrow()
        val snapshot = runner.observe(handle.runId).value

        assertEquals(handle.runId, snapshot.runId)
        // Status should be RUNNING or COMPLETED depending on timing
        assertTrue(snapshot.status in setOf(AgentRunStatus.RUNNING, AgentRunStatus.COMPLETED))
    }

    @Test
    fun `completed runs are not reported as unfinished after cleanup`() = runBlocking {
        val store = RecordingEventStore()
        val registry = InMemoryAgentRegistry().apply {
            register(
                descriptor = FakeAgent().descriptor,
                inputClass = FakeInput::class,
                inputSerializer = FakeInput.serializer(),
                artifactSerializer = FakeArtifact.serializer(),
                factory = { FakeAgent() },
            )
        }
        val runner = InProcessAgentRunner(registry, store)

        runner.launch(AgentDescriptorId("fake"), FakeInput("done")).getOrThrow()
        repeat(20) {
            if (store.runs.lastOrNull()?.status == "completed") return@repeat
            delay(50)
        }

        assertEquals(emptyList<AgentRunSnapshot>(), runner.listUnfinishedRuns())
    }
}
