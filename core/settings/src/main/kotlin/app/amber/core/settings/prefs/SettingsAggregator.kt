package app.amber.core.settings.prefs

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.core.infra.AppScope
import app.amber.core.settings.DEFAULT_ASSISTANTS
import app.amber.core.settings.DEFAULT_ASSISTANTS_IDS
import app.amber.core.settings.DEFAULT_PROVIDERS
import app.amber.core.settings.DEFAULT_SYSTEM_TTS_ID
import app.amber.core.settings.DEFAULT_TTS_PROVIDERS
import app.amber.core.settings.GeminiProviderIdRef
import app.amber.core.settings.OpenAIProviderIdRef
import app.amber.core.settings.REMOVED_DEFAULT_PROVIDER_IDS
import app.amber.core.settings.REMOVED_DEFAULT_TTS_PROVIDER_IDS
import app.amber.core.settings.SeedGeminiImageModel
import app.amber.core.settings.SeedGeminiImageModelId
import app.amber.core.settings.SeedOpenAIImageModel
import app.amber.core.settings.SeedOpenAIImageModelId
import app.amber.core.settings.SeedRoutingQuickMessages
import app.amber.core.settings.Settings
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.withAmberAgentAssistantBranding
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.toMutableStateFlow
import kotlin.uuid.Uuid

private const val TAG = "SettingsAggregator"

/**
 * M1.1.8a — SettingsAggregator combines the 7 domain Prefs (UI/Search/Agent/
 * Provider/Chat/Extension/Assistant) into a single [Settings] flow with the
 * same cleanup semantics as the pre-M1.1.8e SettingsStore.settingsFlow.
 *
 * NOT YET WIRED to any caller. Phase 1 plan kept the old SettingsStore as the (now deleted in M1.1.8e)
 * canonical Settings source until M1.1.8c-d migrate callers one batch at a
 * time. Any divergence between this and the pre-M1.1.8e SettingsStore is a bug we want to
 * surface BEFORE caller migration begins.
 */
