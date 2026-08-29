package app.amber.agent.replay

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.ImageModelGateway
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.TextModelGateway
import app.amber.ai.ui.ImageGenerationResult
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.core.agent.runtime.Agent
import app.amber.core.agent.runtime.AgentCapability
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentDescriptorId
import app.amber.core.agent.runtime.AgentEventPayloadCodec
import app.amber.core.agent.runtime.AgentEventRecord
import app.amber.core.agent.runtime.AgentEventStore
import app.amber.core.agent.runtime.AgentHandler
import app.amber.core.agent.runtime.AgentInput
import app.amber.core.agent.runtime.AgentArtifact
import app.amber.core.agent.runtime.AgentRunId
import app.amber.core.agent.runtime.AgentRunRecord
import app.amber.core.agent.runtime.AgentRunSnapshot
import app.amber.core.agent.runtime.InMemoryAgentEventStore
import app.amber.core.agent.runtime.MessageNodeId
import app.amber.core.agent.runtime.RunStatus
import app.amber.core.agent.runtime.RunTransitionResult
import app.amber.core.agent.runtime.ToolLifecycleEvent
import app.amber.core.agent.runtime.TraceSpanRecord
import app.amber.core.agent.runtime.adapter.LegacyRunScope
import app.amber.core.agent.runtime.impl.InMemoryAgentRegistry
import app.amber.core.agent.runtime.impl.InProcessAgentRunner
import app.amber.core.agent.runtime.impl.PersistingEventWriter
import app.amber.core.ai.AILoggingManager
import app.amber.core.ai.ChatGenerationRoundEngine
import app.amber.core.ai.DefaultRunKernel
import app.amber.core.ai.GenerationChunk
import app.amber.core.ai.GenerationRetrySetting
import app.amber.core.ai.GenerationRunSession
import app.amber.core.ai.GenerationTerminal
import app.amber.core.context.AgentCapabilitySnapshotBuilder
import app.amber.core.context.ConversationContextEngine
import app.amber.core.context.ConversationContextRepository
import app.amber.core.infra.AppScope
import app.amber.core.memory.recall.MemoryRecallStore
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.repository.MemoryRepository
import app.amber.core.service.ConversationAccess
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.ContextCompactionSetting
import app.amber.core.settings.GenerativeUiSetting
import app.amber.core.settings.Settings
import app.amber.core.settings.SpeculativeToolExecutionSetting
import app.amber.feature.chat.api.ChatEventPayload
import app.amber.feature.chat.impl.ChatEventProjector
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.PauseReason
import app.amber.feature.runtime.PermissionDecisionResolver
import app.amber.feature.runtime.RoomRunTerminalStore
import app.amber.feature.runtime.RoomToolEffectLedger
import app.amber.feature.runtime.RunRecoveryService
import app.amber.feature.runtime.RunTerminalState
import app.amber.feature.runtime.ToolEffectLedger
import app.amber.feature.runtime.ToolEffectStatus
import app.amber.feature.runtime.ToolInvocationContext
import app.amber.feature.runtime.terminalForFlowEnd
import app.amber.feature.runtime.sha256Hex
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** GDBG diagnostics print only with REPLAY_DEBUG=1; normal runs stay silent. */
private val replayDebug: Boolean = System.getenv("REPLAY_DEBUG") == "1"

