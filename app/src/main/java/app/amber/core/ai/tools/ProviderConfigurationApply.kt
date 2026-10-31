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
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.secret.SecretStore
import kotlin.uuid.Uuid

internal fun providerConfigApplyTool(
    settingsStore: SettingsAggregator,
    secretStore: SecretStore,
): Tool = Tool(
    name = TOOL_PROVIDER_CONFIG_APPLY,
    description = "Atomically update ONE provider: enable/disable, set or clear the API key, change the base URL, chat_completions_path / use_response_api (OpenAI-compatible only), rename, or create a new openai-compatible provider (create_if_missing=true). Requires user approval; when denied nothing is written. The API key is never echoed back — the result only reports api_key_status (updated/cleared/unchanged) and has_api_key. An empty api_key string CLEARS the stored key: the provider will not be callable until a new key is set. base_url must be https (no embedded userinfo); placeholder keys (sk-xxx, your_key, ...) are rejected.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("provider_id", providerStringProp("Provider id (preferred). Optional when provider_name resolves uniquely."))
                put(
                    "provider_name",
                    providerStringProp(
                        "Provider name; a case-insensitive exact match wins first, then a unique substring " +
                            "match; when ambiguous or unmatched the result returns candidates and nothing is written."
                    )
                )
                put("enabled", providerBoolProp("Optional. Enable/disable the provider."))
                put("api_key", providerStringProp("Optional. Omit to keep unchanged. Empty string CLEARS the stored key (requires approval; the provider will not be callable until a new key is set). Placeholder values are rejected."))
                put("base_url", providerStringProp("Optional. Must be a valid https URL without embedded userinfo. Changing the endpoint requires approval."))
                put("chat_completions_path", providerStringProp("Optional. OpenAI-compatible only (e.g. /chat/completions)."))
                put("use_response_api", providerBoolProp("Optional. OpenAI-compatible only: route chat through the Responses API."))
                put("name", providerStringProp("Optional. New display name (must be unique among other providers)."))
                put("create_if_missing", providerBoolProp("Optional. When true and no provider matches, create a new openai-compatible provider (requires provider_name + base_url). Defaults to false."))
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
    val parse = ApplyInput.parse(root)
    if (parse.error != null) return parse.error
    var committed: PrepareResult? = null
    try {
        settingsStore.update { current ->
            val prepared = prepareApply(current, parse.value!!)
            // Abort before writeSettings: a rejected command must not even republish unchanged settings.
            prepared.error?.let { throw ApplyRejected(it) }
            committed = prepared
            prepared.target!!
        }
    } catch (rejected: ApplyRejected) {
        return rejected.payload
    }
    val prepared = checkNotNull(committed)
    val postProvider = prepared.target!!.providers.first { it.id == prepared.providerId }
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

/** Structured command rejection before the aggregator writes anything. */
private class ApplyRejected(val payload: JsonObject) : RuntimeException()

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
        if (id == null) return PrepareResult(error = providerConfigError("provider_id is not a valid uuid"))
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
                error = providerConfigError("provider name \"${input.providerName}\" is ambiguous", candidates = exact)
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
            error = providerConfigError(
                "provider not found" + if (input.providerName != null) " for name \"${input.providerName}\"" else "",
                candidates = candidates,
            )
        )
    }
    if (provider == null) {
        // create_if_missing：仅 openai-compatible（GENERIC brand）
        if (input.providerName.isNullOrBlank()) {
            return PrepareResult(error = providerConfigError("create_if_missing requires provider_name"))
        }
        if (input.baseUrl == null) {
            return PrepareResult(error = providerConfigError("create_if_missing requires base_url (https)"))
        }
        // 创建路径唯一性校验：与任一现有 provider 重名（忽略大小写）即拒绝
        val clash = settings.providers.filter { it.name.equals(input.providerName, ignoreCase = true) }
        if (clash.isNotEmpty()) {
            return PrepareResult(
                error = providerConfigError("provider name \"${input.providerName}\" already exists", candidates = clash)
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
            error = providerConfigError("chat_completions_path / use_response_api only apply to openai-compatible providers")
        )
    }

    // ---- 构造目标 provider（任何一步失败都不提交） ----
    // 显式 elvis 解包：让编译器在此处完成非空收窄（前面已两次 null 校验，此处必非空）
    val resolvedProvider: ProviderSetting = provider ?: return PrepareResult(error = providerConfigError("provider not found"))
    val changed = mutableListOf<String>()
    var apiKeyStatus = "unchanged"
    val targetProvider: ProviderSetting = when (resolvedProvider) {
        is ProviderSetting.OpenAI -> {
            var p = resolvedProvider as ProviderSetting.OpenAI
            input.enabled?.let { if (it != p.enabled) { p = p.copy(enabled = it); changed += "enabled" } }
            input.name?.takeIf { it.isNotBlank() && it != p.name }?.let { newName ->
                val clash = settings.providers.filter { it.id != p.id && it.name.equals(newName, ignoreCase = true) }
                if (clash.isNotEmpty()) return PrepareResult(error = providerConfigError("provider name \"$newName\" already exists", candidates = clash))
                p = p.copy(name = newName); changed += "name"
            }
            input.baseUrl?.let { if (it != p.baseUrl) { p = p.copy(baseUrl = it); changed += "base_url" } }
            input.chatCompletionsPath?.let {
                if (it.isBlank()) return PrepareResult(error = providerConfigError("chat_completions_path must not be blank"))
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
                if (clash.isNotEmpty()) return PrepareResult(error = providerConfigError("provider name \"$newName\" already exists", candidates = clash))
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
                if (clash.isNotEmpty()) return PrepareResult(error = providerConfigError("provider name \"$newName\" already exists", candidates = clash))
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

private fun isValidHttpsUrl(value: String): Boolean {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() == "https" &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null
}

/** 拒绝明显 placeholder（sk-xxx / your_key / 掩码占位符 / 过短等）；空串由调用方按「清除」处理。 */
internal fun isPlaceholderApiKey(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return false
    val lower = trimmed.lowercase()
    if (trimmed.startsWith("••••") || trimmed.all { it == '•' || it == '*' }) return true
    if (lower == "<redacted>" || lower == "[redacted]" || lower == "***" || lower == "******") return true
    if (lower.contains("<redacted>") || lower.contains("[redacted]") || lower.contains("__masked_by_amberagent_backup__")) return true
    if (trimmed.length < 8) return true
    if (lower.contains("your_key") || lower.contains("your-key") || lower.contains("your key") ||
        lower.contains("yourapi") || lower.contains("your_api") || lower.contains("your_api_key") ||
        lower.contains("changeme") || lower.contains("placeholder") || lower == "example"
    ) {
        return true
    }
    if (lower in setOf("xxx", "xxxx", "test", "testing", "dummy", "demo")) return true
    if (Regex("^sk-x{3,}$").matches(lower) || Regex("^sk-[x*\\-]{3,}$").matches(lower)) return true
    if (lower.contains("sk-xxx") || lower.contains("sk-xxxx") || lower.contains("sk-******")) return true
    return false
}

private class ParseResult<T>(val value: T?, val error: JsonObject?) {
    companion object {
        fun <T> ok(value: T): ParseResult<T> = ParseResult(value, null)
        fun <T> failed(message: String): ParseResult<T> = ParseResult(null, providerConfigFailure(message, written = false))
    }
}
