package app.amber.core.settings.secret

import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.core.ai.mcp.McpCommonOptions
import app.amber.core.ai.mcp.McpOAuthState
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.WebDavConfig
import app.amber.core.sync.s3.S3Config
import app.amber.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    // ---------------- Provider round-trip ----------------

    @Test
    fun `provider apiKey and model custom headers redact on save and rehydrate on read`() {
        val store = fakeSecretStore()
        val redactor = SecretRedactor(store)
        val provider = ProviderSetting.OpenAI(
            apiKey = "sk-roundtrip-1234",
            models = listOf(
                Model(
                    customHeaders = listOf(
                        CustomHeader("Authorization", "Bearer header-secret-99"),
                    )
                )
            ),
        )

        val out = mutableMapOf<String, SecretReference>()
        val redacted = redactor.redactProviders(listOf(provider), emptyMap(), out)

        // 掩码 + 不落明文
        assertEquals(SecretRedactor.MASK_STRING + "1234", (redacted.single() as ProviderSetting.OpenAI).apiKey)
        val json = JsonInstant.encodeToString(redacted)
        assertFalse("persisted providers JSON must not contain apiKey plaintext", json.contains("sk-roundtrip-1234"))
        assertFalse("persisted providers JSON must not contain header plaintext", json.contains("header-secret-99"))
        assertTrue(json.contains(SecretRedactor.MASK_STRING))
        assertEquals(2, out.size)

        // 业务使用边界 rehydration：读取设置时按需还原
        val rehydrated = redactor.rehydrateProviders(redacted, out)
        val back = rehydrated.single() as ProviderSetting.OpenAI
        assertEquals("sk-roundtrip-1234", back.apiKey)
        assertEquals("Bearer header-secret-99", back.models.single().customHeaders.single().value)
        assertEquals("sk-roundtrip-1234", store.read(out.keys.first().let { SecretDescriptor.fromKey(it)!! }))
    }

    @Test
    fun `google privateKey and claude apiKey also round-trip`() {
        val store = fakeSecretStore()
        val redactor = SecretRedactor(store)
        val google = ProviderSetting.Google(apiKey = "google-key-777", privateKey = "sa-private-key-888")
        val claude = ProviderSetting.Claude(apiKey = "claude-key-666")

        val out = mutableMapOf<String, SecretReference>()
        val redacted = redactor.redactProviders(listOf(google, claude), emptyMap(), out)
        assertFalse(JsonInstant.encodeToString(redacted).contains("sa-private-key-888"))

        val rehydrated = redactor.rehydrateProviders(redacted, out)
        assertEquals("google-key-777", (rehydrated[0] as ProviderSetting.Google).apiKey)
        assertEquals("sa-private-key-888", (rehydrated[0] as ProviderSetting.Google).privateKey)
        assertEquals("claude-key-666", (rehydrated[1] as ProviderSetting.Claude).apiKey)
    }

    // ---------------- MCP round-trip ----------------

    @Test
    fun `mcp headers redact on save and rehydrate on read`() {
        val store = fakeSecretStore()
        val redactor = SecretRedactor(store)
        val server = McpServerConfig.StreamableHTTPServer(
            url = "https://example.com/mcp",
            commonOptions = McpCommonOptions(
                headers = listOf("Authorization" to "Bearer mcp-token-555"),
            ),
        )

        val out = mutableMapOf<String, SecretReference>()
        val redacted = redactor.redactMcpServers(listOf(server), emptyMap(), out)

        val json = JsonInstant.encodeToString(redacted)
        assertFalse("persisted MCP JSON must not contain header plaintext", json.contains("mcp-token-555"))
        assertTrue(json.contains(SecretRedactor.MASK_STRING))

        val rehydrated = redactor.rehydrateMcpServers(redacted, out)
        assertEquals("Bearer mcp-token-555", rehydrated.single().commonOptions.headers.single().second)
        assertEquals("Authorization", rehydrated.single().commonOptions.headers.single().first)
    }

    @Test
    fun `mcp oauth secrets redact on save and rehydrate on read`() {
        val store = fakeSecretStore()
        val redactor = SecretRedactor(store)
        val server = McpServerConfig.StreamableHTTPServer(
            url = "https://example.com/mcp",
            commonOptions = McpCommonOptions(
                oauth = McpOAuthState(
                    enabled = true,
                    clientId = "client-id",
                    clientSecret = "client-secret-1234",
                    accessToken = "access-token-5678",
                    refreshToken = "refresh-token-9012",
                ),
            ),
        )

        val out = mutableMapOf<String, SecretReference>()
        val redacted = redactor.redactMcpServers(listOf(server), emptyMap(), out)
        val json = JsonInstant.encodeToString(redacted)
        assertFalse(json.contains("client-secret-1234"))
        assertFalse(json.contains("access-token-5678"))
        assertFalse(json.contains("refresh-token-9012"))
        assertEquals(3, out.size)

        val rehydrated = redactor.rehydrateMcpServers(redacted, out).single()
        val oauth = requireNotNull(rehydrated.commonOptions.oauth)
        assertEquals("client-secret-1234", oauth.clientSecret)
        assertEquals("access-token-5678", oauth.accessToken)
        assertEquals("refresh-token-9012", oauth.refreshToken)
    }

    // ---------------- 其余设置类型轻量 round-trip ----------------

    @Test
    fun `search webdav s3 round-trip through redactor`() {
        val store = fakeSecretStore()
        val redactor = SecretRedactor(store)
        val out = mutableMapOf<String, SecretReference>()

        val search = listOf(
            SearchServiceOptions.TavilyOptions(apiKey = "tavily-key-111"),
            SearchServiceOptions.SearXNGOptions(url = "https://searx.example", username = "sx-user", password = "sx-pass"),
        )
        val redactedSearch = redactor.redactSearchServices(search, emptyMap(), out)
        assertFalse(JsonInstant.encodeToString(redactedSearch).contains("tavily-key-111"))
        val rehydratedSearch = redactor.rehydrateSearchServices(redactedSearch, out)
        assertEquals("tavily-key-111", (rehydratedSearch[0] as SearchServiceOptions.TavilyOptions).apiKey)
        assertEquals("sx-pass", (rehydratedSearch[1] as SearchServiceOptions.SearXNGOptions).password)

        val webDav = WebDavConfig(url = "https://dav.example", username = "dav-user", password = "dav-pass")
        val redactedWebDav = redactor.redactWebDav(webDav, emptyMap(), out)
        assertFalse(JsonInstant.encodeToString(redactedWebDav).contains("dav-pass"))
        val rehydratedWebDav = redactor.rehydrateWebDav(redactedWebDav, out)
        assertEquals("dav-pass", rehydratedWebDav.password)

        val s3 = S3Config(accessKeyId = "AKIA-123", secretAccessKey = "s3-secret-444")
        val redactedS3 = redactor.redactS3(s3, emptyMap(), out)
        assertFalse(JsonInstant.encodeToString(redactedS3).contains("s3-secret-444"))
        val rehydratedS3 = redactor.rehydrateS3(redactedS3, out)
        assertEquals("s3-secret-444", rehydratedS3.secretAccessKey)
    }

    // ---------------- 掩码语义 ----------------

    @Test
    fun `value equal to existing ref mask is kept without rewriting secret`() {
        val store = fakeSecretStore()
        val redactor = SecretRedactor(store)
        val provider = ProviderSetting.OpenAI(apiKey = "sk-same-mask-1234")

        val firstOut = mutableMapOf<String, SecretReference>()
        redactor.redactProviders(listOf(provider), emptyMap(), firstOut)
        val storedBefore = store.listOrphans(emptySet()).size

        // 恢复/回环场景：值 = 已有 reference 的掩码 → 保留原 secret，不重写
        val restored = provider.copy(apiKey = firstOut.values.single().mask)
        val secondOut = mutableMapOf<String, SecretReference>()
        val redacted = redactor.redactProviders(listOf(restored), firstOut, secondOut)

        assertEquals(firstOut.values.single().mask, (redacted.single() as ProviderSetting.OpenAI).apiKey)
        assertEquals(storedBefore, store.listOrphans(emptySet()).size)
        assertEquals("sk-same-mask-1234", store.read(firstOut.values.single().descriptor()))
    }

    @Test
    fun `blank value produces no reference`() {
        val store = fakeSecretStore()
        val redactor = SecretRedactor(store)
        val provider = ProviderSetting.OpenAI(apiKey = "")

        val out = mutableMapOf<String, SecretReference>()
        val redacted = redactor.redactProviders(listOf(provider), emptyMap(), out)

        assertTrue(out.isEmpty())
        assertEquals("", (redacted.single() as ProviderSetting.OpenAI).apiKey)
    }

    @Test
    fun `already-masked value without ref is left as-is without creating a secret`() {
        val store = fakeSecretStore()
        val redactor = SecretRedactor(store)
        val provider = ProviderSetting.OpenAI(apiKey = SecretRedactor.MASK_STRING + "1234")

        val out = mutableMapOf<String, SecretReference>()
        val redacted = redactor.redactProviders(listOf(provider), emptyMap(), out)

        assertTrue(out.isEmpty())
        assertTrue(store.listOrphans(emptySet()).isEmpty())
        assertEquals(SecretRedactor.MASK_STRING + "1234", (redacted.single() as ProviderSetting.OpenAI).apiKey)
    }
}
