package app.amber.feature.ui.pages.setting

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.amber.agent.R
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
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
class ProviderModelRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun swipeActionsStayHiddenUntilRevealedAndDoNotInterceptForegroundActions() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val cancel = context.getString(R.string.cancel)
        val delete = context.getString(R.string.chat_page_delete)
        val setCurrent = context.getString(R.string.setting_provider_page_model_set_current)
        var currentChanges = 0
        var editorOpens = 0
        var deletions = 0

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        ProviderModelRow(
                            model = Model(modelId = "qwen3.8-flash", type = ModelType.CHAT),
                            modifier = Modifier.testTag("model-row"),
                            isCurrent = false,
                            onSetCurrent = { currentChanges += 1 },
                            onDelete = { deletions += 1 },
                            onOpenEditor = { editorOpens += 1 },
                        )
                    }
                }
            }
        }

        compose.onNodeWithContentDescription(cancel).assertDoesNotExist()
        compose.onNodeWithContentDescription(delete).assertDoesNotExist()
        compose.onNodeWithText(setCurrent).performClick()
        compose.onNodeWithText("qwen3.8-flash").performClick()
        assertEquals(1, currentChanges)
        assertEquals(1, editorOpens)
        assertEquals(0, deletions)

        compose.onNodeWithTag("model-row").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription(delete).assertIsDisplayed()
        assertEquals(0, deletions)
        compose.onNodeWithContentDescription(cancel).performClick()
        compose.onNodeWithContentDescription(delete).assertDoesNotExist()
        compose.onNodeWithText(setCurrent).assertIsDisplayed()

        compose.onNodeWithTag("model-row").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription(delete).performClick()
        assertEquals(1, deletions)
        compose.onNodeWithContentDescription(cancel).assertDoesNotExist()
        compose.onNodeWithContentDescription(delete).assertDoesNotExist()
    }
}
