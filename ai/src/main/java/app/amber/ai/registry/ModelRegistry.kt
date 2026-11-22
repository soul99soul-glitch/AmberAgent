package app.amber.ai.registry

import app.amber.ai.provider.Modality
import app.amber.ai.provider.ModelAbility

fun interface ModelData<T> {
    fun getData(modelId: String): T
}

data class ModelSessionDefaultGroup(
    val id: String,
    val label: String,
    val selector: ModelSelector,
)

object ModelRegistry {
    private val GPT4O = defineModel {
        tokens("gpt", "4", "o")
        visionInput()
        toolAbility()
        contextWindow(128_000)
    }

    private val GPT_4_1 = defineModel {
        tokens("gpt", "4", "1")
        visionInput()
        toolAbility()
        contextWindow(1_000_000)
    }

    val OPENAI_O_MODELS = defineModel {
        tokens(tokenRegex("^o$"), tokenRegex("^\\d+$"))
        visionInput()
        toolReasoningAbility()
        contextWindow(200_000)
    }

    private val GPT_OSS = defineModel {
        tokens("gpt", "oss")
        toolReasoningAbility()
        contextWindow(131_072)
    }

    val GPT_5 = defineModel {
        tokens("gpt", "5")
        notTokens("gpt", "5", ".")
        notTokens("gpt", "5", "chat")
        visionInput()
        toolReasoningAbility()
        contextWindow(400_000)
    }

    private val GPT_5_CHAT = defineModel {
        tokens("gpt", "5", "chat")
        visionInput()
        toolAbility()
        contextWindow(128_000)
    }

    private val GPT_5_1 = defineModel {
        tokens("gpt", "5", "1")
        visionInput()
        toolReasoningAbility()
        contextWindow(400_000)
    }

    private val GPT_5_2 = defineModel {
        tokens("gpt", "5", "2")
        visionInput()
        toolReasoningAbility()
        contextWindow(400_000)
    }

    private val GPT_5_3 = defineModel {
        tokens("gpt", "5", "3")
        visionInput()
        toolAbility()
        contextWindow(400_000)
    }

