package app.amber.core.settings.secret

import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.settings.LegacyAssistantProfile
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.WebDavConfig
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.sync.s3.S3Config
import app.amber.search.SearchServiceOptions
import java.net.URI

private const val TAG = "SecretRedactor"
private val SENSITIVE_MCP_URL_KEYS = setOf(
    "authorization",
    "auth",
    "token",
    "access_token",
    "refresh_token",
    "auth_token",
    "cookie",
    "secret",
    "password",
    "api_key",
    "key",
    "code",
    "client_secret",
    "sig",
    "signature",
    "hmac",
)

/**
 * P1-01 统一 redaction 引擎。
 *
 * - 设置保存边界（SettingsAggregator.writeSettings / 各域 Prefs.writeTo）：redact ——
 *   明文写入 SecretStore，序列化内容只保留掩码 + [SecretReference]。
 * - 设置读取边界（各域 Prefs.readFrom）：rehydrate —— 按 reference 从 SecretStore
 *   取回明文，业务代码与既有 UI 看到的仍是完整运行时值。
 * - 备份/导出边界：redactForExport —— 导出 JSON 中同样只有掩码 + reference，无明文。
 *
 * 键 = scope + ownerId + fieldName（真实值不进 key）。Legacy 字段写失败时保持旧明文并告警；
 * MCP OAuth 和含凭据 URL 使用 fail-closed 写入。读失败按未设置处理且不删 reference。
 */
class SecretRedactor(private val secretStore: SecretStore) {

    /** 最近一次 redact 周期中写入失败的 descriptor key（迁移据此不标记完成）。 */
    private val writeFailures = mutableSetOf<String>()

    // ---------------- 掩码 ----------------

    /** 掩码位：保留末 4 位便于区分不同 key，其余以 ● 覆盖。 */
    private fun mask(value: String): String = when {
        value.isBlank() -> ""
        value.length <= 4 -> MASK_STRING
        else -> MASK_STRING + value.takeLast(4)
    }

    // ---------------- refs map（DataStore 中的 reference+掩码位） ----------------

    fun readRefs(p: Preferences): Map<String, SecretReference> =
        p[PreferencesKeys.SECRET_REFS]?.let { raw ->
            runCatching {
                JsonInstant.decodeFromString<List<SecretReference>>(raw)
                    .associateBy { it.descriptor().key }
            }.getOrNull()
        } ?: emptyMap()

    /**
     * Strict reference decoding for write/migration boundaries. A missing key is the
     * empty map; an explicitly present malformed value fails the enclosing operation.
     */
    internal fun readRefsStrict(p: Preferences): Map<String, SecretReference> =
        p[PreferencesKeys.SECRET_REFS]?.let { raw ->
            JsonInstant.decodeFromString<List<SecretReference>>(raw)
                .associateBy { it.descriptor().key }
        } ?: emptyMap()

    fun writeRefs(p: MutablePreferences, refs: Map<String, SecretReference>) {
        if (refs.isEmpty()) {
            p.remove(PreferencesKeys.SECRET_REFS)
        } else {
            p[PreferencesKeys.SECRET_REFS] = JsonInstant.encodeToString(refs.values.toList())
        }
    }

    /** orphan 回收：只删确认不再被任何设置引用的项（委托 SecretStore）。 */
    fun deleteOrphans(active: Set<SecretDescriptor>) {
        secretStore.deleteOrphans(active)
    }

    /**
     * 从导出的 settings JSON（含合成键 `secretRefs`）中提取 reference 列表。
     * 备份导出携带 refs 才能让恢复后的掩码值通过 redact keep 规则找回原 secret。
     */
    fun extractRefsFromSettingsJson(json: Json, settingsJson: String): List<SecretReference> =
        runCatching {
            val obj = json.parseToJsonElement(settingsJson).jsonObject
            val refs = json.decodeFromString<List<SecretReference>>(
                obj[EXPORT_SECRET_REFS_KEY]?.toString() ?: "[]"
            )
            val sourceProfileId = obj["amberProfile"]
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: obj["assistantId"]?.jsonPrimitive?.contentOrNull
            refs.mapNotNull { ref ->
                if (ref.scope != "assistant") return@mapNotNull ref
                if (sourceProfileId != null && ref.ownerId != sourceProfileId) return@mapNotNull null
                val canonical = ref.copy(ownerId = AMBER_AGENT_ID.toString())
                if (canonical.ownerId != ref.ownerId) {
                    secretStore.read(ref.descriptor())?.let { value ->
                        secretStore.update(canonical.descriptor(), value)
                    }
                }
                canonical
            }
        }.getOrNull() ?: emptyList()

