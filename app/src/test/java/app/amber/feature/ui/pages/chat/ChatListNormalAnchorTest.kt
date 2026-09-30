package app.amber.feature.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.amber.agent.R
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.message.ChatMessageReasoningStep
import app.amber.feature.ui.components.ui.ChainOfThought
import app.amber.feature.ui.context.LocalReasoningScrollAnchor
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.rememberReasoningScrollAnchor
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
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
class ChatListNormalAnchorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun reverseTimelineKeepsTheActualReasoningHeaderInPlaceOnBothToggles() {
        val finished = Clock.System.now()
        val thought = UIMessagePart.Reasoning(
            reasoning = "A long completed thought. ".repeat(150),
            createdAt = finished - 4.seconds,
            finishedAt = finished,
        )
        compose.setContent {
            val list = rememberLazyListState()
            CompositionLocalProvider(
                LocalSettings provides Settings(),
                LocalReasoningScrollAnchor provides rememberReasoningScrollAnchor(list),
            ) {
                MaterialTheme {
                    LazyColumn(state = list, reverseLayout = true, modifier = Modifier.size(320.dp, 600.dp)) {
                        // This can be the newest assistant message too: the fix must not exclude it.
                        item(key = "assistant") {
                            ChainOfThought(steps = listOf(thought)) {
                                ChatMessageReasoningStep(it, model = null, regexes = emptyList(), loading = false)
                            }
                        }
                        repeat(16) { index ->
                            item(key = "older-$index") {
                                Box(Modifier.fillMaxWidth().height(56.dp)) { Text("older-$index") }
                            }
                        }
                    }
                }
            }
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val label = app.getString(R.string.deep_thinking_seconds, 4)
        val header = compose.onNodeWithText(label, useUnmergedTree = true)
        val before = header.fetchSemanticsNode().boundsInRoot.top
        compose.onNode(hasClickAction(), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("expand must preserve the title", before, header.fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.onNode(hasClickAction(), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("collapse must preserve the title", before, header.fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    private lateinit var tailList: LazyListState
    private var tailBodyHeight by mutableStateOf(0.dp)

    /** Production shape: TimelineTail at index 0, the streaming message as one growing item above it. */
    private fun setStreamingTailTimeline(initialBodyHeight: Dp) {
        tailBodyHeight = initialBodyHeight
        compose.setContent {
            tailList = rememberLazyListState()
            val followBottomPx = with(compose.density) { 24.dp.roundToPx() }
            LazyColumn(state = tailList, reverseLayout = true, modifier = Modifier.size(320.dp, 600.dp)) {
                item(key = "timeline-tail") { Box(Modifier.fillMaxWidth().height(40.dp)) }
                item(key = "streaming") {
                    Column(Modifier.holdReadingOnTailGrowth(tailList, lazyIndex = 1, followBottomPx = followBottomPx)) {
                        Text("streaming-head", Modifier.height(40.dp))
                        Spacer(Modifier.fillMaxWidth().height(tailBodyHeight))
                    }
                }
                repeat(8) { index ->
                    item(key = "older-$index") {
                        Box(Modifier.fillMaxWidth().height(120.dp)) { Text("older-$index") }
                    }
                }
            }
        }
    }

    private fun streamingHeadTop() =
        compose.onNodeWithText("streaming-head").fetchSemanticsNode().boundsInRoot.top

    @Test
    fun nearBottomStreamingGrowthKeepsFollowingTheTail() {
        setStreamingTailTimeline(initialBodyHeight = 200.dp)
        val before = streamingHeadTop()
        compose.runOnIdle { tailBodyHeight = 260.dp }
        compose.waitForIdle()
        val growthPx = with(compose.density) { 60.dp.toPx() }
        assertEquals("pinned bottom follows the growth", before - growthPx, streamingHeadTop(), 2f)
        assertEquals(0, tailList.firstVisibleItemIndex)
        assertEquals(0, tailList.firstVisibleItemScrollOffset)
    }

    @Test
    fun scrolledUpInsideStreamingMessageKeepsReadContentWithoutCancellingTheDrag() {
        setStreamingTailTimeline(initialBodyHeight = 800.dp)
        // Reading the head of the growing message: it is still the bottom-most visible item.
        compose.runOnIdle { tailList.requestScrollToItem(1, with(compose.density) { 400.dp.roundToPx() }) }
        compose.waitForIdle()
        assertEquals(1, tailList.firstVisibleItemIndex)
        compose.onNodeWithText("streaming-head").performTouchInput {
            down(center)
            moveBy(Offset(0f, 80f))
            moveBy(Offset(0f, 40f))
        }
        compose.waitForIdle()
        assertTrue("drag is in progress", tailList.isScrollInProgress)
        val before = streamingHeadTop()
        compose.runOnIdle { tailBodyHeight = 860.dp }
        compose.waitForIdle()
        assertEquals("read content stays put while the tail grows", before, streamingHeadTop(), 2f)
        assertTrue("growth hold must not cancel the user's drag", tailList.isScrollInProgress)
        compose.onNodeWithText("streaming-head").performTouchInput { up() }
        compose.runOnIdle { tailList.requestScrollToItem(0) }
        compose.waitForIdle()
        compose.runOnIdle { tailBodyHeight = 920.dp }
        compose.waitForIdle()
        assertEquals("returning to bottom resumes native following", 0, tailList.firstVisibleItemIndex)
        assertEquals(0, tailList.firstVisibleItemScrollOffset)
    }
}
