package app.amber.core.jev

import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.AccessibilityTextMatch
import app.amber.core.automation.ScreenAction
import app.amber.core.automation.ScreenActionKind
import app.amber.core.automation.ScreenActionReceipt
import app.amber.core.automation.ScreenNode
import app.amber.core.automation.ScreenSnapshot
import app.amber.core.automation.ScreenSnapshotInvalidationRegistry
import app.amber.core.settings.Settings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无障碍 Jev 链路的边界回归：授权、快照、派发、fresh 核验和硬限。 */
class JevScreenGoalRunnerTest {

    @After
    fun tearDown() {
        ScreenSnapshotInvalidationRegistry.clearForTest()
    }

    private class ScriptedTransport(
        private val responses: MutableList<String>,
        private val onCall: (Int) -> Unit = {},
    ) : JevTransport {
        var calls = 0
            private set
        val bodies = mutableListOf<String>()

        override suspend fun execute(request: JevHttpRequest): JevTransportResponse {
            calls++
            bodies += request.body
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
        val hostReturns = mutableListOf<String>()

        override fun captureScreenSnapshot(): ScreenSnapshot {
            captures++
            return current
        }

        override fun performScreenAction(snapshot: ScreenSnapshot, action: ScreenAction): ScreenActionReceipt {
            if (isSnapshotInvalidated(snapshot.id)) {
                return ScreenActionReceipt(
                    status = "stale_snapshot",
                    dispatched = false,
                    reason = snapshotInvalidationReason(snapshot.id) ?: "returned_to_host_snapshot_invalidated",
                )
            }
            actions += action
            onAction(action)
            return receipt
        }

        override fun returnToHostApp(hostPackageName: String): Boolean {
            hostReturns += hostPackageName
            return true
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
        verification: JevMode = mode,
        actions: JevMode = mode,
    ) = Settings(
        jev = JevSetting(
            enabled = verification != JevMode.OFF || actions != JevMode.OFF,
            purposes = buildMap {
                if (verification != JevMode.OFF) put(JevPurpose.PHONE_VERIFICATION, verification)
                if (actions != JevMode.OFF) put(JevPurpose.PHONE_ACTIONS, actions)
            },
            dataScopes = scopes,
        ),
    )

    private fun actionStepAnswers(vararg answers: String) =
        answers.flatMap { listOf(it, it) }.toMutableList()

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
            actionStepAnswers(answer("a0", safe = 0.84), answer("a0", safe = 0.88)).apply {
                add(answer("done", done = 0.96))
            },
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
        assertEquals(5, transport.calls)
        assertTrue(transport.bodies[0].contains("\"done\""))
        assertTrue(transport.bodies[1].contains("\"action\""))
        assertEquals(2, result["actions_dispatched"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, result["steps"]!!.jsonArray.size)
        // Post-action snapshots are reused as the next observation: initial + 2
        // post-action + 1 done-verification fresh read (same-id required) = 4.
        assertEquals(4, controller.captures)
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
        val shadowTransport = ScriptedTransport(actionStepAnswers(answer("a0")))
        val shadowController = FakeAccessibilityController(snapshot("shadow"))
        val shadow = runner(runtime(shadowTransport) { settings(JevMode.SHADOW) }, testScheduler).run(
            shadowController, "com.example.target", "goal", emptyList(), "screen-shadow",
        )
        assertEquals("shadow_trace", status(shadow))
        assertEquals(2, shadowTransport.calls)
        assertEquals(0, shadowController.actions.size)

        val dryTransport = ScriptedTransport(actionStepAnswers(answer("a0")))
        val dryController = FakeAccessibilityController(snapshot("dry"))
        val dry = runner(runtime(dryTransport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            dryController, "com.example.target", "goal", emptyList(), "screen-dry", dryRun = true,
        )
        assertEquals("shadow_trace", status(dry))
        assertEquals(2, dryTransport.calls)
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
    fun switchingForegroundPackageDuringAttachedCaptureDoesNotSendTheFrame() = runTest {
        val transport = ScriptedTransport(mutableListOf(answer("done", done = 0.99)))
        val controller = FakeAccessibilityController(snapshot("before-capture"))
        val runner = JevScreenGoalRunner(
            runtime = runtime(transport) {
                settings(JevMode.ACTIVE, JevPurpose.PHONE_ACTIONS.requiredScopes + JevDataScope.SCREEN_SCREENSHOTS)
            },
            mainDispatcher = StandardTestDispatcher(testScheduler),
            settle = {},
            screenshotProvider = { reference ->
                delay(10)
                controller.current = snapshot("other-app", packageName = "com.other.app")
                ScreenshotAttempt.Attached(JevImageAsset(reference, "png", byteArrayOf(1)))
            },
        )

        val result = runner.run(
            controller, "com.example.target", "open the article", emptyList(), "capture-package-change",
        )

        assertEquals("needs_user_action", status(result))
        assertEquals("foreground_package_changed", result["reason"]!!.jsonPrimitive.content)
        assertEquals("skipped:foreground_package_changed", result["image"]!!.jsonPrimitive.content)
        assertEquals("No captured bytes may leave after the package scope changed", 0, transport.calls)
        assertEquals(0, controller.actions.size)
    }

    @Test
    fun changingModelDuringAttachedCaptureDoesNotSendTheFrameUnderNewConfiguration() = runTest {
        var current = settings(
            JevMode.ACTIVE, JevPurpose.PHONE_ACTIONS.requiredScopes + JevDataScope.SCREEN_SCREENSHOTS,
        )
        val transport = ScriptedTransport(mutableListOf(answer("handback")))
        val controller = FakeAccessibilityController(snapshot("before-capture"))
        val runner = JevScreenGoalRunner(
            runtime = runtime(transport) { current },
            mainDispatcher = StandardTestDispatcher(testScheduler),
            settle = {},
            screenshotProvider = { reference ->
                delay(10)
                current = current.copy(jev = current.jev.copy(model = "jev-latest"))
                ScreenshotAttempt.Attached(JevImageAsset(reference, "png", byteArrayOf(1)))
            },
        )

        val result = runner.run(
            controller, "com.example.target", "open the article", emptyList(), "capture-model-change",
        )

        assertEquals("handback", status(result))
        assertEquals("configuration_changed", result["reason"]!!.jsonPrimitive.content)
        assertEquals("skipped:configuration_changed", result["image"]!!.jsonPrimitive.content)
        assertEquals("A captured frame must not be sent using a changed model configuration", 0, transport.calls)
        assertEquals(0, controller.actions.size)
    }

    @Test
    fun attachedCaptureStillAllowsSamePackageDecisionWhenAxIdChanges() = runTest {
        val transport = ScriptedTransport(actionStepAnswers(answer("handback")))
        val controller = FakeAccessibilityController(snapshot("before-capture"))
        val runner = JevScreenGoalRunner(
            runtime = runtime(transport) {
                settings(JevMode.ACTIVE, JevPurpose.PHONE_ACTIONS.requiredScopes + JevDataScope.SCREEN_SCREENSHOTS)
            },
            mainDispatcher = StandardTestDispatcher(testScheduler),
            settle = {},
            screenshotProvider = { reference ->
                delay(10)
                controller.current = snapshot("same-package-updated")
                ScreenshotAttempt.Attached(JevImageAsset(reference, "png", byteArrayOf(1)))
            },
        )

        val result = runner.run(
            controller, "com.example.target", "open the article", emptyList(), "capture-same-package-change",
        )

        assertEquals("handback", status(result))
        assertEquals("jev_requested_handback", result["reason"]!!.jsonPrimitive.content)
        assertEquals(2, transport.calls)
        assertEquals(0, controller.actions.size)
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
        val transport = ScriptedTransport(actionStepAnswers(answer("a0")))
        val controller = FakeAccessibilityController(
            snapshot("unknown"),
            receipt = ScreenActionReceipt("unknown", dispatched = true, reason = "driver_lost"),
        )
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            controller, "com.example.target", "goal", emptyList(), "screen-unknown",
        )

        assertEquals("outcome_unknown", status(result))
        assertEquals(1, controller.actions.size)
        assertEquals(2, transport.calls)
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
        val transport = ScriptedTransport(actionStepAnswers(answer("a0", safe = 0.79)))
        val controller = FakeAccessibilityController(snapshot("unsafe"))
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            controller, "com.example.target", "goal", emptyList(), "screen-unsafe",
        )

        assertEquals("needs_user_action", status(result))
        assertEquals("action_not_read_only", result["reason"]!!.jsonPrimitive.content)
        assertEquals(0, controller.actions.size)
        assertEquals(2, transport.calls)
    }

    @Test
    fun noProgressStopsAtBoundedTwoActions() = runTest {
        val transport = ScriptedTransport(actionStepAnswers(answer("a0"), answer("a0")))
        val controller = FakeAccessibilityController(snapshot("same"))
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            controller, "com.example.target", "goal", emptyList(), "screen-no-progress", maxSteps = 4,
        )

        assertEquals("handback", status(result))
        assertEquals("no_progress", result["reason"]!!.jsonPrimitive.content)
        assertEquals(2, controller.actions.size)
        assertEquals(4, transport.calls)
    }

    @Test
    fun stepLimitIsHard() = runTest {
        val snapshots = listOf(snapshot("step-1"), snapshot("step-2"), snapshot("step-3"))
        val transport = ScriptedTransport(actionStepAnswers(answer("a0"), answer("a0")))
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
        assertEquals(4, transport.calls)
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

    @Test
    fun completedGoalReturnsToHostAndInvalidatesPreReturnSnapshot() = runTest {
        val finalSnapshot = snapshot("final-target-snap")
        val transport = ScriptedTransport(mutableListOf(answer("done", done = 0.96)))
        val controller = FakeAccessibilityController(finalSnapshot)
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            controller = controller,
            packageName = "com.example.target",
            goal = "confirm target content",
            texts = emptyList(),
            runKey = "screen-return-host",
            hostPackageName = "app.amber.agent",
        )

        assertEquals("completed", status(result))
        assertEquals("true", result["returned_to_host"]!!.jsonPrimitive.content)
        assertEquals("completed_auto_return", result["host_return_reason"]!!.jsonPrimitive.content)
        assertEquals("true", result["snapshot_invalidated"]!!.jsonPrimitive.content)
        assertEquals("final-target-snap", result["invalidated_snapshot_id"]!!.jsonPrimitive.content)
        assertEquals(listOf("app.amber.agent"), controller.hostReturns)

        // Reusing the pre-return snapshot must fail closed so subsequent actions never mis-click the host UI.
        val staleReceipt = controller.performScreenAction(
            finalSnapshot,
            ScreenAction(ScreenActionKind.CLICK, "open"),
        )
        assertEquals("stale_snapshot", staleReceipt.status)
        assertEquals(false, staleReceipt.dispatched)
        assertEquals("returned_to_host_snapshot_invalidated", staleReceipt.reason)
    }

    @Test
    fun completedGoalOnHostPackageDoesNotTriggerHostReturn() = runTest {
        val hostSnapshot = snapshot("host-snap", packageName = "app.amber.agent")
        val transport = ScriptedTransport(mutableListOf(answer("done", done = 0.96)))
        val controller = FakeAccessibilityController(hostSnapshot)
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            controller = controller,
            packageName = "app.amber.agent",
            goal = "inspect own screen",
            texts = emptyList(),
            runKey = "screen-own-host",
            hostPackageName = "app.amber.agent",
        )

        assertEquals("completed", status(result))
        assertEquals("false", result["returned_to_host"]!!.jsonPrimitive.content)
        assertEquals("target_is_host", result["host_return_reason"]!!.jsonPrimitive.content)
        assertEquals("false", result["snapshot_invalidated"]!!.jsonPrimitive.content)
        assertTrue(controller.hostReturns.isEmpty())
    }

