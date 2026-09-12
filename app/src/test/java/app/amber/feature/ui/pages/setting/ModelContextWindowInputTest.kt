package app.amber.feature.ui.pages.setting

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import app.amber.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ModelContextWindowInputTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun typingOneMillionPreservesDigitsAndUpdatesModel() {
        val model = mutableStateOf(Model(modelId = "test-model", contextWindowTokens = 1))
        compose.setContent {
            ModelSettingsForm(
                model = model.value,
                onModelChange = { model.value = it },
                isEdit = true,
            )
        }

        var input = "1"
        repeat(6) {
            val field = compose.onNode(hasSetTextAction() and hasText(input))
            field.performTextInputSelection(TextRange(input.length))
            field.performTextInput("0")
            input += "0"
            compose.onNode(hasSetTextAction() and hasText(input)).assertTextEquals(input)
            compose.runOnIdle {
                assertEquals(input.toInt(), model.value.contextWindowTokens)
            }
        }
    }
}
