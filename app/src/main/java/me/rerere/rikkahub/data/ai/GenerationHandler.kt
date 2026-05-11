package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.MessageStreamAccumulator
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.util.ImageEncodingException
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.generative.GenerativeUiPlanner
import me.rerere.rikkahub.data.ai.generative.GenerativeWidgetParser
import me.rerere.rikkahub.data.agent.runtime.AgentToolDispatcher
import me.rerere.rikkahub.data.agent.runtime.PermissionDecisionResolver
import me.rerere.rikkahub.data.agent.runtime.SpeculativeToolRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.resolveSessionDefaults
import me.rerere.rikkahub.data.context.ConversationContextEngine
import me.rerere.rikkahub.data.context.ConversationContextPlanner
import me.rerere.rikkahub.data.memory.recall.MemoryRecallStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock

private const val TAG = "GenerationHandler"
private const val STREAM_UI_FLUSH_INTERVAL_MS = 50L
private const val GENERATIVE_UI_REASONING_ONLY_FALLBACK_MS = 5_000L
private const val GENERATIVE_UI_REASONING_ONLY_FALLBACK_CHARS = 800
// "Did the model produce real prose?" threshold for skipping the local fallback widget
// after a retry. ~30 chars is "looks like a real sentence in either CN or EN", below
// that we treat the response as effectively empty and let the skeleton widget kick in.
private const val MEANINGFUL_TEXT_MIN_CHARS = 30

private class GenerativeUiReasoningOnlyStreamException : RuntimeException(
    "Generative UI stream emitted only hidden reasoning without visible content"
)

private class GenerativeUiMissingWidgetStreamException : RuntimeException(
    "Generative UI stream completed without a widget"
)

