package app.amber.feature.ui.pages.setting

import android.app.Application
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.amber.agent.R
import app.amber.ai.provider.ProviderSetting
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
class ProviderFileImportPreviewTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun previewSkipsMatchingNamesAndOnlyConfirmsSelectedEntries() {
        val providers = listOf(
            ProviderSetting.OpenAI(name = "Existing", apiKey = "secret-not-for-preview"),
            ProviderSetting.OpenAI(name = "New"),
        )
        var imported = emptyList<ProviderSetting>()
        compose.setContent {
            ProviderFileImportPreview(
                providers = providers,
                existingProviders = listOf(ProviderSetting.OpenAI(name = " existing ")),
                saving = false,
                onDismiss = {},
                onConfirm = { imported = it },
            )
        }
        compose.onNodeWithText("Existing").assertIsOff()
        compose.onNodeWithText("New").assertIsOn()
        compose.onNodeWithText("secret-not-for-preview").assertIsNotDisplayed()
        val context = ApplicationProvider.getApplicationContext<Application>()
        compose.onNodeWithText(context.getString(R.string.provider_file_import_confirm, 1)).performClick()
        assertEquals(listOf("New"), imported.map { it.name })
    }
}
