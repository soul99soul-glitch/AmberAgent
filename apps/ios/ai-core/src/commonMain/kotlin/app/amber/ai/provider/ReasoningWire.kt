package app.amber.ai.provider

import app.amber.ai.core.ReasoningLevel
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class OpenAICompatibleThinkingFields(
    val thinkingType: String? = null,
    val reasoningEffort: String? = null,
    val enableThinking: Boolean? = null,
    val thinkingBudget: Int? = null,
    val thinkingMode: Boolean? = null,
    val openRouterEffort: String? = null,
    val openRouterEnabled: Boolean? = null,
)

data class GeminiThinkingConfig(
    val includeThoughts: Boolean,
    val thinkingLevel: String? = null,
    val thinkingBudget: Int? = null,
)

fun planOpenAICompatibleThinking(
    host: String,
    brand: OpenAIBrand?,
    modelId: String,
    level: ReasoningLevel,
): OpenAICompatibleThinkingFields {
    val id = modelId.normalizedModelId()
    return when {
        host == "api.mistral.ai" -> OpenAICompatibleThinkingFields()
        host == "openrouter.ai" -> openRouterFields(level)
        host == "api.siliconflow.cn" -> OpenAICompatibleThinkingFields(enableThinking = level.isEnabled)
        id.isKimiK3() || (isKimiHost(host, brand) && id.isKimiK3()) -> kimiK3Fields(level)
        id.isKimiK27Code() -> OpenAICompatibleThinkingFields()
        id.isGlm53() -> glm53Fields(level)
        id.isGlm52() -> glm52Fields(level)
        id.isGlm5Effort() -> glm5Fields(level)
        id.isDeepSeekV4() -> deepSeekV4Fields(level)
        id.isQwen38Max() -> qwen38Fields(level)
        id.isMiniMaxM3() -> miniMaxM3Fields(level)
        id.isMiniMaxM2() -> OpenAICompatibleThinkingFields(thinkingType = "adaptive")
        id.isGrok46() || id.isGrok45() || host == "api.x.ai" || host == "cli-chat-proxy.grok.com" ->
            grokFields(id, level)
        isMiMoHost(host, brand, id) -> OpenAICompatibleThinkingFields(
            thinkingType = if (level.isEnabled) "enabled" else "disabled",
        )
        isDashScopeHost(host) -> dashScopeBudgetFields(level)
        host == "ark.cn-beijing.volces.com" -> OpenAICompatibleThinkingFields(
            thinkingType = if (level.isEnabled) "enabled" else "disabled",
        )
        host == "chat.intern-ai.org.cn" -> OpenAICompatibleThinkingFields(thinkingMode = level.isEnabled)
        isKimiHost(host, brand) || "kimi" in id || "moonshot" in id -> OpenAICompatibleThinkingFields(
            thinkingType = if (level.isEnabled) "enabled" else "disabled",
        )
        isZhipuHost(host, brand) || "glm" in id -> OpenAICompatibleThinkingFields(
            thinkingType = if (level.isEnabled) "enabled" else "disabled",
        )
        isDeepSeekHost(host, brand) || "deepseek" in id -> OpenAICompatibleThinkingFields(
            thinkingType = if (level.isEnabled) "enabled" else "disabled",
        )
        else -> openAiOfficialFields(level)
    }
}

fun JsonObjectBuilder.putOpenAICompatibleThinking(fields: OpenAICompatibleThinkingFields) {
    fields.thinkingType?.let { type ->
        put("thinking", buildJsonObject { put("type", type) })
    }
    fields.reasoningEffort?.let { put("reasoning_effort", it) }
    fields.enableThinking?.let { put("enable_thinking", it) }
    fields.thinkingBudget?.let { put("thinking_budget", it) }
    fields.thinkingMode?.let { put("thinking_mode", it) }
    if (fields.openRouterEffort != null || fields.openRouterEnabled != null) {
        put("reasoning", buildJsonObject {
            fields.openRouterEffort?.let { put("effort", it) }
            fields.openRouterEnabled?.let { put("enabled", it) }
        })
    }
}

