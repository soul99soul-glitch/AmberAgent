package app.amber.core.settings.secret

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import app.amber.ai.provider.ProviderSetting
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.model.Assistant
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.WebDavConfig
import app.amber.core.settings.prefs.decodeJsonOrNull
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.sync.s3.S3Config
import app.amber.search.SearchServiceOptions
import app.amber.tts.provider.TTSProviderSetting

private const val TAG = "SettingsSecretMigrator"

/**
 * P1-01 幂等迁移：把 Preferences DataStore 中的明文字段迁移进 SecretStore，
 * 设置里只留 reference + 掩码位。
 *
 * 步骤（严格按计划）：
 * 1. 先写 SecretStore（upsert，幂等）。
 * 2. 验证可读：每个新建 reference 都能读回真实值；任一读不回 → 中止，不写 DataStore。
 * 3. 再把 DataStore 中明文替换为掩码 + reference。
 * 4. 成功后持久化迁移版本标记；迁移中断后重跑是 no-op 或继续完成。
 * 失败不删旧值：写 SecretStore 失败的字段保持明文落盘（短期兼容旧明文字段）。
 */
class SettingsSecretMigrator(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: SecretStore,
    private val redactor: SecretRedactor,
) {
    /**
     * 返回当前迁移版本。已迁移（版本达标）时直接返回，不做任何写操作。
     */
    suspend fun migrateIfNeeded(): Int {
        if (secretStore.migrationVersion() >= MIGRATION_VERSION) {
            return secretStore.migrationVersion()
        }
        var changed = false
        var aborted = false
        dataStore.edit { p ->
            redactor.resetWriteFailures()
            val existingRefs = redactor.readRefs(p)
            val out = mutableMapOf<String, SecretReference>()

            val providersJson = p[PreferencesKeys.PROVIDERS]
            val providers = providersJson?.decodeJsonOrNull<List<ProviderSetting>>() ?: emptyList()
            val redactedProviders = redactor.redactProviders(providers, existingRefs, out)
            if (providersJson != null && JsonInstant.encodeToString(redactedProviders) != providersJson) {
                changed = true
            }

            val assistantsJson = p[PreferencesKeys.ASSISTANTS]
            val assistants = assistantsJson?.decodeJsonOrNull<List<Assistant>>() ?: emptyList()
            val redactedAssistants = redactor.redactAssistants(assistants, existingRefs, out)
            if (assistantsJson != null && JsonInstant.encodeToString(redactedAssistants) != assistantsJson) {
                changed = true
            }

            val searchJson = p[PreferencesKeys.SEARCH_SERVICES]
            val searchServices = searchJson?.decodeJsonOrNull<List<SearchServiceOptions>>() ?: emptyList()
            val redactedSearch = redactor.redactSearchServices(searchServices, existingRefs, out)
            if (searchJson != null && JsonInstant.encodeToString(redactedSearch) != searchJson) {
                changed = true
            }

            val mcpJson = p[PreferencesKeys.MCP_SERVERS]
            val mcpServers = mcpJson?.decodeJsonOrNull<List<McpServerConfig>>() ?: emptyList()
            val redactedMcp = redactor.redactMcpServers(mcpServers, existingRefs, out)
            if (mcpJson != null && JsonInstant.encodeToString(redactedMcp) != mcpJson) {
                changed = true
            }

            val webDavJson = p[PreferencesKeys.WEBDAV_CONFIG]
            val webDav = webDavJson?.decodeJsonOrNull<WebDavConfig>() ?: WebDavConfig()
            val redactedWebDav = redactor.redactWebDav(webDav, existingRefs, out)
            if (webDavJson != null && JsonInstant.encodeToString(redactedWebDav) != webDavJson) {
                changed = true
            }

            val s3Json = p[PreferencesKeys.S3_CONFIG]
            val s3 = s3Json?.decodeJsonOrNull<S3Config>() ?: S3Config()
            val redactedS3 = redactor.redactS3(s3, existingRefs, out)
            if (s3Json != null && JsonInstant.encodeToString(redactedS3) != s3Json) {
                changed = true
            }

            val ttsJson = p[PreferencesKeys.TTS_PROVIDERS]
            val ttsProviders = ttsJson?.decodeJsonOrNull<List<TTSProviderSetting>>() ?: emptyList()
            val redactedTts = redactor.redactTtsProviders(ttsProviders, existingRefs, out)
            if (ttsJson != null && JsonInstant.encodeToString(redactedTts) != ttsJson) {
                changed = true
            }

            // 验证可读：任一新建 reference 读不回真实值 → 中止，旧明文原样保留
            val writeFailures = redactor.takeWriteFailures()
            val unreadable = out.values
                .filter { it.descriptor().key !in existingRefs }
                .filter { secretStore.read(it.descriptor()) == null }
            if (writeFailures.isNotEmpty() || unreadable.isNotEmpty()) {
                Log.e(
                    TAG,
                    "Secret migration aborted: writeFailures=$writeFailures, unreadable=${unreadable.map { it.descriptor().key }}; keeping legacy plaintext"
                )
                changed = false
                aborted = true
                return@edit
            }

            if (changed) {
                p[PreferencesKeys.PROVIDERS] = JsonInstant.encodeToString(redactedProviders)
                p[PreferencesKeys.ASSISTANTS] = JsonInstant.encodeToString(redactedAssistants)
                p[PreferencesKeys.SEARCH_SERVICES] = JsonInstant.encodeToString(redactedSearch)
                p[PreferencesKeys.MCP_SERVERS] = JsonInstant.encodeToString(redactedMcp)
                p[PreferencesKeys.WEBDAV_CONFIG] = JsonInstant.encodeToString(redactedWebDav)
                p[PreferencesKeys.S3_CONFIG] = JsonInstant.encodeToString(redactedS3)
                p[PreferencesKeys.TTS_PROVIDERS] = JsonInstant.encodeToString(redactedTts)
                redactor.writeRefs(p, out)
            }
        }
        if (aborted) {
            // 失败不删旧值、不标记完成：下次启动重跑
            return secretStore.migrationVersion()
        }
        if (changed) {
            // 迁移完成标记持久化
            secretStore.markMigrated(MIGRATION_VERSION)
            // orphan 回收：只删确认不再被任何设置引用的项
            val refs = dataStore.data.first().let { redactor.readRefs(it) }
            secretStore.deleteOrphans(refs.values.map { it.descriptor() }.toSet())
            Log.i(TAG, "Secret migration to version $MIGRATION_VERSION completed")
        } else {
            // DataStore 已是迁移后形态（或没有敏感字段）：仅补齐版本标记
            secretStore.markMigrated(MIGRATION_VERSION)
        }
        return secretStore.migrationVersion()
    }

    companion object {
        const val MIGRATION_VERSION = 1
    }
}
