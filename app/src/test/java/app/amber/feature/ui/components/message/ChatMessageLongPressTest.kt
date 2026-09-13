package app.amber.feature.ui.components.message

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.amber.ai.ui.UIMessage
import app.amber.agent.R
import app.amber.core.model.MessageNode
import app.amber.core.settings.Settings
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.Navigator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ChatMessageLongPressTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun completedReplyOpensActionsByLongPressWithoutFooterIcons() {
        val text = "A completed assistant reply."
        val node = MessageNode.of(UIMessage.assistant(text))
        var regenerations = 0
        content {
            ChatMessage(
                node = node,
                onFork = {}, onRegenerate = { regenerations++ }, onEdit = {},
                onShare = {}, onDelete = {}, onQuote = {}, onUpdate = {},
            )
        }
        openMenuAndRegenerate(text)
        assertEquals(1, regenerations)
    }

    @Test
    fun virtualizedParagraphOpensTheFullMessageActions() {
        val heading = "Long answer"
        val node = MessageNode.of(UIMessage.assistant("# $heading\n\n" + "A longer paragraph. ".repeat(45)))
        val item = buildChatMessageVirtualItems(
            node = node, assistant = emptyList(), showAssistantBubble = false,
            loading = false, lastMessage = false,
        )!!.filterIsInstance<ChatMessageVirtualItem.MarkdownChild>().first()
        var regenerations = 0
        content {
            ChatMessageVirtualItemContent(
                node = node, item = item,
                onFork = {}, onRegenerate = { regenerations++ }, onEdit = {},
                onShare = {}, onDelete = {}, onQuote = {}, onUpdate = {},
            )
        }
        openMenuAndRegenerate(heading)
        assertEquals(1, regenerations)
    }

    private fun openMenuAndRegenerate(text: String) {
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithContentDescription(context.getString(R.string.copy)).assertDoesNotExist()
        compose.onNodeWithText(text).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.copy)).assertExists()
        compose.onNodeWithText(context.getString(R.string.regenerate)).performClick()
    }

    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings(),
                LocalNavController provides Navigator(mutableListOf()),
            ) {
                MaterialTheme(content = block)
            }
        }
    }
}