    @Test
    fun completedGoalRespectsDisabledAutoReturnSetting() = runTest {
        val targetSnapshot = snapshot("target-keep-fg")
        val transport = ScriptedTransport(mutableListOf(answer("done", done = 0.96)))
        val controller = FakeAccessibilityController(targetSnapshot)
        val disabledReturnSettings = settings(JevMode.ACTIVE).let { base ->
            base.copy(agentRuntime = base.agentRuntime.copy(autoReturnToHostOnScreenGoalComplete = false))
        }
        val result = runner(runtime(transport) { disabledReturnSettings }, testScheduler).run(
            controller = controller,
            packageName = "com.example.target",
            goal = "stay in target app",
            texts = emptyList(),
            runKey = "screen-disabled-return",
            hostPackageName = "app.amber.agent",
        )

        assertEquals("completed", status(result))
        assertEquals("false", result["returned_to_host"]!!.jsonPrimitive.content)
        assertEquals("disabled_by_setting", result["host_return_reason"]!!.jsonPrimitive.content)
        assertTrue(controller.hostReturns.isEmpty())

        // Per-call override (autoReturnToHost = false) also suppresses host return even when setting is enabled.
        val overrideController = FakeAccessibilityController(snapshot("target-override-off"))
        val overrideResult = runner(
            runtime(ScriptedTransport(mutableListOf(answer("done", done = 0.96)))) { settings(JevMode.ACTIVE) },
            testScheduler,
        ).run(
            controller = overrideController,
            packageName = "com.example.target",
            goal = "stay via override",
            texts = emptyList(),
            runKey = "screen-override-return",
            hostPackageName = "app.amber.agent",
            autoReturnToHost = false,
        )
        assertEquals("completed", status(overrideResult))
        assertEquals("false", overrideResult["returned_to_host"]!!.jsonPrimitive.content)
        assertEquals("disabled_by_setting", overrideResult["host_return_reason"]!!.jsonPrimitive.content)
        assertTrue(overrideController.hostReturns.isEmpty())

        // Launch failure still invalidates pre-return snapshot so stale refs cannot be reused.
        val failedLaunchSnapshot = snapshot("target-launch-fail")
        val failedLaunchController = FakeAccessibilityController(failedLaunchSnapshot)
        val failedLaunchResult = runner(
            runtime(ScriptedTransport(mutableListOf(answer("done", done = 0.96)))) { settings(JevMode.ACTIVE) },
            testScheduler,
        ).run(
            controller = failedLaunchController,
            packageName = "com.example.target",
            goal = "return fails gracefully",
            texts = emptyList(),
            runKey = "screen-launch-fail",
            hostPackageName = "app.amber.agent",
            returnToHostLauncher = { false },
        )
        assertEquals("completed", status(failedLaunchResult))
        assertEquals("false", failedLaunchResult["returned_to_host"]!!.jsonPrimitive.content)
        assertEquals("return_launch_failed", failedLaunchResult["host_return_reason"]!!.jsonPrimitive.content)
        assertEquals("true", failedLaunchResult["snapshot_invalidated"]!!.jsonPrimitive.content)
        assertTrue(failedLaunchController.isSnapshotInvalidated(failedLaunchSnapshot.id))
    }

