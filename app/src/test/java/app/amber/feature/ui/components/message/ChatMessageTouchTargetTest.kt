package app.amber.feature.ui.components.message

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import app.amber.feature.ui.components.ui.ChainOfThought
import app.amber.feature.ui.context.LocalSettings
import app.amber.core.settings.DisplaySetting
import app.amber.core.settings.Settings
import app.amber.ai.ui.UIMessagePart
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
import kotlin.time.Clock

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
        val expanded = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            expectedTargetPx = with(LocalDensity.current) { 48.dp.roundToPx() }
            MaterialTheme {
                ChainOfThought(steps = listOf("step")) { value ->
                    ControlledChainOfThoughtStep(
                        expanded = expanded.value,
                        onExpandedChange = { expanded.value = it },
                        label = { androidx.compose.material3.Text(value) },
                        framed = true,
                        content = {
                            androidx.compose.material3.Text("details", modifier = Modifier.fillMaxWidth())
                        },
                    )
                }
            }
        }

        val target = compose.onNode(hasClickAction(), useUnmergedTree = true)
        target.assertHasClickAction()
        assertTrue(target.fetchSemanticsNode().size.height >= expectedTargetPx)
        val collapsedLabelBounds = compose.onNodeWithText("step", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        target.performClick()
        compose.onNodeWithText("details").assertExists()
        val expandedLabelBounds = compose.onNodeWithText("step", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("reasoning title top should stay anchored", collapsedLabelBounds.top, expandedLabelBounds.top, 1f)
        assertEquals("reasoning title left should stay anchored", collapsedLabelBounds.left, expandedLabelBounds.left, 1f)
        val body = compose.onNodeWithText("details").fetchSemanticsNode().boundsInRoot
        val card = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("reasoning body must have equal left/right insets", body.left - card.left, card.right - body.right, 1f)
    }

    @Test
    fun realReasoningStepPreviewCollapsesOnTapAndAutoClosesAfterFinishedAt() {
        // Streaming follows frames continuously; advance a bounded clock instead of waiting for it to stop.
        compose.mainClock.autoAdvance = false
        val reasoningState = androidx.compose.runtime.mutableStateOf(
            UIMessagePart.Reasoning("first thought", finishedAt = null),
        )
        val messageLoadingState = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings(
                    displaySetting = DisplaySetting(
                        showThinkingContent = true,
                        autoCloseThinking = true,
                    )
                )
            ) {
                MaterialTheme {
                    ChainOfThought(steps = listOf(Unit)) {
                        ChatMessageReasoningStep(
                            reasoning = reasoningState.value,
                            model = null,
                            regexes = emptyList(),
                            loading = messageLoadingState.value,
                            framed = true,
                        )
                    }
                }
            }
        }

        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val target = compose.onNode(hasClickAction(), useUnmergedTree = true)
        compose.onNodeWithText("first thought").assertExists()

        // Preview is content-visible but must be treated as expanded by the control. A tap must
        // collapse it directly; the next reasoning append must not reopen the content.
        target.performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("first thought").assertDoesNotExist()
        compose.runOnIdle {
            reasoningState.value = reasoningState.value.copy(reasoning = "next token")
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("next token").assertDoesNotExist()

        // Reopen it intentionally, then finish reasoning before the whole message ends. The
        // real finishedAt transition must still honor autoCloseThinking.
        target.performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("next token").assertExists()
        compose.runOnIdle {
            reasoningState.value = reasoningState.value.copy(finishedAt = Clock.System.now())
        }
        compose.mainClock.advanceTimeBy(600)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText("next token").assertDoesNotExist()
    }

    @Test
    fun framedThinkingStepInMixedBlockWrapsLabelAndKeepsTouchTarget() {
        var expectedTargetPx = 0
        val expanded = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            expectedTargetPx = with(LocalDensity.current) { 48.dp.roundToPx() }
            MaterialTheme {
                ChainOfThought(
                    // This mirrors a ThinkingBlock that contains both reasoning and a tool
                    // step: the reasoning capsule must stay content-sized while collapsed.
                    steps = listOf("thinking", "tool"),
                    collapsedVisibleCount = 2,
                ) { value ->
                    if (value == "thinking") {
                        ControlledChainOfThoughtStep(
                            expanded = expanded.value,
                            onExpandedChange = { expanded.value = it },
                            label = { androidx.compose.material3.Text("思考了1秒 · 高") },
                            framed = true,
                            content = { androidx.compose.material3.Text("thinking details") },
                        )
                    } else {
                        ChainOfThoughtStep(
                            label = { androidx.compose.material3.Text("工具调用") },
                        )
                    }
                }
            }
        }

        val clickTargets = compose.onAllNodes(hasClickAction(), useUnmergedTree = true)
        val targetNode = clickTargets.fetchSemanticsNodes().single()
        val rootWidth = compose.onRoot().fetchSemanticsNode().size.width
        assertTrue("collapsed framed step should wrap its label", targetNode.size.width < rootWidth)
        assertTrue("touch target must remain at least 48dp", targetNode.size.height >= expectedTargetPx)
        clickTargets[0].performClick()
        compose.onNodeWithText("thinking details").assertExists()
    }
}
