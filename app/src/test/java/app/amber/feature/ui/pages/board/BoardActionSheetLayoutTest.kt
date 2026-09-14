package app.amber.feature.ui.pages.board

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import app.amber.agent.R
import app.amber.feature.board.hotlist.HotTopic
import app.amber.feature.board.hotlist.HotTopicSource
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w320dp-h480dp")
class BoardActionSheetLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longTopicSheetKeepsActionsReachableThroughScroll() {
        val context = RuntimeEnvironment.getApplication()
        val topic = HotTopic(
            id = "long-topic",
            title = "超长热点标题".repeat(40),
            sources = (1..12).map { rank ->
                HotTopicSource(
                    providerId = "provider-$rank",
                    providerName = "provider-name-$rank".repeat(3),
                    rank = rank,
                    title = "source-title-$rank",
                )
            },
            sourceCount = 12,
            bestRank = 1,
            latestFetchedAt = 1L,
        )

        compose.setContent {
            CompositionLocalProvider(
                LocalAmberTokens provides buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A)),
            ) {
                MaterialTheme {
                    HotListActionSheet(
                        topic = topic,
                        onDismiss = {},
                        onDeepRead = {},
                        onRegenerate = {},
                        onOpenOriginal = {},
                        onShare = {},
                    )
                }
            }
        }

        val scrollable = compose.onNode(hasScrollAction(), useUnmergedTree = true)
        scrollable.performScrollToNode(hasText(context.getString(R.string.share)))
        compose.onNodeWithText(context.getString(R.string.share), useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
