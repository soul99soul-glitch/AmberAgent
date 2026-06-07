package app.amber.feature.ui.components.ds

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
@Config(sdk = [34])
class GraphiteComposePipelineTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun composePipelineRendersOnJvm() {
        compose.setContent { Text("graphite-pipeline-ok") }
        compose.onNodeWithText("graphite-pipeline-ok").assertIsDisplayed()
    }
}