    // ---------------- redact（保存/导出边界：明文 → SecretStore） ----------------

    /**
     * redact 单个字段。返回持久化形式的值（掩码或原样）。
     * 规则：
     * - 空值 → 无 reference；
     * - 值等于已有 reference 的掩码（恢复/回环场景）→ 保留原 reference，不重写 secret；
     * - 值本身已是掩码但无 reference（孤儿掩码）→ 原样保留，不建 reference；
     * - 其余 → 写入 SecretStore（幂等 upsert），产出新 reference。
     * 写失败 → 保持明文并告警（迁移失败不删旧值，短期兼容旧明文字段）。
     */
    private fun redactSecret(
        scope: String,
        ownerId: String,
        fieldName: String,
        value: String,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
        failOnWriteFailure: Boolean = false,
    ): String {
        if (value.isBlank()) return ""
        val descriptor = SecretDescriptor(scope, ownerId, fieldName)
        val existing = existingRefs[descriptor.key]
        if (existing != null && value == existing.mask) {
            out[descriptor.key] = existing
            return value
        }
        val newMask = mask(value)
        if (newMask == value) return value // 孤儿掩码：不建 reference，原样保留
        val written = runCatching { secretStore.update(descriptor, value) }.isSuccess
        if (!written) {
            writeFailures += descriptor.key
            if (failOnWriteFailure) {
                Log.e(TAG, "SecretStore write failed for ${descriptor.key}; refusing plaintext persistence")
                throw IllegalStateException("SecretStore write failed for ${descriptor.key}")
            }
            Log.w(TAG, "SecretStore write failed for ${descriptor.key}; keeping plaintext (legacy compat)")
            return value
        }
        out[descriptor.key] = SecretReference(scope, ownerId, fieldName, newMask)
        return newMask
    }

    /** 开始一次迁移/保存前调用，清空上一次的写入失败记录。 */
    fun resetWriteFailures() {
        writeFailures.clear()
    }

    /** 本次 redact 过程中写入 SecretStore 失败的 descriptor key（迁移据此不标记完成）。 */
    fun takeWriteFailures(): Set<String> = writeFailures.toSet().also { writeFailures.clear() }

    fun redactProviders(
        providers: List<ProviderSetting>,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
    ): List<ProviderSetting> = providers.map { provider ->
        val ownerId = provider.id.toString()
        when (provider) {
            is ProviderSetting.OpenAI -> provider.copy(
                apiKey = redactSecret("provider", ownerId, "apiKey", provider.apiKey, existingRefs, out),
                models = provider.models.redactModelHeaders("provider", ownerId, existingRefs, out),
            )

            is ProviderSetting.Google -> provider.copy(
                apiKey = redactSecret("provider", ownerId, "apiKey", provider.apiKey, existingRefs, out),
                privateKey = redactSecret("provider", ownerId, "privateKey", provider.privateKey, existingRefs, out),
                models = provider.models.redactModelHeaders("provider", ownerId, existingRefs, out),
            )

            is ProviderSetting.Claude -> provider.copy(
                apiKey = redactSecret("provider", ownerId, "apiKey", provider.apiKey, existingRefs, out),
                models = provider.models.redactModelHeaders("provider", ownerId, existingRefs, out),
            )
        }
    }

    /** Redact the single Amber custom-header list under one stable owner. */
    fun redactCustomHeaders(
        headers: List<CustomHeader>,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
    ): List<CustomHeader> = headers.redactHeaders(
        "assistant", AMBER_AGENT_ID.toString(), existingRefs, out,
    )

    /** Legacy profile helper retained only for the one-time migration decoder. */
    internal fun redactLegacyAssistantProfile(
        profile: LegacyAssistantProfile,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
    ): LegacyAssistantProfile = if (profile.customHeaders.isEmpty()) {
        profile
    } else {
        profile.copy(
            customHeaders = profile.customHeaders.redactHeaders(
                "assistant", AMBER_AGENT_ID.toString(), existingRefs, out,
            )
        )
    }

    /** Legacy list helper retained only for old migration fixtures. */
    internal fun redactLegacyAssistants(
        assistants: List<LegacyAssistantProfile>,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
    ): List<LegacyAssistantProfile> = assistants.map { redactLegacyAssistantProfile(it, existingRefs, out) }