    private val GPT_5_4 = defineModel {
        tokens("gpt", "5", "4")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_050_000)
    }

    private val GPT_5_4_MINI = defineModel {
        tokens("gpt", "5", "4", "mini")
        visionInput()
        toolReasoningAbility()
        contextWindow(400_000)
    }

    private val GPT_5_4_NANO = defineModel {
        tokens("gpt", "5", "4", "nano")
        visionInput()
        toolReasoningAbility()
        contextWindow(400_000)
    }

    private val GPT_5_5 = defineModel {
        tokens("gpt", "5", "5")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_050_000)
    }

    private val GPT_5_6 = defineModel {
        tokens("gpt", "5", "6")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_050_000)
    }

    private val GPT_6 = defineModel {
        tokens("gpt", "6")
        notTokens("gpt", "4")
        notTokens("gpt", "5")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_047_576)
    }

    private val GPT_6_ASTRA = defineModel {
        tokens("gpt", "6", "astra")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_047_576)
    }

    private val GPT_6_SOL = defineModel {
        tokens("gpt", "6", "sol")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_047_576)
    }

    private val GPT_6_TERRA = defineModel {
        tokens("gpt", "6", "terra")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_047_576)
    }

    private val GPT_6_LUNA = defineModel {
        tokens("gpt", "6", "luna")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_047_576)
    }

    private val GEMINI_20_FLASH = defineModel {
        tokens("gemini", "2", "0", "flash")
        visionInput()
        toolAbility()
        contextWindow(1_000_000)
    }

    val GEMINI_2_5_FLASH = defineModel {
        tokens("gemini", "2", "5", "flash")
        notTokens("image")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    val GEMINI_2_5_PRO = defineModel {
        tokens("gemini", "2", "5", "pro")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    val GEMINI_2_5_IMAGE = defineModel {
        tokens("gemini", "2", "5", "flash", "image")
        visionInput()
        imageOutput()
    }

    val GEMINI_3_PRO_IMAGE = defineModel {
        tokens("gemini", "3", "pro", "image")
        visionInput()
        imageOutput()
    }

    val GEMINI_NANO_BANANA = defineModel {
        tokens("nano", "banana")
        visionInput()
        imageOutput()
    }

    val GEMINI_3_PRO = defineModel {
        tokens("gemini", "3", "pro")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    val GEMINI_3_FLASH = defineModel {
        tokens("gemini", "3", "flash")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    val GEMINI_3_1_PRO = defineModel {
        tokens("gemini", "3", "1", "pro")
        notTokens("image")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(1_048_576)
    }

    val GEMINI_3_1_PRO_PREVIEW = defineModel {
        tokens("gemini", "3", "1", "pro", "preview")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(1_048_576)
    }

    val GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS = defineModel {
        tokens("gemini", "3", "1", "pro", "preview", "customtools")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(1_048_576)
    }

    val GEMINI_3_1_FLASH = defineModel {
        tokens("gemini", "3", "1", "flash")
        notTokens("image")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(1_048_576)
    }

    val GEMINI_3_1_FLASH_IMAGE = defineModel {
        tokens("gemini", "3", "1", "flash", "image")
        visionInput()
        imageOutput()
        reasoningAbility()
    }

    private val GEMINI_3_5 = defineModel {
        tokens("gemini", "3", "5")
        notTokens("image")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(1_048_576)
    }

    private val GEMINI_3_8 = defineModel {
        tokens("gemini", "3", "8")
        notTokens("image")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(1_048_576)
    }

    private val GEMINI_4 = defineModel {
        tokens("gemini", "4")
        notTokens("image")
        notTokens("gemini", "2")
        notTokens("gemini", "3")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(1_048_576)
    }

    private val GEMINI_DECISION_1 = defineModel {
        tokens("gemini", "decision", "1")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(256_000)
    }

    val GEMINI_FLASH_LATEST = defineModel {
        exact("gemini-flash-latest")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    val GEMINI_PRO_LATEST = defineModel {
        exact("gemini-pro-latest")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    val GEMINI_LATEST = defineGroup {
        add(GEMINI_FLASH_LATEST, GEMINI_PRO_LATEST)
    }

    val GEMINI_3_SERIES = defineGroup {
        add(
            GEMINI_3_PRO,
            GEMINI_3_FLASH,
            GEMINI_3_1_PRO,
            GEMINI_3_1_PRO_PREVIEW,
            GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS,
            GEMINI_3_1_FLASH,
            GEMINI_3_5,
            GEMINI_3_8,
        )
    }

    val GEMINI_SERIES = defineGroup {
        add(
            GEMINI_20_FLASH,
            GEMINI_2_5_FLASH,
            GEMINI_2_5_PRO,
            GEMINI_3_SERIES,
            GEMINI_4,
            GEMINI_DECISION_1,
            GEMINI_LATEST,
        )
    }

    private val CLAUDE_SONNET_3_5 = defineModel {
        tokens("claude", "3", "5", "sonnet")
        visionInput()
        toolReasoningAbility()
        contextWindow(200_000)
    }

    private val CLAUDE_SONNET_3_7 = defineModel {
        tokens("claude", "3", "7", "sonnet")
        visionInput()
        toolReasoningAbility()
        contextWindow(200_000)
    }

    private val CLAUDE_4 = defineModel {
        tokens("claude", "4")
        visionInput()
        toolReasoningAbility()
        contextWindow(200_000)
    }

    val CLAUDE_4_5 = defineModel {
        tokens("claude", "4", "5")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val CLAUDE_HAIKU_4_5 = defineModel {
        tokens("claude", "haiku", "4", "5")
        visionInput()
        toolReasoningAbility()
        contextWindow(200_000)
    }

    private val CLAUDE_4_5_HAIKU = defineModel {
        tokens("claude", "4", "5", "haiku")
        visionInput()
        toolReasoningAbility()
        contextWindow(200_000)
    }

    private val CLAUDE_4_6 = defineModel {
        tokens("claude", "4", "6")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val CLAUDE_SONNET_4_6 = defineModel {
        tokens("claude", "sonnet", "4", "6")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val CLAUDE_OPUS_4_6 = defineModel {
        tokens("claude", "opus", "4", "6")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val CLAUDE_4_7 = defineModel {
        tokens("claude", "4", "7")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val CLAUDE_OPUS_4_7 = defineModel {
        tokens("claude", "opus", "4", "7")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val CLAUDE_5 = defineModel {
        tokens("claude", "5")
        notTokens("claude", "3")
        notTokens("claude", "4")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val CLAUDE_FABLE_5 = defineModel {
        tokens("claude", "fable", "5")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val CLAUDE_MYTHOS_5 = defineModel {
        tokens("claude", "mythos", "5")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    val CLAUDE_SERIES = defineGroup {
        add(
            CLAUDE_SONNET_3_5,
            CLAUDE_SONNET_3_7,
            CLAUDE_4,
            CLAUDE_4_5,
            CLAUDE_HAIKU_4_5,
            CLAUDE_4_5_HAIKU,
            CLAUDE_4_6,
            CLAUDE_SONNET_4_6,
            CLAUDE_OPUS_4_6,
            CLAUDE_4_7,
            CLAUDE_OPUS_4_7,
            CLAUDE_5,
            CLAUDE_FABLE_5,
            CLAUDE_MYTHOS_5,
        )
    }

    private val DEEPSEEK_V3_MODEL = defineModel {
        tokens("deepseek", "v", "3")
        toolAbility()
        contextWindow(128_000)
    }

    private val DEEPSEEK_CHAT = defineModel {
        tokens("deepseek", "chat")
        toolAbility()
        contextWindow(1_000_000)
    }

    private val DEEPSEEK_R1_MODEL = defineModel {
        tokens("deepseek", "r", "1")
        toolReasoningAbility()
        contextWindow(128_000)
    }

    private val DEEPSEEK_REASONER = defineModel {
        tokens("deepseek", "reasoner")
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val DEEPSEEK_V4 = defineModel {
        tokens("deepseek", "v", "4")
        notTokens("deepseek", "v", "4", "1")
        notTokens("deepseek", "v", "4", "flash")
        notTokens("deepseek", "v", "4", "pro")
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val DEEPSEEK_V4_FLASH = defineModel {
        tokens("deepseek", "v", "4", "flash")
        notTokens("deepseek", "v", "4", "1")
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val DEEPSEEK_V4_1 = defineModel {
        tokens("deepseek", "v", "4", "1")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val DEEPSEEK_V4_1_FLASH = defineModel {
        tokens("deepseek", "v", "4", "1", "flash")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    // Official `deepseek-flash` alias migrated to DeepSeek-V4.1-Flash (native multimodal, 1M).
    private val DEEPSEEK_FLASH_ALIAS = defineModel {
        exact("deepseek-flash")
        input(*DEEPSEEK_V4_1_FLASH.inputModalities.toTypedArray())
        output(*DEEPSEEK_V4_1_FLASH.outputModalities.toTypedArray())
        ability(*DEEPSEEK_V4_1_FLASH.abilities.toTypedArray())
        DEEPSEEK_V4_1_FLASH.contextWindowTokens?.let(::contextWindow)
    }

    private val DEEPSEEK_V4_PRO = defineModel {
        tokens("deepseek", "v", "4", "pro")
        notTokens("deepseek", "v", "4", "1")
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val DEEPSEEK_R1 = defineGroup {
        add(DEEPSEEK_R1_MODEL, DEEPSEEK_REASONER)
    }

    private val DEEPSEEK_V3_1 = defineModel {
        tokens("deepseek", "v", "3", "1")
        toolReasoningAbility()
        contextWindow(128_000)
    }

    private val DEEPSEEK_V3_2 = defineModel {
        tokens("deepseek", "v", "3", "2")
        toolReasoningAbility()
        contextWindow(128_000)
    }

    private val QWEN_3_CODER_1M = defineModel {
        tokens("qwen", "3", "coder", "plus|flash")
        toolAbility()
        contextWindow(1_000_000)
    }

    private val QWEN_3_CODER = defineModel {
        tokens("qwen", "3", "coder")
        toolAbility()
        contextWindow(256_000)
    }

    private val QWEN_VL = defineModel {
        tokens("qwen", "vl")
        notTokens("qwen", "3")
        notTokens("qwen", "4")
        visionInput()
        toolAbility()
        contextWindow(128_000)
    }

    private val QWEN_3_VL = defineModel {
        tokens("qwen", "3", "vl")
        visionInput()
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val QWEN_3_6_1M = defineModel {
        tokens("qwen", "3", "6", "plus|flash")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val QWEN_3_6_MAX = defineModel {
        tokens("qwen", "3", "6", "max")
        visionInput()
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val QWEN_3_5_1M = defineModel {
        tokens("qwen", "3", "5", "plus|flash")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val QWEN_3_MAX = defineModel {
        tokens("qwen", "3", "max")
        notTokens("qwen", "3", "5|6|7|8")
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val QWEN_3 = defineModel {
        tokens("qwen", "3")
        toolReasoningAbility()
        contextWindow(128_000)
    }

    private val QWEN_3_5 = defineModel {
        tokens("qwen", "3", "5")
        visionInput()
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val QWEN_3_6 = defineModel {
        tokens("qwen", "3", "6")
        visionInput()
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val QWEN_3_7 = defineModel {
        tokens("qwen", "3", "7")
        notTokens("omni")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val QWEN_3_8 = defineModel {
        tokens("qwen", "3", "8")
        notTokens("omni")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val QWEN_3_8_OMNI = defineModel {
        tokens("qwen", "3", "8", "omni")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(65_536)
    }

    private val QWEN_4 = defineModel {
        tokens("qwen", "4")
        notTokens("omni")
        notTokens("qwen", "3")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val QWEN_OMNI = defineModel {
        tokens("qwen", "omni")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(65_536)
    }

    private val QWEN_PLUS = defineModel {
        tokens("qwen", "plus")
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val QWEN_FLASH = defineModel {
        tokens("qwen", "flash")
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val QWEN_TURBO = defineModel {
        tokens("qwen", "turbo")
        toolReasoningAbility()
        contextWindow(128_000)
    }

    private val QWEN_MAX = defineModel {
        tokens("qwen", "max")
        notTokens("qwen", "4")
        toolAbility()
        contextWindow(32_000)
    }

    private val DOUBAO_1_6 = defineModel {
        tokens("doubao", "1", "6")
        visionInput()
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val DOUBAO_1_8 = defineModel {
        tokens("doubao", "1", "8")
        visionInput()
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val DOUBAO_SEED_2_0 = defineModel {
        tokens("seed", "2", "0")
        visionInput()
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val DOUBAO_SEED_2_1 = defineModel {
        tokens("seed", "2", "1")
        notTokens("turbo")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val DOUBAO_SEED_2_1_TURBO = defineModel {
        tokens("seed", "2", "1", "turbo")
        visionInput()
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val DOUBAO_SEED_EVOLVING = defineModel {
        tokens("doubao", "seed", "evolving")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val GROK_3 = defineModel {
        tokens("grok", "3")
        notTokens("grok", "4")
        toolReasoningAbility()
        contextWindow(131_072)
    }

    private val GROK_4 = defineModel {
        tokens("grok", "4")
        visionInput()
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val GROK_4_LONG = defineModel {
        tokens("grok", "4", "2|20|3")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val GROK_4_5_TO_7 = defineModel {
        tokens("grok", "4", "5|6|7")
        visionInput()
        toolReasoningAbility()
        contextWindow(500_000)
    }

    private val GROK_BUILD = defineModel {
        tokens("grok", "build")
        visionInput()
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val KIMI_K2 = defineModel {
        tokens("kimi", "k", "2")
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val KIMI_K2_5 = defineModel {
        tokens("kimi", "k", "2", "5")
        visionInput()
        toolReasoningAbility()
        contextWindow(262_144)
    }

    val KIMI_K2_6 = defineModel {
        tokens("kimi", "k", "2", "6")
        visionInput()
        toolReasoningAbility()
        contextWindow(262_144)
    }

    private val KIMI_K2_7 = defineModel {
        tokens("kimi", "k", "2", "7")
        visionInput()
        toolReasoningAbility()
        contextWindow(262_144)
    }

    val KIMI_K3 = defineModel {
        tokens("kimi", "k", "3")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_048_576)
    }

    // 兼容不带 kimi 前缀的裸 id "k3"
    val KIMI_K3_ALIAS = defineModel {
        exact("k3")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_048_576)
    }

    // Kimi Coding Plan default model (e.g. modelId "kimi-for-coding").
    // Token order matches "kimi" → ... → "coding"; matches both
    // "kimi-for-coding" and "kimi-coding-*" variants. KIMI_K2_* below
    // takes priority for ids that include "k2" thanks to higher score.
    private val KIMI_FOR_CODING = defineModel {
        tokens("kimi", "coding")
        visionInput()
        toolReasoningAbility()
        contextWindow(262_144)
    }

    private val STEP_3 = defineModel {
        tokens("step", "3")
        visionInput()
        toolReasoningAbility()
    }

    private val INTERN_S1 = defineModel {
        tokens("intern", "s", "1")
        visionInput()
        toolReasoningAbility()
    }

    private val GLM_4_5 = defineModel {
        tokens("glm", "4", "5")
        toolReasoningAbility()
        contextWindow(128_000)
    }

    private val GLM_4_5_V = defineModel {
        tokens("glm", "4", "5", "v")
        visionInput()
        toolReasoningAbility()
        contextWindow(128_000)
    }

    private val GLM_4_6 = defineModel {
        tokens("glm", "4", "6")
        toolReasoningAbility()
        contextWindow(200_000)
    }

    private val GLM_4_6_V = defineModel {
        tokens("glm", "4", "6", "v")
        visionInput()
        toolReasoningAbility()
        contextWindow(200_000)
    }

    private val GLM_4_7 = defineModel {
        tokens("glm", "4", "7")
        toolReasoningAbility()
        contextWindow(200_000)
    }

    private val GLM_5 = defineModel {
        tokens("glm", "5")
        toolReasoningAbility()
        contextWindow(200_000)
    }

    private val GLM_5_V = defineModel {
        tokens("glm", "5", "v")
        visionInput()
        toolReasoningAbility()
        contextWindow(200_000)
    }

    private val GLM_5_1 = defineModel {
        tokens("glm", "5", "1")
        toolReasoningAbility()
        contextWindow(200_000)
    }

    private val GLM_5_2 = defineModel {
        tokens("glm", "5", "2")
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val GLM_5_3 = defineModel {
        tokens("glm", "5", "3")
        notTokens("glm", "5", "3", "flash|flashx")
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val GLM_5_3_FLASH = defineModel {
        tokens("glm", "5", "3", "flash|flashx")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val MINIMAX_M2 = defineModel {
        tokens("minimax", "m", "2")
        toolReasoningAbility()
        contextWindow(200_000)
    }

    private val MINIMAX_M2_5 = defineModel {
        tokens("minimax", "m", "2", "5")
        toolReasoningAbility()
        contextWindow(200_000)
    }

    private val MINIMAX_M2_7 = defineModel {
        tokens("minimax", "m", "2", "7")
        toolReasoningAbility()
        contextWindow(204_800)
    }

    private val MINIMAX_M3 = defineModel {
        tokens("minimax", "m", "3")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val XIAOMI_MIMO_V2 = defineModel {
        tokens("mimo", "v", "2")
        toolReasoningAbility()
    }

    private val XIAOMI_MIMO_V2_PRO = defineModel {
        tokens("mimo", "v", "2", "pro")
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val XIAOMI_MIMO_V2_OMNI = defineModel {
        tokens("mimo", "v", "2", "omni")
        visionInput()
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val XIAOMI_MIMO_V2_FLASH = defineModel {
        tokens("mimo", "v", "2", "flash")
        visionInput()
        toolReasoningAbility()
        contextWindow(256_000)
    }

    private val XIAOMI_MIMO_V2_5 = defineModel {
        tokens("mimo", "v", "2", "5")
        visionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val XIAOMI_MIMO_V2_5_PRO = defineModel {
        tokens("mimo", "v", "2", "5", "pro")
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    private val XIAOMI_MIMO_V2_6 = defineModel {
        tokens("mimo", "v", "2", "6", "pro|flash")
        audioVisionInput()
        toolReasoningAbility()
        contextWindow(1_000_000)
    }

    val QWEN_MT = defineModel {
        tokens("qwen", "mt")
        contextWindow(16_000)
    }

    private val OPENAI_REASONING_SERIES = defineGroup {
        add(
            OPENAI_O_MODELS,
            GPT_OSS,
            GPT_5,
            GPT_5_1,
            GPT_5_2,
            GPT_5_4,
            GPT_5_4_MINI,
            GPT_5_4_NANO,
            GPT_5_5,
            GPT_5_6,
            GPT_6,
            GPT_6_ASTRA,
            GPT_6_SOL,
            GPT_6_TERRA,
            GPT_6_LUNA,
        )
    }

    private val DEEPSEEK_REASONING_SERIES = defineGroup {
        add(
            DEEPSEEK_R1,
            DEEPSEEK_V4,
            DEEPSEEK_V4_FLASH,
            DEEPSEEK_V4_1,
            DEEPSEEK_V4_1_FLASH,
            DEEPSEEK_FLASH_ALIAS,
            DEEPSEEK_V4_PRO,
            DEEPSEEK_V3_1,
            DEEPSEEK_V3_2,
        )
    }

    private val QWEN_REASONING_SERIES = defineGroup {
        add(QWEN_3, QWEN_3_5, QWEN_3_6, QWEN_3_7, QWEN_3_8, QWEN_3_8_OMNI, QWEN_4)
    }

    val SESSION_DEFAULT_GROUPS = listOf(
        ModelSessionDefaultGroup(
            id = "openai_reasoning",
            label = "OpenAI reasoning",
            selector = OPENAI_REASONING_SERIES,
        ),
        ModelSessionDefaultGroup(
            id = "claude",
            label = "Claude",
            selector = CLAUDE_SERIES,
        ),
        ModelSessionDefaultGroup(
            id = "gemini",
            label = "Gemini",
            selector = GEMINI_SERIES,
        ),
        ModelSessionDefaultGroup(
            id = "deepseek_reasoning",
            label = "DeepSeek reasoning",
            selector = DEEPSEEK_REASONING_SERIES,
        ),
        ModelSessionDefaultGroup(
            id = "qwen_reasoning",
            label = "Qwen reasoning",
            selector = QWEN_REASONING_SERIES,
        ),
    )

    fun sessionDefaultGroupForModel(modelId: String): ModelSessionDefaultGroup? =
        SESSION_DEFAULT_GROUPS.firstOrNull { it.selector.match(modelId) }

    private val ALL_MODELS = listOf(
        GPT4O,
        GPT_4_1,
        OPENAI_O_MODELS,
        GPT_OSS,
        GPT_5,
        GPT_5_CHAT,
        GPT_5_1,
        GPT_5_2,
        GPT_5_3,
        GPT_5_4,
        GPT_5_4_MINI,
        GPT_5_4_NANO,
        GPT_5_5,
        GPT_5_6,
        GPT_6,
        GPT_6_ASTRA,
        GPT_6_SOL,
        GPT_6_TERRA,
        GPT_6_LUNA,
        GEMINI_20_FLASH,
        GEMINI_2_5_FLASH,
        GEMINI_2_5_PRO,
        GEMINI_2_5_IMAGE,
        GEMINI_3_PRO_IMAGE,
        GEMINI_NANO_BANANA,
        GEMINI_3_PRO,
        GEMINI_3_FLASH,
        GEMINI_3_1_PRO,
        GEMINI_3_1_PRO_PREVIEW,
        GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS,
        GEMINI_3_1_FLASH,
        GEMINI_3_1_FLASH_IMAGE,
        GEMINI_3_5,
        GEMINI_3_8,
        GEMINI_4,
        GEMINI_DECISION_1,
        GEMINI_FLASH_LATEST,
        GEMINI_PRO_LATEST,
        CLAUDE_SONNET_3_5,
        CLAUDE_SONNET_3_7,
        CLAUDE_4,
        CLAUDE_4_5,
        CLAUDE_HAIKU_4_5,
        CLAUDE_4_5_HAIKU,
        CLAUDE_4_6,
        CLAUDE_SONNET_4_6,
        CLAUDE_OPUS_4_6,
        CLAUDE_4_7,
        CLAUDE_OPUS_4_7,
        CLAUDE_5,
        CLAUDE_FABLE_5,
        CLAUDE_MYTHOS_5,
        DEEPSEEK_V3_MODEL,
        DEEPSEEK_CHAT,
        DEEPSEEK_R1_MODEL,
        DEEPSEEK_REASONER,
        DEEPSEEK_V4,
        DEEPSEEK_V4_FLASH,
        DEEPSEEK_V4_1,
        DEEPSEEK_V4_1_FLASH,
        DEEPSEEK_FLASH_ALIAS,
        DEEPSEEK_V4_PRO,
        DEEPSEEK_V3_1,
        DEEPSEEK_V3_2,
        QWEN_3_CODER_1M,
        QWEN_3_CODER,
        QWEN_VL,
        QWEN_3_VL,
        QWEN_3_6_1M,
        QWEN_3_6_MAX,
        QWEN_3_5_1M,
        QWEN_3_MAX,
        QWEN_3,
        QWEN_3_5,
        QWEN_3_6,
        QWEN_3_7,
        QWEN_3_8,
        QWEN_3_8_OMNI,
        QWEN_4,
        QWEN_OMNI,
        QWEN_PLUS,
        QWEN_FLASH,
        QWEN_TURBO,
        QWEN_MAX,
        DOUBAO_1_6,
        DOUBAO_1_8,
        DOUBAO_SEED_2_0,
        DOUBAO_SEED_2_1,
        DOUBAO_SEED_2_1_TURBO,
        DOUBAO_SEED_EVOLVING,
        GROK_3,
        GROK_4,
        GROK_4_LONG,
        GROK_4_5_TO_7,
        GROK_BUILD,
        KIMI_K2,
        KIMI_K2_5,
        KIMI_K2_6,
        KIMI_K2_7,
        KIMI_K3,
        KIMI_K3_ALIAS,
        KIMI_FOR_CODING,
        STEP_3,
        INTERN_S1,
        GLM_4_5,
        GLM_4_5_V,
        GLM_4_6,
        GLM_4_6_V,
        GLM_4_7,
        GLM_5,
        GLM_5_V,
        GLM_5_1,
        GLM_5_2,
        GLM_5_3,
        GLM_5_3_FLASH,
        MINIMAX_M2,
        MINIMAX_M2_5,
        MINIMAX_M2_7,
        MINIMAX_M3,
        XIAOMI_MIMO_V2,
        XIAOMI_MIMO_V2_PRO,
        XIAOMI_MIMO_V2_OMNI,
        XIAOMI_MIMO_V2_FLASH,
        XIAOMI_MIMO_V2_5,
        XIAOMI_MIMO_V2_5_PRO,
        XIAOMI_MIMO_V2_6,
        QWEN_MT
    )

    val MODEL_INPUT_MODALITIES = ModelData { modelId ->
        resolveModalities(modelId) { it.inputModalities }
    }

    val MODEL_OUTPUT_MODALITIES = ModelData { modelId ->
        resolveModalities(modelId) { it.outputModalities }
    }

    val MODEL_ABILITIES = ModelData { modelId ->
        val abilities = resolveModels(modelId)
            .flatMap { it.abilities }
            .toSet()
        buildList {
            if (ModelAbility.TOOL in abilities) add(ModelAbility.TOOL)
            if (ModelAbility.REASONING in abilities) add(ModelAbility.REASONING)
        }
    }

    val MODEL_CONTEXT_WINDOW = ModelData { modelId ->
        resolveModels(modelId)
            .mapNotNull { it.contextWindowTokens }
            .maxOrNull()
    }

    private fun resolveModels(modelId: String): List<ModelDefinition> {
        var bestScore: Int? = null
        val matches = mutableListOf<ModelDefinition>()
        for (model in ALL_MODELS) {
            val score = model.matchScore(modelId) ?: continue
            when {
                bestScore == null || score > bestScore -> {
                    bestScore = score
                    matches.clear()
                    matches.add(model)
                }

                score == bestScore -> matches.add(model)
            }
        }
        return matches
    }

    private fun resolveModalities(
        modelId: String,
        selector: (ModelDefinition) -> Set<Modality>
    ): List<Modality> {
        val modalities = resolveModels(modelId)
            .flatMap { selector(it) }
            .toSet()
        return if (modalities.isEmpty()) {
            listOf(Modality.TEXT)
        } else {
            listOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO).filter { it in modalities }
        }
    }

    private fun ModelDefinitionBuilder.visionInput() {
        input(Modality.TEXT, Modality.IMAGE)
    }

    private fun ModelDefinitionBuilder.audioVisionInput() {
        input(Modality.TEXT, Modality.IMAGE, Modality.AUDIO)
    }

    private fun ModelDefinitionBuilder.imageOutput() {
        output(Modality.TEXT, Modality.IMAGE)
    }

    private fun ModelDefinitionBuilder.toolAbility() {
        ability(ModelAbility.TOOL)
    }

    private fun ModelDefinitionBuilder.reasoningAbility() {
        ability(ModelAbility.REASONING)
    }

    private fun ModelDefinitionBuilder.toolReasoningAbility() {
        ability(ModelAbility.TOOL, ModelAbility.REASONING)
    }
}
