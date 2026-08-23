package app.amber.feature.subagent

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.agent.data.files.CasTestFixtures
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.ai.GenerationTerminal
import app.amber.core.infra.AppScope
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.history.SessionAccessGrantStore
import app.amber.feature.runtime.DurableRuntimeTestBase
import app.amber.feature.runtime.RoomThreadGraphStore
import app.amber.feature.task.AgentTaskStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P4-02 acceptance tests through the real production chain (SubAgentManager +
 * Room thread graph store + capability flags), with a scripted fake
 * [SubAgentRunner] standing in for the LLM generation:
 *
 *  - persisted ThreadNode/Result readable after a cold-start restart;
 *  - cold-start recovery of waiting / cancelled / completed threads;
 *  - followup_task / send_message queued → delivered → persisted;
 *  - interrupt keeps the thread (resumable via followup);
 *  - parent-run cancellation cascades to child threads;
 *  - child runs activate the durable ledger path (runId + onTerminal wiring).
 */
class SubAgentThreadGraphIntegrationTest : DurableRuntimeTestBase() {

    private lateinit var testRoot: File
    private lateinit var settingsStore: SettingsAggregator
    private lateinit var threadGraphFlags: CapabilityFlags
    private lateinit var fakeRunner: FakeSubAgentRunner
    private val appScope = AppScope()
    private val mainDispatcher = UnconfinedTestDispatcher()

    private val conversationId = kotlin.uuid.Uuid.random()

    @Before
    fun setUpThreadGraph() = runBlocking {
        // AppScope dispatches on Dispatchers.Main; Robolectric needs a main
        // dispatcher for the settings flow collection to run.
        Dispatchers.setMain(mainDispatcher)
        testRoot = File(context.cacheDir, "thread-graph-test-${System.nanoTime()}")
        testRoot.mkdirs()
        settingsStore = CasTestFixtures.settingsAggregator(context, testRoot)
        withTimeout(5_000) { settingsStore.settingsFlow.first { !it.init } }
        settingsStore.update { settings ->
            settings.copy(
                agentRuntime = settings.agentRuntime.copy(
                    subAgent = settings.agentRuntime.subAgent.copy(enabled = true)
                )
            )
        }
        threadGraphFlags = CapabilityFlags(
            PreferenceDataStoreFactory.create {
                File(testRoot, "flags.preferences_pb")
            }
        )
        threadGraphFlags.setEnabled(Capability.ThreadGraphV2, true)
        fakeRunner = FakeSubAgentRunner()
    }

    @After
    fun tearDownThreadGraph() {
        fakeRunner.releaseAll()
        Dispatchers.resetMain()
    }

    private fun manager(
        runner: SubAgentRunner = fakeRunner,
        flags: CapabilityFlags? = threadGraphFlags,
    ) = SubAgentManager(
        context = context,
        appScope = appScope,
        settingsStore = settingsStore,
        json = Json,
        runner = runner,
        agentTaskStore = AgentTaskStore(context, Json),
        sessionAccessGrantStore = SessionAccessGrantStore(),
        threadGraphStore = RoomThreadGraphStore(database.threadGraphDao()),
        capabilityFlags = flags,
    )

    private fun startInput(objective: String) = buildJsonObject {
        put("subagent_id", "explorer")
        put(
            "task",
            buildJsonObject {
                put("objective", objective)
            }
        )
    }

    /**
     * A parent tool matching explorer's allowlist — subagent_start filters the
     * definition's allowlist against the parent tools, and an empty result is
     * rejected ("no_allowed_tools"). The fake runner never executes it.
     */
    private fun parentTools(): List<Tool> = listOf(
        Tool(
            name = "tools_list",
            description = "list tools",
            parameters = { app.amber.ai.core.InputSchema.Obj(properties = buildJsonObject {}) },
            execute = { listOf(UIMessagePart.Text("{}")) },
        )
    )

