package shared

import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.core.settings.Settings
import app.amber.core.settings.getCurrentAssistant
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

    /** Remove a provider by [id] (Uuid string); no-op if not found. */
    fun removeProvider(settings: Settings, id: String): Settings {
        val parsed = kotlin.uuid.Uuid.parse(id)
        return settings.copy(providers = settings.providers.filterNot { it.id == parsed })
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
