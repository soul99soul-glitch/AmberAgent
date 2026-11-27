package app.amber.feature.tools

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.os.Handler
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import app.amber.agent.PHONE_USE_RUN_CHANNEL_ID
import app.amber.agent.RouteActivity
import app.amber.ai.ui.UIMessagePart
import app.amber.core.automation.AccessibilityActive
import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.AccessibilityTextMatch
import app.amber.core.automation.ScreenAction
import app.amber.core.automation.ScreenActionReceipt
import app.amber.core.automation.ScreenNode
import app.amber.core.automation.ScreenSnapshot
import app.amber.core.automation.display.ConnectedDisplayBridge
import app.amber.core.automation.display.DisplayCapabilities
import app.amber.core.automation.display.DisplaySessionOutcome
import app.amber.core.automation.display.FrameReaderPort
import app.amber.core.automation.display.PhoneDisplayMode
import app.amber.core.automation.display.PhoneDisplaySession
import app.amber.core.automation.display.RawRgbaPlane
import app.amber.core.automation.display.VirtualDisplayCompatibility
import app.amber.core.automation.display.VirtualDisplayFrameSource
import app.amber.core.automation.display.VirtualDisplayRunControlReceiver
import app.amber.core.automation.display.ActiveVirtualDisplayRun
import app.amber.core.automation.display.VirtualDisplayRunControlState
import app.amber.core.automation.display.VirtualDisplayRunNotifier
import app.amber.core.automation.display.VirtualDisplayRunRegistry
import app.amber.core.automation.display.VirtualDisplaySession
import app.amber.core.automation.display.VirtualDisplaySessionState
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
import app.amber.core.jev.ScreenshotAttempt
import app.amber.core.settings.AgentRuntimeSetting
import app.amber.core.settings.Settings
import app.amber.feature.runtime.AgentToolActivityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScreenRunGoalDisplayModeTest {
    private val baseContext get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        VirtualDisplayRunRegistry.clearForTest()
        AccessibilityActive.activeController = null
    }

    @After
    fun tearDown() {
        VirtualDisplayRunRegistry.clearForTest()
        AccessibilityActive.activeController = null
        Dispatchers.resetMain()
    }

    @Test
    fun statusReportsEachVirtualGateAndOnlyMarksFullyConfiguredModeReady() = runTest {
        suspend fun checkStatus(
            accessibility: Boolean = true,
            allowed: Boolean = true,
            enabled: Boolean = true,
            compatibility: VirtualDisplayCompatibility = VirtualDisplayCompatibility.VERIFIED,
            hasOpener: Boolean = true,
            ready: Boolean = false,
        ) {
            AccessibilityActive.activeController = if (accessibility) {
                FakeAccessibilityController(targetSnapshot("com.example.target", "n1"))
            } else null
            val tools = ScreenAppScopeTools(
                context = baseContext,
                settingsStore = null,
                jevScreenGoalRunner = null,
                operations = ScreenOperationOwner(AgentToolActivityStore()),
                virtualDisplayAllowedProvider = { allowed },
                virtualDisplayEnabledProvider = { enabled },
                probeCompatibilityProvider = { compatibility },
                sessionOpener = if (hasOpener) { _, _, _ -> error("screen_status opened a session") } else null,
            )
            val parts = tools.screenStatusTool("run-status").execute(JsonObject(emptyMap()))
            val result = Json.parseToJsonElement(parts.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
            assertEquals(allowed, result.getValue("virtual_display_allowed").jsonPrimitive.boolean)
            assertEquals(enabled, result.getValue("virtual_display_enabled").jsonPrimitive.boolean)
            assertEquals(compatibility.name.lowercase(), result.getValue("virtual_display_compatibility").jsonPrimitive.content)
            assertEquals(ready, result.getValue("virtual_display_ready").jsonPrimitive.boolean)
        }

        checkStatus(ready = true)
        checkStatus(accessibility = false)
        checkStatus(allowed = false)
        checkStatus(enabled = false)
        checkStatus(hasOpener = false)
        checkStatus(compatibility = VirtualDisplayCompatibility.UNVERIFIED)
        checkStatus(compatibility = VirtualDisplayCompatibility.FAILED)
        checkStatus(compatibility = VirtualDisplayCompatibility.UNSUPPORTED)
    }

    @Test
    fun defaultVirtualFrameAttachesProportionallyScaledJpeg() = runTest {
        val width = 720
        val height = 1600
        val source = VirtualDisplayFrameSource(
            width, height,
            SinglePlaneFrameReaderPort(width, height, 0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
            ownWorkerThread = false,
        )
        val session = VirtualDisplaySession(
            runId = "run-vd-screenshot",
            packageName = "com.example.target",
            displayId = 7,
            generation = 1L,
            width = width,
            height = height,
            dpi = 320,
            bridge = FakeConnectedBridge(),
            frameSource = source,
            windowsProvider = { emptyList() },
        )
        try {
            source.onImageAvailable()
            val tools = ScreenAppScopeTools(baseContext, null, null, ScreenOperationOwner(AgentToolActivityStore()))
            val attempt = tools.buildVirtualScreenshotProvider(session)("<<IMAGE:1>>")
            assertTrue("Expected screenshot attachment, got ${attempt::class.simpleName}", attempt is ScreenshotAttempt.Attached)
            val asset = (attempt as ScreenshotAttempt.Attached).asset
            assertEquals(706, asset.widthPx)
            assertEquals(1568, asset.heightPx)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(asset.data, 0, asset.data.size, bounds)
            assertEquals(asset.widthPx, bounds.outWidth)
            assertEquals(asset.heightPx, bounds.outHeight)
        } finally {
            session.close("test_complete")
        }
    }

    @Test
    fun autoModeWithSwitchOffOrUnverifiedUsesPhysicalPathUnchanged() = runTest {
        val targetPkg = "com.example.target"
        val recordingContext = RecordingLaunchContext(baseContext, targetPkg)
        val physicalController = FakeAccessibilityController(
            initial = targetSnapshot(targetPkg, id = "phys-1"),
        )
        AccessibilityActive.activeController = physicalController

        var sessionOpenerCalls = 0
        val runner = createRunner(
            responses = mutableListOf(verifiedDoneResponse()),
        )

        // Case 1: switch OFF, even if VERIFIED -> PHYSICAL
        val toolsSwitchOff = ScreenAppScopeTools(
            context = recordingContext,
            settingsStore = null,
            jevScreenGoalRunner = runner,
            operations = ScreenOperationOwner(AgentToolActivityStore()),
            virtualDisplayAllowedProvider = { true },
            virtualDisplayEnabledProvider = { false },
            probeCompatibilityProvider = { VirtualDisplayCompatibility.VERIFIED },
            sessionOpener = { _, _, _ ->
                sessionOpenerCalls++
                error("should not open virtual session")
            },
        )
        val resultOff = executeGoal(
            tools = toolsSwitchOff,
            json = """{"package_name":"$targetPkg","goal":"Verify home","display_mode":"auto"}""",
            runId = "run-auto-off",
        )
        assertEquals("completed", resultOff["status"]?.jsonPrimitive?.content)
        assertNull(resultOff["display_mode"])
        assertEquals(0, sessionOpenerCalls)
        assertTrue(physicalController.captureCalls > 0)

        // Case 2: switch ON, but UNVERIFIED -> PHYSICAL (also launches on Display 0 when not already open)
        val otherForegroundController = FakeAccessibilityController(
            initial = targetSnapshot("com.other.app", id = "phys-other"),
            captureOverride = { callIndex ->
                // First call checks alreadyOpen -> different app; subsequent calls observe targetPkg
                if (callIndex == 1) targetSnapshot("com.other.app", id = "phys-other")
                else targetSnapshot(targetPkg, id = "phys-target")
            },
        )
        AccessibilityActive.activeController = otherForegroundController
        val runner2 = createRunner(responses = mutableListOf(verifiedDoneResponse()))
        val toolsUnverified = ScreenAppScopeTools(
            context = recordingContext,
            settingsStore = null,
            jevScreenGoalRunner = runner2,
            operations = ScreenOperationOwner(AgentToolActivityStore()),
            virtualDisplayAllowedProvider = { true },
            virtualDisplayEnabledProvider = { true },
            probeCompatibilityProvider = { VirtualDisplayCompatibility.UNVERIFIED },
            sessionOpener = { _, _, _ ->
                sessionOpenerCalls++
                error("should not open virtual session")
            },
        )
        val resultUnverified = executeGoal(
            tools = toolsUnverified,
            json = """{"package_name":"$targetPkg","goal":"Verify home","display_mode":"auto"}""",
            runId = "run-auto-unverified",
        )
        assertEquals("completed", resultUnverified["status"]?.jsonPrimitive?.content)
        assertEquals(0, sessionOpenerCalls)
        assertEquals(1, recordingContext.startActivityCount.get())
    }

    @Test
    fun virtualModeWhenUnavailableReturnsStructuredErrorWithoutFallbackOrStartActivity() = runTest {
        val targetPkg = "com.example.target"
        val recordingContext = RecordingLaunchContext(baseContext, targetPkg)
        val physicalController = FakeAccessibilityController(
            initial = targetSnapshot("com.other.app", id = "phys-1"),
        )
        AccessibilityActive.activeController = physicalController

        // Case 1: factory returns Unavailable(reason)
        val runner = createRunner(responses = mutableListOf(verifiedDoneResponse()))
        val tools = ScreenAppScopeTools(
            context = recordingContext,
            settingsStore = null,
            jevScreenGoalRunner = runner,
            operations = ScreenOperationOwner(AgentToolActivityStore()),
            virtualDisplayAllowedProvider = { true },
            virtualDisplayEnabledProvider = { true },
            probeCompatibilityProvider = { VirtualDisplayCompatibility.UNVERIFIED },
            sessionOpener = { _, _, _ -> DisplaySessionOutcome.Unavailable("isolation_probe_unverified") },
        )

        val result = executeGoal(
            tools = tools,
            json = """{"package_name":"$targetPkg","goal":"Open settings","display_mode":"virtual"}""",
            runId = "run-vd-unavailable",
        )
        assertEquals("display_unavailable", result["status"]?.jsonPrimitive?.content)
        assertEquals("isolation_probe_unverified", result["reason"]?.jsonPrimitive?.content)
        assertEquals(false, result["fallback_attempted"]?.jsonPrimitive?.boolean)
        assertEquals(0, recordingContext.startActivityCount.get())
        assertEquals(0, physicalController.captureCalls)

        // Case 2: experimental setting disabled
        val toolsDisabled = ScreenAppScopeTools(
            context = recordingContext,
            settingsStore = null,
            jevScreenGoalRunner = runner,
            operations = ScreenOperationOwner(AgentToolActivityStore()),
            virtualDisplayAllowedProvider = { true },
            virtualDisplayEnabledProvider = { false },
            probeCompatibilityProvider = { VirtualDisplayCompatibility.VERIFIED },
            sessionOpener = { _, _, _ -> error("must not be called when setting disabled") },
        )
        val disabledResult = executeGoal(
            tools = toolsDisabled,
            json = """{"package_name":"$targetPkg","goal":"Open settings","display_mode":"virtual"}""",
            runId = "run-vd-disabled",
        )
        assertEquals("display_unavailable", disabledResult["status"]?.jsonPrimitive?.content)
        assertEquals("virtual_display_setting_disabled", disabledResult["reason"]?.jsonPrimitive?.content)
        assertEquals(0, recordingContext.startActivityCount.get())
        assertEquals(0, physicalController.captureCalls)
    }

    @Test
    fun virtualModeWhenAvailableLaunchesTargetWithZeroDisplay0StartActivityAndNoAutoReturn() = runTest {
        val targetPkg = "com.example.target"
        val recordingContext = RecordingLaunchContext(baseContext, targetPkg)
        // Display 0 currently shows Amber itself (not targetPkg)
        val physicalController = FakeAccessibilityController(
            initial = targetSnapshot(baseContext.packageName, id = "phys-host"),
        )
        AccessibilityActive.activeController = physicalController

        val virtualController = FakeAccessibilityController(
            initial = targetSnapshot(targetPkg, id = "vd:7:1:1"),
        )
        val fakeSession = FakeVirtualPhoneDisplaySession(
            displayId = 7,
            generation = 1L,
            controller = virtualController,
        )
        val runner = createRunner(
            responses = mutableListOf(verifiedDoneResponse()),
            autoReturnSetting = true,
        )
        val tools = ScreenAppScopeTools(
            context = recordingContext,
            settingsStore = null,
            jevScreenGoalRunner = runner,
            operations = ScreenOperationOwner(AgentToolActivityStore()),
            virtualDisplayRunNotifier = VirtualDisplayRunNotifier(baseContext),
            virtualDisplayAllowedProvider = { true },
            virtualDisplayEnabledProvider = { true },
            probeCompatibilityProvider = { VirtualDisplayCompatibility.VERIFIED },
            sessionOpener = { _, _, mode ->
                assertEquals(PhoneDisplayMode.VIRTUAL, mode)
                DisplaySessionOutcome.Ready(fakeSession)
            },
            virtualLaunchObserveRetries = 2,
            virtualLaunchObserveDelayMs = 0L,
        )

        val result = executeGoal(
            tools = tools,
            // Even when caller asks for return_to_host=true, virtual mode must force autoReturnToHost=false.
            json = """{"package_name":"$targetPkg","goal":"Check feed","display_mode":"virtual","return_to_host":true}""",
            runId = "run-vd-ok",
        )

        assertEquals("completed", result["status"]?.jsonPrimitive?.content)
        assertEquals("virtual", result["display_mode"]?.jsonPrimitive?.content)
        assertEquals(false, result["auto_return_to_host"]?.jsonPrimitive?.boolean)
        assertEquals(false, result["returned_to_host"]?.jsonPrimitive?.boolean)
        assertEquals(1, fakeSession.launchTargetCalls)
        assertEquals(1, fakeSession.closeCalls)
        assertEquals(0, recordingContext.startActivityCount.get())
        assertEquals(0, virtualController.hostReturnCalls)
        assertNull(VirtualDisplayRunRegistry.get("run-vd-ok"))
    }

    @Test
    fun virtualSessionDisconnectDuringRunReturnsSafePausedOrOutcomeUnknownWithoutReplay() = runTest {
        val targetPkg = "com.example.target"
        val recordingContext = RecordingLaunchContext(baseContext, targetPkg)

        // 1. Mid-dispatch disconnect during launchTarget -> OUTCOME_UNKNOWN, never replayed, 0 Display 0 startActivity
        lateinit var vdSessionRef: VirtualDisplaySession
        val disconnectBridge = FakeConnectedBridge(
            onLaunchSideEffect = {
                vdSessionRef.invalidate("shizuku_binder_dead")
            },
        )
        val frameSource = VirtualDisplayFrameSource(
            width = 16,
            height = 16,
            readerPort = StubFrameReaderPort(),
            ownWorkerThread = false,
        )
        vdSessionRef = VirtualDisplaySession(
            runId = "run-vd-disconnect-mid",
            packageName = targetPkg,
            displayId = 9,
            generation = 2L,
            width = 16,
            height = 16,
            dpi = 320,
            bridge = disconnectBridge,
            frameSource = frameSource,
            windowsProvider = { id -> listOf(app.amber.core.automation.display.appWindow(id, baseContext.packageName)) },
        )

        val runner = createRunner(responses = mutableListOf(verifiedDoneResponse()))
        val tools = ScreenAppScopeTools(
            context = recordingContext,
            settingsStore = null,
            jevScreenGoalRunner = runner,
            operations = ScreenOperationOwner(AgentToolActivityStore()),
            virtualDisplayAllowedProvider = { true },
            virtualDisplayEnabledProvider = { true },
            probeCompatibilityProvider = { VirtualDisplayCompatibility.VERIFIED },
            sessionOpener = { _, _, _ -> DisplaySessionOutcome.Ready(vdSessionRef) },
            virtualLaunchObserveRetries = 1,
            virtualLaunchObserveDelayMs = 0L,
        )

        val midResult = executeGoal(
            tools = tools,
            json = """{"package_name":"$targetPkg","goal":"Open target","display_mode":"virtual"}""",
            runId = "run-vd-disconnect-mid",
        )
        assertEquals("outcome_unknown", midResult["status"]?.jsonPrimitive?.content)
        assertEquals("shizuku_binder_dead", midResult["reason"]?.jsonPrimitive?.content)
        assertEquals(1, disconnectBridge.launchCount) // Never replayed
        assertEquals(0, recordingContext.startActivityCount.get())
        assertTrue(frameSource.isClosed)
        assertTrue(disconnectBridge.disconnected)

        // 2. Idle pre-dispatch disconnect before launch -> SAFE_PAUSED, never launches or replays
        val idleBridge = FakeConnectedBridge()
        val idleFrameSource = VirtualDisplayFrameSource(
            width = 16,
            height = 16,
            readerPort = StubFrameReaderPort(),
            ownWorkerThread = false,
        )
        val idleSession = VirtualDisplaySession(
            runId = "run-vd-disconnect-idle",
            packageName = targetPkg,
            displayId = 10,
            generation = 3L,
            width = 16,
            height = 16,
            dpi = 320,
            bridge = idleBridge,
            frameSource = idleFrameSource,
            windowsProvider = { emptyList() },
        )
        idleSession.invalidate("virtual_display_removed")

        val idleTools = ScreenAppScopeTools(
            context = recordingContext,
            settingsStore = null,
            jevScreenGoalRunner = runner,
            operations = ScreenOperationOwner(AgentToolActivityStore()),
            virtualDisplayAllowedProvider = { true },
            virtualDisplayEnabledProvider = { true },
            probeCompatibilityProvider = { VirtualDisplayCompatibility.VERIFIED },
            sessionOpener = { _, _, _ -> DisplaySessionOutcome.Ready(idleSession) },
            virtualLaunchObserveRetries = 1,
            virtualLaunchObserveDelayMs = 0L,
        )

        val idleResult = executeGoal(
            tools = idleTools,
            json = """{"package_name":"$targetPkg","goal":"Open target","display_mode":"virtual"}""",
            runId = "run-vd-disconnect-idle",
        )
        assertEquals("SAFE_PAUSED", idleResult["status"]?.jsonPrimitive?.content)
        assertEquals("virtual_display_removed", idleResult["reason"]?.jsonPrimitive?.content)
        assertEquals(0, idleBridge.launchCount)
        assertEquals(0, recordingContext.startActivityCount.get())
        assertTrue(idleFrameSource.isClosed)
    }

    @Test
    fun virtualSessionUnobservableFrameHaltsAndReturnsUnobservable() = runTest {
        val targetPkg = "com.example.target"
        val recordingContext = RecordingLaunchContext(baseContext, targetPkg)
        val blackPlanePort = SinglePlaneFrameReaderPort(
            width = 16,
            height = 16,
            argbPixel = 0xFF000000.toInt(),
        )
        val frameSource = VirtualDisplayFrameSource(
            width = 16,
            height = 16,
            readerPort = blackPlanePort,
            ownWorkerThread = false,
        )
        // Produce an all-black frame so latestStats.unobservable == true.
        frameSource.onImageAvailable()
        assertTrue(frameSource.latestStats.value?.unobservable == true)

        val bridge = FakeConnectedBridge()
        val session = VirtualDisplaySession(
            runId = "run-vd-unobservable",
            packageName = targetPkg,
            displayId = 11,
            generation = 4L,
            width = 16,
            height = 16,
            dpi = 320,
            bridge = bridge,
            frameSource = frameSource,
            windowsProvider = { emptyList() },
        )
        val runner = createRunner(responses = mutableListOf(verifiedDoneResponse()))
        val tools = ScreenAppScopeTools(
            context = recordingContext,
            settingsStore = null,
            jevScreenGoalRunner = runner,
            operations = ScreenOperationOwner(AgentToolActivityStore()),
            virtualDisplayAllowedProvider = { true },
            virtualDisplayEnabledProvider = { true },
            probeCompatibilityProvider = { VirtualDisplayCompatibility.VERIFIED },
            sessionOpener = { _, _, _ -> DisplaySessionOutcome.Ready(session) },
            virtualLaunchObserveRetries = 1,
            virtualLaunchObserveDelayMs = 0L,
        )

        val result = executeGoal(
            tools = tools,
            json = """{"package_name":"$targetPkg","goal":"Inspect screen","display_mode":"virtual","dry_run":true}""",
            runId = "run-vd-unobservable",
        )
        assertEquals("UNOBSERVABLE", result["status"]?.jsonPrimitive?.content)
        assertEquals(0, recordingContext.startActivityCount.get())
        assertTrue(frameSource.isClosed)
    }

    @Test
    fun notifierNotificationActionsContainOnlyViewPauseEmergencyStopAndNeverApprove() {
        val notifier = VirtualDisplayRunNotifier(baseContext)
        val runId = "run-notif-check"
        val pkg = "com.example.target"
        val session = FakeVirtualPhoneDisplaySession(8, 1L, FakeAccessibilityController(targetSnapshot(pkg, "n1")))
        val run = ActiveVirtualDisplayRun(runId, pkg, "Search item", session)
        VirtualDisplayRunRegistry.register(run)
        val specs = notifier.buildActionSpecs(run)
        assertEquals(
            listOf(
                VirtualDisplayRunNotifier.ActionKind.VIEW,
                VirtualDisplayRunNotifier.ActionKind.PAUSE,
                VirtualDisplayRunNotifier.ActionKind.EMERGENCY_STOP,
            ),
            specs.map { it.kind },
        )

        val notification = notifier.buildNotification(run)
        assertEquals(PHONE_USE_RUN_CHANNEL_ID, notification.channelId)
        val actions = notification.actions
        assertNotNull(actions)
        assertEquals(3, actions.size)

        val forbiddenTokens = listOf("approve", "allow", "同意", "批准", "允许")
        actions.forEach { action ->
            val title = action.title?.toString().orEmpty().lowercase()
            forbiddenTokens.forEach { token ->
                assertFalse("Notification action must never include approval token '$token': $title", title.contains(token))
            }
            val shadowPi = Shadows.shadowOf(action.actionIntent)
            assertTrue(
                "PendingIntent must include FLAG_IMMUTABLE",
                (shadowPi.flags and PendingIntent.FLAG_IMMUTABLE) != 0,
            )
            val savedAction = shadowPi.savedIntent.action.orEmpty().lowercase()
            forbiddenTokens.forEach { token ->
                assertFalse("Intent action must never include approval token '$token': $savedAction", savedAction.contains(token))
            }
        }

        // Verify explicit components: View -> RouteActivity; Pause & Emergency Stop -> VirtualDisplayRunControlReceiver
        val viewShadow = Shadows.shadowOf(actions[0].actionIntent)
        assertEquals(RouteActivity::class.java.name, viewShadow.savedIntent.component?.className)
        assertEquals(VirtualDisplayRunNotifier.ACTION_VIEW_VIRTUAL_RUN, viewShadow.savedIntent.action)
        assertEquals(runId, viewShadow.savedIntent.getStringExtra(VirtualDisplayRunNotifier.EXTRA_RUN_ID))

        val pauseShadow = Shadows.shadowOf(actions[1].actionIntent)
        assertEquals(VirtualDisplayRunControlReceiver::class.java.name, pauseShadow.savedIntent.component?.className)
        assertEquals(VirtualDisplayRunNotifier.ACTION_PAUSE_VIRTUAL_RUN, pauseShadow.savedIntent.action)
        assertEquals(runId, pauseShadow.savedIntent.getStringExtra(VirtualDisplayRunNotifier.EXTRA_RUN_ID))

        val stopShadow = Shadows.shadowOf(actions[2].actionIntent)
        assertEquals(VirtualDisplayRunControlReceiver::class.java.name, stopShadow.savedIntent.component?.className)
        assertEquals(VirtualDisplayRunNotifier.ACTION_EMERGENCY_STOP_VIRTUAL_RUN, stopShadow.savedIntent.action)
        assertEquals(runId, stopShadow.savedIntent.getStringExtra(VirtualDisplayRunNotifier.EXTRA_RUN_ID))

        val stalePause = pauseShadow.savedIntent
        assertEquals(run, VirtualDisplayRunRegistry.forIntent(stalePause))
        val replacement = ActiveVirtualDisplayRun(runId, pkg, "Next step", session)
        VirtualDisplayRunRegistry.register(replacement)
        val nextPause = notifier.buildActionSpecs(replacement).single { it.kind == VirtualDisplayRunNotifier.ActionKind.PAUSE }
        assertFalse(nextPause.pendingIntent == actions[1].actionIntent)
        VirtualDisplayRunControlReceiver().onReceive(baseContext, stalePause)
        assertEquals(VirtualDisplayRunControlState.RUNNING, replacement.controlState.value)
        assertEquals(replacement, VirtualDisplayRunRegistry.forIntent(Shadows.shadowOf(nextPause.pendingIntent).savedIntent))
    }

    private suspend fun executeGoal(
        tools: ScreenAppScopeTools,
        json: String,
        runId: String,
    ): JsonObject {
        val tool = tools.screenGoalTool(runId)
        val parts = tool.execute(Json.parseToJsonElement(json))
        val text = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun createRunner(
        responses: MutableList<String>,
        autoReturnSetting: Boolean = true,
    ): JevScreenGoalRunner {
        val transport = object : JevTransport {
            override suspend fun execute(request: JevHttpRequest): JevTransportResponse =
                JevTransportResponse.Http(
                    code = 200,
                    body = (responses.removeFirstOrNull() ?: verifiedDoneResponse()).toByteArray(),
                    retryAfterHeader = null,
                )
        }
        val runtime = JevRuntime(
            coordinator = JevDecisionCoordinator(
                client = JevClient(transport),
                apiKeyProvider = { "test-key" },
                usageStore = JevUsageStore.IN_MEMORY,
                clock = { 1_000_000L },
            ),
            settingsProvider = {
                Settings(
                    agentRuntime = AgentRuntimeSetting(
                        autoReturnToHostOnScreenGoalComplete = autoReturnSetting,
                    ),
                    jev = JevSetting(
                        enabled = true,
                        purposes = mapOf(
                            JevPurpose.PHONE_VERIFICATION to JevMode.ACTIVE,
                            JevPurpose.PHONE_ACTIONS to JevMode.ACTIVE,
                        ),
                        dataScopes = setOf(
                            JevDataScope.SCREEN_CONTENT,
                            JevDataScope.TASK_TEXT,
                        ),
                    ),
                )
            },
        )
        return JevScreenGoalRunner(
            runtime = runtime,
            mainDispatcher = Dispatchers.Main.immediate,
            settle = {},
        )
    }

    private fun verifiedDoneResponse(): String =
        """{"model":"jev-test","answers":{"done":{"type":"noul","noul":0.95}}}"""

    private fun targetSnapshot(packageName: String, id: String): ScreenSnapshot =
        ScreenSnapshot(
            id = id,
            packageName = packageName,
            windowId = 1,
            nodes = listOf(
                ScreenNode(
                    ref = "0",
                    label = "Home feed ready",
                    className = "android.widget.TextView",
                    viewId = "",
                    clickable = false,
                    editable = false,
                    scrollable = false,
                    enabled = true,
                    left = 0,
                    top = 0,
                    right = 100,
                    bottom = 40,
                ),
            ),
        )

    private class RecordingLaunchContext(
        base: Context,
        launchablePackage: String,
    ) : ContextWrapper(base) {
        val startActivityCount = AtomicInteger(0)

        init {
            val pmShadow = Shadows.shadowOf(base.packageManager)
            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(launchablePackage)
            }
            val resolveInfo = ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = launchablePackage
                    name = "$launchablePackage.MainActivity"
                }
            }
            pmShadow.addResolveInfoForIntent(launchIntent, resolveInfo)
        }

        override fun startActivity(intent: Intent?) {
            startActivityCount.incrementAndGet()
        }
    }

    private class FakeAccessibilityController(
        private val initial: ScreenSnapshot,
        private val captureOverride: ((Int) -> ScreenSnapshot?)? = null,
    ) : AccessibilityController {
        var captureCalls = 0
            private set
        var hostReturnCalls = 0
            private set

        override fun captureScreenSnapshot(): ScreenSnapshot? {
            captureCalls++
            return captureOverride?.invoke(captureCalls) ?: initial
        }

        override fun performScreenAction(snapshot: ScreenSnapshot, action: ScreenAction): ScreenActionReceipt =
            ScreenActionReceipt("ok", dispatched = true)

        override fun returnToHostApp(hostPackageName: String): Boolean {
            hostReturnCalls++
            return true
        }

        override suspend fun tap(x: Float, y: Float, durationMillis: Long): Boolean = true
        override suspend fun longPress(x: Float, y: Float, durationMillis: Long): Boolean = true
        override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long): Boolean = true
        override fun setFocusedText(text: String): Boolean = true
        override fun dumpUiTree(maxNodes: Int): String = ""
        override fun findTextNodes(query: String, maxNodes: Int): List<AccessibilityTextMatch> = emptyList()
        override fun back(): Boolean = false
        override fun home(): Boolean = false
    }

    private class FakeVirtualPhoneDisplaySession(
        override val displayId: Int,
        override val generation: Long,
        override val controller: AccessibilityController,
    ) : PhoneDisplaySession {
        override val mode: PhoneDisplayMode = PhoneDisplayMode.VIRTUAL
        var launchTargetCalls = 0
            private set
        var closeCalls = 0
            private set
        var lastCloseReason: String? = null
            private set

        override suspend fun launchTarget(): Boolean {
            launchTargetCalls++
            return true
        }

        override suspend fun close(reason: String) {
            closeCalls++
            lastCloseReason = reason
        }
    }

    private class FakeConnectedBridge(
        private val onLaunchSideEffect: (() -> Unit)? = null,
    ) : ConnectedDisplayBridge {
        var launchCount = 0
            private set
        var releaseCount = 0
            private set
        var disconnected = false
            private set

        override fun uid(): Int = 2000

        override fun capabilities(): DisplayCapabilities = DisplayCapabilities(
            apiLevel = 34,
            uid = 2000,
            ownFocus = true,
            destroyContentOnRemoval = true,
            trusted = true,
            secondaryActivities = true,
        )

        override fun createDisplay(
            runId: String,
            authorizedPackageName: String,
            generation: Long,
            width: Int,
            height: Int,
            dpi: Int,
            surface: Surface,
        ): Int = 9

        override fun launchOnDisplay(generation: Long, displayId: Int, packageName: String): Int {
            launchCount++
            onLaunchSideEffect?.invoke()
            return 0
        }

        override fun injectMotion(generation: Long, displayId: Int, observedPackageName: String, event: MotionEvent): Boolean = true
        override fun injectKey(generation: Long, displayId: Int, observedPackageName: String, event: KeyEvent): Boolean = true

        override fun release(generation: Long, reason: String) {
            releaseCount++
        }

        override fun disconnect() {
            disconnected = true
        }
    }

    private class StubFrameReaderPort : FrameReaderPort {
        override val maxImages: Int = VirtualDisplayFrameSource.MAX_IMAGES
        private val texture = SurfaceTexture(0)
        override val surface: Surface = Surface(texture)
        override fun setListener(handler: Handler?, listener: (() -> Unit)?) = Unit
        override fun acquireLatestPlane(block: (RawRgbaPlane) -> Unit): Boolean = false
        override fun dropLatestFrame() = Unit
        override fun close() {
            runCatching { surface.release() }
            runCatching { texture.release() }
        }
    }

    private class SinglePlaneFrameReaderPort(
        private val width: Int,
        private val height: Int,
        private val argbPixel: Int,
        private val bottomHalfPixel: Int = argbPixel,
    ) : FrameReaderPort {
        override val maxImages: Int = VirtualDisplayFrameSource.MAX_IMAGES
        private val texture = SurfaceTexture(0)
        override val surface: Surface = Surface(texture)
        override fun setListener(handler: Handler?, listener: (() -> Unit)?) = Unit

        override fun acquireLatestPlane(block: (RawRgbaPlane) -> Unit): Boolean {
            val bytes = ByteBuffer.allocate(width * height * 4)
            for (y in 0 until height) {
                val argb = if (y < height / 2) argbPixel else bottomHalfPixel
                val rgba = (argb shl 8) or (argb ushr 24)
                repeat(width) { bytes.putInt(rgba) }
            }
            bytes.flip()
            block(
                RawRgbaPlane(
                    width = width,
                    height = height,
                    rowStride = width * 4,
                    pixelStride = 4,
                    timestampNanos = 1234L,
                    buffer = bytes,
                ),
            )
            return true
        }

        override fun dropLatestFrame() = Unit
        override fun close() {
            runCatching { surface.release() }
            runCatching { texture.release() }
        }
    }
}
