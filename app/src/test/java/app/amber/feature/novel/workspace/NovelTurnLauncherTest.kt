package app.amber.feature.novel.workspace

import app.amber.ai.provider.Model
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentRunHandle
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.AgentRunner
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.settings.Settings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelTurnLauncherTest {

    @Test
    fun `terminal first waits for handler settle without cancelling the run`() = runTest {
        val runner = ControlledRunner()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val payloads = NovelTurnPayloads()
        val launcher = NovelTurnLauncher(runner, payloads, scope)
        val handle = launcher.launch(request(), NovelWorkspaceRuntime(NoopKernel))
        val payload = checkNotNull(payloads.resolve(handle.runId.value))

        val terminal = async {
            handle.events.first { it is NovelWorkspaceRuntime.TurnEvent.Completed }
        }
        runCurrent()
        payload.events.send(NovelWorkspaceRuntime.TurnEvent.Completed("完成", proposal = null))
        runCurrent()

        assertFalse(terminal.isCompleted)
        assertTrue(runner.cancelled.isEmpty())

        payload.completion.complete(Unit)
        runCurrent()
        assertEquals(
            NovelWorkspaceRuntime.TurnEvent.Completed("完成", proposal = null),
            terminal.await(),
        )
        assertTrue(runner.cancelled.isEmpty())
        scope.cancel()
    }

    @Test
    fun `terminal runner outcome before handler is delivered as a failed turn`() = runTest {
        val runner = ControlledRunner()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val payloads = NovelTurnPayloads()
        val launcher = NovelTurnLauncher(runner, payloads, scope)
        val handle = launcher.launch(request(), NovelWorkspaceRuntime(NoopKernel))
        val payload = checkNotNull(payloads.resolve(handle.runId.value))

        val terminal = async { handle.events.first() }
        runCurrent()
        runner.finish(handle.runId, RunStatus.FAILED, IllegalStateException("launch gate rejected"))
        runCurrent()

        assertEquals(
            NovelWorkspaceRuntime.TurnEvent.Failed("launch gate rejected"),
            terminal.await(),
        )
        assertFalse(payload.claimHandler())
        assertNull(payloads.resolve(handle.runId.value))
        assertTrue(runner.cancelled.isEmpty())
        scope.cancel()
    }

    private fun request() = NovelWorkspaceRuntime.TurnRequest(
        projectDirectory = File("."),
        branchId = "B-1",
        branchSlug = "主线",
        userText = "继续",
        systemPrompt = "",
        settings = Settings(),
        model = Model(),
    )

    private object NoopKernel : RunKernel {
        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = emptyFlow()
    }

    private class ControlledRunner : AgentRunner {
        private val snapshots = mutableMapOf<AgentRunId, MutableStateFlow<AgentRunSnapshot>>()
        val cancelled = mutableListOf<AgentRunId>()

        override fun <I : AgentInput> launch(
            descriptorId: AgentDescriptorId,
            input: I,
            requestedRunId: AgentRunId?,
        ): Result<AgentRunHandle> {
            val runId = requestedRunId ?: AgentRunId.new()
            snapshots[runId] = MutableStateFlow(
                AgentRunSnapshot(
                    runId = runId,
                    parentRunId = null,
                    descriptorId = descriptorId,
                    status = RunStatus.RUNNING,
                    startedAt = 0,
                    finishedAt = null,
                ),
            )
            return Result.success(AgentRunHandle(runId, descriptorId))
        }

        override fun observe(runId: AgentRunId): StateFlow<AgentRunSnapshot> = snapshots.getValue(runId)

        override fun cancel(runId: AgentRunId) {
            cancelled += runId
            finish(runId, RunStatus.CANCELLED)
        }

        override suspend fun listUnfinishedRuns(): List<AgentRunSnapshot> = snapshots.values
            .map { it.value }
            .filter { !it.status.isTerminal }

        fun finish(runId: AgentRunId, status: RunStatus, error: Throwable? = null) {
            val current = snapshots.getValue(runId).value
            snapshots.getValue(runId).value = current.copy(
                status = status,
                finishedAt = if (status.isTerminal) 1 else null,
                error = error,
            )
        }
    }
}
