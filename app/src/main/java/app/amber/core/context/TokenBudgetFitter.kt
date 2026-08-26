package app.amber.core.context

import app.amber.ai.core.InputSchema
import app.amber.ai.core.MessageRole
import app.amber.ai.core.Tool
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/**
 * P1-04 — final token budget hard fit.
 *
 * Runs once, at the provider request boundary (inside ChatRunCoordinator's
 * streamWith / generateWith, AFTER all transformers, mailbox and steer
 * merge). Covers: system prompt, memory, materials, document/OCR output,
 * tool schemas, mailbox/steer and the current-turn attachment text.
 *
 * Trimming policy (plan §P1-04):
 *  - never trimmed: system prompt (incl. safety rules), the current user
 *    request, and executed tool-call/result pairs — providers reject broken
 *    tool pairs, so results are never split from their calls.
 *  - trimmed first: old history (plain text messages), then steer/mailbox
 *    messages that were queued during generation (they are counted last).
 *  - if still over the hard budget after trimming, the request is NOT sent:
 *    a [ContextTooLargeException] is thrown and the failure is recorded in
 *    [TokenFitReceiptStore] for the debug page.
 *
 * The hard budget is the model context window minus an output reserve, so
 * the input alone can never exhaust the window before generation starts.
 * Token counting reuses the existing char-weight estimator
 * ([ContextFootprintEstimator]) — no new dependency. The only
 * provider-specific difference is the multimodal per-part cost (Claude's
 * (w*h)/750 pricing vs tile-based vision for OpenAI/Google-compatible
 * providers); everything else uses the shared estimator.
 */
object TokenBudgetFitter {

    private const val DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000
    private const val DEFAULT_OUTPUT_RESERVE_TOKENS = 4_096
    private const val MIN_HARD_BUDGET_TOKENS = 4_000
    // ContextFootprintEstimator counts multimodal parts at 4_500 weighted
    // chars (≈1125 tokens). Provider-specific deltas below.
    private const val BASE_MULTIMODAL_WEIGHTED_CHARS = 4_500
    private const val CLAUDE_IMAGE_WEIGHTED_CHARS = 5_600 // 1024² ≈ 1400 tokens at (w*h)/750
    private const val TILE_VISION_IMAGE_WEIGHTED_CHARS = 2_400 // tile-based vision ≈ 600 tokens

    private const val MAX_RECEIPTS = 20

    /** Ring buffer of fit outcomes, surfaced on the debug page. */
    private val _receipts = MutableStateFlow<List<TokenFitReceipt>>(emptyList())
    val receipts: StateFlow<List<TokenFitReceipt>> = _receipts.asStateFlow()

    /** Test hook — the store is global by design (debug observability). */
    fun clearReceiptsForTest() {
        _receipts.value = emptyList()
    }

