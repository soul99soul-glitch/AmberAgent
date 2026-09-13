package app.amber.feature.ui.pages.setting

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
@Config(sdk = [34], application = Application::class)
class ExperimentActionButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun actionLabelIsVerticallyCenteredWith48DpTouchTarget() {
        val accent = Color(0xFFB8623A)
        var touchHeightPx = 0
        var clicks = 0
        compose.setContent {
            with(LocalDensity.current) {
                touchHeightPx = 48.dp.roundToPx()
            }
            MaterialTheme(colorScheme = lightColorScheme(primary = accent)) {
                ExperimentActionButton("登录", enabled = true, primary = true) { clicks++ }
            }
        }

        val target = compose.onNode(hasClickAction(), useUnmergedTree = true)
        val bounds = target.fetchSemanticsNode().boundsInRoot
        val textBounds = compose.onNodeWithText("登录", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.center.y, textBounds.center.y, 1f)
        assertTrue(bounds.height >= touchHeightPx)

        target.performClick()
        assertEquals(1, clicks)
    }
}
