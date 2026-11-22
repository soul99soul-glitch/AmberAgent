package app.amber.core.ai.tools

import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.GoogleProvider
import app.amber.ai.provider.providers.openai.OpenAIChatGptSiwcAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.ai.provider.providers.grok.GrokAuthStore
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.secret.SecretStore

/**
 * Agent 可控 Provider / Model 配置工具（对齐 iOS docs/IOS_AGENT_PROVIDER_CONFIG_PLAN.md §6，P0–P2）。
 *
 * 四工具：
 *  - provider_config_status      只读盘点（脱敏：无 key/token/header 值，base_url 只给 host）
 *  - provider_config_apply      单 provider 原子写（enabled/api_key/base_url/path/name；审批后执行）
 *  - provider_refresh_models    拉模型目录并 merge / replace_chat（复用 UI 设置页同款 listModels）
 *  - settings_set_model_slot    设默认 chat/title/ocr/compress/suggestion/image_generation 槽位
 *
 * 安全不变量：
 *  - tool result 永不含 apiKey/token/header 值；key 只以 has_api_key / api_key_status 状态出现。
 *  - base_url 变更必须 https（拒绝其它 scheme / userinfo），且 apply 走 High 风险审批。
 *  - apply 校验全部通过才写（单 settings 原子写，失败不部分提交）。
 *  - api_key 拒绝明显 placeholder；空串 = 清除 key（审批文案在 schema 中强调）。
 *  - 写路径统一走 SettingsAggregator.update（自动获得 SecretStore redaction）。
 *  - 仅前台 Chat 注册（ChatService.createRunTools 按 conversationId != null 装配）；
 *    SubAgent / 后台任务工具集不含这四个（不进入 profiledRawTools，allowlist 亦不可达）。
 */
const val TOOL_PROVIDER_CONFIG_STATUS = "provider_config_status"
const val TOOL_PROVIDER_CONFIG_APPLY = "provider_config_apply"
const val TOOL_PROVIDER_REFRESH_MODELS = "provider_refresh_models"
const val TOOL_SETTINGS_SET_MODEL_SLOT = "settings_set_model_slot"

val PROVIDER_CONFIG_TOOL_NAMES = setOf(
    TOOL_PROVIDER_CONFIG_STATUS,
    TOOL_PROVIDER_CONFIG_APPLY,
    TOOL_PROVIDER_REFRESH_MODELS,
    TOOL_SETTINGS_SET_MODEL_SLOT,
)

/** 拉模型目录的注入点 —— 生产默认走 ProviderCatalog（与 UI 设置页同路径），测试注入 fake。 */
fun interface ProviderModelFetcher {
    suspend fun fetch(provider: ProviderSetting): List<Model>
}

fun createProviderConfigTools(
    settingsStore: SettingsAggregator,
    secretStore: SecretStore,
    providerCatalog: ProviderCatalog,
    googleProvider: GoogleProvider,
    modelFetcher: ProviderModelFetcher = ProviderModelFetcher { provider ->
        if (provider is ProviderSetting.OpenAI) providerCatalog.listModels(provider)
        else providerCatalog.text(provider).listModels(provider)
    },
    openAICodexAuthStore: OpenAICodexAuthStore? = null,
    grokAuthStore: GrokAuthStore? = null,
    antigravityAuthStore: app.amber.ai.provider.providers.google.AntigravityAuthStore? = null,
    openAIChatGptSiwcAuthStore: OpenAIChatGptSiwcAuthStore? = null,
): List<Tool> {
    val oauthStatusResolver = managedProviderAuthStatusResolver(
        googleProvider = googleProvider,
        openAICodexAuthStore = openAICodexAuthStore,
        grokAuthStore = grokAuthStore,
        openAIChatGptSiwcAuthStore = openAIChatGptSiwcAuthStore,
    )
    return listOf(
        providerConfigStatusTool(settingsStore, secretStore, oauthStatusResolver),
        providerConfigApplyTool(settingsStore, secretStore),
        providerRefreshModelsTool(settingsStore, modelFetcher),
        settingsSetModelSlotTool(settingsStore),
    )
}
