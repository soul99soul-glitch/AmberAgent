package app.amber.feature.runtime

import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.InMemoryAgentEventStore
import app.amber.core.agent.runtime.RunStatus
import app.amber.feature.tools.ToolEffectClass
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Step 3-4 dual-write convergence: every run_terminal settle performed by
 * cold-start recovery is mirrored into the protocol run row, so the two
 * stores cannot diverge. agent_run CREATED/RUNNING rows are deliberately
 * left to ChatEventProjector.replayUnfinished (which also projects the last
 * stream checkpoint) — recovery only settles pause-state rows itself.
 */
class RunRecoveryDualWriteTest : DurableRuntimeTestBase() {

    private val eventStore = InMemoryAgentEventStore()

    private fun service(): RunRecoveryService = RunRecoveryService(
        ledger = ledger,
        runTerminalStore = runTerminalStore,
        conversationRepo = conversationRepository(),
        json = Json,
        agentEventStore = eventStore,
    )

    private suspend fun seedAgentRun(runId: String, status: RunStatus) {
        eventStore.appendRun(
            AgentRunRecord(
                runId = runId,
                parentRunId = null,
                agentDescriptorId = "chat_turn",
                agentVersion = "1.0.0",
                conversationId = null,
                messageNodeId = null,
                producesMessageId = null,
                assistantId = null,
                status = RunStatus.CREATED,
                inputDigest = "digest",
                inputSnapshotRef = null,
                inputSchemaVersion = 1,
                startedAt = System.currentTimeMillis(),
                finishedAt = null,
                interruptedReason = null,
            ),
        )
        if (status == RunStatus.CREATED) return
        // Walk a legal transition chain: CREATED → RUNNING → target pause.
        eventStore.transitionRun(AgentRunId(runId), emptySet(), RunStatus.RUNNING)
        if (status != RunStatus.RUNNING) {
            eventStore.transitionRun(AgentRunId(runId), emptySet(), status)
        }
    }

    private suspend fun startedNonIdempotentEffect(runId: String) {
        val effect = ledger.prepare(
            runId = runId,
            turnId = 0,
            toolCallId = "call_1",
            toolName = "post_message",
            input = """{"text":"hello"}""",
            effectClass = ToolEffectClass.NON_IDEMPOTENT_WRITE,
        )
        ledger.markStarted(effect.effectId, approvalDigest(runId, "call_1", effect.argsDigest))
    }

    @Test
    fun waitingUserEscalationMirrorsOutcomeUnknownToTheEventStore() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        runTerminalStore.pause("run_1", RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)
        seedAgentRun("run_1", RunStatus.WAITING_USER)
        startedNonIdempotentEffect("run_1")

        service().recover()

        assertEquals(RunTerminalState.OUTCOME_UNKNOWN, runTerminalStore.get("run_1")!!.state)
        assertEquals(RunStatus.OUTCOME_UNKNOWN, eventStore.runs["run_1"]!!.status)
    }

    @Test
    fun runningCrashVictimStaysRunningInTheEventStoreForReplayUnfinished() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        seedAgentRun("run_1", RunStatus.RUNNING)

        service().recover()

        // run_terminal settles INTERRUPTED here; the protocol row is owned by
        // replayUnfinished so its checkpoint projection still runs.
        assertEquals(RunTerminalState.INTERRUPTED, runTerminalStore.get("run_1")!!.state)
        assertEquals(RunStatus.RUNNING, eventStore.runs["run_1"]!!.status)
    }

    @Test
    fun resumableParkedRunSettlesInterruptedOnBothStores() = runBlocking {
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        runTerminalStore.pause("run_1", RunTerminalState.RESUMABLE, PauseReason.PROCESS_RESTART)
        seedAgentRun("run_1", RunStatus.RESUMABLE)

        // No stored-response gateway → Phase 1 crash rules decide.
        service().recover()

        assertEquals(RunTerminalState.INTERRUPTED, runTerminalStore.get("run_1")!!.state)
        // replayUnfinished skips pause states by design, so recovery itself
        // must settle the protocol row or it would stay RESUMABLE forever.
        assertEquals(RunStatus.INTERRUPTED, eventStore.runs["run_1"]!!.status)
    }

    @Test
    fun legacyOutcomeUnknownTerminalRowConvergesTheProtocolRow() = runBlocking {
        // Pre-protocol divergence: run_terminal escalated to OUTCOME_UNKNOWN
        // while agent_run was left at WAITING_USER. Without the re-assert,
        // replayUnfinished would leave the pause alone but a legacy RUNNING
        // row would have been stomped INTERRUPTED.
        val conversationId = Uuid.random()
        runTerminalStore.begin("run_1", conversationId.toString(), null)
        runTerminalStore.pause("run_1", RunTerminalState.OUTCOME_UNKNOWN, PauseReason.OUTCOME_UNKNOWN)
        seedAgentRun("run_1", RunStatus.WAITING_USER)

        service().recover()

        assertEquals(RunTerminalState.OUTCOME_UNKNOWN, runTerminalStore.get("run_1")!!.state)
        assertEquals(RunStatus.OUTCOME_UNKNOWN, eventStore.runs["run_1"]!!.status)
    }
}
