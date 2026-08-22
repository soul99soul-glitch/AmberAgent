package app.amber.ai.provider

import app.amber.ai.core.ReasoningLevel

data class ReasoningOption(
    val level: ReasoningLevel,
    val label: String = level.reasoningLabel(),
)

enum class ReasoningFamily {
    ALWAYS_LOW_HIGH_MAX,
    ALWAYS_ON,
    DEEPSEEK,
    GLM_52,
    GLM_5,
    BINARY,
    QWEN_38,
    OPENAI,
    GEMINI,
    GEMINI_LOW_MEDIUM_HIGH,
    GEMINI_MINIMAL_LOW_MEDIUM_HIGH,
    GEMINI_LOW_HIGH,
    GEMINI_OFF_LOW_MEDIUM_HIGH,
    GEMINI_MINIMAL_HIGH,
    CLAUDE_OPUS_47,
    CLAUDE_MAX,
    CLAUDE_HIGH,
    CLAUDE_ALWAYS_FULL,
    GROK_XHIGH,
    GROK_HIGH,
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

fun ReasoningLevel.coerceToReasoningOptions(
    options: List<ReasoningOption>,
    preferredDefault: ReasoningLevel? = null,
): ReasoningLevel {
    if (options.any { it.level == this }) return this
    if (this == ReasoningLevel.AUTO) {
        return options.firstOrNull { it.level == preferredDefault }?.level
            ?: options.firstOrNull { it.level == ReasoningLevel.MEDIUM }?.level
            ?: options.firstOrNull { it.level == ReasoningLevel.HIGH }?.level
            ?: options.firstOrNull()?.level
            ?: ReasoningLevel.OFF
    }
    if ((this == ReasoningLevel.XHIGH || this == ReasoningLevel.MAX) &&
        options.any { it.level == ReasoningLevel.MAX }
    ) {
        return ReasoningLevel.MAX
    }
    if ((this == ReasoningLevel.XHIGH || this == ReasoningLevel.MAX) &&
        options.any { it.level == ReasoningLevel.XHIGH }
    ) {
        return ReasoningLevel.XHIGH
    }
    if (this == ReasoningLevel.MEDIUM &&
        options.none { it.level == ReasoningLevel.MEDIUM } &&
        options.any { it.level == ReasoningLevel.HIGH }
    ) {
        return ReasoningLevel.HIGH
    }
    if (this == ReasoningLevel.OFF && options.none { it.level == ReasoningLevel.OFF }) {
        return options.firstOrNull { it.level == ReasoningLevel.LOW }?.level
            ?: options.firstOrNull { it.level == preferredDefault }?.level
            ?: options.firstOrNull()?.level
            ?: ReasoningLevel.OFF
    }
    if (isEnabled && options.any { it.level == ReasoningLevel.AUTO }) {
        return ReasoningLevel.AUTO
    }
    return options.firstOrNull { it.level == preferredDefault }?.level
        ?: options.firstOrNull()?.level
        ?: ReasoningLevel.OFF
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
        ReasoningFamily.ALWAYS_LOW_HIGH_MAX -> reasoningOptionsOf(
            ReasoningLevel.LOW,
            ReasoningLevel.HIGH,
            ReasoningLevel.MAX,
        )

        ReasoningFamily.ALWAYS_ON -> reasoningOptionsOf(ReasoningLevel.AUTO)

        ReasoningFamily.DEEPSEEK -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.LOW,
            ReasoningLevel.HIGH,
            ReasoningLevel.MAX,
        )

