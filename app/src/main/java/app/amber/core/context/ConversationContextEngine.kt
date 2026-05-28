package app.amber.core.context

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.AppScope
import app.amber.feature.prompts.AgentPromptConfigRepository
import app.amber.core.settings.Settings
import app.amber.core.settings.findProvider
import app.amber.core.settings.resolveTaskChatModel
import app.amber.core.settings.toCompactPolicy
import app.amber.core.model.Conversation
import app.amber.core.model.MessageNode
import app.amber.core.utils.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ConversationContextEngine"

/**
 * Min millis between successive _summaryStreamFlow.update emissions while
 * streaming a compact summary. Mirrors STREAM_UI_FLUSH_INTERVAL_MS in
 * GenerationHandler — 33ms ≈ one frame at 30Hz; one accumulated string copy
 * per frame is enough to feel "live" without saturating the StateFlow CAS
 * loop and ChatList recomposition.
 */
private const val STREAM_FLUSH_INTERVAL_MS = 33L

/**
 * Thrown when automatic context compaction fails during prepareContext.
 *
 * Generation cannot proceed without compaction at this point (context is over
 * the model's window). ChatService catches this specifically and surfaces a
 * targeted error to the UI ("上下文已满，压缩失败，请配置任务模型") rather than
 * the generic "Generation failed: <stack>" that earlier swallowed this case
 * into a 5-second toast users routinely missed.
 *
 * `phase` tells the UI handler which compaction path failed:
 *  - "auto_force": ratio crossed forceRatio (0.85) and compactConversation
 *    returned status=failed
 *  - "auto_fit_model_window": post-prepare estimate still over forceRatio of
 *    the model's contextWindow, last-ditch compaction failed too
 *  - "manual": user-triggered compact tool returned failed
 *
 * `compactionReason` is the inner error message from CompactResult.error.
 */
class ContextCompactionFailedException(
    val phase: String,
    val compactionReason: String,
) : RuntimeException("Context compression failed [$phase]: $compactionReason")