class SettingsAggregator(
    private val dataStore: DataStore<Preferences>,
    private val uiPrefs: UIPrefs,
    private val searchPrefs: SearchPrefs,
    private val agentPrefs: AgentPrefs,
    private val providerPrefs: ProviderPrefs,
    private val chatPrefs: ChatPrefs,
    private val extensionPrefs: ExtensionPrefs,
    private val assistantPrefs: AssistantPrefs,
    scope: AppScope,
) {

    private val _settingsFlow: MutableStateFlow<Settings> = combine(
        uiPrefs.rawFlow,
        searchPrefs.rawFlow,
        agentPrefs.rawFlow,
        providerPrefs.rawFlow,
        chatPrefs.rawFlow,
        extensionPrefs.rawFlow,
        assistantPrefs.rawFlow,
    ) { arr: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        composeRawSettings(
            ui = arr[0] as UIPrefsData,
            search = arr[1] as SearchPrefsData,
            agent = arr[2] as AgentPrefsData,
            provider = arr[3] as ProviderPrefsData,
            chat = arr[4] as ChatPrefsData,
            ext = arr[5] as ExtensionPrefsData,
            assistant = arr[6] as AssistantPrefsData,
        )
    }
        .map { applyBackfillAndSeed(it) }
        .map { applyCrossDomainConsistency(it) }
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    val settingsFlow: StateFlow<Settings> get() = _settingsFlow

    /**
     * Atomic write — single [dataStore.edit] block writing all 55 keys.
     * Mirrors the pre-M1.1.8e SettingsStore.update line 485-557 byte-for-byte so character
     * test can prove behavioural equivalence.
     */
    suspend fun update(settings: Settings) {
        if (settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        // [M1.1.8a W1] Mirror the pre-M1.1.8e SettingsStore.update line 484 — push value into
        // flow eagerly so caller reading settingsFlow.value immediately after
        // update() sees the new value (matches existing god-class semantics
        // before the dataStore.edit reader cycle completes).
        _settingsFlow.value = settings
        dataStore.edit { p ->
            p[PreferencesKeys.DYNAMIC_COLOR] = settings.dynamicColor
            p[PreferencesKeys.THEME_ID] = settings.themeId
            p[PreferencesKeys.DEVELOPER_MODE] = settings.developerMode
            p[PreferencesKeys.DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)

            p[PreferencesKeys.ENABLE_WEB_SEARCH] = settings.enableWebSearch
            p[PreferencesKeys.FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            p[PreferencesKeys.SELECT_MODEL] = settings.chatModelId.toString()
            p[PreferencesKeys.TITLE_MODEL] = settings.titleModelId.toString()
            p[PreferencesKeys.SUGGESTION_MODEL] = settings.suggestionModelId.toString()
            p[PreferencesKeys.IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
            p[PreferencesKeys.TITLE_PROMPT] = settings.titlePrompt
            p[PreferencesKeys.SUGGESTION_PROMPT] = settings.suggestionPrompt
            p[PreferencesKeys.OCR_MODEL] = settings.ocrModelId.toString()
            p[PreferencesKeys.OCR_PROMPT] = settings.ocrPrompt
            p[PreferencesKeys.COMPRESS_MODEL] = settings.compressModelId.toString()
            p[PreferencesKeys.COMPRESS_PROMPT] = settings.compressPrompt
            p[PreferencesKeys.MODEL_GROUP_SESSION_DEFAULTS] =
                JsonInstant.encodeToString(settings.modelGroupSessionDefaults)

            p[PreferencesKeys.PROVIDERS] = JsonInstant.encodeToString(settings.providers)

            p[PreferencesKeys.ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
            p[PreferencesKeys.SELECT_ASSISTANT] = settings.assistantId.toString()
            p[PreferencesKeys.ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

            p[PreferencesKeys.SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            p[PreferencesKeys.SEARCH_COMMON] =
                JsonInstant.encodeToString(settings.searchCommonOptions)
            p[PreferencesKeys.SEARCH_SELECTED] = if (settings.searchServices.isEmpty()) {
                0
            } else {
                settings.searchServiceSelected.coerceIn(0, settings.searchServices.lastIndex)
            }
            p[PreferencesKeys.SEARCH_ENABLED_SERVICE_IDS] = JsonInstant.encodeToString(
                settings.searchEnabledServiceIds.filter { id ->
                    settings.searchServices.any { service -> service.id == id }
                }
            )
            p[PreferencesKeys.SEARCH_BUILTIN_DUCKDUCKGO_ENABLED] =
                settings.searchBuiltinDuckDuckGoEnabled
            p[PreferencesKeys.SEARCH_BUILTIN_BING_ENABLED] = settings.searchBuiltinBingEnabled
            p[PreferencesKeys.SEARCH_BUILTIN_JINA_ENABLED] = settings.searchBuiltinJinaEnabled
            p[PreferencesKeys.SEARCH_BUILTIN_WIKIPEDIA_ENABLED] =
                settings.searchBuiltinWikipediaEnabled
            p[PreferencesKeys.SEARCH_BUILTIN_HACKERNEWS_ENABLED] =
                settings.searchBuiltinHackerNewsEnabled
            p[PreferencesKeys.SEARCH_GOOGLE_WEBVIEW_FALLBACK_ENABLED] =
                settings.searchGoogleWebViewFallbackEnabled

            p[PreferencesKeys.MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
            p[PreferencesKeys.WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
            p[PreferencesKeys.S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
            p[PreferencesKeys.TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
            p[PreferencesKeys.SELECTED_TTS_PROVIDER] = settings.selectedTTSProviderId.toString()
            p[PreferencesKeys.MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
            p[PreferencesKeys.LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            p[PreferencesKeys.QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            p[PreferencesKeys.AGENT_RUNTIME] = JsonInstant.encodeToString(settings.agentRuntime)
            p[PreferencesKeys.BACKUP_REMINDER_CONFIG] =
                JsonInstant.encodeToString(settings.backupReminderConfig)
            p[PreferencesKeys.SYNC_SETTINGS] = JsonInstant.encodeToString(settings.syncSettings)
            p[PreferencesKeys.LAUNCH_COUNT] = settings.launchCount
            p[PreferencesKeys.SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
            if (settings.imageModelsSeededVersion > 0) {
                p[PreferencesKeys.SEEDED_IMAGE_MODELS_V1] = true
            }
            if (settings.routingQuickMessagesSeededVersion > 0) {
                p[PreferencesKeys.SEEDED_ROUTING_QUICK_MESSAGES_V1] = true
            }
        }
    }

    suspend fun update(fn: (Settings) -> Settings) {
        update(fn(settingsFlow.value))
    }

    suspend fun updateLaunchCount(launchCount: Int) {
        dataStore.edit { p ->
            p[PreferencesKeys.LAUNCH_COUNT] = launchCount
        }
        val current = _settingsFlow.value
        if (!current.init) {
            _settingsFlow.value = current.copy(launchCount = launchCount)
        }
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        dataStore.edit { p ->
            p[PreferencesKeys.SELECT_ASSISTANT] = assistantId.toString()
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

/**
 * Phase 1 — raw assembly of [Settings] from the 7 PrefsData snapshots.
 *
 * This mirrors the pre-M1.1.8e SettingsStore.settingsFlowRaw line 205-300 except for the 3
 * cross-field search cleanups (search selected coerceIn / searchEnabledIds
 * filter / searchEnabledIds derived default). Those cleanups now happen at
 * the writer (the pre-M1.1.8e SettingsStore.update line 516-525 already enforces them) so
 * the value lives back through the next read. We re-apply them as part of
 * [applyCrossDomainConsistency] below.
 */
internal fun composeRawSettings(
    ui: UIPrefsData,
    search: SearchPrefsData,
    agent: AgentPrefsData,
    provider: ProviderPrefsData,
    chat: ChatPrefsData,
    ext: ExtensionPrefsData,
    assistant: AssistantPrefsData,
): Settings = Settings(
    init = false,
    dynamicColor = ui.dynamicColor,
    themeId = ui.themeId,
    developerMode = ui.developerMode,
    displaySetting = ui.displaySetting,
    launchCount = ui.launchCount,
    sponsorAlertDismissedAt = ui.sponsorAlertDismissedAt,

    enableWebSearch = chat.enableWebSearch,
    favoriteModels = chat.favoriteModels,
    chatModelId = chat.chatModelId,
    titleModelId = chat.titleModelId,
    suggestionModelId = chat.suggestionModelId,
    imageGenerationModelId = chat.imageGenerationModelId,
    titlePrompt = chat.titlePrompt,
    suggestionPrompt = chat.suggestionPrompt,
    ocrModelId = chat.ocrModelId,
    ocrPrompt = chat.ocrPrompt,
    compressModelId = chat.compressModelId,
    compressPrompt = chat.compressPrompt,
    modelGroupSessionDefaults = chat.modelGroupSessionDefaults,

    providers = provider.providers,
    imageModelsSeededVersion = provider.imageModelsSeededVersion,

    assistantId = assistant.assistantId,
    assistants = assistant.assistants,
    assistantTags = assistant.assistantTags,

    searchServices = search.searchServices,
    searchCommonOptions = search.searchCommonOptions,
    searchServiceSelected = search.searchServiceSelected,
    searchEnabledServiceIds = search.searchEnabledServiceIds,
    searchBuiltinDuckDuckGoEnabled = search.searchBuiltinDuckDuckGoEnabled,
    searchBuiltinBingEnabled = search.searchBuiltinBingEnabled,
    searchBuiltinJinaEnabled = search.searchBuiltinJinaEnabled,
    searchBuiltinWikipediaEnabled = search.searchBuiltinWikipediaEnabled,
    searchBuiltinHackerNewsEnabled = search.searchBuiltinHackerNewsEnabled,
    searchGoogleWebViewFallbackEnabled = search.searchGoogleWebViewFallbackEnabled,

    agentRuntime = agent.agentRuntime,

    mcpServers = ext.mcpServers,
    webDavConfig = ext.webDavConfig,
    s3Config = ext.s3Config,
    ttsProviders = ext.ttsProviders,
    selectedTTSProviderId = ext.selectedTTSProviderId,
    modeInjections = ext.modeInjections,
    lorebooks = ext.lorebooks,
    quickMessages = ext.quickMessages,
    backupReminderConfig = ext.backupReminderConfig,
    syncSettings = ext.syncSettings,
    routingQuickMessagesSeededVersion = ext.routingQuickMessagesSeededVersion,
)

/**
 * Phase 2 — per-load backfill / seed / branding.
 *
 * Byte-equivalent to the pre-M1.1.8e SettingsStore.settingsFlowRaw line 301-419:
 * - Remove deprecated providers (REMOVED_DEFAULT_PROVIDER_IDS)
 * - Sync built-in provider metadata (description / shortDescription / brand)
 * - Seed gpt-image-2 / nano-banana-2 (gated by imageModelsSeededVersion < 1)
 * - Inject DEFAULT_ASSISTANTS that the user has not yet got
 * - Seed routing quick messages (/draw /diagram /slide) + subscribe default
 *   assistants to them (gated by routingQuickMessagesSeededVersion < 1)
 * - Backfill DEFAULT_TTS_PROVIDERS + clamp selectedTTSProviderId
 * - Apply AmberAgent assistant branding
 * - Flip both seed version flags to 1 once seeding done
 */
internal fun applyBackfillAndSeed(it: Settings): Settings {
    val shouldSeedImageModels = it.imageModelsSeededVersion < 1
    val providers = it.providers
        .filterNot { provider -> provider.id in REMOVED_DEFAULT_PROVIDER_IDS }
        .map { provider ->
            val defaultProvider = DEFAULT_PROVIDERS.find { dp -> dp.id == provider.id }
            if (defaultProvider != null) {
                val withMeta = provider.copyProvider(
                    builtIn = defaultProvider.builtIn,
                    description = defaultProvider.description,
                    shortDescription = defaultProvider.shortDescription,
                )
                val withBrand = if (
                    withMeta is ProviderSetting.OpenAI &&
                    defaultProvider is ProviderSetting.OpenAI &&
                    withMeta.brand == OpenAIBrand.GENERIC &&
                    defaultProvider.brand != OpenAIBrand.GENERIC
                ) {
                    withMeta.copy(brand = defaultProvider.brand)
                } else {
                    withMeta
                }
                if (!shouldSeedImageModels) withBrand
                else when {
                    withBrand is ProviderSetting.OpenAI &&
                        withBrand.id == OpenAIProviderIdRef &&
                        withBrand.models.none { m ->
                            m.id == SeedOpenAIImageModelId || m.modelId == SeedOpenAIImageModel.modelId
                        } -> {
                        withBrand.copy(models = withBrand.models + SeedOpenAIImageModel)
                    }
                    withBrand is ProviderSetting.Google &&
                        withBrand.id == GeminiProviderIdRef &&
                        withBrand.models.none { m ->
                            m.id == SeedGeminiImageModelId || m.modelId == SeedGeminiImageModel.modelId
                        } -> {
                        withBrand.copy(models = withBrand.models + SeedGeminiImageModel)
                    }
                    else -> withBrand
                }
            } else provider
        }
    val assistantsRaw = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
    DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
        if (assistantsRaw.none { a -> a.id == defaultAssistant.id }) {
            assistantsRaw.add(defaultAssistant.copy())
        }
    }

    val shouldSeedRoutingQuickMessages = it.routingQuickMessagesSeededVersion < 1
    val routingSeedIds = SeedRoutingQuickMessages.map { qm -> qm.id }.toSet()
    val nextQuickMessages = if (shouldSeedRoutingQuickMessages) {
        val existingIds = it.quickMessages.map { qm -> qm.id }.toSet()
        it.quickMessages + SeedRoutingQuickMessages.filter { qm -> qm.id !in existingIds }
    } else it.quickMessages
    val assistants = if (shouldSeedRoutingQuickMessages) {
        assistantsRaw.map { assistant ->
            if (assistant.id !in DEFAULT_ASSISTANTS_IDS) return@map assistant
            val missing = routingSeedIds - assistant.quickMessageIds
            if (missing.isEmpty()) assistant
            else assistant.copy(quickMessageIds = assistant.quickMessageIds + missing)
        }.toMutableList()
    } else assistantsRaw
    val ttsProviders = it.ttsProviders
        .filterNot { provider -> provider.id in REMOVED_DEFAULT_TTS_PROVIDER_IDS }
        .ifEmpty { DEFAULT_TTS_PROVIDERS }
        .toMutableList()
    DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
        if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
            ttsProviders.add(defaultTTSProvider.copyProvider())
        }
    }
    return it.copy(
        providers = providers,
        assistants = assistants.withAmberAgentAssistantBranding(),
        quickMessages = nextQuickMessages,
        ttsProviders = ttsProviders,
        selectedTTSProviderId = if (ttsProviders.any { provider -> provider.id == it.selectedTTSProviderId }) {
            it.selectedTTSProviderId
        } else {
            DEFAULT_SYSTEM_TTS_ID
        },
        imageModelsSeededVersion = if (shouldSeedImageModels) 1 else it.imageModelsSeededVersion,
        routingQuickMessagesSeededVersion =
            if (shouldSeedRoutingQuickMessages) 1 else it.routingQuickMessagesSeededVersion,
    )
}

/**
 * Phase 3 — cross-domain consistency.
 *
 * Byte-equivalent to the pre-M1.1.8e SettingsStore.settingsFlowRaw line 420-473:
 * - Dedup providers (by id) and dedup their models (by id)
 * - Dedup assistants (by id), filter stale mcpServers / modeInjectionIds /
 *   lorebookIds / quickMessageIds references on each assistant
 * - Dedup ttsProviders (by id)
 * - Filter favoriteModels — only models that still exist in providers survive
 * - Filter searchEnabledServiceIds — only services that still exist survive
 * - Dedup modeInjections / lorebooks / quickMessages (by id)
 */
internal fun applyCrossDomainConsistency(settings: Settings): Settings {
    val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
    val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
    val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
    val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
    // [M1.1.8a B1] Reader-path search cleanup. the pre-M1.1.8e SettingsStore reader applied
    // these inline in the raw decode (PreferencesStore.kt:197-204). The 7
    // domain Prefs in M1.1.1-7 deliberately skipped them (raw mirror only),
    // and the pre-M1.1.8e SettingsStore.update writer also enforced them (line 516-525) — but
    // a user who never wrote settings (fresh install / migration gap) would
    // see stale values on read. Apply here so aggregator.settingsFlow is
    // byte-equivalent to settingsStore.settingsFlow on every read path.
    val cleanedSearchSelected = if (settings.searchServices.isEmpty()) {
        0
    } else {
        settings.searchServiceSelected.coerceIn(0, settings.searchServices.lastIndex)
    }
    val cleanedSearchEnabledIds = settings.searchEnabledServiceIds
        .filter { id -> settings.searchServices.any { service -> service.id == id } }
        .ifEmpty {
            settings.searchServices.getOrNull(cleanedSearchSelected)
                ?.let { listOf(it.id) }
                .orEmpty()
        }
    return settings.copy(
        searchServiceSelected = cleanedSearchSelected,
        searchEnabledServiceIds = cleanedSearchEnabledIds,
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
                mcpServers = assistant.mcpServers.filter { serverId ->
                    serverId in validMcpServerIds
                }.toSet(),
                modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                    id in validModeInjectionIds
                }.toSet(),
                lorebookIds = assistant.lorebookIds.filter { id ->
                    id in validLorebookIds
                }.toSet(),
                quickMessageIds = assistant.quickMessageIds.filter { id ->
                    id in validQuickMessageIds
                }.toSet()
            )
        },
        ttsProviders = settings.ttsProviders.distinctBy { it.id },
        favoriteModels = settings.favoriteModels.filter { uuid ->
            settings.providers.flatMap { it.models }.any { m -> m.id == uuid }
        },
        modeInjections = settings.modeInjections.distinctBy { it.id },
        lorebooks = settings.lorebooks.distinctBy { it.id },
        quickMessages = settings.quickMessages.distinctBy { it.id },
    )
}