internal fun shouldPauseForToolApproval(
    toolDef: Tool?,
    tool: UIMessagePart.Tool,
    autoApproveTools: Boolean,
    autoApproveHighRiskTools: Boolean = false,
    autoApprovedToolNames: Set<String> = emptySet(),
): Boolean {
    return PermissionDecisionResolver().shouldPauseForApproval(
        toolDef = toolDef,
        tool = tool,
        autoApproveTools = autoApproveTools,
        autoApproveHighRiskTools = autoApproveHighRiskTools,
        autoApprovedToolNames = autoApprovedToolNames,
    )
}

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val memoryRecallStore: MemoryRecallStore,
    private val conversationRepo: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
    private val conversationContextEngine: ConversationContextEngine,
    private val toolDispatcher: AgentToolDispatcher,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        autoApproveTools: Boolean = false,
        autoApproveHighRiskTools: Boolean = false,
        autoApprovedToolNames: Set<String> = emptySet(),
        conversation: Conversation? = null,
        consumeSteerMessages: suspend () -> List<UIMessage> = { emptyList() },
    ): Flow<GenerationChunk> = flow {
        coroutineScope {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val hasResumableTools = messages.lastOrNull()?.getTools()?.any { it.canResumeExecution } == true
            val directWidgetRequested =
                GenerativeUiPlanner.shouldGenerateDirectWidgetWithoutTools(settings.agentRuntime.generativeUi, messages)
            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                addAll(
                    if (directWidgetRequested && !hasResumableTools) {
                        emptyList()
                    } else {
                        tools
                    }
                )
            }
            val speculativeRunner = if (settings.agentRuntime.speculativeToolExecution.enabled && assistant.streamOutput) {
                SpeculativeToolRunner(
                    scope = this,
                    dispatcher = toolDispatcher,
                    maxConcurrentTools = settings.agentRuntime.speculativeToolExecution.maxConcurrentTools,
                )
            } else {
                null
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = toolsInternal,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversation = conversation,
                    speculativeRunner = speculativeRunner,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    val decision = toolDispatcher.resolveDecision(
                        toolDef = toolDef,
                        tool = tool,
                        autoApproveTools = autoApproveTools,
                        autoApproveHighRiskTools = autoApproveHighRiskTools,
                        autoApprovedToolNames = autoApprovedToolNames,
                    )
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        decision.action == me.rerere.rikkahub.data.agent.runtime.PermissionDecisionAction.ASK -> {
                            hasPendingApproval = true
                            tool.copy(
                                approvalState = ToolApprovalState.Pending,
                                metadata = kotlinx.serialization.json.buildJsonObject {
                                    tool.metadata?.forEach { (key, value) -> put(key, value) }
                                    put("permission_trace", decision.trace.toJson())
                                },
                            )
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = toolDispatcher.executeBatch(
                tools = toolsToProcess,
                toolDefinitions = toolsInternal.associateBy { it.name },
                autoApproveTools = autoApproveTools,
                autoApproveHighRiskTools = autoApproveHighRiskTools,
                autoApprovedToolNames = autoApprovedToolNames,
                prefetchedTools = speculativeRunner?.reusableResults(toolsToProcess).orEmpty(),
                retrySetting = settings.agentRuntime.generationRetry,
            )

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )

            val steerMessages = consumeSteerMessages()
            if (steerMessages.isNotEmpty()) {
                messages = messages + steerMessages
                emit(GenerationChunk.Messages(messages))
            }
        }

        }
    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversation: Conversation? = null,
        speculativeRunner: SpeculativeToolRunner? = null,
    ) {
        val sessionDefaults = settings.resolveSessionDefaults(assistant, model)
        val memoryContextPrompt = memoryRecallStore.buildPrompt(settings, messages)
        val system = buildString {
            append(buildAgentSoulPrompt(settings.agentRuntime.agentSoulMarkdown))

            // 如果助手有系统提示，则添加到消息中
            if (assistant.systemPrompt.isNotBlank()) {
                if (isNotBlank()) appendLine()
                append(assistant.systemPrompt)
            }

            if (memoryContextPrompt.isNotBlank()) {
                appendLine()
                append(memoryContextPrompt)
            }
            buildGenerativeUiPrompt(settings.agentRuntime.generativeUi, model).takeIf { it.isNotBlank() }?.let { prompt ->
                appendLine()
                append(prompt)
            }
            GenerativeUiPlanner.buildPrompt(settings.agentRuntime.generativeUi, messages).takeIf { it.isNotBlank() }?.let { prompt ->
                appendLine()
                append(prompt)
            }
            if (settings.agentRuntime.enableRecentChatsReference) {
                appendLine()
                append(buildRecentChatsPrompt(conversationRepo))
            }

            // 工具prompt
            tools.forEach { tool ->
                appendLine()
                append(tool.systemPrompt(model, messages))
            }
        }
        val preparedContext = conversationContextEngine.prepareContext(
            conversation = conversation,
            settings = settings,
            model = model,
            messages = messages,
            tools = tools,
            contextMessageSize = sessionDefaults.contextMessageSize,
            promptOverheadTokens = ConversationContextPlanner.estimateTokens(listOf(UIMessage.system(system))),
        )
        suspend fun prepareInternalMessages(forceImageToText: Boolean = false): List<UIMessage> =
            buildList {
                if (system.isNotBlank()) add(UIMessage.system(prompt = system))
                addAll(preparedContext.messages)
            }.transforms(
                transformers = transformers,
                context = context,
                model = model,
                assistant = assistant,
                settings = settings,
                processingStatus = processingStatus,
                forceImageToText = forceImageToText,
            )

        val internalMessages = prepareInternalMessages()
        val canUseVisionFallback = model.inputModalities.contains(Modality.IMAGE) && internalMessages.hasImageParts()

        val baseMessages: List<UIMessage> = messages
        var messages: List<UIMessage> = baseMessages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = sessionDefaults.maxTokens,
            tools = tools,
            reasoningLevel = sessionDefaults.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        val shouldRequireGenerativeUiWidget =
            GenerativeUiPlanner.needsVisibleStreamingFallback(settings.agentRuntime.generativeUi, messages)
        val shouldGuardGenerativeUiReasoningOnly =
            shouldRequireGenerativeUiWidget
        if (stream) {
            runProviderCallWithRetry(
                retrySetting = settings.agentRuntime.generationRetry,
                processingStatus = processingStatus,
                providerName = provider.name,
                modelName = model.displayName,
                onBeforeRetry = {
                    messages = baseMessages
                    onUpdateMessages(messages)
                },
            ) {
                aiLoggingManager.addLog(
                    AILogging.Generation(
                        params = params,
                        messages = baseMessages,
                        providerSetting = provider,
                        stream = true
                    )
                )
                suspend fun streamWith(
                    providerMessages: List<UIMessage>,
                    streamParams: TextGenerationParams,
                    guardReasoningOnly: Boolean,
                    requireWidget: Boolean,
                ) {
                    val accumulator = MessageStreamAccumulator(baseMessages, model)
                    var lastFlushAt = 0L
                    val streamStartedAt = System.currentTimeMillis()
                    var visibleTextChars = 0
                    var reasoningChars = 0
                    providerImpl.streamText(
                        providerSetting = provider,
                        messages = providerMessages,
                        params = streamParams,
                    ).collect { chunk ->
                        val deltaParts = chunk.choices.getOrNull(0)?.let { choice ->
                            choice.delta?.parts ?: choice.message?.parts
                        }.orEmpty()
                        visibleTextChars += deltaParts
                            .filterIsInstance<UIMessagePart.Text>()
                            .sumOf { it.text.length }
                        reasoningChars += deltaParts
                            .filterIsInstance<UIMessagePart.Reasoning>()
                            .sumOf { it.reasoning.length }
                        accumulator.append(chunk)
                        val now = System.currentTimeMillis()
                        if (lastFlushAt == 0L || now - lastFlushAt >= STREAM_UI_FLUSH_INTERVAL_MS) {
                            messages = accumulator.snapshot()
                            speculativeRunner?.observe(
                                messages.lastOrNull()?.getTools().orEmpty(),
                                tools.associateBy { it.name }
                            )
                            onUpdateMessages(messages)
                            lastFlushAt = now
                        }
                        if (
                            guardReasoningOnly &&
                            visibleTextChars == 0 &&
                            reasoningChars > 0 &&
                            (
                                reasoningChars >= GENERATIVE_UI_REASONING_ONLY_FALLBACK_CHARS ||
                                    now - streamStartedAt >= GENERATIVE_UI_REASONING_ONLY_FALLBACK_MS
                                )
                        ) {
                            throw GenerativeUiReasoningOnlyStreamException()
                        }
                    }
                    messages = accumulator.snapshot()
                    speculativeRunner?.observe(
                        messages.lastOrNull()?.getTools().orEmpty(),
                        tools.associateBy { it.name }
                    )
                    onUpdateMessages(messages)
                    if (requireWidget && !messages.hasVisibleWidgetFence()) {
                        throw GenerativeUiMissingWidgetStreamException()
                    }
                }

                try {
                    streamWith(
                        providerMessages = internalMessages,
                        streamParams = params,
                        guardReasoningOnly = shouldGuardGenerativeUiReasoningOnly,
                        requireWidget = shouldRequireGenerativeUiWidget,
                    )
                } catch (error: Throwable) {
                    if (
                        error is GenerativeUiReasoningOnlyStreamException ||
                        error is GenerativeUiMissingWidgetStreamException
                    ) {
                        messages = baseMessages
                        onUpdateMessages(messages)
                        processingStatus.value = "正在切换为可见输出模式生成可视化..."
                        streamWith(
                            providerMessages = internalMessages.withGenerativeUiVisibleFallbackPrompt(),
                            streamParams = params.copy(reasoningLevel = ReasoningLevel.OFF),
                            guardReasoningOnly = false,
                            requireWidget = false,
                        )
                        // Only force-inject the local fallback widget when the retry
                        // ALSO produced no meaningful visible text. Previously we
                        // injected unconditionally on "no widget fence", which meant a
                        // model that legitimately decided "this question doesn't need a
                        // visualisation, here's a normal answer" had a synthetic
                        // skeleton widget appended to its perfectly good text — visible
                        // as the "widget keeps appearing on every reply" loop the user
                        // reported.
                        if (
                            !messages.hasVisibleWidgetFence() &&
                            !messages.hasMeaningfulVisibleAssistantText()
                        ) {
                            messages = messages.withLocalGenerativeUiFallbackWidget(
                                baseMessages = baseMessages,
                                model = model,
                            )
                            onUpdateMessages(messages)
                        }
                        return@runProviderCallWithRetry
                    }
                    if (!canUseVisionFallback || !shouldFallbackToVisionRecognition(error)) throw error
                    processingStatus.value = "正在改用视觉识别模型读取图片..."
                    streamWith(
                        providerMessages = prepareInternalMessages(forceImageToText = true),
                        streamParams = params,
                        guardReasoningOnly = shouldGuardGenerativeUiReasoningOnly,
                        requireWidget = shouldRequireGenerativeUiWidget,
                    )
                }
            }
        } else {
            runProviderCallWithRetry(
                retrySetting = settings.agentRuntime.generationRetry,
                processingStatus = processingStatus,
                providerName = provider.name,
                modelName = model.displayName,
                onBeforeRetry = {
                    messages = baseMessages
                    onUpdateMessages(messages)
                },
            ) {
                aiLoggingManager.addLog(
                    AILogging.Generation(
                        params = params,
                        messages = baseMessages,
                        providerSetting = provider,
                        stream = false
                    )
                )
                suspend fun generateWith(providerMessages: List<UIMessage>) =
                    providerImpl.generateText(
                        providerSetting = provider,
                        messages = providerMessages,
                        params = params,
                    )

                val chunk = try {
                    generateWith(internalMessages)
                } catch (error: Throwable) {
                    if (!canUseVisionFallback || !shouldFallbackToVisionRecognition(error)) throw error
                    processingStatus.value = "正在改用视觉识别模型读取图片..."
                    generateWith(prepareInternalMessages(forceImageToText = true))
                }
                messages = baseMessages.handleMessageChunk(chunk = chunk, model = model)
                chunk.usage?.let { usage ->
                    messages = messages.mapIndexed { index, message ->
                        if (index == messages.lastIndex) {
                            message.copy(
                                usage = message.usage.merge(usage)
                            )
                        } else {
                            message
                        }
                    }
                }
                onUpdateMessages(messages)
            }
        }
    }

    private suspend fun runProviderCallWithRetry(
        retrySetting: GenerationRetrySetting,
        processingStatus: MutableStateFlow<String?>,
        providerName: String,
        modelName: String,
        onBeforeRetry: suspend () -> Unit,
        block: suspend () -> Unit,
    ) {
        var retryAttempt = 1
        while (true) {
            try {
                currentCoroutineContext().ensureActive()
                block()
                processingStatus.value = null
                return
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                val decision = GenerationFailureClassifier.decide(
                    error = error,
                    attempt = retryAttempt,
                    setting = retrySetting,
                )
                if (!decision.retryable) {
                    processingStatus.value = null
                    throw error
                }
                onBeforeRetry()
                val seconds = (decision.delayMs / 1_000L).coerceAtLeast(1L)
                val status = context.getString(
                    R.string.generation_retry_status,
                    seconds,
                    retryAttempt,
                    retrySetting.maxRetries,
                    decision.reason,
                )
                processingStatus.value = status
                Log.w(
                    TAG,
                    "Provider call failed; retrying $retryAttempt/${retrySetting.maxRetries} " +
                        "after ${decision.delayMs}ms category=${decision.category} provider=$providerName model=$modelName",
                    error,
                )
                delay(decision.delayMs)
                retryAttempt++
            }
        }
    }

    private fun List<UIMessage>.hasImageParts(): Boolean =
        any { message -> message.parts.any { it is UIMessagePart.Image && it.url.isNotBlank() } }

    private fun List<UIMessage>.hasVisibleWidgetFence(): Boolean =
        lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.any { GenerativeWidgetParser.hasRenderableWidget(it.text) } == true

    /**
     * "Did the retry give a real answer?" — joined visible Text parts of the last
     * assistant message, with the widget fence stripped, must contain non-trivial
     * prose. Used to decide whether the local-fallback skeleton widget should still
     * be appended after the second pass.
     */
    private fun List<UIMessage>.hasMeaningfulVisibleAssistantText(): Boolean {
        val text = lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            .orEmpty()
        // Strip any ```show-widget``` fence so the check measures *prose*, not the
        // widget JSON the model may have already produced.
        val withoutWidget = text.replace(
            Regex("```\\s*(?:show-widget|widget|generative-ui)[\\s\\S]*?```"),
            "",
        ).trim()
        return withoutWidget.length >= MEANINGFUL_TEXT_MIN_CHARS
    }

    private fun List<UIMessage>.withGenerativeUiVisibleFallbackPrompt(): List<UIMessage> {
        val instruction = UIMessage.system(
            // NOTE: the inline SVG example below adds ~400 input tokens to every fallback retry.
            // If weak models trigger this path frequently, consider trimming to a skeleton SVG.
            prompt = """
            **Visible Generative UI Retry**
            The previous stream did not produce a visible widget. Reply in visible content immediately.
            First output one valid fenced widget block with widget_code, then at most one short sentence.
            Use this exact form:
            ```show-widget
            {"title":"可视化草图","widget_code":"<svg width=\"100%\" viewBox=\"0 0 680 260\" xmlns=\"http://www.w3.org/2000/svg\"><rect x=\"24\" y=\"24\" width=\"632\" height=\"212\" rx=\"18\" fill=\"#ffffff\" stroke=\"#e5e7eb\"/><text x=\"48\" y=\"64\" font-size=\"20\" font-weight=\"700\" fill=\"#111827\">可视化结果</text><rect x=\"48\" y=\"96\" width=\"160\" height=\"74\" rx=\"14\" fill=\"#eff6ff\" stroke=\"#bfdbfe\"/><text x=\"72\" y=\"140\" font-size=\"15\" fill=\"#1e3a8a\">起点</text><path d=\"M220 133 H300\" stroke=\"#94a3b8\" stroke-width=\"3\" marker-end=\"url(#arrow)\"/><rect x=\"312\" y=\"96\" width=\"160\" height=\"74\" rx=\"14\" fill=\"#f0fdf4\" stroke=\"#bbf7d0\"/><text x=\"336\" y=\"140\" font-size=\"15\" fill=\"#166534\">过程</text><path d=\"M484 133 H552\" stroke=\"#94a3b8\" stroke-width=\"3\" marker-end=\"url(#arrow)\"/><circle cx=\"600\" cy=\"133\" r=\"36\" fill=\"#fff7ed\" stroke=\"#fed7aa\"/><text x=\"582\" y=\"140\" font-size=\"15\" fill=\"#9a3412\">结果</text><defs><marker id=\"arrow\" markerWidth=\"8\" markerHeight=\"8\" refX=\"7\" refY=\"4\" orient=\"auto\"><path d=\"M0,0 L8,4 L0,8 Z\" fill=\"#94a3b8\"/></marker></defs></svg>"}
            ```
            Replace the labels with the actual answer content.
            Keep the SVG small, static, and self-contained.
            Do not use renderer/spec in this retry because the timeline needs widget_code for streaming partial render.
            Do not put widget JSON, SVG, HTML, or renderer/spec inside hidden reasoning.
            Do not output Markdown-only prose for this retry.
            """.trimIndent()
        )
        val first = firstOrNull()
        return if (first?.role == MessageRole.SYSTEM) {
            listOf(first, instruction) + drop(1)
        } else {
            listOf(instruction) + this
        }
    }

    private fun List<UIMessage>.withLocalGenerativeUiFallbackWidget(
        baseMessages: List<UIMessage>,
        model: Model,
    ): List<UIMessage> {
        val fallbackText = buildLocalGenerativeUiFallbackWidget(labels = fallbackWidgetLabels(baseMessages))
        val lastAssistantIndex = indexOfLast { it.role == MessageRole.ASSISTANT }
        if (lastAssistantIndex < 0) {
            return this + UIMessage(
                role = MessageRole.ASSISTANT,
                modelId = model.id,
                parts = listOf(UIMessagePart.Text(fallbackText)),
            )
        }
        return mapIndexed { index, message ->
            if (index == lastAssistantIndex) {
                message.copy(parts = message.parts + UIMessagePart.Text("\n\n$fallbackText"))
            } else {
                message
            }
        }
    }

    private fun List<UIMessage>.fallbackWidgetLabels(baseMessages: List<UIMessage>): List<String> {
        val assistantLines = lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.lineSequence()
            ?.mapNotNull(::cleanFallbackWidgetLine)
            ?.distinct()
            ?.take(4)
            ?.toList()
            .orEmpty()
        if (assistantLines.isNotEmpty()) return assistantLines

        val userHint = baseMessages.lastOrNull { it.role == MessageRole.USER }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString(" ") { it.text }
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.take(26)
            ?.takeIf { it.isNotBlank() }
        return listOfNotNull("需求", userHint, "结构化", "结论").take(4)
    }

    private fun cleanFallbackWidgetLine(line: String): String? {
        val compact = line
            .trim()
            .removePrefix("-")
            .removePrefix("*")
            .removePrefix("•")
            .removePrefix("·")
            .replace(Regex("""^\d+[.)、]\s*"""), "")
            .replace(Regex("""[#*_`><]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (compact.length < 2) return null
        if (compact.startsWith("```")) return null
        if (compact.contains("widget_code") || compact.contains("renderer")) return null
        if (GenerativeWidgetParser.containsWidgetFence(compact)) return null
        return compact.take(30)
    }

    private fun buildLocalGenerativeUiFallbackWidget(labels: List<String>): String {
        val nodes = buildJsonArray {
            labels.take(4).forEach { label ->
                add(buildJsonObject { put("label", label) })
            }
        }
        val widget = buildJsonObject {
            put("title", "可视化摘要")
            put("renderer", "diagram")
            put(
                "spec",
                buildJsonObject {
                    put("type", "flow")
                    put("nodes", nodes)
                }
            )
        }
        return """
        ```show-widget
        $widget
        ```
        """.trimIndent()
    }

    private fun shouldFallbackToVisionRecognition(error: Throwable): Boolean {
        if (error is ImageEncodingException) return true
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return listOf(
            "image",
            "vision",
            "modalit",
            "unsupported url",
            "unsupported file",
            "invalid file",
            "invalid mime",
            "decode",
            "base64"
        ).any { it in message }
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customHeaders = model.customHeaders,
                    customBody = buildList {
                        addAll(model.customBodies)
                        add(
                            CustomBody(
                                key = "translation_options",
                                value = buildJsonObject {
                                    put("source_lang", JsonPrimitive("auto"))
                                    put(
                                        "target_lang",
                                        JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                    )
                                }
                            )
                        )
                    }
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
