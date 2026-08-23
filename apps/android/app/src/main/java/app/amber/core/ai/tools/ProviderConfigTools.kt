package app.amber.core.ai.tools

import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.hasUsableAuth
import app.amber.ai.provider.providers.GoogleProvider
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStatus
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStatusCode
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.DEFAULT_AUTO_MODEL_ID
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.secret.SecretDescriptor
import app.amber.core.settings.secret.SecretStore
import kotlin.uuid.Uuid

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

/** 拉模型目录的注入点 —— 生产默认走 ProviderManager（与 UI 设置页同路径），测试注入 fake。 */
fun interface ProviderModelFetcher {
    suspend fun fetch(provider: ProviderSetting): List<Model>
}

fun createProviderConfigTools(
    settingsStore: SettingsAggregator,
    secretStore: SecretStore,
    providerManager: ProviderManager,
    modelFetcher: ProviderModelFetcher = ProviderModelFetcher { provider ->
        providerManager.getProviderByType(provider).listModels(provider)
    },
): List<Tool> {
    // Google OAuth state lives in the provider's encrypted token store, not in
    // Settings/SecretStore. Keep the resolver here so status and refresh use the
    // same concrete provider that sends the request; no token value crosses the
    // tool boundary.
    val oauthStatusResolver: (ProviderSetting) -> GoogleGeminiAuthStatus? = { provider ->
        if (provider is ProviderSetting.Google) {
            runCatching {
                (providerManager.getProviderByType(provider) as? GoogleProvider)
                    ?.oauthAuthStatus(provider)
            }.getOrElse { GoogleGeminiAuthStatus.clientUnavailable() }
        } else {
            null
        }
    }
    return listOf(
        providerConfigStatusTool(settingsStore, secretStore, oauthStatusResolver),
        providerConfigApplyTool(settingsStore, secretStore),
        providerRefreshModelsTool(settingsStore, modelFetcher),
        settingsSetModelSlotTool(settingsStore),
    )
}

// ---------------------------------------------------------------------------
// provider_config_status
// ---------------------------------------------------------------------------

private fun providerConfigStatusTool(
    settingsStore: SettingsAggregator,
    secretStore: SecretStore,
    oauthStatusResolver: (ProviderSetting) -> GoogleGeminiAuthStatus?,
): Tool = Tool(
    name = TOOL_PROVIDER_CONFIG_STATUS,
    description = "Read-only inventory of configured AI providers, their enabled/model/auth state, and the six default model slots (chat/title/ocr/compress/suggestion/image_generation). All output is redacted: API keys/tokens are never included; API-key providers expose has_api_key and managed OAuth providers expose auth_status/auth_usable; base_url is reported as host only. Use this first to diagnose missing credentials, empty model catalogs, or a dangling chat model before applying changes.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("provider_id", stringProp("Optional provider id filter."))
                put("provider_name_contains", stringProp("Optional substring filter on provider name."))
                put("include_models", boolProp("Include the full model list per provider (id, model_id, display_name, type). Defaults to false; counts are always included."))
            }
        )
    },
    execute = { input ->
        val providerId = input.jsonObject["provider_id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val nameContains = input.jsonObject["provider_name_contains"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val includeModels = input.jsonObject["include_models"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        listOf(
            UIMessagePart.Text(
                statusPayload(
                    settingsStore.settingsFlow.value,
                    secretStore,
                    providerId,
                    nameContains,
                    includeModels,
                    oauthStatusResolver,
                )
                    .toString()
            )
        )
    },
)

private fun statusPayload(
    settings: Settings,
    secretStore: SecretStore,
    providerId: String?,
    nameContains: String?,
    includeModels: Boolean,
    oauthStatusResolver: (ProviderSetting) -> GoogleGeminiAuthStatus?,
): JsonObject = buildJsonObject {
    val providers = settings.providers
        .filter { providerId == null || it.id.toString() == providerId }
        .filter { nameContains == null || it.name.contains(nameContains, ignoreCase = true) }
    put("providers", buildJsonArray {
        providers.forEach { provider ->
            add(providerStatusJson(provider, secretStore, includeModels, oauthStatusResolver))
        }
    })
    put("slots", buildJsonObject {
        slotDefinitions().forEach { slot ->
            put(slot.jsonKey, buildJsonObject {
                put("model_id", slot.idOf(settings).toString())
                val model = settings.findModelById(slot.idOf(settings))
                put("resolved", model != null)
                put("label", model?.displayName?.ifBlank { model.modelId })
                put("expected_type", slot.expectedType.name)
            })
        }
    })
    val issues = mutableListOf<String>()
    providers.forEach { provider ->
        val oauthStatus = oauthStatusResolver(provider)
        val authUsable = oauthStatus?.usable ?: (provider.hasUsableAuth() || secretStore.hasStoredKey(provider))
        if (provider.enabled) {
            if (oauthStatus != null && oauthStatus.code != GoogleGeminiAuthStatusCode.READY) {
                issues += "provider ${provider.name} OAuth auth is ${oauthStatus.code.wireValue}"
            } else if (!authUsable) {
                issues += if (oauthStatus != null) {
                    "provider ${provider.name} OAuth auth is ${oauthStatus.code.wireValue}"
                } else {
                    "provider ${provider.name} has no API key"
                }
            }
        }
        if (provider.enabled && provider.models.none { it.type == ModelType.CHAT }) {
            issues += "provider ${provider.name} has zero chat models"
        }
    }
    slotDefinitions().forEach { slot ->
        val id = slot.idOf(settings)
        if (id != DEFAULT_AUTO_MODEL_ID && settings.findModelById(id) == null) {
            issues += "${slot.jsonKey} model id does not resolve to any configured ${slot.expectedType.name} model"
        }
    }
    put("issues", buildJsonArray { issues.forEach { add(it) } })
}

