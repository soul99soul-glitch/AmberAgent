package app.amber.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.AccessibilityTextMatch
import app.amber.core.automation.PhoneAutomationTakeoverController
import app.amber.core.automation.ScreenAction
import app.amber.core.automation.ScreenActionKind
import app.amber.core.automation.ScreenActionReceipt
import app.amber.core.automation.ScreenNode
import app.amber.core.automation.ScreenSnapshot
import app.amber.core.automation.ScreenSnapshotInvalidationRegistry
import app.amber.core.jev.FilePerAppSuccessPathStore
import app.amber.core.jev.JevClient
import app.amber.core.jev.JevDataScope
import app.amber.core.jev.JevDecisionCoordinator
import app.amber.core.jev.JevHttpRequest
import app.amber.core.jev.JevMode
import app.amber.core.jev.JevPurpose
import app.amber.core.jev.JevRuntime
import app.amber.core.jev.JevScreenGoalRunner
import app.amber.core.jev.JevSetting
import app.amber.core.jev.JevTransport
import app.amber.core.jev.JevTransportResponse
import app.amber.core.jev.JevUsageStore
import app.amber.core.jev.PerAppCandidateIdentity
import app.amber.core.jev.PerAppSuccessPathPlanner
import app.amber.core.jev.PerAppSuccessPathSanitizer
import app.amber.core.jev.clearPerAppSuccessPaths
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device ART + fake [AccessibilityController] snapshots + real files for per-App
 * memory. Isolated fixture packages only; no payment, send, or user-data deletion.
 *
 * This is **not** real-controller / foreground evidence. That path is
 * [PerAppRealControllerDeviceTest].
 */
@RunWith(AndroidJUnit4::class)
class PerAppSuccessPathDeviceTest {
    private val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @After
    fun tearDown() {
        ScreenSnapshotInvalidationRegistry.clearForTest()
    }

    @Test
    fun samePackageFreshSnapshotUniquelyAssistsAndCrossPackageIsIsolated() = runBlocking {
        val memory = store()
        val first = runner(runtime(ScriptedTransport(actionStepAnswers(answer("a0")).also { it += answer("done", done = 0.96) })), memory)
        val write = first.run(
            FakeAccessibilityController(snapshot("s1")),
            "com.example.target",
            "open the draft",
            emptyList(),
            "run-write",
        )
        assertEquals("completed", status(write))
        assertEquals("true", write["experience_written"]!!.jsonPrimitive.content)
        assertTrue(memory.pathsFor("com.other.app").isEmpty())

        val secondNodes = listOf(
            node("other", "Other", clickable = true),
            node("fresh-open", "Open draft", clickable = true, viewId = "id/open"),
        )
        val secondController = FakeAccessibilityController(snapshot("s2", nodes = secondNodes))
        val guided = runner(
            runtime(ScriptedTransport(actionStepAnswers(answer("a0")).also { it += answer("done", done = 0.96) })),
            memory,
        ).run(secondController, "com.example.target", "open the draft", emptyList(), "run-read")
        assertEquals("completed", status(guided))
        assertEquals("true", guided["experience_guided"]!!.jsonPrimitive.content)
        assertEquals(listOf("fresh-open"), secondController.actions.map { it.ref })

        val foreign = FakeAccessibilityController(snapshot("foreign", packageName = "com.other.app"))
        val isolated = runner(
            runtime(ScriptedTransport(actionStepAnswers(answer("a0")).also { it += answer("done", done = 0.96) })),
            memory,
        ).run(foreign, "com.other.app", "open the draft", emptyList(), "run-foreign")
        assertEquals("false", isolated["experience_guided"]!!.jsonPrimitive.content)
        val disk = File(filesDir, "amber-s9-per-app.json").readText()
        assertFalse(disk.contains("s1"))
        assertFalse(disk.contains("\"left\""))
    }

