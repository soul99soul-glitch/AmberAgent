package app.amber.feature.ui.pages.phoneuse

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.amber.core.automation.AccessibilityController
import app.amber.core.automation.display.ActiveVirtualDisplayRun
import app.amber.core.automation.display.PhoneDisplayMode
import app.amber.core.automation.display.PhoneDisplaySession
import app.amber.core.automation.display.VirtualDisplayRunControlState
import app.amber.core.automation.display.VirtualDisplayRunRegistry
import app.amber.feature.ui.theme.AmberLight
import app.amber.feature.ui.theme.LocalAmberTokens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w400dp-h800dp-xxhdpi")
class VirtualDisplayMiniWindowOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        VirtualDisplayRunRegistry.clearForTest()
    }

    private fun fakeSession(): PhoneDisplaySession = object : PhoneDisplaySession {
        override val mode: PhoneDisplayMode = PhoneDisplayMode.VIRTUAL
        override val displayId: Int = 7
        override val generation: Long = 1L
        override val controller: AccessibilityController
            get() = error("unused in mini window UI test")

        override suspend fun launchTarget(): Boolean = true
        override suspend fun close(reason: String) = Unit
    }

    @Test
    fun clampAndEdgeSnapKeepMiniWindowWithinSafeBounds() {
        val (clampedX, clampedY) = clampMiniWindowOffset(
            x = -100f,
            y = 10f,
            windowWidthPx = 400f,
            windowHeightPx = 700f,
            containerWidthPx = 1080f,
            containerHeightPx = 2400f,
            edgeMarginPx = 36f,
            topSafePx = 200f,
            bottomSafePx = 360f,
        )
        assertEquals(36f, clampedX, 0.01f)
        assertEquals(200f, clampedY, 0.01f)

        val leftSnap = snapMiniWindowHorizontalEdge(
            currentX = 120f,
            windowWidthPx = 400f,
            containerWidthPx = 1080f,
            edgeMarginPx = 36f,
        )
        val rightSnap = snapMiniWindowHorizontalEdge(
            currentX = 520f,
            windowWidthPx = 400f,
            containerWidthPx = 1080f,
            edgeMarginPx = 36f,
        )
        assertEquals(36f, leftSnap, 0.01f)
        assertEquals(644f, rightSnap, 0.01f)
    }

    @Test
    fun miniWindowHidesWithoutRun_showsOnRegister_andControlsPauseMinimizeExpandStop() {
        VirtualDisplayRunRegistry.clearForTest()
        var openedLiveRunId: String? = null
        var pauseCount = 0
        var resumeCount = 0
        var stopCount = 0

        compose.setContent {
            CompositionLocalProvider(LocalAmberTokens provides AmberLight) {
                MaterialTheme {
                    VirtualDisplayMiniWindowOverlay(
                        onOpenLive = { openedLiveRunId = it },
                    )
                }
            }
        }

        // 1) Hidden when no active run exists.
        compose.waitForIdle()
        compose.onNodeWithTag("vd-mini-window").assertDoesNotExist()
        compose.onNodeWithTag("vd-mini-window-pill").assertDoesNotExist()

        // 2) Appears automatically when a virtual display run registers.
        val run = ActiveVirtualDisplayRun(
            runId = "vd-run-xhs",
            packageName = "com.xingin.xhs",
            goal = "刷一下 GPT6.2 的相关内容",
            session = fakeSession(),
            frameSource = null,
            onPauseRequested = { pauseCount++ },
            onResumeRequested = { resumeCount++ },
            onEmergencyStopRequested = { stopCount++ },
        )
        VirtualDisplayRunRegistry.register(run)
        compose.waitForIdle()

        compose.onNodeWithTag("vd-mini-window").assertIsDisplayed()
        compose.onNodeWithText("com.xingin.xhs").assertIsDisplayed()

        // 3) Pause / Resume controls update run controlState cleanly.
        compose.onNodeWithTag("vd-mini-window-pause-resume").performClick()
        compose.waitForIdle()
        assertEquals(VirtualDisplayRunControlState.PAUSED, run.controlState.value)
        assertEquals(1, pauseCount)

        compose.onNodeWithTag("vd-mini-window-pause-resume").performClick()
        compose.waitForIdle()
        assertEquals(VirtualDisplayRunControlState.RUNNING, run.controlState.value)
        assertEquals(1, resumeCount)

        // 4) Expand navigates to PhoneUseLive(runId).
        compose.onNodeWithTag("vd-mini-window-expand").performClick()
        assertEquals("vd-run-xhs", openedLiveRunId)

        // 5) Minimize collapses into floating pill; tapping pill restores mini window.
        compose.onNodeWithTag("vd-mini-window-minimize").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("vd-mini-window").assertDoesNotExist()
        compose.onNodeWithTag("vd-mini-window-pill").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("vd-mini-window").assertIsDisplayed()

        // 6) Emergency stop invokes stop callback and sets STOPPED state.
        compose.onNodeWithTag("vd-mini-window-stop").performClick()
        compose.waitForIdle()
        assertEquals(VirtualDisplayRunControlState.STOPPED, run.controlState.value)
        assertTrue(stopCount == 1)

        // 7) Unregistering hides the floating mini window cleanly.
        VirtualDisplayRunRegistry.unregister(run.runId, run)
        compose.waitForIdle()
        compose.onNodeWithTag("vd-mini-window").assertDoesNotExist()
    }
}
