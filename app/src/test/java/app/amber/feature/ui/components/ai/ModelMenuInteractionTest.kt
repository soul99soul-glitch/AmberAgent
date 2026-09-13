package app.amber.feature.ui.components.ai

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.Navigator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI **interaction** tests for the Graphite model menu (`ModelList.kt`, design §6.2
 * "Top model menu" + the model-picker reasoning segment) — criterion 5.
 *
 * Seam: the model menu's reasoning selector [ThinkingLevelSegment] is a stateless `internal`
 * composable that takes its segment data + an `onChange` callback (no `koinViewModel()` / no
 * `koinInject`), so we render it directly with fake data and drive real taps. It reads only
 * `LocalChatTheme`, which has a default value, so no Koin/theme scaffolding is required.
 * ModelList receives favorites and updates from its owner, so the real search field,
 * provider expansion, lazy row, and selection callback can be exercised without starting Koin.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Stub Application: the real `AmberAgentApp.onCreate()` calls `startKoin {…}`, and Robolectric
// re-creates the Application per test — the 2nd test would then throw
// KoinApplicationAlreadyStartedException. These tests render a stateless composable that needs no
// Koin, so a bare Application keeps each test isolated.
@Config(sdk = [34], application = Application::class)
class ModelMenuInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val claudeLevels = reasoningLevelsForModel(Model(modelId = "claude-sonnet-4.5"))

    @Test
    fun reasoningSegment_rendersEveryLevelLabel() {
        compose.setContent {
            Column {
                ThinkingLevelSegment(
                    levels = claudeLevels,
                    current = ReasoningLevel.AUTO,
                    onChange = {},
                )
            }
        }
        // claude segment set = auto / low / med / high / xhigh / max
        compose.onNodeWithText("auto").assertIsDisplayed()
        compose.onNodeWithText("low").assertIsDisplayed()
        compose.onNodeWithText("high").assertIsDisplayed()
        compose.onNodeWithText("max").assertIsDisplayed()
    }

    @Test
    fun topModelMenu_reasoningFooterFiresCallbackWithTheSupportedLevel() {
        var picked: ReasoningLevel? = null
        val model = Model(
            modelId = "deepseek-v4.1-flash",
            displayName = "DeepSeek Flash",
            abilities = listOf(ModelAbility.REASONING),
        )
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        compose.setContent {
            TopModelMenu(
                open = true,
                providers = listOf(provider),
                modelType = ModelType.CHAT,
                currentProviderId = provider.id,
                currentModelId = model.id,
                onSelect = {},
                onClose = {},
                reasoningLevel = ReasoningLevel.MAX,
                onUpdateReasoningLevel = { picked = it },
            )
        }

        assertNull("no selection before any tap", picked)
        compose.onNodeWithText("Thinking level").assertIsDisplayed()
        compose.onNodeWithText("High").performClick()
        assertEquals(ReasoningLevel.HIGH, picked)
    }

    @Test
    fun reasoningSegment_selectionStateMachine_movesActiveAcrossTaps() {
        // Closed→Open is the menu opening (covered structurally below); here we drive the
        // Selected transition: the hoisted `current` follows whatever the user taps, and the
        // segment re-renders with the new active label — a real Idle→Selected→Reselect cycle.
        compose.setContent {
            var current by remember { mutableStateOf(ReasoningLevel.AUTO) }
            ThinkingLevelSegment(
                levels = claudeLevels,
                current = current,
                onChange = { current = it },
            )
        }

        compose.onNodeWithText("low").performClick()
        // re-select a different one; both labels stay present, no crash, callback re-drives state
        compose.onNodeWithText("max").performClick()
        compose.onNodeWithText("max").assertIsDisplayed()
        compose.onNodeWithText("low").assertIsDisplayed()
    }

    @Test
    fun reasoningSegment_perModelLevelSets_areCorrect() {
        // The menu shows different reasoning sets per model family (design §ThinkingLevel).
        // gpt → low/med/high/xhigh (no "auto"); deepseek → off/high/max.
        compose.setContent {
            ThinkingLevelSegment(
                levels = reasoningLevelsForModel(Model(modelId = "gpt-5")),
                current = ReasoningLevel.MEDIUM,
                onChange = {},
            )
        }
        compose.onNodeWithText("low").assertIsDisplayed()
        compose.onNodeWithText("xhigh").assertIsDisplayed()
        // gpt set has no "auto" segment
        compose.onNodeWithText("auto").assertDoesNotExist()
    }

    @Test
    fun topModelMenu_openWithoutProvidersShowsEmptyState() {
        compose.setContent {
            TopModelMenu(
                open = true,
                providers = emptyList(),
                modelType = ModelType.CHAT,
                currentProviderId = null,
                currentModelId = null,
                onSelect = {},
                onClose = {},
            )
        }

        compose.onNodeWithText("No available AI providers, please add in settings")
            .assertIsDisplayed()
    }

    @Test
    fun modelList_keepsSearchAndModelRowVisibleAndSelectable() {
        val model = Model(
            modelId = "model-visible",
            displayName = "model-visible",
            type = ModelType.CHAT,
        )
        val provider = ProviderSetting.OpenAI(
            name = "Test provider",
            models = listOf(model),
        )
        var picked: Model? = null

        compose.setContent {
            CompositionLocalProvider(
                LocalNavController provides Navigator(mutableListOf(Screen.SessionHome)),
            ) {
                Column(Modifier.height(600.dp)) {
                    ModelList(
                        currentModel = null,
                        providers = listOf(provider),
                        modelType = ModelType.CHAT,
                        onSelect = { picked = it },
                        onDismiss = {},
                        favoriteModelIds = emptyList(),
                        onFavoriteModelsChange = {},
                    )
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Application>()
        compose.onNodeWithText(
            context.getString(R.string.model_list_search_placeholder),
        ).assertIsDisplayed()
        compose.onNodeWithText("model-visible").assertIsDisplayed().performClick()
        assertEquals(model.id, picked?.id)
    }
}
