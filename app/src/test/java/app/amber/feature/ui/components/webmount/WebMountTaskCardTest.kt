package app.amber.feature.ui.components.webmount

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
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

        compose.onNodeWithText("会话 0").assertIsDisplayed()
        compose.onNodeWithText("会话 7").assertIsNotDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("会话 7"))
        compose.onNodeWithText("会话 7").assertIsDisplayed()
    }
}