    private fun awaitLive(threadId: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (fakeRunner.calls.any { it.threadId == threadId }) return
            Thread.sleep(20)
        }
        error("runner never started for thread $threadId")
    }

    private fun awaitTerminal(manager: SubAgentManager, threadId: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val status = manager.snapshot(threadId)?.status
            if (status != null && status != SubAgentRunStatus.RUNNING) return
            Thread.sleep(20)
        }
        error("thread $threadId did not reach a terminal status in time")
    }

    private fun payloadStatus(payload: JsonObject): String? =
        payload["status"]?.jsonPrimitive?.contentOrNull

    private fun resultField(payload: JsonObject, key: String): String? =
        payload["result"]?.jsonPrimitive?.contentOrNull

    // ── persisted node/result readable after restart ──────────────────────

    @Test
    fun startCompleteThenRestartReadReturnsFinalAnswer() = runBlocking {
        val first = manager()
        val started = first.start(
            parentConversationId = conversationId,
            input = startInput("Restart test"),
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )
        assertEquals("start payload: ${started.toString()}", "running", payloadStatus(started))
        awaitTerminal(first, started["run_id"]!!.jsonPrimitive.content)

        // Cold start: a brand-new manager (same DB) — the thread and its
        // final answer must be readable without any in-memory state.
        val second = manager()
        val payload = second.read(started["run_id"]!!.jsonPrimitive.content)
        assertEquals("completed", payloadStatus(payload))
        assertTrue(resultField(payload, "summary").orEmpty().contains("fake answer: Restart test"))
        // The child final answer comes back to the parent run through the payload.
        assertEquals("Explorer", payload["subagent_name"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun waitAfterRestartReconcilesDeadRunningThread() = runBlocking {
        val first = manager()
        fakeRunner.gate = CompletableDeferred()
        val started = first.start(
            parentConversationId = conversationId,
            input = startInput("Wait test"),
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )
        val threadId = started["run_id"]!!.jsonPrimitive.content
        awaitLive(threadId)

        // Cold start while the thread "runs": a dead RUNNING node is
        // reconciled to INTERRUPTED instead of being waited on forever.
        val second = manager()
        val payload = second.wait(threadId, waitTimeoutMs = 100)
        assertEquals("interrupted", payloadStatus(payload))
    }

    @Test
    fun waitCompletesFromAgentTaskTerminalFlow() = runBlocking {
        val manager = manager()
        fakeRunner.gate = CompletableDeferred()
        val started = manager.start(
            parentConversationId = conversationId,
            input = startInput("Flow wait test"),
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )
        val threadId = started["run_id"]!!.jsonPrimitive.content
        awaitLive(threadId)

        val waiter = async { manager.wait(threadId, waitTimeoutMs = 5_000) }
        fakeRunner.gate!!.complete(
            SubAgentResult(status = SubAgentRunStatus.COMPLETED, summary = "flow done")
        )

        assertEquals("completed", payloadStatus(waiter.await()))
    }

    @Test
    fun productionStartPreservesParentAndRejectsThirdGeneration() = runBlocking {
        val store = RoomThreadGraphStore(database.threadGraphDao())
        store.upsertNode(
            ThreadNodeRecord(
                threadId = "child",
                parentThreadId = null,
                rootRunId = "root_run",
                conversationId = conversationId.toString(),
                status = SubAgentRunStatus.COMPLETED.name,
                task = "{}",
                startedAtMs = 1_000,
                updatedAtMs = 1_000,
            )
        )
        store.upsertNode(
            ThreadNodeRecord(
                threadId = "grandchild",
                parentThreadId = "child",
                rootRunId = "root_run",
                conversationId = conversationId.toString(),
                status = SubAgentRunStatus.COMPLETED.name,
                task = "{}",
                startedAtMs = 2_000,
                updatedAtMs = 2_000,
            )
        )

        val manager = manager()
        val allowed = manager.start(
            parentConversationId = conversationId,
            input = startInput("Allowed grandchild"),
            parentTools = parentTools(),
            parentRunId = "child",
        )
        val allowedId = allowed["run_id"]!!.jsonPrimitive.content
        awaitTerminal(manager, allowedId)
        assertEquals("child", store.getNode(allowedId)!!.parentThreadId)
        assertEquals("root_run", store.getNode(allowedId)!!.rootRunId)

        val rejected = manager.start(
            parentConversationId = conversationId,
            input = startInput("Too deep"),
            parentTools = parentTools(),
            parentRunId = "grandchild",
        )
        assertEquals("thread_depth_limit", rejected["code"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun coldStartCancelOfStaleRunningThread() = runBlocking {
        val first = manager()
        fakeRunner.gate = CompletableDeferred()
        val started = first.start(
            parentConversationId = conversationId,
            input = startInput("Cancel test"),
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )
        val threadId = started["run_id"]!!.jsonPrimitive.content
        awaitLive(threadId)

        // Cold start: cancelling a stale running thread persists CANCELLED.
        val second = manager()
        val payload = second.cancel(threadId)
        assertEquals("cancelled", payloadStatus(payload))
        assertEquals("cancelled", second.read(threadId)["status"]?.jsonPrimitive?.contentOrNull)
    }

    // ── interrupt keeps the thread; followup continues it ─────────────────

    @Test
    fun interruptKeepsThreadAndFollowupContinuesIt() = runBlocking {
        val manager = manager()
        fakeRunner.gate = CompletableDeferred()
        val started = manager.start(
            parentConversationId = conversationId,
            input = startInput("Interrupt test"),
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )
        val threadId = started["run_id"]!!.jsonPrimitive.content
        awaitLive(threadId)

        val interrupted = manager.interrupt(threadId)
        assertEquals("interrupted", payloadStatus(interrupted))

        // The thread is preserved (not deleted), still readable after restart.
        val second = manager()
        assertEquals("interrupted", payloadStatus(second.read(threadId)))
        assertEquals(SubAgentRunStatus.INTERRUPTED, second.persistedState(threadId)?.status)

        // followup_task continues the same thread from its interrupted state.
        fakeRunner.gate = null
        fakeRunner.nextResult = SubAgentResult(status = SubAgentRunStatus.COMPLETED, summary = "followup done")
        val followup = second.followup(
            parentConversationId = conversationId,
            threadId = threadId,
            input = buildJsonObject {
                put("task", buildJsonObject { put("objective", "Continue the work") })
            },
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )
        assertTrue(
            payloadStatus(followup) in setOf("running", "completed") // may finish before the call returns
        )
        awaitTerminal(second, threadId)
        assertEquals("completed", payloadStatus(second.read(threadId)))
        // The followup generation was seeded with the thread's previous answer.
        assertEquals("fake answer: Interrupt test", fakeRunner.calls.last().previousAnswer)
    }

    @Test
    fun sameInstanceFollowupAfterInterruptSucceeds() = runBlocking {
        // Regression: interrupt must write the INTERRUPTED snapshot back into
        // memory — otherwise the same SubAgentManager still sees RUNNING and
        // blocks the followup with "thread_running" (the previous test only
        // passed because it followed up from a fresh manager instance).
        val manager = manager()
        fakeRunner.gate = CompletableDeferred()
        val started = manager.start(
            parentConversationId = conversationId,
            input = startInput("Same-instance interrupt test"),
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )
        val threadId = started["run_id"]!!.jsonPrimitive.content
        awaitLive(threadId)

        val interrupted = manager.interrupt(threadId)
        assertEquals("interrupted", payloadStatus(interrupted))
        // The in-memory snapshot is terminal immediately — no new instance needed.
        assertEquals(SubAgentRunStatus.INTERRUPTED, manager.snapshot(threadId)?.status)
        assertEquals("interrupted", payloadStatus(manager.read(threadId)))

        fakeRunner.gate = null
        fakeRunner.nextResult = SubAgentResult(status = SubAgentRunStatus.COMPLETED, summary = "same-instance followup done")
        val followup = manager.followup(
            parentConversationId = conversationId,
            threadId = threadId,
            input = buildJsonObject {
                put("task", buildJsonObject { put("objective", "Continue on the same instance") })
            },
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )
        assertTrue(
            payloadStatus(followup) in setOf("running", "completed") // may finish before the call returns
        )
        awaitTerminal(manager, threadId)
        assertEquals("completed", payloadStatus(manager.read(threadId)))
    }

    // ── send_message: queued / delivered / persisted ──────────────────────

    @Test
    fun sendMessageToLiveThreadIsDeliveredAndPersistedWithResult() = runBlocking {
        val manager = manager()
        fakeRunner.gate = CompletableDeferred()
        val started = manager.start(
            parentConversationId = conversationId,
            input = startInput("Message test"),
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )
        val threadId = started["run_id"]!!.jsonPrimitive.content
        awaitLive(threadId)

        // Mid-run delivery: the message reaches the thread's mailbox.
        val sent = manager.sendMessage(threadId, "steer me")
        assertEquals("delivered", sent["delivery_state"]?.jsonPrimitive?.contentOrNull)
        val messageId = sent["message_id"]!!.jsonPrimitive.content
        val store = RoomThreadGraphStore(database.threadGraphDao())
        assertEquals(ThreadDeliveryState.DELIVERED.name, store.getMessage(messageId)!!.deliveryState)

        // Result lands → the delivered message is persisted (no silent loss).
        fakeRunner.gate!!.complete(SubAgentResult(status = SubAgentRunStatus.COMPLETED, summary = "done"))
        awaitTerminal(manager, threadId)
        assertEquals(ThreadDeliveryState.PERSISTED.name, store.getMessage(messageId)!!.deliveryState)
    }

    @Test
    fun sendMessageToIdleThreadStaysQueuedThenPersistedAfterFollowup() = runBlocking {
        val manager = manager()
        val started = manager.start(
            parentConversationId = conversationId,
            input = startInput("Idle message test"),
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )
        val threadId = started["run_id"]!!.jsonPrimitive.content
        awaitTerminal(manager, threadId)

        // Idle thread: the message is queued, not lost.
        val sent = manager.sendMessage(threadId, "queued note")
        assertEquals("queued", sent["delivery_state"]?.jsonPrimitive?.contentOrNull)
        val messageId = sent["message_id"]!!.jsonPrimitive.content
        val store = RoomThreadGraphStore(database.threadGraphDao())
        assertEquals(ThreadDeliveryState.QUEUED.name, store.getMessage(messageId)!!.deliveryState)

        // The thread next runs (followup): queued message is delivered and
        // persisted once the followup result lands.
        manager.followup(
            parentConversationId = conversationId,
            threadId = threadId,
            input = buildJsonObject {
                put("task", buildJsonObject { put("objective", "Pick up the queue") })
            },
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )
        awaitTerminal(manager, threadId)
        assertEquals(ThreadDeliveryState.PERSISTED.name, store.getMessage(messageId)!!.deliveryState)
        // Enqueued order preserved: the idle message first, then the followup —
        // both PERSISTED after the followup result landed, nothing lost.
        assertEquals(
            listOf("message", "followup"),
            store.listMessages(threadId).map { it.kind },
        )
    }

    // ── parent-run cancellation cascades to children ──────────────────────

    @Test
    fun cancelByRootRunCascadesToLiveAndStaleChildren() = runBlocking {
        val first = manager()
        fakeRunner.gate = CompletableDeferred()
        val childA = first.start(
            parentConversationId = conversationId,
            input = startInput("Cascade A"),
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )["run_id"]!!.jsonPrimitive.content
        awaitLive(childA)

        // "Restart": a new manager instance; child A is now a stale RUNNING
        // node, child B is started live under the same root run.
        val second = manager()
        fakeRunner.gate = CompletableDeferred()
        val childB = second.start(
            parentConversationId = conversationId,
            input = startInput("Cascade B"),
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )["run_id"]!!.jsonPrimitive.content
        awaitLive(childB)

        val result = second.cancelByRootRun("parent_run_1", conversationId.toString())
        assertEquals("ok", result["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, result["cancelled"]?.jsonPrimitive?.content?.toIntOrNull())

        // Both children are CANCELLED — live (B) and persisted-stale (A).
        val third = manager()
        assertEquals("cancelled", payloadStatus(third.read(childA)))
        assertEquals("cancelled", payloadStatus(third.read(childB)))
        // A child of another root run is untouched.
        fakeRunner.gate = null
        val unrelated = second.start(
            parentConversationId = conversationId,
            input = startInput("Cascade unrelated"),
            parentTools = parentTools(),
            parentRunId = "other_parent_run",
        )["run_id"]!!.jsonPrimitive.content
        awaitTerminal(second, unrelated)
        assertEquals("completed", payloadStatus(second.read(unrelated)))
    }

    // ── child runs activate the unified ledger path ───────────────────────

    @Test
    fun childRunWiresLedgerPathAndWaitingUserPausesThread() = runBlocking {
        val manager = manager()
        fakeRunner.gate = CompletableDeferred()
        val started = manager.start(
            parentConversationId = conversationId,
            input = startInput("Ledger wiring test"),
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )
        val threadId = started["run_id"]!!.jsonPrimitive.content
        awaitLive(threadId)

        val call = fakeRunner.calls.single()
        // runId + onTerminal are exactly the two conditions GenerationHandler
        // requires to activate its durable path for this child run, so child
        // tool effects are recorded in the unified ledger under the child
        // runId (plan §P4-02 "child tool effect 仍使用统一 ledger").
        assertEquals(threadId, call.runId)
        assertNotNull(call.onTerminal)

        // Approval pause: WaitingUser keeps the thread (node APPROVAL_REQUIRED,
        // never finished) — the pause survives a restart.
        call.onTerminal!!(GenerationTerminal.WaitingUser)
        val second = manager()
        assertEquals("approval_required", payloadStatus(second.read(threadId)))

        // StepLimit must not be published as COMPLETED. The terminal callback
        // runs before the result lands (same order as the generator flow).
        call.onTerminal!!(GenerationTerminal.StepLimit)
        fakeRunner.gate!!.complete(SubAgentResult(status = SubAgentRunStatus.COMPLETED, summary = "done"))
        awaitTerminal(manager, threadId)
        assertEquals("timed_out", payloadStatus(manager.read(threadId)))
    }

    // ── flag off: legacy behavior unchanged ───────────────────────────────

    @Test
    fun flagOffKeepsLegacyInMemoryBehaviorWithoutPersistence() = runBlocking {
        val legacy = manager(flags = null)
        val started = legacy.start(
            parentConversationId = conversationId,
            input = startInput("Legacy test"),
            parentTools = parentTools(),
            parentRunId = "parent_run_1",
        )
        val threadId = started["run_id"]!!.jsonPrimitive.content
        awaitTerminal(legacy, threadId)

        // No thread-graph tables are written when the flag is off.
        assertEquals(0, database.threadGraphDao().listNodesByRootRun("parent_run_1").size)

        // A fresh manager cannot read the thread from persistence — it falls
        // back to the transcript file (legacy behavior).
        val restarted = manager(flags = null)
        val payload = restarted.read(threadId)
        assertEquals("interrupted", payloadStatus(payload))
        assertEquals(true, payload["transcript_available"]?.jsonPrimitive?.contentOrNull?.toBoolean())
    }
}

/** Scripted [SubAgentRunner] fake: completes instantly or blocks on a gate. */
class FakeSubAgentRunner : SubAgentRunner {
    data class CapturedCall(
        val threadId: String,
        val runId: String?,
        val onTerminal: (suspend (GenerationTerminal) -> Unit)?,
        val previousAnswer: String,
    )

    val calls = mutableListOf<CapturedCall>()
    var gate: CompletableDeferred<SubAgentResult>? = null
    var nextResult: SubAgentResult =
        SubAgentResult(status = SubAgentRunStatus.COMPLETED, summary = "fake done")

    override suspend fun run(
        settings: Settings,
        definition: SubAgentDefinition,
        task: SubAgentTaskSpec,
        tools: List<Tool>,
        liveText: MutableStateFlow<String>,
        liveParts: MutableStateFlow<List<UIMessagePart>>,
        runId: String?,
        onTerminal: (suspend (GenerationTerminal) -> Unit)?,
        consumeSteerMessages: suspend () -> List<UIMessage>,
        previousAnswer: String,
    ): SubAgentResult {
        calls += CapturedCall(
            threadId = runId.orEmpty(),
            runId = runId,
            onTerminal = onTerminal,
            previousAnswer = previousAnswer,
        )
        liveText.value = "fake answer: ${task.objective}"
        val g = gate
        return if (g != null) g.await() else nextResult
    }

    fun releaseAll() {
        gate?.cancel()
        gate = null
    }
}
