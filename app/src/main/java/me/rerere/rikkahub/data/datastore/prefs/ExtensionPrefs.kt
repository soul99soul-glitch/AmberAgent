package me.rerere.rikkahub.data.datastore.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.BackupReminderConfig
import me.rerere.rikkahub.data.datastore.DEFAULT_SYSTEM_TTS_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.sync.core.SyncSettings
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

data class ExtensionPrefsData(
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = emptyList(),
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val modeInjections: List<PromptInjection.ModeInjection> = emptyList(),
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val syncSettings: SyncSettings = SyncSettings(),
    val routingQuickMessagesSeededVersion: Int = 0,
)

class ExtensionPrefs(
    private val dataStore: DataStore<Preferences>,
    scope: AppScope,
) {
    val flow: StateFlow<ExtensionPrefsData> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { readFrom(it) }
        .distinctUntilChanged()
        .toMutableStateFlow(scope, ExtensionPrefsData())

    suspend fun update(transform: (ExtensionPrefsData) -> ExtensionPrefsData) {
        dataStore.edit { p ->
            val current = readFrom(p)
            val next = transform(current)
            if (next == current) return@edit
            writeTo(p, next)
        }
    }

    private fun readFrom(p: Preferences): ExtensionPrefsData = ExtensionPrefsData(
        mcpServers = p[SettingsStore.MCP_SERVERS]?.let {
            JsonInstant.decodeFromString<List<McpServerConfig>>(it)
        } ?: emptyList(),
        webDavConfig = p[SettingsStore.WEBDAV_CONFIG]?.let {
            JsonInstant.decodeFromString<WebDavConfig>(it)
        } ?: WebDavConfig(),
        s3Config = p[SettingsStore.S3_CONFIG]?.let {
            JsonInstant.decodeFromString<S3Config>(it)
        } ?: S3Config(),
        ttsProviders = p[SettingsStore.TTS_PROVIDERS]?.let {
            JsonInstant.decodeFromString<List<TTSProviderSetting>>(it)
        } ?: emptyList(),
        selectedTTSProviderId = p[SettingsStore.SELECTED_TTS_PROVIDER]
            ?.let { Uuid.parse(it) } ?: DEFAULT_SYSTEM_TTS_ID,
        modeInjections = p[SettingsStore.MODE_INJECTIONS]?.let {
            JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(it)
        } ?: emptyList(),
        lorebooks = p[SettingsStore.LOREBOOKS]?.let {
            JsonInstant.decodeFromString<List<Lorebook>>(it)
        } ?: emptyList(),
        quickMessages = p[SettingsStore.QUICK_MESSAGES]?.let {
            JsonInstant.decodeFromString<List<QuickMessage>>(it)
        } ?: emptyList(),
        webServerEnabled = p[SettingsStore.WEB_SERVER_ENABLED] == true,
        webServerPort = p[SettingsStore.WEB_SERVER_PORT] ?: 8080,
        webServerJwtEnabled = p[SettingsStore.WEB_SERVER_JWT_ENABLED] == true,
        webServerAccessPassword = p[SettingsStore.WEB_SERVER_ACCESS_PASSWORD] ?: "",
        webServerLocalhostOnly = p[SettingsStore.WEB_SERVER_LOCALHOST_ONLY] == true,
        backupReminderConfig = p[SettingsStore.BACKUP_REMINDER_CONFIG]?.let {
            JsonInstant.decodeFromString<BackupReminderConfig>(it)
        } ?: BackupReminderConfig(),
        syncSettings = p[SettingsStore.SYNC_SETTINGS]?.let {
            JsonInstant.decodeFromString<SyncSettings>(it)
        } ?: SyncSettings(),
        routingQuickMessagesSeededVersion =
            if (p[SettingsStore.SEEDED_ROUTING_QUICK_MESSAGES_V1] == true) 1 else 0,
    )

    private fun writeTo(p: MutablePreferences, data: ExtensionPrefsData) {
        p[SettingsStore.MCP_SERVERS] = JsonInstant.encodeToString(data.mcpServers)
        p[SettingsStore.WEBDAV_CONFIG] = JsonInstant.encodeToString(data.webDavConfig)
        p[SettingsStore.S3_CONFIG] = JsonInstant.encodeToString(data.s3Config)
        p[SettingsStore.TTS_PROVIDERS] = JsonInstant.encodeToString(data.ttsProviders)
        p[SettingsStore.SELECTED_TTS_PROVIDER] = data.selectedTTSProviderId.toString()
        p[SettingsStore.MODE_INJECTIONS] = JsonInstant.encodeToString(data.modeInjections)
        p[SettingsStore.LOREBOOKS] = JsonInstant.encodeToString(data.lorebooks)
        p[SettingsStore.QUICK_MESSAGES] = JsonInstant.encodeToString(data.quickMessages)
        p[SettingsStore.WEB_SERVER_ENABLED] = data.webServerEnabled
        p[SettingsStore.WEB_SERVER_PORT] = data.webServerPort
        p[SettingsStore.WEB_SERVER_JWT_ENABLED] = data.webServerJwtEnabled
        p[SettingsStore.WEB_SERVER_ACCESS_PASSWORD] = data.webServerAccessPassword
        p[SettingsStore.WEB_SERVER_LOCALHOST_ONLY] = data.webServerLocalhostOnly
        p[SettingsStore.BACKUP_REMINDER_CONFIG] =
            JsonInstant.encodeToString(data.backupReminderConfig)
        p[SettingsStore.SYNC_SETTINGS] = JsonInstant.encodeToString(data.syncSettings)
        if (data.routingQuickMessagesSeededVersion > 0) {
            p[SettingsStore.SEEDED_ROUTING_QUICK_MESSAGES_V1] = true
        }
    }
}