    fun fit(
        messages: List<UIMessage>,
        tools: List<Tool>,
        model: Model,
        provider: ProviderSetting?,
        maxTokens: Int?,
        json: Json,
        conversationId: String? = null,
        runId: String? = null,
    ): TokenFitResult {
        val budget = hardBudgetTokens(model, maxTokens)
        val toolSchemaTokens = estimateToolSchemaTokens(tools, json)
        val classified = classifyMessages(messages)
        val estimatedBefore = estimateTokens(messages, provider) + toolSchemaTokens

        if (estimatedBefore <= budget) {
            val receipt = TokenFitReceipt(
                conversationId = conversationId,
                runId = runId,
                modelId = model.id.toString(),
                providerFamily = provider?.let(::providerFamilyName) ?: "unknown",
                budgetTokens = budget,
                estimatedBefore = estimatedBefore,
                estimatedAfter = estimatedBefore,
                toolSchemaTokens = toolSchemaTokens,
                trimmedMessages = emptyList(),
                contextTooLarge = false,
            )
            record(receipt)
            return TokenFitResult(messages = messages, tools = tools, receipt = receipt)
        }

        var remaining = classified.toMutableList()
        var total = estimatedBefore
        val trimmed = mutableListOf<TrimmedSegment>()
        val trimmedMessageIds = mutableListOf<String>()
        // Strict trim order: old history first, steer/mailbox last (they were
        // added to the request last, so they are the last to be cut).
        for (provenance in listOf(TokenFitProvenance.HISTORY, TokenFitProvenance.STEER)) {
            var count = 0
            var savedTokens = 0
            while (total > budget) {
                val index = remaining.indexOfFirst { it.second == provenance }
                if (index < 0) break
                val (dropped, _) = remaining.removeAt(index)
                val droppedTokens = estimateTokens(listOf(dropped), provider)
                total -= droppedTokens
                count++
                savedTokens += droppedTokens
                trimmedMessageIds += dropped.id.toString()
            }
            if (count > 0) {
                trimmed += TrimmedSegment(provenance = provenance, count = count, savedTokens = savedTokens)
            }
        }

        val fittedMessages = remaining.map { it.first }
        val estimatedAfter = estimateTokens(fittedMessages, provider) + toolSchemaTokens
        if (estimatedAfter > budget) {
            // Never send a request that is guaranteed to fail.
            val receipt = TokenFitReceipt(
                conversationId = conversationId,
                runId = runId,
                modelId = model.id.toString(),
                providerFamily = provider?.let(::providerFamilyName) ?: "unknown",
                budgetTokens = budget,
                estimatedBefore = estimatedBefore,
                estimatedAfter = estimatedAfter,
                toolSchemaTokens = toolSchemaTokens,
                trimmedMessages = trimmed,
                contextTooLarge = true,
            )
            record(receipt)
            throw ContextTooLargeException(
                receipt = receipt,
                trimmedMessageIds = trimmedMessageIds,
            )
        }
        val receipt = TokenFitReceipt(
            conversationId = conversationId,
            runId = runId,
            modelId = model.id.toString(),
            providerFamily = provider?.let(::providerFamilyName) ?: "unknown",
            budgetTokens = budget,
            estimatedBefore = estimatedBefore,
            estimatedAfter = estimatedAfter,
            toolSchemaTokens = toolSchemaTokens,
            trimmedMessages = trimmed,
            contextTooLarge = false,
        )
        // Trim outcomes are observable via TokenFitReceiptStore (debug page);
        // no android.util.Log here so the fitter stays pure-JVM testable.
        record(receipt)
        return TokenFitResult(messages = fittedMessages, tools = tools, receipt = receipt)
    }

    /**
     * Hard input budget: model context window minus the output reserve, so a
     * request that fits still leaves room for the generated response.
     */
    fun hardBudgetTokens(model: Model, maxTokens: Int?): Int {
        val contextWindow = model.contextWindowTokens?.takeIf { it > 0 }
            ?: DEFAULT_CONTEXT_WINDOW_TOKENS
        val outputReserve = (maxTokens ?: DEFAULT_OUTPUT_RESERVE_TOKENS).coerceIn(1, contextWindow - 1)
        return (contextWindow - outputReserve).coerceAtLeast(MIN_HARD_BUDGET_TOKENS)
    }

    /**
     * Provider-aware message estimate. Text/tool parts reuse
     * [ContextFootprintEstimator]; the only provider-specific component is
     * the multimodal per-part cost (one covered difference: Claude's
     * (w*h)/750 pricing vs tile-based vision elsewhere).
     */
    fun estimateTokens(messages: List<UIMessage>, provider: ProviderSetting? = null): Int {
        val base = ContextFootprintEstimator.estimateMessages(messages)
        val multimodalCount = messages.sumOf { message ->
            message.parts.count { part ->
                part is UIMessagePart.Image || part is UIMessagePart.Video || part is UIMessagePart.Audio
            }
        }
        if (multimodalCount == 0) return base
        val perPartWeighted = when (provider) {
            is ProviderSetting.Claude -> CLAUDE_IMAGE_WEIGHTED_CHARS
            else -> TILE_VISION_IMAGE_WEIGHTED_CHARS
        }
        val deltaTokens = multimodalCount * ((perPartWeighted - BASE_MULTIMODAL_WEIGHTED_CHARS) / 4)
        return (base + deltaTokens).coerceAtLeast(messages.size * 4)
    }

