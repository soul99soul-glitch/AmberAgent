package app.amber.feature.subagent

import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.UIMessage
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.RunKernel
import app.amber.core.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Step 3-5: a dead sub-agent turn (cancel / interrupt / timeout / failure)
 * must classify its orphaned STARTED ledger effects — thread-graph turns
 * have no run_terminal row, so cold-start recovery never visits them.
 * Parked turns (APPROVAL_REQUIRED) are resumable and must NOT reconcile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SubAgentTurnAgentTest {

    private class FakeRunner(
        private val result: SubAgentResult? = null,
        private val failure: Throwable? = null,
        /** When set, run() signals start then suspends until really cancelled. */
        private val startedSignal: CompletableDeferred<Unit>? = null,
    ) : SubAgentRunner {
        override suspend fun run(
            settings: Settings,
            definition: SubAgentDefinition,
            task: SubAgentTaskSpec,
            tools: List<Tool>,
            liveText: MutableStateFlow<String>,
            liveParts: MutableStateFlow<List<app.amber.ai.ui.UIMessagePart>>,
            runId: String?,
            onTerminal: (suspend (app.amber.core.ai.GenerationTerminal) -> Unit)?,
            consumeSteerMessages: suspend () -> List<app.amber.ai.ui.UIMessage>,
            previousAnswer: String,
            events: app.amber.core.agent.runtime.AgentEventWriter?,
            parentPolicy: app.amber.feature.runtime.ExecutionPolicy,
            childPolicy: app.amber.feature.runtime.ExecutionPolicy?,
        ): SubAgentResult {
            if (startedSignal != null) {
                startedSignal.complete(Unit)
                awaitCancellation()
            }
            failure?.let { throw it }
            return result ?: SubAgentResult(status = SubAgentRunStatus.COMPLETED, summary = "done")
        }
    }

    private fun definition() = SubAgentDefinition(
        id = "researcher",
        name = "Researcher",
        description = "",
        systemPrompt = "",
        toolAllowlist = emptySet(),
    )

    private fun task() = SubAgentTaskSpec(
        objective = "调查",
        outputFormat = "",
        toolsAndSources = "",
        boundaries = "",
    )

    private fun input() = SubAgentTurnInput(
        threadId = "thread_1",
        parentConversationId = "conv_1",
        definitionId = "researcher",
        objective = "调查",
    )

    private fun registerPayload(
        payloads: SubAgentTurnPayloads,
        runId: String,
        kernelRunId: String?,
    ) {
        payloads.register(
            runId,
            SubAgentTurnPayloads.Payload(
                threadId = "thread_1",
                settings = Settings(),
                definition = definition(),
                task = task(),
                tools = emptyList(),
                liveText = MutableStateFlow(""),
                liveParts = MutableStateFlow(emptyList()),
                kernelRunId = kernelRunId,
                onTerminal = null,
                consumeSteerMessages = { emptyList() },
                previousAnswer = "",
            ),
        )
    }

    private fun runTurn(
        runner: SubAgentRunner,
        kernelRunId: String?,
        reconciled: MutableList<String>,
    ): SubAgentResult {
        val payloads = SubAgentTurnPayloads()
        registerPayload(payloads, "turn_run_1", kernelRunId)
        val agent = SubAgentTurnAgent(
            runner = runner,
            payloads = payloads,
            reconcileStartedEffects = { runId -> reconciled += runId },
        )
        val scope = LegacyRunScope(runId = AgentRunId("turn_run_1"))
        var result: SubAgentResult? = null
        var thrown: Throwable? = null
        runBlocking {
            try {
                result = agent.handler.handle(input(), scope)
            } catch (error: Throwable) {
                thrown = error
            }
        }
        if (thrown != null) {
            throw AssertionError("turn threw unexpectedly", thrown)
        }
        return result!!
    }

    @Test
    fun cancellationDuringTheRunStillReconcilesTheTurnRunId() {
        // Production semantics: InProcessAgentRunner.cancel() cancels the
        // handler coroutine itself, so the reconcile (Room suspend queries)
        // must run under NonCancellable — a synthetic throw from an
        // un-cancelled runner would not catch its absence.
        val reconciled = mutableListOf<String>()
        val runnerStarted = CompletableDeferred<Unit>()
        val payloads = SubAgentTurnPayloads()
        registerPayload(payloads, "turn_run_1", kernelRunId = "turn_run_1")
        val agent = SubAgentTurnAgent(
            runner = FakeRunner(startedSignal = runnerStarted),
            payloads = payloads,
            reconcileStartedEffects = { runId ->
                // The suspension is what rethrows CancellationException in an
                // already-cancelled coroutine without the NonCancellable wrap.
                yield()
                reconciled += runId
            },
        )
        val scope = LegacyRunScope(runId = AgentRunId("turn_run_1"))

        var thrown: Throwable? = null
        runBlocking {
            val job = launch {
                try {
                    agent.handler.handle(input(), scope)
                } catch (error: Throwable) {
                    thrown = error
                }
            }
            runnerStarted.await()
            job.cancelAndJoin()
        }

        assertTrue(thrown is CancellationException)
        assertEquals(listOf("turn_run_1"), reconciled)
    }

    @Test
    fun failedResultTriggersReconcile() {
        val reconciled = mutableListOf<String>()
        val result = runTurn(
            runner = FakeRunner(result = SubAgentResult(status = SubAgentRunStatus.FAILED, error = "boom")),
            kernelRunId = "turn_run_1",
            reconciled = reconciled,
        )
        assertEquals(SubAgentRunStatus.FAILED, result.status)
        assertEquals(listOf("turn_run_1"), reconciled)
    }

    @Test
    fun completedResultSkipsReconcile() {
        val reconciled = mutableListOf<String>()
        runTurn(
            runner = FakeRunner(),
            kernelRunId = "turn_run_1",
            reconciled = reconciled,
        )
        assertTrue(reconciled.isEmpty())
    }

    @Test
    fun approvalRequiredParkSkipsReconcile() {
        val reconciled = mutableListOf<String>()
        runTurn(
            runner = FakeRunner(result = SubAgentResult(status = SubAgentRunStatus.APPROVAL_REQUIRED, summary = "需要审批")),
            kernelRunId = "turn_run_1",
            reconciled = reconciled,
        )
        assertTrue(reconciled.isEmpty())
    }

    @Test
    fun nonDurableTurnSkipsReconcileEvenOnFailure() {
        val reconciled = mutableListOf<String>()
        runTurn(
            runner = FakeRunner(result = SubAgentResult(status = SubAgentRunStatus.FAILED, error = "boom")),
            kernelRunId = null, // thread graph off → no ledger effects exist
            reconciled = reconciled,
        )
        assertTrue(reconciled.isEmpty())
    }

    /**
     * P1-7 payload wiring: the turn agent hands BOTH payload policies to the
     * real [GenerationSubAgentRunner], and a child that declares a WIDER root
     * than the parent still ends up on parent.narrow(child) — never wider
     * than the parent's sandbox.
     */
    @Test
    fun payloadPoliciesReachTheRunnerAndChildCannotWidenTheParent() {
        val kernel = SessionCapturingKernel()
        val payloads = SubAgentTurnPayloads()
        val parentPolicy = app.amber.feature.runtime.ExecutionPolicy(
            allowedPathRoots = listOf("/workspace"),
            allowShell = false,
        )
        // The child declares the filesystem root "/" — wider than the parent's
        // /workspace — plus a domain grant the parent does not restrict.
        val childPolicy = app.amber.feature.runtime.ExecutionPolicy(
            allowedPathRoots = listOf("/"),
            allowedDomains = listOf("example.com"),
        )
        payloads.register(
            "turn_run_1",
            SubAgentTurnPayloads.Payload(
                threadId = "thread_1",
                settings = modelSettings(),
                definition = definition(),
                task = task(),
                tools = emptyList(),
                liveText = MutableStateFlow(""),
                liveParts = MutableStateFlow(emptyList()),
                kernelRunId = null,
                onTerminal = null,
                consumeSteerMessages = { emptyList() },
                previousAnswer = "",
                parentPolicy = parentPolicy,
                executionPolicy = childPolicy,
            ),
        )
        val agent = SubAgentTurnAgent(runner = GenerationSubAgentRunner(kernel), payloads = payloads)

        var result: SubAgentResult? = null
        runBlocking {
            result = agent.handler.handle(input(), LegacyRunScope(runId = AgentRunId("turn_run_1")))
        }

        assertEquals(SubAgentRunStatus.COMPLETED, result!!.status)
        // Narrow result: "/" is not under /workspace → dropped (deny-all
        // paths); parent's allowShell=false survives the child's implicit
        // true; the domain grant passes (parent dimension unrestricted).
        val expected = app.amber.feature.runtime.ExecutionPolicy(
            allowedPathRoots = emptyList(),
            allowedDomains = listOf("example.com"),
            allowShell = false,
        )
        assertEquals(expected, parentPolicy.narrow(childPolicy))
        // The report-tool reminder may run the kernel twice; every child
        // session must run on exactly the narrowed policy — the wider child
        // root never survives.
        assertTrue(kernel.sessions.isNotEmpty())
        assertTrue(kernel.sessions.all { it.executionPolicy == expected })
    }

    private fun modelSettings(): Settings {
        val model = Model(modelId = "test-model", displayName = "Test Model")
        return Settings(
            chatModelId = model.id,
            providers = listOf(
                ProviderSetting.OpenAI(
                    name = "Test Provider",
                    apiKey = "test-key",
                    baseUrl = "https://example.test/v1",
                    models = listOf(model),
                )
            ),
        )
    }

    /** Records every generation session the runner asks the kernel to run. */
    private inner class SessionCapturingKernel : RunKernel {
        val sessions = mutableListOf<GenerationRunSession>()

        override fun run(session: GenerationRunSession): Flow<GenerationChunk> = flow {
            sessions += session
            emit(GenerationChunk.Messages(session.messages + UIMessage.assistant("done")))
        }
    }
}