    fun redactSearchServices(
        services: List<SearchServiceOptions>,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
    ): List<SearchServiceOptions> = services.map { service ->
        val ownerId = service.id.toString()
        when (service) {
            is SearchServiceOptions.ZhipuOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.TavilyOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.ExaOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.SearXNGOptions -> service.copy(
                username = redactSecret("search", ownerId, "username", service.username, existingRefs, out),
                password = redactSecret("search", ownerId, "password", service.password, existingRefs, out),
            )

            is SearchServiceOptions.LinkUpOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.BraveOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.SerperOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.SerpApiOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.MetasoOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.OllamaOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.PerplexityOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.FirecrawlOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.JinaOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.BochaOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.GrokOptions -> service.copy(
                apiKey = redactSecret("search", ownerId, "apiKey", service.apiKey, existingRefs, out)
            )

            is SearchServiceOptions.BingLocalOptions -> service
        }
    }

    fun redactMcpServers(
        servers: List<McpServerConfig>,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
    ): List<McpServerConfig> = servers.map { server ->
        val common = server.commonOptions
        val redactedServer = server.withMcpUrl(redactMcpUrl(server, existingRefs, out))
        if (common.headers.isEmpty() && common.oauth == null) {
            redactedServer
        } else {
            redactedServer.clone(
                commonOptions = common.copy(
                    headers = common.headers.map { (name, value) ->
                        if (value.isBlank()) {
                            name to value
                        } else {
                            name to redactSecret(
                                "mcp", server.id.toString(), "header:$name", value, existingRefs, out,
                            )
                        }
                    },
                    oauth = common.oauth?.let { oauth ->
                        oauth.copy(
                            clientSecret = oauth.clientSecret.redactMcpOAuthSecret(
                                server.id.toString(), "oauth:clientSecret", existingRefs, out,
                            ),
                            accessToken = oauth.accessToken.redactMcpOAuthSecret(
                                server.id.toString(), "oauth:accessToken", existingRefs, out,
                            ),
                            refreshToken = oauth.refreshToken.redactMcpOAuthSecret(
                                server.id.toString(), "oauth:refreshToken", existingRefs, out,
                            ),
                            authorizationEndpoint = oauth.authorizationEndpoint.redactMcpOAuthUrl(
                                server.id.toString(), "oauth:authorizationEndpoint", existingRefs, out,
                            ),
                            tokenEndpoint = oauth.tokenEndpoint.redactMcpOAuthUrl(
                                server.id.toString(), "oauth:tokenEndpoint", existingRefs, out,
                            ),
                            registrationEndpoint = oauth.registrationEndpoint.redactMcpOAuthUrl(
                                server.id.toString(), "oauth:registrationEndpoint", existingRefs, out,
                            ),
                        )
                    },
                )
            )
        }
    }

    private fun String?.redactMcpOAuthSecret(
        ownerId: String,
        fieldName: String,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
    ): String? = this?.let { value ->
        redactSecret(
            "mcp",
            ownerId,
            fieldName,
            value,
            existingRefs,
            out,
            failOnWriteFailure = true,
        )
    }

    private fun String?.redactMcpOAuthUrl(
        ownerId: String,
        fieldName: String,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
    ): String? = this?.let { url ->
        if (!url.hasSensitiveMcpUrlParts()) {
            url
        } else {
            redactSecret(
                "mcp",
                ownerId,
                fieldName,
                url,
                existingRefs,
                out,
                failOnWriteFailure = true,
            )
        }
    }

    private fun redactMcpUrl(
        server: McpServerConfig,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
    ): String {
        val url = server.mcpUrl()
        if (!url.hasSensitiveMcpUrlParts()) return url
        return redactSecret(
            scope = "mcp",
            ownerId = server.id.toString(),
            fieldName = "url",
            value = url,
            existingRefs = existingRefs,
            out = out,
            failOnWriteFailure = true,
        )
    }

    private fun String.hasSensitiveMcpUrlParts(): Boolean {
        val uri = runCatching { URI(trim()) }.getOrNull()
        return if (uri != null) {
            !uri.userInfo.isNullOrBlank() ||
                uri.rawQuery.hasSensitiveMcpUrlComponent() ||
                uri.rawFragment.hasSensitiveMcpUrlComponent()
        } else {
            contains('@') || contains('?') || contains('#')
        }
    }

