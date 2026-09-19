package app.amber.feature.ui.pages.setting

import android.app.Application
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import app.amber.core.memory.model.MemoryCandidate
import app.amber.core.model.MemoryKind
import app.amber.core.model.MemoryScope
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
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w373dp-h812dp")
class SettingAgentMemoryLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun memoryCandidatesStayLazyAndScrollToOffscreenRows() {
        val candidates = (0 until 120).map { index ->
            MemoryCandidate(
                id = "candidate-$index",
                content = "candidate-row-$index",
                scope = MemoryScope.LONG_TERM,
                kind = MemoryKind.NOTE,
            )
        }

        compose.setContent {
            CompositionLocalProvider(
                LocalAmberTokens provides buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A)),
            ) {
                MaterialTheme {
                    LazyColumn(Modifier.fillMaxSize()) {
                        memoryCandidatesSection(
                            candidates = candidates,
                            onAccept = {},
                            onIgnore = {},
                            onIgnoreLowConfidence = {},
                        )
                    }
                }
            }
        }

        assertTrue(
            compose.onAllNodesWithText("candidate-row-119", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )

        val scrollable = compose.onNode(hasScrollAction(), useUnmergedTree = true)
        scrollable.performScrollToNode(hasText("candidate-row-119"))
        compose.onNodeWithText("candidate-row-119", useUnmergedTree = true).assertIsDisplayed()
    }
}
