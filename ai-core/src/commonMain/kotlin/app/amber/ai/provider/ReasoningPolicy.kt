package app.amber.ai.provider

import app.amber.ai.core.ReasoningLevel

data class ReasoningOption(
    val level: ReasoningLevel,
    val label: String = level.reasoningLabel(),
)

enum class ReasoningFamily {
    CLAUDE_OPUS_47,
    CLAUDE_MAX,
    CLAUDE_HIGH,
    OPENAI_XHIGH,
    OPENAI,
    DEEPSEEK,
    BINARY,
    GEMINI,
    GENERIC,
    NONE,
}

fun ReasoningLevel.reasoningLabel(): String = when (this) {
    ReasoningLevel.OFF -> "off"
    ReasoningLevel.AUTO -> "auto"
    ReasoningLevel.LOW -> "low"
    ReasoningLevel.MEDIUM -> "medium"
    ReasoningLevel.HIGH -> "high"
    ReasoningLevel.XHIGH -> "xhigh"
    ReasoningLevel.MAX -> "max"
}

fun List<ReasoningOption>.labelFor(level: ReasoningLevel): String =
    firstOrNull { it.level == level }?.label ?: level.reasoningLabel()

fun ReasoningLevel.coerceToReasoningOptions(options: List<ReasoningOption>): ReasoningLevel {
    if (options.any { it.level == this }) return this
    if (this == ReasoningLevel.AUTO) {
        return options.firstOrNull { it.level == ReasoningLevel.MEDIUM }?.level
            ?: options.firstOrNull { it.level == ReasoningLevel.HIGH }?.level
            ?: options.firstOrNull()?.level
            ?: ReasoningLevel.OFF
    }
    if ((this == ReasoningLevel.XHIGH || this == ReasoningLevel.MAX) &&
        options.any { it.level == ReasoningLevel.MAX }
    ) {
        return ReasoningLevel.MAX
    }
    if (isEnabled && options.any { it.level == ReasoningLevel.AUTO }) {
        return ReasoningLevel.AUTO
    }
    return options.firstOrNull()?.level ?: ReasoningLevel.OFF
}

fun Model?.reasoningOptions(provider: ProviderSetting?): List<ReasoningOption> {
    if (this == null) {
        return reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
        )
    }
    return when (reasoningFamily(provider)) {
        ReasoningFamily.CLAUDE_OPUS_47 -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
            ReasoningLevel.MAX,
        )

        ReasoningFamily.CLAUDE_MAX -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.MAX,
        )

        ReasoningFamily.CLAUDE_HIGH -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        ReasoningFamily.OPENAI_XHIGH,
        ReasoningFamily.OPENAI -> reasoningOptionsOf(
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
        )

        ReasoningFamily.GEMINI -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        ReasoningFamily.DEEPSEEK -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.HIGH,
            ReasoningLevel.MAX,
        )

        ReasoningFamily.BINARY -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
        )

        ReasoningFamily.GENERIC -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
        )

        ReasoningFamily.NONE -> reasoningOptionsOf(ReasoningLevel.OFF)
    }
}

fun Model.reasoningFamily(provider: ProviderSetting?): ReasoningFamily {
    val id = modelId.lowercase()
    val providerKey = provider.providerRoutingKey()
    return when {
        "claude" in id || provider is ProviderSetting.Claude -> when {
            id.contains("opus") && id.contains("4") && id.contains("7") -> ReasoningFamily.CLAUDE_OPUS_47
            id.contains("mythos") -> ReasoningFamily.CLAUDE_MAX
            id.contains("opus") && id.contains("4") && (id.contains("5") || id.contains("6")) -> ReasoningFamily.CLAUDE_MAX
            id.contains("4") && id.contains("5") -> ReasoningFamily.CLAUDE_MAX
            id.contains("sonnet") && id.contains("4") && id.contains("6") -> ReasoningFamily.CLAUDE_HIGH
            else -> ReasoningFamily.GENERIC
        }

        "deepseek" in id || providerKey == "deepseek" -> ReasoningFamily.DEEPSEEK
        "kimi" in id || "moonshot" in id || providerKey == "kimi" -> ReasoningFamily.BINARY
        "glm" in id || "zhipu" in id || providerKey == "zhipu" -> ReasoningFamily.BINARY
        "mimo" in id || providerKey == "mimo" -> ReasoningFamily.BINARY
        id.isQwenPlusBinaryReasoningModel() -> ReasoningFamily.BINARY
        provider is ProviderSetting.Google || providerKey == "gemini" -> ReasoningFamily.GEMINI
        id.contains("gpt-5.5") || id.contains("gpt-5.4") -> ReasoningFamily.OPENAI_XHIGH
        id.contains("gpt-5") || id.contains("codex") || Regex("\\bo\\d+").containsMatchIn(id) -> ReasoningFamily.OPENAI
        ModelAbility.REASONING in abilities -> ReasoningFamily.GENERIC
        else -> ReasoningFamily.NONE
    }
}

fun reasoningLevelsForModel(
    model: Model,
    provider: ProviderSetting? = null,
): List<Pair<ReasoningLevel, String>> =
    model.reasoningOptions(provider).map { it.level to it.label }

fun ProviderSetting?.providerRoutingKey(): String {
    return when (this) {
        is ProviderSetting.Claude -> "claude"
        is ProviderSetting.Google -> "gemini"
        is ProviderSetting.OpenAI -> {
            when (brand) {
                OpenAIBrand.OPENAI -> "openai"
                OpenAIBrand.DEEPSEEK -> "deepseek"
                OpenAIBrand.ZHIPU -> "zhipu"
                OpenAIBrand.KIMI -> "kimi"
                OpenAIBrand.MIMO -> "mimo"
                OpenAIBrand.MINIMAX -> "minimax"
                OpenAIBrand.GENERIC -> {
                    val endpoint = "$baseUrl $name".lowercase()
                    when {
                        "deepseek" in endpoint -> "deepseek"
                        "moonshot" in endpoint || "kimi" in endpoint -> "kimi"
                        "bigmodel" in endpoint || "zhipu" in endpoint -> "zhipu"
                        "xiaomimimo" in endpoint || "mimo" in endpoint -> "mimo"
                        "api.openai.com" in endpoint || name.equals("openai", ignoreCase = true) -> "openai"
                        else -> ""
                    }
                }
            }
        }

        null -> ""
    }
}

private fun reasoningOptionsOf(vararg levels: ReasoningLevel): List<ReasoningOption> =
    levels.map { ReasoningOption(it) }

private fun String.isQwenPlusBinaryReasoningModel(): Boolean {
    if (!contains("qwen") || !contains("plus")) return false
    return Regex("""(^|[^0-9])3[._-]?5([^0-9]|$)""").containsMatchIn(this) ||
        Regex("""(^|[^0-9])3[._-]?6([^0-9]|$)""").containsMatchIn(this)
}
