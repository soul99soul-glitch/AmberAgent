package app.amber.feature.jscell

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P4-03 persistent JS cells (docs/plans/2026-08-13-android-ios-capability-
 * parity-closure-plan.md §10 P4-03) — first-version acceptance coverage:
 *
 *  - create -> run -> wait -> terminate closed loop through the real tools;
 *  - long tasks return running + cellId, wait polls for new output;
 *  - store/load round-trip and the store size cap;
 *  - cold start: persisted RUNNING/WAITING cells are marked TERMINATED
 *    (process_restart), store content and terminal states stay readable;
 *  - nested tool calls: whitelisted read-only tools are callable from JS,
 *    anything else is rejected (no write tool reaches the sandbox, so there
 *    is no ledger-bypass surface);
 *  - hard limits: run-time limit terminates a long cell, engine failures
 *    (memory limit) mark the cell FAILED, console output is hard-capped.
 *
 * The real QuickJS engine cannot load in Robolectric (native libs), so the
 * runtime is exercised through [FakeJsCellEngine]; the QuickJS wiring
 * (setMemoryLimit / setMaxStackSize / js_call_tool host) is compile-checked
 * in QuickJsCellEngine and the failure contract is tested via the fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JsCellRuntimeTest {

    private lateinit var context: Context
    private lateinit var testRoot: File
    private lateinit var engine: FakeJsCellEngine
    private lateinit var store: JsCellStore
    private lateinit var runtime: JsCellRuntime
    private val json = Json { ignoreUnknownKeys = true }

    private val nestedTools: Map<String, Tool> = mapOf(
        "get_time_info" to Tool(
            name = "get_time_info",
            description = "Test read-only tool",
            execute = { listOf(UIMessagePart.Text("""{"time":"now"}""")) },
        ),
    )

    private fun newRuntime(
        limits: JsCellLimits = testLimits(),
        engineMode: FakeSession.Mode = FakeSession.Mode.Sync("42"),
    ) {
        engine = FakeJsCellEngine(engineMode)
        store = JsCellStore(
            dataStore = PreferenceDataStoreFactory.create {
                File(testRoot, "js_cells_${System.nanoTime()}.preferences_pb")
            },
            json = json,
        )
        runtime = JsCellRuntime(
            store = store,
            engine = engine,
            limits = limits,
            nestedToolResolver = { name -> nestedTools[name] },
            json = json,
        )
    }

    private fun testLimits() = JsCellLimits(quickReturnMs = 100, maxRunTimeMs = 60_000)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        testRoot = File(context.cacheDir, "js-cell-${System.nanoTime()}").apply { mkdirs() }
        newRuntime()
    }

    @After
    fun tearDown() {
        engine.releaseAll()
        runtime.shutdownForTest()
        testRoot.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Closed loop: create -> run -> wait -> terminate
    // ------------------------------------------------------------------

    @Test
    fun `create run wait terminate closed loop`() = runBlocking {
        val tools = createJsCellTools(runtime, runId = "run-1").associateBy { it.name }

        // create
        val created = call(tools, "js_cell_create", buildJsonObject {})
        assertEquals("waiting", created["status"]?.asText())
        val cellId = created["cell_id"]?.asText()
        assertNotNull(cellId)
        assertEquals("run-1", created["owner_run_id"]?.asText())
        assertEquals("run-1", store.get(cellId!!)!!.ownerRunId)

        // run: short code completes inline
        val completed = call(tools, "js_cell_run", buildJsonObject {
            put("cell_id", cellId)
            put("code", "1 + 2")
        })
        assertEquals("completed", completed["status"]?.asText())
        assertEquals("42", completed["result"]?.asText())

        // run: long code returns running + cellId
        engine.sessions().single().mode = FakeSession.Mode.Block
        val running = call(tools, "js_cell_run", buildJsonObject {
            put("cell_id", cellId)
            put("code", "slow()")
        })
        assertEquals("running", running["status"]?.asText())

        // a second run on the busy cell is rejected
        val busy = call(tools, "js_cell_run", buildJsonObject {
            put("cell_id", cellId)
            put("code", "again()")
        })
        assertEquals("failed", busy["status"]?.asText())
        assertEquals("cell_busy", busy["error"]?.asText())

        // wait: poll for the terminal state after releasing the evaluation
        engine.sessions().single().mode = FakeSession.Mode.Sync("done")
        engine.releaseAll()
        val waited = call(tools, "js_cell_wait", buildJsonObject {
            put("cell_id", cellId)
            put("timeout_ms", 5_000)
            put("cursor", 0)
        })
        assertEquals("completed", waited["status"]?.asText())
        assertEquals("done", waited["result"]?.asText())

        // terminate
        val terminated = call(tools, "js_cell_terminate", buildJsonObject { put("cell_id", cellId) })
        assertEquals("terminated", terminated["status"]?.asText())

        // terminated cells reject further runs
        val afterTerminate = call(tools, "js_cell_run", buildJsonObject {
            put("cell_id", cellId)
            put("code", "1")
        })
        assertEquals("terminated", afterTerminate["status"]?.asText())

        // persisted terminal state is readable
        assertEquals(JsCellStatus.TERMINATED.name, store.get(cellId)!!.status)
    }

    @Test
    fun `wait returns new output while cell is still running`() = runBlocking {
        newRuntime(engineMode = FakeSession.Mode.BlockAfterChatter)
        val tools = createJsCellTools(runtime, runId = null).associateBy { it.name }
        val cellId = call(tools, "js_cell_create", buildJsonObject {})["cell_id"]?.asText()!!

        val running = call(tools, "js_cell_run", buildJsonObject {
            put("cell_id", cellId)
            put("code", "chatty()")
        })
        assertEquals("running", running["status"]?.asText())

        val waited = call(tools, "js_cell_wait", buildJsonObject {
            put("cell_id", cellId)
            put("timeout_ms", 5_000)
            put("cursor", 0)
        })
        assertEquals("running", waited["status"]?.asText())
        assertTrue(waited["output"]?.asText().orEmpty().contains("line"))

        engine.releaseAll()
        val done = call(tools, "js_cell_wait", buildJsonObject {
            put("cell_id", cellId)
            put("timeout_ms", 5_000)
            put("cursor", waited["cursor"]?.asText()?.toIntOrNull() ?: 0)
        })
        assertEquals("completed", done["status"]?.asText())
    }

    // ------------------------------------------------------------------
    // store / load + size cap
    // ------------------------------------------------------------------

    @Test
    fun `store load round trip and oversized store rejected`() = runBlocking {
        val tools = createJsCellTools(runtime, runId = null).associateBy { it.name }
        val cellId = call(tools, "js_cell_create", buildJsonObject {})["cell_id"]?.asText()!!

        val payload = """{"count": 3}"""
        val stored = call(tools, "js_cell_store", buildJsonObject {
            put("cell_id", cellId)
            put("value", payload)
        })
        assertEquals("stored", stored["status"]?.asText())
        assertEquals(payload.length, stored["bytes"]?.asText()?.toIntOrNull())

        val loaded = call(tools, "js_cell_load", buildJsonObject { put("cell_id", cellId) })
        assertEquals("loaded", loaded["status"]?.asText())
        assertEquals(payload, loaded["value"]?.asText())

        val oversized = call(tools, "js_cell_store", buildJsonObject {
            put("cell_id", cellId)
            put("value", "x".repeat(32 * 1024 + 1))
        })
        assertEquals("failed", oversized["status"]?.asText())
        assertEquals("store_too_large", oversized["error"]?.asText())
    }

    // ------------------------------------------------------------------
    // Cold start recovery
    // ------------------------------------------------------------------

    @Test
    fun `cold start marks running and waiting cells terminated and keeps store`() = runBlocking {
        val now = System.currentTimeMillis()
        store.upsert(JsCellRecord("cell_stale_running", "run-1", JsCellStatus.RUNNING.name, now, now, storeJson = """{"kept": true}"""))
        store.upsert(JsCellRecord("cell_stale_waiting", "run-1", JsCellStatus.WAITING.name, now, now))
        store.upsert(JsCellRecord("cell_done", "run-1", JsCellStatus.COMPLETED.name, now, now, lastOutput = "[LOG] hi", terminalResult = "3"))

        // a fresh runtime over the same persisted data = cold start
        val freshRuntime = JsCellRuntime(
            store = store,
            engine = FakeJsCellEngine(FakeSession.Mode.Sync("unused")),
            limits = testLimits(),
        )
        assertEquals(2, freshRuntime.recoverFromColdStart())

        val runningRecord = store.get("cell_stale_running")!!
        assertEquals(JsCellStatus.TERMINATED.name, runningRecord.status)
        assertEquals(JsCellTerminationReasons.PROCESS_RESTART, runningRecord.error)
        // store content survives the restart
        assertEquals("""{"kept": true}""", runningRecord.storeJson)
        assertEquals(JsCellStatus.TERMINATED.name, store.get("cell_stale_waiting")!!.status)

        // wait on the recovered cell reports the honest terminal state
        val waited = freshRuntime.waitCell("cell_stale_running", timeoutMs = 1_000)
        assertEquals("terminated", waited["status"]?.asText())
        assertEquals(JsCellTerminationReasons.PROCESS_RESTART, waited["error"]?.asText())

        // load still works after the restart; running again is refused
        assertEquals("""{"kept": true}""", freshRuntime.loadCell("cell_stale_running")["value"]?.asText())
        assertEquals("terminated", freshRuntime.runCell("cell_stale_running", "1")["status"]?.asText())

        // terminal records are untouched by recovery
        assertEquals(JsCellStatus.COMPLETED.name, store.get("cell_done")!!.status)
        assertEquals("3", freshRuntime.waitCell("cell_done", timeoutMs = 1_000)["result"]?.asText())
        freshRuntime.shutdownForTest()
    }

    // ------------------------------------------------------------------
    // Nested whitelisted tools
    // ------------------------------------------------------------------

    @Test
    fun `nested tool calls only reach whitelisted read-only tools`() = runBlocking {
        newRuntime(engineMode = FakeSession.Mode.Nested)
        val tools = createJsCellTools(runtime, runId = null).associateBy { it.name }
        val cellId = call(tools, "js_cell_create", buildJsonObject {})["cell_id"]?.asText()!!

        val completed = call(tools, "js_cell_run", buildJsonObject {
            put("cell_id", cellId)
            put("code", "useTools()")
        })
        assertEquals("completed", completed["status"]?.asText())
        val result = completed["result"]?.asText().orEmpty()
        // whitelisted read-only tool executes
        assertTrue(result.contains("""{"time":"now"}"""))
        // non-whitelisted tool (a write tool name) is refused with a
        // structured error — no permission/ledger bypass surface
        assertTrue(result.contains("""tool_not_allowed"""))
        assertTrue(result.contains("""write_file"""))
    }

    // ------------------------------------------------------------------
    // Hard limits
    // ------------------------------------------------------------------

    @Test
    fun `run time limit terminates a long running cell`() = runBlocking {
        newRuntime(limits = testLimits().copy(quickReturnMs = 50, maxRunTimeMs = 150), engineMode = FakeSession.Mode.Block)
        val tools = createJsCellTools(runtime, runId = null).associateBy { it.name }
        val cellId = call(tools, "js_cell_create", buildJsonObject {})["cell_id"]?.asText()!!

        val running = call(tools, "js_cell_run", buildJsonObject {
            put("cell_id", cellId)
            put("code", "spin()")
        })
        assertEquals("running", running["status"]?.asText())

        val waited = call(tools, "js_cell_wait", buildJsonObject {
            put("cell_id", cellId)
            put("timeout_ms", 5_000)
        })
        assertEquals("terminated", waited["status"]?.asText())
        assertEquals(JsCellTerminationReasons.TIME_LIMIT, waited["error"]?.asText())

        // the worker closes the session once the evaluation returns
        awaitTrue { engine.sessions().isNotEmpty() }
        engine.releaseAll()
        awaitTrue { engine.sessions().all { it.closed } }
        assertEquals(JsCellStatus.TERMINATED.name, store.get(cellId)!!.status)
    }

    @Test
    fun `engine failure marks the cell failed`() = runBlocking {
        newRuntime(engineMode = FakeSession.Mode.Fail("out of memory"))
        val tools = createJsCellTools(runtime, runId = null).associateBy { it.name }
        val cellId = call(tools, "js_cell_create", buildJsonObject {})["cell_id"]?.asText()!!

        val completed = call(tools, "js_cell_run", buildJsonObject {
            put("cell_id", cellId)
            put("code", "allocate()")
        })
        assertEquals("failed", completed["status"]?.asText())
        assertEquals("out of memory", completed["error"]?.asText())
        assertEquals(JsCellStatus.FAILED.name, store.get(cellId)!!.status)
    }

    @Test
    fun `console output is hard capped`() = runBlocking {
        newRuntime(
            limits = JsCellLimits(quickReturnMs = 100, maxRunTimeMs = 60_000, outputChars = 90),
            engineMode = FakeSession.Mode.Chatter,
        )
        val tools = createJsCellTools(runtime, runId = null).associateBy { it.name }
        val cellId = call(tools, "js_cell_create", buildJsonObject {})["cell_id"]?.asText()!!

        val completed = call(tools, "js_cell_run", buildJsonObject {
            put("cell_id", cellId)
            put("code", "printMany()")
        })
        assertEquals("completed", completed["status"]?.asText())
        assertEquals(true, completed["truncated"]?.jsonPrimitive?.booleanOrNull)
        val output = completed["output"]?.asText().orEmpty()
        assertTrue("output length ${output.length} exceeds cap", output.length <= 90)
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private suspend fun call(tools: Map<String, Tool>, name: String, args: JsonElement): JsonObject {
        val tool = tools[name] ?: error("tool $name missing")
        val parts = tool.execute(args)
        val text = parts.filterIsInstance<UIMessagePart.Text>().first().text
        return json.parseToJsonElement(text).jsonObject
    }

    private fun JsonElement?.asText(): String? = this?.jsonPrimitive?.contentOrNull

    private fun awaitTrue(timeoutMs: Long = 3_000, block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (block()) return
            Thread.sleep(20)
        }
        assertTrue("condition not reached within ${timeoutMs}ms", block())
    }
}