fun geminiThinkingConfig(modelId: String, level: ReasoningLevel): GeminiThinkingConfig {
    val family = modelId.geminiFamily()
    return when (family) {
        ReasoningFamily.GEMINI_LOW_MEDIUM_HIGH -> GeminiThinkingConfig(
            includeThoughts = true,
            thinkingLevel = when (level) {
                ReasoningLevel.AUTO -> null
                ReasoningLevel.OFF, ReasoningLevel.LOW -> "low"
                ReasoningLevel.MEDIUM -> "medium"
                ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "high"
            },
        )
        ReasoningFamily.GEMINI_LOW_HIGH -> GeminiThinkingConfig(
            includeThoughts = true,
            thinkingLevel = when (level) {
                ReasoningLevel.AUTO -> null
                ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM -> "low"
                ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "high"
            },
        )
        ReasoningFamily.GEMINI_MINIMAL_HIGH -> GeminiThinkingConfig(
            includeThoughts = level != ReasoningLevel.OFF,
            thinkingLevel = when (level) {
                ReasoningLevel.AUTO -> null
                ReasoningLevel.OFF -> "minimal"
                else -> "high"
            },
        )
        ReasoningFamily.GEMINI_OFF_LOW_MEDIUM_HIGH -> when (level) {
            ReasoningLevel.AUTO -> GeminiThinkingConfig(includeThoughts = true)
            ReasoningLevel.OFF -> GeminiThinkingConfig(
                includeThoughts = false,
                thinkingBudget = 0,
            )
            ReasoningLevel.LOW -> GeminiThinkingConfig(includeThoughts = true, thinkingLevel = "low")
            ReasoningLevel.MEDIUM -> GeminiThinkingConfig(includeThoughts = true, thinkingLevel = "medium")
            else -> GeminiThinkingConfig(includeThoughts = true, thinkingLevel = "high")
        }
        ReasoningFamily.GEMINI_MINIMAL_LOW_MEDIUM_HIGH,
        ReasoningFamily.GEMINI -> GeminiThinkingConfig(
            includeThoughts = level != ReasoningLevel.OFF,
            thinkingLevel = when (level) {
                ReasoningLevel.AUTO -> null
                ReasoningLevel.OFF -> "minimal"
                ReasoningLevel.LOW -> "low"
                ReasoningLevel.MEDIUM -> "medium"
                ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "high"
            },
        )
        else -> GeminiThinkingConfig(
            includeThoughts = level.isEnabled,
            thinkingLevel = when (level) {
                ReasoningLevel.AUTO -> null
                ReasoningLevel.OFF -> "minimal"
                ReasoningLevel.LOW -> "low"
                ReasoningLevel.MEDIUM -> "medium"
                else -> "high"
            },
        )
    }
}

fun openAIResponsesReasoningEffort(level: ReasoningLevel): String? = when (level) {
    ReasoningLevel.AUTO -> null
    ReasoningLevel.OFF -> "none"
    ReasoningLevel.LOW -> "low"
    ReasoningLevel.MEDIUM -> "medium"
    ReasoningLevel.HIGH -> "high"
    ReasoningLevel.XHIGH -> "xhigh"
    ReasoningLevel.MAX -> "max"
}

fun shouldDisableClaudeThinking(modelId: String, level: ReasoningLevel): Boolean {
    if (level != ReasoningLevel.OFF) return false
    val id = modelId.normalizedModelId()
    return !id.contains("fable") && !id.contains("mythos")
}

private fun kimiK3Fields(level: ReasoningLevel) = OpenAICompatibleThinkingFields(
    reasoningEffort = when (level) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.OFF, ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM, ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
    },
)

private fun glm53Fields(level: ReasoningLevel) = OpenAICompatibleThinkingFields(
    thinkingType = "enabled",
    reasoningEffort = when (level) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.OFF, ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM, ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
    },
)

private fun glm52Fields(level: ReasoningLevel) = OpenAICompatibleThinkingFields(
    thinkingType = if (level.isEnabled) "enabled" else "disabled",
    reasoningEffort = when (level) {
        ReasoningLevel.OFF, ReasoningLevel.AUTO -> null
        ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
    },
)

