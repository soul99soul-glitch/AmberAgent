package shared

import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.core.settings.Settings
import app.amber.feature.modelcouncil.ModelCouncilSeat
import app.amber.feature.modelcouncil.ModelCouncilSeatRunner
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
 *
 * NOTE: SubAgent overrides (`Settings.subAgent.overrides`) are NOT wired
 * here. Empirically, accessing `settings.subAgent` from the `:shared`
 * module fails to compile ("Unresolved reference 'subAgent'") even though
 * `settings.agentRuntime` (same Settings.kt, adjacent field) resolves. The
 * `:core:types` Settings field `subAgent: SubAgentRuntimeSetting` pulls its
 * type from `:feature:subagent:api`, and the `:shared` dependency graph
 * (`:feature:subagent` main module -> `:core:types` -> `:feature:subagent:api`)
 * produces metadata where that field is not visible to `:shared`. This is a
 * module-visibility problem worth a dedicated KMP cleanup, not something to
 * force-clone in Swift. Until then, the SubAgentRoleView edit markers stay
 * 待接 (honestly preserved).
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

    /** Remove a search service by [id] (Uuid string); no-op if not found. */
    fun removeSearchService(settings: Settings, id: String): Settings {
        val parsed = kotlin.uuid.Uuid.parse(id)
        return settings.copy(searchServices = settings.searchServices.filterNot { it.id == parsed })
    }

    /**
     * Construct a [SearchServiceOptions] by [serviceType] (the @SerialName wire
     * name, e.g. "tavily"/"zhipu"/"exa"/"brave"/"serper"/"serpapi"/"metaso"/
     * "perplexity"/"firecrawl"/"jina"/"bocha"/"grok"/"linkup"). Sets [apiKey]
     * where the subtype supports it. Unknown types fall back to Tavily (which
     * has an apiKey field) so the entry is still persisted + editable; the iOS
     * form is responsible for offering only known types.
     */
    fun buildSearchService(serviceType: String, apiKey: String): SearchServiceOptions {
        val id = kotlin.uuid.Uuid.random()
        return when (serviceType.lowercase()) {
            "zhipu" -> SearchServiceOptions.ZhipuOptions(id = id, apiKey = apiKey)
            "exa" -> SearchServiceOptions.ExaOptions(id = id, apiKey = apiKey)
            "brave" -> SearchServiceOptions.BraveOptions(id = id, apiKey = apiKey)
            "serper" -> SearchServiceOptions.SerperOptions(id = id, apiKey = apiKey)
            "serpapi" -> SearchServiceOptions.SerpApiOptions(id = id, apiKey = apiKey)
            "metaso" -> SearchServiceOptions.MetasoOptions(id = id, apiKey = apiKey)
            "perplexity" -> SearchServiceOptions.PerplexityOptions(id = id, apiKey = apiKey)
            "firecrawl" -> SearchServiceOptions.FirecrawlOptions(id = id, apiKey = apiKey)
            "jina" -> SearchServiceOptions.JinaOptions(id = id, apiKey = apiKey)
            "bocha" -> SearchServiceOptions.BochaOptions(id = id, apiKey = apiKey)
            "grok" -> SearchServiceOptions.GrokOptions(id = id, apiKey = apiKey)
            "linkup" -> SearchServiceOptions.LinkUpOptions(id = id, apiKey = apiKey)
            "tavily" -> SearchServiceOptions.TavilyOptions(id = id, apiKey = apiKey)
            // Unknown type: default to Tavily (has apiKey) so the entry is
            // persisted and editable. Honest — the iOS form offers known types.
            else -> SearchServiceOptions.TavilyOptions(id = id, apiKey = apiKey)
        }
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
