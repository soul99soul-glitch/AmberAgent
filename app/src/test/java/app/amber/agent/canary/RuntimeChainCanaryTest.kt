package app.amber.agent.canary

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.Model
import app.amber.ai.provider.ImageModelGateway
import app.amber.ai.provider.TextModelGateway
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.AILoggingManager
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.ChatGenerationRoundEngine
import app.amber.core.ai.DefaultRunKernel
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.GenerationRetrySetting
import app.amber.core.ai.GenerationTerminal
import app.amber.core.ai.transformers.InputMessageTransformer
import app.amber.core.ai.transformers.TransformerContext
import app.amber.core.context.AgentCapabilitySnapshotBuilder
import app.amber.core.context.ContextTooLargeException
import app.amber.core.context.ConversationContextEngine
import app.amber.core.context.ConversationContextRepository
import app.amber.core.context.TokenBudgetFitter
import app.amber.core.context.TokenFitProvenance
import app.amber.core.infra.AppScope
import app.amber.core.memory.recall.MemoryRecallStore
import app.amber.core.model.Conversation
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.MessageNode
import app.amber.core.repository.MemoryRepository
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.ContextCompactionSetting
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.settings.Settings
import app.amber.core.settings.SpeculativeToolExecutionSetting
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.PauseReason
import app.amber.feature.runtime.PermissionDecisionResolver
import app.amber.feature.runtime.RoomRunTerminalStore
import app.amber.feature.runtime.RoomToolEffectLedger
import app.amber.feature.runtime.RunOwnershipRegistry
import app.amber.feature.runtime.RunRecoveryService
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.runtime.ToolEffectStatus
import app.amber.feature.runtime.ToolInvocationContext
import app.amber.feature.runtime.terminalForFlowEnd
import app.amber.feature.tools.ToolEffectClass
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Durable-runtime production-chain canaries. File and artifact chains live in
 * [ProductionChainCanaryTest]; this class covers four cross-component
 * invariants:
 *
 *  1. stream → tool call → approval → side effect → tool result → next turn →
 *     durable terminal.
 *  2. stream → stop → the target run is cancelled, other runs unaffected
 *     (`RunOwnershipRegistry` scopes stop to (conversationId, runId), so a
 *     stale run cannot be cancelled).
 *  3. process death → checkpoint → resume/reconcile (recovery rules:
 *     STARTED non-idempotent → OUTCOME_UNKNOWN, never silently re-run;
 *     PREPARED → re-enters approval reusing the same effectId).
 *  4. prompt assembly → transformer → mailbox/steer → final token fit →
 *     provider, with a single hard fit at the provider boundary.
 *
 * Canary rules:
 *  - Real production components only: DefaultRunKernel (+ ChatGenerationRoundEngine), AgentToolDispatcher
 *    (write-ahead), RoomToolEffectLedger, RoomRunTerminalStore,
 *    RunRecoveryService, RunOwnershipRegistry, TokenBudgetFitter. Fakes sit
 *    only at the external boundaries: the provider stream (a scripted
 *    `TextModelGateway<ProviderSetting.OpenAI>` registered into the real
 *    ProviderCatalog), the approval UI action (flipping the tool part to
 *    `Approved`, as the approval card does), and process death (dropping
 *    every component instance and rebuilding over the SAME persisted store —
 *    in-memory Room keeps its data until close(), mirroring the SQLite file
 *    surviving a kill).
 *  - The durable runtime path is exercised (flags on, runId + onTerminal set).
 *  - Every chain's failure output can be located by runId / conversationId /
 *    effectId: runs are named `canary-chainN-…`, the approval card carries
 *    `effect_id` + `run_id` in tool metadata, and assertions include the ids.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalUuidApi::class)
class RuntimeChainCanaryTest : DurableRuntimeTestBase() {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- tool / marker constants (stable ids for failure location) ----
    private val TOOL_SIDE_EFFECT = "canary_post_effect" // non-idempotent write
    private val TOOL_LOOKUP = "canary_lookup" // read-only
    private val MARKER_WRITE = "canary-chain1-write-file"
    private val MARKER_STOP = "canary-chain2-stop-me"
    private val MARKER_FINISH = "canary-chain2-finish"
    private val MARKER_SIDE_EFFECT = "canary-chain3-side-effect"
    private val MARKER_SIDE_EFFECT_PREPARED = "canary-chain3-side-effect-prepared"
    private val MARKER_QUERY = "canary-chain4-query"
    private val MARKER_TOO_LARGE = "canary-chain4-too-large"

    // =====================================================================
    // Chain 1 — stream → tool call → approval → side effect → tool result →
    // next turn → durable terminal (P1-02 write-ahead + P1-03 typed terminal)
    // =====================================================================

