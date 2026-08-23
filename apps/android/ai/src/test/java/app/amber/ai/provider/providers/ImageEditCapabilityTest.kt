package app.amber.ai.provider.providers

import app.amber.ai.provider.ImageGenerationMode
import app.amber.ai.provider.ImageGenerationParams
import app.amber.ai.provider.Model
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * P6-02 — image edit capability declarations (plan §P6-02 #2 按真实 API 能力声明):
 * OpenAI API-key auth exposes `images.edit`; Codex OAuth's `image_generation`
 * tool accepts text input only; Google / Claude keep the default false.
 * The UI entry is gated on this, so unsupported providers never show 修改.
 */
class ImageEditCapabilityTest {

    private fun openAiSetting(authMode: OpenAIAuthMode = OpenAIAuthMode.API_KEY) =
        ProviderSetting.OpenAI(
            id = Uuid.random(),
            baseUrl = "https://api.openai.com/v1",
            useResponseApi = true,
            authMode = authMode,
            name = "OpenAI",
        )

    private fun googleSetting() = ProviderSetting.Google(
        id = Uuid.random(),
        name = "Google",
    )

    private fun claudeSetting() = ProviderSetting.Claude(
        id = Uuid.random(),
        name = "Claude",
    )

    @Test
    fun openAiApiKeyDeclaresEditCapability() {
        assertTrue(OpenAIProvider(okhttp3.OkHttpClient(), null).supportsImageEdit(openAiSetting()))
    }

    @Test
    fun codexOauthAndCodingPlansNeverDeclareEdit() {
        val provider = OpenAIProvider(okhttp3.OkHttpClient(), null)
        assertFalse(provider.supportsImageEdit(openAiSetting(authMode = OpenAIAuthMode.CODEX_OAUTH)))
        assertFalse(provider.supportsImageEdit(openAiSetting(authMode = OpenAIAuthMode.ZHIPU_CODING_PLAN)))
        assertFalse(provider.supportsImageEdit(openAiSetting(authMode = OpenAIAuthMode.KIMI_CODING_PLAN)))
        assertFalse(provider.supportsImageEdit(openAiSetting(authMode = OpenAIAuthMode.MIMO_CODING_PLAN)))
        assertFalse(provider.supportsImageEdit(openAiSetting(authMode = OpenAIAuthMode.MINIMAX_TOKEN_PLAN)))
    }

    @Test
    fun googleAndClaudeDoNotDeclareEdit() {
        assertFalse(GoogleProvider(okhttp3.OkHttpClient(), null).supportsImageEdit(googleSetting()))
        assertFalse(ClaudeProvider(okhttp3.OkHttpClient(), null).supportsImageEdit(claudeSetting()))
    }

    @Test
    fun paramsDefaultToCreateWithoutSource() {
        // P6-02 regression: existing call sites construct ImageGenerationParams
        // without mode/source — defaults keep create behavior byte-identical.
        val params = ImageGenerationParams(
            model = Model(modelId = "gpt-image-test", displayName = "Test"),
            prompt = "a cat",
        )
        assertEquals(ImageGenerationMode.CREATE, params.mode)
        assertNull(params.sourceImageUrl)
    }
}
