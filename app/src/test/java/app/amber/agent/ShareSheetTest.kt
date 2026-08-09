package app.amber.agent

import app.amber.ai.provider.BalanceOption
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.feature.ui.components.ui.decodeProviderSetting
import app.amber.feature.ui.components.ui.encodeForShare
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ShareSheetTest {
    @Test
    fun `encode and decode should preserve every provider variant`() {
        val providers = listOf(
            ProviderSetting.OpenAI(
                id = Uuid.random(),
                enabled = true,
                name = "Test OpenAI",
                models = listOf(Model(id = Uuid.random(), displayName = "gpt-4")),
                apiKey = "sk-test-key",
                baseUrl = "https://api.openai.com/v1",
                chatCompletionsPath = "/chat/completions",
                useResponseApi = false,
                balanceOption = BalanceOption(
                    enabled = true,
                    apiPath = "/custom/credits",
                    resultPath = "data.balance",
                ),
            ),
            ProviderSetting.Google(
                id = Uuid.random(),
                enabled = true,
                name = "Test Google",
                apiKey = "test-google-key",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                vertexAI = true,
                projectId = "project-123",
            ),
            ProviderSetting.Claude(
                id = Uuid.random(),
                enabled = false,
                name = "Test Claude",
                apiKey = "test-claude-key",
                baseUrl = "https://api.anthropic.com/v1",
            ),
        )

        providers.forEach { original ->
            val decoded = decodeProviderSetting(original.encodeForShare())
            assertEquals(original, decoded)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode should throw exception for invalid prefix`() {
        decodeProviderSetting("invalid-string")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode should throw exception for wrong version`() {
        decodeProviderSetting("ai-provider:v2:somedata")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode should throw exception for invalid base64`() {
        decodeProviderSetting("ai-provider:v1:not-valid-base64!!!")
    }
}