    private fun String?.hasSensitiveMcpUrlComponent(): Boolean {
        if (isNullOrBlank()) return false
        return split('&').any { component ->
            val key = component.substringBefore('=').trim().lowercase()
            key in SENSITIVE_MCP_URL_KEYS ||
                key.endsWith("token") ||
                key.endsWith("secret") ||
                key.endsWith("password")
        }
    }

    private fun McpServerConfig.mcpUrl(): String = when (this) {
        is McpServerConfig.SseTransportServer -> url
        is McpServerConfig.StreamableHTTPServer -> url
    }

    private fun McpServerConfig.withMcpUrl(url: String): McpServerConfig = when (this) {
        is McpServerConfig.SseTransportServer -> copy(url = url)
        is McpServerConfig.StreamableHTTPServer -> copy(url = url)
    }

    fun redactWebDav(
        config: WebDavConfig,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
    ): WebDavConfig = config.copy(
        username = redactSecret("webdav", "", "username", config.username, existingRefs, out),
        password = redactSecret("webdav", "", "password", config.password, existingRefs, out),
    )

    fun redactS3(
        config: S3Config,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
    ): S3Config = config.copy(
        accessKeyId = redactSecret("s3", "", "accessKeyId", config.accessKeyId, existingRefs, out),
        secretAccessKey = redactSecret("s3", "", "secretAccessKey", config.secretAccessKey, existingRefs, out),
    )

    private fun List<Model>.redactModelHeaders(
        scope: String,
        ownerId: String,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
    ): List<Model> = map { model ->
        if (model.customHeaders.isEmpty()) {
            model
        } else {
            model.copy(
                customHeaders = model.customHeaders.redactHeaders(scope, ownerId, existingRefs, out)
            )
        }
    }

    private fun List<CustomHeader>.redactHeaders(
        scope: String,
        ownerId: String,
        existingRefs: Map<String, SecretReference>,
        out: MutableMap<String, SecretReference>,
    ): List<CustomHeader> = map { header ->
        if (header.value.isBlank()) {
            header
        } else {
            header.copy(
                value = redactSecret(scope, ownerId, "customHeader:${header.name}", header.value, existingRefs, out)
            )
        }
    }

    /** 聚合器全量 redact：产出各域持久化形式 + 完整 refs map。 */
    fun redactSettings(
        providers: List<ProviderSetting>,
        customHeaders: List<CustomHeader>,
        searchServices: List<SearchServiceOptions>,
        mcpServers: List<McpServerConfig>,
        webDavConfig: WebDavConfig,
        s3Config: S3Config,
        existingRefs: Map<String, SecretReference>,
    ): RedactedSettings {
        val out = mutableMapOf<String, SecretReference>()
        return RedactedSettings(
            providers = redactProviders(providers, existingRefs, out),
            customHeaders = redactCustomHeaders(customHeaders, existingRefs, out),
            searchServices = redactSearchServices(searchServices, existingRefs, out),
            mcpServers = redactMcpServers(mcpServers, existingRefs, out),
            webDavConfig = redactWebDav(webDavConfig, existingRefs, out),
            s3Config = redactS3(s3Config, existingRefs, out),
            refs = out,
        )
    }

    // ---------------- rehydrate（读取/业务使用边界：SecretStore → 明文） ----------------

    private fun rehydrateSecret(
        refs: Map<String, SecretReference>,
        scope: String,
        ownerId: String,
        fieldName: String,
        persistedValue: String,
    ): String {
        val ref = refs[SecretDescriptor(scope, ownerId, fieldName).key] ?: return persistedValue
        // 密钥失效/密文损坏：SecretStore.read 已 null 安全，不删 reference、不崩
        return secretStore.read(ref.descriptor()) ?: ""
    }

    fun rehydrateProviders(
        providers: List<ProviderSetting>,
        refs: Map<String, SecretReference>,
    ): List<ProviderSetting> = providers.map { provider ->
        val ownerId = provider.id.toString()
        when (provider) {
            is ProviderSetting.OpenAI -> provider.copy(
                apiKey = rehydrateSecret(refs, "provider", ownerId, "apiKey", provider.apiKey),
                models = provider.models.rehydrateModelHeaders(refs, "provider", ownerId),
            )

            is ProviderSetting.Google -> provider.copy(
                apiKey = rehydrateSecret(refs, "provider", ownerId, "apiKey", provider.apiKey),
                privateKey = rehydrateSecret(refs, "provider", ownerId, "privateKey", provider.privateKey),
                models = provider.models.rehydrateModelHeaders(refs, "provider", ownerId),
            )

            is ProviderSetting.Claude -> provider.copy(
                apiKey = rehydrateSecret(refs, "provider", ownerId, "apiKey", provider.apiKey),
                models = provider.models.rehydrateModelHeaders(refs, "provider", ownerId),
            )
        }
    }

