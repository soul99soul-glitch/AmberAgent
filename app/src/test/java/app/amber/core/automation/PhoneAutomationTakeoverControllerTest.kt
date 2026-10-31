package app.amber.core.automation

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
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import app.amber.feature.bubble.AgentBubbleReducer
import app.amber.feature.bubble.AgentBubbleUiState
import app.amber.feature.runtime.SandboxActivityUiState
import app.amber.feature.runtime.ToolActivityStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhoneAutomationTakeoverControllerTest {

    private fun sampleNode(
        ref: String,
        label: String = "",
        className: String = "android.widget.TextView",
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
    ) = ScreenNode(
        ref = ref,
        label = label,
        viewId = "id/$ref",
        className = className,
        left = 100,
        top = 200,
        right = 300,
        bottom = 280,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        enabled = true,
    )

    @Before
    fun setUp() {
        ScreenSnapshotInvalidationRegistry.clearForTest()
    }

    @Test
    fun `state machine transitions CAS versions and persists to file`() {
        val tempDir = Files.createTempDirectory("takeover_test").toFile()
        try {
            val stateFile = File(tempDir, "phone_takeover.properties")
            val persistence = FilePhoneTakeoverPersistence(stateFile)
            val controller = PhoneAutomationTakeoverController(persistence = persistence)

            assertEquals(PhoneTakeoverPhase.IDLE, controller.state.value.phase)

            val started = controller.startSession(
                runId = "run-101",
                packageName = "tv.danmaku.bili",
                goal = "Search Kotlin",
                maxSteps = 6,
            )
            assertEquals(PhoneTakeoverPhase.AUTOMATED, started.phase)
            assertTrue(started.canRequestTakeover)
            assertFalse(started.canRequestResume)

            controller.recordObservedSnapshot(snapshotId = "snap-before", stepIndex = 1)
            assertEquals("snap-before", controller.state.value.lastSnapshotId)

            val tookOver = controller.requestUserTakeover(reason = "user_requested")
            assertTrue(tookOver)
            assertEquals(PhoneTakeoverPhase.PAUSING, controller.state.value.phase)
            assertTrue(ScreenSnapshotInvalidationRegistry.isInvalidated("snap-before"))
            assertEquals(
                "takeover_snapshot_invalidated",
                ScreenSnapshotInvalidationRegistry.reasonFor("snap-before"),
            )

            val paused = controller.confirmPaused(snapshotId = "snap-before")
            assertEquals(PhoneTakeoverPhase.PAUSED, paused.phase)
            assertTrue(paused.canRequestResume)
            assertEquals(1, paused.takeoverCount)

            val persisted = persistence.load()
            assertNotNull(persisted)
            assertEquals(PhoneTakeoverPhase.PAUSED, persisted?.phase)
            assertEquals("run-101", persisted?.runId)
            assertEquals(1, persisted?.takeoverCount)
            assertTrue("snap-before" in persisted?.invalidatedSnapshotIds.orEmpty())

            assertTrue(controller.requestResume())
            assertEquals(PhoneTakeoverPhase.RESUMING, controller.state.value.phase)

            // Snapshot ids are content hashes: resuming an untouched screen re-captures the very
            // id invalidated at takeover, and that fresh capture must not wedge RESUMING.
            val resumedUnchangedSnapshot = ScreenSnapshot(
                id = "snap-before",
                packageName = "tv.danmaku.bili",
                windowId = 1,
                nodes = listOf(sampleNode("n1", label = "旧快照", clickable = true)),
            )
            assertTrue(controller.confirmResumedWithFreshSnapshot(resumedUnchangedSnapshot))
            assertEquals(PhoneTakeoverPhase.AUTOMATED, controller.state.value.phase)
            assertEquals("snap-before", controller.state.value.lastSnapshotId)
            assertFalse("snap-before" in controller.state.value.invalidatedSnapshotIds)
            assertFalse(ScreenSnapshotInvalidationRegistry.isInvalidated("snap-before"))

            controller.finishSession("run-101")
            assertEquals(PhoneTakeoverPhase.IDLE, controller.state.value.phase)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `tryRecordSideEffectDispatch blocks duplicate side effect replay on same snapshot`() {
        val controller = PhoneAutomationTakeoverController()
        controller.startSession(runId = "run-side-effect", packageName = "pkg.app", goal = "Goal", maxSteps = 4)
        val action = ScreenAction(ScreenActionKind.CLICK, "n0_10_10_100_80")

        assertTrue(controller.tryRecordSideEffectDispatch("snap-1", action))
        assertTrue(controller.hasDispatchedSideEffect("snap-1", action))
        assertFalse(controller.tryRecordSideEffectDispatch("snap-1", action))
        // Same action on a newly captured snapshot is permitted
        assertTrue(controller.tryRecordSideEffectDispatch("snap-2", action))
    }

    @Test
    fun `bubble reducer keeps bubble touchable during screen_run_goal and when paused for takeover`() {
        val runningGoalActivity = SandboxActivityUiState(
            toolCallId = "tc-1",
            conversationId = "conv-1",
            toolName = "screen_run_goal",
            title = "执行屏幕目标",
            inputPreview = "tv.danmaku.bili",
            outputTail = "",
            status = ToolActivityStatus.RUNNING,
        )
        val stateDuringGoal = AgentBubbleReducer.reduce(
            previous = AgentBubbleUiState(),
            pickedConversationId = "conv-1",
            activity = runningGoalActivity,
            messages = emptyList(),
            nowMillis = 200L,
            takeoverSession = PhoneAutomationSessionState(
                sessionId = "s1",
                runId = "r1",
                packageName = "tv.danmaku.bili",
                goal = "Search",
                phase = PhoneTakeoverPhase.AUTOMATED,
            ),
        )
        // Must NOT passthrough touch during screen_run_goal so user can expand bubble and tap "我来操作"
        assertFalse(stateDuringGoal.stepRunning)
        assertTrue(stateDuringGoal.canRequestTakeover)
        assertFalse(stateDuringGoal.canRequestResume)

        val runningClickActivity = runningGoalActivity.copy(toolName = "screen_click")
        val stateDuringPausedClick = AgentBubbleReducer.reduce(
            previous = stateDuringGoal,
            pickedConversationId = "conv-1",
            activity = runningClickActivity,
            messages = emptyList(),
            nowMillis = 300L,
            takeoverSession = PhoneAutomationSessionState(
                sessionId = "s1",
                runId = "r1",
                packageName = "tv.danmaku.bili",
                goal = "Search",
                phase = PhoneTakeoverPhase.PAUSED,
                pauseReason = "user_requested",
            ),
        )
        // Must NOT passthrough touch when paused so user can tap "继续自动操作"
        assertFalse(stateDuringPausedClick.stepRunning)
        assertFalse(stateDuringPausedClick.canRequestTakeover)
        assertTrue(stateDuringPausedClick.canRequestResume)
        assertEquals(PhoneTakeoverPhase.PAUSED, stateDuringPausedClick.takeoverPhase)
    }

    @Test
    fun `runner pauses on user takeover invalidates pre-takeover snapshot and resumes with fresh snapshot`() = runBlocking {
        val takeoverController = PhoneAutomationTakeoverController()
        var decideCalls = 0
        val transport = JevTransport { _: JevHttpRequest ->
            decideCalls++
            if (decideCalls == 1) {
                // Simulate user tapping "我来操作" while the first Jev decision is in flight
                takeoverController.requestUserTakeover(reason = "user_requested")
                JevTransportResponse.Http(
                    code = 200,
                    body = """{"model":"jev-test","answers":{
                        "action":{"type":"choice","choice":"a0","confidence":0.90},
                        "done":{"type":"noul","noul":0.10},
                        "safe_a0":{"type":"noul","noul":0.95}
                    }}""".toByteArray(),
                    retryAfterHeader = null,
                )
            } else {
                JevTransportResponse.Http(
                    code = 200,
                    body = """{"model":"jev-test","answers":{
                        "action":{"type":"choice","choice":"done","confidence":0.96},
                        "done":{"type":"noul","noul":0.96},
                        "safe_a0":{"type":"noul","noul":0.95}
                    }}""".toByteArray(),
                    retryAfterHeader = null,
                )
            }
        }
        val coordinator = JevDecisionCoordinator(
            client = JevClient(transport),
            apiKeyProvider = { "jev-key" },
            usageStore = JevUsageStore.IN_MEMORY,
            clock = { 1_000_000L },
        )
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                purposes = mapOf(JevPurpose.SCREEN_AUTOMATION to JevMode.ACTIVE),
                dataScopes = setOf(JevDataScope.SCREEN_CONTENT, JevDataScope.TASK_TEXT),
            ),
            agentRuntime = AgentRuntimeSetting(autoReturnToHostOnScreenGoalComplete = false),
        )
        val runtime = JevRuntime(coordinator = coordinator, settingsProvider = { settings })
        val runner = JevScreenGoalRunner(
            runtime = runtime,
            mainDispatcher = Dispatchers.Unconfined,
            settle = {},
            takeoverController = takeoverController,
        )

        var captures = 0
        val preTakeoverSnap = ScreenSnapshot(
            id = "snap-pre-takeover",
            packageName = "tv.danmaku.bili",
            windowId = 1,
            nodes = listOf(sampleNode("n0_10_10_100_80", label = "搜索", clickable = true)),
        )
        val postTakeoverSnap = ScreenSnapshot(
            id = "snap-post-takeover",
            packageName = "tv.danmaku.bili",
            windowId = 1,
            nodes = listOf(sampleNode("n0_10_10_100_80", label = "搜索结果已就绪", clickable = true)),
        )
        val controller = object : AccessibilityController {
            override fun dumpUiTree(maxNodes: Int): String = ""
            override fun findTextNodes(query: String, maxNodes: Int): List<AccessibilityTextMatch> = emptyList()
            override suspend fun tap(x: Float, y: Float, durationMillis: Long): Boolean = true
            override suspend fun longPress(x: Float, y: Float, durationMillis: Long): Boolean = true
            override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long): Boolean = true
            override fun setFocusedText(text: String): Boolean = true
            override fun back(): Boolean = true
            override fun home(): Boolean = true
            override fun captureScreenSnapshot(): ScreenSnapshot {
                captures++
                return if (captures == 1) preTakeoverSnap else postTakeoverSnap
            }
            override fun performScreenAction(snapshot: ScreenSnapshot, action: ScreenAction): ScreenActionReceipt {
                return ScreenActionReceipt(status = "ok", dispatched = true)
            }
        }

        val resumeJob = async {
            while (takeoverController.state.value.phase != PhoneTakeoverPhase.PAUSED &&
                takeoverController.state.value.phase != PhoneTakeoverPhase.PAUSING
            ) {
                delay(15)
            }
            delay(30)
            takeoverController.requestResume()
        }

        val outcome = runner.run(
            controller = controller,
            packageName = "tv.danmaku.bili",
            goal = "Find search results",
            texts = emptyList(),
            runKey = "run-takeover-resume",
        )
        resumeJob.await()

        assertEquals("completed", outcome["status"]?.jsonPrimitive?.content)
        assertEquals(0, outcome["actions_dispatched"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, outcome["takeover_count"]?.jsonPrimitive?.intOrNull)
        assertEquals("snap-post-takeover", outcome["snapshot_id"]?.jsonPrimitive?.contentOrNull)
        assertTrue(controller.isSnapshotInvalidated("snap-pre-takeover"))
    }

    @Test
    fun `runner holds sensitive final action and records blocked sensitive candidates`() = runBlocking {
        val takeoverController = PhoneAutomationTakeoverController()
        val transport = JevTransport { _: JevHttpRequest ->
            JevTransportResponse.Http(
                code = 200,
                body = """{"model":"jev-test","answers":{
                    "action":{"type":"choice","choice":"a0","confidence":0.90},
                    "done":{"type":"noul","noul":0.10},
                    "safe_a0":{"type":"noul","noul":0.15}
                }}""".toByteArray(),
                retryAfterHeader = null,
            )
        }
        val coordinator = JevDecisionCoordinator(
            client = JevClient(transport),
            apiKeyProvider = { "jev-key" },
            usageStore = JevUsageStore.IN_MEMORY,
            clock = { 1_000_000L },
        )
        val settings = Settings(
            jev = JevSetting(
                enabled = true,
                purposes = mapOf(JevPurpose.SCREEN_AUTOMATION to JevMode.ACTIVE),
                dataScopes = setOf(JevDataScope.SCREEN_CONTENT, JevDataScope.TASK_TEXT),
            ),
        )
        val runtime = JevRuntime(coordinator = coordinator, settingsProvider = { settings })
        val runner = JevScreenGoalRunner(
            runtime = runtime,
            mainDispatcher = Dispatchers.Unconfined,
            settle = {},
            takeoverController = takeoverController,
        )

        val snap = ScreenSnapshot(
            id = "snap-sensitive",
            packageName = "com.tencent.mm",
            windowId = 1,
            nodes = listOf(
                sampleNode("n0_10_10_100_80", label = "完成草稿编辑", clickable = true),
                sampleNode("n1_200_800_300_860", label = "发送", clickable = true),
            ),
        )
        val controller = object : AccessibilityController {
            override fun dumpUiTree(maxNodes: Int): String = ""
            override fun findTextNodes(query: String, maxNodes: Int): List<AccessibilityTextMatch> = emptyList()
            override suspend fun tap(x: Float, y: Float, durationMillis: Long): Boolean = true
            override suspend fun longPress(x: Float, y: Float, durationMillis: Long): Boolean = true
            override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long): Boolean = true
            override fun setFocusedText(text: String): Boolean = true
            override fun back(): Boolean = true
            override fun home(): Boolean = true
            override fun captureScreenSnapshot(): ScreenSnapshot = snap
            override fun performScreenAction(snapshot: ScreenSnapshot, action: ScreenAction): ScreenActionReceipt =
                ScreenActionReceipt(status = "ok", dispatched = true)
        }

        val outcome = runner.run(
            controller = controller,
            packageName = "com.tencent.mm",
            goal = "Fill draft message",
            texts = listOf("Hello"),
            runKey = "run-sensitive-hold",
        )

        assertEquals("needs_user_action", outcome["status"]?.jsonPrimitive?.content)
        assertEquals("action_not_read_only", outcome["reason"]?.jsonPrimitive?.content)
        assertEquals(false, outcome["returned_to_host"]?.jsonPrimitive?.booleanOrNull)
        val held = outcome["held_sensitive_actions"]?.jsonArray
        assertNotNull(held)
        assertEquals(1, held?.size)
        assertEquals("Click 完成草稿编辑", held?.first()?.jsonObject?.get("label")?.jsonPrimitive?.content)
        val blocked = outcome["blocked_sensitive_candidates"]?.jsonArray
        assertNotNull(blocked)
        assertEquals(1, blocked?.size)
        assertEquals("发送", blocked?.first()?.jsonObject?.get("label")?.jsonPrimitive?.content)
        // Session stays paused with interceptedSensitiveAction visible until turn ends or user resumes
        assertEquals(PhoneTakeoverPhase.PAUSED, takeoverController.state.value.phase)
        assertEquals("Click 完成草稿编辑", takeoverController.state.value.interceptedSensitiveAction)
    }

    // ---------------------------------------------------------------------------------------
    // P4 (A4.x): FilePhoneTakeoverPersistence was written on every transition but `load()` had no
    // production caller, so a process kill during a takeover restored nothing.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `paused session across process restart invalidates snapshots and clears dead run unless run is alive`() {
        val dir = Files.createTempDirectory("takeover_restore").toFile()
        try {
            val file = File(dir, "phone_takeover_state.properties")
            val before = PhoneAutomationTakeoverController(
                persistence = FilePhoneTakeoverPersistence(file),
            )
            before.startSession(runId = "run-restart", packageName = "com.example", goal = "填表", maxSteps = 8)
            before.recordObservedSnapshot(snapshotId = "snap-live", stepIndex = 3)
            before.confirmPaused(snapshotId = "snap-live", reason = "user_requested")
            ScreenSnapshotInvalidationRegistry.clearForTest()

            // Default restart (dead run): resets to IDLE so it never blocks a new run,
            // while still re-registering invalidated snapshots into ScreenSnapshotInvalidationRegistry.
            val deadRunAfter = PhoneAutomationTakeoverController(
                persistence = FilePhoneTakeoverPersistence(file),
                restoreOnCreate = true,
            )
            assertEquals(PhoneTakeoverPhase.IDLE, deadRunAfter.state.value.phase)
            assertTrue(ScreenSnapshotInvalidationRegistry.isInvalidated("snap-live"))

            // Rewrite paused state to test live-run recovery when isRunAlive returns true:
            before.startSession(runId = "run-restart", packageName = "com.example", goal = "填表", maxSteps = 8)
            before.recordObservedSnapshot(snapshotId = "snap-live-2", stepIndex = 3)
            before.confirmPaused(snapshotId = "snap-live-2", reason = "user_requested")
            ScreenSnapshotInvalidationRegistry.clearForTest()

            val after = PhoneAutomationTakeoverController(
                persistence = FilePhoneTakeoverPersistence(file),
                restoreOnCreate = true,
                isRunAlive = { it == "run-restart" },
            )
            val restored = after.state.value
            assertEquals(PhoneTakeoverPhase.PAUSED, restored.phase)
            assertEquals("run-restart", restored.runId)
            assertEquals("com.example", restored.packageName)
            assertEquals("填表", restored.goal)
            assertEquals(3, restored.currentStep)
            assertEquals(8, restored.maxSteps)
            assertEquals("process_restart", restored.pauseReason)
            assertTrue(after.state.value.canRequestResume)
            assertTrue(ScreenSnapshotInvalidationRegistry.isInvalidated("snap-live-2"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `markFreshCapture does not clear invalidated snapshot while takeover pause is active`() {
        val controller = PhoneAutomationTakeoverController()
        controller.startSession(runId = "run-pause", packageName = "com.example", goal = "goal", maxSteps = 4)
        controller.recordObservedSnapshot("snap-paused", 1)
        controller.confirmPaused(snapshotId = "snap-paused", reason = "user_requested")
        assertTrue(ScreenSnapshotInvalidationRegistry.isTakeoverPauseActive())
        assertTrue(ScreenSnapshotInvalidationRegistry.isInvalidated("snap-paused"))

        // Attempting to mark fresh capture during pause must not resurrect the invalidated snapshot.
        ScreenSnapshotInvalidationRegistry.markFreshCapture("snap-paused")
        assertTrue(ScreenSnapshotInvalidationRegistry.isInvalidated("snap-paused"))
    }

    @Test
    fun `an automated session is not resurrected after a restart`() {
        val dir = Files.createTempDirectory("takeover_restore").toFile()
        try {
            val file = File(dir, "phone_takeover_state.properties")
            val before = PhoneAutomationTakeoverController(
                persistence = FilePhoneTakeoverPersistence(file),
            )
            before.startSession(runId = "run-live", packageName = "com.example", goal = "跑完", maxSteps = 4)
            before.recordObservedSnapshot(snapshotId = "snap-live", stepIndex = 1)
            assertEquals(PhoneTakeoverPhase.AUTOMATED, before.state.value.phase)

            // The executor and its coroutines died with the process; claiming AUTOMATED would show
            // a live run that no longer exists and let the user believe taps will be dispatched.
            val after = PhoneAutomationTakeoverController(
                persistence = FilePhoneTakeoverPersistence(file),
                restoreOnCreate = true,
            )
            assertEquals(PhoneTakeoverPhase.IDLE, after.state.value.phase)
            assertFalse(after.state.value.isActive)
            assertFalse(after.state.value.canRequestResume)
            assertFalse(after.state.value.canRequestTakeover)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `restore is off by default and a missing file restores nothing`() {
        val dir = Files.createTempDirectory("takeover_restore").toFile()
        try {
            val file = File(dir, "phone_takeover_state.properties")
            val writer = PhoneAutomationTakeoverController(persistence = FilePhoneTakeoverPersistence(file))
            writer.startSession(runId = "run-x", packageName = "pkg", goal = "g", maxSteps = 2)
            writer.confirmPaused(reason = "user_requested")

            val inMemoryDefault = PhoneAutomationTakeoverController(
                persistence = FilePhoneTakeoverPersistence(file),
            )
            assertEquals(
                "a controller that does not opt in must start IDLE",
                PhoneTakeoverPhase.IDLE,
                inMemoryDefault.state.value.phase,
            )

            val missing = PhoneAutomationTakeoverController(
                persistence = FilePhoneTakeoverPersistence(File(dir, "absent.properties")),
                restoreOnCreate = true,
            )
            assertEquals(PhoneTakeoverPhase.IDLE, missing.state.value.phase)
        } finally {
            dir.deleteRecursively()
        }
    }
}
