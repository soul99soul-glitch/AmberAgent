package app.amber.core.settings

import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.OpenAIBrand
import me.rerere.ai.provider.ProviderSetting
import app.amber.core.model.QuickMessage
import kotlin.uuid.Uuid

// Stable UUIDs for the two image-generation models we seed into the built-in
// OpenAI and Gemini providers. Stable so the per-load migration that adds the
// model to existing user providers can detect "already added by us" without
// matching on modelId (users could later rename it).
internal val SeedOpenAIImageModelId = Uuid.parse("c7e8a911-3b4d-4f2a-8c1e-0d7f2a3b4c5d")
internal val SeedGeminiImageModelId = Uuid.parse("c7e8a911-3b4d-4f2a-8c1e-0d7f2a3b4c5e")

/**
 * gpt-image-2 entry seeded into the built-in OpenAI provider. Set as
 * ModelType.IMAGE so it appears in the new assistant "生图模型" picker
 * (ModelType.IMAGE filter) and does NOT pollute the chat-model dropdown.
 * Output modality is IMAGE only — input is the user's text prompt.
 */
internal val SeedOpenAIImageModel = Model(
    id = SeedOpenAIImageModelId,
    modelId = "gpt-image-2",
    displayName = "gpt-image-2",
    type = ModelType.IMAGE,
    inputModalities = listOf(Modality.TEXT),
    outputModalities = listOf(Modality.IMAGE),
)

/**
 * gemini-3.1-flash-image-preview (Nano Banana 2) seeded into the built-in
 * Gemini API-Key provider. Same rationale as SeedOpenAIImageModel. NOT added
 * to the Gemini OAuth fallback list — cloudcode-pa is a chat-only endpoint
 * and image generation requires the standard generativelanguage.googleapis.com
 * route, which only the API-Key auth mode hits.
 */
internal val SeedGeminiImageModel = Model(
    id = SeedGeminiImageModelId,
    modelId = "gemini-3.1-flash-image-preview",
    displayName = "Gemini 3.1 Flash Image (Nano Banana 2)",
    type = ModelType.IMAGE,
    inputModalities = listOf(Modality.TEXT),
    outputModalities = listOf(Modality.IMAGE),
)

// Stable UUIDs for the three visual-routing slash commands. Each command's
// content prefixes a route tag the GenerativeUiPlanner picks up at Layer 0,
// forcing the corresponding render path regardless of the prompt's wording.
//   /draw    → [ROUTE:image]   → generate_image tool
//   /diagram → [ROUTE:diagram] → show-widget SVG
//   /slide   → [ROUTE:slides]  → show-widget slides renderer
//
// Trailing newline so the cursor lands on a fresh line after the tag, and
// the user can type their prompt unprefixed.
internal val SeedDrawQuickMessageId = Uuid.parse("ce1f8a2b-7d3c-4e9f-b210-1a3b5c7d9e01")
internal val SeedDiagramQuickMessageId = Uuid.parse("ce1f8a2b-7d3c-4e9f-b210-1a3b5c7d9e02")
internal val SeedSlideQuickMessageId = Uuid.parse("ce1f8a2b-7d3c-4e9f-b210-1a3b5c7d9e03")

internal val SeedRoutingQuickMessages: List<QuickMessage> = listOf(
    QuickMessage(
        id = SeedDrawQuickMessageId,
        title = "draw",
        content = "[ROUTE:image]\n",
    ),
    QuickMessage(
        id = SeedDiagramQuickMessageId,
        title = "diagram",
        content = "[ROUTE:diagram]\n",
    ),
    QuickMessage(
        id = SeedSlideQuickMessageId,
        title = "slide",
        content = "[ROUTE:slides]\n",
    ),
)

val DEFAULT_AUTO_MODEL_ID = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08")

// Exposed so the per-load migration in PreferencesStore can target these two
// specific providers when backfilling the seeded image models. The other
// brand-IDs below remain private — they have no migration tied to them.
internal val OpenAIProviderIdRef = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e")
internal val GeminiProviderIdRef = Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d")
private val OpenAIProviderId = OpenAIProviderIdRef
private val GeminiProviderId = GeminiProviderIdRef
private val DeepSeekProviderId = Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b")
private val OpenRouterProviderId = Uuid.parse("d5734028-d39b-4d41-9841-fd648d65440e")
private val MoonshotProviderId = Uuid.parse("d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3")
private val XAIProviderId = Uuid.parse("ff3cde7e-0f65-43d7-8fb2-6475c99f5990")
// New brand-tagged providers added with the OpenAIBrand work. Use fresh UUIDs (not the old
// removed-zhipu uuid) so users who explicitly removed the previous Zhipu entry don't get
// it resurrected.
private val ZhipuProviderId = Uuid.parse("9f3a6b2c-7d4e-4810-9a2f-3e8b5d142c01")
private val MimoProviderId = Uuid.parse("9f3a6b2c-7d4e-4810-9a2f-3e8b5d142c02")

val REMOVED_DEFAULT_PROVIDER_IDS = setOf(
    Uuid.parse("a8d2d463-e8c0-41f2-b89e-f5eb8e716cce"), // AmberAgent / legacy RikkaHub
    Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953"), // AiHubMix
    Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8"), // SiliconFlow
    Uuid.parse("386e0f29-8228-4512-affe-8fd8add82d88"), // Vercel AI Gateway
    Uuid.parse("da020a90-f7b3-4c29-b90e-c511a0630630"), // TokenPony
    Uuid.parse("f76cae46-069a-4334-ab8e-224e4979e58c"), // Alibaba Bailian
    Uuid.parse("3dfd6f9b-f9d9-417f-80c1-ff8d77184191"), // Volcengine
    Uuid.parse("3bc40dc1-b11a-46fa-863b-6306971223be"), // Zhipu (legacy id; new ZhipuProviderId is different)
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
        brand = OpenAIBrand.OPENAI,
        // Seed gpt-image-2 so first-launch users can pick it in the new
        // assistant "生图模型" field without hunting for the model ID. The
        // migration in PreferencesStore re-adds this for existing users
        // whose OpenAI provider was created before this commit.
        models = listOf(SeedOpenAIImageModel),
    ),
    ProviderSetting.Google(
        id = GeminiProviderId,
        name = "Gemini",
        apiKey = "",
        enabled = true,
        // Seed Nano Banana 2 (gemini-3.1-flash-image-preview) for the same
        // reason. Image generation only works via the API-Key route, not the
        // separate Gemini OAuth provider — see GoogleGeminiOAuth comments.
        models = listOf(SeedGeminiImageModel),
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
        brand = OpenAIBrand.DEEPSEEK,
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
        // OpenRouter is a multi-provider gateway, not a brand with its own auth modes.
        brand = OpenAIBrand.GENERIC,
    ),
    ProviderSetting.OpenAI(
        id = MoonshotProviderId,
        name = "月之暗面 (Kimi)",
        baseUrl = "https://api.moonshot.cn/v1",
        apiKey = "",
        enabled = false,
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/users/me/balance",
            resultPath = "data.available_balance",
        ),
        brand = OpenAIBrand.KIMI,
    ),
    ProviderSetting.OpenAI(
        id = ZhipuProviderId,
        name = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiKey = "",
        enabled = false,
        brand = OpenAIBrand.ZHIPU,
    ),
    ProviderSetting.OpenAI(
        id = MimoProviderId,
        name = "小米 MiMo",
        baseUrl = "https://api.xiaomi.com/v1",  // placeholder API base; user can override
        apiKey = "",
        enabled = false,
        brand = OpenAIBrand.MIMO,
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