/**
 * Step 4 — replay golden tests, Android half of the audit's P0 dual-end
 * replay-consistency suite. The ten audit scenarios run through the real
 * production chain (DefaultRunKernel + AgentToolDispatcher write-ahead +
 * Room ledger/terminal store + InProcessAgentRunner + RunRecoveryService +
 * ChatEventProjector.replayUnfinished); fakes sit only at the external
 * boundaries (scripted provider stream, approval-card flip, process death).
 *
 * What is compared is the NORMALIZED event sequence — run lifecycle lines,
 * tool lifecycle lines and the assistant-message line — never UI text, never
 * ids/timestamps. The vocabulary and the normalization rules are documented
 * in test-fixtures/replay/README.md (schema v1); goldens live in
 * test-fixtures/replay/v1/ and are loaded as test resources. Regenerate after
 * an intentional behavior change with:
 *
 *   ./gradlew :app:testDebugUnitTest --tests "app.amber.agent.replay.ReplayGoldenTest" -PupdateGoldens=true
 *
 * iOS produces the same ten sequences on its own stack; cross-end equality is
 * asserted by comparing the two golden sets, not from this repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalUuidApi::class)
class ReplayGoldenTest : DurableRuntimeTestBase() {

    private val json = Json { ignoreUnknownKeys = true }

    // Read-only (no mutating name hint) and non-idempotent write ("post"
    // hint, not in IDEMPOTENT_WRITE_TOOLS) per ToolRegistry.effectClass().
    private val TOOL_LOOKUP_A = "golden_lookup_a"
    private val TOOL_LOOKUP_B = "golden_lookup_b"
    private val TOOL_POST = "golden_post_effect"
    private val TOOL_DELEGATE = "golden_delegate"

    // =====================================================================
    // Scenario 1 — 模型输出普通文本
    // =====================================================================

    @Test
    fun s01_plain_text() = runBlocking {
        val world = newWorld()
        val process = world.newProcess()
        val provider = GoldenProvider(listOf(listOf(textChunk("你好，世界。"))))
        val holder = world.installChatAgent(process, provider, tools = emptyList())

        process.launchChat("s01_run", holder)
        world.awaitStatus("s01_run") { it == RunStatus.COMPLETED }

        // Step 5 audit: a single-step plain-text turn issues exactly one wire
        // request, snapshotted after the fit and before the provider call.
        val snapshots = requestSnapshotsOf(world, "s01_run")
        assertEquals(1, snapshots.size)
        snapshots.single().let { snapshot ->
            assertEquals(0, snapshot.stepIndex)
            assertEquals(0, snapshot.attempt)
            assertEquals("primary", snapshot.kind)
            assertTrue("messagesDigest must be present", snapshot.messagesDigest.isNotBlank())
            assertEquals(
                "messageCount must match the list the provider actually received",
                provider.received.single().size,
                snapshot.messageCount,
            )
            // Assertion depth: the digest must bind the REAL provider
            // serialization, not the text-only fallback rendering. Recompute
            // it over the list the provider actually received, with the SAME
            // Json instance the round engine (and therefore
            // buildRequestSnapshot) was constructed with — serialization
            // parity by construction, so a fallback digest cannot pass.
            val expectedDigest = sha256Hex(
                json.encodeToString(ListSerializer(UIMessage.serializer()), provider.received.single()),
            )
            assertEquals(
                "messagesDigest must equal the recomputation over the provider-received request",
                expectedDigest,
                snapshot.messagesDigest,
            )
        }
        assertGolden("s01_plain_text", world.normalized())
    }

    // =====================================================================
    // Scenario 2 — 模型输出一个只读工具
    // =====================================================================

    @Test
    fun s02_single_readonly_tool() = runBlocking {
        val world = newWorld()
        val process = world.newProcess()
        val provider = GoldenProvider(
            listOf(
                listOf(toolCallChunk("call_1", TOOL_LOOKUP_A, """{"q":"琥珀"}""")),
                listOf(textChunk("查询完成。")),
            ),
        )
        val holder = world.installChatAgent(process, provider, tools = listOf(world.lookupTool(TOOL_LOOKUP_A)))

        process.launchChat("s02_run", holder)
        world.awaitStatus("s02_run") { it == RunStatus.COMPLETED }

        assertEquals(1, world.executionsOf(TOOL_LOOKUP_A).get())
        assertGolden("s02_single_readonly_tool", world.normalized())
    }

    // =====================================================================
    // Scenario 3 — 模型同时输出多个工具（并行批次）
    // =====================================================================

    @Test
    fun s03_multiple_tools_one_round() = runBlocking {
        val world = newWorld()
        val process = world.newProcess()
        val provider = GoldenProvider(
            listOf(
                listOf(
                    toolCallChunk("call_1", TOOL_LOOKUP_A, """{"q":"a"}"""),
                    toolCallChunk("call_2", TOOL_LOOKUP_B, """{"q":"b"}"""),
                ),
                listOf(textChunk("两个查询都完成。")),
            ),
        )
        // The barrier guarantees the two parallel executions overlap in every
        // run, so the normalized parallel-window collapse (not scheduler
        // timing) decides the golden order.
        val holder = world.installChatAgent(
            process, provider,
            tools = listOf(world.barrieredLookupTool(TOOL_LOOKUP_A), world.barrieredLookupTool(TOOL_LOOKUP_B)),
        )

        process.launchChat("s03_run", holder)
        world.awaitStatus("s03_run") { it == RunStatus.COMPLETED }

        assertEquals(1, world.executionsOf(TOOL_LOOKUP_A).get())
        assertEquals(1, world.executionsOf(TOOL_LOOKUP_B).get())
        assertGolden("s03_multiple_tools_one_round", world.normalized())
    }

    // =====================================================================
    // Scenario 4 — 工具要求审批，用户批准
    // =====================================================================

    @Test
    fun s04_approval_granted() = runBlocking {
        val world = newWorld()
        val process = world.newProcess()
        val provider = GoldenProvider(
            listOf(
                listOf(toolCallChunk("call_1", TOOL_POST, """{"path":"/tmp/golden.txt"}""")),
                listOf(textChunk("已写入 /tmp/golden.txt。")),
            ),
        )
        val executions = AtomicInteger(0)
        val holder = world.installChatAgent(
            process, provider,
            tools = listOf(world.postTool(executions)),
            autoApprove = false,
        )

        process.launchChat("s04_run", holder)
        world.awaitStatus("s04_run") { it == RunStatus.WAITING_USER }
        assertEquals("the side effect must not run before approval", 0, executions.get())
        val effectId = ledger.getByToolCallId("call_1")!!.effectId

        // The approval card action: flip the part to Approved, resume the SAME runId.
        holder.approveTool("call_1")
        process.launchChat("s04_run", holder)
        world.awaitStatus("s04_run") { it == RunStatus.COMPLETED }

        assertEquals(1, executions.get())
        assertEquals("resume reuses the parked effect", effectId, ledger.getByToolCallId("call_1")!!.effectId)
        assertGolden("s04_approval_granted", world.normalized())
    }

    // =====================================================================
    // Scenario 5 — 用户拒绝审批
    // =====================================================================

    @Test
    fun s05_approval_denied() = runBlocking {
        val world = newWorld()
        val process = world.newProcess()
        val provider = GoldenProvider(
            listOf(
                listOf(toolCallChunk("call_1", TOOL_POST, """{"path":"/tmp/golden.txt"}""")),
                listOf(textChunk("好的，已取消该操作。")),
            ),
        )
        val executions = AtomicInteger(0)
        val holder = world.installChatAgent(
            process, provider,
            tools = listOf(world.postTool(executions)),
            autoApprove = false,
        )

        process.launchChat("s05_run", holder)
        world.awaitStatus("s05_run") { it == RunStatus.WAITING_USER }

        holder.denyTool("call_1", "用户拒绝")
        process.launchChat("s05_run", holder)
        world.awaitStatus("s05_run") { it == RunStatus.COMPLETED }

        assertEquals("a denied tool never executes", 0, executions.get())
        val effect = ledger.getByToolCallId("call_1")!!
        assertEquals(ToolEffectStatus.FAILED, effect.status)
        assertEquals("approval_denied", effect.errorCategory)
        assertGolden("s05_approval_denied", world.normalized())
    }

    // =====================================================================
    // Scenario 6 — 工具执行中进程死亡
    // =====================================================================

    @Test
    fun s06_process_death_mid_tool() = runBlocking {
        val world = newWorld()
        val processA = world.newProcess()
        val provider = GoldenProvider(listOf(listOf(toolCallChunk("call_1", TOOL_POST, """{"id":"e-1"}"""))))
        val executions = AtomicInteger(0)
        // The tool hangs mid-execution; "death" below never releases it.
        val holder = world.installChatAgent(
            processA, provider,
            tools = listOf(world.hangingPostTool(executions)),
            autoApprove = false,
        )

        // Production-faithful: the run parks on the approval card first; only
        // after the user approves does the side effect start (and hang).
        processA.launchChat("s06_run", holder)
        world.awaitStatus("s06_run") { it == RunStatus.WAITING_USER }
        holder.approveTool("call_1")
        processA.launchChat("s06_run", holder)
        withTimeout(20_000) {
            while (ledger.getByToolCallId("call_1")?.status != ToolEffectStatus.STARTED) delay(10)
            while (executions.get() == 0) delay(10)
        }

        // 进程死亡: no cancel, no terminal write — the old object graph is
        // simply abandoned (its hung coroutine parks forever, exactly what a
        // killed process leaves behind). Cold start rebuilds every component
        // over the SAME persisted store and runs recovery + replay, in the
        // AmberAgentApp order.
        world.note("s06_run", "# process_death")
        world.coldStartRecovery()

        assertEquals("recovery must not re-run the side effect", 1, executions.get())
        val effect = ledger.getByToolCallId("call_1")!!
        assertEquals(ToolEffectStatus.OUTCOME_UNKNOWN, effect.status)
        assertEquals(RunTerminalState.OUTCOME_UNKNOWN, runTerminalStore.get("s06_run")!!.state)
        assertGolden("s06_process_death_mid_tool", world.normalized())
    }

    // =====================================================================
    // Scenario 7 — 非幂等工具结果未知 → 用户确认重试 → 同一 effect 完成
    // =====================================================================

    @Test
    fun s07_outcome_unknown_then_retry() = runBlocking {
        val world = newWorld()
        val processA = world.newProcess()
        val provider = GoldenProvider(
            listOf(
                listOf(toolCallChunk("call_1", TOOL_POST, """{"id":"e-1"}""")),
                listOf(textChunk("副作用已生效，任务完成。")),
            ),
        )
        val executions = AtomicInteger(0)
        val holder = world.installChatAgent(
            processA, provider,
            tools = listOf(world.hangingOncePostTool(executions)),
            autoApprove = false,
        )

        // Same approval-first shape as s06: park, approve, then the side
        // effect starts and hangs mid-flight.
        processA.launchChat("s07_run", holder)
        world.awaitStatus("s07_run") { it == RunStatus.WAITING_USER }
        holder.approveTool("call_1")
        processA.launchChat("s07_run", holder)
        withTimeout(20_000) {
            while (ledger.getByToolCallId("call_1")?.status != ToolEffectStatus.STARTED) delay(10)
            while (executions.get() == 0) delay(10)
        }
        world.note("s07_run", "# process_death")
        world.coldStartRecovery()
        val effect = ledger.getByToolCallId("call_1")!!
        assertEquals(ToolEffectStatus.OUTCOME_UNKNOWN, effect.status)
        if (replayDebug) {
            System.err.println(
                "GDBG ${world.ts()} after recovery: runStatus=${world.inMem.runs["s07_run"]?.status} " +
                    "effect=${effect.status}:${effect.effectId.takeLast(6)} " +
                    "terminal=${runTerminalStore.get("s07_run")?.state} executions=${executions.get()}",
            )
        }

        // User confirms retry (ChatService.reconcileOutcomeUnknown parity:
        // ledger reconcile + the tool part reset to Approved with no output),
        // then a fresh process resumes the same runId.
        world.recordingLedger.reconcile(effect.effectId, retry = true)
        holder.messages = holder.messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.toolCallId == "call_1") {
                        part.copy(approvalState = ToolApprovalState.Approved, output = emptyList())
                    } else {
                        part
                    }
                },
            )
        }
        val processB = world.newProcess()
        world.reinstallChatAgent(processB, provider, holder)
        if (replayDebug) {
            System.err.println(
                "GDBG ${world.ts()} before launch B: runStatus=${world.inMem.runs["s07_run"]?.status} " +
                    "effect=${world.recordingLedger.getByToolCallId("call_1")?.let { "${it.status}:${it.errorCategory}" }} " +
                    "terminal=${runTerminalStore.get("s07_run")?.state} holder=" +
                    holder.messages.map { m -> m.id.toString().takeLast(4) + m.getTools().map { "${it.toolCallId}:${it.approvalState::class.simpleName}:out=${it.output.size}" } },
            )
        }
        processB.launchChat("s07_run", holder)
        val statusB = world.awaitStatus("s07_run") { it == RunStatus.COMPLETED }
        if (replayDebug) {
            System.err.println(
                "GDBG ${world.ts()} after launch B: status=$statusB executions=${executions.get()} " +
                    "effect=${world.recordingLedger.getByToolCallId("call_1")?.let { "${it.status}:${it.effectId.takeLast(6)}" }} " +
                    "terminal=${runTerminalStore.get("s07_run")?.state}",
            )
        }

        assertEquals("exactly one confirmed retry", 2, executions.get())
        assertEquals(
            "the SAME effectId reaches FINISHED — no duplicate effect",
            effect.effectId,
            ledger.getByToolCallId("call_1")!!.effectId,
        )
        assertEquals(ToolEffectStatus.FINISHED, ledger.get(effect.effectId)!!.status)
        assertGolden("s07_outcome_unknown_then_retry", world.normalized())
    }

    // =====================================================================
    // Scenario 8 — 流式中插入 steer
    // =====================================================================

    @Test
    fun s08_steer_mid_stream() = runBlocking {
        val world = newWorld()
        val process = world.newProcess()
        val provider = GoldenProvider(
            listOf(
                listOf(toolCallChunk("call_1", TOOL_LOOKUP_A, """{"q":"琥珀"}""")),
                listOf(textChunk("已按你的补充继续。")),
            ),
        )
        val steer = UIMessage.user("补充：继续写第二章")
        var steerServed = false
        val holder = world.installChatAgent(
            process, provider,
            tools = listOf(world.lookupTool(TOOL_LOOKUP_A)),
            consumeSteer = {
                if (steerServed) emptyList() else {
                    steerServed = true
                    // Script annotation (see README vocabulary), emitted at
                    // the injection point — the moment the steer list is
                    // served to the kernel.
                    world.note("s08_run", "steer_injected count=1")
                    listOf(steer)
                }
            },
        )

        process.launchChat("s08_run", holder)
        world.awaitStatus("s08_run") { it == RunStatus.COMPLETED }

        assertTrue(
            "the round after the tool must carry the steer to the provider",
            provider.received.last().any { it.id == steer.id },
        )
        // Step 5 audit closes the loop end-to-end: the steer the model
        // actually received is provably logged in the second round's
        // snapshot preview.
        val snapshots = requestSnapshotsOf(world, "s08_run")
        assertEquals(2, snapshots.size)
        assertEquals(listOf(0, 1), snapshots.map { it.stepIndex })
        assertTrue(
            "the post-steer snapshot's preview must contain the steer text the model saw",
            snapshots[1].renderedPreview.contains("补充：继续写第二章"),
        )
        assertGolden("s08_steer_mid_stream", world.normalized())
    }

    // =====================================================================
    // Scenario 9 — 达到 step limit（STEP_LIMIT 是终态，绝不映射 COMPLETED）
    // =====================================================================

    @Test
    fun s09_step_limit() = runBlocking {
        val world = newWorld()
        val process = world.newProcess()
        val provider = GoldenProvider(
            listOf(
                listOf(toolCallChunk("call_1", TOOL_LOOKUP_A, """{"q":"1"}""")),
                listOf(toolCallChunk("call_2", TOOL_LOOKUP_A, """{"q":"2"}""")),
                listOf(toolCallChunk("call_3", TOOL_LOOKUP_A, """{"q":"3"}""")),
                listOf(textChunk("不可达：step limit 必须先触发")),
            ),
        )
        val holder = world.installChatAgent(
            process, provider,
            tools = listOf(world.lookupTool(TOOL_LOOKUP_A)),
            maxSteps = 3,
        )

        process.launchChat("s09_run", holder)
        world.awaitStatus("s09_run") { it == RunStatus.STEP_LIMIT }

        assertEquals(RunTerminalState.STEP_LIMIT, runTerminalStore.get("s09_run")!!.state)
        // Step 5 audit: one snapshot per step's model round, in loop order.
        val snapshots = requestSnapshotsOf(world, "s09_run")
        assertEquals(3, snapshots.size)
        assertEquals(listOf(0, 1, 2), snapshots.map { it.stepIndex })
        assertGolden("s09_step_limit", world.normalized())
    }

    // =====================================================================
    // Scenario 10 — 子代理向父代理回传消息
    // =====================================================================

    @Test
    fun s10_subagent_reports_to_parent() = runBlocking {
        val world = newWorld()
        val process = world.newProcess()
        process.installChildAgent()
        val provider = GoldenProvider(
            listOf(
                listOf(toolCallChunk("call_1", TOOL_DELEGATE, """{"prompt":"调研琥珀"}""")),
                listOf(textChunk("子代理已回报。")),
            ),
        )
        val delegateTool = Tool(
            name = TOOL_DELEGATE,
            description = "golden sub-agent delegation",
            execute = { input ->
                val prompt = input.jsonObject["prompt"]?.jsonPrimitive?.content.orEmpty()
                val childRunId = AgentRunId.new()
                process.runner.launch(
                    GoldenChildDescriptor.value.id,
                    GoldenChildInput(parentRunId = "s10_parent", prompt = prompt),
                    childRunId,
                ).getOrThrow()
                // Await the child's PERSISTED terminal so the parent's
                // tool_finished can never precede the child's run_terminal.
                world.awaitStatus(childRunId.value) { it?.isTerminal == true }
                val artifact = process.runner.observe(childRunId).value.artifact as GoldenChildArtifact
                listOf(UIMessagePart.Text("""{"reply":"${artifact.reply}"}"""))
            },
        )
        val holder = world.installChatAgent(process, provider, tools = listOf(delegateTool))

        process.launchChat("s10_parent", holder)
        world.awaitStatus("s10_parent") { it == RunStatus.COMPLETED }

        assertGolden("s10_subagent_reports_to_parent", world.normalized())
    }

    // =====================================================================
    // harness — real production components, fakes only at the boundaries
    // =====================================================================

    private suspend fun newWorld(): GoldenWorld {
        val flags = CapabilityFlags(
            PreferenceDataStoreFactory.create {
                File(context.cacheDir, "golden-flags-${Uuid.random()}.preferences_pb")
            },
        )
        flags.setEnabled(Capability.DurableToolEffects, true)
        flags.setEnabled(Capability.TypedRunTerminal, true)
        return GoldenWorld(flags)
    }

    /** Mutable conversation view the handler re-reads per (re)launch — the
     *  same role ChatSessionResolver's conversation read plays in production. */
    private class ConversationHolder(
        val conversation: Conversation,
        var messages: List<UIMessage>,
    ) {
        /** The approval card action: flip the matching tool part to Approved. */
        fun approveTool(toolCallId: String) = flipTool(toolCallId, ToolApprovalState.Approved)

        fun denyTool(toolCallId: String, reason: String) = flipTool(toolCallId, ToolApprovalState.Denied(reason))

        private fun flipTool(toolCallId: String, state: ToolApprovalState) {
            if (replayDebug) {
                System.err.println(
                    "GDBG flip $toolCallId -> ${state::class.simpleName}: messages=" + messages.map { m ->
                        m.id.toString().takeLast(4) + m.getTools().map { "${it.toolCallId}:${it.approvalState::class.simpleName}:out=${it.output.size}" }
                    },
                )
            }
            messages = messages.map { message ->
                message.copy(
                    parts = message.parts.map { part ->
                        if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                            part.copy(approvalState = state)
                        } else {
                            part
                        }
                    },
                )
            }
        }
    }

    private inner class GoldenWorld(
        val flags: CapabilityFlags,
    ) {
        val createdAt = System.currentTimeMillis()
        fun ts() = (System.currentTimeMillis() - createdAt).toString().padStart(6)
        val launchSeq = AtomicInteger(0)
        val inMem = InMemoryAgentEventStore()
        val store = RecordingAgentEventStore(inMem, json, { ts() })
        val recordingLedger = RecordingLedger(ledger, store)
        private val toolExecutions = mutableMapOf<String, AtomicInteger>()
        private val barrieredTools = mutableSetOf<String>()
        private val barrierEntered = AtomicInteger(0)
        private val barrierRelease = CompletableDeferred<Unit>()

        val codecs: Map<String, AgentEventPayloadCodec<*>> = mapOf(
            ToolLifecycleEvent.Prepared::class.qualifiedName!! to
                AgentEventPayloadCodec(ToolLifecycleEvent.TYPE_PREPARED, ToolLifecycleEvent.Prepared.serializer()),
            ToolLifecycleEvent.Started::class.qualifiedName!! to
                AgentEventPayloadCodec(ToolLifecycleEvent.TYPE_STARTED, ToolLifecycleEvent.Started.serializer()),
            ToolLifecycleEvent.Finished::class.qualifiedName!! to
                AgentEventPayloadCodec(ToolLifecycleEvent.TYPE_FINISHED, ToolLifecycleEvent.Finished.serializer()),
            ChatEventPayload.AssistantMessageFinalized::class.qualifiedName!! to
                AgentEventPayloadCodec("AssistantMessageFinalized", ChatEventPayload.AssistantMessageFinalized.serializer()),
            // Step 5: request snapshots persist like any registered Final; the
            // recorder's normalization `else -> Unit` keeps goldens untouched.
            ChatEventPayload.RequestSnapshot::class.qualifiedName!! to
                AgentEventPayloadCodec(ChatEventPayload.RequestSnapshot.TYPE, ChatEventPayload.RequestSnapshot.serializer()),
        )

        fun executionsOf(toolName: String): AtomicInteger =
            toolExecutions.getOrPut(toolName) { AtomicInteger(0) }

        fun note(runId: String, line: String) = store.note(runId, line)

        private fun writerFor(runId: AgentRunId, input: AgentInput) = PersistingEventWriter(
            runId = runId,
            parentRunId = (input as? GoldenChildInput)?.parentRunId?.let(::AgentRunId),
            agentDescriptorId = if (input is GoldenChildInput) "golden_child" else "golden_chat",
            store = store,
            json = json,
            codecs = codecs,
        )

        inner class Process {
            val registry = InMemoryAgentRegistry()
            val runner = InProcessAgentRunner(
                registry = registry,
                eventStore = store,
                runScopeFactory = { runId, input ->
                    LegacyRunScope(runId = runId, events = writerFor(runId, input))
                },
            )

            fun installChatAgent(agent: GoldenChatAgent) {
                registry.register(
                    descriptor = GoldenChatDescriptor.value,
                    inputClass = GoldenTurnInput::class,
                    inputSerializer = GoldenTurnInput.serializer(),
                    artifactSerializer = GoldenTurnArtifact.serializer(),
                    factory = { agent },
                )
            }

            fun installChildAgent() {
                registry.register(
                    descriptor = GoldenChildDescriptor.value,
                    inputClass = GoldenChildInput::class,
                    inputSerializer = GoldenChildInput.serializer(),
                    artifactSerializer = GoldenChildArtifact.serializer(),
                    factory = { GoldenChildAgent() },
                )
            }

            suspend fun launchChat(runId: String, holder: ConversationHolder) {
                // Caller arms the terminal row on every (re)launch. Fidelity
                // note: this begin() is issued caller-side with assistantId
                // =null, whereas production issues it in the kernel
                // run-started callback with AMBER_AGENT_ID (ChatService) —
                // harmless because run_terminal rows never enter
                // normalization.
                runTerminalStore.begin(runId, holder.conversation.id.toString(), null)
                runner.launch(
                    GoldenChatDescriptor.value.id,
                    GoldenTurnInput(
                        conversationId = holder.conversation.id.toString(),
                        messageNodeId = "node-1",
                    ),
                    AgentRunId(runId),
                ).getOrThrow()
            }
        }

        fun newProcess() = Process()

        /** Build the real kernel chain over this world's ledger/flags. */
        fun buildKernel(provider: GoldenProvider): DefaultRunKernel {
            val httpClient = OkHttpClient()
            val providerCatalog = ProviderCatalog(
                openAIProvider = app.amber.ai.provider.providers.OpenAIProvider(httpClient, context),
                googleProvider = app.amber.ai.provider.providers.GoogleProvider(httpClient, context),
                claudeProvider = app.amber.ai.provider.providers.ClaudeProvider(httpClient, context),
                openAITextGateway = provider,
                openAIImageGateway = provider,
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
                toolEffectLedger = recordingLedger,
                capabilityFlags = flags,
                capabilityPermissionStore = null,
            )
        }

        suspend fun installChatAgent(
            process: Process,
            provider: GoldenProvider,
            tools: List<Tool>,
            autoApprove: Boolean = false,
            maxSteps: Int = 8,
            consumeSteer: suspend () -> List<UIMessage> = { emptyList() },
            userText: String = "开始",
        ): ConversationHolder {
            val model = Model(modelId = "golden-model", displayName = "Golden Model", contextWindowTokens = 128_000)
            val providerSetting = ProviderSetting.OpenAI(
                id = Uuid.random(),
                name = "Golden OpenAI",
                models = listOf(model),
                baseUrl = "https://api.openai.com/v1",
            )
            val settings = Settings(
                providers = listOf(providerSetting),
                systemPrompt = "You are the golden assistant.",
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
            val userMessage = UIMessage.user(userText)
            val conversation = Conversation(
                id = Uuid.random(),
                assistantId = AMBER_AGENT_ID,
                messageNodes = listOf(MessageNode.of(userMessage)),
            )
            conversationRepository().insertConversation(conversation)
            val holder = ConversationHolder(conversation, conversation.currentMessages)
            lastSettings = settings
            lastModel = model
            lastTools = tools
            lastAutoApprove = autoApprove
            lastMaxSteps = maxSteps
            lastConsumeSteer = consumeSteer
            process.installChatAgent(
                GoldenChatAgent(
                    kernel = buildKernel(provider),
                    holder = holder,
                    settings = settings,
                    model = model,
                    tools = tools,
                    autoApprove = autoApprove,
                    maxSteps = maxSteps,
                    consumeSteer = consumeSteer,
                ),
            )
            return holder
        }

        /** Process B reuses the persisted conversation/holder, the same
         *  settings/model/tools and the same provider (the server survives a
         *  client death) over a fresh kernel. */
        suspend fun reinstallChatAgent(process: Process, provider: GoldenProvider, holder: ConversationHolder) {
            val agent = GoldenChatAgent(
                kernel = buildKernel(provider),
                holder = holder,
                settings = lastSettings ?: error("installChatAgent must run first"),
                model = lastModel ?: error("installChatAgent must run first"),
                tools = lastTools ?: error("installChatAgent must run first"),
                autoApprove = lastAutoApprove,
                maxSteps = lastMaxSteps,
                consumeSteer = lastConsumeSteer,
            )
            process.installChatAgent(agent)
        }

        private var lastSettings: Settings? = null
        private var lastModel: Model? = null
        private var lastTools: List<Tool>? = null
        private var lastAutoApprove: Boolean = false
        private var lastMaxSteps: Int = 8
        private var lastConsumeSteer: suspend () -> List<UIMessage> = { emptyList() }

        fun lookupTool(name: String): Tool {
            val counter = executionsOf(name)
            return Tool(
                name = name,
                description = "golden read-only lookup",
                execute = {
                    counter.incrementAndGet()
                    listOf(UIMessagePart.Text("""{"status":"ok"}"""))
                },
            )
        }

        /** Two barriered tools provably overlap in a parallel batch. */
        fun barrieredLookupTool(name: String): Tool {
            barrieredTools += name
            val counter = executionsOf(name)
            return Tool(
                name = name,
                description = "golden read-only lookup (barriered)",
                execute = {
                    counter.incrementAndGet()
                    if (barrieredTools.size == 2 &&
                        barrierEntered.incrementAndGet() == 2
                    ) {
                        barrierRelease.complete(Unit)
                    }
                    barrierRelease.await()
                    listOf(UIMessagePart.Text("""{"status":"ok"}"""))
                },
            )
        }

        fun postTool(executions: AtomicInteger): Tool = Tool(
            name = TOOL_POST,
            description = "golden non-idempotent side effect",
            execute = {
                executions.incrementAndGet()
                listOf(UIMessagePart.Text("""{"status":"ok","written":true}"""))
            },
        )

        /** A tool whose first execution dies with the process (hangs forever);
         *  the confirmed retry re-runs the body and succeeds. */
        fun hangingOncePostTool(executions: AtomicInteger): Tool {
            val never = CompletableDeferred<Unit>()
            return Tool(
                name = TOOL_POST,
                description = "golden non-idempotent side effect (first attempt hangs)",
                execute = {
                    if (executions.incrementAndGet() == 1) {
                        never.await()
                        throw AssertionError("a dead process must never finish its tool")
                    }
                    listOf(UIMessagePart.Text("""{"status":"ok","retried":true}"""))
                },
            )
        }

        /** A tool that starts and never returns — the process dies around it. */
        fun hangingPostTool(executions: AtomicInteger): Tool {
            val never = CompletableDeferred<Unit>()
            return Tool(
                name = TOOL_POST,
                description = "golden non-idempotent side effect (hangs)",
                execute = {
                    executions.incrementAndGet()
                    never.await()
                    throw AssertionError("a dead process must never finish its tool")
                },
            )
        }

        /** Cold-start recovery over the same persisted store, in app order.
         *  Fidelity note: storedResponseGateway/capabilityFlags/resumeStore
         *  are omitted, so RunRecoveryService's P6-01 stored-response branch
         *  is not exercised here. */
        suspend fun coldStartRecovery() {
            val recovery = RunRecoveryService(
                ledger = recordingLedger,
                runTerminalStore = runTerminalStore,
                conversationRepo = conversationRepository(),
                json = json,
                agentEventStore = store,
            )
            recovery.recover()
            // replayUnfinished runs after recover() in AmberAgentApp; pause
            // states are skipped by design, CREATED/RUNNING rows get marked.
            ChatEventProjector(store, conversationRepository(), FakeConversationAccess(), json)
                .replayUnfinished()
        }

        suspend fun awaitStatus(
            runId: String,
            timeoutMs: Long = 20_000,
            predicate: (RunStatus?) -> Boolean,
        ): RunStatus = withTimeout(timeoutMs) {
            while (true) {
                val status = inMem.runs[runId]?.status
                if (status != null && predicate(status)) return@withTimeout status
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

        fun normalized(): String = store.normalized()

        // The kernel-terminal → two-store write, mirroring ChatService.onTerminal.
        suspend fun publishKernelTerminal(runId: String, terminal: GenerationTerminal) {
            if (replayDebug) {
                System.err.println("GDBG ${ts()} publishKernelTerminal $runId terminal=$terminal")
            }
            when (terminal) {
                GenerationTerminal.WaitingUser -> {
                    runTerminalStore.pause(runId, RunTerminalState.WAITING_USER, PauseReason.TOOL_APPROVAL)
                    store.transitionRun(
                        AgentRunId(runId),
                        RunStatus.LIVE_STATES,
                        RunStatus.WAITING_USER,
                    )
                }
                GenerationTerminal.StepLimit -> {
                    runTerminalStore.finish(runId, RunTerminalState.STEP_LIMIT, PauseReason.STEP_LIMIT_EXHAUSTED)
                    store.transitionRun(
                        AgentRunId(runId),
                        RunStatus.LIVE_STATES,
                        RunStatus.STEP_LIMIT,
                    )
                }
                GenerationTerminal.OutputLimit -> {
                    runTerminalStore.finish(runId, RunTerminalState.OUTPUT_LIMIT, PauseReason.OUTPUT_LIMIT_REACHED)
                    store.transitionRun(
                        AgentRunId(runId),
                        RunStatus.LIVE_STATES,
                        RunStatus.OUTPUT_LIMIT,
                    )
                }
                is GenerationTerminal.GuardStopped -> {
                    runTerminalStore.finish(runId, RunTerminalState.GUARD_STOPPED, PauseReason.DUPLICATE_TOOL_CALL)
                    store.transitionRun(
                        AgentRunId(runId),
                        RunStatus.LIVE_STATES,
                        RunStatus.GUARD_STOPPED,
                    )
                }
            }
        }

        // The caller-side terminal publish, mirroring ChatService.onRunFinished.
        // Fidelity note: onRunFinished's `parked` branch is not modeled here.
        suspend fun publishCallerTerminal(runId: String, cause: Throwable?) {
            val (state, reason) = terminalForFlowEnd(cause, null)
            if (replayDebug) {
                System.err.println(
                    "GDBG ${ts()} publishCallerTerminal $runId state=$state reason=$reason " +
                        "cause=${cause?.let { it::class.simpleName + ": " + it.message }}",
                )
            }
            when (state) {
                RunTerminalState.WAITING_USER -> runTerminalStore.pause(runId, state, reason)
                else -> runTerminalStore.finish(runId, state, reason)
            }
        }

        inner class GoldenChatAgent(
            private val kernel: DefaultRunKernel,
            private val holder: ConversationHolder,
            private val settings: Settings,
            private val model: Model,
            private val tools: List<Tool>,
            private val autoApprove: Boolean,
            private val maxSteps: Int,
            private val consumeSteer: suspend () -> List<UIMessage>,
        ) : Agent<GoldenTurnInput, GoldenTurnArtifact> {
            override val descriptor: AgentDescriptor get() = GoldenChatDescriptor.value

            override val handler = AgentHandler<GoldenTurnInput, GoldenTurnArtifact> { input, scope ->
                val runId = scope.runId.value
                val launch = launchSeq.incrementAndGet()
                if (replayDebug) {
                    System.err.println(
                        "GDBG ${ts()} handler start $runId launch=$launch maxSteps=$maxSteps: messages=" + holder.messages.map { m ->
                            m.id.toString().takeLast(4) + m.getTools().map { "${it.toolCallId}:${it.approvalState::class.simpleName}:out=${it.output.size}" }
                        },
                    )
                }
                var kernelTerminal: GenerationTerminal? = null
                var failure: Throwable? = null
                try {
                    kernel.run(
                        GenerationRunSession(
                            settings = settings,
                            model = model,
                            messages = holder.messages,
                            tools = tools,
                            maxSteps = maxSteps,
                            autoApproveTools = autoApprove,
                            invocationContext = ToolInvocationContext.Normal,
                            conversation = holder.conversation,
                            consumeSteerMessages = consumeSteer,
                            runId = runId,
                            onTerminal = { terminal ->
                                kernelTerminal = terminal
                                publishKernelTerminal(runId, terminal)
                            },
                            toolLifecycleEvents = scope.events,
                        ),
                    ).collect { chunk ->
                        if (chunk is GenerationChunk.Messages) {
                            holder.messages = chunk.messages
                            if (replayDebug) {
                                System.err.println(
                                    "GDBG collect $runId launch=$launch: messages=" + chunk.messages.map { m ->
                                        m.id.toString().takeLast(4) + m.getTools().map { "${it.toolCallId}:${it.approvalState::class.simpleName}:out=${it.output.size}" }
                                    },
                                )
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failure = error
                }
                if (replayDebug) {
                    System.err.println(
                        "GDBG ${ts()} handler end $runId launch=$launch kernelTerminal=$kernelTerminal " +
                            "failure=${failure?.let { it::class.simpleName + ": " + it.message }} " +
                            "inMemStatus=${inMem.runs[runId]?.status}",
                    )
                }
                if (kernelTerminal == null) {
                    publishCallerTerminal(runId, failure)
                }
                failure?.let { throw it }
                if (kernelTerminal == null) {
                    // The turn produced a final answer — the same
                    // AssistantMessageFinalized ChatTurnAgent commits.
                    scope.events.commit(
                        ChatEventPayload.AssistantMessageFinalized(
                            messageNodeId = MessageNodeId(input.messageNodeId),
                            messageId = "golden-final-$runId",
                            inputTokens = 0,
                            outputTokens = 0,
                            regenerateOf = null,
                        ),
                    )
                }
                GoldenTurnArtifact(finalText = holder.messages.lastOrNull()?.toText().orEmpty())
            }
        }

        inner class GoldenChildAgent : Agent<GoldenChildInput, GoldenChildArtifact> {
            override val descriptor: AgentDescriptor get() = GoldenChildDescriptor.value

            override val handler = AgentHandler<GoldenChildInput, GoldenChildArtifact> { input, scope ->
                // The child's produced message — its event rows carry the
                // parentRunId through the scope's writer.
                scope.events.commit(
                    ChatEventPayload.AssistantMessageFinalized(
                        messageNodeId = MessageNodeId("child-node"),
                        messageId = "golden-child-msg",
                        inputTokens = 0,
                        outputTokens = 0,
                        regenerateOf = null,
                    ),
                )
                GoldenChildArtifact(reply = "子代理结论：${input.prompt}")
            }
        }
    }

    // ---------- chunk / message helpers (same shapes as the canary) ----------

    /**
     * Step 5 audit helper: decode every persisted RequestSnapshot payload of
     * a run through the harness Json instance, in seq order.
     */
    private suspend fun requestSnapshotsOf(
        world: GoldenWorld,
        runId: String,
    ): List<ChatEventPayload.RequestSnapshot> =
        world.inMem.listEvents(AgentRunId(runId))
            .filter { it.type == ChatEventPayload.RequestSnapshot.TYPE }
            .mapNotNull { record ->
                runCatching {
                    json.decodeFromString(ChatEventPayload.RequestSnapshot.serializer(), record.payload)
                }.getOrNull()
            }

    private fun textChunk(text: String): MessageChunk = assistantChunk(listOf(UIMessagePart.Text(text)))

    private fun toolCallChunk(toolCallId: String, toolName: String, input: String): MessageChunk =
        assistantChunk(listOf(UIMessagePart.Tool(toolCallId = toolCallId, toolName = toolName, input = input)))

    private fun assistantChunk(parts: List<UIMessagePart>): MessageChunk =
        MessageChunk(
            id = "golden_chunk",
            model = "golden-model",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
                    message = null,
                    finishReason = null,
                ),
            ),
        )

    // ---------- golden assertion ----------

    private fun assertGolden(scenario: String, body: String) {
        val actual = "# amber-replay-golden schema=v1 scenario=$scenario\n$body"
        if (System.getProperty("updateGoldens") == "true") {
            val file = File(System.getProperty("goldenDir"), "replay/v1/$scenario.golden")
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        val resource = javaClass.classLoader.getResource("replay/v1/$scenario.golden")
            ?: throw AssertionError(
                "missing golden for $scenario — regenerate with " +
                    ":app:testDebugUnitTest --tests \"app.amber.agent.replay.*\" -PupdateGoldens=true",
            )
        org.junit.Assert.assertEquals(
            "replay golden mismatch for $scenario (intentional change? regen with -PupdateGoldens=true)",
            resource.readText(),
            actual,
        )
    }

    // ---------- payload types ----------

    @Serializable
    data class GoldenTurnInput(
        val conversationId: String,
        val messageNodeId: String,
    ) : AgentInput

    @Serializable
    data class GoldenTurnArtifact(val finalText: String) : AgentArtifact

    @Serializable
    data class GoldenChildInput(
        val parentRunId: String,
        val prompt: String,
    ) : AgentInput

    @Serializable
    data class GoldenChildArtifact(val reply: String) : AgentArtifact

    private object GoldenChatDescriptor {
        val value = AgentDescriptor(
            id = AgentDescriptorId("golden_chat"),
            version = "1.0.0",
            displayName = "Golden Chat",
            capabilities = setOf(AgentCapability.CHAT_TURN),
        )
    }

    private object GoldenChildDescriptor {
        val value = AgentDescriptor(
            id = AgentDescriptorId("golden_child"),
            version = "1.0.0",
            displayName = "Golden Child",
            capabilities = setOf(AgentCapability.SUB_AGENT),
        )
    }

    // ---------- fakes at the boundary ----------

    /** Round-scripted provider: stream() call N serves rounds[N]. The same
     *  instance survives simulated process death (it models the server). */
    private class GoldenProvider(
        private val rounds: List<List<MessageChunk>>,
    ) : TextModelGateway<ProviderSetting.OpenAI>, ImageModelGateway<ProviderSetting.OpenAI> {

        val received = mutableListOf<List<UIMessage>>()
        private val served = AtomicInteger(0)

        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun complete(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): MessageChunk = error("golden replay uses the streaming path only")

        override suspend fun stream(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<MessageChunk> = flow {
            received += messages
            val round = rounds.getOrNull(served.getAndIncrement())
                ?: error("GoldenProvider: no scripted round for this request")
            round.forEach { emit(it) }
        }

        override suspend fun generateImage(
            providerSetting: ProviderSetting.OpenAI,
            params: ImageGenerationParams,
        ): ImageGenerationResult = error("not used")
    }

    private class FakeConversationAccess : ConversationAccess {
        override fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> =
            MutableStateFlow(Conversation(id = conversationId, assistantId = AMBER_AGENT_ID, messageNodes = emptyList()))

        override fun getConversationFlowOrNull(conversationId: Uuid): StateFlow<Conversation>? = null

        override fun updateConversation(
            conversationId: Uuid,
            conversation: Conversation,
            checkDeletedFiles: Boolean,
        ) = Unit

        override suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) = Unit

        override fun addError(error: Throwable, conversationId: Uuid?, title: String?) = Unit
    }

    /**
     * Records every run transition / event append / harness note into one
     * monotonic log, so the normalized sequence is read off a single causal
     * order. Tool events keep their toolCallId for the parallel-window
     * collapse; ids/timestamps never reach the golden text.
     */
    private class RecordingAgentEventStore(
        private val delegate: InMemoryAgentEventStore,
        private val json: Json,
        private val stamp: () -> String = { "" },
    ) : AgentEventStore {

        enum class Kind { RUN, TOOL_PREPARED, TOOL_STARTED, TOOL_FINISHED, ASSISTANT, NOTE }

        data class Entry(
            val runId: String,
            val order: Long,
            val kind: Kind,
            val toolCallId: String?,
            val parentRunId: String?,
            val line: String,
        )

        private val tick = AtomicLong(0)
        private val _log = mutableListOf<Entry>()

        private fun record(runId: String, kind: Kind, toolCallId: String?, parentRunId: String?, line: String) {
            synchronized(_log) {
                _log += Entry(runId, tick.getAndIncrement(), kind, toolCallId, parentRunId, line)
            }
        }

        fun note(runId: String, line: String) = record(runId, Kind.NOTE, null, null, line)

        override suspend fun appendRun(run: AgentRunRecord): Boolean {
            val isNew = !delegate.runs.containsKey(run.runId)
            val created = delegate.appendRun(run)
            if (isNew && created) record(run.runId, Kind.RUN, null, run.parentRunId, "run_started")
            return created
        }

        override suspend fun appendEvent(event: AgentEventRecord) = delegate.appendEvent(event)

        override suspend fun appendEventAllocatingSeq(event: AgentEventRecord): AgentEventRecord {
            val stored = delegate.appendEventAllocatingSeq(event)
            val payload = runCatching { json.parseToJsonElement(stored.payload).jsonObject }.getOrNull()
            val toolName = payload?.get("toolName")?.jsonPrimitive?.content
            val toolCallId = payload?.get("toolCallId")?.jsonPrimitive?.content
            when (stored.type) {
                ToolLifecycleEvent.TYPE_PREPARED ->
                    record(stored.runId, Kind.TOOL_PREPARED, toolCallId, stored.parentRunId, "tool_prepared tool=$toolName")
                ToolLifecycleEvent.TYPE_STARTED ->
                    record(stored.runId, Kind.TOOL_STARTED, toolCallId, stored.parentRunId, "tool_started tool=$toolName")
                ToolLifecycleEvent.TYPE_FINISHED -> {
                    val status = payload?.get("status")?.jsonPrimitive?.content?.lowercase()
                    record(stored.runId, Kind.TOOL_FINISHED, toolCallId, stored.parentRunId, "tool_finished tool=$toolName status=$status")
                }
                "AssistantMessageFinalized" ->
                    record(stored.runId, Kind.ASSISTANT, null, stored.parentRunId, "assistant_message")
                else -> Unit // checkpoints and future payload kinds are out of the v1 vocabulary
            }
            return stored
        }

        override suspend fun transitionRun(
            runId: AgentRunId,
            expected: Set<RunStatus>,
            to: RunStatus,
            reason: String?,
        ): RunTransitionResult {
            val result = delegate.transitionRun(runId, expected, to, reason)
            // Diagnostic for the InProcessAgentRunner stale-terminal race: a
            // COMPLETED that lands from=RUNNING after a newer resume, or any
            // COMPLETED rejected by a pause, is the fingerprint of that race.
            if (replayDebug && to == RunStatus.COMPLETED) {
                System.err.println("GDBG ${stamp()} store.transitionRun ${runId.value} -> COMPLETED => $result")
            }
            val applied = result as? RunTransitionResult.Applied ?: return result
            if (applied.from == applied.to) return result // idempotent re-assert, not a line
            // CREATED→RUNNING is unreachable in this harness (runs are only
            // ever seen paused/terminal from the kernel), so the fallthrough
            // is a no-op — no v1 vocabulary line exists for it.
            when {
                to == RunStatus.RUNNING && applied.from in RunStatus.PAUSE_STATES ->
                    record(runId.value, Kind.RUN, null, null, "run_resumed")
                to.isPause -> record(runId.value, Kind.RUN, null, null, "run_paused state=${to.wireName}")
                to.isTerminal -> record(runId.value, Kind.RUN, null, null, "run_terminal status=${to.wireName}")
                else -> Unit
            }
            return result
        }

        override suspend fun markInterrupted(runId: AgentRunId, reason: String) {
            delegate.markInterrupted(runId, reason)
            // Room's markInterrupted writes the row; the in-memory delegate
            // only lists it — record the transition the production store makes.
            record(runId.value, Kind.RUN, null, null, "run_terminal status=interrupted")
        }

        override suspend fun appendSpan(span: TraceSpanRecord) = delegate.appendSpan(span)
        override fun observeRun(runId: AgentRunId): Flow<AgentRunSnapshot> = delegate.observeRun(runId)
        override suspend fun listEvents(runId: AgentRunId): List<AgentEventRecord> = delegate.listEvents(runId)
        override suspend fun deleteEventsByType(runId: AgentRunId, type: String) = delegate.deleteEventsByType(runId, type)
        override suspend fun listUnfinishedRuns(): List<AgentRunRecord> = delegate.listUnfinishedRuns()

        /**
         * Normalization (schema v1 — see test-fixtures/replay/README.md):
         * group by run in first-appearance order, aliasing every run
         * uniformly (first = `root`, then `run_2`, `run_3`, …), then emit
         * entries in log order, collapsing each maximal window of
         * tool-lifecycle entries whose EXECUTION spans overlap (a parallel
         * batch) into prepared (log order) → started (toolCallId order) →
         * finished (toolCallId order). Sequential rounds keep log order
         * verbatim because their execution spans never overlap. A golden
         * with more than one run opens every section with `## run <alias>`
         * (root included), suffixed `parent=<parentAlias>` when the run's
         * events carry a parentRunId; single-run goldens keep no header.
         */
        fun normalized(): String {
            val entries = synchronized(_log) { _log.sortedBy { it.order } }
            val runOrder = entries.map { it.runId }.distinct()
            val aliases = runOrder.mapIndexed { index, id -> id to if (index == 0) "root" else "run_${index + 1}" }.toMap()
            val toolKinds = setOf(Kind.TOOL_PREPARED, Kind.TOOL_STARTED, Kind.TOOL_FINISHED)
            val sb = StringBuilder()
            for (runId in runOrder) {
                val runEntries = entries.filter { it.runId == runId }
                if (runOrder.size > 1) {
                    val parent = runEntries.mapNotNull { it.parentRunId }.firstOrNull()
                    sb.append("## run ${aliases[runId]}")
                    if (parent != null) sb.append(" parent=${aliases[parent] ?: parent}")
                    sb.append('\n')
                }
                var i = 0
                while (i < runEntries.size) {
                    if (runEntries[i].kind !in toolKinds) {
                        sb.append(runEntries[i].line).append('\n')
                        i++
                        continue
                    }
                    var end = i
                    val window = mutableListOf<Entry>()
                    while (end < runEntries.size && runEntries[end].kind in toolKinds) {
                        window += runEntries[end]
                        end++
                    }
                    // Execution spans (Started..Finished within the window) decide
                    // whether this is a parallel batch.
                    val execSpans = window.mapIndexedNotNull { index, entry ->
                        if (entry.kind == Kind.TOOL_STARTED) entry.toolCallId to index else null
                    }.toMap().mapValues { (callId, startIdx) ->
                        val endIdx = window.indexOfLast { it.toolCallId == callId && it.kind == Kind.TOOL_FINISHED }
                        startIdx to if (endIdx < 0) window.size - 1 else endIdx
                    }
                    val spans = execSpans.values.toList()
                    val parallel = spans.any { a -> spans.any { b -> a !== b && a.first < b.first && b.first <= a.second } }
                    if (!parallel) {
                        window.forEach { sb.append(it.line).append('\n') }
                    } else {
                        window.filter { it.kind == Kind.TOOL_PREPARED }.forEach { sb.append(it.line).append('\n') }
                        window.filter { it.kind == Kind.TOOL_STARTED }.sortedBy { it.toolCallId }
                            .forEach { sb.append(it.line).append('\n') }
                        window.filter { it.kind == Kind.TOOL_FINISHED }.sortedBy { it.toolCallId }
                            .forEach { sb.append(it.line).append('\n') }
                    }
                    i = end
                }
            }
            return sb.toString()
        }
    }

    /** Ledger wrapper that turns recovery/confirm writes into causal notes. */
    private inner class RecordingLedger(
        private val delegate: ToolEffectLedger,
        private val store: RecordingAgentEventStore,
    ) : ToolEffectLedger by delegate {

        override suspend fun markOutcomeUnknown(effectId: String, errorCategory: String) {
            delegate.markOutcomeUnknown(effectId, errorCategory)
            delegate.get(effectId)?.let { effect ->
                store.note(effect.runId ?: "unknown", "tool_outcome_unknown tool=${effect.toolName}")
            }
        }

        override suspend fun reconcile(effectId: String, retry: Boolean, abandonOutput: List<UIMessagePart>) {
            delegate.reconcile(effectId, retry, abandonOutput)
            delegate.get(effectId)?.let { effect ->
                store.note(
                    effect.runId ?: "unknown",
                    "tool_reconciled tool=${effect.toolName} decision=${if (retry) "retry" else "abandoned"}",
                )
            }
        }
    }
}
