package app.amber.feature.ui.components.message

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import app.amber.feature.ui.components.ui.ChainOfThought
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wrench
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
@Config(sdk = [34], application = Application::class)
class ChatMessageTouchTargetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun agentToolCallCapsuleKeepsCompactVisualAndExposes48DpClickTarget() {
        var clicks = 0
        var expectedTargetPx = 0
        compose.setContent {
            expectedTargetPx = with(LocalDensity.current) { 48.dp.roundToPx() }
            MaterialTheme {
                AgentToolCallCapsule(
                    title = "读取文件",
                    toolName = "file_read",
                    icon = Lucide.Wrench,
                    kind = AgentToolKind.FILE,
                    status = AgentToolStatus.SUCCEEDED,
                    loading = false,
                    onClick = { clicks++ },
                    approvalActions = null,
                )
            }
        }

        val target = compose.onNode(hasClickAction(), useUnmergedTree = true)
        target.assertHasClickAction()
        assertTrue(target.fetchSemanticsNode().size.height >= expectedTargetPx)
        target.performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun chainOfThoughtCollapseControlExposes48DpClickTarget() {
        var expectedTargetPx = 0
        compose.setContent {
            expectedTargetPx = with(LocalDensity.current) { 48.dp.roundToPx() }
            MaterialTheme {
                ChainOfThought(
                    steps = listOf("first", "second"),
                    collapsedVisibleCount = 1,
                ) { step ->
                    ChainOfThoughtStep(label = { androidx.compose.material3.Text(step) })
                }
            }
        }

        val target = compose.onNode(hasClickAction(), useUnmergedTree = true)
        target.assertHasClickAction()
        assertTrue(target.fetchSemanticsNode().size.height >= expectedTargetPx)
        compose.onNodeWithText("first").assertDoesNotExist()
        target.performClick()
        compose.onNodeWithText("first").assertExists()
    }

    @Test
    fun chainOfThoughtStepExposes48DpClickTargetAndExpandsContent() {
        var expectedTargetPx = 0
        compose.setContent {
            expectedTargetPx = with(LocalDensity.current) { 48.dp.roundToPx() }
            MaterialTheme {
                ChainOfThought(steps = listOf("step")) { value ->
                    ChainOfThoughtStep(
                        label = { androidx.compose.material3.Text(value) },
                        content = { androidx.compose.material3.Text("details") },
                    )
                }
            }
        }

        val target = compose.onNode(hasClickAction(), useUnmergedTree = true)
        target.assertHasClickAction()
        assertTrue(target.fetchSemanticsNode().size.height >= expectedTargetPx)
        target.performClick()
        compose.onNodeWithText("details").assertExists()
    }
}