        ReasoningFamily.GLM_52 -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.HIGH,
            ReasoningLevel.MAX,
        )

        ReasoningFamily.GLM_5 -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
        )

        ReasoningFamily.QWEN_38 -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.XHIGH,
        )

        ReasoningFamily.CLAUDE_ALWAYS_FULL -> reasoningOptionsOf(
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
            ReasoningLevel.MAX,
        )

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

        ReasoningFamily.OPENAI -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
            ReasoningLevel.MAX,
        )

        ReasoningFamily.GEMINI_LOW_MEDIUM_HIGH -> reasoningOptionsOf(
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        ReasoningFamily.GEMINI_MINIMAL_LOW_MEDIUM_HIGH -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        ReasoningFamily.GEMINI_LOW_HIGH -> reasoningOptionsOf(
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.HIGH,
        )

        ReasoningFamily.GEMINI_OFF_LOW_MEDIUM_HIGH -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        ReasoningFamily.GEMINI_MINIMAL_HIGH -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.HIGH,
        )

        ReasoningFamily.GEMINI -> reasoningOptionsOf(
            ReasoningLevel.OFF,
            ReasoningLevel.AUTO,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
        )

        ReasoningFamily.GROK_XHIGH -> reasoningOptionsOf(
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
        )

        ReasoningFamily.GROK_HIGH -> reasoningOptionsOf(
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
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
    val id = modelId.normalizedModelId()
    val providerKey = provider.providerRoutingKey()
    return when {
        provider.isGrokWebEndpoint() -> ReasoningFamily.NONE

        "claude" in id || provider is ProviderSetting.Claude -> when {
            id.contains("fable") || id.contains("mythos") -> ReasoningFamily.CLAUDE_ALWAYS_FULL
            id.isClaudeOpus47() || id.isClaudeCurrentFull() -> ReasoningFamily.CLAUDE_OPUS_47
            id.contains("4-5") || id.contains("4.5") -> ReasoningFamily.CLAUDE_MAX
            id.contains("sonnet") && (id.contains("4-6") || id.contains("4.6")) -> ReasoningFamily.CLAUDE_HIGH
            else -> ReasoningFamily.GENERIC
        }

        id.isKimiK3() -> ReasoningFamily.ALWAYS_LOW_HIGH_MAX
        id.isKimiK27Code() -> ReasoningFamily.ALWAYS_ON
        "kimi" in id || "moonshot" in id || providerKey == "kimi" -> ReasoningFamily.BINARY

        id.isGlm53() -> ReasoningFamily.ALWAYS_LOW_HIGH_MAX
        id.isGlm52() -> ReasoningFamily.GLM_52
        id.isGlm5Effort() -> ReasoningFamily.GLM_5
        "glm" in id || "zhipu" in id || providerKey == "zhipu" -> ReasoningFamily.BINARY

        id.isDeepSeekV4() -> ReasoningFamily.DEEPSEEK
        "deepseek" in id || providerKey == "deepseek" -> ReasoningFamily.BINARY

        id.isQwen38Max() -> ReasoningFamily.QWEN_38
        "mimo" in id || providerKey == "mimo" -> ReasoningFamily.BINARY
        id.isMiniMaxM2() -> ReasoningFamily.ALWAYS_ON
        id.isMiniMaxM3() || providerKey == "minimax" -> ReasoningFamily.BINARY
        id.isQwenPlusBinaryReasoningModel() -> ReasoningFamily.BINARY

        provider is ProviderSetting.Google || providerKey == "gemini" || id.contains("gemini") ->
            id.geminiFamily()

        id.isGrok46() || (provider.isXaiApiEndpoint() && id.contains("4.6")) -> ReasoningFamily.GROK_XHIGH
        id.isGrok45() || (provider.isXaiApiEndpoint() && id.contains("grok")) -> ReasoningFamily.GROK_HIGH

        id.contains("gpt-5") || id.contains("codex") || Regex("\\bo\\d+").containsMatchIn(id) ->
            ReasoningFamily.OPENAI

        ModelAbility.REASONING in abilities -> ReasoningFamily.GENERIC
        else -> ReasoningFamily.NONE
    }
}

fun ReasoningFamily.defaultLevel(): ReasoningLevel = when (this) {
    ReasoningFamily.ALWAYS_LOW_HIGH_MAX -> ReasoningLevel.MAX
    ReasoningFamily.ALWAYS_ON -> ReasoningLevel.AUTO
    ReasoningFamily.DEEPSEEK -> ReasoningLevel.HIGH
    ReasoningFamily.GLM_52 -> ReasoningLevel.MAX
    ReasoningFamily.GLM_5 -> ReasoningLevel.HIGH
    ReasoningFamily.BINARY -> ReasoningLevel.AUTO
    ReasoningFamily.QWEN_38 -> ReasoningLevel.XHIGH
    ReasoningFamily.OPENAI -> ReasoningLevel.MEDIUM
    ReasoningFamily.GEMINI,
    ReasoningFamily.GEMINI_LOW_MEDIUM_HIGH,
    ReasoningFamily.GEMINI_MINIMAL_LOW_MEDIUM_HIGH -> ReasoningLevel.MEDIUM
    ReasoningFamily.GEMINI_LOW_HIGH -> ReasoningLevel.HIGH
    ReasoningFamily.GEMINI_OFF_LOW_MEDIUM_HIGH -> ReasoningLevel.OFF
    ReasoningFamily.GEMINI_MINIMAL_HIGH -> ReasoningLevel.OFF
    ReasoningFamily.CLAUDE_OPUS_47,
    ReasoningFamily.CLAUDE_MAX,
    ReasoningFamily.CLAUDE_HIGH,
    ReasoningFamily.CLAUDE_ALWAYS_FULL -> ReasoningLevel.HIGH
    ReasoningFamily.GROK_XHIGH,
    ReasoningFamily.GROK_HIGH -> ReasoningLevel.HIGH
    ReasoningFamily.GENERIC -> ReasoningLevel.AUTO
    ReasoningFamily.NONE -> ReasoningLevel.OFF
}

