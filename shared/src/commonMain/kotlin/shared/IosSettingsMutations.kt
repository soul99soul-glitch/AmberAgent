package shared

import app.amber.ai.core.MessageRole
import app.amber.ai.provider.CustomHeader
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.core.settings.DEFAULT_PROVIDERS
import app.amber.core.model.InjectionPosition
import app.amber.core.model.Lorebook
import app.amber.core.model.PromptInjection
import app.amber.core.settings.Settings
import app.amber.core.settings.getCurrentAssistant
import app.amber.feature.board.DEEP_READ_FONT_SCALE_MAX
import app.amber.feature.board.DEEP_READ_FONT_SCALE_MIN
import app.amber.feature.board.TodayBoardHotListFilterMode
import app.amber.feature.board.TodayBoardReadingFontMode
import app.amber.feature.modelcouncil.ModelCouncilSeat
import app.amber.feature.modelcouncil.ModelCouncilSeatRunner
import app.amber.feature.subagent.SubAgentOverride
import app.amber.search.SearchServiceOptions
import app.amber.tts.provider.TTSProviderSetting

/**
 * Swift-facing typed mutations over the real KMP [Settings] data class.
 *
 * Rationale: KMP data-class `copy()` is not exposed to ObjC/Swift by the
 * framework generator, and the relevant fields (providers / ttsProviders /
 * searchServices / agentRuntime.modelCouncil.defaultSeats) involve sealed
 * classes that are awkward to reconstruct from Swift. Keeping the `.copy()`
 * logic here (native Kotlin) lets the iOS side call one typed function per
 * mutation and get back a new snapshot, which it then persists via
 * [IosSettingsJsonBridge].
 *
 * These are pure functions: they take a snapshot and return a new snapshot;
 * they never touch UserDefaults. The iOS store owns durability.
 */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
object IosSettingsMutations {

    // ---- Council seats (agentRuntime.modelCouncil.defaultSeats) ----

    /**
     * Append a council seat to `agentRuntime.modelCouncil.defaultSeats`.
     * [modelId] must already be a valid Uuid string (parsed here). Returns a
     * new [Settings]; caller persists via restoreSnapshot.
     */
    fun addCouncilSeat(
        settings: Settings,
        seatId: String,
        name: String,
        role: String,
        modelId: String,
        runnerType: String,
        systemPrompt: String = "",
        outputBudgetChars: Int = DEFAULT_OUTPUT_BUDGET_CHARS,
    ): Settings {
        val parsedModelId = kotlin.uuid.Uuid.parse(modelId)
        val seat = ModelCouncilSeat(
            seatId = seatId,
            name = name,
            role = role,
            modelId = parsedModelId,
            runnerType = parseRunnerType(runnerType),
            systemPrompt = systemPrompt,
            outputBudgetChars = outputBudgetChars,
        )
        val council = settings.agentRuntime.modelCouncil
        return settings.copy(
            agentRuntime = settings.agentRuntime.copy(
                modelCouncil = council.copy(defaultSeats = council.defaultSeats + seat)
            )
        )
    }

    /** Remove a council seat by [seatId]; no-op if not found. */
    fun removeCouncilSeat(settings: Settings, seatId: String): Settings {
        val council = settings.agentRuntime.modelCouncil
        return settings.copy(
            agentRuntime = settings.agentRuntime.copy(
                modelCouncil = council.copy(
                    defaultSeats = council.defaultSeats.filterNot { it.seatId == seatId }
                )
            )
        )
    }

    // ---- Providers (settings.providers: List<ProviderSetting>) ----

    /**
     * Append a fully-constructed [provider] to `settings.providers`. Returns a
     * new [Settings]. The caller (Swift) passes a pre-built
     * `ProviderSetting.OpenAI` (or another subtype) — building the sealed
     * subtype is exposed via [buildOpenAIProvider] below.
     */
    fun addProvider(settings: Settings, provider: ProviderSetting): Settings {
        return settings.copy(providers = settings.providers + provider)
    }

    /** Replace an existing provider by id; no-op if the provider is not present. */
    fun replaceProvider(settings: Settings, provider: ProviderSetting): Settings {
        return settings.copy(
            providers = settings.providers.map { if (it.id == provider.id) provider else it }
        )
    }

    /** Remove a provider by [id] (Uuid string); no-op if not found. */
    fun removeProvider(settings: Settings, id: String): Settings {
        val parsed = kotlin.uuid.Uuid.parse(id)
        return settings.copy(providers = settings.providers.filterNot { it.id == parsed })
    }

    /**
     * Switch a provider between the OpenAI-compatible and Anthropic-compatible
     * protocol subtypes while preserving the user-facing provider identity.
     *
     * This is intentionally scoped to the two iOS chat executors that exist today.
     * Google, Response API, and unknown values are returned unchanged.
     */
    fun switchProviderProtocol(
        settings: Settings,
        providerId: String,
        protocolType: String,
    ): Settings {
        val parsed = runCatching { kotlin.uuid.Uuid.parse(providerId) }.getOrNull() ?: return settings
        val target = protocolType.lowercase()
        val providers = settings.providers.map { provider ->
            if (provider.id != parsed) {
                provider
            } else {
                when (target) {
                    "openai", "openai-compatible", "open_ai" -> provider.asOpenAICompatible()
                    "anthropic", "claude" -> provider.asClaudeCompatible()
                    else -> provider
                }
            }
        }
        return settings.copy(providers = providers)
    }