    @Test
    fun nonCompletedOutcomesNeverStealForegroundBackToHost() = runTest {
        // 1. handback
        val handbackController = FakeAccessibilityController(snapshot("s-handback"))
        val handbackResult = runner(
            runtime(ScriptedTransport(actionStepAnswers(answer("handback")))) { settings(JevMode.ACTIVE) },
            testScheduler,
        ).run(handbackController, "com.example.target", "goal", emptyList(), "screen-hb")
        assertEquals("handback", status(handbackResult))
        assertEquals("false", handbackResult["returned_to_host"]!!.jsonPrimitive.content)
        assertEquals("not_completed_keep_foreground", handbackResult["host_return_reason"]!!.jsonPrimitive.content)
        assertTrue(handbackController.hostReturns.isEmpty())

        // 2. outcome_unknown
        val unknownController = FakeAccessibilityController(
            snapshot("s-unknown"),
            receipt = ScreenActionReceipt("unknown", dispatched = true, reason = "set_text_readback_unavailable"),
        )
        val unknownResult = runner(
            runtime(ScriptedTransport(actionStepAnswers(answer("a0")))) { settings(JevMode.ACTIVE) },
            testScheduler,
        ).run(unknownController, "com.example.target", "goal", emptyList(), "screen-unk")
        assertEquals("outcome_unknown", status(unknownResult))
        assertEquals("false", unknownResult["returned_to_host"]!!.jsonPrimitive.content)
        assertEquals("not_completed_keep_foreground", unknownResult["host_return_reason"]!!.jsonPrimitive.content)
        assertTrue(unknownController.hostReturns.isEmpty())

        // 3. needs_user_action
        val userActionController = FakeAccessibilityController(snapshot("s-user-action"))
        val userActionResult = runner(
            runtime(ScriptedTransport(actionStepAnswers(answer("a0", safe = 0.50)))) { settings(JevMode.ACTIVE) },
            testScheduler,
        ).run(userActionController, "com.example.target", "goal", emptyList(), "screen-ua")
        assertEquals("needs_user_action", status(userActionResult))
        assertEquals("false", userActionResult["returned_to_host"]!!.jsonPrimitive.content)
        assertTrue(userActionController.hostReturns.isEmpty())
    }

