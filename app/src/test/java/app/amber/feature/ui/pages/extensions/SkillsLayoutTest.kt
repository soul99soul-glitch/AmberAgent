package app.amber.feature.ui.pages.extensions

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
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
@Config(sdk = [34], application = Application::class, qualifiers = "ru-w320dp-h600dp")
class SkillsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun translatedStatusPillsRemainHorizontallyReachableOnNarrowScreens() {
        compose.setContent {
            CompositionLocalProvider(
                LocalAmberTokens provides buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A)),
            ) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        SkillLibraryStatusCard(
                            installedCount = 12,
                            enabledCount = 8,
                            disabledCount = 4,
                            issueCount = 0,
                            onAdd = {},
                            onImport = {},
                            onRefresh = {},
                            onOptimizeAll = {},
                        )
                    }
                }
            }
        }
        val node = compose.onNodeWithTag("skills-status-pills", useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue(node.config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f)
        compose.onNodeWithTag("skills-status-pills", useUnmergedTree = true).performTouchInput { swipeLeft() }
        assertTrue(compose.onNodeWithTag("skills-status-pills", useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
    }
}
