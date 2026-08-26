package app.amber.core.settings.prefs

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.core.infra.AppScope
import app.amber.core.settings.DEFAULT_PROVIDERS
import app.amber.core.settings.GeminiProviderIdRef
import app.amber.core.settings.OpenAIProviderIdRef
import app.amber.core.settings.REMOVED_DEFAULT_PROVIDER_IDS
import app.amber.core.settings.SeedGeminiImageModel
import app.amber.core.settings.SeedGeminiImageModelId
import app.amber.core.settings.SeedOpenAIImageModel
import app.amber.core.settings.SeedOpenAIImageModelId
import app.amber.core.settings.SeedRoutingQuickMessages
import app.amber.core.settings.SeedSvgQuickMessageId
import app.amber.core.settings.AMBER_AGENT_REQUIRED_SKILLS
import app.amber.core.settings.Settings
import app.amber.core.settings.PreferencesKeys
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretReference
import app.amber.core.agent.utils.JsonInstant
import app.amber.core.settings.toMutableStateFlow

private const val TAG = "SettingsAggregator"

/**
 * Combines the six domain preference stores into the canonical [Settings]
 * flow and owns cross-domain consistency and atomic writes.
 */
class SettingsAggregator(
    private val dataStore: DataStore<Preferences>,
    private val uiPrefs: UIPrefs,
    private val searchPrefs: SearchPrefs,
    private val agentPrefs: AgentPrefs,
    private val providerPrefs: ProviderPrefs,
    private val chatPrefs: ChatPrefs,
    private val extensionPrefs: ExtensionPrefs,
    scope: AppScope,
    private val secretRedactor: SecretRedactor,
) {

    private val _settingsFlow: MutableStateFlow<Settings> = combine(
        uiPrefs.rawFlow,
        searchPrefs.rawFlow,
        agentPrefs.rawFlow,
        providerPrefs.rawFlow,
        chatPrefs.rawFlow,
        extensionPrefs.rawFlow,
    ) { arr: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        composeRawSettings(
            ui = arr[0] as UIPrefsData,
            search = arr[1] as SearchPrefsData,
            agent = arr[2] as AgentPrefsData,
            provider = arr[3] as ProviderPrefsData,
            chat = arr[4] as ChatPrefsData,
            ext = arr[5] as ExtensionPrefsData,
        )
    }
        .map { applyBackfillAndSeed(it) }
        .map { applyCrossDomainConsistency(it) }
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    val settingsFlow: StateFlow<Settings> get() = _settingsFlow

    private val writeMutex = Mutex()

    /** Atomic write: all settings keys are updated in one [dataStore.edit] block. */
    suspend fun update(settings: Settings) = writeMutex.withLock {
        writeSettings(settings)
    }

    private suspend fun writeSettings(settings: Settings) {
        if (settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        val settingsForWrite = settings.withMigratedMemoryDreamLegacy()
        var legacyMigrationPending = false
        dataStore.edit { p ->
            legacyMigrationPending = hasPendingLegacyAssistantSettings(p)
            // 保存边界 redaction：明文进 SecretStore，DataStore 只留掩码 + reference。
            // Legacy secret fields retain plaintext on write failure for compatibility;
            // MCP OAuth and credential-bearing URLs fail closed in SecretRedactor.
            val existingRefs = secretRedactor.readRefsStrict(p)
            val redacted = secretRedactor.redactSettings(
                providers = settingsForWrite.providers,
                customHeaders = settingsForWrite.customHeaders,
                searchServices = settingsForWrite.searchServices,
                mcpServers = settingsForWrite.mcpServers,
                webDavConfig = settingsForWrite.webDavConfig,
                s3Config = settingsForWrite.s3Config,
                existingRefs = existingRefs,
            )
            p[PreferencesKeys.DYNAMIC_COLOR] = settings.dynamicColor
            p[PreferencesKeys.THEME_ID] = settings.themeId
            p[PreferencesKeys.DEVELOPER_MODE] = settings.developerMode
            p[PreferencesKeys.DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)

            p[PreferencesKeys.ENABLE_WEB_SEARCH] = settings.enableWebSearch
            p[PreferencesKeys.FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            p[PreferencesKeys.SELECT_MODEL] = settings.chatModelId.toString()
            p[PreferencesKeys.TITLE_MODEL] = settings.titleModelId.toString()
            p[PreferencesKeys.SUGGESTION_MODEL] = settings.suggestionModelId.toString()
            p[PreferencesKeys.IMAGE_GENERATION_MODEL] = settingsForWrite.imageGenerationModelId.toString()
            p[PreferencesKeys.TITLE_PROMPT] = settings.titlePrompt
            p[PreferencesKeys.SUGGESTION_PROMPT] = settings.suggestionPrompt
            p[PreferencesKeys.OCR_MODEL] = settings.ocrModelId.toString()
            p[PreferencesKeys.OCR_PROMPT] = settings.ocrPrompt
            p[PreferencesKeys.COMPRESS_MODEL] = settings.compressModelId.toString()
            p[PreferencesKeys.COMPRESS_PROMPT] = settings.compressPrompt
            p[PreferencesKeys.MODEL_GROUP_SESSION_DEFAULTS] =
                JsonInstant.encodeToString(settings.modelGroupSessionDefaults)

            p[PreferencesKeys.PROVIDERS] = JsonInstant.encodeToString(redacted.providers)

            p[PreferencesKeys.AMBER_SYSTEM_PROMPT] = settingsForWrite.systemPrompt
            settingsForWrite.temperature?.let { p[PreferencesKeys.AMBER_TEMPERATURE] = it.toString() }
                ?: p.remove(PreferencesKeys.AMBER_TEMPERATURE)
            settingsForWrite.topP?.let { p[PreferencesKeys.AMBER_TOP_P] = it.toString() }
                ?: p.remove(PreferencesKeys.AMBER_TOP_P)
            p[PreferencesKeys.AMBER_CONTEXT_MESSAGE_SIZE] = settingsForWrite.contextMessageSize
            p[PreferencesKeys.AMBER_STREAM_OUTPUT] = settingsForWrite.streamOutput
            p[PreferencesKeys.AMBER_MESSAGE_TEMPLATE] = settingsForWrite.messageTemplate
            p[PreferencesKeys.AMBER_PRESET_MESSAGES] = JsonInstant.encodeToString(settingsForWrite.presetMessages)
            p[PreferencesKeys.AMBER_REGEXES] = JsonInstant.encodeToString(settingsForWrite.regexes)
            p[PreferencesKeys.AMBER_REASONING_LEVEL] = JsonInstant.encodeToString(settingsForWrite.reasoningLevel)
            settingsForWrite.maxTokens?.let { p[PreferencesKeys.AMBER_MAX_TOKENS] = it }
                ?: p.remove(PreferencesKeys.AMBER_MAX_TOKENS)
            p[PreferencesKeys.AMBER_CUSTOM_HEADERS] = JsonInstant.encodeToString(redacted.customHeaders)
            p[PreferencesKeys.AMBER_CUSTOM_BODIES] = JsonInstant.encodeToString(settingsForWrite.customBodies)
            p[PreferencesKeys.AMBER_REMEMBERED_REASONING_LEVELS] =
                JsonInstant.encodeToString(settingsForWrite.rememberedReasoningLevelsByModelId)

            p[PreferencesKeys.SEARCH_SERVICES] = JsonInstant.encodeToString(redacted.searchServices)
            p[PreferencesKeys.SEARCH_COMMON] =
                JsonInstant.encodeToString(
                    settings.searchCommonOptions.copy(
                        resultSize = settings.searchCommonOptions.resultSize.coerceIn(1, 30)
                    )
                )
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

            p[PreferencesKeys.MCP_SERVERS] = JsonInstant.encodeToString(redacted.mcpServers)
            p[PreferencesKeys.WEBDAV_CONFIG] = JsonInstant.encodeToString(redacted.webDavConfig)
            p[PreferencesKeys.S3_CONFIG] = JsonInstant.encodeToString(redacted.s3Config)
            p[PreferencesKeys.MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
            p[PreferencesKeys.LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            p[PreferencesKeys.QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            p[PreferencesKeys.AMBER_ENABLED_SKILLS] = JsonInstant.encodeToString(settingsForWrite.enabledSkills)
            p[PreferencesKeys.AMBER_ENABLED_MCP_SERVER_IDS] =
                JsonInstant.encodeToString(settingsForWrite.enabledMcpServerIds)
            p[PreferencesKeys.AMBER_ENABLED_MODE_INJECTION_IDS] =
                JsonInstant.encodeToString(settingsForWrite.enabledModeInjectionIds)
            p[PreferencesKeys.AMBER_ENABLED_LOREBOOK_IDS] =
                JsonInstant.encodeToString(settingsForWrite.enabledLorebookIds)
            p[PreferencesKeys.AGENT_RUNTIME] = JsonInstant.encodeToString(settingsForWrite.agentRuntime)
            p[PreferencesKeys.BACKUP_REMINDER_CONFIG] =
                JsonInstant.encodeToString(settings.backupReminderConfig)
            p[PreferencesKeys.SYNC_SETTINGS] = JsonInstant.encodeToString(settings.syncSettings)
            p[PreferencesKeys.LAUNCH_COUNT] = settings.launchCount
            p[PreferencesKeys.SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
            // A failed legacy migration leaves these keys as the retry marker. Keep their
            // refs and payload intact while allowing ordinary direct settings to update.
            if (legacyMigrationPending) {
                secretRedactor.writeRefs(p, existingRefs + redacted.refs)
            } else {
                secretRedactor.writeRefs(p, redacted.refs)
                p.remove(PreferencesKeys.AMBER_PROFILE)
                p.remove(PreferencesKeys.LEGACY_SELECTED_ASSISTANT)
                p.remove(PreferencesKeys.LEGACY_ASSISTANTS)
                p.remove(PreferencesKeys.LEGACY_ASSISTANT_TAGS)
            }
            if (settings.imageModelsSeededVersion > 0) {
                p[PreferencesKeys.SEEDED_IMAGE_MODELS_V1] = true
            }
            if (settings.routingQuickMessagesSeededVersion > 0) {
                p[PreferencesKeys.SEEDED_ROUTING_QUICK_MESSAGES_V1] = true
            }
        }
        // Publish only after the redacted DataStore edit succeeds. Security-sensitive MCP
        // URL/OAuth redaction fails closed and must not expose a rejected plaintext value
        // through the in-memory settings flow.
        _settingsFlow.value = settingsForWrite
        // Do not sweep while the legacy profile/list still signals a migration retry.
        if (!legacyMigrationPending) {
            val activeRefs = dataStore.data.first().let { secretRedactor.readRefsStrict(it) }
            secretRedactor.deleteOrphans(activeRefs.values.map { it.descriptor() }.toSet())
        }
    }

    /**
     * 恢复路径专用：把备份携带的 reference 写回 DataStore，
     * 使恢复出的掩码值能通过 redact keep 规则找回本机 secret（备份不含明文）。
     */
    suspend fun restoreSecretRefs(refs: List<SecretReference>) = writeMutex.withLock {
        if (refs.isEmpty()) return@withLock
        dataStore.edit { p ->
            val merged = secretRedactor.readRefsStrict(p) + refs.associateBy { it.descriptor().key }
            secretRedactor.writeRefs(p, merged)
        }
    }

    suspend fun update(fn: (Settings) -> Settings) {
        writeMutex.withLock {
            writeSettings(fn(settingsFlow.value))
        }
    }

    suspend fun updateLaunchCount(launchCount: Int) = writeMutex.withLock {
        dataStore.edit { p ->
            p[PreferencesKeys.LAUNCH_COUNT] = launchCount
        }
        val current = _settingsFlow.value
        if (!current.init) {
            _settingsFlow.value = current.copy(launchCount = launchCount)
        }
    }

}

private fun hasPendingLegacyAssistantSettings(p: Preferences): Boolean =
    p[PreferencesKeys.AMBER_PROFILE] != null ||
        p[PreferencesKeys.LEGACY_SELECTED_ASSISTANT] != null ||
        p[PreferencesKeys.LEGACY_ASSISTANTS] != null ||
        p[PreferencesKeys.LEGACY_ASSISTANT_TAGS] != null

/**
 * Assembles [Settings] from the six domain snapshots. Cross-domain cleanup
 * is applied by [applyCrossDomainConsistency] after assembly.
 */
internal fun composeRawSettings(
    ui: UIPrefsData,
    search: SearchPrefsData,
    agent: AgentPrefsData,
    provider: ProviderPrefsData,
    chat: ChatPrefsData,
    ext: ExtensionPrefsData,
): Settings = Settings(
    init = false,
    dynamicColor = ui.dynamicColor,
    themeId = ui.themeId,
    developerMode = ui.developerMode,
    displaySetting = ui.displaySetting,
    launchCount = ui.launchCount,
    sponsorAlertDismissedAt = ui.sponsorAlertDismissedAt,

    enableWebSearch = chat.enableWebSearch && !search.retiredServiceRequiresReconfiguration,
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
    systemPrompt = chat.systemPrompt,
    temperature = chat.temperature,
    topP = chat.topP,
    contextMessageSize = chat.contextMessageSize,
    streamOutput = chat.streamOutput,
    messageTemplate = chat.messageTemplate,
    presetMessages = chat.presetMessages,
    regexes = chat.regexes,
    reasoningLevel = chat.reasoningLevel,
    maxTokens = chat.maxTokens,
    customHeaders = chat.customHeaders,
    customBodies = chat.customBodies,
    rememberedReasoningLevelsByModelId = chat.rememberedReasoningLevelsByModelId,

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
    modeInjections = ext.modeInjections,
    lorebooks = ext.lorebooks,
    quickMessages = ext.quickMessages,
    enabledSkills = ext.enabledSkills,
    enabledMcpServerIds = ext.enabledMcpServerIds,
    enabledModeInjectionIds = ext.enabledModeInjectionIds,
    enabledLorebookIds = ext.enabledLorebookIds,
    backupReminderConfig = ext.backupReminderConfig,
    syncSettings = ext.syncSettings,
    routingQuickMessagesSeededVersion = ext.routingQuickMessagesSeededVersion,
)

/**
 * Applies per-load backfill, seeding and branding:
 * - Remove deprecated providers (REMOVED_DEFAULT_PROVIDER_IDS)
 * - Sync built-in provider metadata (description / shortDescription / brand)
 * - Seed gpt-image-2 / nano-banana-2 (gated by imageModelsSeededVersion < 1)
 * - Seed routing quick messages (/draw /svg /diagram /slide) into the global pool
 * - Apply the fixed Amber required skills
 * - Flip both seed version flags to 1 once seeding done
 */
internal fun applyBackfillAndSeed(it: Settings): Settings {
    val normalized = it.copy(enabledSkills = it.enabledSkills + AMBER_AGENT_REQUIRED_SKILLS)
    val shouldSeedImageModels = normalized.imageModelsSeededVersion < 1
    val providers = normalized.providers
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
    val routingQuickMessagesToSeed = SeedRoutingQuickMessages.filter { qm ->
        normalized.routingQuickMessagesSeededVersion < if (qm.id == SeedSvgQuickMessageId) 2 else 1
    }
    val shouldSeedRoutingQuickMessages = routingQuickMessagesToSeed.isNotEmpty()
    val nextQuickMessages = if (shouldSeedRoutingQuickMessages) {
        val existingIds = normalized.quickMessages.map { qm -> qm.id }.toSet()
        normalized.quickMessages + routingQuickMessagesToSeed.filter { qm -> qm.id !in existingIds }
    } else normalized.quickMessages
    return normalized.copy(
        providers = providers,
        quickMessages = nextQuickMessages,
        imageModelsSeededVersion = if (shouldSeedImageModels) 1 else normalized.imageModelsSeededVersion,
        routingQuickMessagesSeededVersion =
            if (shouldSeedRoutingQuickMessages) 2 else normalized.routingQuickMessagesSeededVersion,
    )
}

/**
 * Applies cross-domain consistency:
 * - Dedup providers (by id) and dedup their models (by id)
 * - Filter stale enabled MCP/mode-injection/lorebook references
 * - Filter favoriteModels — only models that still exist in providers survive
 * - Filter searchEnabledServiceIds — only services that still exist survive
 * - Dedup modeInjections / lorebooks / quickMessages (by id)
 */
internal fun applyCrossDomainConsistency(settings: Settings): Settings {
    val migratedSettings = settings.withMigratedMemoryDreamLegacy()
    val validMcpServerIds = migratedSettings.mcpServers.map { it.id }.toSet()
    val validModeInjectionIds = migratedSettings.modeInjections.map { it.id }.toSet()
    val validLorebookIds = migratedSettings.lorebooks.map { it.id }.toSet()
    // Normalize search references on every read, including fresh installs and
    // migration gaps where no settings write has occurred yet.
    val cleanedSearchSelected = if (migratedSettings.searchServices.isEmpty()) {
        0
    } else {
        migratedSettings.searchServiceSelected.coerceIn(0, migratedSettings.searchServices.lastIndex)
    }
    val cleanedSearchEnabledIds = migratedSettings.searchEnabledServiceIds
        .filter { id -> migratedSettings.searchServices.any { service -> service.id == id } }
        .ifEmpty {
            migratedSettings.searchServices.getOrNull(cleanedSearchSelected)
                ?.let { listOf(it.id) }
                .orEmpty()
        }
    return migratedSettings.copy(
        searchServiceSelected = cleanedSearchSelected,
        searchEnabledServiceIds = cleanedSearchEnabledIds,
        providers = migratedSettings.providers.distinctBy { it.id }.map { provider ->
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
        enabledMcpServerIds = migratedSettings.enabledMcpServerIds.filter { it in validMcpServerIds }.toSet(),
        enabledModeInjectionIds = migratedSettings.enabledModeInjectionIds.filter { it in validModeInjectionIds }.toSet(),
        enabledLorebookIds = migratedSettings.enabledLorebookIds.filter { it in validLorebookIds }.toSet(),
        favoriteModels = migratedSettings.favoriteModels.filter { uuid ->
            migratedSettings.providers.flatMap { it.models }.any { m -> m.id == uuid }
        },
        modeInjections = migratedSettings.modeInjections.distinctBy { it.id },
        lorebooks = migratedSettings.lorebooks.distinctBy { it.id },
        quickMessages = migratedSettings.quickMessages.distinctBy { it.id },
    )
}

private fun Settings.withMigratedMemoryDreamLegacy(): Settings {
    val worker = agentRuntime.memoryWorker
    if (!worker.dreamEnabled) return this
    return copy(
        agentRuntime = agentRuntime.copy(
            memoryWorker = worker.copy(
                dreamModelEnabled = worker.dreamModelEnabled || worker.dreamEnabled,
                dreamEnabled = false,
            )
        )
    )
}
