package me.rerere.rikkahub.data.context

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.resolveTaskChatModel
import me.rerere.rikkahub.data.datastore.toCompactPolicy
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ConversationContextEngine"

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
) {
    private val compactMutex = Mutex()

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
        if (conversation == null || !policy.enabled) {
            val limited = messages.limitContext(contextMessageSize)
            return PreparedContext(limited, ConversationContextPlanner.estimateTokens(limited), false, emptyList())
        }

        val compacts = contextRepository.getCompacts(conversation.id)
        val toolPromptEstimate = tools.sumOf { it.name.length + it.description.length } / 4
        val overheadEstimate = toolPromptEstimate + promptOverheadTokens.coerceAtLeast(0)
        val plan = ConversationContextPlanner.planCompaction(
            nodes = conversation.messageNodes,
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
        var preparedMessages = prepareMessagesWithCompacts(
            messages = messages,
            activeCompacts = latestCompacts,
            policy = policy,
            contextMessageSize = contextMessageSize,
            tools = tools,
        )
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
            preparedMessages = prepareMessagesWithCompacts(
                messages = messages,
                activeCompacts = latestCompacts,
                policy = fitPolicy,
                contextMessageSize = contextMessageSize,
                tools = tools,
            )
            estimate = ConversationContextPlanner.estimateTokens(preparedMessages) + overheadEstimate
        }
        if (estimate > (contextWindow * policy.forceRatio).toInt()) {
            preparedMessages = ConversationContextPlanner.fitMessagesToTokenBudget(
                messages = preparedMessages,
                maxTokens = targetMessageBudget,
            )
            estimate = ConversationContextPlanner.estimateTokens(preparedMessages) + overheadEstimate
        }
        return PreparedContext(
            messages = preparedMessages,
            tokenEstimate = estimate,
            compressionApplied = latestCompacts.any { it.status == "completed" },
            summaryIds = latestCompacts.filter { it.status == "completed" }.map { it.id },
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

    suspend fun compactConversation(
        conversation: Conversation,
        settings: Settings,
        policy: CompactPolicy,
        model: Model? = null,
        reason: String = "manual_compact",
        additionalPrompt: String = "",
        force: Boolean = false,
    ): CompactResult = withContext(Dispatchers.IO) {
        compactMutex.withLock {
            runCatching {
                val compressionModel = model
                    ?: settings.resolveTaskChatModel(settings.compressModelId)
                    ?: error("No model available for compression")
                val provider = compressionModel.findProvider(settings.providers)
                    ?: error("Provider not found")
                val providerHandler = providerManager.getProviderByType(provider)
                val activeCompacts = contextRepository.getCompacts(conversation.id)
                val planPolicy = if (force) {
                    policy.copy(precompactRatio = 0f, forceRatio = Float.MAX_VALUE)
                } else {
                    policy
                }
                val plan = ConversationContextPlanner.planCompaction(
                    nodes = conversation.messageNodes,
                    activeCompacts = activeCompacts,
                    policy = planPolicy.copy(enabled = true),
                    modelContextWindowTokens = compressionModel.contextWindowTokens,
                )
                if (!plan.shouldCompact) {
                    contextRepository.insertEvent(conversation.id, reason, null, "Skipped: ${plan.reason}")
                    return@runCatching CompactResult(
                        status = "skipped",
                        estimatedTokensBefore = plan.estimatedTokens,
                        estimatedTokensAfter = plan.estimatedTokens,
                        error = plan.reason,
                    )
                }

                val nodes = conversation.messageNodes.subList(plan.sourceStartIndex, plan.sourceEndIndex + 1)
                val contentToCompress = ConversationContextPlanner.buildCompressionInput(nodes.map { it.currentMessage })
                val prompt = buildCompressionPrompt(
                    basePrompt = settings.compressPrompt,
                    content = contentToCompress,
                    targetTokens = policy.maxSummaryTokens,
                    additionalPrompt = additionalPrompt,
                    sourceMessageIds = plan.sourceMessageIds,
                )
                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(prompt)),
                    params = TextGenerationParams(model = compressionModel),
                )
                val summary = result.choices.firstOrNull()?.message?.toText()?.trim()
                    ?: error("Failed to generate compact summary")
                val normalizedSummary = normalizeSummaryJson(summary)
                val now = System.currentTimeMillis()
                val compact = ConversationCompact(
                    id = Uuid.random().toString(),
                    conversationId = conversation.id.toString(),
                    summary = normalizedSummary,
                    level = 1,
                    sourceStartIndex = plan.sourceStartIndex,
                    sourceEndIndex = plan.sourceEndIndex,
                    sourceMessageIds = plan.sourceMessageIds,
                    tokenEstimate = ConversationContextPlanner.estimateTokens(listOf(UIMessage.system(normalizedSummary))),
                    createdAt = now,
                    updatedAt = now,
                    status = "completed",
                )
                contextRepository.insertCompact(compact)
                contextRepository.insertEvent(conversation.id, reason, compact.id, "Compacted ${plan.sourceMessageCount} messages")
                CompactResult(
                    status = "completed",
                    summaryId = compact.id,
                    sourceMessageCount = plan.sourceMessageCount,
                    estimatedTokensBefore = plan.estimatedTokens,
                    estimatedTokensAfter = compact.tokenEstimate,
                )
            }.getOrElse { error ->
                Log.e(TAG, "compactConversation failed", error)
                contextRepository.insertEvent(conversation.id, reason, null, error.message.orEmpty())
                CompactResult(status = "failed", error = error.message ?: error::class.java.simpleName)
            }
        }
    }

    suspend fun invalidateCompacts(conversationId: Uuid, reason: String) {
        contextRepository.invalidateCompacts(conversationId, reason)
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
        return buildJsonObject {
            put("enabled", policy.enabled)
            put("notify_only", policy.notifyOnly)
            put("estimated_tokens", plan.estimatedTokens)
            put("context_window_tokens", plan.contextWindowTokens)
            put("pressure_ratio", plan.estimatedTokens.toFloat() / plan.contextWindowTokens.toFloat())
            put("summary_count", compacts.count { it.status == "completed" })
            put("latest_status", compacts.lastOrNull()?.status ?: "none")
            put("next_action", plan.reason)
        }
    }

    private fun buildCompressionPrompt(
        basePrompt: String,
        content: String,
        targetTokens: Int,
        additionalPrompt: String,
        sourceMessageIds: List<String>,
    ): String {
        val structuredInstructions = """
            Return only valid JSON with these keys:
            goals, facts, decisions, open_tasks, failed_attempts, tool_results, entities, timeline, source_message_ids.
            Preserve concrete names, files, commands, errors, user preferences, and unresolved decisions.
            source_message_ids must exactly list: ${sourceMessageIds.joinToString(", ")}.
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

    private fun normalizeSummaryJson(summary: String): String {
        val candidate = summary.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { cleaned ->
                val start = cleaned.indexOf('{')
                val end = cleaned.lastIndexOf('}')
                if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
            }
        val jsonObject = json.parseToJsonElement(candidate).jsonObject
        val required = listOf(
            "goals",
            "facts",
            "decisions",
            "open_tasks",
            "failed_attempts",
            "tool_results",
            "entities",
            "timeline",
            "source_message_ids",
        )
        val missing = required.filterNot { it in jsonObject }
        require(missing.isEmpty()) {
            "Compact summary JSON missing fields: ${missing.joinToString(", ")}"
        }
        return candidate
    }
}
