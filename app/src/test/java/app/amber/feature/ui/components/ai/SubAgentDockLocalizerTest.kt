package app.amber.feature.ui.components.ai

import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.core.settings.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SubAgentDockLocalizerTest {
    private val model = Model(modelId = "fake-localizer-model")
    private val provider = ProviderSetting.OpenAI(
        name = "fake-provider",
        models = listOf(model),
    )
    private val settings = Settings(
        providers = listOf(provider),
        titleModelId = model.id,
        chatModelId = model.id,
    )
    private val testJson = Json { ignoreUnknownKeys = true }
    private val details = SubAgentDockDetails(
        summary = "Investigate the SSH connection and report the current status.",
        stages = listOf(
            SubAgentDockStage(
                kind = SubAgentDockStageKind.TOOL,
                title = "Inspect host",
                text = "Check the current SSH service status.",
            )
        ),
        output = "raw output stays unchanged",
        previousOutput = "previous raw output",
        available = true,
    )

    @Test
    fun chineseEnglishSummaryNeedsLocalizationButMixedProductTextDoesNot() {
        assertTrue(needsChineseLocalization(details, Locale.SIMPLIFIED_CHINESE))
        assertFalse(
            needsChineseLocalization(
                details.copy(summary = "检查 GPT-5 当前配置", stages = emptyList()),
                Locale.SIMPLIFIED_CHINESE,
            )
        )
        assertFalse(needsChineseLocalization(details, Locale.ENGLISH))
        assertTrue(needsChineseLocalization(details.copy(summary = "Check the current status of 服务", stages = emptyList()), Locale.SIMPLIFIED_CHINESE))
        assertFalse(
            needsChineseLocalization(
                details.copy(summary = "SSH", stages = emptyList()),
                Locale.SIMPLIFIED_CHINESE,
            )
        )
    }

    @Test
    fun chineseTranslationKeepsRawOutputsAndUsesCache() = runBlocking {
        var calls = 0
        val localizer = SubAgentDockLocalizer(
            settingsSnapshot = { settings },
            json = testJson,
            translator = { _, _, _ ->
                calls += 1
                """
                {"items":[
                  {"id":"summary","text":"调查 SSH 连接并报告当前状态。"},
                  {"id":"stage:0:title","text":"检查主机"},
                  {"id":"stage:0:text","text":"检查当前 SSH 服务状态。"}
                ]}
                """.trimIndent()
            },
        )

        val first = localizer.localize(details, Locale.SIMPLIFIED_CHINESE).getOrThrow()
        val changedDetails = details.copy(
            output = "new raw output",
            previousOutput = "new previous raw output",
            stages = details.stages.map { it.copy(isRunning = true) },
        )
        val second = localizer.localize(changedDetails, Locale.SIMPLIFIED_CHINESE).getOrThrow()

        assertEquals(1, calls)
        assertEquals("调查 SSH 连接并报告当前状态。", first.summary)
        assertEquals("检查主机", first.stages.single().title)
        assertEquals("raw output stays unchanged", first.output)
        assertEquals("previous raw output", first.previousOutput)
        assertEquals("new raw output", second.output)
        assertEquals("new previous raw output", second.previousOutput)
        assertTrue(second.stages.single().isRunning)
    }

    @Test
    fun englishLocaleDoesNotCallTranslator() = runBlocking {
        var calls = 0
        val localizer = SubAgentDockLocalizer(
            settingsSnapshot = { settings },
            json = testJson,
            translator = { _, _, _ ->
                calls += 1
                error("must not be called")
            },
        )

        assertEquals(details, localizer.localize(details, Locale.ENGLISH).getOrThrow())
        assertEquals(0, calls)
    }

    @Test
    fun failedTranslationReturnsFailureWithoutReplacement() = runBlocking {
        val localizer = SubAgentDockLocalizer(
            settingsSnapshot = { settings },
            json = testJson,
            translator = { _, _, _ -> error("fake translation failure") },
        )

        val result = localizer.localize(details, Locale.SIMPLIFIED_CHINESE)

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
    }

    @Test
    fun mostlyEnglishTranslationIsNotDisplayedAsChinese() = runBlocking {
        val localizer = SubAgentDockLocalizer(
            settingsSnapshot = { settings }, json = testJson,
            translator = { _, _, _ ->
                """{"items":[{"id":"summary","text":"结果: Investigate the SSH connection and report the current status."}]}"""
            },
        )
        assertTrue(localizer.localize(details.copy(stages = emptyList()), Locale.SIMPLIFIED_CHINESE).isFailure)
    }
}