    @Test
    fun chain1_streamToolApprovalSideEffectResultNextTurnDurableTerminal() = runBlocking {
        val runId = "canary-chain1-run"
        val conversationId = Uuid.random()
        val flags = capabilityFlags(Capability.DurableToolEffects, Capability.TypedRunTerminal)
        val model = canaryModel()
        val settings = canarySettings(model, canaryProviderSetting(model))
        val executions = AtomicInteger(0)
        val toolDef = Tool(
            name = TOOL_SIDE_EFFECT,
            description = "canary side-effect tool (non-idempotent write)",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("""{"status":"ok","written":true}"""))
            },
        )
        val provider = CanaryProvider(
            scripts = mapOf(
                MARKER_WRITE to CanaryProvider.Script(
                    listOf(toolCallChunk("call_chain1_1", TOOL_SIDE_EFFECT, """{"path":"/tmp/canary.txt","content":"hi"}"""))
                ),
                AFTER_TOOL to CanaryProvider.Script(listOf(textChunk("已写入 /tmp/canary.txt。"))),
            ),
        )
        val handler = defaultRunKernel(provider, flags)
        val conversation = canaryConversation(conversationId, listOf(UIMessage.user("$MARKER_WRITE 写入一个文件")))
        conversationRepository().insertConversation(conversation)
        runTerminalStore.begin(runId, conversationId.toString(), null)

        // Round 1: streamed tool call → approval gate. WAITING_USER is
        // persisted (a pause — never a completion), and the approval card is
        // bound to effect_id + run_id (failure output is locatable).
        val round1Events = RecordingEventWriter()
        val round1 = runRound(
            handler, settings, model, conversation.currentMessages, conversation, runId,
            tools = listOf(toolDef),
            lifecycleEvents = round1Events,
        )
        publishTerminal(runId, round1.terminal, round1.error)
        assertNull("chain1 round1 must not fail, runId=$runId", round1.error)
        assertEquals("runId=$runId", GenerationTerminal.WaitingUser, round1.terminal)
        val waiting = runTerminalStore.get(runId)!!
        assertEquals("runId=$runId", RunTerminalState.WAITING_USER, waiting.state)
        assertEquals("runId=$runId", PauseReason.TOOL_APPROVAL, waiting.pauseReason)
        assertNull("WAITING_USER is a pause, never finished, runId=$runId", waiting.finishedAtMs)

        val pendingMessages = round1.lastMessages
        val pendingTool = pendingMessages.flatMap { it.getTools() }.single()
        assertEquals(ToolApprovalState.Pending, pendingTool.approvalState)
        val effectId = ledger.getByToolCallId("call_chain1_1")!!.effectId
        assertNotNull("approval card metadata must exist", pendingTool.metadata)
        val metadata = pendingTool.metadata!!
        assertEquals("effect binding, runId=$runId effectId=$effectId", effectId, metadata["effect_id"]!!.jsonPrimitive.content)
        assertEquals("run binding, runId=$runId", runId, metadata["run_id"]!!.jsonPrimitive.content)
        val prepared = ledger.get(effectId)!!
        assertEquals("runId=$runId effectId=$effectId", ToolEffectStatus.PREPARED, prepared.status)
        assertEquals(ToolEffectClass.NON_IDEMPOTENT_WRITE, prepared.effectClass)
        assertEquals(runId, prepared.runId)

        // Step 3: the write-ahead prepare also entered the protocol event
        // stream, aligned with the ledger by effectId; the approval park
        // itself emits nothing.
        val preparedEvent = round1Events.committed
            .filterIsInstance<app.amber.core.agent.runtime.ToolLifecycleEvent.Prepared>()
            .single()
        assertEquals(effectId, preparedEvent.effectId)
        assertEquals("call_chain1_1", preparedEvent.toolCallId)
        assertEquals(TOOL_SIDE_EFFECT, preparedEvent.toolName)
        assertEquals(ToolEffectClass.NON_IDEMPOTENT_WRITE.name, preparedEvent.effectClass)
        assertEquals(prepared.argsDigest, preparedEvent.argsDigest)
        // Step 5: the round's wire request also left exactly one audit
        // RequestSnapshot on the stream, and exactly one Prepared fired for
        // the one prepared effect. A multiset (not a type-set): duplicates
        // of either event are a regression the set form would silently pass.
        assertEquals(
            "runId=$runId",
            mapOf("Prepared" to 1, "RequestSnapshot" to 1),
            round1Events.committed.groupingBy { it::class.simpleName!! }.eachCount(),
        )

        // Round 2 (same runId): the user approves → STARTED → side effect
        // executes exactly once → FINISHED with the result payload → the tool
        // result lands in the conversation message → the next turn streams the
        // final answer → the caller publishes COMPLETED.
        val approvedMessages = approveTool(pendingMessages, "call_chain1_1")
        val round2Events = RecordingEventWriter()
        val round2 = runRound(
            handler, settings, model, approvedMessages, conversation, runId,
            tools = listOf(toolDef),
            lifecycleEvents = round2Events,
        )
        publishTerminal(runId, round2.terminal, round2.error)
        assertNull("chain1 round2 must not fail, runId=$runId", round2.error)
        assertNull("no pause on the completion round, runId=$runId", round2.terminal)

        // Step 3: the resumed round executes without a re-prepare — exactly
        // Started → Finished(FINISHED), aligned with the same effectId.
        val round2Lifecycle = round2Events.committed
            .filterIsInstance<app.amber.core.agent.runtime.ToolLifecycleEvent>()
        assertEquals(listOf("Started", "Finished"), round2Lifecycle.map { it::class.simpleName })
        assertTrue(round2Lifecycle.all { it.effectId == effectId })
        val finishedEvent = round2Lifecycle.last() as app.amber.core.agent.runtime.ToolLifecycleEvent.Finished
        assertEquals(app.amber.core.agent.runtime.ToolLifecycleEvent.Finished.Status.FINISHED, finishedEvent.status)

        val finished = ledger.get(effectId)!!
        assertEquals("runId=$runId effectId=$effectId", ToolEffectStatus.FINISHED, finished.status)
        assertNotNull("approval digest must be bound, effectId=$effectId", finished.approvalDigest)
        assertNotNull("result payload must be replayable, effectId=$effectId", finished.resultPayload)
        assertEquals("the side effect must run exactly once, runId=$runId", 1, executions.get())
        val executedMessages = round2.lastMessages
        val executedTool = executedMessages.flatMap { it.getTools() }.single()
        assertTrue("tool result must land in the conversation message, runId=$runId", executedTool.isExecuted)
        assertTrue(
            (executedTool.output.single() as UIMessagePart.Text).text.contains("\"written\":true"),
        )
        assertTrue("next turn must stream the final answer, runId=$runId", executedMessages.last().toText().contains("已写入"))
        val terminal = runTerminalStore.get(runId)!!
        assertEquals("runId=$runId", RunTerminalState.COMPLETED, terminal.state)
        assertNotNull("runId=$runId", terminal.finishedAtMs)
    }

    // =====================================================================
    // Chain 2 — stream → stop → the target run is cancelled, other runs
    // unaffected (P1-05 scoped cancellation via RunOwnershipRegistry)
    // =====================================================================

    @Test
    fun chain2_stopCancelsOnlyTheTargetRun() = runBlocking {
        val flags = capabilityFlags(Capability.DurableToolEffects, Capability.TypedRunTerminal)
        val model = canaryModel()
        val settings = canarySettings(model, canaryProviderSetting(model))
        val provider = CanaryProvider(
            scripts = mapOf(
                MARKER_STOP to CanaryProvider.Script(listOf(textChunk("部分回复…")), hangAfter = true),
                MARKER_FINISH to CanaryProvider.Script(listOf(textChunk("另一个会话的完整回复"))),
            ),
        )
        val handler = defaultRunKernel(provider, flags)
        val registry = RunOwnershipRegistry()
        val runIdStop = "canary-chain2-run-stop"
        val runIdFinish = "canary-chain2-run-finish"
        val convStop = Uuid.random()
        val convFinish = Uuid.random()
        val repo = conversationRepository()
        repo.insertConversation(canaryConversation(convStop, listOf(UIMessage.user("$MARKER_STOP 写一篇长文"))))
        repo.insertConversation(canaryConversation(convFinish, listOf(UIMessage.user("$MARKER_FINISH 帮我总结"))))
        runTerminalStore.begin(runIdStop, convStop.toString(), null)
        runTerminalStore.begin(runIdFinish, convFinish.toString(), null)

        val chunksStop = mutableListOf<GenerationChunk>()
        val outcomes = mutableMapOf<String, Throwable?>()
        val jobStop = launch {
            runCatching {
                handler.run(
                    GenerationRunSession(
                        settings = settings,
                        model = model,
                        messages = listOf(UIMessage.user("$MARKER_STOP 写一篇长文")),
                        conversation = canaryConversation(convStop, listOf(UIMessage.user("$MARKER_STOP 写一篇长文"))),
                        runId = runIdStop,
                        onTerminal = { },
                    ),
                ).collect { chunksStop += it }
            }.onSuccess { outcomes[runIdStop] = null }.onFailure { outcomes[runIdStop] = it }
        }
        val jobFinish = launch {
            runCatching {
                handler.run(
                    GenerationRunSession(
                        settings = settings,
                        model = model,
                        messages = listOf(UIMessage.user("$MARKER_FINISH 帮我总结")),
                        conversation = canaryConversation(convFinish, listOf(UIMessage.user("$MARKER_FINISH 帮我总结"))),
                        runId = runIdFinish,
                        onTerminal = { },
                    ),
                ).collect { }
            }.onSuccess { outcomes[runIdFinish] = null }.onFailure { outcomes[runIdFinish] = it }
        }
        // P1-05: both runs register ownership (assistantId, conversationId, runId).
        assertTrue(registry.register(AMBER_AGENT_ID.toString(), convStop.toString(), runIdStop, jobStop))
        assertTrue(registry.register(AMBER_AGENT_ID.toString(), convFinish.toString(), runIdFinish, jobFinish))

        // Both runs stream in parallel; run-stop is mid-stream when we stop it.
        withTimeout(20_000) { while (chunksStop.isEmpty()) yield() }
        val streamedText = chunksStop
            .mapNotNull { it as? GenerationChunk.Messages }
            .flatMap { it.messages }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
        assertTrue("run-stop must be mid-stream before stop, runId=$runIdStop", streamedText.contains("部分回复"))

        // Stop is scoped: a cancel for the WRONG conversation must not touch
        // run-stop, then the real stop cancels exactly the target run.
        assertFalse(
            "wrong conversation must not cancel the run, runId=$runIdStop conv=${convFinish}",
            registry.cancel(runIdStop, convFinish.toString()),
        )
        assertTrue("stop must find and cancel the target run, runId=$runIdStop", registry.cancel(runIdStop, convStop.toString()))
        jobStop.join()
        val stopCause = outcomes[runIdStop]!!
        assertTrue("runId=$runIdStop must die with CancellationException", stopCause is CancellationException)
        assertTrue(jobStop.isCancelled)
        // The caller-side terminal publish mirrors ChatService.onCompletion.
        publishTerminal(runIdStop, null, stopCause)
        registry.unregister(runIdStop)

        // The other run is unaffected and completes normally.
        withTimeout(20_000) { jobFinish.join() }
        assertNull("runId=$runIdFinish must complete without error", outcomes[runIdFinish])
        assertFalse("runId=$runIdFinish must not be cancelled", jobFinish.isCancelled)
        publishTerminal(runIdFinish, null, null)
        registry.unregister(runIdFinish)

        val terminalStop = runTerminalStore.get(runIdStop)!!
        assertEquals("runId=$runIdStop", RunTerminalState.CANCELLED, terminalStop.state)
        assertEquals("runId=$runIdStop", PauseReason.USER_STOP, terminalStop.pauseReason)
        assertNotNull("runId=$runIdStop", terminalStop.finishedAtMs)
        val terminalFinish = runTerminalStore.get(runIdFinish)!!
        assertEquals("runId=$runIdFinish", RunTerminalState.COMPLETED, terminalFinish.state)
        assertNotNull("runId=$runIdFinish", terminalFinish.finishedAtMs)
        assertFalse(
            "a stale/finished runId must never cancel anything, runId=$runIdFinish",
            registry.cancel(runIdFinish, convFinish.toString()),
        )
    }

    // =====================================================================
    // Chain 3 — process death → checkpoint → resume/reconcile (P1-02)
    // =====================================================================

    @Test
    fun chain3_processDeathRecoveryReconcilesStartedAndReapprovesPrepared() = runBlocking {
        val flags = capabilityFlags(Capability.DurableToolEffects, Capability.TypedRunTerminal)
        val model = canaryModel()
        val settings = canarySettings(model, canaryProviderSetting(model))

        // --- sub-chain A: chain 1 run dies at STARTED (in-flight side effect)
        val runId = "canary-chain3-run"
        val conversationId = Uuid.random()
        val conversation = canaryConversation(conversationId, listOf(UIMessage.user("$MARKER_SIDE_EFFECT 执行副作用")))
        conversationRepository().insertConversation(conversation)
        runTerminalStore.begin(runId, conversationId.toString(), null)

        val executions = AtomicInteger(0)
        val completeExecution = AtomicBoolean(false)
        val toolDef = Tool(
            name = TOOL_SIDE_EFFECT,
            description = "non-idempotent canary side effect",
            execute = {
                executions.incrementAndGet()
                if (!completeExecution.get()) {
                    // The external side effect is in flight and never returns
                    // on its own — only a process death can stop it.
                    while (true) delay(1_000)
                }
                listOf(UIMessagePart.Text("""{"status":"applied"}"""))
            },
        )
        val provider = CanaryProvider(
            scripts = mapOf(
                MARKER_SIDE_EFFECT to CanaryProvider.Script(
                    listOf(toolCallChunk("call_chain3_1", TOOL_SIDE_EFFECT, """{"id":"effect-1"}"""))
                ),
                AFTER_TOOL to CanaryProvider.Script(listOf(textChunk("副作用已生效，任务完成。"))),
            ),
        )

        // Round 1 — approval pause, exactly like chain 1.
        val handler = defaultRunKernel(provider, flags)
        val round1 = runRound(
            handler, settings, model, conversation.currentMessages, conversation, runId,
            tools = listOf(toolDef),
        )
        publishTerminal(runId, round1.terminal, round1.error)
        assertEquals("runId=$runId", GenerationTerminal.WaitingUser, round1.terminal)
        val effect = ledger.getByToolCallId("call_chain3_1")!!
        assertEquals("runId=$runId effectId=${effect.effectId}", ToolEffectStatus.PREPARED, effect.status)

        // Round 2 — the user approves; the run is re-opened (ChatService
        // resumes the same runId) and the side effect starts (STARTED
        // persisted) and stays in flight. Simulated process death: the
        // generation job is killed mid-execution and NO terminal is published
        // (the process died before the caller could write one).
        runTerminalStore.begin(runId, conversationId.toString(), null) // approval resume, same runId
        val job = launch {
            runCatching {
                handler.run(
                    GenerationRunSession(
                        settings = settings,
                        model = model,
                        messages = approveTool(round1.lastMessages, "call_chain3_1"),
                        tools = listOf(toolDef),
                        conversation = conversation,
                        runId = runId,
                        onTerminal = { },
                    ),
                ).collect { }
            }
        }
        // Wait until the effect is STARTED and the tool is genuinely in
        // flight (markStarted is written just before execute() starts — poll
        // both so the cancellation below never races the first lambda line).
        withTimeout(20_000) {
            while (ledger.get(effect.effectId)?.status != ToolEffectStatus.STARTED) yield()
            while (executions.get() == 0) yield()
        }
        assertEquals("side effect must have started exactly once, runId=$runId", 1, executions.get())
        job.cancel()
        job.join()
        assertEquals(
            "effectId=${effect.effectId} must stay STARTED (no FINISHED) after death",
            ToolEffectStatus.STARTED, ledger.get(effect.effectId)!!.status,
        )
        assertEquals(
            "runId=$runId stays RUNNING: the process died before any terminal write",
            RunTerminalState.RUNNING, runTerminalStore.get(runId)!!.state,
        )

        // Cold start: rebuild EVERY component instance over the same persisted
        // store (in-memory Room keeps its data until close(), mirroring the
        // SQLite file surviving a kill; the "process" is a fresh object graph).
        val ledgerAfter = RoomToolEffectLedger(database.toolEffectDao(), database.runTerminalDao(), json)
        val terminalAfter = RoomRunTerminalStore(database.runTerminalDao())
        val recoveryAfter = RunRecoveryService(ledgerAfter, terminalAfter, conversationRepository(), json)
        recoveryAfter.recover()

        // Recovery rules: STARTED non-idempotent → OUTCOME_UNKNOWN; the run is
        // escalated (settled, but never silently COMPLETED or re-executed).
        val recovered = ledgerAfter.get(effect.effectId)!!
        assertEquals("effectId=${effect.effectId}", ToolEffectStatus.OUTCOME_UNKNOWN, recovered.status)
        assertEquals("effectId=${effect.effectId}", "interrupted_mid_execution", recovered.errorCategory)
        assertEquals("runId=$runId", RunTerminalState.OUTCOME_UNKNOWN, terminalAfter.get(runId)!!.state)
        assertEquals("recovery must NOT re-run the side effect, runId=$runId", 1, executions.get())

        // User confirms retry → the SAME effectId re-executes to FINISHED.
        ledgerAfter.reconcile(effect.effectId, retry = true)
        assertEquals("effectId=${effect.effectId}", ToolEffectStatus.RECONCILED, ledgerAfter.get(effect.effectId)!!.status)
        completeExecution.set(true)
        runTerminalStore.begin(runId, conversationId.toString(), null) // resume the same runId
        val handler2 = defaultRunKernel(provider, flags)
        val round3 = runRound(
            handler2, settings, model, approveTool(round1.lastMessages, "call_chain3_1"), conversation, runId,
            tools = listOf(toolDef),
        )
        publishTerminal(runId, round3.terminal, round3.error)
        assertNull("runId=$runId", round3.error)
        val finalEffect = ledger.get(effect.effectId)!!
        assertEquals(
            "same effectId reused after reconcile — no duplicate effect, runId=$runId effectId=${effect.effectId}",
            effect.effectId, finalEffect.effectId,
        )
        assertEquals("effectId=${effect.effectId}", ToolEffectStatus.FINISHED, finalEffect.status)
        assertEquals("exactly one confirmed retry, runId=$runId", 2, executions.get())
        assertEquals("runId=$runId", RunTerminalState.COMPLETED, runTerminalStore.get(runId)!!.state)

        // --- sub-chain B: Prepared → re-approval (crash before approval)
        val runIdPrepared = "canary-chain3-run-prepared"
        val conversationIdPrepared = Uuid.random()
        val conversationPrepared =
            canaryConversation(conversationIdPrepared, listOf(UIMessage.user("$MARKER_SIDE_EFFECT_PREPARED 再来一次")))
        conversationRepository().insertConversation(conversationPrepared)
        runTerminalStore.begin(runIdPrepared, conversationIdPrepared.toString(), null)
        val executionsPrepared = AtomicInteger(0)
        val preparedToolDef = Tool(
            name = TOOL_SIDE_EFFECT,
            description = "non-idempotent canary side effect (prepared branch)",
            execute = {
                executionsPrepared.incrementAndGet()
                listOf(UIMessagePart.Text("""{"status":"applied"}"""))
            },
        )
        // A second provider with its own toolCallId, so the prepared branch
        // never collides with sub-chain A's effect rows.
        val providerPrepared = CanaryProvider(
            scripts = mapOf(
                MARKER_SIDE_EFFECT_PREPARED to CanaryProvider.Script(
                    listOf(toolCallChunk("call_chain3_prepared", TOOL_SIDE_EFFECT, """{"id":"effect-2"}"""))
                ),
                AFTER_TOOL to CanaryProvider.Script(listOf(textChunk("副作用已生效，任务完成。"))),
            ),
        )
        val handlerPrepared = defaultRunKernel(providerPrepared, flags)
        val roundPrepared1 = runRound(
            handlerPrepared, settings, model, conversationPrepared.currentMessages, conversationPrepared, runIdPrepared,
            tools = listOf(preparedToolDef),
        )
        publishTerminal(runIdPrepared, roundPrepared1.terminal, roundPrepared1.error)
        assertEquals("runId=$runIdPrepared", GenerationTerminal.WaitingUser, roundPrepared1.terminal)
        val preparedEffect = ledger.getByToolCallId("call_chain3_prepared")!!
        assertEquals("runId=$runIdPrepared effectId=${preparedEffect.effectId}", ToolEffectStatus.PREPARED, preparedEffect.status)

        // Death before approval: recovery leaves PREPARED untouched, and the
        // WAITING_USER pause survives the restart (P1-03: the approval entry
        // is rebuilt at cold start) — the same runId resumes after re-approval.
        recoveryAfter.recover()
        assertEquals(
            "runId=$runIdPrepared effectId=${preparedEffect.effectId}",
            ToolEffectStatus.PREPARED, ledger.get(preparedEffect.effectId)!!.status,
        )
        val survivingPause = runTerminalStore.get(runIdPrepared)!!
        assertEquals("runId=$runIdPrepared", RunTerminalState.WAITING_USER, survivingPause.state)
        assertNull("WAITING_USER survives restart as a pause, runId=$runIdPrepared", survivingPause.finishedAtMs)

        // Re-approval: a fresh approval round reuses the SAME effectId.
        runTerminalStore.begin(runIdPrepared, conversationIdPrepared.toString(), null)
        val roundPrepared2 = runRound(
            handlerPrepared, settings, model,
            approveTool(roundPrepared1.lastMessages, "call_chain3_prepared"), conversationPrepared, runIdPrepared,
            tools = listOf(preparedToolDef),
        )
        publishTerminal(runIdPrepared, roundPrepared2.terminal, roundPrepared2.error)
        assertNull("runId=$runIdPrepared", roundPrepared2.error)
        val reapproved = ledger.getByToolCallId("call_chain3_prepared")!!
        assertEquals(
            "re-approval must reuse the prepared effectId, runId=$runIdPrepared",
            preparedEffect.effectId, reapproved.effectId,
        )
        assertEquals("effectId=${reapproved.effectId}", ToolEffectStatus.FINISHED, reapproved.status)
        assertEquals("runId=$runIdPrepared", 1, executionsPrepared.get())
        assertEquals("runId=$runIdPrepared", RunTerminalState.COMPLETED, runTerminalStore.get(runIdPrepared)!!.state)
    }

    // =====================================================================
    // Chain 4 — prompt assembly → transformer → mailbox/steer → final token
    // fit → provider (P1-04 single hard fit at the provider boundary)
    // =====================================================================

    @Test
    fun chain4_promptAssemblyTransformerSteerFinalFitProviderBoundary() = runBlocking {
        TokenBudgetFitter.clearReceiptsForTest()
        val flags = capabilityFlags(Capability.DurableToolEffects, Capability.TypedRunTerminal)
        // Small window (8_000) → hard input budget = 4_000 tokens.
        val model = canaryModel(windowTokens = 8_000)
        val settings = canarySettings(model, canaryProviderSetting(model))
        val runId = "canary-chain4-run"
        val conversationId = Uuid.random()
        val history = buildList {
            repeat(2) { i ->
                add(UIMessage.user("old history user $i " + "x".repeat(6_000)))
                add(UIMessage.assistant("old history reply $i " + "y".repeat(6_000)))
            }
        }
        val currentUser = UIMessage.user("$MARKER_QUERY 请查一下资料 " + "q".repeat(400))
        val conversation = canaryConversation(conversationId, history + currentUser)
        conversationRepository().insertConversation(conversation)
        runTerminalStore.begin(runId, conversationId.toString(), null)

        val lookupTool = Tool(
            name = TOOL_LOOKUP,
            description = "canary read-only lookup",
            execute = { listOf(UIMessagePart.Text("""{"status":"ok","rows":[]}""")) },
        )
        val provider = CanaryProvider(
            scripts = mapOf(
                MARKER_QUERY to CanaryProvider.Script(listOf(toolCallChunk("call_chain4_1", TOOL_LOOKUP, """{"query":"canary"}"""))),
                AFTER_TOOL to CanaryProvider.Script(listOf(textChunk("查询完成，未发现异常。"))),
            ),
        )
        val handler = defaultRunKernel(provider, flags)
        // Transformer that expands the current user request AFTER prompt
        // assembly (the OCR-style growth that previously made requests
        // silently over-budget).
        val ocr = object : InputMessageTransformer {
            override suspend fun transform(
                ctx: TransformerContext,
                messages: List<UIMessage>,
            ): List<UIMessage> {
                val lastUser = messages.indexOfLast { it.role == MessageRole.USER }
                if (lastUser < 0) return messages
                return messages.mapIndexed { index, message ->
                    if (index == lastUser) {
                        message.copy(parts = message.parts + UIMessagePart.Text("OCR 识别结果: " + "z".repeat(6_000)))
                    } else {
                        message
                    }
                }
            }
        }
        // Mailbox/steer: queued during generation, merged into the request
        // AFTER the tool round. steer1 is a big mailbox message (STEER —
        // trimmable, counted last); steer2 is the latest steer (CURRENT_USER
        // — the current request, never trimmed), mirroring the production
        // classification (last USER message = current request).
        val steer1 = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("steer queued during generation " + "s".repeat(50_000))),
        )
        val steer2 = UIMessage.user("steer2 latest current request " + "c".repeat(200))

        val round = runRound(
            handler, settings, model,
            messages = conversation.currentMessages,
            conversation = conversation,
            runId = runId,
            tools = listOf(lookupTool),
            inputTransformers = listOf(ocr),
            consumeSteer = { listOf(steer1, steer2) },
        )
        publishTerminal(runId, round.terminal, round.error)
        assertNull("chain4 must not throw, runId=$runId", round.error)

        // The provider received the FINAL fitted request: in budget, the
        // current request (latest steer) + tool result kept, old history and
        // the queued mailbox/steer message trimmed.
        val finalRequest = provider.received.last()
        val fittedIds = finalRequest.map { it.id }.toSet()
        assertTrue("current request (latest steer) must survive the final fit, runId=$runId", steer2.id in fittedIds)
        assertTrue(
            "tool result must survive the final fit, runId=$runId",
            finalRequest.any { message -> message.getTools().any { it.isExecuted } },
        )
        assertTrue("system prompt must survive, runId=$runId", finalRequest.any { it.role == MessageRole.SYSTEM })
        assertTrue("old history must be trimmed, runId=$runId", history.none { it.id in fittedIds })
        assertTrue("queued mailbox/steer must be trimmed, runId=$runId", steer1.id !in fittedIds)

        val receipt = TokenBudgetFitter.receipts.value.first { it.conversationId == conversationId.toString() }
        assertFalse("runId=$runId", receipt.contextTooLarge)
        assertTrue(
            "final fit must land within the hard budget, runId=$runId (${receipt.estimatedAfter} <= ${receipt.budgetTokens})",
            receipt.estimatedAfter <= receipt.budgetTokens,
        )
        assertEquals(
            "HISTORY is trimmed before STEER (steer counted last), runId=$runId",
            listOf(TokenFitProvenance.HISTORY, TokenFitProvenance.STEER),
            receipt.trimmedMessages.map { it.provenance },
        )
        // The request body the provider saw IS the fitted request.
        assertEquals(
            "provider request must equal the fitted message set, runId=$runId",
            receipt.estimatedAfter,
            TokenBudgetFitter.estimateTokens(finalRequest) +
                TokenBudgetFitter.estimateToolSchemaTokens(listOf(lookupTool), json),
        )

        // --- ContextTooLarge: a request that cannot fit is NEVER sent.
        val tooLargeRunId = "canary-chain4-too-large"
        val tooLargeConversationId = Uuid.random()
        val hugeUser = UIMessage.user("$MARKER_TOO_LARGE " + "x".repeat(60_000))
        val tooLargeConversation = canaryConversation(tooLargeConversationId, listOf(hugeUser))
        conversationRepository().insertConversation(tooLargeConversation)
        runTerminalStore.begin(tooLargeRunId, tooLargeConversationId.toString(), null)
        val provider2 = CanaryProvider(scripts = mapOf(MARKER_TOO_LARGE to CanaryProvider.Script(listOf(textChunk("不可达")))))
        val handler2 = defaultRunKernel(provider2, flags)
        val receivedBefore = provider2.received.size
        val tooLargeOutcome = runRound(
            handler2, settings, model,
            messages = listOf(hugeUser),
            conversation = tooLargeConversation,
            runId = tooLargeRunId,
            inputTransformers = listOf(ocr),
        )
        assertTrue(
            "over-budget request must throw ContextTooLarge instead of being sent, runId=$tooLargeRunId",
            tooLargeOutcome.error is ContextTooLargeException,
        )
        assertEquals(
            "the provider must not receive an over-budget request, runId=$tooLargeRunId",
            receivedBefore, provider2.received.size,
        )
        val tooLargeReceipt =
            TokenBudgetFitter.receipts.value.first { it.conversationId == tooLargeConversationId.toString() }
        assertTrue("runId=$tooLargeRunId", tooLargeReceipt.contextTooLarge)
        assertTrue(
            "runId=$tooLargeRunId (${tooLargeReceipt.estimatedAfter} > ${tooLargeReceipt.budgetTokens})",
            tooLargeReceipt.estimatedAfter > tooLargeReceipt.budgetTokens,
        )
        publishTerminal(tooLargeRunId, null, tooLargeOutcome.error)
        assertEquals("runId=$tooLargeRunId", RunTerminalState.FAILED, runTerminalStore.get(tooLargeRunId)!!.state)
    }

    // =====================================================================
    // harness — real production components, fakes only at the boundaries
    // =====================================================================

    private suspend fun capabilityFlags(vararg enabled: Capability): CapabilityFlags {
        val flags = CapabilityFlags(
            PreferenceDataStoreFactory.create {
                File(context.cacheDir, "canary-flags-${Uuid.random()}.preferences_pb")
            }
        )
        enabled.forEach { flags.setEnabled(it, true) }
        return flags
    }

    private fun canaryModel(windowTokens: Int? = 128_000) = Model(
        modelId = "canary-model",
        displayName = "Canary Model",
        contextWindowTokens = windowTokens,
    )

    private fun canaryProviderSetting(model: Model) = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = "Canary OpenAI",
        models = listOf(model),
        baseUrl = "https://api.openai.com/v1",
    )

    private fun canarySettings(model: Model, provider: ProviderSetting.OpenAI): Settings = Settings(
        providers = listOf(provider.copy(models = listOf(model))),
        systemPrompt = "You are the canary assistant.",
        agentRuntime = AgentRuntimeSetting(
            agentSoulMarkdown = "",
            enableRecentChatsReference = false,
            enableCoreMemory = false,
            enableShortTermMemory = false,
            enableLongTermMemory = false,
            generativeUi = GenerativeUiSetting(enabled = false),
            contextCompaction = ContextCompactionSetting(enabled = false),
            speculativeToolExecution = SpeculativeToolExecutionSetting(enabled = false),
            generationRetry = GenerationRetrySetting(enabled = false),
        ),
    )

    private fun canaryConversation(id: Uuid, messages: List<UIMessage>): Conversation =
        Conversation(
            id = id,
            assistantId = AMBER_AGENT_ID,
            messageNodes = messages.map { MessageNode.of(it) },
        )

    private fun defaultRunKernel(providerImpl: CanaryProvider, flags: CapabilityFlags): DefaultRunKernel {
        val httpClient = OkHttpClient()
        val providerCatalog = ProviderCatalog(
            openAIProvider = app.amber.ai.provider.providers.OpenAIProvider(httpClient, context),
            googleProvider = app.amber.ai.provider.providers.GoogleProvider(httpClient, context),
            claudeProvider = app.amber.ai.provider.providers.ClaudeProvider(httpClient, context),
            openAITextGateway = providerImpl,
            openAIImageGateway = providerImpl,
        )
        val conversationRepo = conversationRepository()
        val memoryRepo = MemoryRepository(
            memoryDAO = database.memoryDao(),
            candidateDAO = database.memoryCandidateDao(),
            eventDAO = database.memoryEventDao(),
            appDatabase = database,
        )
        val contextEngine = ConversationContextEngine(
            providerCatalog = providerCatalog,
            json = json,
            contextRepository = ConversationContextRepository(
                compactDAO = database.conversationCompactDao(),
                eventDAO = database.conversationContextEventDao(),
                conversationRepository = conversationRepo,
            ),
            appScope = AppScope(),
            capabilitySnapshotBuilder = AgentCapabilitySnapshotBuilder(),
            promptConfigRepository = AgentPromptConfigRepository(context),
            context = context,
        )
        val roundEngine = ChatGenerationRoundEngine(
            context = context,
            providerCatalog = providerCatalog,
            json = json,
            memoryRecallStore = MemoryRecallStore(memoryRepo),
            conversationRepo = conversationRepo,
            aiLoggingManager = AILoggingManager(),
            conversationContextEngine = contextEngine,
        )
        return DefaultRunKernel(
            context = context,
            toolDispatcher = AgentToolDispatcher(json, PermissionDecisionResolver()),
            roundEngine = roundEngine,
            toolEffectLedger = ledger,
            capabilityFlags = flags,
            capabilityPermissionStore = null,
        )
    }

    private class RoundOutcome(
        val chunks: List<GenerationChunk>,
        val terminal: GenerationTerminal?,
        val error: Throwable?,
    ) {
        /** The last message snapshot emitted by the round (post-update). */
        val lastMessages: List<UIMessage>
            get() = chunks.mapNotNull { it as? GenerationChunk.Messages }.lastOrNull()?.messages ?: emptyList()
    }

    private suspend fun runRound(
        handler: DefaultRunKernel,
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        conversation: Conversation,
        runId: String,
        tools: List<Tool> = emptyList(),
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        consumeSteer: suspend () -> List<UIMessage> = { emptyList() },
        lifecycleEvents: app.amber.core.agent.runtime.AgentEventWriter? = null,
    ): RoundOutcome {
        val chunks = mutableListOf<GenerationChunk>()
        var terminal: GenerationTerminal? = null
        val error = runCatching {
            handler.run(
                GenerationRunSession(
                    settings = settings,
                    model = model,
                    messages = messages,
                    inputTransformers = inputTransformers,
                    tools = tools,
                    maxSteps = 8,
                    autoApproveTools = false,
                    invocationContext = ToolInvocationContext.Normal,
                    conversation = conversation,
                    consumeSteerMessages = consumeSteer,
                    runId = runId,
                    onTerminal = { terminal = it },
                    toolLifecycleEvents = lifecycleEvents,
                ),
            ).collect { chunks += it }
        }.exceptionOrNull()
        return RoundOutcome(chunks, terminal, error)
    }

    /** Step 3 recording writer: captures the protocol events a round emits. */
    private class RecordingEventWriter : app.amber.core.agent.runtime.AgentEventWriter {
        val committed = mutableListOf<app.amber.core.agent.runtime.AgentEventPayload.Final>()
        override fun emit(transient: app.amber.core.agent.runtime.AgentEventPayload.Transient) {}
        override suspend fun commit(final: app.amber.core.agent.runtime.AgentEventPayload.Final) {
            committed += final
        }
        override suspend fun flush() {}
        override suspend fun commitError(throwable: Throwable, recoverable: Boolean) {}
    }

    /**
     * Caller-side terminal publish — mirrors ChatService.onCompletion:
     * `terminalForFlowEnd(cause, reportedTerminal)` decides the durable state;
     * CANCELLED / FAILED additionally reconcile STARTED effects.
     */
    private suspend fun publishTerminal(runId: String, reported: GenerationTerminal?, cause: Throwable?) {
        val (state, reason) = terminalForFlowEnd(cause, reported)
        when (state) {
            RunTerminalState.WAITING_USER -> runTerminalStore.pause(runId, state, reason)
            else -> runTerminalStore.finish(runId, state, reason)
        }
        if (state == RunTerminalState.CANCELLED || state == RunTerminalState.FAILED) {
            RunRecoveryService(ledger, runTerminalStore, conversationRepository(), json)
                .reconcileStartedEffects(runId)
        }
    }

    /** The approval card action: Approved → the run resumes with the same runId. */
    private fun approveTool(messages: List<UIMessage>, toolCallId: String): List<UIMessage> =
        messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                        part.copy(approvalState = ToolApprovalState.Approved)
                    } else {
                        part
                    }
                }
            )
        }

    private fun textChunk(text: String): MessageChunk = assistantChunk(listOf(UIMessagePart.Text(text)))

    private fun toolCallChunk(toolCallId: String, toolName: String, input: String): MessageChunk =
        assistantChunk(listOf(UIMessagePart.Tool(toolCallId = toolCallId, toolName = toolName, input = input)))

    /**
     * Streaming delta chunk (delta=…, message=null) — the same shape a real
     * provider stream uses: deltas append to the active assistant message,
     * they never replace it wholesale. (A chunk with `message=` set would
     * REPLACE the active message, which is only correct for the final
     * complete-message replay — and would drop tool parts mid-stream.)
     */
    private fun assistantChunk(parts: List<UIMessagePart>): MessageChunk =
        MessageChunk(
            id = "canary_chunk",
            model = "canary-model",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
                    message = null,
                    finishReason = null,
                )
            ),
        )

    companion object {
        private const val AFTER_TOOL = "__after_tool__"
    }
}

