package app.amber.feature.ui.pages.councilroom

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.amber.feature.modelcouncil.CouncilMessage
import app.amber.feature.modelcouncil.CouncilMessageStatus
import app.amber.feature.modelcouncil.CouncilRoomMode
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.pages.chat.toChatTheme
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w320dp-h812dp")
class CouncilTimelineLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longAuthorNameIsEllipsizedInsideTheIdentityTag() {
        val longName = "member-name-that-cannot-fit-in-a-phone-width"
        val tokens = buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A))
        val message = CouncilMessage(
            id = "message-1",
            authorId = "member-1",
            authorName = longName,
            role = "reviewer",
            round = 1,
            mode = CouncilRoomMode.EXPLORE,
            text = "",
            createdAtMs = 1L,
            status = CouncilMessageStatus.COMPLETED,
        )
        compose.setContent {
            CompositionLocalProvider(
                LocalAmberTokens provides tokens,
                LocalChatTheme provides tokens.toChatTheme(),
            ) {
                MaterialTheme {
                    Box(Modifier.width(220.dp)) {
                        CouncilMemberIdentityHeader(
                            msg = message,
                            isHost = false,
                            modelLabel = "very-long-provider-model-name",
                        )
                    }
                }
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        val action = compose.onNodeWithText(longName, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
        assertTrue(action?.invoke(layouts) == true)
        assertTrue("long author name must be ellipsized", layouts.single().isLineEllipsized(0))
        compose.onNodeWithText("reviewer", useUnmergedTree = true).assertIsDisplayed()
    }
}