/**
 * Fake QuickJS engine for JVM tests: configurable per-session behavior and
 * explicit release for blocked evaluations (simulating long-running code).
 */
private class FakeJsCellEngine(initialMode: FakeSession.Mode) : JsCellEngine {
    private val created = CopyOnWriteArrayList<FakeSession>()
    private val initialMode = initialMode

    override fun createSession(
        limits: JsCellLimits,
        console: (String) -> Unit,
        callTool: (toolName: String, argsJson: String) -> String,
    ): JsCellSession = FakeSession(console = console, callTool = callTool, mode = initialMode).also { created += it }

    fun sessions(): List<FakeSession> = created.toList()

    fun releaseAll() {
        created.forEach { it.release() }
    }
}

private class FakeSession(
    private val console: (String) -> Unit,
    private val callTool: (String, String) -> String,
    mode: Mode,
) : JsCellSession {
    sealed class Mode {
        data class Sync(val result: String) : Mode()
        data class Fail(val error: String) : Mode()
        object Block : Mode()
        object BlockAfterChatter : Mode()
        object Nested : Mode()
        object Chatter : Mode()
    }

    @Volatile
    var mode: Mode = mode

    @Volatile
    var closed: Boolean = false

    private val latch = CountDownLatch(1)

    fun release() {
        latch.countDown()
    }

    override fun evaluate(code: String): JsEvalOutcome = when (val m = mode) {
        is Mode.Sync -> {
            console("[LOG] hello")
            JsEvalOutcome.Completed(m.result)
        }
        is Mode.Fail -> JsEvalOutcome.Failed(m.error)
        is Mode.Block -> {
            latch.await(10, TimeUnit.SECONDS)
            JsEvalOutcome.Completed("done")
        }
        is Mode.BlockAfterChatter -> {
            console("[LOG] line one")
            console("[LOG] line two")
            console("[LOG] line three")
            latch.await(10, TimeUnit.SECONDS)
            JsEvalOutcome.Completed("done")
        }
        is Mode.Nested -> {
            val allowed = callTool("get_time_info", "{}")
            val denied = callTool("write_file", "{}")
            JsEvalOutcome.Completed("allowed=$allowed denied=$denied")
        }
        is Mode.Chatter -> {
            repeat(100) { console("[LOG] line $it") }
            JsEvalOutcome.Completed("done")
        }
    }

    override fun close() {
        closed = true
    }
}
