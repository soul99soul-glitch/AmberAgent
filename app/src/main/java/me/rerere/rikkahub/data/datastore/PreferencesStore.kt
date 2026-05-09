package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.IOException
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.agent.live.LiveModeSetting
import me.rerere.rikkahub.data.agent.modelcouncil.ModelCouncilRuntimeSetting
import me.rerere.rikkahub.data.agent.office.FeishuOfficeEnhancementSetting
import me.rerere.rikkahub.data.agent.subagent.SubAgentRuntimeSetting
import me.rerere.rikkahub.data.agent.terminal.TerminalRuntimeKind
import me.rerere.rikkahub.data.ai.GenerationRetrySetting
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.context.CompactPolicy
import me.rerere.rikkahub.data.memory.model.MemoryRecallSetting
import me.rerere.rikkahub.data.memory.model.MemoryWorkerSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration()
        )
    }
)

class SettingsStore(
    context: Context,
    scope: AppScope,
) : KoinComponent {
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val ENABLE_WEB_SEARCH = booleanPreferencesKey("enable_web_search")
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        val MODEL_GROUP_SESSION_DEFAULTS = stringPreferencesKey("model_group_session_defaults")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")
        val SEARCH_ENABLED_SERVICE_IDS = stringPreferencesKey("search_enabled_service_ids")
        val SEARCH_BUILTIN_DUCKDUCKGO_ENABLED = booleanPreferencesKey("search_builtin_duckduckgo_enabled")
        val SEARCH_BUILTIN_BING_ENABLED = booleanPreferencesKey("search_builtin_bing_enabled")
        val SEARCH_BUILTIN_JINA_ENABLED = booleanPreferencesKey("search_builtin_jina_enabled")
        val SEARCH_BUILTIN_WIKIPEDIA_ENABLED = booleanPreferencesKey("search_builtin_wikipedia_enabled")
        val SEARCH_BUILTIN_HACKERNEWS_ENABLED = booleanPreferencesKey("search_builtin_hackernews_enabled")
        val SEARCH_GOOGLE_WEBVIEW_FALLBACK_ENABLED = booleanPreferencesKey("search_google_webview_fallback_enabled")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")
        val AGENT_RUNTIME = stringPreferencesKey("agent_runtime")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")
    }

    private val dataStore = context.settingsStore

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { preferences ->
            val searchServices = preferences[SEARCH_SERVICES]?.let {
                JsonInstant.decodeFromString<List<SearchServiceOptions>>(it)
            } ?: listOf(SearchServiceOptions.DEFAULT)
            val selectedSearchIndex = preferences[SEARCH_SELECTED]?.let { selected ->
                if (searchServices.isEmpty()) 0 else selected.coerceIn(0, searchServices.lastIndex)
            } ?: 0
            val enabledSearchServiceIds = preferences[SEARCH_ENABLED_SERVICE_IDS]?.let {
                JsonInstant.decodeFromString<List<Uuid>>(it)
            }?.filter { id ->
                searchServices.any { service -> service.id == id }
            } ?: searchServices.getOrNull(selectedSearchIndex)?.let { listOf(it.id) }.orEmpty()
            Settings(
                enableWebSearch = preferences[ENABLE_WEB_SEARCH] == true,
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                modelGroupSessionDefaults = preferences[MODEL_GROUP_SESSION_DEFAULTS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = preferences[PROVIDERS]?.let { providersJson ->
                    JsonInstant.decodeFromString<List<ProviderSetting>>(providersJson)
                } ?: DEFAULT_PROVIDERS,
                assistants = JsonInstant.decodeFromString<List<Assistant>>(
                    preferences[ASSISTANTS] ?: "[]"
                ).withAmberAgentAssistantBranding(),
                dynamicColor = preferences[DYNAMIC_COLOR] ?: false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                searchServices = searchServices,
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = selectedSearchIndex,
                searchEnabledServiceIds = enabledSearchServiceIds,
                searchBuiltinDuckDuckGoEnabled = preferences[SEARCH_BUILTIN_DUCKDUCKGO_ENABLED] != false,
                searchBuiltinBingEnabled = preferences[SEARCH_BUILTIN_BING_ENABLED] != false,
                searchBuiltinJinaEnabled = preferences[SEARCH_BUILTIN_JINA_ENABLED] != false,
                searchBuiltinWikipediaEnabled = preferences[SEARCH_BUILTIN_WIKIPEDIA_ENABLED] != false,
                searchBuiltinHackerNewsEnabled = preferences[SEARCH_BUILTIN_HACKERNEWS_ENABLED] != false,
                searchGoogleWebViewFallbackEnabled = preferences[SEARCH_GOOGLE_WEBVIEW_FALLBACK_ENABLED] != false,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                modeInjections = preferences[MODE_INJECTIONS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                agentRuntime = preferences[AGENT_RUNTIME]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: AgentRuntimeSetting(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
            )
        }
        .map {
            val providers = it.providers
                .filterNot { provider -> provider.id in REMOVED_DEFAULT_PROVIDER_IDS }
                .map { provider ->
                    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                    if (defaultProvider != null) {
                        provider.copyProvider(
                            builtIn = defaultProvider.builtIn,
                            description = defaultProvider.description,
                            shortDescription = defaultProvider.shortDescription,
                        )
                    } else provider
                }
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            val ttsProviders = it.ttsProviders
                .filterNot { provider -> provider.id in REMOVED_DEFAULT_TTS_PROVIDER_IDS }
                .ifEmpty { DEFAULT_TTS_PROVIDERS }
                .toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants.withAmberAgentAssistantBranding(),
                ttsProviders = ttsProviders,
                selectedTTSProviderId = if (ttsProviders.any { provider -> provider.id == it.selectedTTSProviderId }) {
                    it.selectedTTSProviderId
                } else {
                    DEFAULT_SYSTEM_TTS_ID
                },
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                searchEnabledServiceIds = settings.searchEnabledServiceIds.filter { id ->
                    settings.searchServices.any { service -> service.id == id }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
            )
        }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    suspend fun update(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        settingsFlow.value = settings
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = settings.dynamicColor
            preferences[THEME_ID] = settings.themeId
            preferences[DEVELOPER_MODE] = settings.developerMode
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)

            preferences[ENABLE_WEB_SEARCH] = settings.enableWebSearch
            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            preferences[SELECT_MODEL] = settings.chatModelId.toString()
            preferences[TITLE_MODEL] = settings.titleModelId.toString()
            preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
            preferences[SUGGESTION_MODEL] = settings.suggestionModelId.toString()
            preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
            preferences[TITLE_PROMPT] = settings.titlePrompt
            preferences[TRANSLATION_PROMPT] = settings.translatePrompt
            preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
            preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
            preferences[OCR_MODEL] = settings.ocrModelId.toString()
            preferences[OCR_PROMPT] = settings.ocrPrompt
            preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
            preferences[COMPRESS_PROMPT] = settings.compressPrompt
            preferences[MODEL_GROUP_SESSION_DEFAULTS] = JsonInstant.encodeToString(settings.modelGroupSessionDefaults)

            preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)

            preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
            preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            preferences[SEARCH_SELECTED] = if (settings.searchServices.isEmpty()) {
                0
            } else {
                settings.searchServiceSelected.coerceIn(0, settings.searchServices.lastIndex)
            }
            preferences[SEARCH_ENABLED_SERVICE_IDS] = JsonInstant.encodeToString(
                settings.searchEnabledServiceIds.filter { id ->
                    settings.searchServices.any { service -> service.id == id }
                }
            )
            preferences[SEARCH_BUILTIN_DUCKDUCKGO_ENABLED] = settings.searchBuiltinDuckDuckGoEnabled
            preferences[SEARCH_BUILTIN_BING_ENABLED] = settings.searchBuiltinBingEnabled
            preferences[SEARCH_BUILTIN_JINA_ENABLED] = settings.searchBuiltinJinaEnabled
            preferences[SEARCH_BUILTIN_WIKIPEDIA_ENABLED] = settings.searchBuiltinWikipediaEnabled
            preferences[SEARCH_BUILTIN_HACKERNEWS_ENABLED] = settings.searchBuiltinHackerNewsEnabled
            preferences[SEARCH_GOOGLE_WEBVIEW_FALLBACK_ENABLED] = settings.searchGoogleWebViewFallbackEnabled

            preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
            preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
            preferences[SELECTED_TTS_PROVIDER] = settings.selectedTTSProviderId.toString()
            preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            preferences[AGENT_RUNTIME] = JsonInstant.encodeToString(settings.agentRuntime)
            preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
            preferences[WEB_SERVER_PORT] = settings.webServerPort
            preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
            preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
            preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
            preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
            preferences[LAUNCH_COUNT] = settings.launchCount
            preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
        }
    }

    suspend fun update(fn: (Settings) -> Settings) {
        update(fn(settingsFlow.value))
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        dataStore.edit { preferences ->
            preferences[SELECT_ASSISTANT] = assistantId.toString()
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = false,
    val themeId: String = PresetThemes[0].id,
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val enableWebSearch: Boolean = false,
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid = Uuid.random(),
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val suggestionModelId: Uuid = Uuid.random(),
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val modelGroupSessionDefaults: List<ModelGroupSessionDefault> = emptyList(),
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val searchEnabledServiceIds: List<Uuid> = searchServices.take(1).map { it.id },
    val searchBuiltinDuckDuckGoEnabled: Boolean = true,
    val searchBuiltinBingEnabled: Boolean = true,
    val searchBuiltinJinaEnabled: Boolean = true,
    val searchBuiltinWikipediaEnabled: Boolean = true,
    val searchBuiltinHackerNewsEnabled: Boolean = true,
    val searchGoogleWebViewFallbackEnabled: Boolean = true,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val agentRuntime: AgentRuntimeSetting = AgentRuntimeSetting(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
data class ModelGroupSessionDefault(
    val groupId: String,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val contextMessageSize: Int = 0,
    val maxTokens: Int? = null,
)

data class ResolvedSessionDefaults(
    val reasoningLevel: ReasoningLevel,
    val contextMessageSize: Int,
    val maxTokens: Int?,
)

@Serializable
data class AgentRuntimeSetting(
    val enableCoreMemory: Boolean = true,
    val enableShortTermMemory: Boolean = true,
    val enableLongTermMemory: Boolean = true,
    val enableRecentChatsReference: Boolean = true,
    val enableTimeReminder: Boolean = false,
    val agentSoulMarkdown: String = DEFAULT_AGENT_SOUL_MARKDOWN,
    val operationPreviewMode: AgentOperationPreviewMode = AgentOperationPreviewMode.ALWAYS,
    val generativeUi: GenerativeUiSetting = GenerativeUiSetting(),
    val enableLiveStatusNotification: Boolean = true,
    val hideSensitiveLiveStatus: Boolean = true,
    val liveMode: LiveModeSetting = LiveModeSetting(),
    val maxToolLoopSteps: Int = DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS,
    val autoApproveAllToolCalls: Boolean = false,
    val autoApproveHighRiskToolCalls: Boolean = false,
    val terminalDefaultRuntime: TerminalRuntimeKind = TerminalRuntimeKind.BUILTIN_ALPINE,
    val terminalMaxConcurrentJobs: Int = 1,
    val terminalOutputTailChars: Int = 256 * 1024,
    val terminalInstallTimeoutMs: Long = 15 * 60_000L,
    val feishuOfficeEnhancement: FeishuOfficeEnhancementSetting = FeishuOfficeEnhancementSetting(),
    val contextCompaction: ContextCompactionSetting = ContextCompactionSetting(),
    val memoryRecall: MemoryRecallSetting = MemoryRecallSetting(),
    val memoryWorker: MemoryWorkerSetting = MemoryWorkerSetting(),
    val subAgent: SubAgentRuntimeSetting = SubAgentRuntimeSetting(),
    val modelCouncil: ModelCouncilRuntimeSetting = ModelCouncilRuntimeSetting(),
    val externalFileAccess: ExternalFileAccessSetting = ExternalFileAccessSetting(),
    val harnessDebug: HarnessDebugSetting = HarnessDebugSetting(),
    val speculativeToolExecution: SpeculativeToolExecutionSetting = SpeculativeToolExecutionSetting(),
    val generationRetry: GenerationRetrySetting = GenerationRetrySetting(),
    val keepGenerationAliveInBackground: Boolean = true,
)

@Serializable
data class GenerativeUiSetting(
    val enabled: Boolean = true,
    val allowModelJavaScript: Boolean = false,
    val maxWidgetCodeChars: Int = 12_000,
    val maxWidgetHeightDp: Int = 720,
    val enableActions: Boolean = true,
    val enableStructuredRenderers: Boolean = true,
    val enableInteractiveCharts: Boolean = true,
)

@Serializable
data class HarnessDebugSetting(
    val showPermissionReasons: Boolean = false,
    val showParallelBatches: Boolean = false,
    val showCapabilitySnapshotSummary: Boolean = false,
)

@Serializable
data class SpeculativeToolExecutionSetting(
    val enabled: Boolean = false,
    val maxConcurrentTools: Int = 4,
)

@Serializable
data class ExternalFileAccessSetting(
    val enabled: Boolean = false,
    val roots: List<String> = emptyList(),
)

@Serializable
data class ContextCompactionSetting(
    val enabled: Boolean = true,
    val notifyOnly: Boolean = false,
    val precompactRatio: Float = 0.70f,
    val forceRatio: Float = 0.85f,
    val keepRecentTurns: Int = 8,
    val maxSummaryTokens: Int = 2_000,
)

fun ContextCompactionSetting.toCompactPolicy() = CompactPolicy(
    enabled = enabled,
    notifyOnly = notifyOnly,
    precompactRatio = precompactRatio,
    forceRatio = forceRatio,
    keepRecentTurns = keepRecentTurns,
    maxSummaryTokens = maxSummaryTokens,
)

@Serializable
enum class AgentOperationPreviewMode {
    @SerialName("always")
    ALWAYS,

    @SerialName("auto")
    AUTO,

    @SerialName("hidden")
    HIDDEN,
}

const val MIN_AGENT_TOOL_LOOP_STEPS = 16
const val DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS = 256
const val MAX_AGENT_TOOL_LOOP_STEPS = 512

const val DEFAULT_AGENT_SOUL_MARKDOWN = """
# agents.md

You are AmberAgent, an agent-only Android assistant.

- Work toward the user's goal by planning briefly, using available tools, checking results, and continuing until the task is completed or you need explicit user input.
- Prefer the authorized /workspace for file work. Use terminal, system access, and screen automation tools only when they are necessary and allowed by the current trust policy.
- For long terminal commands, package installation, downloads, or commands with large output, prefer terminal_job_start/read/wait/stop or terminal_install_packages instead of blocking on terminal_execute. If a long job must read or write the user workspace, pass sync_workspace=true or call terminal_workspace_flush after it finishes.
- Treat memory as layered:
  - Core memory: durable behavior rules, identity, and explicit facts the user wants AmberAgent to carry into every conversation.
  - Short-term memory: concise summaries of recent tasks or active projects that help continuity.
  - Long-term memory: stable user preferences, recurring interests, plans, and factual context worth preserving beyond a single day.
- Do not store sensitive personal data unless the user explicitly asks. Merge similar memories instead of creating duplicates.
- If you are unsure which skills are installed or enabled, call skills_list before use_skill.
- If the user asks for iCloud or Obsidian files, call icloud_status first. Use icloud_list/read/search only after the experimental iCloud Drive mount reports read access; use icloud_write only after write access is enabled.
- If the user asks about 小米办公 Pro / 飞书办公 work context, call officepro_status or officepro_dashboard first. Use officepro_daily_radar for today's work radar, officepro_project_briefing for Q 代/MiClaw/Lhasa-style project context, officepro_document_warroom for document review drafts, officepro_open_items_radar / officepro_meeting_closure for follow-up closure, and officepro_project_context/report/list/update for local project knowledge packs. Use officepro_create_task_draft, officepro_create_base_record_draft, and officepro_reply_draft only to produce drafts; never send, comment, create tasks, or write Base records without a separate approval and a real Feishu MCP/Skill write tool. Use officepro_capture_context or officepro_context_digest for lower-level read-first analysis, and officepro_make_report when the user wants a workspace Markdown draft. If Feishu MCP tools are available, call mcp_list(include_tools=true) or tools_list(category="mcp"); use mcp_call_tool for a specific cloud document, calendar, task, meeting, IM, Base, or wiki operation. Only use officepro_open/search after the user approves opening or driving the office app.
- If the user asks to recall, compare, or summarize other sessions, use session_list/session_search first. Read full historical content only with session_read/session_expand after approval or a valid session grant. For many sessions, start multiple history-focused subagents such as session-archivist/topic-miner with separate source_session_ids shards, then synthesize their source-backed summaries.
- If subagent tools are available, use them only when the task is complex, clearly bounded, and benefits from isolated context or parallel viewpoints. Simple linear tasks must stay in the main Agent. Subagent results are evidence for the main Agent, not final truth.
- For webpage tasks:
  - When the user asks to open, browse, view, inspect, or visually verify a webpage, call webview_open early so the live preview shows the page.
  - After webview_open, call webview_wait_for_load or webview_read(wait_timeout_ms=...) before relying on the current page title, readable text, or links.
  - Use search_web or scrape_web when you need search results or deeper text extraction.
  - Do not try to launch Android System WebView as a standalone app.
"""

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateBelowName: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val showUpdates: Boolean = true,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = false,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid): Model? {
    return this.providers.findModelById(uuid)
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getModelGroupSessionDefault(model: Model): ModelGroupSessionDefault? {
    val groupId = ModelRegistry.sessionDefaultGroupForModel(model.modelId)?.id ?: return null
    return modelGroupSessionDefaults.firstOrNull { it.groupId == groupId }
}

fun Settings.resolveSessionDefaults(
    assistant: Assistant,
    model: Model,
): ResolvedSessionDefaults {
    val groupDefault = getModelGroupSessionDefault(model)
    return ResolvedSessionDefaults(
        reasoningLevel = if (assistant.reasoningLevel == ReasoningLevel.AUTO) {
            groupDefault?.reasoningLevel ?: assistant.reasoningLevel
        } else {
            assistant.reasoningLevel
        },
        contextMessageSize = if (assistant.contextMessageSize == 0) {
            groupDefault?.contextMessageSize ?: assistant.contextMessageSize
        } else {
            assistant.contextMessageSize
        },
        maxTokens = assistant.maxTokens ?: groupDefault?.maxTokens,
    )
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

fun Settings.resolveTaskChatModel(modelId: Uuid): Model? {
    return findModelById(modelId) ?: getCurrentChatModel()
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == DEFAULT_ASSISTANT_ID }
        ?: this.assistants.find { it.id == assistantId }
        ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return ttsProviders.find { it.id == selectedTTSProviderId } ?: ttsProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "AmberAgent",
        systemPrompt = """
            You are AmberAgent, an agent-only Android assistant.

            Work toward the user's goal by planning briefly, using available tools, checking results, and continuing until the task is completed or you need explicit user input.
            Prefer the authorized /workspace for file work. Use terminal and screen automation tools only when they are necessary and user-approved.
            If you are unsure which skills are installed or enabled, call skills_list before use_skill.
            If the user asks for iCloud or Obsidian files, call icloud_status first. Use icloud_list/read/search only after the experimental iCloud Drive mount reports read access; use icloud_write only after write access is enabled.
            For webpage tasks, call webview_open early when the user asks to open, browse, view, inspect, or visually verify a page. After webview_open, call webview_wait_for_load or webview_read(wait_timeout_ms=...) before relying on the opened page title, readable text, or links. Use search_web or scrape_web when you need search results or deeper extraction. Do not try to launch Android System WebView as a standalone app.
        """.trimIndent(),
        localTools = listOf(
            LocalToolOption.JavascriptEngine,
            LocalToolOption.TimeInfo,
            LocalToolOption.Clipboard,
            LocalToolOption.Tts,
            LocalToolOption.AskUser,
            LocalToolOption.WorkspaceFiles,
            LocalToolOption.Terminal,
            LocalToolOption.ScreenAutomation,
            LocalToolOption.SystemAccess,
            LocalToolOption.WebView,
            LocalToolOption.ICloudDrive,
        )
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Time: {{cur_datetime}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
)

private fun List<Assistant>.withAmberAgentAssistantBranding(): List<Assistant> = map { assistant ->
    if (assistant.id == DEFAULT_ASSISTANT_ID) {
        assistant.copy(
            name = if (assistant.name in setOf("", "RikkaHub", "Amberagent")) {
                "AmberAgent"
            } else {
                assistant.name
            },
            systemPrompt = assistant.systemPrompt.replace("Amberagent", "AmberAgent"),
            localTools = (assistant.localTools + AMBER_AGENT_REQUIRED_LOCAL_TOOLS).distinct(),
            enabledSkills = assistant.enabledSkills + AMBER_AGENT_REQUIRED_SKILLS,
        )
    } else {
        assistant
    }
}

private val AMBER_AGENT_REQUIRED_LOCAL_TOOLS = listOf(
    LocalToolOption.JavascriptEngine,
    LocalToolOption.TimeInfo,
    LocalToolOption.Clipboard,
    LocalToolOption.Tts,
    LocalToolOption.AskUser,
    LocalToolOption.WorkspaceFiles,
    LocalToolOption.Terminal,
    LocalToolOption.ScreenAutomation,
    LocalToolOption.SystemAccess,
    LocalToolOption.WebView,
    LocalToolOption.ICloudDrive,
)

private val AMBER_AGENT_REQUIRED_SKILLS = setOf("skill-creator")

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val REMOVED_DEFAULT_TTS_PROVIDER_IDS = setOf(
    Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"), // AiHubMix TTS
)
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)
