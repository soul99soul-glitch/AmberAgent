package app.amber.ai.provider

import app.amber.ai.core.ReasoningLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReasoningWireTest {
    @Test
    fun kimiK3SendsReasoningEffortAndOmitsThinking() {
        val fields = plan(
            host = "api.moonshot.cn",
            brand = OpenAIBrand.KIMI,
            modelId = "kimi-k3",
            level = ReasoningLevel.HIGH,
        )
        assertNull(fields.thinkingType)
        assertEquals("high", fields.reasoningEffort)
    }

    @Test
    fun kimiK3OnCodingPlanHostStillUsesEffort() {
        val fields = plan(
            host = "api.kimi.com",
            brand = OpenAIBrand.KIMI,
            modelId = "kimi-k3",
            level = ReasoningLevel.MAX,
        )
        assertNull(fields.thinkingType)
        assertEquals("max", fields.reasoningEffort)
    }

    @Test
    fun kimiK3DoesNotSendDisabledThinking() {
        val fields = plan(
            host = "api.moonshot.ai",
            brand = OpenAIBrand.KIMI,
            modelId = "kimi-k3",
            level = ReasoningLevel.OFF,
        )
        assertNull(fields.thinkingType)
        assertEquals("low", fields.reasoningEffort)
    }

    @Test
    fun deepSeekV4SendsLowHighMax() {
        val low = plan("api.deepseek.com", OpenAIBrand.DEEPSEEK, "deepseek-v4-pro", ReasoningLevel.LOW)
        assertEquals("enabled", low.thinkingType)
        assertEquals("low", low.reasoningEffort)

        val medium = plan("api.deepseek.com", OpenAIBrand.DEEPSEEK, "deepseek-v4-flash", ReasoningLevel.MEDIUM)
        assertEquals("high", medium.reasoningEffort)

        val max = plan("api.deepseek.com", OpenAIBrand.DEEPSEEK, "deepseek-v4-pro", ReasoningLevel.MAX)
        assertEquals("max", max.reasoningEffort)

        val off = plan("api.deepseek.com", OpenAIBrand.DEEPSEEK, "deepseek-v4-pro", ReasoningLevel.OFF)
        assertEquals("disabled", off.thinkingType)
        assertNull(off.reasoningEffort)
    }

    @Test
    fun glm53NeverDisablesThinking() {
        val off = plan("open.bigmodel.cn", OpenAIBrand.ZHIPU, "glm-5.3", ReasoningLevel.OFF)
        assertEquals("enabled", off.thinkingType)
        assertEquals("low", off.reasoningEffort)

        val max = plan("open.bigmodel.cn", OpenAIBrand.ZHIPU, "glm-5.3", ReasoningLevel.MAX)
        assertEquals("enabled", max.thinkingType)
        assertEquals("max", max.reasoningEffort)
    }

    @Test
    fun qwen38MaxSendsEffortNotBudget() {
        val fields = plan(
            host = "dashscope.aliyuncs.com",
            brand = OpenAIBrand.GENERIC,
            modelId = "qwen3.8-max",
            level = ReasoningLevel.XHIGH,
        )
        assertEquals(true, fields.enableThinking)
        assertEquals("xhigh", fields.reasoningEffort)
        assertNull(fields.thinkingBudget)
    }

    @Test
    fun qwen38MaxOffDisablesThinking() {
        val fields = plan("dashscope.aliyuncs.com", OpenAIBrand.GENERIC, "qwen3.8-max", ReasoningLevel.OFF)
        assertEquals(false, fields.enableThinking)
        assertNull(fields.reasoningEffort)
        assertNull(fields.thinkingBudget)
    }

    @Test
    fun openAiSendsXhighAndMaxUnchanged() {
        val xhigh = plan("api.openai.com", OpenAIBrand.OPENAI, "gpt-5.6", ReasoningLevel.XHIGH)
        assertEquals("xhigh", xhigh.reasoningEffort)
        val max = plan("api.openai.com", OpenAIBrand.OPENAI, "gpt-5.6", ReasoningLevel.MAX)
        assertEquals("max", max.reasoningEffort)
        val none = plan("api.openai.com", OpenAIBrand.OPENAI, "gpt-5.6", ReasoningLevel.OFF)
        assertEquals("none", none.reasoningEffort)
    }

    @Test
    fun gemini37FlashOmitsThinkingLevelForAuto() {
        val auto = geminiThinkingConfig("gemini-3.7-flash", ReasoningLevel.AUTO)
        assertNull(auto.thinkingLevel)
        val low = geminiThinkingConfig("gemini-3.7-flash", ReasoningLevel.LOW)
        assertEquals("low", low.thinkingLevel)
        val off = geminiThinkingConfig("gemini-3.7-flash", ReasoningLevel.OFF)
        assertEquals("low", off.thinkingLevel)
    }

    @Test
    fun gemini3ProOnlyLowOrHigh() {
        assertEquals("low", geminiThinkingConfig("gemini-3-pro-preview", ReasoningLevel.MEDIUM).thinkingLevel)
        assertEquals("high", geminiThinkingConfig("gemini-3-pro-preview", ReasoningLevel.MAX).thinkingLevel)
    }

    @Test
    fun gemini36OffMapsToMinimal() {
        assertEquals("minimal", geminiThinkingConfig("gemini-3.6-flash", ReasoningLevel.OFF).thinkingLevel)
    }

    private fun plan(
        host: String,
        brand: OpenAIBrand,
        modelId: String,
        level: ReasoningLevel,
    ): OpenAICompatibleThinkingFields =
        planOpenAICompatibleThinking(host, brand, modelId, level)
}