    /** Tool schema footprint (name + description + serialized parameters JSON). */
    fun estimateToolSchemaTokens(tools: List<Tool>, json: Json): Int {
        if (tools.isEmpty()) return 0
        val weighted = tools.sumOf { tool ->
            val schemaText = tool.parameters().let { schema ->
                if (schema == null) "" else runCatching {
                    json.encodeToString(InputSchema.serializer(), schema)
                }.getOrDefault("")
            }
            (tool.name + " " + tool.description + " " + schemaText).weightedTokenChars()
        }
        return (weighted / 4).coerceAtLeast(tools.size * 4)
    }

    /**
     * Classify every message into a provenance. Ordering rule: the last USER
     * message is always the current request; USER messages appended after the
     * last ASSISTANT turn (steer/mailbox queued during generation) are STEER
     * unless they are the current request; executed tool messages are
     * TOOL_RESULT (kept whole — call and result live in the same message).
     */
    internal fun classifyMessages(messages: List<UIMessage>): List<Pair<UIMessage, TokenFitProvenance>> {
        val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
        val lastAssistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        return messages.mapIndexed { index, message ->
            val provenance = when {
                message.role == MessageRole.SYSTEM -> TokenFitProvenance.SYSTEM
                index == lastUserIndex -> TokenFitProvenance.CURRENT_USER
                message.role == MessageRole.USER && lastAssistantIndex >= 0 && index > lastAssistantIndex ->
                    TokenFitProvenance.STEER
                message.getTools().any { it.isExecuted } -> TokenFitProvenance.TOOL_RESULT
                else -> TokenFitProvenance.HISTORY
            }
            message to provenance
        }
    }

    private fun record(receipt: TokenFitReceipt) {
        _receipts.update { (listOf(receipt) + it).take(MAX_RECEIPTS) }
    }

    private fun providerFamilyName(provider: ProviderSetting): String = when (provider) {
        is ProviderSetting.OpenAI -> "openai"
        is ProviderSetting.Google -> "google"
        is ProviderSetting.Claude -> "claude"
        else -> "generic"
    }
}

enum class TokenFitProvenance {
    /** System prompt incl. safety rules / memory / tool prompts — never trimmed. */
    SYSTEM,

    /** The last USER message — the current request — never trimmed. */
    CURRENT_USER,

    /** Assistant messages carrying executed tool call+result pairs — never trimmed. */
    TOOL_RESULT,

    /** USER messages queued during generation (mailbox/steer), counted last. */
    STEER,

    /** Old plain-text history — trimmed first. */
    HISTORY,
}

data class TrimmedSegment(
    val provenance: TokenFitProvenance,
    val count: Int,
    val savedTokens: Int,
)

/**
 * One final-fit outcome. Recorded for every provider-bound request (trimmed
 * or not) so the debug page can show what was cut and why.
 */
data class TokenFitReceipt(
    val conversationId: String?,
    val runId: String?,
    val modelId: String,
    val providerFamily: String,
    val budgetTokens: Int,
    val estimatedBefore: Int,
    val estimatedAfter: Int,
    val toolSchemaTokens: Int,
    val trimmedMessages: List<TrimmedSegment>,
    val contextTooLarge: Boolean,
    val atMillis: Long = System.currentTimeMillis(),
)

data class TokenFitResult(
    val messages: List<UIMessage>,
    val tools: List<Tool>,
    val receipt: TokenFitReceipt,
)

/**
 * P1-04 — the fitted request would still exceed the hard budget after
 * trimming, so it is never sent. Message text includes the "context too
 * large" marker so GenerationFailureClassifier classifies it as non-retryable
 * (Category.CONTEXT) instead of pointlessly retrying.
 */
class ContextTooLargeException(
    val receipt: TokenFitReceipt,
    val trimmedMessageIds: List<String>,
) : RuntimeException(
    "context too large for model window: estimated ${receipt.estimatedAfter} tokens " +
        "(incl. ${receipt.toolSchemaTokens} tool schema) exceeds hard budget ${receipt.budgetTokens} " +
        "after trimming ${receipt.trimmedMessages.joinToString { "${it.provenance}(${it.count})" }} — request not sent"
)