/**
 * Scripted fake at the provider boundary only. Records every request body it
 * receives (`received`) so the canaries can assert what the provider actually
 * saw (fitted request / no request at all). Script selection keys off the
 * conversation content, with an "after tool execution" script that wins once
 * an executed tool is present in the request.
 */
private class CanaryProvider(
    private val scripts: Map<String, Script>,
) : TextModelGateway<ProviderSetting.OpenAI>, ImageModelGateway<ProviderSetting.OpenAI> {

    data class Script(
        val chunks: List<MessageChunk>,
        val hangAfter: Boolean = false,
    )

    val received = mutableListOf<List<UIMessage>>()

    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

    override suspend fun complete(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = error("chain canaries use the streaming path only")

    override suspend fun stream(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = flow {
        received += messages
        val script = resolveScript(messages)
        script.chunks.forEach { emit(it) }
        if (script.hangAfter) {
            // The stream stays open (like a slow provider) until the run is
            // cancelled through RunOwnershipRegistry.
            while (true) delay(100)
        }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
    ): ImageGenerationResult = error("not used")

    private fun resolveScript(messages: List<UIMessage>): Script {
        if (messages.any { message -> message.getTools().any { it.isExecuted } }) {
            return scripts["__after_tool__"]
                ?: error("CanaryProvider: missing __after_tool__ script")
        }
        val userTexts = messages.filter { it.role == MessageRole.USER }.map { it.toText() }
        val matched = scripts.entries.firstOrNull { (marker, _) -> userTexts.any { marker in it } }?.value
        if (matched == null) {
            error("CanaryProvider: no script matches ${userTexts.joinToString(" | ").take(160)}")
        }
        return matched
    }
}
