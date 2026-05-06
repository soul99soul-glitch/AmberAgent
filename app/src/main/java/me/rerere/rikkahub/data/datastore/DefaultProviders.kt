package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

val DEFAULT_AUTO_MODEL_ID = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08")

private val OpenAIProviderId = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e")
private val GeminiProviderId = Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d")
private val DeepSeekProviderId = Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b")
private val OpenRouterProviderId = Uuid.parse("d5734028-d39b-4d41-9841-fd648d65440e")
private val MoonshotProviderId = Uuid.parse("d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3")
private val XAIProviderId = Uuid.parse("ff3cde7e-0f65-43d7-8fb2-6475c99f5990")

val REMOVED_DEFAULT_PROVIDER_IDS = setOf(
    Uuid.parse("a8d2d463-e8c0-41f2-b89e-f5eb8e716cce"), // AmberAgent / legacy RikkaHub
    Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953"), // AiHubMix
    Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8"), // SiliconFlow
    Uuid.parse("386e0f29-8228-4512-affe-8fd8add82d88"), // Vercel AI Gateway
    Uuid.parse("da020a90-f7b3-4c29-b90e-c511a0630630"), // TokenPony
    Uuid.parse("f76cae46-069a-4334-ab8e-224e4979e58c"), // Alibaba Bailian
    Uuid.parse("3dfd6f9b-f9d9-417f-80c1-ff8d77184191"), // Volcengine
    Uuid.parse("3bc40dc1-b11a-46fa-863b-6306971223be"), // Zhipu
    Uuid.parse("f4f8870e-82d3-495b-9b64-d58e508b3b2c"), // StepFun
    Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7"), // 302.AI
    Uuid.parse("ef5d149b-8e34-404b-818c-6ec242e5c3c5"), // Tencent Hunyuan
    Uuid.parse("53027b08-1b58-43d5-90ed-29173203e3d8"), // AckAI
    Uuid.parse("4da09554-8844-4cc8-a4a9-fe1b2515e91b"), // UnifyLLM
)

val DEFAULT_PROVIDERS = listOf(
    ProviderSetting.OpenAI(
        id = OpenAIProviderId,
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
    ),
    ProviderSetting.Google(
        id = GeminiProviderId,
        name = "Gemini",
        apiKey = "",
        enabled = true,
    ),
    ProviderSetting.OpenAI(
        id = DeepSeekProviderId,
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "",
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/user/balance",
            resultPath = "balance_infos[0].total_balance",
        ),
    ),
    ProviderSetting.OpenAI(
        id = OpenRouterProviderId,
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = "",
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/credits",
            resultPath = "data.total_credits - data.total_usage",
        ),
    ),
    ProviderSetting.OpenAI(
        id = MoonshotProviderId,
        name = "月之暗面",
        baseUrl = "https://api.moonshot.cn/v1",
        apiKey = "",
        enabled = false,
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/users/me/balance",
            resultPath = "data.available_balance",
        ),
    ),
    ProviderSetting.OpenAI(
        id = XAIProviderId,
        name = "xAI",
        baseUrl = "https://api.x.ai/v1",
        apiKey = "",
        enabled = false,
        useResponseApi = true,
    ),
)