private fun glm5Fields(level: ReasoningLevel) = OpenAICompatibleThinkingFields(
    thinkingType = if (level.isEnabled) "enabled" else "disabled",
    reasoningEffort = when (level) {
        ReasoningLevel.OFF, ReasoningLevel.AUTO -> null
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "xhigh"
    },
)

private fun deepSeekV4Fields(level: ReasoningLevel) = OpenAICompatibleThinkingFields(
    thinkingType = if (level.isEnabled) "enabled" else "disabled",
    reasoningEffort = when (level) {
        ReasoningLevel.OFF, ReasoningLevel.AUTO -> null
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM, ReasoningLevel.HIGH, ReasoningLevel.XHIGH -> "high"
        ReasoningLevel.MAX -> "max"
    },
)

private fun qwen38Fields(level: ReasoningLevel) = when (level) {
    ReasoningLevel.OFF -> OpenAICompatibleThinkingFields(enableThinking = false)
    ReasoningLevel.AUTO -> OpenAICompatibleThinkingFields(enableThinking = true)
    ReasoningLevel.LOW -> OpenAICompatibleThinkingFields(enableThinking = true, reasoningEffort = "low")
    ReasoningLevel.MEDIUM -> OpenAICompatibleThinkingFields(enableThinking = true, reasoningEffort = "medium")
    ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MAX ->
        OpenAICompatibleThinkingFields(enableThinking = true, reasoningEffort = "xhigh")
}

private fun dashScopeBudgetFields(level: ReasoningLevel) = OpenAICompatibleThinkingFields(
    enableThinking = level.isEnabled,
    thinkingBudget = if (level != ReasoningLevel.AUTO) level.budgetTokens else null,
)

private fun miniMaxM3Fields(level: ReasoningLevel) = OpenAICompatibleThinkingFields(
    thinkingType = if (level.isEnabled) "adaptive" else "disabled",
)

private fun grokFields(modelId: String, level: ReasoningLevel) = OpenAICompatibleThinkingFields(
    reasoningEffort = when (level) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.OFF, ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH, ReasoningLevel.MAX -> if (modelId.isGrok45()) "high" else "xhigh"
    },
)

private fun openAiOfficialFields(level: ReasoningLevel) = OpenAICompatibleThinkingFields(
    reasoningEffort = openAIResponsesReasoningEffort(level),
)

private fun openRouterFields(level: ReasoningLevel) = when (level) {
    ReasoningLevel.OFF -> OpenAICompatibleThinkingFields(openRouterEffort = "none")
    ReasoningLevel.AUTO -> OpenAICompatibleThinkingFields(openRouterEnabled = true)
    else -> OpenAICompatibleThinkingFields(openRouterEffort = level.effort)
}

private fun isKimiHost(host: String, brand: OpenAIBrand?): Boolean =
    brand == OpenAIBrand.KIMI ||
        host == "api.moonshot.cn" ||
        host == "api.moonshot.ai" ||
        host == "api.kimi.com" ||
        host == "api.kimi.ai" ||
        host.endsWith(".kimi.com") ||
        host.endsWith(".moonshot.cn") ||
        host.endsWith(".moonshot.ai")

private fun isZhipuHost(host: String, brand: OpenAIBrand?): Boolean =
    brand == OpenAIBrand.ZHIPU ||
        host == "open.bigmodel.cn" ||
        host == "api.z.ai" ||
        host.endsWith(".bigmodel.cn")

private fun isDeepSeekHost(host: String, brand: OpenAIBrand?): Boolean =
    brand == OpenAIBrand.DEEPSEEK || host == "api.deepseek.com"

private fun isMiMoHost(host: String, brand: OpenAIBrand?, modelId: String): Boolean =
    brand == OpenAIBrand.MIMO ||
        host.endsWith("xiaomimimo.com") ||
        host.endsWith("xiaomi.com") && host.contains("mimo") ||
        "mimo" in modelId

private fun isDashScopeHost(host: String): Boolean =
    host.contains("dashscope") || host.contains("maas.aliyuncs.com")
