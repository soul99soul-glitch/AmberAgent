package app.amber.core.automation.display

import android.app.Application
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import app.amber.core.automation.ScreenAction
import app.amber.core.automation.ScreenActionKind
import app.amber.core.automation.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DisplayInputGuardTest {

    @Test
    fun displayIdReadBackMismatchRefusesMotionAndKeyInjection() {
        val backend = FakeDisplayServiceBackend(readBackDisplayIdOverride = { Display.DEFAULT_DISPLAY })
        val service = AmberDisplayUserService(backend, onDestroyProcess = {})
        val texture = SurfaceTexture(10)
        val surface = Surface(texture)
        val motion = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        val key = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B)
        try {
            val displayId = service.createDisplay("run-1", "com.example.app", 1L, 720, 1600, 320, surface)
            assertEquals(12, displayId)

            backend.readBackDisplayIdOverride = { it }
            assertFalse(service.injectMotion(1L, displayId, "com.example.app", motion))
            assertEquals(0, service.launchOnDisplay(1L, displayId, "com.example.app"))
            assertFalse(service.injectKey(1L, displayId, "com.other.app", key))
            backend.readBackDisplayIdOverride = { Display.DEFAULT_DISPLAY }

            // Readback returned 0 instead of 12 -> MUST refuse injection and invoke injectInputEvent 0 times.
            assertFalse(service.injectMotion(1L, displayId, "com.example.app", motion))
            assertFalse(service.injectKey(1L, displayId, "com.example.app", key))
            assertEquals(0, backend.injectedEvents.size)

            // Readback returns wrong non-zero display (99 != 12) -> still refused.
            backend.readBackDisplayIdOverride = { 99 }
            assertFalse(service.injectMotion(1L, displayId, "com.example.app", motion))
            assertFalse(service.injectKey(1L, displayId, "com.example.app", key))
            assertEquals(0, backend.injectedEvents.size)

            // Readback matches 12 -> injection succeeds.
            backend.readBackDisplayIdOverride = { it }
            assertTrue(service.injectMotion(1L, displayId, "com.example.app", motion))
            assertTrue(service.injectKey(1L, displayId, "com.example.app", key))
            assertEquals(2, backend.injectedEvents.size)
        } finally {
            motion.recycle()
            surface.release()
            texture.release()
        }
    }

    @Test
    fun userServiceRejectsDefaultDisplayAndStaleGenerationAndInvalidUid() {
        // Root UID 0 rejected.
        val rootBackend = FakeDisplayServiceBackend(uidValue = 0)
        val rootService = AmberDisplayUserService(rootBackend, onDestroyProcess = {})
        val texture = SurfaceTexture(11)
        val surface = Surface(texture)
        val motion = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 20f, 20f, 0)
        try {
            assertEquals(
                AmberDisplayUserService.ERR_INVALID_UID,
                rootService.createDisplay("run-root", "com.example.app", 1L, 720, 1600, 320, surface),
            )

            val shellBackend = FakeDisplayServiceBackend(uidValue = AmberDisplayUserService.SHELL_UID)
            val shellService = AmberDisplayUserService(shellBackend, onDestroyProcess = {})
            val displayId = shellService.createDisplay("run-shell", "com.example.app", 5L, 720, 1600, 320, surface)
            assertEquals(12, displayId)

            // Display 0 is always forbidden.
            assertFalse(shellService.injectMotion(5L, Display.DEFAULT_DISPLAY, "com.example.app", motion))
            assertEquals(
                AmberDisplayUserService.ERR_INVALID_DISPLAY_ID,
                shellService.launchOnDisplay(5L, Display.DEFAULT_DISPLAY, "com.example.app"),
            )

            // Stale generation is forbidden.
            assertFalse(shellService.injectMotion(4L, displayId, "com.example.app", motion))
            assertEquals(
                AmberDisplayUserService.ERR_STALE_GENERATION,
                shellService.launchOnDisplay(4L, displayId, "com.example.app"),
            )

            // Arbitrary URI / shell injection string rejected.
            assertEquals(
                AmberDisplayUserService.ERR_INVALID_PACKAGE,
                shellService.launchOnDisplay(5L, displayId, "intent:#Intent;end"),
            )
            assertEquals(
                AmberDisplayUserService.ERR_INVALID_PACKAGE,
                shellService.launchOnDisplay(5L, displayId, "com.example.app; rm -rf /"),
            )

            // The package is immutable from display creation, including the very first launch.
            assertEquals(
                AmberDisplayUserService.ERR_PACKAGE_MISMATCH,
                shellService.launchOnDisplay(5L, displayId, "com.other.app"),
            )
            assertTrue(shellBackend.launchedPackages.isEmpty())
            assertEquals(0, shellService.launchOnDisplay(5L, displayId, "com.example.app"))
            assertEquals(
                AmberDisplayUserService.ERR_PACKAGE_MISMATCH,
                shellService.launchOnDisplay(5L, displayId, "com.other.app"),
            )

            // destroy() releases virtual display and rejects further calls.
            var processExited = false
            val destroyable = AmberDisplayUserService(shellBackend, onDestroyProcess = { processExited = true })
            val vd2 = destroyable.createDisplay("run-2", "com.example.app", 6L, 720, 1600, 320, surface)
            assertEquals(12, vd2)
            destroyable.destroy()
            assertTrue(processExited)
            assertTrue(shellBackend.releaseCount >= 1)
            assertFalse(destroyable.injectMotion(6L, vd2, "com.example.app", motion))
        } finally {
            motion.recycle()
            surface.release()
            texture.release()
        }
    }

    @Test
    fun userServiceRequiresMandatoryFlagsNeverUsesForbiddenFlagsAndNeverDowngrades() {
        val texture = SurfaceTexture(12)
        val surface = Surface(texture)
        try {
            // 1. Verify required flags are set and AUTO_MIRROR / SECURE are never set.
            val backend = FakeDisplayServiceBackend()
            val service = AmberDisplayUserService(backend, onDestroyProcess = {})
            val vd = service.createDisplay("run-flags", "com.example.app", 1L, 720, 1600, 320, surface)
            assertEquals(12, vd)
            val usedFlags = backend.requestedFlags.last()
            assertTrue((usedFlags and DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0)
            assertTrue((usedFlags and DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) != 0)
            assertTrue((usedFlags and FakeDisplayServiceBackend.FLAG_OWN_FOCUS) != 0)
            assertTrue((usedFlags and FakeDisplayServiceBackend.FLAG_DESTROY_CONTENT_ON_REMOVAL) != 0)
            assertEquals(0, usedFlags and DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
            assertEquals(0, usedFlags and DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE)

            // 2. When TRUSTED is rejected, retries with ALL 4 mandatory flags intact.
            val rejectTrustedBackend = FakeDisplayServiceBackend(rejectTrustedFlag = true)
            val rejectTrustedService = AmberDisplayUserService(rejectTrustedBackend, onDestroyProcess = {})
            assertEquals(12, rejectTrustedService.createDisplay("run-no-trusted", "com.example.app", 2L, 720, 1600, 320, surface))
            val fallbackFlags = rejectTrustedBackend.requestedFlags.last()
            assertEquals(0, fallbackFlags and FakeDisplayServiceBackend.FLAG_TRUSTED)
            assertTrue((fallbackFlags and FakeDisplayServiceBackend.FLAG_OWN_FOCUS) != 0)
            assertTrue((fallbackFlags and FakeDisplayServiceBackend.FLAG_DESTROY_CONTENT_ON_REMOVAL) != 0)

            // 3. When required flags are rejected, fails with ERR_FLAG_REJECTED — never drops OWN_FOCUS.
            val rejectAllBackend = FakeDisplayServiceBackend(rejectAllFlags = true)
            val rejectAllService = AmberDisplayUserService(rejectAllBackend, onDestroyProcess = {})
            assertEquals(
                AmberDisplayUserService.ERR_FLAG_REJECTED,
                rejectAllService.createDisplay("run-reject", "com.example.app", 3L, 720, 1600, 320, surface),
            )
            assertTrue(
                rejectAllBackend.requestedFlags.all { flags ->
                    (flags and FakeDisplayServiceBackend.FLAG_OWN_FOCUS) != 0 &&
                        (flags and FakeDisplayServiceBackend.FLAG_DESTROY_CONTENT_ON_REMOVAL) != 0
                },
            )

            // 4. Missing OWN_FOCUS flag constant -> ERR_MISSING_REQUIRED_FLAG.
            val missingFlagBackend = FakeDisplayServiceBackend(ownFocusAvailable = false)
            val missingFlagService = AmberDisplayUserService(missingFlagBackend, onDestroyProcess = {})
            assertEquals(
                AmberDisplayUserService.ERR_MISSING_REQUIRED_FLAG,
                missingFlagService.createDisplay("run-missing", "com.example.app", 4L, 720, 1600, 320, surface),
            )

            // 5. API < 34 -> ERR_API_UNSUPPORTED.
            val lowApiBackend = FakeDisplayServiceBackend(apiLevel = 33)
            val lowApiService = AmberDisplayUserService(lowApiBackend, onDestroyProcess = {})
            assertEquals(
                AmberDisplayUserService.ERR_API_UNSUPPORTED,
                lowApiService.createDisplay("run-low-api", "com.example.app", 5L, 720, 1600, 320, surface),
            )
        } finally {
            surface.release()
            texture.release()
        }
    }

    @Test
    fun virtualAccessibilityControllerWithoutKeyInjectorRejectsKeysAndDefaultDisplaySnapshots() {
        val controller = VirtualDisplayAccessibilityController(
            displayId = 15,
            generation = 4L,
            expectedPackageName = "com.example.target",
            isGenerationValid = { true },
            displayBoundsProvider = { Rect(0, 0, 720, 1600) },
            motionInjector = { true },
            windowsProvider = { emptyList() },
        )

        assertFalse(controller.back())
        assertFalse(controller.pressEnter())
        assertFalse(controller.home())
        assertFalse(controller.returnToHostApp("app.amber.agent"))

        // With empty windows on virtual display, never falls back to Display 0.
        assertNull(controller.captureScreenSnapshot())

        // Snapshot ID from Display 0 (no vd:{displayId}:{generation}: prefix) is rejected.
        val foreignReceipt = controller.performScreenAction(
            snapshot = ScreenSnapshot(
                id = "physical-sha256-hash",
                packageName = "com.example.target",
                windowId = 1,
                nodes = emptyList(),
            ),
            action = ScreenAction(ScreenActionKind.CLICK, "0"),
        )
        assertEquals("stale_snapshot", foreignReceipt.status)
        assertEquals("snapshot_display_or_generation_mismatch", foreignReceipt.reason)
        assertFalse(foreignReceipt.dispatched)

        // Verify snapshot ID format helper matches frozen contract.
        val formatted = VirtualDisplayAccessibilityController.formatSnapshotId(15, 4L, 7L, "deadbeef")
        assertTrue(formatted.startsWith("vd:15:4:7"))
    }

    @Test
    fun virtualControllerKeysRequireCurrentGenerationAndMatchingReadbackForEachEvent() {
        val backend = FakeDisplayServiceBackend()
        val service = AmberDisplayUserService(backend, onDestroyProcess = {})
        val texture = SurfaceTexture(13)
        val surface = Surface(texture)
        val pkg = "com.example.target"
        try {
            val id = service.createDisplay("run-keys", pkg, 4L, 720, 1600, 320, surface)
            assertTrue(id > Display.DEFAULT_DISPLAY)
            assertEquals(0, service.launchOnDisplay(4L, id, pkg))
            var valid = true
            val readbacks = mutableListOf<Int>()
            backend.readBackDisplayIdOverride = { requestedId ->
                readbacks += requestedId
                requestedId
            }
            val controller = VirtualDisplayAccessibilityController(
                displayId = id,
                generation = 4L,
                expectedPackageName = pkg,
                isGenerationValid = { valid },
                displayBoundsProvider = { Rect(0, 0, 720, 1600) },
                motionInjector = { false },
                keyInjector = { service.injectKey(4L, id, pkg, it) },
            )

            assertTrue(controller.back())
            assertTrue(controller.pressEnter())
            assertFalse(controller.home())
            assertFalse(controller.returnToHostApp("app.amber.agent"))
            val keys = backend.injectedEvents.map { it as KeyEvent }
            assertEquals(listOf(id, id, id, id), readbacks)
            assertEquals(
                listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ENTER),
                keys.map { it.keyCode },
            )
            assertEquals(
                listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP, KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP),
                keys.map { it.action },
            )

            valid = false
            assertFalse(controller.back())
            assertEquals(4, readbacks.size)

            valid = true
            backend.readBackDisplayIdOverride = {
                valid = false
                it
            }
            assertFalse(controller.back())
            assertEquals(5, backend.injectedEvents.size)
            assertEquals(KeyEvent.ACTION_DOWN, (backend.injectedEvents.last() as KeyEvent).action)
        } finally {
            service.release(4L, "test_complete")
            surface.release()
            texture.release()
        }
    }

    private class FakeDisplayServiceBackend(
        private val uidValue: Int = AmberDisplayUserService.SHELL_UID,
        private val apiLevel: Int = 34,
        private val ownFocusAvailable: Boolean = true,
        private val destroyOnRemovalAvailable: Boolean = true,
        private val rejectTrustedFlag: Boolean = false,
        private val rejectAllFlags: Boolean = false,
        var readBackDisplayIdOverride: (Int) -> Int? = { it },
    ) : DisplayServiceBackend {
        val requestedFlags = mutableListOf<Int>()
        val injectedEvents = mutableListOf<InputEvent>()
        val launchedPackages = mutableListOf<String>()
        var releaseCount = 0

        override fun uid(): Int = uidValue

        override fun probeCapabilities(): DisplayCapabilities = DisplayCapabilities(
            apiLevel = apiLevel,
            uid = uidValue,
            ownFocus = apiLevel >= 34 && ownFocusAvailable,
            destroyContentOnRemoval = destroyOnRemovalAvailable,
            trusted = true,
            secondaryActivities = true,
        )

        override fun resolveVirtualDisplayFlags(): ResolvedDisplayFlags = ResolvedDisplayFlags(
            ownFocusFlag = if (ownFocusAvailable) FLAG_OWN_FOCUS else null,
            destroyContentOnRemovalFlag = if (destroyOnRemovalAvailable) FLAG_DESTROY_CONTENT_ON_REMOVAL else null,
            trustedFlag = FLAG_TRUSTED,
        )

        override fun createVirtualDisplay(
            name: String,
            width: Int,
            height: Int,
            dpi: Int,
            surface: Surface,
            flags: Int,
        ): CreateDisplayBackendResult {
            requestedFlags += flags
            if (rejectAllFlags) return CreateDisplayBackendResult.FlagRejected("rejected")
            if (rejectTrustedFlag && (flags and FLAG_TRUSTED) != 0) {
                return CreateDisplayBackendResult.FlagRejected("trusted_rejected")
            }
            return CreateDisplayBackendResult.Created(
                object : VirtualDisplayHandle {
                    override val displayId: Int = 12
                    override fun release() {
                        releaseCount++
                    }
                },
            )
        }

        override fun launchPackageOnDisplay(displayId: Int, target: String): Boolean {
            launchedPackages += target
            return true
        }

        override fun setAndReadBackDisplayId(event: InputEvent, displayId: Int): Int? =
            readBackDisplayIdOverride(displayId)

        override fun injectInputEvent(event: InputEvent): Boolean {
            injectedEvents += event
            return true
        }

        companion object {
            const val FLAG_TRUSTED = 1 shl 10
            const val FLAG_OWN_FOCUS = 1 shl 14
            const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 12
        }
    }
}