    internal fun rehydrateLegacyAssistantProfile(
        profile: LegacyAssistantProfile,
        refs: Map<String, SecretReference>,
    ): LegacyAssistantProfile = if (profile.customHeaders.isEmpty()) {
        profile
    } else {
        profile.copy(
            customHeaders = profile.customHeaders.rehydrateHeaders(refs, "assistant", profile.id.toString())
        )
    }

    fun rehydrateCustomHeaders(
        headers: List<CustomHeader>,
        refs: Map<String, SecretReference>,
    ): List<CustomHeader> = headers.rehydrateHeaders(
        refs,
        "assistant",
        AMBER_AGENT_ID.toString(),
    )

    /** Legacy list helper retained only for old migration fixtures. */
    internal fun rehydrateLegacyAssistants(
        assistants: List<LegacyAssistantProfile>,
        refs: Map<String, SecretReference>,
    ): List<LegacyAssistantProfile> = assistants.map { rehydrateLegacyAssistantProfile(it, refs) }

    fun rehydrateSearchServices(
        services: List<SearchServiceOptions>,
        refs: Map<String, SecretReference>,
    ): List<SearchServiceOptions> = services.map { service ->
        val ownerId = service.id.toString()
        when (service) {
            is SearchServiceOptions.ZhipuOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.TavilyOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.ExaOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.SearXNGOptions -> service.copy(
                username = rehydrateSecret(refs, "search", ownerId, "username", service.username),
                password = rehydrateSecret(refs, "search", ownerId, "password", service.password),
            )

            is SearchServiceOptions.LinkUpOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.BraveOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.SerperOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.SerpApiOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.MetasoOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.OllamaOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.PerplexityOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.FirecrawlOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.JinaOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.BochaOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.GrokOptions -> service.copy(
                apiKey = rehydrateSecret(refs, "search", ownerId, "apiKey", service.apiKey)
            )

            is SearchServiceOptions.BingLocalOptions -> service
        }
    }

    fun rehydrateMcpServers(
        servers: List<McpServerConfig>,
        refs: Map<String, SecretReference>,
    ): List<McpServerConfig> = servers.map { server ->
        val rehydratedServer = server.withMcpUrl(rehydrateMcpUrl(server, refs))
        val common = rehydratedServer.commonOptions
        if (common.headers.isEmpty() && common.oauth == null) {
            rehydratedServer
        } else {
            rehydratedServer.clone(
                commonOptions = common.copy(
                    headers = common.headers.map { (name, value) ->
                        if (value.isBlank()) {
                            name to value
                        } else {
                            name to rehydrateSecret(refs, "mcp", server.id.toString(), "header:$name", value)
                        }
                    },
                    oauth = common.oauth?.let { oauth ->
                        oauth.copy(
                            clientSecret = oauth.clientSecret.rehydrateMcpOAuthSecret(
                                server.id.toString(), "oauth:clientSecret", refs,
                            ),
                            accessToken = oauth.accessToken.rehydrateMcpOAuthSecret(
                                server.id.toString(), "oauth:accessToken", refs,
                            ),
                            refreshToken = oauth.refreshToken.rehydrateMcpOAuthSecret(
                                server.id.toString(), "oauth:refreshToken", refs,
                            ),
                            authorizationEndpoint = oauth.authorizationEndpoint.rehydrateMcpOAuthUrl(
                                server.id.toString(), "oauth:authorizationEndpoint", refs,
                            ),
                            tokenEndpoint = oauth.tokenEndpoint.rehydrateMcpOAuthUrl(
                                server.id.toString(), "oauth:tokenEndpoint", refs,
                            ),
                            registrationEndpoint = oauth.registrationEndpoint.rehydrateMcpOAuthUrl(
                                server.id.toString(), "oauth:registrationEndpoint", refs,
                            ),
                        )
                    },
                )
            )
        }
    }