    fun convertProviderProtocol(
        settings: Settings,
        providerId: String,
        target: String,
    ): Settings = switchProviderProtocol(settings, providerId, target)

    fun updateProviderBasics(
        settings: Settings,
        providerId: String,
        name: String,
        enabled: Boolean,
    ): Settings {
        val parsed = runCatching { kotlin.uuid.Uuid.parse(providerId) }.getOrNull() ?: return settings
        return settings.copy(
            providers = settings.providers.map { provider ->
                if (provider.id == parsed) {
                    provider.copyProvider(name = name, enabled = enabled)
                } else {
                    provider
                }
            }
        )
    }

    fun updateProviderEndpoint(
        settings: Settings,
        providerId: String,
        baseUrl: String,
        chatCompletionsPath: String,
        useResponseApi: Boolean,
        promptCaching: Boolean,
    ): Settings {
        val parsed = runCatching { kotlin.uuid.Uuid.parse(providerId) }.getOrNull() ?: return settings
        return settings.copy(
            providers = settings.providers.map { provider ->
                if (provider.id != parsed) {
                    provider
                } else {
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            baseUrl = baseUrl,
                            chatCompletionsPath = chatCompletionsPath,
                            useResponseApi = useResponseApi,
                        )
                        is ProviderSetting.Google -> provider.copy(baseUrl = baseUrl)
                        is ProviderSetting.Claude -> provider.copy(
                            baseUrl = baseUrl,
                            promptCaching = promptCaching,
                        )
                    }
                }
            }
        )
    }

    /**
     * Construct an Anthropic Claude [ProviderSetting.Claude] with a single model.
     * iOS counterpart of [buildOpenAIProvider] for the Anthropic protocol.
     */
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun buildClaudeProvider(
        name: String,
        apiKey: String,
        baseUrl: String,
        modelName: String,
        modelId: String,
    ): ProviderSetting.Claude {
        val model = Model(
            modelId = modelId,
            displayName = modelName,
            id = kotlin.uuid.Uuid.random(),
            type = ModelType.CHAT,
        )
        return ProviderSetting.Claude(
            id = kotlin.uuid.Uuid.random(),
            name = name,
            apiKey = apiKey,
            baseUrl = baseUrl,
            models = listOf(model),
            builtIn = false,
        )
    }

    fun buildBlankClaudeProvider(
        name: String,
        apiKey: String,
        baseUrl: String,
    ): ProviderSetting.Claude {
        return ProviderSetting.Claude(
            id = kotlin.uuid.Uuid.random(),
            name = name,
            apiKey = apiKey,
            baseUrl = baseUrl,
            models = emptyList(),
            builtIn = false,
        )
    }

    /**
     * Write an API key onto the provider identified by [providerId] inside
     * `settings.providers`, preserving every other field. Works for OpenAI,
     * Google, and Claude provider subtypes (dispatched by sealed type). Returns
     * the original snapshot unchanged if the id is not found. This is the
     * Swift-facing equivalent of `provider.copy(apiKey = ...)` (KMP data-class
     * copy is not exposed to ObjC).
     */
    fun updateProviderApiKey(settings: Settings, providerId: String, apiKey: String): Settings {
        val parsed = runCatching { kotlin.uuid.Uuid.parse(providerId) }.getOrNull() ?: return settings
        val providers = settings.providers.map { provider ->
            if (provider.id != parsed) {
                provider
            } else {
                when (provider) {
                    is ProviderSetting.OpenAI -> provider.copy(apiKey = apiKey)
                    is ProviderSetting.Google -> provider.copy(apiKey = apiKey)
                    is ProviderSetting.Claude -> provider.copy(apiKey = apiKey)
                }
            }
        }
        return settings.copy(providers = providers)
    }

    /**
     * Replace the chat-typed models on the provider identified by [providerId].
     * Used by the iOS provider editor's model field to set/update the models a
     * provider exposes. Non-chat models on the provider are preserved.
     * Returns the original snapshot unchanged if the id is not found.
     */
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun updateProviderChatModels(
        settings: Settings,
        providerId: String,
        modelIds: List<Pair<String, String>>,  // (modelId, displayName)
    ): Settings {
        val parsed = runCatching { kotlin.uuid.Uuid.parse(providerId) }.getOrNull() ?: return settings
        val newChatModels = modelIds.map { (modelId, displayName) ->
            Model(
                modelId = modelId,
                displayName = displayName,
                id = kotlin.uuid.Uuid.random(),
                type = ModelType.CHAT,
            )
        }
        val providers = settings.providers.map { provider ->
            if (provider.id != parsed) {
                provider
            } else {
                val nonChat = provider.models.filter { it.type != ModelType.CHAT }
                val merged = nonChat + newChatModels
                when (provider) {
                    is ProviderSetting.OpenAI -> provider.copy(models = merged)
                    is ProviderSetting.Google -> provider.copy(models = merged)
                    is ProviderSetting.Claude -> provider.copy(models = merged)
                }
            }
        }
        return settings.copy(providers = providers)
    }

    fun upsertProviderChatModel(
        settings: Settings,
        providerId: String,
        modelUuid: String?,
        modelId: String,
        displayName: String,
        contextWindowTokens: Int?,
        headerPairs: List<Pair<String, String>>,
    ): Settings {
        val parsedProvider = runCatching { kotlin.uuid.Uuid.parse(providerId) }.getOrNull() ?: return settings
        val parsedModel = modelUuid?.takeIf { it.isNotBlank() }?.let {
            runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull()
        }
        val headers = headerPairs
            .filter { (name, _) -> name.isNotBlank() }
            .map { (name, value) -> CustomHeader(name = name, value = value) }
        return settings.copy(
            providers = settings.providers.map { provider ->
                if (provider.id != parsedProvider) {
                    provider
                } else {
                    val existing = provider.models.firstOrNull { model ->
                        (parsedModel != null && model.id == parsedModel) ||
                            (parsedModel == null && model.modelId == modelId && model.type == ModelType.CHAT)
                    }
                    val updated = if (existing != null) {
                        existing.copy(
                            modelId = modelId,
                            displayName = displayName.ifBlank { modelId },
                            type = ModelType.CHAT,
                            contextWindowTokens = contextWindowTokens,
                            customHeaders = headers,
                        )
                    } else {
                        Model(
                            modelId = modelId,
                            displayName = displayName.ifBlank { modelId },
                            id = kotlin.uuid.Uuid.random(),
                            type = ModelType.CHAT,
                            customHeaders = headers,
                            contextWindowTokens = contextWindowTokens,
                        )
                    }
                    val models = if (existing != null) {
                        provider.models.map { if (it.id == existing.id) updated else it }
                    } else {
                        provider.models + updated
                    }
                    provider.copyProvider(models = models)
                }
            }
        )
    }

    fun removeProviderChatModel(
        settings: Settings,
        providerId: String,
        modelUuid: String,
    ): Settings {
        val parsedProvider = runCatching { kotlin.uuid.Uuid.parse(providerId) }.getOrNull() ?: return settings
        val parsedModel = runCatching { kotlin.uuid.Uuid.parse(modelUuid) }.getOrNull() ?: return settings
        return settings.copy(
            providers = settings.providers.map { provider ->
                if (provider.id == parsedProvider) {
                    provider.copyProvider(models = provider.models.filterNot { it.id == parsedModel })
                } else {
                    provider
                }
            }
        )
    }

    /**
     * Set the global `settings.chatModelId` to a specific model UUID string. iOS
     * uses this when the user picks the current chat model. Returns a new snapshot.
     */
    fun setChatModelId(settings: Settings, modelId: String): Settings {
        val parsed = runCatching { kotlin.uuid.Uuid.parse(modelId) }.getOrNull() ?: return settings
        return settings.copy(chatModelId = parsed)
    }

    /**
     * Construct an OpenAI-compatible [ProviderSetting.OpenAI] with a single
     * model. This is what iOS "add custom model" maps to: a user-added model
     * lives in its own OpenAI-compatible provider entry (builtIn=false,
     * brand=GENERIC), matching the Android PreferencesStore convention.
     */
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    fun buildOpenAIProvider(
        name: String,
        apiKey: String,
        baseUrl: String,
        modelName: String,
        modelId: String,
    ): ProviderSetting.OpenAI {
        val model = Model(
            modelId = modelId,
            displayName = modelName,
            id = kotlin.uuid.Uuid.random(),
            type = ModelType.CHAT,
        )
        return ProviderSetting.OpenAI(
            id = kotlin.uuid.Uuid.random(),
            name = name,
            apiKey = apiKey,
            baseUrl = baseUrl,
            models = listOf(model),
            builtIn = false,
        )
    }

    fun buildBlankOpenAIProvider(
        name: String,
        apiKey: String,
        baseUrl: String,
    ): ProviderSetting.OpenAI {
        return ProviderSetting.OpenAI(
            id = kotlin.uuid.Uuid.random(),
            name = name,
            apiKey = apiKey,
            baseUrl = baseUrl,
            models = emptyList(),
            builtIn = false,
            brand = OpenAIBrand.GENERIC,
        )
    }

    /**
     * Construct a key-less OpenAI-compatible provider with a stable id supplied
     * by iOS. Used by the Provider registry so a custom provider can survive
     * app restarts while its API key remains in the matching iOS Keychain slot.
     */
    fun buildOpenAIProviderWithId(
        id: String,
        name: String,
        baseUrl: String,
    ): ProviderSetting.OpenAI {
        return ProviderSetting.OpenAI(
            id = kotlin.uuid.Uuid.parse(id),
            name = name,
            apiKey = "",
            baseUrl = baseUrl,
            models = emptyList(),
            builtIn = false,
        )
    }

    private fun ProviderSetting.asOpenAICompatible(): ProviderSetting {
        if (this is ProviderSetting.OpenAI && !useResponseApi) return this
        val defaultOpenAI = DEFAULT_PROVIDERS.firstOrNull { it.id == id } as? ProviderSetting.OpenAI
        val apiKey = when (this) {
            is ProviderSetting.OpenAI -> apiKey
            is ProviderSetting.Google -> apiKey
            is ProviderSetting.Claude -> apiKey
        }
        val sourceBaseUrl = when (this) {
            is ProviderSetting.OpenAI -> baseUrl
            is ProviderSetting.Google -> baseUrl
            is ProviderSetting.Claude -> baseUrl
        }
        val targetDefaultBaseUrl = defaultOpenAI?.baseUrl ?: ProviderSetting.OpenAI().baseUrl
        return ProviderSetting.OpenAI(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            balanceOption = defaultOpenAI?.balanceOption ?: balanceOption,
            builtIn = builtIn,
            descriptionText = descriptionText,
            shortDescriptionText = shortDescriptionText,
            apiKey = apiKey,
            baseUrl = sourceBaseUrl.convertToTargetBaseUrl(targetDefaultBaseUrl),
            chatCompletionsPath = defaultOpenAI?.chatCompletionsPath ?: "/chat/completions",
            useResponseApi = false,
            authMode = defaultOpenAI?.authMode ?: OpenAIAuthMode.API_KEY,
            brand = defaultOpenAI?.brand ?: OpenAIBrand.GENERIC,
        )
    }

    private fun ProviderSetting.asClaudeCompatible(): ProviderSetting {
        if (this is ProviderSetting.Claude) return this
        val apiKey = when (this) {
            is ProviderSetting.OpenAI -> apiKey
            is ProviderSetting.Google -> apiKey
            is ProviderSetting.Claude -> apiKey
        }
        val sourceBaseUrl = when (this) {
            is ProviderSetting.OpenAI -> baseUrl
            is ProviderSetting.Google -> baseUrl
            is ProviderSetting.Claude -> baseUrl
        }
        return ProviderSetting.Claude(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            balanceOption = balanceOption,
            builtIn = builtIn,
            descriptionText = descriptionText,
            shortDescriptionText = shortDescriptionText,
            apiKey = apiKey,
            baseUrl = sourceBaseUrl.convertToTargetBaseUrl(ProviderSetting.Claude().baseUrl),
        )
    }

    private fun String.convertToTargetBaseUrl(targetDefaultBaseUrl: String): String {
        val source = parseBaseUrl(trim().trimEnd('/')) ?: return this
        if (source.host in OFFICIAL_PROVIDER_HOSTS) {
            return targetDefaultBaseUrl
        }
        val target = parseBaseUrl(targetDefaultBaseUrl) ?: return this
        val convertedPath = source.path.convertToTargetPath(target.path)
        return source.origin + convertedPath
    }

    private fun String.convertToTargetPath(targetPath: String): String {
        val source = normalizePath()
        val target = targetPath.normalizePath()
        val replaced = when {
            source.lowercase().endsWith(V1_BETA_SUFFIX) -> source.dropLast(V1_BETA_SUFFIX.length) + target
            source.lowercase().endsWith(V1_SUFFIX) -> source.dropLast(V1_SUFFIX.length) + target
            source.isBlank() -> target
            else -> source + target
        }
        return replaced.normalizePath()
    }

    private fun String.normalizePath(): String {
        val value = trim()
        if (value.isEmpty() || value == "/") return ""
        val path = if (value.startsWith("/")) value else "/$value"
        return path.trimEnd('/')
    }

    private data class ParsedBaseUrl(
        val origin: String,
        val host: String,
        val path: String,
    )

    private fun parseBaseUrl(value: String): ParsedBaseUrl? {
        val match = Regex("^([A-Za-z][A-Za-z0-9+.-]*://([^/?#]+))([^?#]*)").find(value) ?: return null
        val origin = match.groupValues[1].trimEnd('/')
        val host = match.groupValues[2].substringBefore(':').lowercase()
        val path = match.groupValues[3]
        return ParsedBaseUrl(origin = origin, host = host, path = path)
    }

    private const val OPENAI_OFFICIAL_HOST = "api.openai.com"
    private const val GOOGLE_OFFICIAL_HOST = "generativelanguage.googleapis.com"
    private const val CLAUDE_OFFICIAL_HOST = "api.anthropic.com"
    private const val V1_SUFFIX = "/v1"
    private const val V1_BETA_SUFFIX = "/v1beta"
    private val OFFICIAL_PROVIDER_HOSTS = setOf(
        OPENAI_OFFICIAL_HOST,
        GOOGLE_OFFICIAL_HOST,
        CLAUDE_OFFICIAL_HOST,
    )

    // ---- TTS providers (settings.ttsProviders: List<TTSProviderSetting>) ----

    /** Append a fully-constructed [provider] to `settings.ttsProviders`. */
    fun addTtsProvider(settings: Settings, provider: TTSProviderSetting): Settings {
        return settings.copy(ttsProviders = settings.ttsProviders + provider)
    }

    /** Remove a TTS provider by [id] (Uuid string); no-op if not found. */
    fun removeTtsProvider(settings: Settings, id: String): Settings {
        val parsed = kotlin.uuid.Uuid.parse(id)
        return settings.copy(ttsProviders = settings.ttsProviders.filterNot { it.id == parsed })
    }

    /**
     * Construct an OpenAI-compatible [TTSProviderSetting.OpenAI]. The iOS
     * "add TTS engine" form collects (name, engineType, apiKey, model); this
     * factory builds the OpenAI subtype when engineType == "openai". Other
     * engine types are not built from the iOS form in this slice (their markers
     * stay 待接 honestly).
     */
    fun buildOpenAITtsProvider(
        name: String,
        apiKey: String,
        model: String,
    ): TTSProviderSetting.OpenAI {
        return TTSProviderSetting.OpenAI(
            id = kotlin.uuid.Uuid.random(),
            name = name,
            apiKey = apiKey,
            model = model,
        )
    }

    // ---- Search services (settings.searchServices: List<SearchServiceOptions>) ----

    /** Append a fully-constructed [service] to `settings.searchServices`. */
    fun addSearchService(settings: Settings, service: SearchServiceOptions): Settings {
        return settings.copy(searchServices = settings.searchServices + service)
    }

    /**
     * Append [service], make it the selected search service, and mark it
     * enabled. Mirrors Android's add-provider sheet behavior so iOS additions
     * are immediately visible to the search execution route.
     */
    fun addSearchServiceAndSelect(settings: Settings, service: SearchServiceOptions): Settings {
        val services = settings.searchServices + service
        return settings.copy(
            searchServices = services,
            searchServiceSelected = services.lastIndex,
            searchEnabledServiceIds = (settings.searchEnabledServiceIds + service.id).distinct(),
        )
    }

    /** Remove a search service by [id] (Uuid string); no-op if not found. */
    fun removeSearchService(settings: Settings, id: String): Settings {
        val parsed = kotlin.uuid.Uuid.parse(id)
        val services = settings.searchServices.filterNot { it.id == parsed }
        val selected = if (services.isEmpty()) {
            0
        } else {
            settings.searchServiceSelected.coerceIn(0, services.lastIndex)
        }
        val enabledIds = settings.searchEnabledServiceIds
            .filterNot { it == parsed }
            .filter { id -> services.any { service -> service.id == id } }
            .ifEmpty { services.getOrNull(selected)?.let { listOf(it.id) }.orEmpty() }
        return settings.copy(
            searchServices = services,
            searchServiceSelected = selected,
            searchEnabledServiceIds = enabledIds,
        )
    }

    /** Select a search service by [index], clamped to the current service list. */
    fun selectSearchService(settings: Settings, index: Int): Settings {
        val selected = if (settings.searchServices.isEmpty()) {
            0
        } else {
            index.coerceIn(0, settings.searchServices.lastIndex)
        }
        return settings.copy(searchServiceSelected = selected)
    }

    /** Enable or disable one search service by [id] (Uuid string). */
    fun setSearchServiceEnabled(settings: Settings, id: String, enabled: Boolean): Settings {
        val parsed = kotlin.uuid.Uuid.parse(id)
        if (settings.searchServices.none { it.id == parsed }) return settings
        val enabledIds = if (enabled) {
            (settings.searchEnabledServiceIds + parsed).distinct()
        } else {
            settings.searchEnabledServiceIds.filterNot { it == parsed }
        }
        return settings.copy(searchEnabledServiceIds = enabledIds)
    }

    /** Toggle the master web-search gate consumed by iOS ChatViewModel. */
    fun setEnableWebSearch(settings: Settings, enabled: Boolean): Settings {
        return settings.copy(enableWebSearch = enabled)
    }

    /** Enable or disable the built-in DuckDuckGo Lite fallback source. */
    fun setSearchBuiltinDuckDuckGoEnabled(settings: Settings, enabled: Boolean): Settings {
        return settings.copy(searchBuiltinDuckDuckGoEnabled = enabled)
    }

    /** Enable or disable the built-in Bing HTML fallback source. */
    fun setSearchBuiltinBingEnabled(settings: Settings, enabled: Boolean): Settings {
        return settings.copy(searchBuiltinBingEnabled = enabled)
    }

    /**
     * Construct a [SearchServiceOptions] by [serviceType] (the @SerialName wire
     * name, e.g. "bing_local"/"tavily"/"zhipu"/"exa"/"brave"/"serper"/
     * "serpapi"/"metaso"/"perplexity"/"firecrawl"/"jina"/"bocha"/"grok"/
     * "linkup"). Sets [apiKey] where the subtype supports it. Unknown types
     * fall back to Tavily (which has an apiKey field) so the entry is still
     * persisted + editable; the iOS form is responsible for offering only
     * known types.
     */
    fun buildSearchService(serviceType: String, apiKey: String): SearchServiceOptions {
        val id = kotlin.uuid.Uuid.random()
        return when (serviceType.lowercase()) {
            "bing", "bing_local" -> SearchServiceOptions.BingLocalOptions(id = id)
            "zhipu" -> SearchServiceOptions.ZhipuOptions(id = id, apiKey = apiKey)
            "exa" -> SearchServiceOptions.ExaOptions(id = id, apiKey = apiKey)
            "searxng" -> SearchServiceOptions.SearXNGOptions(id = id)
            "brave" -> SearchServiceOptions.BraveOptions(id = id, apiKey = apiKey)
            "serper" -> SearchServiceOptions.SerperOptions(id = id, apiKey = apiKey)
            "serpapi" -> SearchServiceOptions.SerpApiOptions(id = id, apiKey = apiKey)
            "metaso" -> SearchServiceOptions.MetasoOptions(id = id, apiKey = apiKey)
            "ollama" -> SearchServiceOptions.OllamaOptions(id = id, apiKey = apiKey)
            "perplexity" -> SearchServiceOptions.PerplexityOptions(id = id, apiKey = apiKey)
            "firecrawl" -> SearchServiceOptions.FirecrawlOptions(id = id, apiKey = apiKey)
            "jina" -> SearchServiceOptions.JinaOptions(id = id, apiKey = apiKey)
            "bocha" -> SearchServiceOptions.BochaOptions(id = id, apiKey = apiKey)
            "amber_agent" -> SearchServiceOptions.AmberAgentSearchOptions(id = id, apiKey = apiKey)
            "grok" -> SearchServiceOptions.GrokOptions(id = id, apiKey = apiKey)
            "linkup" -> SearchServiceOptions.LinkUpOptions(id = id, apiKey = apiKey)
            "tavily" -> SearchServiceOptions.TavilyOptions(id = id, apiKey = apiKey)
            // Unknown type: default to Tavily (has apiKey) so the entry is
            // persisted and editable. Honest — the iOS form offers known types.
            else -> SearchServiceOptions.TavilyOptions(id = id, apiKey = apiKey)
        }
    }

    // ---- SubAgent overrides (settings.agentRuntime.subAgent.overrides) ----
    // NOTE: the path is agentRuntime.subAgent (subAgent is a field of
    // AgentRuntimeSetting, not of Settings directly — Settings only carries
    // agentRuntime). This compiles fine from :shared.

    /**
     * Put a [SubAgentOverride] for [roleId] into
     * `agentRuntime.subAgent.overrides`. Only [systemPrompt] is writable from
     * iOS in this slice; other override fields keep their defaults. Returns a
     * new [Settings]; caller persists via restoreSnapshot.
     */
    fun putSubAgentOverride(
        settings: Settings,
        roleId: String,
        systemPrompt: String,
    ): Settings {
        val sub = settings.agentRuntime.subAgent
        val existing = sub.overrides[roleId]
        val merged = (existing ?: SubAgentOverride()).copy(systemPrompt = systemPrompt)
        return settings.copy(
            agentRuntime = settings.agentRuntime.copy(
                subAgent = sub.copy(overrides = sub.overrides + (roleId to merged))
            )
        )
    }

    /** Remove a sub-agent override for [roleId]; no-op if not found. */
    fun removeSubAgentOverride(settings: Settings, roleId: String): Settings {
        val sub = settings.agentRuntime.subAgent
        return settings.copy(
            agentRuntime = settings.agentRuntime.copy(
                subAgent = sub.copy(
                    overrides = sub.overrides.filterKeys { it != roleId }
                )
            )
        )
    }

    // ---- Memory runtime switches (settings.agentRuntime.enable*Memory) ----

    fun setMemoryRuntimeEnabled(
        settings: Settings,
        enableCoreMemory: Boolean,
        enableShortTermMemory: Boolean,
        enableLongTermMemory: Boolean,
    ): Settings {
        return settings.copy(
            agentRuntime = settings.agentRuntime.copy(
                enableCoreMemory = enableCoreMemory,
                enableShortTermMemory = enableShortTermMemory,
                enableLongTermMemory = enableLongTermMemory,
            )
        )
    }

    // ---- Today Board / Deep Read settings (agentRuntime.todayBoard) ----

    /**
     * Replace the iOS-editable TodayBoard fields while preserving the rest of
     * [TodayBoardSetting]. Swift cannot safely reconstruct the Kotlin data class,
     * so it submits the complete editable surface here, then persists via
     * IOSSharedSettingsStore.restoreSnapshot.
     */
    fun setTodayBoardOptions(
        settings: Settings,
        boardModelId: String?,
        clearBoardModelId: Boolean,
        hotListRefreshIntervalMinutes: Int,
        hotListWifiOnly: Boolean,
        hotListEnabledSources: List<String>,
        hotListFocusKeywords: List<String>,
        hotListFilterModeWireName: String,
        boardReadingFontModeWireName: String,
        boardReadingFontPackId: String?,
        clearBoardReadingFontPackId: Boolean,
        deepReadFontScale: Float,
        deepReadTemplateId: String,
    ): Settings {
        val runtime = settings.agentRuntime
        val board = runtime.todayBoard
        val nextBoardModelId = when {
            clearBoardModelId -> null
            boardModelId != null -> boardModelId.trim().takeIf { it.isNotBlank() }
            else -> board.boardModelId
        }
        val nextPackId = when {
            clearBoardReadingFontPackId -> null
            boardReadingFontPackId != null -> boardReadingFontPackId.trim().takeIf { it.isNotBlank() }
            else -> board.boardReadingFontPackId
        }
        val nextSources = hotListEnabledSources
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val nextKeywords = hotListFocusKeywords
            .flatMap { raw -> raw.split(',', '，', '、', ';', '；', '\n', '\t') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(80)
        val nextTemplateId = deepReadTemplateId.trim().takeIf { it.isNotBlank() }
            ?: board.deepReadTemplateId

        return settings.copy(
            agentRuntime = runtime.copy(
                todayBoard = board.copy(
                    boardModelId = nextBoardModelId,
                    hotListRefreshIntervalMinutes = hotListRefreshIntervalMinutes.coerceAtLeast(30),
                    hotListWifiOnly = hotListWifiOnly,
                    hotListEnabledSources = nextSources,
                    hotListFocusKeywords = nextKeywords,
                    hotListFilterMode = TodayBoardHotListFilterMode.fromWireName(hotListFilterModeWireName),
                    boardReadingFontMode = TodayBoardReadingFontMode.fromWireName(boardReadingFontModeWireName),
                    boardReadingFontPackId = nextPackId,
                    deepReadFontScale = deepReadFontScale.coerceIn(DEEP_READ_FONT_SCALE_MIN, DEEP_READ_FONT_SCALE_MAX),
                    deepReadTemplateId = nextTemplateId,
                )
            )
        )
    }

    // ---- MiniApp host access switches (settings.agentRuntime.miniApp) ----

    fun setMiniAppHostAccess(
        settings: Settings,
        hostContextEnabled: Boolean,
        hostWriteEnabled: Boolean,
    ): Settings {
        val miniApp = settings.agentRuntime.miniApp
        return settings.copy(
            agentRuntime = settings.agentRuntime.copy(
                miniApp = miniApp.copy(
                    hostContextEnabled = hostContextEnabled,
                    hostWriteEnabled = hostWriteEnabled,
                )
            )
        )
    }

    fun setMiniAppRuntimeOptions(
        settings: Settings,
        enabled: Boolean,
        networkEnabled: Boolean,
        externalImagesEnabled: Boolean,
        searchEnabled: Boolean,
        clipboardCopyEnabled: Boolean,
        boardSummaryUpdateEnabled: Boolean,
        hostContextEnabled: Boolean,
        hostWriteEnabled: Boolean,
        aiEnabled: Boolean,
        sharedStoreEnabled: Boolean,
        eventBusEnabled: Boolean,
        launchEnabled: Boolean,
        sensorEnabled: Boolean,
        locationEnabled: Boolean,
        clipboardReadEnabled: Boolean,
        webViewDebugEnabled: Boolean,
        showSourceButton: Boolean,
    ): Settings {
        val miniApp = settings.agentRuntime.miniApp
        return settings.copy(
            agentRuntime = settings.agentRuntime.copy(
                miniApp = miniApp.copy(
                    enabled = enabled,
                    networkEnabled = networkEnabled,
                    externalImagesEnabled = externalImagesEnabled,
                    searchEnabled = searchEnabled,
                    clipboardCopyEnabled = clipboardCopyEnabled,
                    boardSummaryUpdateEnabled = boardSummaryUpdateEnabled,
                    hostContextEnabled = hostContextEnabled,
                    hostWriteEnabled = hostWriteEnabled,
                    aiEnabled = aiEnabled,
                    sharedStoreEnabled = sharedStoreEnabled,
                    eventBusEnabled = eventBusEnabled,
                    launchEnabled = launchEnabled,
                    sensorEnabled = sensorEnabled,
                    locationEnabled = locationEnabled,
                    clipboardReadEnabled = clipboardReadEnabled,
                    webViewDebugEnabled = webViewDebugEnabled,
                    showSourceButton = showSourceButton,
                )
            )
        )
    }

    // ---- Skills (assistant.enabledSkills) ----

    /** Swift-friendly read for the current assistant's enabled skill names. */
    fun currentAssistantEnabledSkillNames(settings: Settings): List<String> {
        return settings.getCurrentAssistant().enabledSkills.sorted()
    }

    /** Enable or disable one skill for the current assistant. */
    fun setSkillEnabledForCurrentAssistant(
        settings: Settings,
        skillName: String,
        enabled: Boolean,
    ): Settings {
        val normalized = skillName.trim()
        if (normalized.isBlank()) return settings
        val currentAssistantId = settings.getCurrentAssistant().id
        return settings.copy(
            assistants = settings.assistants.map { assistant ->
                if (assistant.id == currentAssistantId) {
                    val next = if (enabled) {
                        assistant.enabledSkills + normalized
                    } else {
                        assistant.enabledSkills - normalized
                    }
                    assistant.copy(enabledSkills = next)
                } else {
                    assistant
                }
            }
        )
    }

    /** Remove a deleted skill from every assistant so it cannot resurrect. */
    fun removeSkillFromAllAssistants(settings: Settings, skillName: String): Settings {
        val normalized = skillName.trim()
        if (normalized.isBlank()) return settings
        return settings.copy(
            assistants = settings.assistants.map { assistant ->
                if (normalized in assistant.enabledSkills) {
                    assistant.copy(enabledSkills = assistant.enabledSkills - normalized)
                } else {
                    assistant
                }
            }
        )
    }

    /**
     * Update the current assistant's generation-prompt fields: systemPrompt,
     * messageTemplate, temperature, topP, maxTokens, contextMessageSize. Any
     * argument left null is left unchanged on the assistant. This mirrors the
     * fields Android's AssistantBasicPage/AssistantPromptPage edit and that
     * `GenerationHandler` consumes. iOS-only bridge helper (same pattern as
     * [setSkillEnabledForCurrentAssistant]).
     */
    fun updateCurrentAssistantParams(
        settings: Settings,
        systemPrompt: String? = null,
        messageTemplate: String? = null,
        temperature: Float? = null,
        topP: Float? = null,
        maxTokens: Int? = null,
        contextMessageSize: Int? = null,
        clearTemperature: Boolean = false,
        clearTopP: Boolean = false,
        clearMaxTokens: Boolean = false,
    ): Settings {
        val currentAssistantId = settings.getCurrentAssistant().id
        return settings.copy(
            assistants = settings.assistants.map { assistant ->
                if (assistant.id != currentAssistantId) {
                    assistant
                } else {
                    assistant.copy(
                        systemPrompt = systemPrompt ?: assistant.systemPrompt,
                        messageTemplate = messageTemplate ?: assistant.messageTemplate,
                        temperature = if (clearTemperature) null else (temperature ?: assistant.temperature),
                        topP = if (clearTopP) null else (topP ?: assistant.topP),
                        maxTokens = if (clearMaxTokens) null else (maxTokens ?: assistant.maxTokens),
                        contextMessageSize = contextMessageSize ?: assistant.contextMessageSize,
                    )
                }
            }
        )
    }

    /**
     * Update the current user's nickname (`displaySetting.userNickname`). Android
     * edits this in ChatDrawer; iOS AccountView was preview-only. iOS-only
     * bridge helper (same pattern as [updateCurrentAssistantParams]).
     */
    fun updateUserNickname(settings: Settings, nickname: String): Settings {
        val trimmed = nickname.trim()
        return settings.copy(
            displaySetting = settings.displaySetting.copy(userNickname = trimmed)
        )
    }

    // ---- Mode injections (PromptInjection.ModeInjection) CRUD ----
    // Android extensions/PromptPage parity. iOS-only bridge helpers.

    /** Upsert a mode injection by id (insert if absent). */
    fun upsertModeInjection(
        settings: Settings,
        id: String,
        name: String,
        content: String,
        enabled: Boolean,
        priority: Int,
        position: String,
        role: String,
    ): Settings {
        val parsedId = runCatching { kotlin.uuid.Uuid.parse(id) }.getOrElse { kotlin.uuid.Uuid.random() }
        val injection = PromptInjection.ModeInjection(
            id = parsedId,
            name = name.trim(),
            content = content,
            enabled = enabled,
            priority = priority,
            position = parseInjectionPosition(position),
            role = parseMessageRole(role),
        )
        val existing = settings.modeInjections.toMutableList()
        val idx = existing.indexOfFirst { it.id == parsedId }
        if (idx >= 0) existing[idx] = injection else existing.add(injection)
        return settings.copy(modeInjections = existing)
    }

    /** Delete a mode injection by id string. */
    fun deleteModeInjection(settings: Settings, id: String): Settings {
        val parsedId = runCatching { kotlin.uuid.Uuid.parse(id) }.getOrNull() ?: return settings
        return settings.copy(
            modeInjections = settings.modeInjections.filterNot { it.id == parsedId }
        )
    }

    // ---- Lorebooks CRUD ----

    /** Upsert a lorebook by id (insert if absent). A lorebook holds keyword
     *  entries; pass an empty entries list to create a placeholder. */
    fun upsertLorebook(
        settings: Settings,
        id: String,
        name: String,
        description: String,
        enabled: Boolean,
    ): Settings {
        val parsedId = runCatching { kotlin.uuid.Uuid.parse(id) }.getOrElse { kotlin.uuid.Uuid.random() }
        val existing = settings.lorebooks.toMutableList()
        val idx = existing.indexOfFirst { it.id == parsedId }
        val lorebook = if (idx >= 0) {
            existing[idx].copy(name = name.trim(), description = description, enabled = enabled)
        } else {
            Lorebook(id = parsedId, name = name.trim(), description = description, enabled = enabled)
        }
        if (idx >= 0) existing[idx] = lorebook else existing.add(lorebook)
        return settings.copy(lorebooks = existing)
    }

    /** Delete a lorebook by id string. */
    fun deleteLorebook(settings: Settings, id: String): Settings {
        val parsedId = runCatching { kotlin.uuid.Uuid.parse(id) }.getOrNull() ?: return settings
        return settings.copy(lorebooks = settings.lorebooks.filterNot { it.id == parsedId })
    }

    private fun parseInjectionPosition(raw: String): InjectionPosition = when (raw.lowercase()) {
        "before_system_prompt", "before" -> InjectionPosition.BEFORE_SYSTEM_PROMPT
        "top_of_chat", "top" -> InjectionPosition.TOP_OF_CHAT
        "bottom_of_chat", "after_user", "bottom" -> InjectionPosition.BOTTOM_OF_CHAT
        "at_depth" -> InjectionPosition.AT_DEPTH
        else -> InjectionPosition.AFTER_SYSTEM_PROMPT
    }

    private fun parseMessageRole(raw: String): MessageRole = when (raw.lowercase()) {
        "assistant" -> MessageRole.ASSISTANT
        "system" -> MessageRole.SYSTEM
        else -> MessageRole.USER
    }

    // ---- helpers ----

    private fun parseRunnerType(raw: String): ModelCouncilSeatRunner {
        // Accept the legacy iOS shorthand ("provider"/"external") and the
        // serial name ("provider_model"/"external_cli"); default to PROVIDER_MODEL.
        return when (raw.lowercase()) {
            "external", "external_cli" -> ModelCouncilSeatRunner.EXTERNAL_CLI
            else -> ModelCouncilSeatRunner.PROVIDER_MODEL
        }
    }

    private const val DEFAULT_OUTPUT_BUDGET_CHARS = 4096
}
