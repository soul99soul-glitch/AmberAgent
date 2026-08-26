package app.amber.agent.data.sync

import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.CustomHeader
import app.amber.core.ai.mcp.McpCommonOptions
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.Settings
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.settings.WebDavConfig
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import app.amber.core.sync.core.SyncMode
import app.amber.core.sync.core.SyncRedactor
import app.amber.core.sync.encodeSettingsForBackup
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import app.amber.core.sync.s3.S3Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    )

    private fun exportedSettingsJson(settings: Settings, mode: SyncMode): String {
        val redacted = redactor.redactForExport(
            providers = settings.providers,
            customHeaders = settings.customHeaders,
            searchServices = settings.searchServices,
            mcpServers = settings.mcpServers,
            webDavConfig = settings.webDavConfig,
            s3Config = settings.s3Config,
        )
        val redactedSettings = settings.copy(
            providers = redacted.providers,
            customHeaders = redacted.customHeaders,
            searchServices = redacted.searchServices,
            mcpServers = redacted.mcpServers,
            webDavConfig = redacted.webDavConfig,
            s3Config = redacted.s3Config,
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

    @Test
    fun `restore keeps global Amber header refs`() {
        val settings = Settings(
            customHeaders = listOf(CustomHeader("Authorization", "Bearer amber")),
        )
        val json = exportedSettingsJson(settings, SyncMode.FULL)

        val refs = redactor.extractRefsFromSettingsJson(JsonInstant, json)
        val profileRef = refs.single { it.scope == "assistant" }
        val restored = redactor.rehydrateCustomHeaders(
            listOf(CustomHeader("Authorization", profileRef.mask)),
            refs.associateBy { it.descriptor().key },
        )

        assertEquals(AMBER_AGENT_ID.toString(), profileRef.ownerId)
        assertEquals("Bearer amber", restored.single().value)
    }

    @Test
    fun `standard restore merges local secret before flattening legacy assistants`() {
        val legacyId = kotlin.uuid.Uuid.random()
        val local = Settings(
            customHeaders = listOf(CustomHeader("Authorization", "Bearer local-v1")),
        )
        val base = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(Settings())).jsonObject
        val legacyProfile = buildJsonObject {
            put("id", JsonPrimitive(legacyId.toString()))
            put(
                "customHeaders",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("name", JsonPrimitive("Authorization"))
                            put("value", JsonPrimitive("__MASKED_BY_AMBERAGENT_SYNC__"))
                        }
                    )
                ),
            )
        }
        val historical = JsonObject(
            base.toMutableMap().apply {
                this["assistantId"] = JsonPrimitive(legacyId.toString())
                this["assistants"] = JsonArray(listOf(legacyProfile))
            }
        ).toString()

        val restored = SyncRedactor(JsonInstant).decodeSettingsForRestore(
            settingsJson = historical,
            mode = SyncMode.STANDARD,
            localSettings = local,
        )

        assertEquals("Bearer local-v1", restored.customHeaders.single().value)
    }
}
