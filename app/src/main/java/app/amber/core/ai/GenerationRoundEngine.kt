package app.amber.core.ai

import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.core.ai.transformers.MessageTransformer
import app.amber.core.model.AssistantMemory
import app.amber.core.model.Conversation
import app.amber.core.settings.Settings
import app.amber.feature.runtime.SpeculativeToolRunner
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The kernel's per-round SPI: everything one model round needs, decided by
 * the [DefaultRunKernel] loop (exposed tools, budget prompt, speculative
 * runner) and executed by the engine (prompt/context assembly, provider
 * streaming with retry, Generative-UI and vision fallbacks).
 *
 * This is what remains of the retired `Generator` facade: consumers never
 * see it — only the kernel drives rounds through it.
 */
interface GenerationRoundEngine {

    /**
     * Stream one model round. Progress is reported through
     * [onUpdateMessages] ([GenerationUpdate.streamingTail] while tokens
     * arrive, [GenerationUpdate.full] at each turn boundary); round-level
     * semantics that die at the accumulator boundary (notably the provider
     * `finish_reason`) are returned in the [GenerationRoundOutcome].
     */
    suspend fun generateRound(
        request: GenerationRoundRequest,
        onUpdateMessages: suspend (GenerationUpdate) -> Unit,
    ): GenerationRoundOutcome
}

/**
 * Round-level outcome semantics the kernel consumes — deliberately the
 * boolean judgment, never the raw provider string: normalization is engine
 * business (it knows the per-provider finish_reason vocabularies), the loop
 * only needs "was the reply cut off by the output limit" (the iOS
 * `reachedOutputLimit` semantics) to guard half-emitted tool calls.
 */
class GenerationRoundOutcome(
    val outputLimitReached: Boolean,
)

class GenerationRoundRequest(
    val settings: Settings,
    val messages: List<UIMessage>,
    val transformers: List<MessageTransformer>,
    val model: Model,
    val tools: List<Tool>,
    val memories: List<AssistantMemory>,
    val stream: Boolean,
    val processingStatus: MutableStateFlow<String?>,
    val conversation: Conversation?,
    val speculativeRunner: SpeculativeToolRunner?,
    val loopBudgetPrompt: String,
    val responsesResume: app.amber.ai.provider.ResponsesResumeRequest?,
    // Step 5 — durable request-snapshot audit trail: the kernel threads the
    // run identity (the ledger's turnId counter) and the run's event writer
    // so the engine can commit a RequestSnapshot at the provider boundary.
    // Defaults keep unknown callers snapshot-free without breaking them.
    val runId: String? = null,
    val stepIndex: Int = -1,
    val events: app.amber.core.agent.runtime.AgentEventWriter? = null,
    // The kernel's durable-path decision (runId + onTerminal + ledger +
    // capability flags), computed once per run. Emission is gated on this —
    // not merely on `events != null` — so a snapshot is only committed when
    // the run's durable boundary is genuinely on and the audit trail exists.
    val durablePath: Boolean = false,
)
