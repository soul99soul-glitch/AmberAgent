package app.amber.feature.ui.pages.setting

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import app.amber.agent.R
import app.amber.agent.data.files.CasTestFixtures
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.ui.pages.setting.SettingVM
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class SettingModelPagePromptTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var testRoot: File
    private lateinit var settingsStore: SettingsAggregator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        testRoot = File(context.cacheDir, "setting-model-prompt-${System.nanoTime()}").apply { mkdirs() }
        settingsStore = CasTestFixtures.settingsAggregator(context, testRoot)
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun auxiliaryTitlePromptCancelAndSaveUseDurableSettings() {
        val vm = SettingVM(settingsStore)
        val parameters = RuntimeEnvironment.getApplication()
            .getString(R.string.setting_model_page_parameters)
        val cancel = RuntimeEnvironment.getApplication().getString(R.string.cancel)
        val save = RuntimeEnvironment.getApplication().getString(R.string.common_save)
        val cancelPrompt = "ui-title-cancel-prompt"
        val savedPrompt = "ui-title-saved-prompt"

        compose.setContent {
            val settings by settingsStore.settingsFlow.collectAsState(
                initial = Settings.dummy(),
            )
            CompositionLocalProvider(
                LocalAmberTokens provides buildAmberTokens(
                    AmberBase.LIGHT,
                    Color(0xFFB8623A),
                ),
            ) {
                MaterialTheme {
                    DefaultTitleModelSetting(settings = settings, vm = vm)
                }
            }
        }

        compose.waitUntil(5_000) { !settingsStore.settingsFlow.value.init }
        val originalPrompt = settingsStore.settingsFlow.value.titlePrompt

        compose.onNodeWithText(parameters).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(cancelPrompt)
        compose.onNodeWithText(cancel).performClick()
        compose.waitForIdle()
        runBlocking { delay(250) }
        assertEquals(originalPrompt, settingsStore.settingsFlow.value.titlePrompt)

        compose.onNodeWithText(parameters).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(savedPrompt)
        compose.onNodeWithText(save).performClick()
        compose.waitUntil(5_000) {
            settingsStore.settingsFlow.value.titlePrompt == savedPrompt
        }
        assertEquals(savedPrompt, settingsStore.settingsFlow.value.titlePrompt)
    }
}
