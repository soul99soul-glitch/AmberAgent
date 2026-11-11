package app.amber.feature.tools

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import app.amber.ai.ui.UIMessagePart
import app.amber.core.automation.AccessibilityActive
import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.AccessibilityTextMatch
import app.amber.core.automation.AmberAccessibilityService
import app.amber.core.automation.PhoneAutomationTakeoverController
import app.amber.core.automation.PhoneTakeoverPhase
import app.amber.core.automation.ScreenCaptureManager
import app.amber.core.automation.ScreenCapturePermissionActivity
import app.amber.core.automation.ScreenCaptureService
import app.amber.core.automation.ScreenNode
import app.amber.core.automation.ScreenOcrBlock
import app.amber.core.automation.ScreenOcrEngine
import app.amber.core.automation.ScreenOcrMode
import app.amber.core.automation.ScreenOcrObservation
import app.amber.core.automation.ScreenSnapshot
import app.amber.core.automation.ScreenTreeCoverageAnalyzer
import app.amber.core.automation.ScreenTreeCoverageReport
import app.amber.core.automation.NormalizedScreenBounds
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.runtime.ToolActivityStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScreenAutomationAcceptanceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        AccessibilityActive.activeController = null
        Dispatchers.resetMain()
    }

    @Test
    fun emptyEditableHintDoesNotCountAsAnEnteredValue() = runTest {
        // Use the real Android-node projection: its display label includes the hint.
        val projected = projectedEditable(text = "", hint = "Search")
        assertEquals("Search", projected.nodes.single().label)
        val result = clickWithExpectation(FakeController(projected), """{"kind":"value_equals","value":"Search"}""")
        assertFalse(result.getValue("verification").jsonObject.getValue("met").jsonPrimitive.boolean)
    }

    @Test
    fun enteredValueIsComparedWithoutDisplayLabelTruncation() = runTest {
        val value = "x".repeat(240)
        val projected = projectedEditable(text = value)
        assertTrue(projected.nodes.single().label.length < value.length)
        assertEquals(value, projected.nodes.single().editableValue)
        val result = clickWithExpectation(FakeController(projected), """{"kind":"value_equals","value":"$value"}""")
        assertTrue(result.getValue("verification").jsonObject.getValue("met").jsonPrimitive.boolean)
    }

    @Test
    fun passwordTextNeverEntersValueAcceptance() = runTest {
        val projected = projectedEditable(text = "private text", password = true)
        assertNull(projected.nodes.single().editableValue)
        val result = clickWithExpectation(FakeController(projected), """{"kind":"value_equals","value":"private text"}""")
        assertFalse(result.getValue("verification").jsonObject.getValue("met").jsonPrimitive.boolean)
    }

    @Test
    fun hiddenLegacySearchMatchCannotSatisfyVisibleAcceptance() = runTest {
        val controller = FakeController(snapshot(listOf(node("root/0", "Current page"))))
        // Legacy text search includes cached hidden nodes; the acceptance observation does not.
        controller.searchMatches = listOf(textMatch("Saved"))
        val result = clickWithExpectation(controller, """{"kind":"visible","target":"Saved"}""")
        assertFalse(result.getValue("verification").jsonObject.getValue("met").jsonPrimitive.boolean)
    }

    @Test
    fun aSubstringOfAnotherLabelCannotSatisfyExactAcceptance() = runTest {
        val controller = FakeController(snapshot(listOf(node("root/0", "Not saved"))))
        controller.searchMatches = listOf(textMatch("Not saved"))
        val result = clickWithExpectation(controller, """{"kind":"enabled","target":"saved"}""")
        assertFalse(result.getValue("verification").jsonObject.getValue("met").jsonPrimitive.boolean)
    }

    @Test
    fun anAggregateParentLabelDoesNotDuplicateTheVisibleTarget() = runTest {
        val controller = FakeController(snapshot(listOf(
            node("root", "Saved").copy(hasOwnLabel = false),
            node("root/0", "Saved"),
        )))
        val result = clickWithExpectation(controller, """{"kind":"enabled","target":"Saved"}""")
        assertTrue(result.getValue("verification").jsonObject.getValue("met").jsonPrimitive.boolean)
    }

    @Test
    fun listCollectionContainsOnlyTheChosenContainerSubtree() = runTest {
        val controller = FakeController(snapshot(listOf(
            node("root", "Whole page"),
            node("root/0", "Target list", scrollable = true),
            node("root/0/0", "Target row"),
            node("root/1", "Unrelated sidebar"),
        )))
        AccessibilityActive.activeController = controller
        val result = executeTool("screen_collect_list", """{"container":"Target list","direction":"down","max_swipes":0}""")
        assertEquals(
            listOf("Target row"),
            result.getValue("items").jsonArray.map { it.jsonObject.getValue("label").jsonPrimitive.content },
        )
    }

    @Test
    fun listCollectionDoesNotCountSyntheticRowWrappersAsAdditionalItems() = runTest {
        val controller = FakeController(snapshot(listOf(
            node("root/0", "Target list", scrollable = true),
            node("root/0/0", "First row").copy(hasOwnLabel = false),
            node("root/0/0/0", "First row"),
            node("root/0/1", "Second row").copy(hasOwnLabel = false),
            node("root/0/1/0", "Second row"),
        )))
        AccessibilityActive.activeController = controller
        val result = executeTool(
            "screen_collect_list",
            """{"container":"Target list","direction":"down","max_swipes":0,"max_items":2}""",
        )
        assertEquals(
            listOf("First row", "Second row"),
            result.getValue("items").jsonArray.map { it.jsonObject.getValue("label").jsonPrimitive.content },
        )
    }

    @Test
    fun cancellingAnInFlightActionReleasesTheSingleOperationOwner() = runTest {
        val entered = CompletableDeferred<Unit>()
        val controller = FakeController(snapshot(emptyList())).apply {
            onTap = { entered.complete(Unit); awaitCancellation() }
        }
        AccessibilityActive.activeController = controller
        val activities = AgentToolActivityStore()
        val tools = ScreenAutomationTools(context, ScreenCaptureManager(context), activities)
        val pending = launch { executeTool("screen_click", """{"x":1,"y":1}""", tools) }
        entered.await()
        val activity = activities.sandboxActivity.value

        val concurrent = runCatching { executeTool("screen_click", """{"x":1,"y":1}""", tools) }.exceptionOrNull()
        assertTrue(concurrent is IllegalStateException)
        assertEquals(1, controller.tapCalls)
        assertEquals(activity, activities.sandboxActivity.value)

        pending.cancelAndJoin()
        assertEquals(ToolActivityStatus.FAILED, activities.sandboxActivity.value?.status)
        controller.onTap = { true }
        executeTool("screen_click", """{"x":1,"y":1}""", tools)
        assertEquals(2, controller.tapCalls)
        assertEquals(ToolActivityStatus.SUCCEEDED, activities.sandboxActivity.value?.status)
    }

    @Test
    fun stoppedRunRejectsCompositeFlowWithoutStoppingAnotherRunOrApprovedSingleActions() = runTest {
        val controller = FakeController(snapshot(emptyList()))
        AccessibilityActive.activeController = controller
        val tools = ScreenAutomationTools(context, ScreenCaptureManager(context), AgentToolActivityStore())
        assertTrue(executeTool("screen_stop", "{}", tools, "old").getValue("stopped").jsonPrimitive.boolean)
        assertTrue(executeTool("screen_stop", "{}", tools, "old").getValue("already_stopped").jsonPrimitive.boolean)

        val flow = """{"package_name":"test.target","steps":[{"target":"Next","operation":"click"}]}"""
        val stopped = runCatching { executeTool("screen_run_flow", flow, tools, "old") }.exceptionOrNull()
        assertTrue(stopped is IllegalStateException)
        assertEquals("no_scope", executeTool("screen_run_flow", flow, tools, "new").getValue("status").jsonPrimitive.content)
        assertFalse(executeTool("screen_status", "{}", tools, "new").getValue("session_stopped").jsonPrimitive.boolean)
        executeTool("screen_click", """{"x":1,"y":1}""", tools, "old")
        assertEquals(1, controller.tapCalls)
    }

    @Test
    fun resumedTakeoverReacquiresFreshSnapshotBeforeDispatchingTool() = runTest {
        val takeover = PhoneAutomationTakeoverController(clock = { testScheduler.currentTime })
        takeover.startSession(runId = "run", packageName = "test.target", goal = "goal", maxSteps = 1)
        takeover.requestUserTakeover(reason = "user_requested", controller = null)
        val controller = FakeController(snapshot(listOf(node("root/0", "Next"))))
        AccessibilityActive.activeController = controller
        val tools = ScreenAutomationTools(
            context = context,
            screenCaptureManager = ScreenCaptureManager(context),
            activityStore = AgentToolActivityStore(),
            takeoverController = takeover,
        )

        val pending = launch { executeTool("screen_click", """{"x":1,"y":1}""", tools) }
        runCurrent()
        assertEquals(PhoneTakeoverPhase.PAUSED, takeover.state.value.phase)

        assertTrue(takeover.requestResume())
        advanceUntilIdle()
        pending.join()

        // RESUMING must be closed with a post-takeover capture before the tap is dispatched,
        // otherwise the banner would stay on "re-reading screen" forever.
        assertEquals(PhoneTakeoverPhase.AUTOMATED, takeover.state.value.phase)
        assertEquals(1, controller.captureCalls)
        assertEquals(1, controller.tapCalls)
    }

    @Test
    fun resumeWithoutReadableScreenStaysPausedAndDispatchesNothing() = runTest {
        val takeover = PhoneAutomationTakeoverController(clock = { testScheduler.currentTime })
        takeover.startSession(runId = "run", packageName = "test.target", goal = "goal", maxSteps = 1)
        takeover.requestUserTakeover(reason = "user_requested", controller = null)
        val controller = FakeController(snapshot(listOf(node("root/0", "Next")))).apply {
            snapshotAvailable = false
        }
        AccessibilityActive.activeController = controller
        val tools = ScreenAutomationTools(
            context = context,
            screenCaptureManager = ScreenCaptureManager(context),
            activityStore = AgentToolActivityStore(),
            takeoverController = takeover,
        )

        val pending = launch { executeTool("screen_click", """{"x":1,"y":1}""", tools) }
        runCurrent()
        assertTrue(takeover.requestResume())
        advanceUntilIdle()
        pending.join()

        assertEquals(PhoneTakeoverPhase.PAUSED, takeover.state.value.phase)
        assertEquals("fresh_snapshot_unavailable", takeover.state.value.pauseReason)
        assertEquals(0, controller.tapCalls)
    }

    @Test
    fun screenReadUiOcrTextIsRedactedLikeItsBlocks() = runTest {
        val observation = ScreenOcrObservation(
            mode = ScreenOcrMode.AUTO,
            triggered = true,
            triggerReason = "webview_or_canvas_low_text",
            coverage = ScreenTreeCoverageReport(0, 0, 0, 0, 1, 0f, true, "webview_or_canvas_low_text"),
            blocks = listOf(
                ScreenOcrBlock(
                    id = "ocr_0",
                    text = "验证码 839201 请勿泄露",
                    left = 0,
                    top = 0,
                    right = 100,
                    bottom = 40,
                    normalizedBounds = NormalizedScreenBounds.fromPixelBounds(0, 0, 100, 40, 100, 100),
                    isSensitive = true,
                ),
            ),
            displayWidth = 100,
            displayHeight = 100,
            pixelFrame = true,
        )
        val controller = FakeController(snapshot(listOf(node("root/0", "Next"))))
        AccessibilityActive.activeController = controller
        val tools = ScreenAutomationTools(
            context = context,
            screenCaptureManager = ScreenCaptureManager(context),
            activityStore = AgentToolActivityStore(),
            ocrEngine = StaticOcrEngine(observation),
        )

        val result = executeTool("screen_read_ui", "{}", tools)
        val ocrText = result.getValue("ocr_text").jsonPrimitive.content
        assertFalse(ocrText.contains("839201"))
        assertTrue(ocrText.contains("******"))
        assertTrue(result.getValue("ocr_pixel_frame").jsonPrimitive.boolean)
    }

    @Test
    fun staleTakeoverFromDifferentRunDoesNotBlockNewRunAndNotificationBlocksHighRiskApprove() = runTest {
        val takeover = PhoneAutomationTakeoverController(clock = { testScheduler.currentTime })
        takeover.startSession(runId = "old-run", packageName = "test.target", goal = "old", maxSteps = 2)
        takeover.confirmPaused(snapshotId = "old-snap", reason = "user_requested")
        val controller = FakeController(snapshot(listOf(node("root/0", "Next"))))
        AccessibilityActive.activeController = controller
        val tools = ScreenAutomationTools(
            context = context,
            screenCaptureManager = ScreenCaptureManager(context),
            activityStore = AgentToolActivityStore(),
            takeoverController = takeover,
        )

        // New run ("new-run") must clear orphaned "old-run" takeover session and succeed.
        val result = executeTool("screen_click", """{"x":1,"y":1}""", tools, runId = "new-run")
        assertTrue(result.getValue("success").jsonPrimitive.boolean)
        assertEquals(PhoneTakeoverPhase.IDLE, takeover.state.value.phase)

        // AgentBubbleReducer ignores takeover session from another runId.
        val bubble = app.amber.feature.bubble.AgentBubbleReducer.reduce(
            previous = app.amber.feature.bubble.AgentBubbleUiState(),
            pickedConversationId = "conv-1",
            activity = null,
            messages = emptyList(),
            nowMillis = 1_000L,
            takeoverSession = app.amber.core.automation.PhoneAutomationSessionState(
                runId = "old-run",
                phase = PhoneTakeoverPhase.PAUSED,
                pauseReason = "user_requested",
            ),
            activeRunId = "new-run",
        )
        assertEquals(PhoneTakeoverPhase.IDLE, bubble.takeoverPhase)

        // High-risk / mandatoryApproval tools require in-app approval only (no notification approve).
        val highRiskTool = UIMessagePart.Tool(
            toolCallId = "tc-1",
            toolName = "screen_run_flow",
            input = """{"package_name":"test.target","steps":[]}""",
            output = emptyList(),
        )
        assertTrue(app.amber.feature.runtime.AgentLiveStatusNotifier.requiresInAppApprovalOnly(highRiskTool))
    }

    @Test
    fun buildingToolsForSecondRunDoesNotTerminateActiveUserTakeoverPauseOnFirstRun() = runTest {
        val takeover = PhoneAutomationTakeoverController(clock = { testScheduler.currentTime })
        takeover.startSession(runId = "run-A", packageName = "test.target", goal = "goal-A", maxSteps = 2)
        takeover.confirmPaused(snapshotId = "snap-A", reason = "user_requested")
        val controller = FakeController(snapshot(listOf(node("root/0", "Next"))))
        AccessibilityActive.activeController = controller
        val tools = ScreenAutomationTools(
            context = context,
            screenCaptureManager = ScreenCaptureManager(context),
            activityStore = AgentToolActivityStore(),
            takeoverController = takeover,
        )

        val runATools = tools.getTools("run-A")
        // Another conversation / background task builds its tool catalog before run-A's primitive executes.
        tools.getTools("run-B")

        val pendingTap = launch {
            runATools.single { it.name == "screen_click" }.execute(Json.parseToJsonElement("""{"x":1,"y":1}"""))
        }
        runCurrent()

        assertEquals(PhoneTakeoverPhase.PAUSED, takeover.state.value.phase)
        assertEquals(0, controller.tapCalls)

        assertTrue(takeover.requestResume())
        advanceUntilIdle()
        pendingTap.join()
        assertEquals(1, controller.tapCalls)
    }

    @Test
    fun screenCapturePermissionActivityRecreationAndStaleRequestGuard() {
        val captureManager = ScreenCaptureManager(context)
        org.koin.core.context.startKoin {
            modules(org.koin.dsl.module { single { captureManager } })
        }
        try {
            val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
            shadowApp.clearNextStartedActivities()
            val intent = android.content.Intent(context, ScreenCapturePermissionActivity::class.java)
                .putExtra(ScreenCaptureManager.EXTRA_REQUEST_ID, "stale-request-id")
            val activityController = Robolectric.buildActivity(ScreenCapturePermissionActivity::class.java, intent)
                .setup()
            // Initial creation launches one system MediaProjection permission intent
            val shadowActivity = org.robolectric.Shadows.shadowOf(activityController.get())
            val firstLaunched = shadowActivity.nextStartedActivityForResult
            assertTrue(firstLaunched != null)

            // Recreating due to configuration change must NOT launch a duplicate permission dialog
            activityController.recreate()
            val recreatedShadow = org.robolectric.Shadows.shadowOf(activityController.get())
            assertNull(recreatedShadow.nextStartedActivityForResult)

            // Receiving RESULT_OK for an expired/non-pending requestId must NOT start ScreenCaptureService
            recreatedShadow.receiveResult(
                firstLaunched!!.intent,
                android.app.Activity.RESULT_OK,
                android.content.Intent(),
            )
            assertNull(shadowApp.nextStartedService)
        } finally {
            org.koin.core.context.stopKoin()
        }
    }

    private suspend fun clickWithExpectation(controller: FakeController, expectation: String): JsonObject {
        AccessibilityActive.activeController = controller
        return executeTool("screen_click", """{"x":1,"y":1,"expectation":$expectation}""")
    }

    private suspend fun executeTool(
        name: String,
        input: String,
        tools: ScreenAutomationTools = ScreenAutomationTools(context, ScreenCaptureManager(context), AgentToolActivityStore()),
        runId: String? = null,
    ): JsonObject {
        val output = tools.getTools(runId).single { it.name == name }.execute(Json.parseToJsonElement(input))
        return Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
    }

    private fun snapshot(nodes: List<ScreenNode>) = ScreenSnapshot("snapshot", "test.target", 1, nodes)

    private fun node(ref: String, label: String, scrollable: Boolean = false) = ScreenNode(
        ref = ref, label = label, viewId = "", className = "View",
        left = 0, top = 0, right = 200, bottom = 200,
        clickable = false, editable = false, scrollable = scrollable, enabled = true,
    )

    private fun textMatch(text: String) = AccessibilityTextMatch(
        text = text, contentDescription = "", className = "View", viewId = null,
        bounds = Rect(0, 0, 200, 200), clickable = false, enabled = true,
    )

    private fun projectedEditable(text: String, hint: String = "", password: Boolean = false): ScreenSnapshot {
        val serviceController = Robolectric.buildService(AmberAccessibilityService::class.java).create()
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = "test.target"
            className = "android.widget.EditText"
            this.text = text
            hintText = hint
            isPassword = password
            isEditable = true
            isEnabled = true
            isVisibleToUser = true
            setBoundsInScreen(Rect(0, 0, 200, 80))
        }
        return try {
            val readerType = Class.forName("app.amber.core.automation.AccessibilityNativeScreen")
            val reader = readerType.getDeclaredConstructor(AccessibilityService::class.java)
                .apply { isAccessible = true }.newInstance(serviceController.get())
            val method = readerType.getDeclaredMethod(
                "buildScreenSnapshot", AccessibilityNodeInfo::class.java,
                Int::class.javaPrimitiveType, String::class.java,
            ).apply { isAccessible = true }
            method.invoke(reader, root, 1, "test.target") as ScreenSnapshot
        } finally {
            root.recycle()
            serviceController.destroy()
        }
    }

    private class StaticOcrEngine(private val observation: ScreenOcrObservation) : ScreenOcrEngine {
        override suspend fun observe(
            snapshot: ScreenSnapshot?,
            uiTreeDump: String,
            mode: ScreenOcrMode,
            forceReason: String?,
        ): ScreenOcrObservation = observation
    }

    private class FakeController(private val observation: ScreenSnapshot) : AccessibilityController {
        var searchMatches: List<AccessibilityTextMatch> = emptyList()
        var onTap: suspend () -> Boolean = { true }
        var snapshotAvailable = true
        var captureCalls = 0
        var tapCalls = 0
        override fun captureScreenSnapshot(): ScreenSnapshot? {
            captureCalls++
            return observation.takeIf { snapshotAvailable }
        }
        override fun findTextNodes(query: String, maxNodes: Int): List<AccessibilityTextMatch> =
            searchMatches.filter { it.text.contains(query, ignoreCase = true) }
        override suspend fun tap(x: Float, y: Float, durationMillis: Long): Boolean {
            tapCalls++
            return onTap()
        }
        override suspend fun longPress(x: Float, y: Float, durationMillis: Long): Boolean = true
        override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long): Boolean = true
        override fun setFocusedText(text: String): Boolean = true
        override fun dumpUiTree(maxNodes: Int): String = ""
        override fun back(): Boolean = true
        override fun home(): Boolean = true
    }
}
