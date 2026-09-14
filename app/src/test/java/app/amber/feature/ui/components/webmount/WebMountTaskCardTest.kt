package app.amber.feature.ui.components.webmount

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import app.amber.feature.webmount.primitives.WebMountOwner
import app.amber.feature.webmount.primitives.WebMountSessionMetadata
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "zh")
class WebMountTaskCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun needsReopenOffersExplicitReopenAction() {
        val session = WebMountSessionMetadata(
            sessionId = "wm_reopen",
            title = "登录页",
            needsReopen = true,
        )
        var opened: Pair<String, Boolean>? = null

        compose.setContent {
            MaterialTheme {
                WebMountTaskCard(
                    sessions = listOf(session),
                    onOpenSession = { id, reopen -> opened = id to reopen },
                )
            }
        }

        compose.onNodeWithText("浏览器会话需要重开").assertIsDisplayed()
        compose.onNodeWithText("重开").performClick()
        assertEquals("wm_reopen" to true, opened)
    }

    @Test
    fun agentOwnedSessionRemainsViewOnlyEntry() {
        val session = WebMountSessionMetadata(
            sessionId = "wm_agent",
            title = "资料页",
            owner = WebMountOwner.AGENT,
        )
        var opened: Pair<String, Boolean>? = null

        compose.setContent {
            MaterialTheme {
                WebMountTaskCard(
                    sessions = listOf(session),
                    onOpenSession = { id, reopen -> opened = id to reopen },
                )
            }
        }

        compose.onNodeWithText("Amber 正在使用").assertIsDisplayed()
        compose.onNodeWithText("查看").performClick()
        assertEquals("wm_agent" to false, opened)
    }

    @Test
    fun multipleSessionsStayBoundedAndScrollToLastAtLargeFont() {
        val sessions = (0 until 8).map { index ->
            WebMountSessionMetadata(
                sessionId = "wm_$index",
                title = "会话 $index",
                status = "ready",
            )
        }

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    WebMountTaskCard(
                        sessions = sessions,
                        onOpenSession = { _, _ -> },
                    )
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        val actionLabel = compose.onAllNodesWithText("查看", useUnmergedTree = true)[0].fetchSemanticsNode()
        assertTrue(actionLabel.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
        assertFalse("Action text must not be clipped at large font scale", layouts.single().didOverflowHeight)
        compose.onNodeWithText("会话 0").assertIsDisplayed()
        compose.onNodeWithText("会话 7").assertIsNotDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("会话 7"))
        compose.onNodeWithText("会话 7").assertIsDisplayed()
    }

    @Test
    fun cardCanCollapseExpandAnimateActivityAndDismiss() {
        val session = WebMountSessionMetadata(
            sessionId = "wm_activity",
            title = "iCloud 云盘",
            redactedUrl = "https://www.icloud.com/iclouddrive/",
            status = "ready",
        )
        var activity by mutableStateOf<String?>("打开云盘")
        var dismissed = false
        var renderedView: View? = null

        compose.setContent {
            renderedView = LocalView.current
            MaterialTheme {
                WebMountTaskCard(
                    sessions = listOf(session),
                    currentActivity = activity,
                    onDismiss = { dismissed = true },
                    onOpenSession = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("打开云盘").assertIsDisplayed()
        File("build/reports/webmount-ui").mkdirs()
        compose.runOnIdle {
            val view = requireNotNull(renderedView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/reports/webmount-ui/task-card.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val expandedHeight = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("折叠").performClick()
        // Android measurement follows the Compose frame; sample completed frames
        // rather than assuming that the transition starts on the click's frame.
        val heights = (1..12).map {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        }
        assertTrue("Height transition: $expandedHeight -> $heights", heights.any { it < expandedHeight && it > 36f })
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("打开云盘").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").assertDoesNotExist()
        compose.onRoot().assertHeightIsEqualTo(36.dp)

        compose.runOnIdle {
            val view = requireNotNull(renderedView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/reports/webmount-ui/task-strip.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithContentDescription("展开").performTouchInput { swipeUp(startY = height - 1f, endY = 1f) }
        compose.onNodeWithText("iCloud 云盘").assertIsDisplayed()
        compose.onNodeWithContentDescription("折叠").performTouchInput { swipeDown(startY = 1f, endY = height - 1f) }
        compose.onNodeWithContentDescription("展开").assertIsDisplayed()

        compose.runOnIdle { activity = "读取文件" }
        compose.waitForIdle()
        compose.onNodeWithText("读取文件").assertIsDisplayed()

        compose.onNodeWithContentDescription("展开").performClick()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.runOnIdle { assertEquals(true, dismissed) }
    }
}
