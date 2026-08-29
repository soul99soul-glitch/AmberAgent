package app.amber.core.ai

import app.amber.core.ai.transformers.InputMessageTransformer
import app.amber.core.ai.transformers.OutputMessageTransformer
import app.amber.core.model.AssistantMemory
import app.amber.core.model.Conversation
import app.amber.core.settings.Settings
import app.amber.feature.runtime.ToolInvocationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage

/**
 * The consumer-facing entry to the agent tool loop: one [run] executes the
 * full model-round / tool-execution cycle until a terminal condition.
 *
 * Consumers (chat turn, subagent, DeepRead, Novel) describe the run as a
 * [GenerationRunSession] and collect streamed [GenerationChunk]s; loop
 * mechanics (step budget, tool exposure, approval gating, write-ahead ledger,
 * steer drain) live behind this interface in the runtime's kernel
 * implementation. Per-round model streaming is the kernel's own SPI
 * (`GenerationRoundEngine` in :app), never exposed to consumers.
 */
interface RunKernel {

    fun run(session: GenerationRunSession): Flow<GenerationChunk>

}

/**
 * Everything the tool loop needs for one run, packed so the [RunKernel]
 * signature stays stable as loop concerns evolve.
 */
class GenerationRunSession(
    /** Global Amber runtime settings for this generation. */
    val settings: Settings,
    val model: Model,
    val messages: List<UIMessage>,
    val inputTransformers: List<InputMessageTransformer> = emptyList(),
    val outputTransformers: List<OutputMessageTransformer> = emptyList(),
    val memories: List<AssistantMemory>? = null,
    val tools: List<Tool> = emptyList(),
    val maxSteps: Int = 256,
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    val autoApproveTools: Boolean = false,
    val autoApproveHighRiskTools: Boolean = false,
    val autoApprovedToolNames: Set<String> = emptySet(),
    val invocationContext: ToolInvocationContext = ToolInvocationContext.Normal,
    val conversation: Conversation? = null,
    val consumeSteerMessages: suspend () -> List<UIMessage> = { emptyList() },
    val runId: String? = null,
    val onTerminal: (suspend (GenerationTerminal) -> Unit)? = null,
    /**
     * Protocol event sink for the tool lifecycle (Step 3): on the durable
     * path the kernel emits `ToolPrepared` after the write-ahead prepare and
     * the dispatcher emits `ToolStarted` / `ToolFinished` around execution,
     * each aligned with the ledger by effectId. Typically the run scope's
     * `AgentEventWriter`; null (tests, bare loops) keeps the loop silent.
     * Emission stays gated by the durable path even when a writer is present.
     */
    val toolLifecycleEvents: app.amber.core.agent.runtime.AgentEventWriter? = null,
    /**
     * Non-null enables server-side stored OpenAI Responses streaming for
     * this run (store=true + cursor persistence + reconnect + recovery).
     * Only the main chat path sets it, under the
     * openai_responses_resume capability flag + user toggle + strict
     * official-endpoint match; other callers keep the default.
     */
    val responsesResume: app.amber.ai.provider.ResponsesResumeRequest? = null,
    /**
     * Step 6 — separate sandbox from approval: the per-run sandbox policy the
     * kernel enforces at the ToolRuntime boundary on every tool execution,
     * independent of approval. Non-null by default
     * ([ExecutionPolicy.permissive]) so v1 production behavior is
     * byte-identical: only an explicitly narrowed policy changes what an
     * approved call may touch.
     */
    val executionPolicy: app.amber.feature.runtime.ExecutionPolicy =
        app.amber.feature.runtime.ExecutionPolicy.permissive(),
)
