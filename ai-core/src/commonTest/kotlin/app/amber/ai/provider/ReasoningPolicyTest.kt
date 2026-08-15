package app.amber.ai.provider

import app.amber.ai.core.ReasoningLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReasoningPolicyTest {
    @Test
    fun kimiK3IsAlwaysLowHighMax() {
        val options = options("kimi-k3", kimi())
        assertEquals(
            listOf(ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX),
            options,
        )
        assertEquals(ReasoningLevel.MAX, default("kimi-k3", kimi()))
    }

    @Test
    fun kimiK26StaysBinary() {
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO),
            options("kimi-k2.6", kimi()),
        )
    }

    @Test
    fun kimiK27CodeIsAlwaysOn() {
        assertEquals(
            listOf(ReasoningLevel.AUTO),
            options("kimi-k2.7-code", kimi()),
        )
    }

    @Test
    fun deepSeekV4IsOffLowHighMax() {
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX),
            options("deepseek-v4-pro", deepseek()),
        )
        assertEquals(ReasoningLevel.HIGH, default("deepseek-v4-flash", deepseek()))
    }

    @Test
    fun glm53IsAlwaysLowHighMax() {
        assertEquals(
            listOf(ReasoningLevel.LOW, ReasoningLevel.HIGH, ReasoningLevel.MAX),
            options("glm-5.3", zhipu()),
        )
        assertEquals(ReasoningLevel.MAX, default("glm-5.3", zhipu()))
    }

    @Test
    fun glm52IsOffHighMax() {
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.HIGH, ReasoningLevel.MAX),
            options("glm-5.2", zhipu()),
        )
    }

    @Test
    fun qwen38MaxIsOffLowMediumXhigh() {
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.XHIGH),
            options("qwen3.8-max", dashscope()),
        )
        assertEquals(ReasoningLevel.XHIGH, default("qwen3.8-max", dashscope()))
    }

    @Test
    fun openAiIncludesNoneAndMax() {
        assertEquals(
            listOf(
                ReasoningLevel.OFF,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
                ReasoningLevel.XHIGH,
                ReasoningLevel.MAX,
            ),
            options("gpt-5.6", openai()),
        )
        assertFalse(ReasoningLevel.AUTO in options("gpt-5.4", openai()))
        assertEquals(ReasoningLevel.MEDIUM, default("gpt-5.5", openai()))
    }

    @Test
    fun gemini37FlashIsLowMediumHigh() {
        assertEquals(
            listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
            options("gemini-3.7-flash", gemini()),
        )
        assertFalse(ReasoningLevel.OFF in options("gemini-3.7-flash", gemini()))
        assertEquals(ReasoningLevel.MEDIUM, default("gemini-3.7-flash", gemini()))
    }

    @Test
    fun gemini3ProIsLowHigh() {
        assertEquals(
            listOf(ReasoningLevel.AUTO, ReasoningLevel.LOW, ReasoningLevel.HIGH),
            options("gemini-3-pro-preview", gemini()),
        )
    }

    @Test
    fun claudeFableCannotDisableThinking() {
        val options = options("claude-fable-5", claude())
        assertFalse(ReasoningLevel.OFF in options)
        assertTrue(ReasoningLevel.XHIGH in options)
        assertTrue(ReasoningLevel.MAX in options)
    }

    @Test
    fun claudeOpus5HasOffAndXhigh() {
        val options = options("claude-opus-5", claude())
        assertTrue(ReasoningLevel.OFF in options)
        assertTrue(ReasoningLevel.XHIGH in options)
        assertTrue(ReasoningLevel.MAX in options)
    }

    @Test
    fun grok46ApiHasXhighAndNoOff() {
        assertEquals(
            listOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH),
            options("grok-4.6", xai()),
        )
    }

    @Test
    fun grokWebModesDoNotUseApiEffortMenu() {
        assertEquals(
            listOf(ReasoningLevel.OFF),
            options("grok-4.20-fast", grokWeb()),
        )
    }

    @Test
    fun miniMaxM3IsBinary() {
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO),
            options("MiniMax-M3", minimax()),
        )
    }

    @Test
    fun miniMaxM2IsAlwaysOn() {
        assertEquals(
            listOf(ReasoningLevel.AUTO),
            options("MiniMax-M2.5", minimax()),
        )
    }

    @Test
    fun mimoStaysBinary() {
        assertEquals(
            listOf(ReasoningLevel.OFF, ReasoningLevel.AUTO),
            options("mimo-v2.5-pro", mimo()),
        )
    }

    @Test
    fun offOnAlwaysOnModelCoercesToLow() {
        val model = model("kimi-k3")
        val coerced = ReasoningLevel.OFF.coerceToReasoningOptions(
            model.reasoningOptions(kimi()),
            model.defaultReasoningLevel(kimi()),
        )
        assertEquals(ReasoningLevel.LOW, coerced)
    }

    @Test
    fun autoOnK3CoercesToOfficialDefaultMax() {
        val model = model("kimi-k3")
        val coerced = ReasoningLevel.AUTO.coerceToReasoningOptions(
            model.reasoningOptions(kimi()),
            model.defaultReasoningLevel(kimi()),
        )
        assertEquals(ReasoningLevel.MAX, coerced)
    }

    private fun options(modelId: String, provider: ProviderSetting): List<ReasoningLevel> =
        model(modelId).reasoningOptions(provider).map { it.level }

    private fun default(modelId: String, provider: ProviderSetting): ReasoningLevel =
        model(modelId).defaultReasoningLevel(provider)

    private fun model(modelId: String) = Model(
        modelId = modelId,
        displayName = modelId,
        abilities = listOf(ModelAbility.REASONING),
    )

    private fun kimi() = ProviderSetting.OpenAI(brand = OpenAIBrand.KIMI, baseUrl = "https://api.moonshot.cn/v1")
    private fun deepseek() = ProviderSetting.OpenAI(brand = OpenAIBrand.DEEPSEEK, baseUrl = "https://api.deepseek.com/v1")
    private fun zhipu() = ProviderSetting.OpenAI(brand = OpenAIBrand.ZHIPU, baseUrl = "https://open.bigmodel.cn/api/paas/v4")
    private fun openai() = ProviderSetting.OpenAI(brand = OpenAIBrand.OPENAI, baseUrl = "https://api.openai.com/v1")
    private fun gemini() = ProviderSetting.Google()
    private fun claude() = ProviderSetting.Claude()
    private fun xai() = ProviderSetting.OpenAI(name = "xAI", baseUrl = "https://api.x.ai/v1")
    private fun grokWeb() = ProviderSetting.OpenAI(name = "Grok", baseUrl = "https://grok.com/rest/app-chat")
    private fun minimax() = ProviderSetting.OpenAI(brand = OpenAIBrand.MINIMAX, baseUrl = "https://api.minimaxi.com/v1")
    private fun mimo() = ProviderSetting.OpenAI(brand = OpenAIBrand.MIMO, baseUrl = "https://api.xiaomimimo.com/v1")
    private fun dashscope() = ProviderSetting.OpenAI(baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1")
}
