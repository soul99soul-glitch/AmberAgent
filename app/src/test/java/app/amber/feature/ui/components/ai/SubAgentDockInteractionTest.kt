package app.amber.feature.ui.components.ai

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.amber.agent.R
import kotlin.uuid.Uuid
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
class SubAgentDockInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val conversation = Uuid.parse("11111111-1111-1111-1111-111111111111")

    @Test
    fun overviewShowsReadableStageResultsWithoutDumpingTheFullTranscript() {
        compose.setContent {
            SubAgentDockOverview(
                details = SubAgentDockDetails(
                    objective = "Find why requests are repeated",
                    stages = listOf(
                        SubAgentDockStage(
                            kind = SubAgentDockStageKind.TEXT,
                            title = "Inspect request log",
                            text = "Two retry handlers process the same request.",
                        ),
                        SubAgentDockStage(
                            kind = SubAgentDockStageKind.TOOL,
                            title = "functions.exec_command",
                            text = "Raw tool arguments and diagnostics",
                        ),
                    ),
                    output = "A much longer full transcript belongs in the output tab.",
                    available = true,
                ),
                modifier = Modifier.height(500.dp),
            )
        }
        compose.onNodeWithText("Inspect request log").assertIsDisplayed()
        compose.onNodeWithText("Two retry handlers process the same request.").assertIsDisplayed()
        compose.onNodeWithText("Raw tool arguments and diagnostics").assertDoesNotExist()
        compose.onNodeWithText("A much longer full transcript belongs in the output tab.").assertDoesNotExist()
    }

    @Test
    fun hideAllButtonDoesNotExpandTheRail() {
        var hidden = false
        var expanded = false
        compose.setContent {
            SubAgentDockRail(
                listOf(task(0)), conversation, 200, false,
                onToggle = { expanded = true }, onSelect = {}, onHideAll = { hidden = true },
            )
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.onNodeWithContentDescription(app.getString(R.string.subagent_dock_dismiss_all)).performClick()
        assertTrue(hidden)
        org.junit.Assert.assertFalse(expanded)
    }

    @Test
    fun lastTaskRemainsDuringExitThenLeavesTheComposition() {
        compose.mainClock.autoAdvance = false
        var tasks by mutableStateOf(listOf(task(0)))
        compose.setContent {
            SubAgentDockRail(tasks, conversation, 200, false, onToggle = {}, onSelect = {})
        }
        compose.onNodeWithText("Dock task 0").assertIsDisplayed()
        compose.runOnIdle { tasks = emptyList() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("Dock task 0").assertExists()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Dock task 0").assertDoesNotExist()
    }

    @Test
    fun expansionInterpolatesHeightAndTheGridRemainsInteractive() {
        compose.mainClock.autoAdvance = false
        var expanded by mutableStateOf(false)
        var selected: SubAgentDockRunKey? = null
        val tasks = List(4, ::task)
        compose.setContent {
            SubAgentDockRail(
                tasks, conversation, 200, expanded,
                modifier = Modifier.testTag("dock"),
                onToggle = { expanded = !expanded },
                onSelect = { selected = it.key },
            )
        }
        val dock = compose.onNodeWithTag("dock")
        val initial = dock.fetchSemanticsNode().boundsInRoot.height
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.onNodeWithContentDescription(app.getString(R.string.subagent_dock_header_running, 0)).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        val middle = dock.fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.advanceTimeBy(400)
        val finished = dock.fetchSemanticsNode().boundsInRoot.height
        assertTrue("height must grow through intermediate values", middle > initial && middle < finished)
        compose.onNodeWithText("Dock task 3").assertIsDisplayed().performClick()
        assertEquals(tasks[3].key, selected)
    }

    @Test
    fun verticalSwipesExpandAndCollapseButNeverHideAll() {
        var expanded by mutableStateOf(false)
        var selected: SubAgentDockRunKey? = null
        var hidden = false
        compose.setContent {
            SubAgentDockRail(
                List(4, ::task), conversation, 200, expanded,
                onToggle = { expanded = !expanded }, onSelect = { selected = it.key }, onHideAll = { hidden = true },
            )
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val strip = compose.onNodeWithContentDescription(app.getString(R.string.subagent_dock_swipe_hint))
        strip.performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(expanded) }
        compose.onNodeWithText("Dock task 3").assertIsDisplayed()
        strip.performTouchInput { swipeDown() }
        compose.runOnIdle { org.junit.Assert.assertFalse(expanded) }
        strip.performTouchInput { swipeUp() }
        compose.onNodeWithTag("subagentDockGrid").performTouchInput { swipeDown() }
        compose.runOnIdle { org.junit.Assert.assertFalse(expanded) }
        strip.performTouchInput { swipeDown() }
        compose.runOnIdle {
            org.junit.Assert.assertFalse(expanded)
            org.junit.Assert.assertFalse(hidden)
            org.junit.Assert.assertNull(selected)
        }
        compose.onNodeWithText(app.getString(R.string.subagent_dock_header_running, 0)).assertDoesNotExist()
    }

    @Test
    fun horizontalSwipeStillScrollsTasksWithoutExpandingOrOpeningOne() {
        var expanded = false
        var selected: SubAgentDockRunKey? = null
        compose.setContent {
            SubAgentDockRail(
                List(4, ::task), conversation, 200, false,
                onToggle = { expanded = true }, onSelect = { selected = it.key },
            )
        }
        val strip = compose.onNodeWithTag("subagentDockPills")
        val viewport = strip.fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertEquals("track must reach the screen edges", root.left, viewport.left, 1f)
        assertEquals("no fixed close-button gutter", root.right, viewport.right, 1f)
        strip.performTouchInput { swipeLeft() }
        compose.runOnIdle {
            org.junit.Assert.assertFalse(expanded)
            org.junit.Assert.assertNull(selected)
        }
        assertTrue(strip.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.onNodeWithContentDescription(app.getString(R.string.subagent_dock_header_running, 0)).assertIsNotDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.subagent_dock_dismiss_all)).assertIsNotDisplayed()
    }

    private fun task(index: Int) = SubAgentDockTask(
        key = SubAgentDockRunKey("task-$index", 100),
        title = "Dock task $index",
        sourceConversationId = conversation,
        status = SubAgentDockStatus.COMPLETED,
        startedAtMs = 100,
        finishedAtMs = 200,
    )
}