private fun providerStatusJson(
    provider: ProviderSetting,
    secretStore: SecretStore,
    includeModels: Boolean,
    oauthStatusResolver: (ProviderSetting) -> GoogleGeminiAuthStatus?,
): JsonObject = buildJsonObject {
    val oauthStatus = oauthStatusResolver(provider)
    val authUsable = oauthStatus?.usable ?: (provider.hasUsableAuth() || secretStore.hasStoredKey(provider))
    put("id", provider.id.toString())
    put("name", provider.name)
    put("type", provider.typeName())
    put("brand", provider.brandName())
    put("auth_mode", provider.authModeName())
    put("enabled", provider.enabled)
    put("base_url_host", provider.hostOnly())
    put("has_api_key", if (oauthStatus != null) false else secretStore.hasStoredKey(provider))
    put("auth_usable", authUsable)
    put("auth_status", oauthStatus?.code?.wireValue ?: if (authUsable) "ready" else "missing")
    put("chat_model_count", provider.models.count { it.type == ModelType.CHAT })
    put("image_model_count", provider.models.count { it.type == ModelType.IMAGE })
    if (includeModels) {
        put("models", buildJsonArray {
            provider.models.forEach { model ->
                add(buildJsonObject {
                    put("id", model.id.toString())
                    put("model_id", model.modelId)
                    put("display_name", model.displayName)
                    put("type", model.type.name.lowercase())
                })
            }
        })
    }
}

/**
 * iOS §6.2 status 契约的 brand：仅 OpenAI-compatible 有品牌概念（[OpenAIBrand]），
 * Google / Claude 模型无对应字段 → 输出 null。
 */
private fun ProviderSetting.brandName(): String? = when (this) {
    is ProviderSetting.OpenAI -> brand.name.lowercase()
    is ProviderSetting.Google -> null
    is ProviderSetting.Claude -> null
}

/**
 * iOS §6.2 status 契约的 auth_mode：各 provider 的认证模式枚举（API_KEY /
 * 托管 OAuth / Coding Plan），输出与 @SerialName 一致的小写形式。
 */
private fun ProviderSetting.authModeName(): String = when (this) {
    is ProviderSetting.OpenAI -> authMode.name.lowercase()
    is ProviderSetting.Google -> authMode.name.lowercase()
    is ProviderSetting.Claude -> "api_key"
}

// ---------------------------------------------------------------------------
// provider_config_apply
// ---------------------------------------------------------------------------

private fun providerConfigApplyTool(
    settingsStore: SettingsAggregator,
    secretStore: SecretStore,
): Tool = Tool(
    name = TOOL_PROVIDER_CONFIG_APPLY,
    description = "Atomically update ONE provider: enable/disable, set or clear the API key, change the base URL, chat_completions_path / use_response_api (OpenAI-compatible only), rename, or create a new openai-compatible provider (create_if_missing=true). Requires user approval; when denied nothing is written. The API key is never echoed back — the result only reports api_key_status (updated/cleared/unchanged) and has_api_key. An empty api_key string CLEARS the stored key: the provider will not be callable until a new key is set. base_url must be https (no embedded userinfo); placeholder keys (sk-xxx, your_key, ...) are rejected.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("provider_id", stringProp("Provider id (preferred). Optional when provider_name resolves uniquely."))
                put(
                    "provider_name",
                    stringProp(
                        "Provider name; a case-insensitive exact match wins first, then a unique substring " +
                            "match; when ambiguous or unmatched the result returns candidates and nothing is written."
                    )
                )
                put("enabled", boolProp("Optional. Enable/disable the provider."))
                put("api_key", stringProp("Optional. Omit to keep unchanged. Empty string CLEARS the stored key (requires approval; the provider will not be callable until a new key is set). Placeholder values are rejected."))
                put("base_url", stringProp("Optional. Must be a valid https URL without embedded userinfo. Changing the endpoint requires approval."))
                put("chat_completions_path", stringProp("Optional. OpenAI-compatible only (e.g. /chat/completions)."))
                put("use_response_api", boolProp("Optional. OpenAI-compatible only: route chat through the Responses API."))
                put("name", stringProp("Optional. New display name (must be unique among other providers)."))
                put("create_if_missing", boolProp("Optional. When true and no provider matches, create a new openai-compatible provider (requires provider_name + base_url). Defaults to false."))
            }
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        listOf(UIMessagePart.Text(runApply(input.jsonObject, settingsStore, secretStore).toString()))
    },
)

