package app.amber.core.settings.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.amber.core.infra.AppScope
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.settings.BackupReminderConfig
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.WebDavConfig
import app.amber.core.model.Lorebook
import app.amber.core.model.PromptInjection
import app.amber.core.model.QuickMessage
import app.amber.core.sync.core.SyncSettings
import app.amber.core.sync.s3.S3Config
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.toMutableStateFlow
import kotlin.uuid.Uuid

data class ExtensionPrefsData(
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val modeInjections: List<PromptInjection.ModeInjection> = emptyList(),
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val enabledSkills: Set<String> = emptySet(),
    val enabledMcpServerIds: Set<Uuid> = emptySet(),
    val enabledModeInjectionIds: Set<Uuid> = emptySet(),
    val enabledLorebookIds: Set<Uuid> = emptySet(),
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val syncSettings: SyncSettings = SyncSettings(),
    val routingQuickMessagesSeededVersion: Int = 0,
)

class ExtensionPrefs(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
    private val secretStore: SecretStore,
) {
    private val redactor = SecretRedactor(secretStore)

    internal val rawFlow: Flow<ExtensionPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { readFrom(it) }
        .distinctUntilChanged()

    val flow: StateFlow<ExtensionPrefsData> = rawFlow
        .toMutableStateFlow(scope, ExtensionPrefsData())

    suspend fun update(transform: (ExtensionPrefsData) -> ExtensionPrefsData) {
        dataStore.edit { p ->
            val current = readFrom(p)
            val next = transform(current)
            if (next == current) return@edit
            writeTo(p, next)
        }
    }

    private fun readFrom(p: Preferences): ExtensionPrefsData {
        val refs = redactor.readRefs(p)
        return ExtensionPrefsData(
            mcpServers = p[PreferencesKeys.MCP_SERVERS]?.let { raw ->
                raw.decodeJsonOrNull<List<McpServerConfig>>()?.let {
                    redactor.rehydrateMcpServers(it, refs)
                }
            } ?: emptyList(),
            webDavConfig = p[PreferencesKeys.WEBDAV_CONFIG]?.let { raw ->
                raw.decodeJsonOrNull<WebDavConfig>()?.let {
                    redactor.rehydrateWebDav(it, refs)
                }
            } ?: WebDavConfig(),
            s3Config = p[PreferencesKeys.S3_CONFIG]?.let { raw ->
                raw.decodeJsonOrNull<S3Config>()?.let {
                    redactor.rehydrateS3(it, refs)
                }
            } ?: S3Config(),
            modeInjections = p[PreferencesKeys.MODE_INJECTIONS]?.let {
                it.decodeJsonOrNull<List<PromptInjection.ModeInjection>>()
            } ?: emptyList(),
            lorebooks = p[PreferencesKeys.LOREBOOKS]?.let {
                it.decodeJsonOrNull<List<Lorebook>>()
            } ?: emptyList(),
            quickMessages = p[PreferencesKeys.QUICK_MESSAGES]?.let {
                it.decodeJsonOrNull<List<QuickMessage>>()
            } ?: emptyList(),
            enabledSkills = p[PreferencesKeys.AMBER_ENABLED_SKILLS]?.let {
                it.decodeJsonOrNull<Set<String>>()
            } ?: emptySet(),
            enabledMcpServerIds = p[PreferencesKeys.AMBER_ENABLED_MCP_SERVER_IDS]?.let {
                it.decodeJsonOrNull<Set<Uuid>>()
            } ?: emptySet(),
            enabledModeInjectionIds = p[PreferencesKeys.AMBER_ENABLED_MODE_INJECTION_IDS]?.let {
                it.decodeJsonOrNull<Set<Uuid>>()
            } ?: emptySet(),
            enabledLorebookIds = p[PreferencesKeys.AMBER_ENABLED_LOREBOOK_IDS]?.let {
                it.decodeJsonOrNull<Set<Uuid>>()
            } ?: emptySet(),
            backupReminderConfig = p[PreferencesKeys.BACKUP_REMINDER_CONFIG]?.let {
                it.decodeJsonOrNull<BackupReminderConfig>()
            } ?: BackupReminderConfig(),
            syncSettings = p[PreferencesKeys.SYNC_SETTINGS]?.let {
                it.decodeJsonOrNull<SyncSettings>()
            } ?: SyncSettings(),
            routingQuickMessagesSeededVersion =
                if (p[PreferencesKeys.SEEDED_ROUTING_QUICK_MESSAGES_V1] == true) 1 else 0,
        )
    }

    private fun writeTo(p: MutablePreferences, data: ExtensionPrefsData) {
        // P1-01: redaction 由 SettingsAggregator.writeSettings 统一执行
        p[PreferencesKeys.MCP_SERVERS] = JsonInstant.encodeToString(data.mcpServers)
        p[PreferencesKeys.WEBDAV_CONFIG] = JsonInstant.encodeToString(data.webDavConfig)
        p[PreferencesKeys.S3_CONFIG] = JsonInstant.encodeToString(data.s3Config)
        p[PreferencesKeys.MODE_INJECTIONS] = JsonInstant.encodeToString(data.modeInjections)
        p[PreferencesKeys.LOREBOOKS] = JsonInstant.encodeToString(data.lorebooks)
        p[PreferencesKeys.QUICK_MESSAGES] = JsonInstant.encodeToString(data.quickMessages)
        p[PreferencesKeys.AMBER_ENABLED_SKILLS] = JsonInstant.encodeToString(data.enabledSkills)
        p[PreferencesKeys.AMBER_ENABLED_MCP_SERVER_IDS] =
            JsonInstant.encodeToString(data.enabledMcpServerIds)
        p[PreferencesKeys.AMBER_ENABLED_MODE_INJECTION_IDS] =
            JsonInstant.encodeToString(data.enabledModeInjectionIds)
        p[PreferencesKeys.AMBER_ENABLED_LOREBOOK_IDS] =
            JsonInstant.encodeToString(data.enabledLorebookIds)
        p[PreferencesKeys.BACKUP_REMINDER_CONFIG] =
            JsonInstant.encodeToString(data.backupReminderConfig)
        p[PreferencesKeys.SYNC_SETTINGS] = JsonInstant.encodeToString(data.syncSettings)
        if (data.routingQuickMessagesSeededVersion > 0) {
            p[PreferencesKeys.SEEDED_ROUTING_QUICK_MESSAGES_V1] = true
        }
    }
}
