package app.amber.feature.ui.components.ds

import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.amber.feature.ui.components.ui.CardGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Proves the Robolectric + Compose UI-test pipeline runs on the JVM (no device). Once green, the
 * real interaction tests (composer +→capsule, model menu open/select, context meter) build on this.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class)
class GraphiteComposePipelineTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun composePipelineRendersOnJvm() {
        compose.setContent { Text("graphite-pipeline-ok") }
        compose.onNodeWithText("graphite-pipeline-ok").assertIsDisplayed()
    }

    @Test
    fun cardGroupKeepsTrailingControlSeparateFromRowAction() {
        val enabled = mutableStateOf(false)
        var rowClicks = 0
        var switchChanges = 0
        compose.setContent {
            CardGroup {
                item(
                    onClick = { rowClicks++ },
                    trailingContent = {
                        Switch(
                            checked = enabled.value,
                            onCheckedChange = { enabled.value = it; switchChanges++ },
                            modifier = Modifier.testTag("trailing-switch"),
                        )
                    },
                ) { Text("Notifications") }
                rawItem { Text("Custom content") }
            }
        }
        compose.onNodeWithTag("trailing-switch").performClick()
        compose.runOnIdle {
            assertTrue(enabled.value)
            assertEquals(1, switchChanges)
            assertEquals(0, rowClicks)
        }
        compose.onNodeWithText("Notifications").performClick()
        compose.onNodeWithText("Custom content").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, rowClicks) }
    }
}
