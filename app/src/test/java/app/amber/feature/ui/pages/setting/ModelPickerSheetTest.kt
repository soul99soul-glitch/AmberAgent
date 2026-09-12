package app.amber.feature.ui.pages.setting

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.agent.R
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
class ModelPickerSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun confirmAppliesBatchSelectionOnceWithCompleteFinalModels() {
        val selected = listOf(model("keep"), model("remove"))
        val available = selected + listOf(model("add-a"), model("add-b"))
        var callbackCount = 0
        var applied = emptyList<Model>()
        val context = ApplicationProvider.getApplicationContext<Application>()

        compose.setContent {
            ModelPickerSheet(
                models = available,
                selectedModels = selected,
                parentProvider = ProviderSetting.OpenAI(),
                onApplyModels = {
                    callbackCount += 1
                    applied = it
                },
                onCreateBlank = {},
                onDismiss = {},
            )
        }

        compose.onNodeWithText("remove").performClick()
        compose.onNodeWithText("add-a").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        compose.onNodeWithText("add-b").performClick()
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()

        assertEquals(1, callbackCount)
        assertEquals(listOf("keep", "add-a", "add-b"), applied.map { it.modelId })
    }

    private fun model(modelId: String) = Model(
        modelId = modelId,
        displayName = modelId,
    )
}
