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
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.hasUsableAuth
import app.amber.ai.provider.providers.GoogleProvider
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStatus
import app.amber.ai.provider.providers.google.AntigravityAuthStatus
import app.amber.ai.provider.providers.openai.OpenAIChatGptSiwcAuthStore
import app.amber.ai.provider.providers.openai.OpenAIChatGptSiwcTokens
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexAuthTokens
import app.amber.ai.provider.providers.grok.GrokAuthStatus
import app.amber.ai.provider.providers.grok.GrokAuthStore
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.DEFAULT_AUTO_MODEL_ID
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.secret.SecretStore

/**
 * The status tool has one wire shape for managed OAuth providers. Keep the DTO independent
 * from Google's auth enum so Codex can report its real token state through the same contract.
 */
internal data class ManagedProviderAuthStatus(
    val wireValue: String,
    val usable: Boolean,
)

private const val AUTH_STATUS_READY = "ready"
private const val AUTH_STATUS_NOT_SIGNED_IN = "not_signed_in"
private const val AUTH_STATUS_TOKEN_MISSING = "token_missing"
private const val AUTH_STATUS_TOKEN_EXPIRED = "token_expired"
private const val AUTH_STATUS_CLIENT_UNAVAILABLE = "client_unavailable"

internal fun codexAuthStatus(
    tokens: OpenAICodexAuthTokens?,
    nowMillis: Long = System.currentTimeMillis(),
): ManagedProviderAuthStatus {
    if (tokens == null) {
        return ManagedProviderAuthStatus(AUTH_STATUS_NOT_SIGNED_IN, usable = false)
    }
    if (tokens.accessToken.isBlank()) {
        return ManagedProviderAuthStatus(AUTH_STATUS_TOKEN_MISSING, usable = false)
    }
    if (tokens.expiresAtMillis <= nowMillis) {
        // The next request will refresh before use. A stored refresh credential keeps this
        // session usable; without one the expired access token cannot authenticate a call.
        return ManagedProviderAuthStatus(
            AUTH_STATUS_TOKEN_EXPIRED,
            usable = tokens.refreshToken.isNotBlank(),
        )
    }
    return ManagedProviderAuthStatus(AUTH_STATUS_READY, usable = true)
}

internal fun siwcAuthStatus(
    tokens: OpenAIChatGptSiwcTokens?,
    nowMillis: Long = System.currentTimeMillis(),
): ManagedProviderAuthStatus {
    if (tokens == null) {
        return ManagedProviderAuthStatus(AUTH_STATUS_NOT_SIGNED_IN, usable = false)
    }
    if (!tokens.hasDirectUsageScope || tokens.accessToken.isBlank()) {
        return ManagedProviderAuthStatus(AUTH_STATUS_TOKEN_MISSING, usable = false)
    }
    if (tokens.expiresAtMillis <= nowMillis) {
        return ManagedProviderAuthStatus(
            AUTH_STATUS_TOKEN_EXPIRED,
            usable = tokens.refreshToken.isNotBlank(),
        )
    }
    return ManagedProviderAuthStatus(AUTH_STATUS_READY, usable = true)
}

