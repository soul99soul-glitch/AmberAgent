package app.amber.feature.ui.components.richtext

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.feature.ui.components.message.ChatMessageReasoningStep
import app.amber.feature.ui.components.ui.ChainOfThought
import app.amber.feature.ui.context.LocalSettings
import kotlin.time.Clock
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
class StreamingPlainTextComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun streamingPastTheWindowLimitStillRevealsTheTailAndFinishesOpaque() {
        val line = "abcdefghijklmnopqrstuvwxyz\n"
        var text by mutableStateOf(line.repeat(72) + "READING_ANCHOR\n") // Start below the old switch.
        var streaming by mutableStateOf(true)
        var charLimit by mutableStateOf(2000)
        lateinit var scroll: ScrollState
        compose.mainClock.autoAdvance = false
        compose.setContent {
            scroll = rememberScrollState()
            Box(Modifier.width(280.dp).height(100.dp).verticalScroll(scroll)) {
                StreamingPlainText(
                    text = text,
                    streaming = streaming,
                    style = TextStyle(color = Color.Black, fontSize = 13.sp, lineHeight = 23.sp),
                    maxVisibleChars = charLimit,
                    omittedPrefixTemplate = "omitted %d",
                    scrollState = scroll,
                    modifier = Modifier.fillMaxWidth().testTag("thought"),
                )
            }
        }
        compose.mainClock.advanceTimeBy(1200)
        compose.runOnIdle { scroll.dispatchRawDelta(scroll.maxValue.toFloat()) }
        fun anchorY(): Float {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("thought").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val offset = layout.layoutInput.text.text.indexOf("READING_ANCHOR")
            return layout.getLineTop(layout.getLineForOffset(offset)) - scroll.value
        }
        val readingY = anchorY()
        compose.runOnIdle { text += line.repeat(12) + "TAIL" }
        compose.mainClock.advanceTimeBy(6000)
        val flowing = compose.onNodeWithTag("thought").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        assertEquals("trimming must preserve the retained reading line", readingY, anchorY(), 1f)
        assertTrue(flowing.text.startsWith("omitted "))
        assertTrue(flowing.text.endsWith("TAIL"))
        assertTrue("window retains whole rows within one row of its budget", flowing.length < 2100)
        compose.runOnIdle { scroll.dispatchRawDelta(scroll.maxValue.toFloat()) }
        val pinnedReadingY = anchorY()
        compose.runOnIdle { text += "123456789012345678901234567890" }
        compose.mainClock.advanceTimeBy(2500)
        assertEquals("pruning at max scroll must not clamp and compensate twice", pinnedReadingY, anchorY(), 1f)
        val finalText = compose.onNodeWithTag("thought").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text
        compose.runOnIdle { streaming = false }
        compose.mainClock.advanceTimeBy(1600)
        val settled = compose.onNodeWithTag("thought").fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        assertEquals(finalText, settled.text)
        assertTrue("no frozen transparent tail after loading ends", settled.spanStyles.isEmpty())
        val beforeExpansion = anchorY()
        compose.runOnIdle { charLimit = 18000 }
        compose.mainClock.advanceTimeBy(32)
        assertEquals("restoring completed history keeps the reading line", beforeExpansion, anchorY(), 1f)
    }

    @Test
    fun reasoningDragPausesFollowingUntilTheUserReturnsNearTheBottom() {
        val line = "abcdefghijklmnopqrstuvwxyz\n"
        var thought by mutableStateOf(UIMessagePart.Reasoning(
            reasoning = line.repeat(73), createdAt = Clock.System.now(), finishedAt = null,
        ))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings()) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        ChainOfThought(steps = listOf(thought)) {
                            ChatMessageReasoningStep(it, model = null, regexes = emptyList(), loading = true)
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(3500)
        val body = compose.onNode(hasScrollAction(), useUnmergedTree = true)
        fun scrollRange() = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("initial generation follows", scrollRange().maxValue() - scrollRange().value() < 2f)
        body.performTouchInput {
            down(center)
            moveBy(Offset(0f, 60f))
            moveBy(Offset(0f, 40f))
        }
        compose.mainClock.advanceTimeBy(100)
        val readingOffset = scrollRange().value()
        assertTrue("gesture has left the bottom", scrollRange().maxValue() - readingOffset > 24f)
        compose.runOnIdle { thought = thought.copy(reasoning = thought.reasoning + line.repeat(4)) }
        compose.mainClock.advanceTimeBy(2500)
        assertEquals("generation must not fight the held drag", readingOffset, scrollRange().value(), 1f)
        body.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1500)
        val releasedOffset = scrollRange().value()
        compose.runOnIdle { thought = thought.copy(reasoning = thought.reasoning + line.repeat(2)) }
        compose.mainClock.advanceTimeBy(2000)
        assertEquals("releasing away from bottom must not restart follow", releasedOffset, scrollRange().value(), 1f)
        // Place near the bottom, then use a real gesture through the production gate.
        val returnDistance = scrollRange().maxValue() - scrollRange().value() - 12f
        body.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, returnDistance) }
        compose.mainClock.advanceTimeBy(1000)
        body.performTouchInput { swipeUp(durationMillis = 700) }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle { thought = thought.copy(reasoning = thought.reasoning + line.repeat(2)) }
        compose.mainClock.advanceTimeBy(2000)
        assertTrue("near bottom resumes follow: remaining=${scrollRange().maxValue() - scrollRange().value()}", scrollRange().maxValue() - scrollRange().value() < 2f)
    }

}