private suspend fun runApply(
    root: JsonObject,
    settingsStore: SettingsAggregator,
    secretStore: SecretStore,
): JsonObject {
    val settings = settingsStore.settingsFlow.value
    val parse = ApplyInput.parse(root)
    if (parse.error != null) return parse.error
    val prepared = prepareApply(settings, parse.value!!)
    if (prepared.error != null) return prepared.error
    // 校验全部通过 → 单 settings 原子写（SecretStore redaction 由 SettingsAggregator 统一执行）
    settingsStore.update(prepared.target!!)
    val postProvider = prepared.target.providers.first { it.id == prepared.providerId }
    return buildJsonObject {
        put("status", "ok")
        put("provider_id", prepared.providerId.toString())
        put("provider_name", postProvider.name)
        put("changed_fields", buildJsonArray { prepared.changedFields.forEach { add(it) } })
        put("api_key_status", prepared.apiKeyStatus)
        put("has_api_key", secretStore.hasStoredKey(postProvider))
        put("note", "API key value is never included in tool results.")
    }
}

private data class ApplyInput(
    val providerId: String?,
    val providerName: String?,
    val enabled: Boolean?,
    val apiKey: String?,
    val baseUrl: String?,
    val chatCompletionsPath: String?,
    val useResponseApi: Boolean?,
    val name: String?,
    val createIfMissing: Boolean,
) {
    companion object {
        fun parse(root: JsonObject): ParseResult<ApplyInput> {
            val providerId = root["provider_id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
            val providerName = root["provider_name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
            if (providerId == null && providerName == null) {
                return ParseResult.failed("provider_id or provider_name is required to select a provider")
            }
            val apiKey = root["api_key"]?.jsonPrimitive?.contentOrNull
            val baseUrl = root["base_url"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
            if (baseUrl != null && !isValidHttpsUrl(baseUrl)) {
                return ParseResult.failed(
                    "base_url must be a valid https URL without embedded userinfo (got \"$baseUrl\")"
                )
            }
            if (apiKey != null && !apiKey.isBlank() && isPlaceholderApiKey(apiKey)) {
                return ParseResult.failed(
                    "api_key looks like a placeholder (sk-xxx / your_key / too short) and was rejected; " +
                        "empty string clears the key, otherwise paste the real key"
                )
            }
            return ParseResult.ok(
                ApplyInput(
                    providerId = providerId,
                    providerName = providerName,
                    enabled = root["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    chatCompletionsPath = root["chat_completions_path"]?.jsonPrimitive?.contentOrNull,
                    useResponseApi = root["use_response_api"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                    name = root["name"]?.jsonPrimitive?.contentOrNull,
                    createIfMissing = root["create_if_missing"]?.jsonPrimitive?.contentOrNull
                        ?.toBooleanStrictOrNull() ?: false,
                )
            )
        }
    }
}

private class PrepareResult(
    val error: JsonObject? = null,
    val target: Settings? = null,
    val providerId: Uuid? = null,
    val changedFields: List<String> = emptyList(),
    val apiKeyStatus: String = "unchanged",
)

private fun prepareApply(
    settings: Settings,
    input: ApplyInput,
): PrepareResult {
    // ---- 解析目标 provider（id 优先；name 唯一匹配，否则候选） ----
    val provider: ProviderSetting?
    val candidates: List<ProviderSetting>
    if (input.providerId != null) {
        val id = runCatching { Uuid.parse(input.providerId) }.getOrNull()
        if (id == null) return PrepareResult(error = configError("provider_id is not a valid uuid"))
        provider = settings.providers.find { it.id == id }
        candidates = emptyList()
    } else {
        // 大小写不敏感精确匹配优先（"OpenAI" 与 "openai" 视为同名）：
        // 无精确匹配时回退到唯一子串匹配（保持可用性）；
        // 多个 / 零个命中都返回 candidates / 错误（不写入）。
        val exact = settings.providers.filter { it.name.equals(input.providerName, ignoreCase = true) }
        val fuzzy = settings.providers.filter { it.name.contains(input.providerName.orEmpty(), ignoreCase = true) }
        // 同名歧义：无条件返回候选 —— 即使 create_if_missing=true 也绝不落入创建分支
        if (exact.size > 1) {
            return PrepareResult(
                error = configError("provider name \"${input.providerName}\" is ambiguous", candidates = exact)
            )
        }
        provider = when {
            exact.size == 1 -> exact.first()
            fuzzy.size == 1 -> fuzzy.first()
            else -> null
        }
        candidates = fuzzy
    }
    val creating = provider == null && input.createIfMissing
    if (provider == null && !creating) {
        return PrepareResult(
            error = configError(
                "provider not found" + if (input.providerName != null) " for name \"${input.providerName}\"" else "",
                candidates = candidates,
            )
        )
    }
    if (provider == null) {
        // create_if_missing：仅 openai-compatible（GENERIC brand）
        if (input.providerName.isNullOrBlank()) {
            return PrepareResult(error = configError("create_if_missing requires provider_name"))
        }
        if (input.baseUrl == null) {
            return PrepareResult(error = configError("create_if_missing requires base_url (https)"))
        }
        // 创建路径唯一性校验：与任一现有 provider 重名（忽略大小写）即拒绝
        val clash = settings.providers.filter { it.name.equals(input.providerName, ignoreCase = true) }
        if (clash.isNotEmpty()) {
            return PrepareResult(
                error = configError("provider name \"${input.providerName}\" already exists", candidates = clash)
            )
        }
        val changed = mutableListOf("name", "base_url")
        input.enabled?.let { changed += "enabled" }
        input.chatCompletionsPath?.let { changed += "chat_completions_path" }
        input.useResponseApi?.let { changed += "use_response_api" }
        var apiKeyStatus = "unchanged"
        if (input.apiKey != null && !input.apiKey.isBlank()) {
            changed += "api_key"
            apiKeyStatus = "updated"
        }
        val created = ProviderSetting.OpenAI(
            id = Uuid.random(),
            enabled = input.enabled ?: true,
            name = input.providerName,
            models = emptyList(),
            apiKey = input.apiKey ?: "",
            baseUrl = input.baseUrl!!,
            chatCompletionsPath = input.chatCompletionsPath ?: "/chat/completions",
            useResponseApi = input.useResponseApi ?: false,
            brand = OpenAIBrand.GENERIC,
        )
        return PrepareResult(
            target = settings.copy(providers = settings.providers + created),
            providerId = created.id,
            changedFields = changed,
            apiKeyStatus = apiKeyStatus,
        )
    }
    // OpenAI 专属字段对非 OpenAI provider 是错误（先校验后写）
    if (provider !is ProviderSetting.OpenAI && (input.chatCompletionsPath != null || input.useResponseApi != null)) {
        return PrepareResult(
            error = configError("chat_completions_path / use_response_api only apply to openai-compatible providers")
        )
    }

    // ---- 构造目标 provider（任何一步失败都不提交） ----
    // 显式 elvis 解包：让编译器在此处完成非空收窄（前面已两次 null 校验，此处必非空）
    val resolvedProvider: ProviderSetting = provider ?: return PrepareResult(error = configError("provider not found"))
    val changed = mutableListOf<String>()
    var apiKeyStatus = "unchanged"
    val targetProvider: ProviderSetting = when (resolvedProvider) {
        is ProviderSetting.OpenAI -> {
            var p = resolvedProvider as ProviderSetting.OpenAI
            input.enabled?.let { if (it != p.enabled) { p = p.copy(enabled = it); changed += "enabled" } }
            input.name?.takeIf { it.isNotBlank() && it != p.name }?.let { newName ->
                val clash = settings.providers.filter { it.id != p.id && it.name.equals(newName, ignoreCase = true) }
                if (clash.isNotEmpty()) return PrepareResult(error = configError("provider name \"$newName\" already exists", candidates = clash))
                p = p.copy(name = newName); changed += "name"
            }
            input.baseUrl?.let { if (it != p.baseUrl) { p = p.copy(baseUrl = it); changed += "base_url" } }
            input.chatCompletionsPath?.let {
                if (it.isBlank()) return PrepareResult(error = configError("chat_completions_path must not be blank"))
                if (it != p.chatCompletionsPath) { p = p.copy(chatCompletionsPath = it); changed += "chat_completions_path" }
            }
            input.useResponseApi?.let { if (it != p.useResponseApi) { p = p.copy(useResponseApi = it); changed += "use_response_api" } }
            input.apiKey?.let { apiKeyStatus = applyApiKeyTo(p, it, changed) { value -> p = p.copy(apiKey = value) } }
            p
        }

        is ProviderSetting.Google -> {
            var p = resolvedProvider as ProviderSetting.Google
            input.enabled?.let { if (it != p.enabled) { p = p.copy(enabled = it); changed += "enabled" } }
            input.name?.takeIf { it.isNotBlank() && it != p.name }?.let { newName ->
                val clash = settings.providers.filter { it.id != p.id && it.name.equals(newName, ignoreCase = true) }
                if (clash.isNotEmpty()) return PrepareResult(error = configError("provider name \"$newName\" already exists", candidates = clash))
                p = p.copy(name = newName); changed += "name"
            }
            input.baseUrl?.let { if (it != p.baseUrl) { p = p.copy(baseUrl = it); changed += "base_url" } }
            input.apiKey?.let { apiKeyStatus = applyApiKeyTo(p, it, changed) { value -> p = p.copy(apiKey = value) } }
            p
        }

        else -> {
            // ProviderSetting.Claude
            var p = resolvedProvider as ProviderSetting.Claude
            input.enabled?.let { if (it != p.enabled) { p = p.copy(enabled = it); changed += "enabled" } }
            input.name?.takeIf { it.isNotBlank() && it != p.name }?.let { newName ->
                val clash = settings.providers.filter { it.id != p.id && it.name.equals(newName, ignoreCase = true) }
                if (clash.isNotEmpty()) return PrepareResult(error = configError("provider name \"$newName\" already exists", candidates = clash))
                p = p.copy(name = newName); changed += "name"
            }
            input.baseUrl?.let { if (it != p.baseUrl) { p = p.copy(baseUrl = it); changed += "base_url" } }
            input.apiKey?.let { apiKeyStatus = applyApiKeyTo(p, it, changed) { value -> p = p.copy(apiKey = value) } }
            p
        }
    }

    val nextProviders = settings.providers.map { if (it.id == targetProvider.id) targetProvider else it }
    return PrepareResult(
        target = settings.copy(providers = nextProviders),
        providerId = resolvedProvider.id,
        changedFields = changed,
        apiKeyStatus = apiKeyStatus,
    )
}

private fun applyApiKeyTo(
    provider: ProviderSetting,
    value: String,
    changed: MutableList<String>,
    assign: (String) -> Unit,
): String {
    if (value.isBlank()) {
        if (provider.apiKeyValue().isNotBlank()) {
            assign("")
            changed += "api_key"
            return "cleared"
        }
        return "unchanged"
    }
    if (value != provider.apiKeyValue()) {
        assign(value)
        changed += "api_key"
        return "updated"
    }
    return "unchanged"
}

private fun configError(message: String, candidates: List<ProviderSetting>? = null): JsonObject = buildJsonObject {
    put("status", "failed")
    put("error", message)
    put("written", false)
    if (!candidates.isNullOrEmpty()) {
        put("candidates", buildJsonArray {
            candidates.forEach { p ->
                add(buildJsonObject {
                    put("id", p.id.toString())
                    put("name", p.name)
                    put("type", p.typeName())
                    put("enabled", p.enabled)
                })
            }
        })
    }
}

// ---------------------------------------------------------------------------
// provider_refresh_models
// ---------------------------------------------------------------------------

private fun providerRefreshModelsTool(
    settingsStore: SettingsAggregator,
    modelFetcher: ProviderModelFetcher,
): Tool = Tool(
    name = TOOL_PROVIDER_REFRESH_MODELS,
    description = "Fetch the model catalog for ONE provider through the provider's own API (same path as the settings UI) and write it back. mode=merge (default) keeps all existing models and appends new ones; mode=replace_chat replaces only the CHAT models and keeps IMAGE/EMBEDDING entries. On failure (invalid key, network) existing models are never modified. Requires approval; the API key is read from the secret store and never included in the result.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("provider_id", stringProp("Provider id (preferred). Optional when provider_name resolves uniquely."))
                put("provider_name", stringProp("Provider name for unique resolution; when ambiguous the result returns candidates and nothing is written."))
                put("mode", stringProp("merge (default) or replace_chat."))
            }
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        listOf(UIMessagePart.Text(runRefreshModels(input.jsonObject, settingsStore, modelFetcher).toString()))
    },
)

private suspend fun runRefreshModels(
    root: JsonObject,
    settingsStore: SettingsAggregator,
    modelFetcher: ProviderModelFetcher,
): JsonObject {
    val settings = settingsStore.settingsFlow.value
    val providerId = root["provider_id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
    val providerName = root["provider_name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
    val mode = root["mode"]?.jsonPrimitive?.contentOrNull?.ifBlank { "merge" } ?: "merge"
    if (mode != "merge" && mode != "replace_chat") {
        return failedJson("mode must be \"merge\" or \"replace_chat\"", written = false)
    }
    if (providerId == null && providerName == null) {
        return failedJson("provider_id or provider_name is required", written = false)
    }
    val resolved = resolveProvider(settings, providerId, providerName)
    if (resolved.error != null) return resolved.error
    val provider = resolved.provider!!
    val fetched = try {
        modelFetcher.fetch(provider)
    } catch (error: Throwable) {
        return refreshFailureJson(error)
    }
    // 在写事务内计算 merge 结果（以真正落盘的快照为准）
    val outcome = arrayOfNulls<JsonObject>(1)
    var deleted = false
    settingsStore.update { current ->
        val existing = current.providers.find { it.id == provider.id }
        if (existing == null) {
            // provider 已被并发删除：不写、不报 ok，走 provider-not-found 结构化错误
            deleted = true
            return@update current
        }
        val fresh = fetched.distinctBy { it.modelId }
        val merged = when (mode) {
            "replace_chat" -> existing.models.filter { it.type != ModelType.CHAT } + fresh
            else -> mergeModelLists(existing.models, fresh)
        }
        outcome[0] = refreshOutcomeJson(mode, existing.models, fresh, merged, existing.name)
        current.copy(
            providers = current.providers.map {
                if (it.id == provider.id) it.copyModels(merged) else it
            }
        )
    }
    if (deleted) {
        return configError("provider not found for id ${provider.id}")
    }
    return buildJsonObject {
        put("status", "ok")
        put("provider_id", provider.id.toString())
        outcome[0]?.forEach { (key, value) -> put(key, value) }
    }
}

private class ResolveResult(
    val error: JsonObject? = null,
    val provider: ProviderSetting? = null,
)

/** id 优先；name 精确唯一 → 子串唯一 → 否则候选错误。 */
private fun resolveProvider(
    settings: Settings,
    providerId: String?,
    providerName: String?,
): ResolveResult {
    if (providerId != null) {
        val id = runCatching { Uuid.parse(providerId) }.getOrNull()
        if (id == null) return ResolveResult(error = configError("provider_id is not a valid uuid"))
        val provider = settings.providers.find { it.id == id }
        return if (provider != null) {
            ResolveResult(provider = provider)
        } else {
            ResolveResult(error = configError("provider not found for id $providerId"))
        }
    }
    val exact = settings.providers.filter { it.name == providerName }
    val fuzzy = settings.providers.filter { it.name.contains(providerName.orEmpty(), ignoreCase = true) }
    return when {
        exact.size == 1 -> ResolveResult(provider = exact.first())
        exact.size > 1 -> ResolveResult(error = configError("provider name \"$providerName\" is ambiguous", candidates = exact))
        fuzzy.size == 1 -> ResolveResult(provider = fuzzy.first())
        fuzzy.size > 1 -> ResolveResult(error = configError("provider name \"$providerName\" is ambiguous", candidates = fuzzy))
        else -> ResolveResult(error = configError("provider not found for name \"$providerName\""))
    }
}

private fun refreshOutcomeJson(
    mode: String,
    existingModels: List<Model>,
    fetched: List<Model>,
    merged: List<Model>,
    providerName: String,
): JsonObject = buildJsonObject {
    val existingIds = existingModels.map { it.modelId }.toSet()
    val added = fetched.count { it.modelId !in existingIds }
    val kept = when (mode) {
        "replace_chat" -> existingModels.count { it.type != ModelType.CHAT }
        else -> existingModels.size
    }
    put("mode", mode)
    put("provider_name", providerName)
    put("added", added)
    put("kept", kept)
    put("total_models", merged.size)
    put("total_chat_models", merged.count { it.type == ModelType.CHAT })
    put("sample_labels", buildJsonArray {
        fetched.take(20).forEach { model ->
            add(model.displayName.ifBlank { model.modelId })
        }
    })
}

private fun mergeModelLists(existing: List<Model>, fetched: List<Model>): List<Model> {
    val existingIds = existing.map { it.modelId }.toSet()
    return (existing + fetched.filter { it.modelId !in existingIds }).distinctBy { it.id }
}

private fun refreshFailureJson(error: Throwable): JsonObject {
    val statusCode = error.message?.let { msg ->
        Regex("\\b(4\\d{2}|5\\d{2})\\b").find(msg)?.groupValues?.get(1)?.toIntOrNull()
    }
    val message = when (statusCode) {
        401, 403 -> "API key is invalid or lacks model-list permission (HTTP $statusCode). Fix the key via provider_config_apply, or check the base_url. Existing models were NOT modified."
        else -> "Failed to fetch models: ${error.message ?: error.toString()}. Existing models were NOT modified."
    }
    return buildJsonObject {
        put("status", "failed")
        put("error", message)
        put("models_modified", false)
        put("retryable", statusCode == null || statusCode >= 500)
    }
}

// ---------------------------------------------------------------------------
// settings_set_model_slot
// ---------------------------------------------------------------------------

private data class ModelSlot(
    val jsonKey: String,
    val expectedType: ModelType,
    val idOf: (Settings) -> Uuid,
)

private fun slotDefinitions(): List<ModelSlot> = listOf(
    ModelSlot("chat", ModelType.CHAT) { it.chatModelId },
    ModelSlot("title", ModelType.CHAT) { it.titleModelId },
    ModelSlot("ocr", ModelType.CHAT) { it.ocrModelId },
    ModelSlot("compress", ModelType.CHAT) { it.compressModelId },
    ModelSlot("suggestion", ModelType.CHAT) { it.suggestionModelId },
    ModelSlot("image_generation", ModelType.IMAGE) { it.imageGenerationModelId },
)

private fun settingsSetModelSlotTool(settingsStore: SettingsAggregator): Tool = Tool(
    name = TOOL_SETTINGS_SET_MODEL_SLOT,
    description = "Set one default model slot: chat, title, ocr, compress, suggestion, or image_generation. Pass model_id (a configured model's uuid) or model_ref (exact model_id/display name, or a unique substring match across ENABLED providers only). Multiple matches return candidates and nothing is written. A slot/type mismatch (e.g. an IMAGE-only model in chat) is rejected. Requires approval.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("slot", stringProp("Required. One of: chat, title, ocr, compress, suggestion, image_generation."))
                put("model_id", stringProp("Optional. Exact uuid of a configured model."))
                put("model_ref", stringProp("Optional. Exact model_id or display name; falls back to a unique substring match across enabled providers. Use when you do not know the uuid."))
            },
            required = listOf("slot")
        )
    },
    needsApproval = true,
    allowsAutoApproval = false,
    execute = { input ->
        listOf(UIMessagePart.Text(runSetModelSlot(input.jsonObject, settingsStore).toString()))
    },
)

private suspend fun runSetModelSlot(
    root: JsonObject,
    settingsStore: SettingsAggregator,
): JsonObject {
    val settings = settingsStore.settingsFlow.value
    val slotKey = root["slot"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val slot = slotDefinitions().firstOrNull { it.jsonKey == slotKey }
    if (slot == null) {
        return failedJson("slot must be one of ${slotDefinitions().joinToString { it.jsonKey }}", written = false)
    }
    val modelIdRaw = root["model_id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
    val modelRef = root["model_ref"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
    if (modelIdRaw == null && modelRef == null) {
        return failedJson("model_id or model_ref is required", written = false)
    }
    val resolution = resolveSlotModel(settings, slot, modelIdRaw, modelRef)
    if (resolution.error != null) return resolution.error
    val model = resolution.model!!
    val provider = resolution.provider!!
    settingsStore.update { current -> current.withSlot(slotKey, model.id) }
    return buildJsonObject {
        put("status", "ok")
        put("slot", slotKey)
        put("model_id", model.id.toString())
        put("model_label", model.displayName.ifBlank { model.modelId })
        put("provider_id", provider.id.toString())
        put("provider_name", provider.name)
        put("resolved", true)
    }
}

private class SlotResolution(
    val error: JsonObject? = null,
    val model: Model? = null,
    val provider: ProviderSetting? = null,
)

private fun resolveSlotModel(
    settings: Settings,
    slot: ModelSlot,
    modelIdRaw: String?,
    modelRef: String?,
): SlotResolution {
    val candidates = mutableListOf<Pair<ProviderSetting, Model>>()
    val model: Model
    val provider: ProviderSetting
    if (modelIdRaw != null) {
        val id = runCatching { Uuid.parse(modelIdRaw) }.getOrNull()
        if (id == null) return SlotResolution(error = failedJson("model_id is not a valid uuid", written = false))
        model = settings.findModelById(id)
            ?: return SlotResolution(error = failedJson("model_id does not resolve to any configured model", written = false))
        provider = settings.providers.firstOrNull { p -> p.models.any { it.id == id } } ?: return SlotResolution(
            error = failedJson("model_id does not belong to any provider", written = false)
        )
    } else {
        val ref = modelRef!!.trim()
        // 只搜 enabled provider（iOS 契约：仅 enabled provider 参与 model_ref 解析）
        settings.providers.filter { it.enabled }.forEach { p ->
            p.models.forEach { m ->
                if (m.modelId == ref || m.displayName == ref) candidates += p to m
            }
        }
        if (candidates.size != 1) {
            settings.providers.filter { it.enabled }.forEach { p ->
                p.models.forEach { m ->
                    if (m.modelId.contains(ref, ignoreCase = true) || m.displayName.contains(ref, ignoreCase = true)) {
                        candidates += p to m
                    }
                }
            }
        }
        when {
            candidates.isEmpty() -> return SlotResolution(
                error = failedJson("no enabled provider model matches model_ref \"$ref\"", written = false)
            )
            candidates.size > 1 -> return SlotResolution(
                error = buildJsonObject {
                    put("status", "failed")
                    put("error", "model_ref \"$ref\" matches multiple models; pass model_id or a more specific ref")
                    put("written", false)
                    put("candidates", buildJsonArray {
                        candidates.distinctBy { it.second.id }.forEach { (p, m) ->
                            add(buildJsonObject {
                                put("model_id", m.id.toString())
                                put("model_ref", m.modelId)
                                put("label", m.displayName)
                                put("type", m.type.name.lowercase())
                                put("provider_id", p.id.toString())
                                put("provider_name", p.name)
                            })
                        }
                    })
                }
            )
        }
        val hit = candidates.first()
        provider = hit.first
        model = hit.second
    }
    if (model.type != slot.expectedType) {
        return SlotResolution(
            error = failedJson(
                "slot \"${slot.jsonKey}\" requires a ${slot.expectedType.name} model, but " +
                    "\"${model.displayName.ifBlank { model.modelId }}\" is ${model.type.name}",
                written = false,
            )
        )
    }
    return SlotResolution(model = model, provider = provider)
}

// ---------------------------------------------------------------------------
// 共享小工具
// ---------------------------------------------------------------------------

private fun Settings.withSlot(slotKey: String, modelId: Uuid): Settings = when (slotKey) {
    "chat" -> copy(chatModelId = modelId)
    "title" -> copy(titleModelId = modelId)
    "ocr" -> copy(ocrModelId = modelId)
    "compress" -> copy(compressModelId = modelId)
    "suggestion" -> copy(suggestionModelId = modelId)
    "image_generation" -> copy(imageGenerationModelId = modelId)
    else -> this
}

private fun ProviderSetting.copyModels(models: List<Model>): ProviderSetting = when (this) {
    is ProviderSetting.OpenAI -> copy(models = models)
    is ProviderSetting.Google -> copy(models = models)
    is ProviderSetting.Claude -> copy(models = models)
}

private fun ProviderSetting.typeName(): String = when (this) {
    is ProviderSetting.OpenAI -> "openai"
    is ProviderSetting.Google -> "google"
    is ProviderSetting.Claude -> "claude"
}

/** 只取 host（不含 userinfo / path / query）；解析失败返回空串。 */
private fun ProviderSetting.hostOnly(): String {
    val raw = when (this) {
        is ProviderSetting.OpenAI -> baseUrl
        is ProviderSetting.Google -> baseUrl
        is ProviderSetting.Claude -> baseUrl
    }
    return runCatching { URI(raw).host }.getOrNull().orEmpty()
}

private fun isValidHttpsUrl(value: String): Boolean {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() == "https" &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null
}

/** 拒绝明显 placeholder（sk-xxx / your_key / 过短等）；空串由调用方按「清除」处理。 */
private fun isPlaceholderApiKey(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return false
    val lower = trimmed.lowercase()
    if (trimmed.length < 8) return true
    if (lower.contains("your_key") || lower.contains("your-key") || lower.contains("your key") ||
        lower.contains("yourapi") || lower.contains("your_api") || lower.contains("your_api_key") ||
        lower.contains("changeme") || lower.contains("placeholder") || lower == "example"
    ) {
        return true
    }
    if (lower in setOf("xxx", "xxxx", "test", "testing", "dummy", "demo")) return true
    if (Regex("^sk-x{3,}$").matches(lower) || Regex("^sk-[x*\\-]{3,}$").matches(lower)) return true
    if (lower.contains("sk-xxx") || lower.contains("sk-xxxx")) return true
    return false
}

private fun SecretStore.hasStoredKey(provider: ProviderSetting): Boolean {
    val descriptor = SecretDescriptor("provider", provider.id.toString(), "apiKey")
    // 判定 = 密文条目存在 且 能解密出非空值（Keystore 失效 / 密文损坏时不误报已配置）
    if (has(descriptor) && !read(descriptor).isNullOrBlank()) return true
    // 旧明文 / 孤儿掩码兜底：settings 内存值非空即视为已配置（只输出布尔，不输出值）
    return provider.apiKeyValue().isNotBlank()
}

/** 统一取各 concrete provider 的 apiKey 字段（sealed 基类无此字段）。 */
private fun ProviderSetting.apiKeyValue(): String = when (this) {
    is ProviderSetting.OpenAI -> apiKey
    is ProviderSetting.Google -> apiKey
    is ProviderSetting.Claude -> apiKey
}

private class ParseResult<T>(val value: T?, val error: JsonObject?) {
    companion object {
        fun <T> ok(value: T): ParseResult<T> = ParseResult(value, null)
        fun <T> failed(message: String): ParseResult<T> = ParseResult(null, failedJson(message, written = false))
    }
}

private fun failedJson(message: String, written: Boolean): JsonObject = buildJsonObject {
    put("status", "failed")
    put("error", message)
    put("written", written)
}

private fun stringProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun boolProp(description: String) = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}
