package app.amber.feature.modelcouncil

import app.amber.ai.core.ReasoningLevel
import app.amber.core.settings.Settings
import kotlin.uuid.Uuid

/**
 * Abstraction over the host's tool-augmented generation, declared in this
 * module but implemented in `:app` (which owns [ProviderCatalog],
 * [app.amber.feature.runtime.AgentToolDispatcher] and the tool factories).
 *
 * The council orchestration code ([CouncilRoomManager]) only needs the *ability*
 * to run a host turn that may call read-only tools (search/scrape) to fill
 * information gaps. The actual streamText↔executeBatch loop, tool wiring and
 * ask_user HITL plumbing live behind this interface so this module never has to
 * depend on `:app` (which would invert the module graph).
 *
 * Inject a real implementation via DI; tests can inject a stub. A null provider
 * (or [isAvailable] == false) makes FULL mode degrade gracefully to a plain
 * tool-less host turn — i.e. STANDARD mode behaviour.
 */
interface CouncilHostToolProvider {
    /** True when the host's tool set is usable right now (e.g. a search provider is configured). */
    fun isAvailable(settings: Settings): Boolean

    /**
     * Run a host turn that may call read-only tools up to [maxToolRounds] times
     * before producing its final text. Only the host's own text is forwarded via
     * [onChunk] (cumulative) for live display; tool I/O is internal.
     *
     * `ask_user` is NOT executed inline — if the host emits one, the run stops
     * and returns [HostToolOutcome.AskUser] so the caller drives the HITL flow.
     *
     * @return the final text + warnings, or an AskUser hand-off.
     */
    suspend fun generateWithTools(
        settings: Settings,
        modelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        reasoningLevel: ReasoningLevel,
        maxToolRounds: Int,
        timeoutMs: Long,
        outputBudgetChars: Int,
        onChunk: (String) -> Unit = {},
    ): HostToolOutcome
}

/** Outcome of a [CouncilHostToolProvider.generateWithTools] run. */
sealed interface HostToolOutcome {
    /** The host finished (with or without using tools). */
    data class Done(val text: String, val warnings: List<String> = emptyList()) : HostToolOutcome

    /**
     * The host emitted an `ask_user` call. The caller must run the HITL flow
     * (room pause → user answers → resume) before continuing. [rawPayload] is
     * the model's ask_user arguments (a JSON string) for the UI to render.
     */
    data class AskUser(val rawPayload: String, val displayQuestion: String) : HostToolOutcome
}

/**
 * A no-op provider: always reports unavailable, so FULL mode degrades to STANDARD.
 *
 * Callers MUST gate on [isAvailable] before invoking [generateWithTools]. If they
 * forget and call anyway, this returns a [HostToolOutcome.Done] with an explicit
 * degradation warning rather than a silent empty string — so an empty host turn
 * is always attributable ("provider unavailable") instead of looking like the
 * host chose to say nothing.
 */
object NoOpCouncilHostToolProvider : CouncilHostToolProvider {
    override fun isAvailable(settings: Settings): Boolean = false
    override suspend fun generateWithTools(
        settings: Settings,
        modelId: Uuid,
        systemPrompt: String,
        userPrompt: String,
        reasoningLevel: ReasoningLevel,
        maxToolRounds: Int,
        timeoutMs: Long,
        outputBudgetChars: Int,
        onChunk: (String) -> Unit,
    ): HostToolOutcome = HostToolOutcome.Done(
        text = "",
        warnings = listOf("Host tool provider unavailable; FULL mode degraded to STANDARD for this turn."),
    )
}