    private fun String?.rehydrateMcpOAuthSecret(
        ownerId: String,
        fieldName: String,
        refs: Map<String, SecretReference>,
    ): String? = this?.let { value ->
        rehydrateSecret(refs, "mcp", ownerId, fieldName, value)
    }

    private fun String?.rehydrateMcpOAuthUrl(
        ownerId: String,
        fieldName: String,
        refs: Map<String, SecretReference>,
    ): String? = this?.let { url ->
        rehydrateSecret(refs, "mcp", ownerId, fieldName, url)
    }

    private fun rehydrateMcpUrl(
        server: McpServerConfig,
        refs: Map<String, SecretReference>,
    ): String {
        val descriptor = SecretDescriptor("mcp", server.id.toString(), "url")
        return if (refs.containsKey(descriptor.key)) {
            rehydrateSecret(refs, "mcp", server.id.toString(), "url", server.mcpUrl())
        } else {
            server.mcpUrl()
        }
    }

    fun rehydrateWebDav(
        config: WebDavConfig,
        refs: Map<String, SecretReference>,
    ): WebDavConfig = config.copy(
        username = rehydrateSecret(refs, "webdav", "", "username", config.username),
        password = rehydrateSecret(refs, "webdav", "", "password", config.password),
    )

    fun rehydrateS3(
        config: S3Config,
        refs: Map<String, SecretReference>,
    ): S3Config = config.copy(
        accessKeyId = rehydrateSecret(refs, "s3", "", "accessKeyId", config.accessKeyId),
        secretAccessKey = rehydrateSecret(refs, "s3", "", "secretAccessKey", config.secretAccessKey),
    )

    private fun List<Model>.rehydrateModelHeaders(
        refs: Map<String, SecretReference>,
        scope: String,
        ownerId: String,
    ): List<Model> = map { model ->
        if (model.customHeaders.isEmpty()) {
            model
        } else {
            model.copy(
                customHeaders = model.customHeaders.rehydrateHeaders(refs, scope, ownerId)
            )
        }
    }

    private fun List<CustomHeader>.rehydrateHeaders(
        refs: Map<String, SecretReference>,
        scope: String,
        ownerId: String,
    ): List<CustomHeader> = map { header ->
        if (header.value.isBlank()) {
            header
        } else {
            header.copy(
                value = rehydrateSecret(refs, scope, ownerId, "customHeader:${header.name}", header.value)
            )
        }
    }

    // ---------------- 备份/导出边界 ----------------

    /**
     * 导出用持久化形式：所有敏感字段替换为掩码，refs 随 settings 一起序列化
     * （恢复端靠 refs 让掩码值走 keep 规则找回本机 secret；refs 不含真实值）。
     * 导出本身不写入 DataStore。
     */
    fun redactForExport(
        providers: List<ProviderSetting>,
        customHeaders: List<CustomHeader>,
        searchServices: List<SearchServiceOptions>,
        mcpServers: List<McpServerConfig>,
        webDavConfig: WebDavConfig,
        s3Config: S3Config,
    ): RedactedSettings = redactSettings(
        providers = providers,
        customHeaders = customHeaders,
        searchServices = searchServices,
        mcpServers = mcpServers,
        webDavConfig = webDavConfig,
        s3Config = s3Config,
        existingRefs = emptyMap(),
    )

    /** 把 refs 注入导出 JSON（合成键 `secretRefs`，旧版本 ignoreUnknownKeys 兼容）。 */
    fun settingsJsonWithRefs(json: Json, settingsJson: String, refs: List<SecretReference>): String {
        if (refs.isEmpty()) return settingsJson
        val obj: JsonObject = runCatching { json.parseToJsonElement(settingsJson).jsonObject }.getOrNull()
            ?: return settingsJson
        return buildJsonObject {
            obj.forEach { (key, value) -> put(key, value) }
            put(EXPORT_SECRET_REFS_KEY, json.parseToJsonElement(json.encodeToString(refs)))
        }.toString()
    }

    companion object {
        const val MASK_STRING = "••••"
        const val EXPORT_SECRET_REFS_KEY = "secretRefs"
    }
}

/** redact 后的各域持久化数据 + 完整 refs map。 */
data class RedactedSettings(
    val providers: List<ProviderSetting>,
    val customHeaders: List<CustomHeader>,
    val searchServices: List<SearchServiceOptions>,
    val mcpServers: List<McpServerConfig>,
    val webDavConfig: WebDavConfig,
    val s3Config: S3Config,
    val refs: Map<String, SecretReference>,
)