class ConversationContextEngine(
    private val providerManager: ProviderManager,
    private val json: Json,
    private val contextRepository: ConversationContextRepository,
    private val appScope: AppScope,
    private val capabilitySnapshotBuilder: AgentCapabilitySnapshotBuilder,
    private val promptConfigRepository: AgentPromptConfigRepository,
) {
    private val compactMutex = Mutex()

    // 2026-05-15: exposes "which conversations are currently compacting" so the
    // UI can render a Codex-style "———正在压缩上下文———" timeline divider while
    // compactConversation runs. Without this signal the user had
    // zero feedback that compaction was happening — a 30s+ silent stall before
    // the next AI reply, indistinguishable from a network hang. Set membership
    // is keyed on conversationId (Uuid as String to align with the
    // ConversationCompact.conversationId field stored on disk).
    private val _compactingConversations = MutableStateFlow<Set<String>>(emptySet())
    val compactingConversations: StateFlow<Set<String>> = _compactingConversations.asStateFlow()

    private val _activeCompactBoundaries = MutableStateFlow<Map<String, ActiveCompactBoundary>>(emptyMap())
    val activeCompactBoundaries: StateFlow<Map<String, ActiveCompactBoundary>> = _activeCompactBoundaries.asStateFlow()

    fun isCompacting(conversationId: Uuid): Boolean =
        conversationId.toString() in _compactingConversations.value

    // 2026-05-15 (1.9.6): live-streaming summary text per conversation. Updated
    // on each delta chunk while compactInternal streams the summary; cleared in
    // the finally block. UI (ContextCompactInProgressMarker) reads this and
    // renders the trailing 120 chars under the shimmer divider so the user
    // can see the summary being generated in real time. Once compaction
    // finishes the stream entry is cleared and the permanent completed marker
    // reads the FINAL summary preview from ConversationCompact.summary.
    private val _summaryStreamFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val summaryStreamFlow: StateFlow<Map<String, String>> = _summaryStreamFlow.asStateFlow()

    private val _compactLifecycleStates = MutableStateFlow<Map<String, CompactLifecycleState>>(emptyMap())
    val compactLifecycleStates: StateFlow<Map<String, CompactLifecycleState>> =
        _compactLifecycleStates.asStateFlow()

    suspend fun prepareContext(
        conversation: Conversation?,
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        tools: List<Tool>,
        contextMessageSize: Int,
        promptOverheadTokens: Int = 0,
    ): PreparedContext {
        val policy = settings.agentRuntime.contextCompaction.toCompactPolicy()
        val editResult = if (policy.enabled) {
            PreparedContextEditor.edit(
                messages = messages,
                keepRecentMessages = (policy.keepRecentTurns * 2).coerceAtLeast(4),
            )
        } else {
            val estimate = ConversationContextPlanner.estimateTokens(messages)
            PreparedContextEditResult(
                messages = messages,
                trace = ContextPreparationTrace(
                    originalTokenEstimate = estimate,
                    finalTokenEstimate = estimate,
                    steps = emptyList(),
                )
            )
        }
        val effectiveMessages = editResult.messages
        val effectiveConversation = conversation?.withEffectiveMessages(effectiveMessages)
        if (conversation == null || !policy.enabled) {
            val limited = effectiveMessages.limitContext(contextMessageSize)
            val estimate = ConversationContextPlanner.estimateTokens(limited)
            return PreparedContext(
                messages = limited,
                tokenEstimate = estimate,
                compressionApplied = false,
                summaryIds = emptyList(),
                trace = editResult.trace.copy(finalTokenEstimate = estimate),
            )
        }

        val compacts = contextRepository.getCompacts(conversation.id)
        val toolPromptEstimate = tools.sumOf { it.name.length + it.description.length } / 4
        val overheadEstimate = toolPromptEstimate + promptOverheadTokens.coerceAtLeast(0)
        val plan = ConversationContextPlanner.planCompaction(
            nodes = effectiveConversation?.messageNodes ?: conversation.messageNodes,
            activeCompacts = compacts,
            policy = policy,
            modelContextWindowTokens = model.contextWindowTokens,
            extraTokenEstimate = overheadEstimate,
        )
        val shouldForce = plan.reason == "force_threshold"
        if (plan.shouldCompact && !policy.notifyOnly) {
            if (shouldForce) {
                val result = compactConversation(
                    conversation = conversation,
                    settings = settings,
                    policy = policy,
                    model = model,
                    reason = "auto_force",
                )
                if (result.status == "failed") {
                    // Throw typed exception instead of generic kotlin.error(): lets
                    // ChatService.onFailure render a user-actionable message (point to
                    // 任务模型 setting) rather than a generic "Generation failed" toast.
                    throw ContextCompactionFailedException(
                        phase = "auto_force",
                        compactionReason = result.error ?: result.status,
                    )
                }
            } else {
                appScope.launch(Dispatchers.IO) {
                    compactConversation(
                        conversation = conversation,
                        settings = settings,
                        policy = policy,
                        model = model,
                        reason = "auto_precompact",
                    )
                }
            }
        }

        var latestCompacts = contextRepository.getCompacts(conversation.id)
        val traceSteps = editResult.trace.steps.toMutableList()
        val beforeCompactEstimate = ConversationContextPlanner.estimateTokens(effectiveMessages) + overheadEstimate
        var preparedMessages = prepareMessagesWithCompacts(
            messages = effectiveMessages,
            activeCompacts = latestCompacts,
            policy = policy,
            contextMessageSize = contextMessageSize,
            tools = tools,
        )
        val afterCompactEstimate = ConversationContextPlanner.estimateTokens(preparedMessages) + overheadEstimate
        if (latestCompacts.any { it.status == "completed" }) {
            traceSteps += ContextPreparationStepTrace(
                stage = "compact",
                reason = "completed compact summaries replaced covered source messages",
                beforeTokens = beforeCompactEstimate,
                afterTokens = afterCompactEstimate,
                savedTokens = (beforeCompactEstimate - afterCompactEstimate).coerceAtLeast(0),
                changedMessages = latestCompacts.count { it.status == "completed" },
            )
        }
        val contextWindow = ConversationContextPlanner.estimateContextWindow(model.contextWindowTokens)
        val softTotalBudget = (contextWindow * policy.forceRatio).toInt().coerceAtLeast(4_000)
        val targetMessageBudget = (softTotalBudget - overheadEstimate)
            .coerceAtLeast(1_000)
        var estimate = ConversationContextPlanner.estimateTokens(preparedMessages) + overheadEstimate
        if (
            policy.enabled &&
            !policy.notifyOnly &&
            estimate > (contextWindow * policy.forceRatio).toInt()
        ) {
            val fitPolicy = policy.copy(keepRecentTurns = (policy.keepRecentTurns / 2).coerceAtLeast(2))
            val result = compactConversation(
                conversation = conversation,
                settings = settings,
                policy = fitPolicy,
                model = model,
                reason = "auto_fit_model_window",
                force = true,
            )
            if (result.status == "failed") {
                throw ContextCompactionFailedException(
                    phase = "auto_fit_model_window",
                    compactionReason = result.error ?: result.status,
                )
            }
            latestCompacts = contextRepository.getCompacts(conversation.id)
            val beforeFitCompactEstimate = ConversationContextPlanner.estimateTokens(preparedMessages) + overheadEstimate
            preparedMessages = prepareMessagesWithCompacts(
                messages = effectiveMessages,
                activeCompacts = latestCompacts,
                policy = fitPolicy,
                contextMessageSize = contextMessageSize,
                tools = tools,
            )
            estimate = ConversationContextPlanner.estimateTokens(preparedMessages) + overheadEstimate
            traceSteps += ContextPreparationStepTrace(
                stage = "compact",
                reason = "forced compaction to fit model window",
                beforeTokens = beforeFitCompactEstimate,
                afterTokens = estimate,
                savedTokens = (beforeFitCompactEstimate - estimate).coerceAtLeast(0),
                changedMessages = latestCompacts.count { it.status == "completed" },
            )
        }
        if (estimate > (contextWindow * policy.forceRatio).toInt()) {
            val beforeFitEstimate = estimate
            preparedMessages = ConversationContextPlanner.fitMessagesToTokenBudget(
                messages = preparedMessages,
                maxTokens = targetMessageBudget,
            )
            estimate = ConversationContextPlanner.estimateTokens(preparedMessages) + overheadEstimate
            traceSteps += ContextPreparationStepTrace(
                stage = "fit",
                reason = "prepared context still exceeded force ratio after editing/compaction",
                beforeTokens = beforeFitEstimate,
                afterTokens = estimate,
                savedTokens = (beforeFitEstimate - estimate).coerceAtLeast(0),
                changedMessages = 0,
            )
        }
        return PreparedContext(
            messages = preparedMessages,
            tokenEstimate = estimate,
            compressionApplied = latestCompacts.any { it.status == "completed" },
            summaryIds = latestCompacts.filter { it.status == "completed" }.map { it.id },
            trace = ContextPreparationTrace(
                originalTokenEstimate = editResult.trace.originalTokenEstimate,
                finalTokenEstimate = estimate,
                steps = traceSteps,
            ),
        )
    }

    private fun prepareMessagesWithCompacts(
        messages: List<UIMessage>,
        activeCompacts: List<ConversationCompact>,
        policy: CompactPolicy,
        contextMessageSize: Int,
        tools: List<Tool>,
    ): List<UIMessage> {
        val compactedMessages = ConversationContextPlanner.prepareMessages(
            messages = messages,
            activeCompacts = activeCompacts,
            policy = policy,
            contextMessageSize = contextMessageSize,
        )
        val hasCompletedCompact = activeCompacts.any { it.status == "completed" }
        return if (hasCompletedCompact) {
            compactedMessages + capabilitySnapshotBuilder.build(tools)
        } else {
            compactedMessages
        }
    }

    private fun setCompactLifecycle(conversationKey: String, state: CompactLifecycleState) {
        _compactLifecycleStates.update { map -> map + (conversationKey to state) }
    }

    private fun updateCompactLifecycle(
        conversationKey: String,
        transform: (CompactLifecycleState) -> CompactLifecycleState,
    ) {
        _compactLifecycleStates.update { map ->
            map + (conversationKey to transform(map[conversationKey] ?: CompactLifecycleState.idle()))
        }
    }

    private fun planCompactionForRequest(
        nodes: List<MessageNode>,
        activeCompacts: List<ConversationCompact>,
        policy: CompactPolicy,
        modelContextWindowTokens: Int?,
        force: Boolean,
    ): CompactPlan {
        if (!force) {
            return ConversationContextPlanner.planCompaction(
                nodes = nodes,
                activeCompacts = activeCompacts,
                policy = policy,
                modelContextWindowTokens = modelContextWindowTokens,
            )
        }

        return ConversationContextPlanner.planForceCompaction(
            nodes = nodes,
            activeCompacts = activeCompacts,
            policy = policy.copy(enabled = true),
            modelContextWindowTokens = modelContextWindowTokens,
        )
    }

    suspend fun compactConversation(
        conversation: Conversation,
        settings: Settings,
        policy: CompactPolicy,
        model: Model? = null,
        reason: String = "manual_compact",
        additionalPrompt: String = "",
        force: Boolean = false,
    ): CompactResult = withContext(Dispatchers.IO) {
        // Mark this conversation as actively compacting BEFORE the mutex
        // acquire — if another caller already holds the mutex we want the
        // UI to start showing the shimmer the moment a compact is queued,
        // not only when it actually starts running. Cleared in finally so
        // even mutex-acquisition failures (theoretical) reset the flag.
        val conversationKey = conversation.id.toString()
        _compactingConversations.update { it + conversationKey }
        setCompactLifecycle(
            conversationKey,
            CompactLifecycleState(
                status = CompactLifecycleStatus.PLANNING,
                reason = reason,
                updatedAt = System.currentTimeMillis(),
            )
        )
        try {
            compactMutex.withLock {
                compactInternal(conversation, settings, policy, model, reason, additionalPrompt, force)
            }
        } finally {
            _compactingConversations.update { it - conversationKey }
            _activeCompactBoundaries.update { it - conversationKey }
            updateCompactLifecycle(conversationKey) { state ->
                if (state.isActive) {
                    state.copy(
                        status = CompactLifecycleStatus.FAILED,
                        error = "cancelled",
                        updatedAt = System.currentTimeMillis(),
                    )
                } else {
                    state
                }
            }
            // Clear any leftover streaming text — UI swaps to the completed
            // "上下文已压缩" marker which reads from ConversationCompact.summary
            // (final, persisted). Leaving a partial in _summaryStreamFlow
            // would either show stale streaming under the next compact or
            // ghost under the wrong conversation.
            _summaryStreamFlow.update { it - conversationKey }
        }
    }

    private suspend fun compactInternal(
        conversation: Conversation,
        settings: Settings,
        policy: CompactPolicy,
        model: Model?,
        reason: String,
        additionalPrompt: String,
        force: Boolean,
    ): CompactResult {
        val conversationKey = conversation.id.toString()
        return runCatching {
                val compressionModel = model
                    ?: settings.resolveTaskChatModel(settings.compressModelId)
                    ?: error("No model available for compression")
                val provider = compressionModel.findProvider(settings.providers)
                    ?: error("Provider not found")
                val providerHandler = providerManager.getProviderByType(provider)
                val activeCompacts = contextRepository.getCompacts(conversation.id)
                val plan = planCompactionForRequest(
                    nodes = conversation.messageNodes,
                    activeCompacts = activeCompacts,
                    policy = policy.copy(enabled = true),
                    modelContextWindowTokens = compressionModel.contextWindowTokens,
                    force = force,
                )
                if (!plan.shouldCompact) {
                    contextRepository.insertEvent(conversation.id, reason, null, "Skipped: ${plan.reason}")
                    setCompactLifecycle(
                        conversationKey,
                        CompactLifecycleState(
                            status = CompactLifecycleStatus.SKIPPED,
                            reason = reason,
                            error = plan.reason,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    return@runCatching CompactResult(
                        status = "skipped",
                        estimatedTokensBefore = plan.estimatedTokens,
                        estimatedTokensAfter = plan.estimatedTokens,
                        error = plan.reason,
                    )
                }

                _activeCompactBoundaries.update { map ->
                    map + (conversationKey to ActiveCompactBoundary(
                        sourceStartIndex = plan.sourceStartIndex,
                        sourceEndIndex = plan.sourceEndIndex,
                        sourceMessageIds = plan.sourceMessageIds,
                    ))
                }
                setCompactLifecycle(
                    conversationKey,
                    CompactLifecycleState(
                        status = CompactLifecycleStatus.COMPACTING,
                        reason = reason,
                        sourceStartIndex = plan.sourceStartIndex,
                        sourceEndIndex = plan.sourceEndIndex,
                        sourceMessageIds = plan.sourceMessageIds,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                val nodes = conversation.messageNodes.subList(plan.sourceStartIndex, plan.sourceEndIndex + 1)
                val contentToCompress = ConversationContextPlanner.buildCompressionInput(nodes.map { it.currentMessage })
                val existingMessageIds = conversation.currentMessages.map { it.id.toString() }.toSet()
                val previousCompacts = CompactSummaryPayloads.selectCompactsForInjection(
                    activeCompacts = activeCompacts,
                    existingMessageIds = existingMessageIds,
                )
                val coveredCompactIds = previousCompacts.map { it.id }
                val previousCompactContext = previousCompacts.joinToString("\n\n") { compact ->
                    CompactSummaryPayloads.injectionText(compact)
                }
                val payloadCreatedAt = System.currentTimeMillis()
                val handoffPrompt = promptConfigRepository.readContextCompactionPrompt()
                val prompt = buildCompressionPrompt(
                    basePrompt = settings.compressPrompt,
                    content = contentToCompress,
                    targetTokens = policy.maxSummaryTokens,
                    additionalPrompt = additionalPrompt,
                    sourceMessageIds = plan.sourceMessageIds,
                    previousCompacts = previousCompacts,
                    coveredCompactIds = coveredCompactIds,
                    payloadCreatedAt = payloadCreatedAt,
                    handoffPrompt = handoffPrompt,
                )
                val summary = streamCompactSummary(
                    providerHandler = providerHandler,
                    provider = provider,
                    compressionModel = compressionModel,
                    conversationKey = conversationKey,
                    prompt = prompt,
                )
                var normalizedSummary = CompactSummaryNormalizer.normalizeOrNull(
                    json = json,
                    summary = summary,
                    sourceMessageIds = plan.sourceMessageIds,
                    coveredCompactIds = coveredCompactIds,
                    createdAt = payloadCreatedAt,
                )
                if (normalizedSummary == null || !CompactSummaryPayloads.isHighQualityPayload(normalizedSummary)) {
                    val retryPrompt = buildCompressionPrompt(
                        basePrompt = settings.compressPrompt,
                        content = contentToCompress,
                        targetTokens = policy.maxSummaryTokens,
                        additionalPrompt = listOf(
                            additionalPrompt,
                            "Retry because the previous compaction did not satisfy the schema or the timeline summary was too short. Return valid JSON only. `timeline_summary` must contain 4-5 complete sentences, and `handoff_markdown` must contain the required sections.",
                        ).filter { it.isNotBlank() }.joinToString("\n\n"),
                        sourceMessageIds = plan.sourceMessageIds,
                        previousCompacts = previousCompacts,
                        coveredCompactIds = coveredCompactIds,
                        payloadCreatedAt = payloadCreatedAt,
                        handoffPrompt = handoffPrompt,
                    )
                    val retrySummary = streamCompactSummary(
                        providerHandler = providerHandler,
                        provider = provider,
                        compressionModel = compressionModel,
                        conversationKey = conversationKey,
                        prompt = retryPrompt,
                    )
                    normalizedSummary = CompactSummaryNormalizer.normalizeOrNull(
                        json = json,
                        summary = retrySummary,
                        sourceMessageIds = plan.sourceMessageIds,
                        coveredCompactIds = coveredCompactIds,
                        createdAt = payloadCreatedAt,
                    )
                    if (normalizedSummary == null || !CompactSummaryPayloads.isHighQualityPayload(normalizedSummary)) {
                        normalizedSummary = CompactSummaryNormalizer.fallbackPlainTextSummaryJson(
                            summary = retrySummary.ifBlank { summary },
                            sourceMessageIds = plan.sourceMessageIds,
                            coveredCompactIds = coveredCompactIds,
                            createdAt = payloadCreatedAt,
                            sourceContent = contentToCompress,
                            carriedHandoffMarkdown = previousCompactContext,
                        )
                    }
                }
                val compactId = Uuid.random().toString()
                val now = System.currentTimeMillis()
                val compact = ConversationCompact(
                    id = compactId,
                    conversationId = conversation.id.toString(),
                    summary = normalizedSummary,
                    level = 1,
                    sourceStartIndex = plan.sourceStartIndex,
                    sourceEndIndex = plan.sourceEndIndex,
                    sourceMessageIds = plan.sourceMessageIds,
                    tokenEstimate = ConversationContextPlanner.estimateTokens(
                        listOf(
                            UIMessage.system(
                                CompactSummaryPayloads.injectionText(
                                    id = compactId,
                                    summary = normalizedSummary,
                                    sourceMessageIds = plan.sourceMessageIds,
                                )
                            )
                        )
                    ),
                    createdAt = payloadCreatedAt,
                    updatedAt = now,
                    status = "completed",
                )
                contextRepository.insertCompact(compact)
                contextRepository.insertEvent(conversation.id, reason, compact.id, "Compacted ${plan.sourceMessageCount} messages")
                setCompactLifecycle(
                    conversationKey,
                    CompactLifecycleState(
                        status = CompactLifecycleStatus.COMPLETED,
                        reason = reason,
                        sourceStartIndex = plan.sourceStartIndex,
                        sourceEndIndex = plan.sourceEndIndex,
                        sourceMessageIds = plan.sourceMessageIds,
                        streamingSummary = CompactSummaryPayloads.timelineSummary(normalizedSummary).orEmpty(),
                        completedCompactId = compact.id,
                        anchorAt = payloadCreatedAt,
                        updatedAt = now,
                    )
                )
                CompactResult(
                    status = "completed",
                    summaryId = compact.id,
                    sourceMessageCount = plan.sourceMessageCount,
                    estimatedTokensBefore = plan.estimatedTokens,
                    estimatedTokensAfter = compact.tokenEstimate,
                )
            }.getOrElse { error ->
                // 2026-05-15 (1.9.7): rethrow CancellationException explicitly.
                // runCatching catches it like any other Throwable, which would
                // turn a legitimate user-initiated cancel (switch conversation,
                // stop generation, app backgrounded) into a "对话压缩失败" toast.
                // Kotlin coroutine best practice — cancellation is structured,
                // not a failure mode.
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(TAG, "compactConversation failed", error)
                contextRepository.insertEvent(conversation.id, reason, null, error.message.orEmpty())
                updateCompactLifecycle(conversationKey) { state ->
                    state.copy(
                        status = CompactLifecycleStatus.FAILED,
                        reason = reason,
                        error = error.message ?: error::class.java.simpleName,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                CompactResult(status = "failed", error = error.message ?: error::class.java.simpleName)
            }
    }

    suspend fun invalidateCompacts(conversationId: Uuid, reason: String) {
        contextRepository.invalidateCompacts(conversationId, reason)
    }

    suspend fun copyValidCompactsToConversation(
        sourceConversationId: Uuid,
        targetConversation: Conversation,
        reason: String = "conversation_forked_compacts_copied",
    ): Int {
        return contextRepository.copyValidCompactsToConversation(
            sourceConversationId = sourceConversationId,
            targetConversation = targetConversation,
            reason = reason,
        )
    }

    suspend fun search(conversationId: Uuid, query: String, limit: Int): List<ContextSearchResult> {
        return contextRepository.search(conversationId, query, limit)
    }

    suspend fun expand(conversationId: Uuid, sourceId: String, radius: Int): List<UIMessage> {
        return contextRepository.expand(conversationId, sourceId, radius)
    }

    suspend fun status(conversation: Conversation, settings: Settings, model: Model?): JsonObject {
        val policy = settings.agentRuntime.contextCompaction.toCompactPolicy()
        val compacts = contextRepository.getCompacts(conversation.id)
        val plan = ConversationContextPlanner.planCompaction(
            nodes = conversation.messageNodes,
            activeCompacts = compacts,
            policy = policy,
            modelContextWindowTokens = model?.contextWindowTokens,
        )
        // 2026-05-18: `plan.estimatedTokens` is the RAW token count of all
        // messageNodes — it ignores active compact substitution that the send
        // pipeline (prepareMessagesWithCompacts) actually applies. The UI ring
        // (ContextFootprintEstimator.estimateConversationInputTokens) DOES
        // substitute, so before this change the model reported e.g. "335K /
        // 1M = 33.5% pressure" while the ring + real API call were at 150K.
        // Now we expose `effective_tokens` (post-substitution) as the
        // headline number alongside `raw_tokens` (pre-substitution) for
        // debug, and base `pressure_ratio` on the effective count so the
        // model's reasoning about "should I compact" matches reality.
        val effectiveTokens = ContextFootprintEstimator.estimateConversationInputTokens(
            conversation = conversation,
            activeCompacts = compacts,
        )
        val lifecycle = _compactLifecycleStates.value[conversation.id.toString()]
        val rawPressureRatio = plan.estimatedTokens.toFloat() / plan.contextWindowTokens.toFloat()
        val effectivePressureRatio = effectiveTokens.toFloat() / plan.contextWindowTokens.toFloat()
        return buildJsonObject {
            put("enabled", policy.enabled)
            put("notify_only", policy.notifyOnly)
            put("estimated_tokens", effectiveTokens)
            put("raw_tokens", plan.estimatedTokens)
            put("context_window_tokens", plan.contextWindowTokens)
            put("pressure_ratio", effectivePressureRatio)
            put("raw_pressure_ratio", rawPressureRatio)
            put("summary_count", compacts.count { it.status == "completed" })
            put("latest_status", compacts.lastOrNull()?.status ?: "none")
            put("compact_lifecycle_status", lifecycle?.status?.name?.lowercase() ?: "idle")
            lifecycle?.completedCompactId?.let { put("latest_lifecycle_compact_id", it) }
            put("next_action", effectiveContextNextAction(policy, effectiveTokens, plan.contextWindowTokens))
            put("raw_next_action", plan.reason)
        }
    }

    private suspend fun <T : ProviderSetting> streamCompactSummary(
        providerHandler: Provider<T>,
        provider: T,
        compressionModel: Model,
        conversationKey: String,
        prompt: String,
    ): String {
        // 2026-05-15 (1.9.6): switched from generateText to streamText so the
        // UI can render compaction progress live. Flush at <=30fps to avoid a
        // large StateFlow string copy and ChatList recomposition per token.
        val accumulated = StringBuilder()
        var lastFlushAt = 0L
        providerHandler.streamText(
            providerSetting = provider,
            messages = listOf(UIMessage.user(prompt)),
            params = TextGenerationParams(model = compressionModel),
        ).collect { chunk ->
            val deltaParts = chunk.choices.firstOrNull()?.let { choice ->
                choice.delta?.parts ?: choice.message?.parts
            }.orEmpty()
            val deltaText = deltaParts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }
            if (deltaText.isNotEmpty()) {
                accumulated.append(deltaText)
                val now = System.currentTimeMillis()
                if (lastFlushAt == 0L || now - lastFlushAt >= STREAM_FLUSH_INTERVAL_MS) {
                    val text = accumulated.toString()
                    _summaryStreamFlow.update { map ->
                        map + (conversationKey to text)
                    }
                    updateCompactLifecycle(conversationKey) { state ->
                        state.copy(
                            streamingSummary = text,
                            updatedAt = now,
                        )
                    }
                    lastFlushAt = now
                }
            }
        }
        val finalStreamText = accumulated.toString().trim()
        _summaryStreamFlow.update { map ->
            map + (conversationKey to finalStreamText)
        }
        updateCompactLifecycle(conversationKey) { state ->
            state.copy(
                streamingSummary = finalStreamText,
                updatedAt = System.currentTimeMillis(),
            )
        }
        if (finalStreamText.isBlank()) {
            Log.w(TAG, "compact stream produced no Text parts")
        }
        return finalStreamText
    }

    private fun buildCompressionPrompt(
        basePrompt: String,
        content: String,
        targetTokens: Int,
        additionalPrompt: String,
        sourceMessageIds: List<String>,
        previousCompacts: List<ConversationCompact>,
        coveredCompactIds: List<String>,
        payloadCreatedAt: Long,
        handoffPrompt: String,
    ): String {
        val previousCompactContext = previousCompacts.joinToString("\n\n") { compact ->
            CompactSummaryPayloads.injectionText(compact)
        }
        val structuredInstructions = """
            Return valid JSON only. Required schema:
            {
              "schema_version": 2,
              "timeline_summary": "4-5 complete human-readable sentences in the user's language for the chat timeline.",
              "handoff_markdown": "Dense Markdown continuation handoff with sections: Goal, Constraints, Progress, Decisions, Current State, Next Steps, Critical Context, Relevant Files.",
              "covered_compact_ids": [${coveredCompactIds.joinToString(", ") { "\"$it\"" }}],
              "source_message_ids": [${sourceMessageIds.joinToString(", ") { "\"$it\"" }}],
              "created_at": $payloadCreatedAt
            }
            `covered_compact_ids`, `source_message_ids`, and `created_at` must exactly match the values above.
            Preserve concrete names, files, commands, errors, user preferences, rejected approaches, tool outcomes, and unresolved decisions.
            The timeline summary is for the human timeline; the handoff Markdown is what the next model will receive.

            Agent-editable handoff instructions:
            $handoffPrompt

            Previous compact handoffs to carry forward:
            ${previousCompactContext.ifBlank { "None." }}
        """.trimIndent()
        return basePrompt.applyPlaceholders(
            "content" to content,
            "target_tokens" to targetTokens.toString(),
            "additional_context" to listOf(structuredInstructions, additionalPrompt)
                .filter { it.isNotBlank() }
                .joinToString("\n\n"),
            "locale" to Locale.getDefault().displayName,
        )
    }

    private fun Conversation.withEffectiveMessages(effectiveMessages: List<UIMessage>): Conversation {
        val byId = effectiveMessages.associateBy { it.id }
        return copy(
            messageNodes = messageNodes.map { node ->
                val edited = byId[node.currentMessage.id] ?: return@map node
                if (edited == node.currentMessage) {
                    node
                } else {
                    node.copy(
                        messages = node.messages.map { message ->
                            if (message.id == edited.id) edited else message
                        }
                    )
                }
            }
        )
    }
}

internal fun effectiveContextNextAction(
    policy: CompactPolicy,
    effectiveTokens: Int,
    contextWindowTokens: Int,
): String {
    if (!policy.enabled) return "disabled"
    val ratio = effectiveTokens.toFloat() / contextWindowTokens.coerceAtLeast(1).toFloat()
    return when {
        ratio >= policy.forceRatio -> "force_threshold"
        ratio >= policy.precompactRatio -> "precompact_threshold"
        else -> "below_threshold"
    }
}
