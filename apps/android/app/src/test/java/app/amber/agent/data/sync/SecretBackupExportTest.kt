package app.amber.agent.data.sync

import app.amber.ai.provider.ProviderSetting
import app.amber.core.ai.mcp.McpCommonOptions
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.Settings
import app.amber.core.settings.WebDavConfig
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import app.amber.core.sync.core.SyncMode
import app.amber.core.sync.core.SyncRedactor
import app.amber.core.sync.encodeSettingsForBackup
import app.amber.core.sync.s3.S3Config
import app.amber.tts.provider.TTSProviderSetting
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-01: 备份/导出 JSON 无明文。
 * - 新同步归档（SyncArchiveManager 路径）：先 redactForExport（掩码+refs），
 *   FULL/STANDARD 模式编码后均无明文。
 * - 遗留 S3/WebDAV 导出（BackupSettingsRedactor 路径）：按字段名脱敏，无明文。
 */
class SecretBackupExportTest {
    // Robolectric 无真实 AndroidKeyStore，用内存 backend + 桩 cipher（复用 Keystore 包装模式）
    private val redactor = SecretRedactor(
        SecretStore(
            backend = object : SecretStoreBackend {
                private val map = mutableMapOf<String, String>()
                override fun get(key: String): String? = map[key]
                override fun put(key: String, value: String) {
                    map[key] = value
                }

                override fun remove(key: String) {
                    map.remove(key)
                }

                override fun keys(): Set<String> = map.keys.toSet()
            },
            cipher = object : SecretCipher {
                override fun encrypt(plaintext: String): String = "enc:$plaintext"
                override fun decrypt(stored: String): String? = stored.removePrefix("enc:")
            },
        )
    )

    private fun runtimeSettings(): Settings = Settings(
        providers = listOf(
            ProviderSetting.OpenAI(apiKey = "sk-export-plain-111"),
            ProviderSetting.Google(privateKey = "sa-export-key-222"),
        ),
        mcpServers = listOf(
            McpServerConfig.StreamableHTTPServer(
                commonOptions = McpCommonOptions(headers = listOf("Authorization" to "Bearer mcp-export-token")),
            )
        ),
        webDavConfig = WebDavConfig(url = "https://dav.example", username = "dav-user", password = "dav-export-pass"),
        s3Config = S3Config(accessKeyId = "AKIA-EXPORT", secretAccessKey = "s3-export-secret"),
        ttsProviders = listOf(TTSProviderSetting.Groq(apiKey = "groq-export-key")),
    )

    private fun exportedSettingsJson(settings: Settings, mode: SyncMode): String {
        val redacted = redactor.redactForExport(
            providers = settings.providers,
            assistants = settings.assistants,
            searchServices = settings.searchServices,
            mcpServers = settings.mcpServers,
            webDavConfig = settings.webDavConfig,
            s3Config = settings.s3Config,
            ttsProviders = settings.ttsProviders,
        )
        val redactedSettings = settings.copy(
            providers = redacted.providers,
            assistants = redacted.assistants,
            searchServices = redacted.searchServices,
            mcpServers = redacted.mcpServers,
            webDavConfig = redacted.webDavConfig,
            s3Config = redacted.s3Config,
            ttsProviders = redacted.ttsProviders,
        )
        val syncRedactor = SyncRedactor(JsonInstant)
        return redactor.settingsJsonWithRefs(
            JsonInstant,
            syncRedactor.encodeSettings(redactedSettings, mode),
            redacted.refs.values.toList(),
        )
    }

    @Test
    fun `sync archive export has no plaintext in FULL mode`() {
        val json = exportedSettingsJson(runtimeSettings(), SyncMode.FULL)
        assertFalse(json.contains("sk-export-plain-111"))
        assertFalse(json.contains("sa-export-key-222"))
        assertFalse(json.contains("mcp-export-token"))
        assertFalse(json.contains("dav-export-pass"))
        assertFalse(json.contains("s3-export-secret"))
        assertFalse(json.contains("groq-export-key"))
        assertTrue(json.contains("\"secretRefs\""))
        assertTrue(json.contains(SecretRedactor.MASK_STRING))
    }

    @Test
    fun `sync archive export has no plaintext in STANDARD mode`() {
        val json = exportedSettingsJson(runtimeSettings(), SyncMode.STANDARD)
        assertFalse(json.contains("sk-export-plain-111"))
        assertFalse(json.contains("mcp-export-token"))
        assertFalse(json.contains("dav-export-pass"))
        assertFalse(json.contains("s3-export-secret"))
    }

    @Test
    fun `legacy S3 and WebDAV backup export has no plaintext`() {
        val json = JsonInstant.encodeSettingsForBackup(runtimeSettings())
        assertFalse(json.contains("sk-export-plain-111"))
        assertFalse(json.contains("sa-export-key-222"))
        assertFalse(json.contains("mcp-export-token"))
        assertFalse(json.contains("dav-export-pass"))
        assertFalse("webdav username must not leak", json.contains("dav-user"))
        assertFalse("s3 accessKeyId must not leak", json.contains("AKIA-EXPORT"))
        assertFalse(json.contains("s3-export-secret"))
        assertFalse(json.contains("groq-export-key"))
    }

    @Test
    fun `restore can extract refs from exported settings json`() {
        val json = exportedSettingsJson(runtimeSettings(), SyncMode.FULL)
        val refs = redactor.extractRefsFromSettingsJson(JsonInstant, json)
        assertTrue(refs.isNotEmpty())
        assertTrue(refs.any { it.scope == "provider" && it.fieldName == "apiKey" })
        assertTrue(refs.any { it.scope == "mcp" && it.fieldName == "header:Authorization" })
        refs.forEach { ref -> assertTrue(ref.mask.startsWith(SecretRedactor.MASK_STRING)) }
    }
}
