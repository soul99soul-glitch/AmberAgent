package app.amber.core.jev

import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.AccessibilityTextMatch
import app.amber.core.automation.ScreenAction
import app.amber.core.automation.ScreenActionReceipt
import app.amber.core.automation.ScreenNode
import app.amber.core.automation.ScreenSnapshot
import app.amber.core.settings.Settings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无障碍 Jev 链路的边界回归：授权、快照、派发、fresh 核验和硬限。 */
class JevScreenGoalRunnerTest {

    private class ScriptedTransport(
        private val responses: MutableList<String>,
        private val onCall: (Int) -> Unit = {},
    ) : JevTransport {
        var calls = 0
            private set

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            onCall(calls)
            return JevTransportResponse.Http(
                code = 200,
                body = (responses.removeFirstOrNull()
                    ?: error("unexpected Jev request #$calls")).toByteArray(),
                retryAfterHeader = null,
            )
        }
    }

    private class SuspendingTransport : JevTransport {
        var calls = 0
            private set

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            awaitCancellation()
        }
    }

    private class FakeAccessibilityController(
        initial: ScreenSnapshot,
        private val receipt: ScreenActionReceipt = ScreenActionReceipt("ok", dispatched = true),
        private val onAction: (ScreenAction) -> Unit = {},
    ) : AccessibilityController {
        var current = initial
        var captures = 0
            private set
        val actions = mutableListOf<ScreenAction>()

        override fun captureScreenSnapshot(): ScreenSnapshot {
            captures++
            return current
        }

        override fun performScreenAction(snapshot: ScreenSnapshot, action: ScreenAction): ScreenActionReceipt {
            actions += action
            onAction(action)
            return receipt
        }

        override suspend fun tap(x: Float, y: Float, durationMillis: Long): Boolean = true
        override suspend fun longPress(x: Float, y: Float, durationMillis: Long): Boolean = true
        override suspend fun swipe(
            fromX: Float,
            fromY: Float,
            toX: Float,
            toY: Float,
            durationMillis: Long,
        ): Boolean = true

        override fun setFocusedText(text: String): Boolean = true
        override fun dumpUiTree(maxNodes: Int): String = ""
        override fun findTextNodes(query: String, maxNodes: Int): List<AccessibilityTextMatch> = emptyList()
        override fun back(): Boolean = true
        override fun home(): Boolean = true
    }

    private fun runtime(
        transport: JevTransport,
        settingsProvider: () -> Settings,
    ) = JevRuntime(
        coordinator = JevDecisionCoordinator(
            client = JevClient(transport),
            apiKeyProvider = { "test-key" },
            usageStore = JevUsageStore.IN_MEMORY,
            clock = { 1_000_000L },
        ),
        settingsProvider = settingsProvider,
    )

    private fun settings(
        mode: JevMode,
        scopes: Set<JevDataScope> = setOf(JevDataScope.SCREEN_CONTENT, JevDataScope.TASK_TEXT),
    ) = Settings(
        jev = JevSetting(
            enabled = mode != JevMode.OFF,
            purposes = mapOf(JevPurpose.SCREEN_AUTOMATION to mode),
            dataScopes = scopes,
        ),
    )

    private fun answer(action: String, safe: Double = 0.99, done: Double = 0.1): String {
        val safety = (0 until 30).joinToString(",") { "\"safe_a$it\":{\"type\":\"noul\",\"noul\":$safe}" }
        return """{"model":"jev-test","answers":{
            "action":{"type":"choice","choice":"$action","confidence":0.9},
            $safety,
            "done":{"type":"noul","noul":$done}
        }}"""
    }

    private fun runner(
        runtime: JevRuntime,
        scheduler: TestCoroutineScheduler,
    ) = JevScreenGoalRunner(
        runtime = runtime,
        mainDispatcher = StandardTestDispatcher(scheduler),
        settle = {},
    )

    private fun snapshot(
        id: String,
        packageName: String = "com.example.target",
        nodes: List<ScreenNode> = listOf(
            node(ref = "open", label = "Open draft", clickable = true),
        ),
    ) = ScreenSnapshot(
        id = id,
        packageName = packageName,
        windowId = 1,
        nodes = nodes,
    )

    private fun node(
        ref: String,
        label: String,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
    ) = ScreenNode(
        ref = ref,
        label = label,
        viewId = "id/$ref",
        className = "android.view.View",
        left = 0,
        top = 0,
        right = 100,
        bottom = 100,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        enabled = enabled,
    )

    private fun status(result: kotlinx.serialization.json.JsonObject): String =
        result["status"]!!.jsonPrimitive.content

    @Test
    fun twoActionsThenFreshDoneCompletes() = runTest {
        val snapshots = listOf(snapshot("s1"), snapshot("s2"), snapshot("s3"))
        val transport = ScriptedTransport(
            mutableListOf(answer("a0", safe = 0.84), answer("a0", safe = 0.88), answer("done", done = 0.96)),
        )
        var step = 0
        lateinit var controller: FakeAccessibilityController
        controller = FakeAccessibilityController(snapshots.first()) {
            step++
            controller.current = snapshots[step]
        }
        val result = runner(
            runtime(transport) { settings(JevMode.ACTIVE) },
            testScheduler,
        ).run(
            controller = controller,
            packageName = "com.example.target",
            goal = "open the draft",
            texts = emptyList(),
            runKey = "screen-success-${testScheduler.currentTime}",
            maxSteps = 4,
        )

        assertEquals("completed", status(result))
        assertEquals(2, controller.actions.size)
        assertEquals(3, transport.calls)
        assertEquals(2, result["actions_dispatched"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, result["steps"]!!.jsonArray.size)
        // The final done decision must be followed by a same-id fresh snapshot read.
        assertTrue(controller.captures >= 6)
    }

    @Test
    fun offModeDoesNotReadScreenOrUseNetwork() = runTest {
        val transport = ScriptedTransport(mutableListOf(answer("done", done = 0.99)))
        val controller = FakeAccessibilityController(snapshot("off"))
        val result = runner(runtime(transport) { settings(JevMode.OFF) }, testScheduler).run(
            controller, "com.example.target", "goal", emptyList(), "screen-off",
        )

        assertEquals("disabled", status(result))
        assertEquals(0, controller.captures)
        assertEquals(0, transport.calls)
        assertEquals(0, controller.actions.size)
    }

    @Test
    fun shadowModeAndDryRunNeverDispatchActions() = runTest {
        val shadowTransport = ScriptedTransport(mutableListOf(answer("a0")))
        val shadowController = FakeAccessibilityController(snapshot("shadow"))
        val shadow = runner(runtime(shadowTransport) { settings(JevMode.SHADOW) }, testScheduler).run(
            shadowController, "com.example.target", "goal", emptyList(), "screen-shadow",
        )
        assertEquals("shadow_trace", status(shadow))
        assertEquals(1, shadowTransport.calls)
        assertEquals(0, shadowController.actions.size)

        val dryTransport = ScriptedTransport(mutableListOf(answer("a0")))
        val dryController = FakeAccessibilityController(snapshot("dry"))
        val dry = runner(runtime(dryTransport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            dryController, "com.example.target", "goal", emptyList(), "screen-dry", dryRun = true,
        )
        assertEquals("shadow_trace", status(dry))
        assertEquals(1, dryTransport.calls)
        assertEquals(0, dryController.actions.size)
    }

    @Test
    fun missingScopeDoesNotReadScreenOrSendContent() = runTest {
        val transport = ScriptedTransport(mutableListOf(answer("done", done = 0.99)))
        val controller = FakeAccessibilityController(snapshot("scope"))
        val result = runner(
            runtime(transport) { settings(JevMode.ACTIVE, setOf(JevDataScope.TASK_TEXT)) },
            testScheduler,
        ).run(controller, "com.example.target", "goal", emptyList(), "screen-scope")

        assertEquals("handback", status(result))
        assertEquals("screen_content_or_task_text_not_authorized", result["reason"]!!.jsonPrimitive.content)
        assertEquals(0, controller.captures)
        assertEquals(0, transport.calls)
    }

    @Test
    fun wrongForegroundPackageIsNeverSentToJev() = runTest {
        val transport = ScriptedTransport(mutableListOf(answer("done", done = 0.99)))
        val controller = FakeAccessibilityController(snapshot("wrong", packageName = "com.other.app"))
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            controller, "com.example.target", "goal", emptyList(), "screen-package",
        )

        assertEquals("needs_user_action", status(result))
        assertEquals("foreground_package_changed", result["reason"]!!.jsonPrimitive.content)
        assertEquals(1, controller.captures)
        assertEquals(0, transport.calls)
    }

    @Test
    fun staleDecisionNeverDispatches() = runTest {
        var current = settings(JevMode.ACTIVE)
        val transport = ScriptedTransport(mutableListOf(answer("a0"))) {
            current = settings(JevMode.OFF)
        }
        val controller = FakeAccessibilityController(snapshot("stale"))
        val result = runner(runtime(transport) { current }, testScheduler).run(
            controller, "com.example.target", "goal", emptyList(), "screen-stale",
        )

        assertEquals("handback", status(result))
        assertEquals("configuration_changed", result["reason"]!!.jsonPrimitive.content)
        assertEquals(1, transport.calls)
        assertEquals(0, controller.actions.size)
    }

    @Test
    fun configurationChangingAfterDecisionStillNeverDispatches() = runTest {
        val active = settings(JevMode.ACTIVE)
        val off = settings(JevMode.OFF)
        var reads = 0
        val transport = ScriptedTransport(mutableListOf(answer("a0")))
        val controller = FakeAccessibilityController(snapshot("late-config"))
        val result = runner(
            runtime(transport) {
                reads++
                if (reads >= 5) off else active
            },
            testScheduler,
        ).run(controller, "com.example.target", "goal", emptyList(), "screen-config")

        assertEquals("handback", status(result))
        assertEquals("configuration_changed", result["reason"]!!.jsonPrimitive.content)
        assertTrue(reads >= 5)
        assertEquals(0, controller.actions.size)
    }

    @Test
    fun unknownReceiptStopsWithoutReplay() = runTest {
        val transport = ScriptedTransport(
            mutableListOf(answer("a0"), answer("a0")),
        )
        val controller = FakeAccessibilityController(
            snapshot("unknown"),
            receipt = ScreenActionReceipt("unknown", dispatched = true, reason = "driver_lost"),
        )
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            controller, "com.example.target", "goal", emptyList(), "screen-unknown",
        )

        assertEquals("outcome_unknown", status(result))
        assertEquals(1, controller.actions.size)
        assertEquals(1, transport.calls)
    }

    @Test
    fun cancellationDuringSuspendedDecisionProducesNoAction() = runTest {
        val transport = SuspendingTransport()
        val controller = FakeAccessibilityController(snapshot("cancel"))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val job = async(dispatcher) {
            JevScreenGoalRunner(
                runtime(transport) { settings(JevMode.ACTIVE) },
                mainDispatcher = dispatcher,
                settle = {},
            ).run(controller, "com.example.target", "goal", emptyList(), "screen-cancel")
        }
        runCurrent()
        assertEquals(1, transport.calls)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(0, controller.actions.size)
    }

    @Test
    fun unsafeDecisionHandsBackWithoutDispatch() = runTest {
        val transport = ScriptedTransport(mutableListOf(answer("a0", safe = 0.79)))
        val controller = FakeAccessibilityController(snapshot("unsafe"))
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            controller, "com.example.target", "goal", emptyList(), "screen-unsafe",
        )

        assertEquals("needs_user_action", status(result))
        assertEquals("action_not_read_only", result["reason"]!!.jsonPrimitive.content)
        assertEquals(0, controller.actions.size)
        assertEquals(1, transport.calls)
    }

    @Test
    fun noProgressStopsAtBoundedTwoActions() = runTest {
        val transport = ScriptedTransport(mutableListOf(answer("a0"), answer("a0")))
        val controller = FakeAccessibilityController(snapshot("same"))
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            controller, "com.example.target", "goal", emptyList(), "screen-no-progress", maxSteps = 4,
        )

        assertEquals("handback", status(result))
        assertEquals("no_progress", result["reason"]!!.jsonPrimitive.content)
        assertEquals(2, controller.actions.size)
        assertEquals(2, transport.calls)
    }

    @Test
    fun stepLimitIsHard() = runTest {
        val snapshots = listOf(snapshot("step-1"), snapshot("step-2"), snapshot("step-3"))
        val transport = ScriptedTransport(mutableListOf(answer("a0"), answer("a0"), answer("a0")))
        var step = 0
        lateinit var controller: FakeAccessibilityController
        controller = FakeAccessibilityController(snapshots.first()) {
            step++
            controller.current = snapshots[step.coerceAtMost(snapshots.lastIndex)]
        }
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            controller, "com.example.target", "goal", emptyList(), "screen-step", maxSteps = 2,
        )

        assertEquals("steps_exhausted", status(result))
        assertEquals(2, controller.actions.size)
        assertEquals(2, transport.calls)
    }

    @Test
    fun durationLimitIsHardBeforeAnyAction() = runTest {
        val transport = object : JevTransport {
            var calls = 0
            override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
                calls++
                delay(5_000)
                return JevTransportResponse.Http(200, answer("a0").toByteArray(), null)
            }
        }
        val controller = FakeAccessibilityController(snapshot("deadline"))
        val start = testScheduler.currentTime
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            controller,
            "com.example.target",
            "goal",
            emptyList(),
            "screen-deadline",
            maxDurationMs = 1_000,
        )

        assertNotEquals("completed", status(result))
        assertEquals(0, controller.actions.size)
        assertEquals(1, transport.calls)
        assertTrue(testScheduler.currentTime - start <= 1_000)
    }

    @Test
    fun candidatesExcludeSensitiveAndDisabledNodes() = runTest {
        val nodes = listOf(
            node("send", "发送", clickable = true),
            node("delete", "Delete", clickable = true),
            node("submit", "Submit", clickable = true),
            node("account", "Username", editable = true),
            node("password", "Password", editable = true),
            node("disabled", "Open disabled", clickable = true, enabled = false),
            node("disabled-scroll", "List", scrollable = true, enabled = false),
            node("safe", "Open draft", clickable = true),
            node("search", "Search", editable = true),
        )
        val runner = runner(runtime(ScriptedTransport(mutableListOf())) { settings(JevMode.ACTIVE) }, testScheduler)
        val candidates = runner.candidates(snapshot("candidates", nodes = nodes), listOf("query"))

        assertTrue(candidates.none { it.action.ref in setOf("send", "delete", "password", "disabled", "disabled-scroll") })
        assertTrue(candidates.any { it.action.ref == "safe" && it.action.kind.name == "CLICK" })
        assertTrue(candidates.any { it.action.ref == "search" && it.action.kind.name == "TYPE" && it.action.text == "query" })
    }
}