internal fun managedProviderAuthStatusResolver(
    googleProvider: GoogleProvider,
    openAICodexAuthStore: OpenAICodexAuthStore?,
    grokAuthStore: GrokAuthStore?,
    openAIChatGptSiwcAuthStore: OpenAIChatGptSiwcAuthStore? = null,
): (ProviderSetting) -> ManagedProviderAuthStatus? {
    // Google OAuth state lives in the provider's encrypted token store, not in
    // Settings/SecretStore. Keep the resolver here so status and refresh use the
    // same concrete provider that sends the request; no token value crosses the
    // tool boundary.
    return { provider ->
        when (provider) {
            is ProviderSetting.Google -> runCatching {
                val gemini = googleProvider.oauthAuthStatus(provider)
                if (gemini != null) {
                    gemini.toManagedStatus() as ManagedProviderAuthStatus?
                } else {
                    googleProvider.antigravityAuthStatus(provider)
                        ?.let { it.toManagedStatus() as ManagedProviderAuthStatus? }
                }
            }.getOrElse { ManagedProviderAuthStatus(AUTH_STATUS_CLIENT_UNAVAILABLE, usable = false) }
            is ProviderSetting.OpenAI -> if (provider.authMode == OpenAIAuthMode.GROK_OAUTH) {
                grokAuthStore?.let { GrokAuthStatus.from(it.get(provider.id), System.currentTimeMillis()).toManagedStatus() }
                    ?: ManagedProviderAuthStatus(AUTH_STATUS_CLIENT_UNAVAILABLE, usable = false)
            } else if (provider.authMode == OpenAIAuthMode.CODEX_OAUTH) {
                if (openAICodexAuthStore == null) {
                    ManagedProviderAuthStatus(AUTH_STATUS_CLIENT_UNAVAILABLE, usable = false)
                } else {
                    runCatching {
                        codexAuthStatus(openAICodexAuthStore.get(provider.id))
                    }.getOrElse { ManagedProviderAuthStatus(AUTH_STATUS_CLIENT_UNAVAILABLE, usable = false) }
                }
            } else if (provider.authMode == OpenAIAuthMode.CHATGPT_SIWC) {
                if (openAIChatGptSiwcAuthStore == null) {
                    ManagedProviderAuthStatus(AUTH_STATUS_CLIENT_UNAVAILABLE, usable = false)
                } else {
                    runCatching {
                        siwcAuthStatus(openAIChatGptSiwcAuthStore.get(provider.id))
                    }.getOrElse { ManagedProviderAuthStatus(AUTH_STATUS_CLIENT_UNAVAILABLE, usable = false) }
                }
            } else {
                null
            }
            else -> null
        }
    }
}

internal fun providerConfigStatusTool(
    settingsStore: SettingsAggregator,
    secretStore: SecretStore,
    oauthStatusResolver: (ProviderSetting) -> ManagedProviderAuthStatus?,
): Tool = Tool(
    name = TOOL_PROVIDER_CONFIG_STATUS,
    description = "Read-only inventory of configured AI providers, their enabled/model/auth state, and the six default model slots (chat/title/ocr/compress/suggestion/image_generation). All output is redacted: API keys/tokens are never included; API-key providers expose has_api_key and managed OAuth providers expose auth_status/auth_usable; base_url is reported as host only. Use this first to diagnose missing credentials, empty model catalogs, or a dangling chat model before applying changes.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("provider_id", providerStringProp("Optional provider id filter."))
                put("provider_name_contains", providerStringProp("Optional substring filter on provider name."))
                put("include_models", providerBoolProp("Include the full model list per provider (id, model_id, display_name, type). Defaults to false; counts are always included."))
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
    oauthStatusResolver: (ProviderSetting) -> ManagedProviderAuthStatus?,
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
        providerModelSlotDefinitions().forEach { slot ->
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
            if (oauthStatus != null && oauthStatus.wireValue != AUTH_STATUS_READY) {
                issues += "provider ${provider.name} OAuth auth is ${oauthStatus.wireValue}"
            } else if (!authUsable) {
                issues += if (oauthStatus != null) {
                    "provider ${provider.name} OAuth auth is ${oauthStatus.wireValue}"
                } else {
                    "provider ${provider.name} has no API key"
                }
            }
        }
        if (provider.enabled && provider.models.none { it.type == ModelType.CHAT }) {
            issues += "provider ${provider.name} has zero chat models"
        }
    }
    providerModelSlotDefinitions().forEach { slot ->
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
    oauthStatusResolver: (ProviderSetting) -> ManagedProviderAuthStatus?,
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
    put("auth_status", oauthStatus?.wireValue ?: if (authUsable) AUTH_STATUS_READY else "missing")
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

private fun GoogleGeminiAuthStatus.toManagedStatus(): ManagedProviderAuthStatus =
    ManagedProviderAuthStatus(code.wireValue, usable)

private fun AntigravityAuthStatus.toManagedStatus(): ManagedProviderAuthStatus =
    ManagedProviderAuthStatus(code.wireValue, usable)

private fun GrokAuthStatus.toManagedStatus(): ManagedProviderAuthStatus =
    ManagedProviderAuthStatus(code.wireValue, usable)

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

/** 只取 host（不含 userinfo / path / query）；解析失败返回空串。 */
private fun ProviderSetting.hostOnly(): String {
    val raw = when (this) {
        is ProviderSetting.OpenAI -> baseUrl
        is ProviderSetting.Google -> baseUrl
        is ProviderSetting.Claude -> baseUrl
    }
    return runCatching { URI(raw).host }.getOrNull().orEmpty()
}