fun Model.defaultReasoningLevel(provider: ProviderSetting? = null): ReasoningLevel {
    val options = reasoningOptions(provider)
    val preferred = reasoningFamily(provider).defaultLevel()
    return options.firstOrNull { it.level == preferred }?.level
        ?: options.firstOrNull()?.level
        ?: ReasoningLevel.AUTO
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
                        "minimax" in endpoint -> "minimax"
                        "api.x.ai" in endpoint -> "xai"
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

internal fun String.normalizedModelId(): String =
    lowercase().replace('_', '-').replace(' ', '-')

internal fun String.isKimiK3(): Boolean {
    val id = normalizedModelId()
    return id.contains("kimi-k3") || id.endsWith("/k3") || id.contains("kimi/k3")
}

internal fun String.isKimiK27Code(): Boolean {
    val id = normalizedModelId()
    return id.contains("k2.7-code") || id.contains("k2-7-code")
}

internal fun String.isGlm53(): Boolean {
    val id = normalizedModelId()
    return id.contains("glm-5.3") || id.contains("glm-5-3")
}

internal fun String.isGlm52(): Boolean {
    val id = normalizedModelId()
    return id.contains("glm-5.2") || id.contains("glm-5-2")
}

internal fun String.isGlm5Effort(): Boolean {
    val id = normalizedModelId()
    if (id.isGlm53() || id.isGlm52()) return false
    return id.contains("glm-5.1") || id.contains("glm-5-1") ||
        Regex("""(^|[^0-9])glm-5([^0-9.]|$)""").containsMatchIn(id)
}

internal fun String.isDeepSeekV4(): Boolean {
    val id = normalizedModelId()
    return id.contains("deepseek") && (id.contains("v4") || id.contains("-4-"))
}

internal fun String.isQwen38Max(): Boolean {
    val id = normalizedModelId()
    return id.contains("qwen") && id.contains("max") &&
        (id.contains("3.8") || id.contains("3-8"))
}

internal fun String.isMiniMaxM3(): Boolean {
    val id = normalizedModelId()
    return (id.contains("minimax") || id.contains("mini-max")) &&
        Regex("""(^|[^0-9])m3([^0-9]|$)""").containsMatchIn(id)
}

internal fun String.isMiniMaxM2(): Boolean {
    val id = normalizedModelId()
    return (id.contains("minimax") || id.contains("mini-max")) &&
        Regex("""(^|[^0-9])m2([^0-9]|$)""").containsMatchIn(id)
}

internal fun String.isGrok46(): Boolean {
    val id = normalizedModelId()
    return id.contains("grok-4.6") || id.contains("grok-4-6")
}

internal fun String.isGrok45(): Boolean {
    val id = normalizedModelId()
    return id.contains("grok-4.5") || id.contains("grok-4-5")
}

internal fun String.isClaudeOpus47(): Boolean {
    val id = normalizedModelId()
    return id.contains("opus") && (id.contains("4-7") || id.contains("4.7"))
}

internal fun String.isClaudeCurrentFull(): Boolean {
    val id = normalizedModelId()
    if (id.contains("opus-5") || id.contains("sonnet-5") || id.contains("haiku-5")) return true
    if (id.contains("opus") && (id.contains("4-8") || id.contains("4.8"))) return true
    return false
}

internal fun String.geminiFamily(): ReasoningFamily {
    val id = normalizedModelId()
    return when {
        id.contains("3.7") && id.contains("flash") -> ReasoningFamily.GEMINI_LOW_MEDIUM_HIGH
        id.contains("3.1") && id.contains("flash-lite") && id.contains("image") ->
            ReasoningFamily.GEMINI_MINIMAL_HIGH
        id.contains("2.5") && id.contains("flash-lite") -> ReasoningFamily.GEMINI_OFF_LOW_MEDIUM_HIGH
        id.contains("gemini-3-pro") && !id.contains("3.1") -> ReasoningFamily.GEMINI_LOW_HIGH
        id.contains("3.1") && id.contains("pro") -> ReasoningFamily.GEMINI_LOW_MEDIUM_HIGH
        id.contains("3.6") || (id.contains("3.5") && id.contains("flash")) ||
            id.contains("gemini-3-flash") -> ReasoningFamily.GEMINI_MINIMAL_LOW_MEDIUM_HIGH
        id.contains("2.5") -> ReasoningFamily.GEMINI_LOW_MEDIUM_HIGH
        else -> ReasoningFamily.GEMINI
    }
}

internal fun ProviderSetting?.isGrokWebEndpoint(): Boolean {
    val openAI = this as? ProviderSetting.OpenAI ?: return false
    val host = openAI.baseUrl.hostOfBaseUrl()
    return host == "grok.com"
}

internal fun ProviderSetting?.isXaiApiEndpoint(): Boolean {
    val openAI = this as? ProviderSetting.OpenAI ?: return false
    val host = openAI.baseUrl.hostOfBaseUrl()
    val name = openAI.name.lowercase()
    return host == "api.x.ai" || host == "cli-chat-proxy.grok.com" || name == "xai" || name == "x.ai"
}

internal fun String.hostOfBaseUrl(): String {
    val afterScheme = substringAfter("://", this)
    val authority = afterScheme.substringBefore('/')
    return authority.substringBefore('?').substringBefore('#')
        .substringAfter('@')
        .substringBefore(':')
        .lowercase()
}

private fun String.isQwenPlusBinaryReasoningModel(): Boolean {
    if (!contains("qwen") || !contains("plus")) return false
    return Regex("""(^|[^0-9])3[._-]?5([^0-9]|$)""").containsMatchIn(this) ||
        Regex("""(^|[^0-9])3[._-]?6([^0-9]|$)""").containsMatchIn(this)
}
