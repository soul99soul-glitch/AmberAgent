package app.amber.core.automation.display

import android.app.Application
import android.graphics.SurfaceTexture
import android.os.Handler
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.AccessibilityTextMatch
import app.amber.core.automation.ScreenAction
import app.amber.core.automation.ScreenActionKind
import app.amber.core.automation.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VirtualDisplaySessionStateTest {

    @Test
    fun generationInvalidationRejectsAllSubsequentLaunchAndInjection() = runTest {
        val bridge = FakeConnectedBridge(displayIdToReturn = 7)
        val framePort = StubFrameReaderPort()
        val factory = createFactory(
            bridge = FakeBridgeConnector(bridge),
            framePortFactory = { framePort },
            probeVerified = true,
        )

        val outcome = factory.open("run-1", "com.example.target", PhoneDisplayMode.VIRTUAL)
        assertTrue(outcome is DisplaySessionOutcome.Ready)
        val session = (outcome as DisplaySessionOutcome.Ready).session as VirtualDisplaySession
        assertEquals(PhoneDisplayMode.VIRTUAL, session.mode)
        assertEquals(7, session.displayId)
        assertEquals(VirtualDisplaySessionState.OPEN, session.state.value)

        assertTrue(session.launchTarget())
        assertEquals(1, bridge.launchCount)

        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 200f, 0)
        val key = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        try {
            assertTrue(session.injectMotion(down))
            assertTrue(session.injectKey(key))
            assertEquals(1, bridge.motionCount)
            assertEquals(1, bridge.keyCount)

            // Simulate Shizuku disconnect while idle.
            session.invalidate("shizuku_binder_dead")
            assertFalse(session.isGenerationValid)
            assertEquals(VirtualDisplaySessionState.SAFE_PAUSED, session.state.value)
            assertEquals(PhoneDisplayMode.VIRTUAL, session.mode) // Never hot-downgrades to PHYSICAL.

            // Every subsequent launch/inject/tap/action must be rejected.
            assertFalse(session.launchTarget())
            assertFalse(session.injectMotion(down))
            assertFalse(session.injectKey(key))
            assertFalse(session.controller.tap(100f, 200f))
            assertEquals(1, bridge.launchCount)
            assertEquals(1, bridge.motionCount)
            assertEquals(1, bridge.keyCount)

            val receipt = session.controller.performScreenAction(
                ScreenSnapshot(
                    id = "vd:7:${session.generation}:1:abcd",
                    packageName = "com.example.target",
                    windowId = 1,
                    nodes = emptyList(),
                ),
                ScreenAction(ScreenActionKind.CLICK, "0"),
            )
            assertEquals("stale_snapshot", receipt.status)
            assertFalse(receipt.dispatched)
        } finally {
            down.recycle()
            session.close("test_end")
        }
    }

    @Test
    fun disconnectDuringInFlightDispatchTransitionsToOutcomeUnknownWithoutReplay() = runTest {
        lateinit var triggerDisconnect: (String) -> Unit
        val bridge = FakeConnectedBridge(
            displayIdToReturn = 9,
            onMotionSideEffect = { triggerDisconnect("shizuku_binder_dead") },
        )
        val factory = createFactory(
            bridge = FakeBridgeConnector(bridge) { callback -> triggerDisconnect = callback },
            framePortFactory = { StubFrameReaderPort() },
            probeVerified = true,
        )

        val session = (factory.open("run-2", "com.example.target", PhoneDisplayMode.VIRTUAL) as DisplaySessionOutcome.Ready)
            .session as VirtualDisplaySession
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 50f, 50f, 0)
        try {
            val injected = session.injectMotion(event)
            assertFalse(injected)
            assertEquals(VirtualDisplaySessionState.OUTCOME_UNKNOWN, session.state.value)
            assertEquals(1, bridge.motionCount) // Invoked once, never replayed.
            assertFalse(session.injectMotion(event))
            assertEquals(1, bridge.motionCount)
        } finally {
            event.recycle()
            session.close("test_end")
        }
    }

    @Test
    fun markUnobservableStopsSessionAndClosesResources() = runTest {
        val bridge = FakeConnectedBridge(displayIdToReturn = 5)
        val framePort = StubFrameReaderPort()
        val factory = createFactory(
            bridge = FakeBridgeConnector(bridge),
            framePortFactory = { framePort },
            probeVerified = true,
        )
        val session = (factory.open("run-3", "com.example.target", PhoneDisplayMode.VIRTUAL) as DisplaySessionOutcome.Ready)
            .session as VirtualDisplaySession

        session.markUnobservable("secure_black_frame")
        assertEquals(VirtualDisplaySessionState.UNOBSERVABLE, session.state.value)
        assertFalse(session.isGenerationValid)
        assertTrue(session.frameSource.isClosed)
        assertTrue(framePort.closed)
        assertTrue(bridge.disconnected)
        assertEquals(1, bridge.releaseCount)
        assertFalse(session.launchTarget())
    }

    @Test
    fun factoryReturnsUnavailableOnApiBelow34MissingFlagsFlagRejectedOrInvalidUid() = runTest {
        // 1. API < 34
        val lowApi = createFactory(
            bridge = FakeBridgeConnector(FakeConnectedBridge()),
            apiLevel = 33,
            probeVerified = true,
        ).open("run", "com.example.target", PhoneDisplayMode.VIRTUAL)
        assertTrue(lowApi is DisplaySessionOutcome.Unavailable)
        assertTrue((lowApi as DisplaySessionOutcome.Unavailable).reason.contains("api_below_34"))

        // 2. Missing OWN_FOCUS
        val missingOwnFocusBridge = FakeConnectedBridge(
            caps = validCaps().copy(ownFocus = false),
        )
        val missingOwnFocus = createFactory(
            bridge = FakeBridgeConnector(missingOwnFocusBridge),
            probeVerified = true,
        ).open("run", "com.example.target", PhoneDisplayMode.VIRTUAL)
        assertTrue(missingOwnFocus is DisplaySessionOutcome.Unavailable)
        assertEquals("missing_own_focus_flag", (missingOwnFocus as DisplaySessionOutcome.Unavailable).reason)
        assertTrue(missingOwnFocusBridge.disconnected)

        // 3. Flag rejected during createDisplay
        val flagRejectedBridge = FakeConnectedBridge(
            displayIdToReturn = AmberDisplayUserService.ERR_FLAG_REJECTED,
        )
        val framePort = StubFrameReaderPort()
        val flagRejected = createFactory(
            bridge = FakeBridgeConnector(flagRejectedBridge),
            framePortFactory = { framePort },
            probeVerified = true,
        ).open("run", "com.example.target", PhoneDisplayMode.VIRTUAL)
        assertTrue(flagRejected is DisplaySessionOutcome.Unavailable)
        assertTrue((flagRejected as DisplaySessionOutcome.Unavailable).reason.contains("display_flag_rejected"))
        assertTrue(framePort.closed)
        assertTrue(flagRejectedBridge.disconnected)

        // 4. UID != 2000 (root UID 0 rejected)
        val rootBridge = FakeConnectedBridge(uidValue = 0, caps = validCaps().copy(uid = 0))
        val rootRejected = createFactory(
            bridge = FakeBridgeConnector(rootBridge),
            probeVerified = true,
        ).open("run", "com.example.target", PhoneDisplayMode.VIRTUAL)
        assertTrue(rootRejected is DisplaySessionOutcome.Unavailable)
        assertTrue((rootRejected as DisplaySessionOutcome.Unavailable).reason.contains("invalid_shizuku_uid"))
        assertTrue(rootBridge.disconnected)

        // 5. Probe store UNVERIFIED
        val unverified = createFactory(
            bridge = FakeBridgeConnector(FakeConnectedBridge()),
            probeVerified = false,
        ).open("run", "com.example.target", PhoneDisplayMode.VIRTUAL)
        assertTrue(unverified is DisplaySessionOutcome.Unavailable)
        assertEquals("isolation_probe_unverified", (unverified as DisplaySessionOutcome.Unavailable).reason)
    }

    @Test
    fun physicalSessionPreservesExistingBehaviorUnchanged() = runTest {
        val physicalController = RecordingPhysicalController()
        val launchedPackages = mutableListOf<String>()
        val factory = createFactory(
            bridge = FakeBridgeConnector(FakeConnectedBridge()),
            physicalController = physicalController,
            physicalLauncher = { pkg ->
                launchedPackages += pkg
                true
            },
            probeVerified = false, // Physical mode does NOT require virtual probe verification.
        )

        val outcome = factory.open("run-phys", "com.example.app", PhoneDisplayMode.PHYSICAL)
        assertTrue(outcome is DisplaySessionOutcome.Ready)
        val session = (outcome as DisplaySessionOutcome.Ready).session
        assertEquals(PhoneDisplayMode.PHYSICAL, session.mode)
        assertEquals(Display.DEFAULT_DISPLAY, session.displayId)
        assertEquals(0L, session.generation)
        assertSame(physicalController, session.controller)
        assertTrue(session.controller.back())
        assertTrue(session.controller.home())
        assertTrue(session.launchTarget())
        assertEquals(listOf("com.example.app"), launchedPackages)

        session.close("done")
        assertTrue(session.controller.back())
    }

    private fun validCaps() = DisplayCapabilities(
        apiLevel = 34,
        uid = AmberDisplayUserService.SHELL_UID,
        ownFocus = true,
        destroyContentOnRemoval = true,
        trusted = true,
        secondaryActivities = true,
    )

    private fun createFactory(
        bridge: DisplayBridge,
        apiLevel: Int = 34,
        probeVerified: Boolean = true,
        physicalController: AccessibilityController = RecordingPhysicalController(),
        physicalLauncher: suspend (String) -> Boolean = { true },
        framePortFactory: () -> FrameReaderPort = { StubFrameReaderPort() },
    ): PhoneDisplaySessionFactory {
        val kv = InMemoryProbeKvStore()
        val probeStore = VirtualDisplayProbeStore(
            kv = kv,
            fingerprintProvider = { "fp-1" },
            versionCodeProvider = { 396L },
            environmentSupportedProvider = { apiLevel >= 34 },
        )
        if (probeVerified) {
            probeStore.record(
                IsolationProbeResult(
                    passed = true,
                    mainScreenInjectedEvents = 0,
                    targetReceivedEvents = 20,
                    textReadBackOk = true,
                    frameMarkerOk = true,
                    focusStable = true,
                    residualAfterDisconnect = 0,
                    leakedToDefaultOnDestroy = false,
                    detail = "ok",
                ),
            )
        }
        return PhoneDisplaySessionFactory(
            probeStore = probeStore,
            displayBridge = bridge,
            virtualDisplayBuildAllowed = { true },
            apiLevelProvider = { apiLevel },
            secondaryActivitiesFeatureProvider = { true },
            physicalControllerProvider = { physicalController },
            physicalSessionCreator = { pkg, ctrl -> PhysicalDisplaySession(pkg, ctrl, physicalLauncher) },
            virtualWindowsProvider = { emptyList() },
            accessibilityServiceAvailableProvider = { true },
            frameSourceCreator = { w, h -> VirtualDisplayFrameSource(w, h, framePortFactory(), ownWorkerThread = false) },
            displayWatcherFactory = { _, _, _, _ -> null },
        )
    }

    private class FakeBridgeConnector(
        private val connected: ConnectedDisplayBridge,
        private val onRegisterDisconnect: ((String) -> Unit) -> Unit = {},
    ) : DisplayBridge {
        override suspend fun connect(onDisconnected: (String) -> Unit): Result<ConnectedDisplayBridge> {
            onRegisterDisconnect(onDisconnected)
            return Result.success(connected)
        }
    }

    private class FakeConnectedBridge(
        private val uidValue: Int = AmberDisplayUserService.SHELL_UID,
        private val caps: DisplayCapabilities = DisplayCapabilities(
            apiLevel = 34,
            uid = AmberDisplayUserService.SHELL_UID,
            ownFocus = true,
            destroyContentOnRemoval = true,
            trusted = true,
            secondaryActivities = true,
        ),
        private val displayIdToReturn: Int = 3,
        private val onMotionSideEffect: () -> Unit = {},
    ) : ConnectedDisplayBridge {
        var launchCount = 0
        var motionCount = 0
        var keyCount = 0
        var releaseCount = 0
        var disconnected = false

        override fun uid(): Int = uidValue
        override fun capabilities(): DisplayCapabilities = caps
        override fun createDisplay(runId: String, generation: Long, width: Int, height: Int, dpi: Int, surface: Surface): Int =
            displayIdToReturn

        override fun launchOnDisplay(generation: Long, displayId: Int, packageName: String): Int {
            launchCount++
            return 0
        }

        override fun injectMotion(generation: Long, displayId: Int, event: MotionEvent): Boolean {
            motionCount++
            onMotionSideEffect()
            return true
        }

        override fun injectKey(generation: Long, displayId: Int, event: KeyEvent): Boolean {
            keyCount++
            return true
        }

        override fun release(generation: Long, reason: String) {
            releaseCount++
        }

        override fun disconnect() {
            disconnected = true
        }
    }

    private class StubFrameReaderPort : FrameReaderPort {
        private val texture = SurfaceTexture(1)
        override val maxImages: Int = VirtualDisplayFrameSource.MAX_IMAGES
        override val surface: Surface = Surface(texture)
        var closed: Boolean = false

        override fun setListener(handler: Handler?, listener: (() -> Unit)?) = Unit
        override fun acquireLatestPlane(block: (RawRgbaPlane) -> Unit): Boolean = false
        override fun dropLatestFrame() = Unit
        override fun close() {
            closed = true
            surface.release()
            texture.release()
        }
    }

    private class RecordingPhysicalController : AccessibilityController {
        override suspend fun tap(x: Float, y: Float, durationMillis: Long): Boolean = true
        override suspend fun longPress(x: Float, y: Float, durationMillis: Long): Boolean = true
        override suspend fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long): Boolean = true
        override fun setFocusedText(text: String): Boolean = true
        override fun dumpUiTree(maxNodes: Int): String = "physical-tree"
        override fun findTextNodes(query: String, maxNodes: Int): List<AccessibilityTextMatch> = emptyList()
        override fun back(): Boolean = true
        override fun home(): Boolean = true
    }
}

internal class InMemoryProbeKvStore : ProbeKvStore {
    private val map = linkedMapOf<String, Any>()
    override fun getString(key: String): String? = map[key] as? String
    override fun getLong(key: String, defaultValue: Long): Long = (map[key] as? Long) ?: defaultValue
    override fun getInt(key: String, defaultValue: Int): Int = (map[key] as? Int) ?: defaultValue
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = (map[key] as? Boolean) ?: defaultValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun write(values: Map<String, Any>) {
        map.putAll(values)
    }

    override fun clear() {
        map.clear()
    }
}