    @Test
    fun ambiguousAndPageDriftFallBackWithoutInventingClicks() = runBlocking {
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
        val ambController = FakeAccessibilityController(
            snapshot(
                "amb",
                nodes = listOf(
                    node("a", "Open draft", clickable = true, viewId = "id/open"),
                    node("b", "Open draft", clickable = true, viewId = "id/open"),
                ),
            ),
        )
        val amb = runner(
            runtime(ScriptedTransport(actionStepAnswers(answer("a0")).also { it += answer("done", done = 0.96) })),
            memory,
        ).run(ambController, "com.example.target", "open the draft", emptyList(), "run-amb")
        assertEquals("false", amb["experience_guided"]!!.jsonPrimitive.content)
        assertEquals(listOf("a"), ambController.actions.map { it.ref })

        val driftMemory = store()
        val driftSeed = PerAppSuccessPathSanitizer.buildPath(
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
        assertTrue(driftMemory.recordSuccess(0, driftSeed))
        val driftController = FakeAccessibilityController(snapshot("drift"))
        val drift = runner(
            runtime(ScriptedTransport(actionStepAnswers(answer("a0")).also { it += answer("done", done = 0.96) })),
            driftMemory,
        ).run(driftController, "com.example.target", "open settings", emptyList(), "run-drift")
        assertEquals("false", drift["experience_guided"]!!.jsonPrimitive.content)
        assertEquals(listOf("open"), driftController.actions.map { it.ref })
    }

    @Test
    fun takeoverDoesNotRepeatAndClearBlocksInflightWrite() = runBlocking {
        val memory = store()
        val seed = PerAppSuccessPathSanitizer.buildPath(
            packageName = "com.example.target",
            goal = "open the draft",
            steps = listOf(PerAppSuccessPathSanitizer.stepFrom(ScreenActionKind.CLICK, node("open", "Open draft"))!!),
            recordedAtMs = 1,
        )!!
        assertTrue(memory.recordSuccess(0, seed))
        val takeover = PhoneAutomationTakeoverController()
        val sideTransport = ScriptedTransport(actionStepAnswers(answer("a0"))) { calls ->
            if (calls == 1) {
                takeover.tryRecordSideEffectDispatch("side-s", ScreenAction(ScreenActionKind.CLICK, "open"))
            }
        }
        val side = runner(runtime(sideTransport), memory, takeover).run(
            FakeAccessibilityController(snapshot("side-s")),
            "com.example.target",
            "open the draft",
            emptyList(),
            "run-side",
        )
        assertEquals("handback", status(side))
        assertEquals("side_effect_already_dispatched", side["reason"]!!.jsonPrimitive.content)
        assertEquals("false", side["experience_written"]!!.jsonPrimitive.content)

        lateinit var inflightController: FakeAccessibilityController
        val inflight = runner(
            runtime(ScriptedTransport(actionStepAnswers(answer("a0")).also { it += answer("done", done = 0.96) })),
            memory,
            settle = { clearPerAppSuccessPaths(memory) },
        )
        inflightController = FakeAccessibilityController(snapshot("inflight")) {
            inflightController.current = snapshot("inflight-2")
        }
        val cleared = inflight.run(
            inflightController,
            "com.example.target",
            "open the draft",
            emptyList(),
            "run-inflight",
        )
        assertEquals("completed", status(cleared))
        assertEquals("false", cleared["experience_written"]!!.jsonPrimitive.content)
        assertTrue(memory.pathsFor("com.example.target").isEmpty())
    }

    @Test
    fun plannerUniqueKindRefOnlyReordersLiveCandidates() {
        val snapshot = snapshot(
            "live",
            nodes = listOf(
                node("other", "Other", clickable = true),
                node("fresh-open", "Open draft", clickable = true, viewId = "id/open"),
            ),
        )
        val remembered = listOf(
            PerAppSuccessPathSanitizer.buildPath(
                packageName = "com.example.target",
                goal = "open the draft",
                steps = listOf(
                    PerAppSuccessPathSanitizer.stepFrom(
                        ScreenActionKind.CLICK,
                        node("ignored", "Open draft", viewId = "id/open"),
                    )!!,
                ),
                recordedAtMs = 1,
            )!!,
        )
        data class Cand(val kind: ScreenActionKind, val ref: String)
        val candidates = listOf(Cand(ScreenActionKind.CLICK, "other"), Cand(ScreenActionKind.CLICK, "fresh-open"))
        val guided = PerAppSuccessPathPlanner.guide(
            packageName = "com.example.target",
            snapshot = snapshot,
            remembered = remembered,
            goal = "open the draft",
            candidates = candidates,
            identityOf = { PerAppCandidateIdentity(it.kind, it.ref) },
        )
        assertEquals(listOf("fresh-open", "other"), guided.candidates.map { it.ref })
        assertTrue(guided.hint!!.contains("uniquely matched"))
    }

    private fun store() = FilePerAppSuccessPathStore(File(filesDir, "amber-s9-per-app.json").also { it.delete() })

    private fun runtime(transport: JevTransport) = JevRuntime(
        coordinator = JevDecisionCoordinator(
            client = JevClient(transport),
            apiKeyProvider = { "test-key" },
            usageStore = JevUsageStore.IN_MEMORY,
            clock = { 1_000_000L },
        ),
        settingsProvider = {
            Settings(
                jev = JevSetting(
                    enabled = true,
                    purposes = mapOf(JevPurpose.PHONE_ACTIONS to JevMode.ACTIVE, JevPurpose.PHONE_VERIFICATION to JevMode.ACTIVE),
                    dataScopes = setOf(JevDataScope.SCREEN_CONTENT, JevDataScope.TASK_TEXT),
                ),
                agentRuntime = AgentRuntimeSetting(autoReturnToHostOnScreenGoalComplete = false),
            )
        },
    )

    private fun runner(
        runtime: JevRuntime,
        store: FilePerAppSuccessPathStore,
        takeover: PhoneAutomationTakeoverController? = null,
        settle: suspend () -> Unit = {},
    ) = JevScreenGoalRunner(
        runtime = runtime,
        mainDispatcher = Dispatchers.Main.immediate,
        settle = settle,
        takeoverController = takeover,
        successPathStore = store,
    )

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
                body = (responses.removeFirstOrNull() ?: error("unexpected Jev request #$calls")).toByteArray(),
                retryAfterHeader = null,
            )
        }
    }

    private class FakeAccessibilityController(
        initial: ScreenSnapshot,
        private val onAction: (ScreenAction) -> Unit = {},
    ) : AccessibilityController {
        var current = initial
        val actions = mutableListOf<ScreenAction>()
        override fun captureScreenSnapshot(): ScreenSnapshot = current
        override fun performScreenAction(snapshot: ScreenSnapshot, action: ScreenAction): ScreenActionReceipt {
            if (isSnapshotInvalidated(snapshot.id)) {
                return ScreenActionReceipt("stale_snapshot", dispatched = false, reason = snapshotInvalidationReason(snapshot.id))
            }
            actions += action
            onAction(action)
            return ScreenActionReceipt("ok", dispatched = true)
        }
        override fun returnToHostApp(hostPackageName: String): Boolean = true
        override suspend fun tap(x: Float, y: Float, durationMillis: Long): Boolean = true
        override suspend fun longPress(x: Float, y: Float, durationMillis: Long): Boolean = true
        override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long): Boolean = true
        override fun setFocusedText(text: String): Boolean = true
        override fun dumpUiTree(maxNodes: Int): String = ""
        override fun findTextNodes(query: String, maxNodes: Int): List<AccessibilityTextMatch> = emptyList()
        override fun back(): Boolean = true
        override fun home(): Boolean = true
    }

    private fun node(
        ref: String,
        label: String,
        clickable: Boolean = false,
        viewId: String = "id/$ref",
    ) = ScreenNode(
        ref = ref,
        label = label,
        viewId = viewId,
        className = "android.view.View",
        left = 0,
        top = 0,
        right = 100,
        bottom = 40,
        clickable = clickable,
        editable = false,
        scrollable = false,
        enabled = true,
    )

    private fun snapshot(
        id: String,
        nodes: List<ScreenNode> = listOf(node("open", "Open draft", clickable = true)),
        packageName: String = "com.example.target",
    ) = ScreenSnapshot(id = id, packageName = packageName, windowId = 1, nodes = nodes)

    private fun status(result: kotlinx.serialization.json.JsonObject) = result["status"]!!.jsonPrimitive.content

    private fun actionStepAnswers(vararg answers: String) = answers.flatMap { listOf(it, it) }.toMutableList()

    private fun answer(action: String, safe: Double = 0.99, done: Double = 0.1): String {
        val safety = (0 until 30).joinToString(",") { "\"safe_a$it\":{\"type\":\"noul\",\"noul\":$safe}" }
        return """{"model":"jev-test","answers":{
            "action":{"type":"choice","choice":"$action","confidence":0.9},
            $safety,
            "done":{"type":"noul","noul":$done}
        }}"""
    }
}
