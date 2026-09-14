package app.amber.feature.ui.pages.backup

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import org.junit.Assert.assertFalse
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
class BackupLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longWebDavLabelWrapsWithinTheOriginalLabelSlot() {
        compose.setContent {
            CompositionLocalProvider(
                LocalAmberTokens provides buildAmberTokens(AmberBase.LIGHT, Color(0xFFB8623A)),
            ) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        BackupCompactField(
                            label = "Каталог резервных копий",
                            value = "",
                            onValueChange = {},
                        )
                    }
                }
            }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val node = compose.onNodeWithTag("backup-compact-field-label", useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
        assertFalse(layouts.single().hasVisualOverflow)
    }
}
