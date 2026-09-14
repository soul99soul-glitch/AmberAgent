package app.amber.feature.ui.pages.setting

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.baseTokens
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
class SettingDisplayPageLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fontFamilyChoiceAllowsLargeFontLabelToGrowWithoutVerticalClipping() {
        compose.setContent {
            CompositionLocalProvider(
                LocalAmberTokens provides baseTokens(AmberBase.LIGHT),
                LocalDensity provides Density(1f, fontScale = 2f),
            ) {
                MaterialTheme {
                    WorkspaceSegmentedChoice(
                        options = listOf("默认", "衬线体", "Моноширинный"),
                        selected = "默认",
                        modifier = Modifier.width(280.dp),
                        onSelected = {},
                        label = { Text(it, onTextLayout = {}) },
                    )
                }
            }
        }

        val longLabel = compose.onNodeWithText("Моноширинный")
        longLabel.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(
            "Text must expose its layout result",
            longLabel.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
                .action
                ?.invoke(layouts) == true,
        )
        assertTrue(layouts.isNotEmpty())
        assertTrue("large-font label must not be vertically clipped", layouts.single().didOverflowHeight.not())
    }
}