    @Test
    fun verificationOffIgnoresDoneAndUsesPhoneActions() = runTest {
        val transport = ScriptedTransport(mutableListOf(answer("a0", done = 0.99)))
        val controller = FakeAccessibilityController(snapshot("verify-off"))
        val result = runner(
            runtime(transport) { settings(JevMode.ACTIVE, verification = JevMode.OFF, actions = JevMode.ACTIVE) },
            testScheduler,
        ).run(controller, "com.example.target", "goal", emptyList(), "phone-verify-off", maxSteps = 1)

        assertNotEquals("completed", status(result))
        assertEquals(1, transport.calls)
        assertTrue(transport.bodies.single().contains("\"action\""))
        assertEquals(1, controller.actions.size)
    }

    @Test
    fun verificationActiveCompletesWithoutActionDecide() = runTest {
        val transport = ScriptedTransport(mutableListOf(answer("a0", done = 0.96)))
        val controller = FakeAccessibilityController(snapshot("verify-done"))
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            controller, "com.example.target", "goal", emptyList(), "phone-verify-done",
        )

        assertEquals("completed", status(result))
        assertEquals(1, transport.calls)
        assertTrue(transport.bodies.single().contains("\"done\""))
        assertFalse(transport.bodies.single().contains("\"action\""))
        assertEquals(0, controller.actions.size)
    }

    @Test
    fun shadowVerificationDoesNotCompleteThenActionsDispatch() = runTest {
        val transport = ScriptedTransport(actionStepAnswers(answer("a0", done = 0.99)))
        val controller = FakeAccessibilityController(snapshot("verify-shadow"))
        val result = runner(
            runtime(transport) { settings(JevMode.ACTIVE, verification = JevMode.SHADOW, actions = JevMode.ACTIVE) },
            testScheduler,
        ).run(controller, "com.example.target", "goal", emptyList(), "phone-verify-shadow", maxSteps = 1)

        assertEquals("steps_exhausted", status(result))
        assertEquals(2, transport.calls)
        assertTrue(transport.bodies[0].contains("\"done\""))
        assertTrue(transport.bodies[1].contains("\"action\""))
        assertEquals(1, controller.actions.size)
    }

    /**
     * Independent-config correction: verification ACTIVE + actions OFF is a
     * reachable read-only path. Zero action decide/dispatch is the retained
     * safety assertion from [actionsOffDisablesRunnerEvenIfVerificationActive];
     * verification may issue its own request. canExecute stays false so
     * screen_run_goal does not launch the target or auto-return to host.
     */
    @Test
    fun verificationActiveActionsOffCompletesWithoutDispatchOrHostReturn() = runTest {
        val transport = ScriptedTransport(mutableListOf(answer("done", done = 0.96)))
        val controller = FakeAccessibilityController(snapshot("verify-only-done"))
        val runtime = runtime(transport) {
            settings(JevMode.ACTIVE, verification = JevMode.ACTIVE, actions = JevMode.OFF)
        }
        val goalRunner = runner(runtime, testScheduler)
        assertFalse(goalRunner.canExecute())
        val result = goalRunner.run(
            controller, "com.example.target", "goal", emptyList(), "phone-verify-only-done",
            hostPackageName = "app.amber.agent",
        )
        assertEquals("completed", status(result))
        assertEquals(1, transport.calls)
        assertTrue(transport.bodies.single().contains("\"done\""))
        assertFalse(transport.bodies.single().contains("\"action\""))
        assertEquals(0, controller.actions.size)
        assertEquals(0, result["actions_dispatched"]!!.jsonPrimitive.content.toInt())
        assertEquals("false", result["returned_to_host"]!!.jsonPrimitive.content)
        assertEquals("verification_only_keep_foreground", result["host_return_reason"]!!.jsonPrimitive.content)
        assertEquals("false", result["snapshot_invalidated"]!!.jsonPrimitive.content)
        assertTrue(controller.hostReturns.isEmpty())
    }

    @Test
    fun verificationActiveActionsOffNotDoneHandsBackWithoutDispatch() = runTest {
        val transport = ScriptedTransport(mutableListOf(answer("done", done = 0.1)))
        val controller = FakeAccessibilityController(snapshot("verify-only-notdone"))
        val runtime = runtime(transport) {
            settings(JevMode.ACTIVE, verification = JevMode.ACTIVE, actions = JevMode.OFF)
        }
        val goalRunner = runner(runtime, testScheduler)
        assertFalse(goalRunner.canExecute())
        val result = goalRunner.run(
            controller, "com.example.target", "goal", emptyList(), "phone-verify-only-notdone",
        )
        assertEquals("handback", status(result))
        assertEquals("goal_not_verified", result["reason"]!!.jsonPrimitive.content)
        assertEquals(1, transport.calls)
        assertTrue(transport.bodies.single().contains("\"done\""))
        assertFalse(transport.bodies.any { it.contains("\"action\"") })
        assertEquals(0, controller.actions.size)
    }

    @Test
    fun verificationActiveActionsOffFailedDecisionHandsBackWithoutDispatch() = runTest {
        var calls = 0
        val transport = JevTransport {
            calls++
            JevTransportResponse.Http(500, "boom".toByteArray(), null)
        }
        val controller = FakeAccessibilityController(snapshot("verify-only-fail"))
        val runtime = runtime(transport) {
            settings(JevMode.ACTIVE, verification = JevMode.ACTIVE, actions = JevMode.OFF)
        }
        val goalRunner = runner(runtime, testScheduler)
        assertFalse(goalRunner.canExecute())
        val result = goalRunner.run(
            controller, "com.example.target", "goal", emptyList(), "phone-verify-only-fail",
        )
        assertEquals("handback", status(result))
        assertEquals("server_error", result["reason"]!!.jsonPrimitive.content)
        assertEquals(0, controller.actions.size)
        // ACTIVE verification HTTP 500 retries once, then fail-closes; never PHONE_ACTIONS.
        assertEquals(2, calls)
    }

    @Test
    fun verificationActiveActionsOffMissingScopeNeverRequests() = runTest {
        val transport = ScriptedTransport(mutableListOf(answer("done", done = 0.99)))
        val controller = FakeAccessibilityController(snapshot("verify-only-scope"))
        val runtime = runtime(transport) {
            settings(
                JevMode.ACTIVE,
                scopes = setOf(JevDataScope.TASK_TEXT),
                verification = JevMode.ACTIVE,
                actions = JevMode.OFF,
            )
        }
        val goalRunner = runner(runtime, testScheduler)
        assertFalse(goalRunner.canExecute())
        val result = goalRunner.run(
            controller, "com.example.target", "goal", emptyList(), "phone-verify-only-scope",
        )
        assertEquals("handback", status(result))
        assertEquals("screen_content_or_task_text_not_authorized", result["reason"]!!.jsonPrimitive.content)
        assertEquals(0, controller.captures)
        assertEquals(0, transport.calls)
        assertEquals(0, controller.actions.size)
    }

    @Test
    fun verificationActiveActionsOffBudgetExhaustedHandsBackWithoutDispatch() = runTest {
        val transport = ScriptedTransport(
            MutableList(JevBudget.PHONE_MAX_REQUESTS + 4) { answer("done", done = 0.99) },
        )
        val runtime = runtime(transport) {
            settings(JevMode.ACTIVE, verification = JevMode.ACTIVE, actions = JevMode.OFF)
        }
        repeat(JevBudget.PHONE_MAX_REQUESTS) { index ->
            runtime.decide(
                purpose = JevPurpose.PHONE_VERIFICATION,
                runKey = "phone-verify-only-budget",
                state = buildJsonObject { put("n", index) },
                questions = mapOf("done" to JevQuestion.Noul("done?")),
                requiredScopes = JevPurpose.PHONE_VERIFICATION.requiredScopes,
            )
        }
        val controller = FakeAccessibilityController(snapshot("verify-only-budget"))
        val goalRunner = runner(runtime, testScheduler)
        assertFalse(goalRunner.canExecute())
        val result = goalRunner.run(
            controller, "com.example.target", "goal", emptyList(), "phone-verify-only-budget",
        )
        assertEquals("handback", status(result))
        assertEquals("budget_exhausted", result["reason"]!!.jsonPrimitive.content)
        assertEquals(0, controller.actions.size)
        assertEquals(JevBudget.PHONE_MAX_REQUESTS, transport.calls)
    }

    @Test
    fun verificationShadowActionsOffTracesWithoutCompletingOrDispatch() = runTest {
        val transport = ScriptedTransport(mutableListOf(answer("done", done = 0.99)))
        val controller = FakeAccessibilityController(snapshot("verify-shadow-only"))
        val runtime = runtime(transport) {
            settings(JevMode.SHADOW, verification = JevMode.SHADOW, actions = JevMode.OFF)
        }
        val goalRunner = runner(runtime, testScheduler)
        assertFalse(goalRunner.canExecute())
        val result = goalRunner.run(
            controller, "com.example.target", "goal", emptyList(), "phone-verify-shadow-only",
        )
        assertEquals("shadow_trace", status(result))
        assertEquals(1, transport.calls)
        assertTrue(transport.bodies.single().contains("\"done\""))
        assertFalse(transport.bodies.single().contains("\"action\""))
        assertEquals(0, controller.actions.size)
        assertTrue(controller.hostReturns.isEmpty())
    }

    @Test
    fun failedDecisionFallsBackWithoutDispatch() = runTest {
        var calls = 0
        val transport = JevTransport {
            calls++
            JevTransportResponse.Http(500, "boom".toByteArray(), null)
        }
        val controller = FakeAccessibilityController(snapshot("fail"))
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler).run(
            controller, "com.example.target", "goal", emptyList(), "phone-fail",
        )
        assertEquals("handback", status(result))
        assertEquals("server_error", result["reason"]!!.jsonPrimitive.content)
        assertEquals(0, controller.actions.size)
        // ACTIVE verification HTTP 500 retries once, then fail-closes before PHONE_ACTIONS.
        assertEquals(2, calls)
    }

    @Test
    fun phoneBudgetExhaustedHandsBackWithoutDispatch() = runTest {
        val transport = ScriptedTransport(
            MutableList(JevBudget.PHONE_MAX_REQUESTS + 4) { answer("a0") },
        )
        val runtime = runtime(transport) { settings(JevMode.ACTIVE) }
        repeat(JevBudget.PHONE_MAX_REQUESTS) { index ->
            runtime.decide(
                purpose = JevPurpose.PHONE_ACTIONS,
                runKey = "screen-budget",
                state = buildJsonObject { put("n", index) },
                questions = mapOf(
                    "action" to JevQuestion.Choice("pick", mapOf("a0" to "x", "handback" to "h")),
                ),
                requiredScopes = JevPurpose.PHONE_ACTIONS.requiredScopes,
            )
        }
        val controller = FakeAccessibilityController(snapshot("budget"))
        val result = runner(runtime, testScheduler).run(
            controller, "com.example.target", "goal", emptyList(), "screen-budget",
        )
        assertEquals("handback", status(result))
        assertEquals("budget_exhausted", result["reason"]!!.jsonPrimitive.content)
        assertEquals(0, controller.actions.size)
    }
}
