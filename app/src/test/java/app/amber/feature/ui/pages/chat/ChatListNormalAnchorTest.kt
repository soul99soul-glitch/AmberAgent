package app.amber.feature.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}
