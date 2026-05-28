package me.rerere.rikkahub.ui.pages.chat

import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentRunHandle
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunStatus
import app.amber.core.agent.runtime.AgentRunner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests the ChatVM kernel observe flow composition without instantiating
 * the full ChatVM (which has 11+ DI dependencies). Verifies that
 * activeKernelRunId → runner.observe(runId).map { status } correctly
 * propagates run lifecycle from the AgentRunner to UI-observable state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KernelRunObserveTest {

    private class FakeRunner : AgentRunner {
        private val snapshots = ConcurrentHashMap<AgentRunId, MutableStateFlow<AgentRunSnapshot>>()

        fun publish(runId: AgentRunId, status: AgentRunStatus) {
            snapshots.getOrPut(runId) {
                MutableStateFlow(
                    AgentRunSnapshot(
                        runId = runId,
                        parentRunId = null,
                        descriptorId = AgentDescriptorId("chat_turn"),
                        status = status,
                        startedAt = 0,
                        finishedAt = null,
                    )
                )
            }.value = AgentRunSnapshot(
                runId = runId,
                parentRunId = null,
                descriptorId = AgentDescriptorId("chat_turn"),
                status = status,
                startedAt = 0,
                finishedAt = if (status != AgentRunStatus.RUNNING) 1L else null,
            )
        }

        override fun <I : AgentInput> launch(descriptorId: AgentDescriptorId, input: I): Result<AgentRunHandle> =
            Result.failure(NotImplementedError())

        override fun observe(runId: AgentRunId): StateFlow<AgentRunSnapshot> =
            snapshots.getOrPut(runId) {
                MutableStateFlow(
                    AgentRunSnapshot(
                        runId = runId,
                        parentRunId = null,
                        descriptorId = AgentDescriptorId("chat_turn"),
                        status = AgentRunStatus.RUNNING,
                        startedAt = 0,
                        finishedAt = null,
                    )
                )
            }

        override fun cancel(runId: AgentRunId) {}

        override suspend fun listUnfinishedRuns(): List<AgentRunSnapshot> = emptyList()
    }

    @Test
    fun `null runId yields null status`() = runTest {
        val activeRunId = MutableStateFlow<AgentRunId?>(null)
        val runner = FakeRunner()

        val statusFlow = activeRunId
            .flatMapLatest { id ->
                if (id == null) flowOf(null)
                else runner.observe(id).map { it.status }
            }
            .stateIn(backgroundScope, SharingStarted.Eagerly, null)

        assertNull(statusFlow.value)
    }

    @Test
    fun `runId set propagates runner status`() = runTest {
        val activeRunId = MutableStateFlow<AgentRunId?>(null)
        val runner = FakeRunner()

        val statusFlow = activeRunId
            .flatMapLatest { id ->
                if (id == null) flowOf(null)
                else runner.observe(id).map { it.status }
            }
            .stateIn(backgroundScope, SharingStarted.Eagerly, null)

        val runId = AgentRunId("test-run-1")
        runner.publish(runId, AgentRunStatus.RUNNING)
        activeRunId.value = runId

        runCurrent()
        assertEquals(AgentRunStatus.RUNNING, statusFlow.value)

        runner.publish(runId, AgentRunStatus.COMPLETED)
        runCurrent()
        assertEquals(AgentRunStatus.COMPLETED, statusFlow.value)
    }

    @Test
    fun `runId change switches observation to new run via flatMapLatest`() = runTest {
        val activeRunId = MutableStateFlow<AgentRunId?>(null)
        val runner = FakeRunner()

        val statusFlow = activeRunId
            .flatMapLatest { id ->
                if (id == null) flowOf(null)
                else runner.observe(id).map { it.status }
            }
            .stateIn(backgroundScope, SharingStarted.Eagerly, null)

        val run1 = AgentRunId("run-1")
        val run2 = AgentRunId("run-2")
        runner.publish(run1, AgentRunStatus.RUNNING)
        runner.publish(run2, AgentRunStatus.FAILED)

        activeRunId.value = run1
        runCurrent()
        assertEquals(AgentRunStatus.RUNNING, statusFlow.value)

        activeRunId.value = run2
        runCurrent()
        assertEquals(AgentRunStatus.FAILED, statusFlow.value)

        // Updates to old run shouldn't affect current view
        runner.publish(run1, AgentRunStatus.CANCELLED)
        runCurrent()
        assertEquals(AgentRunStatus.FAILED, statusFlow.value)
    }

    @Test
    fun `runId cleared yields null again`() = runTest {
        val activeRunId = MutableStateFlow<AgentRunId?>(null)
        val runner = FakeRunner()

        val statusFlow = activeRunId
            .flatMapLatest { id ->
                if (id == null) flowOf(null)
                else runner.observe(id).map { it.status }
            }
            .stateIn(backgroundScope, SharingStarted.Eagerly, null)

        val runId = AgentRunId("temp-run")
        runner.publish(runId, AgentRunStatus.COMPLETED)
        activeRunId.value = runId
        runCurrent()
        assertEquals(AgentRunStatus.COMPLETED, statusFlow.value)

        activeRunId.value = null
        runCurrent()
        assertNull(statusFlow.value)
    }
}
