package app.amber.core.jev

import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.AccessibilityTextMatch
import app.amber.core.automation.PhoneAutomationTakeoverController
import app.amber.core.automation.ScreenAction
import app.amber.core.automation.ScreenActionKind
import app.amber.core.automation.ScreenActionReceipt
import app.amber.core.automation.ScreenNode
import app.amber.core.automation.ScreenSnapshot
import app.amber.core.automation.ScreenSnapshotInvalidationRegistry
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PerAppSuccessPathRunnerMemoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

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

    private class FakeAccessibilityController(
        initial: ScreenSnapshot,
        private val receipt: ScreenActionReceipt = ScreenActionReceipt("ok", dispatched = true),
        private val onAction: (ScreenAction) -> Unit = {},
    ) : AccessibilityController {
        var current = initial
        val actions = mutableListOf<ScreenAction>()

        override fun captureScreenSnapshot(): ScreenSnapshot = current

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

        override fun returnToHostApp(hostPackageName: String): Boolean = true
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

    private fun runtime(transport: JevTransport, settingsProvider: () -> Settings) = JevRuntime(
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
        agentRuntime = AgentRuntimeSetting(autoReturnToHostOnScreenGoalComplete = false),
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

    private fun store() = FilePerAppSuccessPathStore(File(tmp.root, "jev/per_app_success_paths.json"))

    private fun runner(
        runtime: JevRuntime,
        scheduler: TestCoroutineScheduler,
        store: PerAppSuccessPathStore,
        takeover: PhoneAutomationTakeoverController? = null,
        settle: suspend () -> Unit = {},
    ) = JevScreenGoalRunner(
        runtime = runtime,
        mainDispatcher = StandardTestDispatcher(scheduler),
        settle = settle,
        takeoverController = takeover,
        successPathStore = store,
    )

    private fun node(
        ref: String,
        label: String,
        clickable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        viewId: String = "id/$ref",
        left: Int = 0,
        top: Int = 0,
        right: Int = 100,
        bottom: Int = 40,
    ) = ScreenNode(
        ref = ref,
        label = label,
        viewId = viewId,
        className = "android.view.View",
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        clickable = clickable,
        editable = editable,
        scrollable = false,
        enabled = enabled,
    )

    private fun snapshot(
        id: String,
        nodes: List<ScreenNode> = listOf(node("open", "Open draft", clickable = true)),
        packageName: String = "com.example.target",
    ) = ScreenSnapshot(id = id, packageName = packageName, windowId = 1, nodes = nodes)

    private fun status(result: kotlinx.serialization.json.JsonObject) =
        result["status"]!!.jsonPrimitive.content

    @Test
    fun acceptedSuccessWritesAndLaterRunReadsOnFreshSnapshot() = runTest {
        val memory = store()
        val firstTransport = ScriptedTransport(
            actionStepAnswers(answer("a0")).apply { add(answer("done", done = 0.96)) },
        )
        val firstSnap = snapshot("s1")
        val firstController = FakeAccessibilityController(firstSnap)
        val first = runner(runtime(firstTransport) { settings(JevMode.ACTIVE) }, testScheduler, memory)
        val completed = first.run(
            firstController, "com.example.target", "open the draft", emptyList(), "run-write",
        )
        assertEquals("completed", status(completed))
        assertEquals("true", completed["experience_written"]!!.jsonPrimitive.content)
        assertEquals(1, memory.pathsFor("com.example.target").size)
        val disk = File(tmp.root, "jev/per_app_success_paths.json").readText()
        assertFalse(disk.contains("s1"))
        assertFalse(disk.contains("\"ref\""))
        assertFalse(disk.contains("\"left\""))

        val secondNodes = listOf(
            node("other", "Other", clickable = true),
            node("fresh-open", "Open draft", clickable = true, viewId = "id/open"),
        )
        val secondSnap = snapshot("s2", nodes = secondNodes)
        val secondTransport = ScriptedTransport(
            actionStepAnswers(answer("a0")).apply { add(answer("done", done = 0.96)) },
        )
        val secondController = FakeAccessibilityController(secondSnap)
        val second = runner(runtime(secondTransport) { settings(JevMode.ACTIVE) }, testScheduler, memory)
        val guided = second.run(
            secondController, "com.example.target", "open the draft", emptyList(), "run-read",
        )
        assertEquals("completed", status(guided))
        assertEquals("true", guided["experience_guided"]!!.jsonPrimitive.content)
        assertEquals(listOf("fresh-open"), secondController.actions.map { it.ref })
        assertTrue(secondTransport.bodies.any { it.contains("experience_hint") })
    }

    @Test
    fun verificationOnlyAndFailedStatusesDoNotWrite() = runTest {
        val memory = store()
        val verifyTransport = ScriptedTransport(mutableListOf(answer("done", done = 0.96)))
        val verify = runner(
            runtime(verifyTransport) { settings(JevMode.ACTIVE, verification = JevMode.ACTIVE, actions = JevMode.OFF) },
            testScheduler,
            memory,
        ).run(
            FakeAccessibilityController(snapshot("verify-only")),
            "com.example.target",
            "goal",
            emptyList(),
            "run-verify-only",
        )
        assertEquals("completed", status(verify))
        assertEquals("false", verify["experience_written"]!!.jsonPrimitive.content)
        assertTrue(memory.pathsFor("com.example.target").isEmpty())

        val handback = runner(
            runtime(ScriptedTransport(actionStepAnswers(answer("a0", safe = 0.79)))) { settings(JevMode.ACTIVE) },
            testScheduler,
            memory,
        ).run(
            FakeAccessibilityController(snapshot("unsafe")),
            "com.example.target",
            "goal",
            emptyList(),
            "run-unsafe",
        )
        assertEquals("needs_user_action", status(handback))
        assertTrue(memory.pathsFor("com.example.target").isEmpty())

        val unknown = runner(
            runtime(ScriptedTransport(actionStepAnswers(answer("a0")))) { settings(JevMode.ACTIVE) },
            testScheduler,
            memory,
        ).run(
            FakeAccessibilityController(
                snapshot("unknown"),
                receipt = ScreenActionReceipt("unknown", dispatched = true, reason = "driver_lost"),
            ),
            "com.example.target",
            "goal",
            emptyList(),
            "run-unknown",
        )
        assertEquals("outcome_unknown", status(unknown))
        assertTrue(memory.pathsFor("com.example.target").isEmpty())

        val exhausted = runner(
            runtime(ScriptedTransport(actionStepAnswers(answer("a0")))) { settings(JevMode.ACTIVE) },
            testScheduler,
            memory,
        ).run(
            FakeAccessibilityController(snapshot("exh")),
            "com.example.target",
            "goal",
            emptyList(),
            "run-exhausted",
            maxSteps = 1,
        )
        assertEquals("steps_exhausted", status(exhausted))
        assertTrue(memory.pathsFor("com.example.target").isEmpty())
    }

    @Test
    fun typedValueIsNotPersistedOnAcceptedSuccess() = runTest {
        val memory = store()
        val nodes = listOf(node("search", "Search", editable = true))
        val transport = ScriptedTransport(
            actionStepAnswers(answer("a0")).apply { add(answer("done", done = 0.96)) },
        )
        val controller = FakeAccessibilityController(snapshot("type-s", nodes = nodes))
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler, memory).run(
            controller,
            "com.example.target",
            "search listings",
            listOf("hello query"),
            "run-type",
        )
        assertEquals("completed", status(result))
        assertEquals("true", result["experience_written"]!!.jsonPrimitive.content)
        val stored = memory.pathsFor("com.example.target").single()
        assertEquals("type", stored.steps.single().kind)
        assertEquals("Search", stored.steps.single().label)
        val disk = File(tmp.root, "jev/per_app_success_paths.json").readText()
        assertFalse(disk.contains("hello query"))
        assertFalse(disk.contains("\"text\""))
    }

    @Test
    fun ambiguousMemoryDoesNotDispatchPreferredTarget() = runTest {
        val memory = store()
        val seed = PerAppSuccessPathSanitizer.buildPath(
            packageName = "com.example.target",
            goal = "open the draft",
            steps = listOf(
                PerAppSuccessPathSanitizer.stepFrom(
                    ScreenActionKind.CLICK,
                    node("ignored", "Open draft", viewId = "id/open"),
                )!!,
            ),
            recordedAtMs = 1,
        )!!
        assertTrue(memory.recordSuccess(0, seed))
        val amb = snapshot(
            "amb",
            nodes = listOf(
                node("a", "Open draft", clickable = true, viewId = "id/open"),
                node("b", "Open draft", clickable = true, viewId = "id/open"),
            ),
        )
        val transport = ScriptedTransport(
            actionStepAnswers(answer("a0")).apply { add(answer("done", done = 0.96)) },
        )
        val controller = FakeAccessibilityController(amb)
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler, memory).run(
            controller, "com.example.target", "open the draft", emptyList(), "run-amb",
        )
        assertEquals("completed", status(result))
        assertEquals("false", result["experience_guided"]!!.jsonPrimitive.content)
        assertEquals(listOf("a"), controller.actions.map { it.ref })
        assertTrue(transport.bodies.none { it.contains("experience_hint") })
    }

    @Test
    fun pageDriftFallsBackToLiveCandidateOrder() = runTest {
        val memory = store()
        val seed = PerAppSuccessPathSanitizer.buildPath(
            packageName = "com.example.target",
            goal = "open settings",
            steps = listOf(
                PerAppSuccessPathSanitizer.stepFrom(
                    ScreenActionKind.CLICK,
                    node("settings", "Settings", viewId = "id/settings"),
                )!!,
            ),
            recordedAtMs = 1,
        )!!
        assertTrue(memory.recordSuccess(0, seed))
        val transport = ScriptedTransport(
            actionStepAnswers(answer("a0")).apply { add(answer("done", done = 0.96)) },
        )
        val controller = FakeAccessibilityController(snapshot("drift"))
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler, memory).run(
            controller, "com.example.target", "open settings", emptyList(), "run-drift",
        )
        assertEquals("completed", status(result))
        assertEquals("false", result["experience_guided"]!!.jsonPrimitive.content)
        assertEquals(listOf("open"), controller.actions.map { it.ref })
    }

    @Test
    fun staleSnapshotRejectsMemoryRankedCandidate() = runTest {
        val memory = store()
        val seed = PerAppSuccessPathSanitizer.buildPath(
            packageName = "com.example.target",
            goal = "open the draft",
            steps = listOf(
                PerAppSuccessPathSanitizer.stepFrom(
                    ScreenActionKind.CLICK,
                    node("open", "Open draft"),
                )!!,
            ),
            recordedAtMs = 1,
        )!!
        assertTrue(memory.recordSuccess(0, seed))
        lateinit var controller: FakeAccessibilityController
        val transport = ScriptedTransport(actionStepAnswers(answer("a0"))) { calls ->
            if (calls >= 2) controller.invalidateSnapshot("stale-s", "takeover_test")
        }
        controller = FakeAccessibilityController(snapshot("stale-s"))
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler, memory).run(
            controller, "com.example.target", "open the draft", emptyList(), "run-stale",
        )
        assertEquals("handback", status(result))
        assertTrue(controller.actions.isEmpty())
        assertEquals("false", result["experience_written"]!!.jsonPrimitive.content)
    }

    @Test
    fun memoryCannotBypassSideEffectDedup() = runTest {
        val memory = store()
        val seed = PerAppSuccessPathSanitizer.buildPath(
            packageName = "com.example.target",
            goal = "open the draft",
            steps = listOf(
                PerAppSuccessPathSanitizer.stepFrom(
                    ScreenActionKind.CLICK,
                    node("open", "Open draft"),
                )!!,
            ),
            recordedAtMs = 1,
        )!!
        assertTrue(memory.recordSuccess(0, seed))
        val takeover = PhoneAutomationTakeoverController()
        val transport = ScriptedTransport(actionStepAnswers(answer("a0"))) { calls ->
            if (calls == 1) {
                takeover.tryRecordSideEffectDispatch(
                    "side-s",
                    ScreenAction(ScreenActionKind.CLICK, "open"),
                )
            }
        }
        val controller = FakeAccessibilityController(snapshot("side-s"))
        val result = runner(
            runtime(transport) { settings(JevMode.ACTIVE) },
            testScheduler,
            memory,
            takeover = takeover,
        ).run(
            controller, "com.example.target", "open the draft", emptyList(), "run-side",
        )
        assertEquals("handback", status(result))
        assertEquals("side_effect_already_dispatched", result["reason"]!!.jsonPrimitive.content)
        assertTrue(controller.actions.isEmpty())
        assertEquals("false", result["experience_written"]!!.jsonPrimitive.content)
    }

    @Test
    fun settingsClearDropsCacheAndBlocksInflightSuccessWrite() = runTest {
        val memory = store()
        val firstTransport = ScriptedTransport(
            actionStepAnswers(answer("a0")).apply { add(answer("done", done = 0.96)) },
        )
        val first = runner(runtime(firstTransport) { settings(JevMode.ACTIVE) }, testScheduler, memory)
        assertEquals(
            "completed",
            status(
                first.run(
                    FakeAccessibilityController(snapshot("seed")),
                    "com.example.target",
                    "open the draft",
                    emptyList(),
                    "run-seed",
                ),
            ),
        )
        assertTrue(memory.hasPaths())

        lateinit var controller: FakeAccessibilityController
        val inflightTransport = ScriptedTransport(
            actionStepAnswers(answer("a0")).apply { add(answer("done", done = 0.96)) },
        )
        val inflight = runner(
            runtime(inflightTransport) { settings(JevMode.ACTIVE) },
            testScheduler,
            memory,
            settle = { clearPerAppSuccessPaths(memory) },
        )
        controller = FakeAccessibilityController(snapshot("inflight")) {
            controller.current = snapshot("inflight-2")
        }
        val result = inflight.run(
            controller, "com.example.target", "open the draft", emptyList(), "run-inflight",
        )
        assertEquals("completed", status(result))
        assertEquals("false", result["experience_written"]!!.jsonPrimitive.content)
        assertTrue(memory.pathsFor("com.example.target").isEmpty())
        val laterController = FakeAccessibilityController(
            snapshot(
                "later",
                nodes = listOf(
                    node("other", "Other", clickable = true),
                    node("fresh-open", "Open draft", clickable = true, viewId = "id/open"),
                ),
            ),
        )
        val later = runner(
            runtime(ScriptedTransport(actionStepAnswers(answer("a0")).apply { add(answer("done", done = 0.96)) })) {
                settings(JevMode.ACTIVE)
            },
            testScheduler,
            memory,
        ).run(
            laterController,
            "com.example.target",
            "open the draft",
            emptyList(),
            "run-after-clear",
        )
        assertEquals("false", later["experience_guided"]!!.jsonPrimitive.content)
        assertEquals(listOf("other"), laterController.actions.map { it.ref })
    }

    @Test
    fun scrollMemoryPromotesMatchingKindWithoutDroppingTheOtherDirection() = runTest {
        val memory = store()
        val list = node("list", "Items", viewId = "id/list").copy(scrollable = true, clickable = false)
        val seed = PerAppSuccessPathSanitizer.buildPath(
            packageName = "com.example.target",
            goal = "open the draft",
            steps = listOf(
                PerAppSuccessPathSanitizer.stepFrom(ScreenActionKind.SCROLL_BACKWARD, list)!!,
            ),
            recordedAtMs = 1,
        )!!
        assertTrue(memory.recordSuccess(0, seed))
        val transport = ScriptedTransport(
            actionStepAnswers(answer("a0")).apply { add(answer("done", done = 0.96)) },
        )
        val controller = FakeAccessibilityController(snapshot("scroll", nodes = listOf(list)))
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler, memory).run(
            controller, "com.example.target", "open the draft", emptyList(), "run-scroll",
        )
        assertEquals("completed", status(result))
        assertEquals("true", result["experience_guided"]!!.jsonPrimitive.content)
        assertEquals(listOf(ScreenActionKind.SCROLL_BACKWARD), controller.actions.map { it.kind })
        assertTrue(transport.bodies.any { it.contains("Scroll down") && it.contains("Scroll up") })
    }

    @Test
    fun multipleTypedTextsStayUnguidedOnSameRef() = runTest {
        val memory = store()
        val field = node("search", "Search", editable = true)
        val seed = PerAppSuccessPathSanitizer.buildPath(
            packageName = "com.example.target",
            goal = "search listings",
            steps = listOf(
                PerAppSuccessPathSanitizer.stepFrom(ScreenActionKind.TYPE, field, typedValue = "hello query")!!,
            ),
            recordedAtMs = 1,
        )!!
        assertTrue(memory.recordSuccess(0, seed))
        val transport = ScriptedTransport(
            actionStepAnswers(answer("a0")).apply { add(answer("done", done = 0.96)) },
        )
        val controller = FakeAccessibilityController(snapshot("types", nodes = listOf(field)))
        val result = runner(runtime(transport) { settings(JevMode.ACTIVE) }, testScheduler, memory).run(
            controller,
            "com.example.target",
            "search listings",
            listOf("alpha", "beta"),
            "run-types",
        )
        assertEquals("completed", status(result))
        assertEquals("false", result["experience_guided"]!!.jsonPrimitive.content)
        assertEquals("alpha", controller.actions.single().text)
        assertTrue(transport.bodies.any { it.contains("alpha") && it.contains("beta") })
        assertTrue(transport.bodies.none { it.contains("experience_hint") })
    }
}
