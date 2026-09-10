package app.amber.feature.ui.pages.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
@Config(sdk = [34], application = Application::class, qualifiers = "en")
class ChatConfigurationHintTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun narrowScreenWithLargeTextKeepsReasonAndRepairActionSeparate() {
        var repairs = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(280.dp)) {
                        ChatConfigurationHint(ChatConfigurationIssue.MissingKey) { repairs++ }
                    }
                }
            }
        }
        val reason = compose.onNodeWithText("Add an API key to this provider").assertIsDisplayed()
        val action = compose.onNodeWithText("Configure").assertIsDisplayed()
        val reasonBounds = reason.fetchSemanticsNode().boundsInRoot
        val actionBounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue("The explanation must not overlap the repair action", reasonBounds.right <= actionBounds.left)
        action.performClick()
        assertEquals(1, repairs)
    }

    @Test
    fun missingModelOffersModelSelection() {
        var selections = 0
        compose.setContent {
            MaterialTheme { ChatConfigurationHint(ChatConfigurationIssue.MissingModel) { selections++ } }
        }
        compose.onNodeWithText("Choose model").assertIsDisplayed().performClick()
        assertEquals(1, selections)
    }
}
