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
import app.amber.core.settings.DEFAULT_SYSTEM_TTS_ID
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.WebDavConfig
import app.amber.core.model.Lorebook
import app.amber.core.model.PromptInjection
import app.amber.core.model.QuickMessage
import app.amber.core.sync.core.SyncSettings
import app.amber.core.sync.s3.S3Config
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.toMutableStateFlow
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

    private fun readFrom(p: Preferences): ExtensionPrefsData = ExtensionPrefsData(
        mcpServers = p[PreferencesKeys.MCP_SERVERS]?.let {
            JsonInstant.decodeFromString<List<McpServerConfig>>(it)
        } ?: emptyList(),
        webDavConfig = p[PreferencesKeys.WEBDAV_CONFIG]?.let {
            JsonInstant.decodeFromString<WebDavConfig>(it)
        } ?: WebDavConfig(),
        s3Config = p[PreferencesKeys.S3_CONFIG]?.let {
            JsonInstant.decodeFromString<S3Config>(it)
        } ?: S3Config(),
        ttsProviders = p[PreferencesKeys.TTS_PROVIDERS]?.let {
            JsonInstant.decodeFromString<List<TTSProviderSetting>>(it)
        } ?: emptyList(),
        selectedTTSProviderId = p[PreferencesKeys.SELECTED_TTS_PROVIDER]
            ?.let { Uuid.parse(it) } ?: DEFAULT_SYSTEM_TTS_ID,
        modeInjections = p[PreferencesKeys.MODE_INJECTIONS]?.let {
            JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(it)
        } ?: emptyList(),
        lorebooks = p[PreferencesKeys.LOREBOOKS]?.let {
            JsonInstant.decodeFromString<List<Lorebook>>(it)
        } ?: emptyList(),
        quickMessages = p[PreferencesKeys.QUICK_MESSAGES]?.let {
            JsonInstant.decodeFromString<List<QuickMessage>>(it)
        } ?: emptyList(),
        webServerEnabled = p[PreferencesKeys.WEB_SERVER_ENABLED] == true,
        webServerPort = p[PreferencesKeys.WEB_SERVER_PORT] ?: 8080,
        webServerJwtEnabled = p[PreferencesKeys.WEB_SERVER_JWT_ENABLED] == true,
        webServerAccessPassword = p[PreferencesKeys.WEB_SERVER_ACCESS_PASSWORD] ?: "",
        webServerLocalhostOnly = p[PreferencesKeys.WEB_SERVER_LOCALHOST_ONLY] == true,
        backupReminderConfig = p[PreferencesKeys.BACKUP_REMINDER_CONFIG]?.let {
            JsonInstant.decodeFromString<BackupReminderConfig>(it)
        } ?: BackupReminderConfig(),
        syncSettings = p[PreferencesKeys.SYNC_SETTINGS]?.let {
            JsonInstant.decodeFromString<SyncSettings>(it)
        } ?: SyncSettings(),
        routingQuickMessagesSeededVersion =
            if (p[PreferencesKeys.SEEDED_ROUTING_QUICK_MESSAGES_V1] == true) 1 else 0,
    )

    private fun writeTo(p: MutablePreferences, data: ExtensionPrefsData) {
        p[PreferencesKeys.MCP_SERVERS] = JsonInstant.encodeToString(data.mcpServers)
        p[PreferencesKeys.WEBDAV_CONFIG] = JsonInstant.encodeToString(data.webDavConfig)
        p[PreferencesKeys.S3_CONFIG] = JsonInstant.encodeToString(data.s3Config)
        p[PreferencesKeys.TTS_PROVIDERS] = JsonInstant.encodeToString(data.ttsProviders)
        p[PreferencesKeys.SELECTED_TTS_PROVIDER] = data.selectedTTSProviderId.toString()
        p[PreferencesKeys.MODE_INJECTIONS] = JsonInstant.encodeToString(data.modeInjections)
        p[PreferencesKeys.LOREBOOKS] = JsonInstant.encodeToString(data.lorebooks)
        p[PreferencesKeys.QUICK_MESSAGES] = JsonInstant.encodeToString(data.quickMessages)
        p[PreferencesKeys.WEB_SERVER_ENABLED] = data.webServerEnabled
        p[PreferencesKeys.WEB_SERVER_PORT] = data.webServerPort
        p[PreferencesKeys.WEB_SERVER_JWT_ENABLED] = data.webServerJwtEnabled
        p[PreferencesKeys.WEB_SERVER_ACCESS_PASSWORD] = data.webServerAccessPassword
        p[PreferencesKeys.WEB_SERVER_LOCALHOST_ONLY] = data.webServerLocalhostOnly
        p[PreferencesKeys.BACKUP_REMINDER_CONFIG] =
            JsonInstant.encodeToString(data.backupReminderConfig)
        p[PreferencesKeys.SYNC_SETTINGS] = JsonInstant.encodeToString(data.syncSettings)
        if (data.routingQuickMessagesSeededVersion > 0) {
            p[PreferencesKeys.SEEDED_ROUTING_QUICK_MESSAGES_V1] = true
        }
    }
}
