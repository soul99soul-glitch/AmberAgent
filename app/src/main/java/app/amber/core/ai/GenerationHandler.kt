package app.amber.core.ai

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
import app.amber.ai.core.MessageRole
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.core.SYSTEM_PROMPT_CACHE_CONTROL_METADATA
import app.amber.ai.core.SYSTEM_PROMPT_CACHE_EPHEMERAL
import app.amber.ai.core.Tool
import app.amber.ai.core.merge
import app.amber.ai.provider.CustomBody
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.Provider
import app.amber.ai.provider.ProviderManager
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.registry.ModelRegistry
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.MessageStreamAccumulator
import app.amber.ai.ui.UIMessagePart
import app.amber.ai.ui.ToolApprovalState
import app.amber.ai.ui.handleMessageChunk
import app.amber.ai.util.ImageEncodingException
import app.amber.core.ai.transformers.InputMessageTransformer
import app.amber.core.ai.transformers.MessageTransformer
import app.amber.core.ai.transformers.OutputMessageTransformer
import app.amber.core.ai.transformers.onGenerationFinish
import app.amber.core.ai.transformers.transforms
import app.amber.core.ai.transformers.visualTransforms
import app.amber.core.ai.transformers.visualTransformsStreamingTail
import app.amber.core.ai.generative.GenerativeUiPlanner
import app.amber.core.ai.generative.GenerativeUiWidgetRequirement
import app.amber.core.ai.generative.GenerativeWidgetParser
import app.amber.core.ai.generative.GuizangHtmlDeckValidator
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.AgentLoopBudgetPrompt
import app.amber.feature.runtime.SpeculativeToolRunner
import app.amber.feature.runtime.ToolInvocationContext
import app.amber.feature.tools.ToolExposureState
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.findProvider
import app.amber.core.settings.resolveSessionDefaults
import app.amber.core.context.ConversationContextEngine
import app.amber.core.context.ConversationContextPlanner
import app.amber.core.memory.recall.MemoryRecallStore
import app.amber.core.model.Assistant
import app.amber.core.model.AssistantMemory
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.repository.MemoryRepository
import app.amber.agent.R
import app.amber.agent.BuildConfig
import java.util.Locale
import kotlin.uuid.Uuid
import kotlin.time.Clock

private const val TAG = "GenerationHandler"
private const val PERF_TAG = "AmberChatPerf"
// 2026-05-15 — flush cadence rationale.
//
// Was 200ms historically. User feedback after that: "一坨一坨蹦出来", not
// "一个字一个字蹦出来 like Claude Code CLI". Tried 16ms in 1.8.12 — fixed
// the chunk-reveal feel but user reported "往上滚动的帧数好像变低了". Root
// cause of the regression: 16ms means up to 60 Compose recompose +
// Markdown parse schedule + LazyColumn item re-layout + animateScrollBy
// cancel-and-restart cycles per second. Even with Markdown parse off the
// main thread (mapLatest on Dispatchers.Default), the recompose+layout
// chain itself eats enough budget on a mid-range device to choke user
// gesture scroll and the streaming auto-scroll animation.
//
// 33ms (~30Hz) is the sweet spot:
//   - SSE chunks from real providers arrive every 50-200ms (5-20 tokens
//     each on the fast end). 33ms is below that floor, so a chunk-arrival
//     reaches the UI on the very next 33ms tick — practically immediate.
//     The only case where 33ms differs from 16ms is when two chunks land
//     <33ms apart, which is rare and not perceptually distinguishable
//     anyway (one tick visually = one font row).
//   - Halves the per-second recompose budget vs 16ms, freeing CPU for
//     scroll animation interpolation and gesture handling.
//
// If a future profile shows this is still too aggressive, the next stop
// is 50ms. Do not tie this producer-side flush to 120Hz: high-refresh
// smoothness should come from the lightweight display/reveal layer, while
// Markdown parse/layout stays on a lower-frequency budget.
private const val STREAM_UI_FLUSH_INTERVAL_MS = 33L
private const val GENERATIVE_UI_REASONING_ONLY_FALLBACK_MS = 5_000L
private const val GENERATIVE_UI_REASONING_ONLY_FALLBACK_CHARS = 800
// "Did the model produce real prose?" threshold for skipping the local fallback widget
// after a retry. ~30 chars is "looks like a real sentence in either CN or EN", below
// that we treat the response as effectively empty and let the skeleton widget kick in.
private const val MEANINGFUL_TEXT_MIN_CHARS = 30

private class GenerativeUiReasoningOnlyStreamException : RuntimeException(
    "Generative UI stream emitted only hidden reasoning without visible content"
)

private class GenerativeUiInvalidWidgetStreamException(
    val issue: String,
) : RuntimeException("Generative UI stream completed without a valid widget: $issue")

// GenerationChunk + GenerationUpdate moved to :core:ai:generation:api so
// consumers (subagent, board, chat impl, DeepRead) can depend on the
// interface module without pulling the heavy :app implementation. See
// commit T4.2 — Phase D cascade un-deferral.

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
) : Generator {
    override fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer>,
        outputTransformers: List<OutputMessageTransformer>,
        assistant: Assistant,
        memories: List<AssistantMemory>?,
        tools: List<Tool>,
        maxSteps: Int,
        processingStatus: MutableStateFlow<String?>,
        autoApproveTools: Boolean,
        autoApproveHighRiskTools: Boolean,
        autoApprovedToolNames: Set<String>,
        invocationContext: ToolInvocationContext,
        conversation: Conversation?,
        consumeSteerMessages: suspend () -> List<UIMessage>,
    ): Flow<GenerationChunk> = flow {
        coroutineScope {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        var messages: List<UIMessage> = messages
        val toolExposure = ToolExposureState.from(tools)

        for (stepIndex in 0 until maxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()
            toolExposure.exposeToolNames(pendingTools.map { it.toolName })
            val hasResumableTools = pendingTools.isNotEmpty()
            val loopBudgetPrompt = AgentLoopBudgetPrompt.build(stepIndex = stepIndex, maxSteps = maxSteps)
            val shouldHideToolsForBudget = AgentLoopBudgetPrompt.shouldHideTools(
                stepIndex = stepIndex,
                maxSteps = maxSteps,
                hasResumableTools = hasResumableTools,
            )
            val directWidgetRequested =
                GenerativeUiPlanner.shouldGenerateDirectWidgetWithoutTools(settings.agentRuntime.generativeUi, messages)
            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                addAll(
                    if ((directWidgetRequested && !hasResumableTools) || shouldHideToolsForBudget) {
                        emptyList()
                    } else {
                        toolExposure.toolsForStep()
                    }
                )
            }
            val speculativeRunner = if (settings.agentRuntime.speculativeToolExecution.enabled && assistant.streamOutput) {
                SpeculativeToolRunner(
                    scope = this,
                    dispatcher = toolDispatcher,
                    maxConcurrentTools = settings.agentRuntime.speculativeToolExecution.maxConcurrentTools,
                    invocationContext = invocationContext,
                )
            } else {
                null
            }

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                var streamingVisualBaselineReady = false
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = { update ->
                        messages = update.messages.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        val startedAt = if (BuildConfig.DEBUG) System.nanoTime() else 0L
                        val visualMessages = if (update.isStreamingTail && streamingVisualBaselineReady) {
                            messages.visualTransformsStreamingTail(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings
                            )
                        } else {
                            streamingVisualBaselineReady = true
                            messages.visualTransforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings
                            )
                        }
                        if (BuildConfig.DEBUG) {
                            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
                            Log.d(
                                PERF_TAG,
                                "streamFlush kind=${if (update.isStreamingTail) "tail" else "full"} " +
                                    "messages=${visualMessages.size} elapsedMs=${String.format(Locale.US, "%.2f", elapsedMs)}",
                            )
                        }
                        emit(
                            GenerationChunk.Messages(
                                messages = visualMessages,
                                update = update.withMessages(visualMessages),
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
                    loopBudgetPrompt = loopBudgetPrompt,
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
                        invocationContext = invocationContext,
                    )
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        decision.action == app.amber.feature.runtime.PermissionDecisionAction.ASK -> {
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
                invocationContext = invocationContext,
                prefetchedTools = speculativeRunner?.reusableResults(toolsToProcess).orEmpty(),
                retrySetting = settings.agentRuntime.generationRetry,
            )

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }
            toolExposure.observeExecutedTools(executedTools)

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
        onUpdateMessages: suspend (GenerationUpdate) -> Unit,
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
        loopBudgetPrompt: String = "",
    ) {
        val sessionDefaults = settings.resolveSessionDefaults(assistant, model)
        val memoryContextPrompt = memoryRecallStore.buildPrompt(settings, messages)
        val systemParts = buildSystemPromptParts(
            settings = settings,
            assistant = assistant,
            model = model,
            messages = messages,
            tools = tools,
            memoryContextPrompt = memoryContextPrompt,
            loopBudgetPrompt = loopBudgetPrompt,
        )
        val system = systemParts.filterIsInstance<UIMessagePart.Text>().joinToString("\n\n") { it.text }
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
                if (systemParts.isNotEmpty()) add(UIMessage(role = MessageRole.SYSTEM, parts = systemParts))
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
        val generativeUiWidgetRequirement =
            GenerativeUiPlanner.widgetRequirement(settings.agentRuntime.generativeUi, messages)
        val shouldGuardGenerativeUiReasoningOnly =
            GenerativeUiPlanner.needsVisibleStreamingFallback(settings.agentRuntime.generativeUi, messages)
        if (stream) {
            runProviderCallWithRetry(
                retrySetting = settings.agentRuntime.generationRetry,
                processingStatus = processingStatus,
                providerName = provider.name,
                modelName = model.displayName,
                onBeforeRetry = {
                    messages = baseMessages
                    onUpdateMessages(GenerationUpdate.full(messages))
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
                    widgetRequirement: GenerativeUiWidgetRequirement,
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
                            onUpdateMessages(GenerationUpdate.streamingTail(messages))
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
                    onUpdateMessages(GenerationUpdate.full(messages))
                    if (widgetRequirement.required && !messages.hasPendingToolCalls()) {
                        messages.visibleWidgetIssue(widgetRequirement)?.let { issue ->
                            throw GenerativeUiInvalidWidgetStreamException(issue)
                        }
                    }
                }

                try {
                    streamWith(
                        providerMessages = internalMessages,
                        streamParams = params,
                        guardReasoningOnly = shouldGuardGenerativeUiReasoningOnly,
                        widgetRequirement = generativeUiWidgetRequirement,
                    )
                } catch (error: Throwable) {
                    if (
                        error is GenerativeUiReasoningOnlyStreamException ||
                        error is GenerativeUiInvalidWidgetStreamException
                    ) {
                        val widgetIssue = (error as? GenerativeUiInvalidWidgetStreamException)?.issue
                        messages = baseMessages
                        onUpdateMessages(GenerationUpdate.full(messages))
                        processingStatus.value = if (generativeUiWidgetRequirement.expectSlides) {
                            "正在修复演示卡片..."
                        } else {
                            "正在切换为可见输出模式生成可视化..."
                        }
                        streamWith(
                            providerMessages = internalMessages.withGenerativeUiVisibleFallbackPrompt(
                                requirement = generativeUiWidgetRequirement,
                                previousIssue = widgetIssue,
                            ),
                            streamParams = params.copy(
                                tools = emptyList(),
                                reasoningLevel = ReasoningLevel.OFF,
                            ),
                            guardReasoningOnly = false,
                            widgetRequirement = GenerativeUiWidgetRequirement.None,
                        )
                        // Only force-inject the local fallback widget when the retry
                        // ALSO produced no meaningful visible text. Previously we
                        // injected unconditionally on "no widget fence", which meant a
                        // model that legitimately decided "this question doesn't need a
                        // visualisation, here's a normal answer" had a synthetic
                        // skeleton widget appended to its perfectly good text — visible
                        // as the "widget keeps appearing on every reply" loop the user
                        // reported.
                        val retryIssue = messages.visibleWidgetIssue(generativeUiWidgetRequirement)
                        val shouldAppendLocalFallback = if (generativeUiWidgetRequirement.required) {
                            retryIssue != null && !messages.hasPendingToolCalls()
                        } else {
                            !messages.hasVisibleWidgetFence() && !messages.hasMeaningfulVisibleAssistantText()
                        }
                        if (shouldAppendLocalFallback) {
                            messages = messages.withLocalGenerativeUiFallbackWidget(
                                baseMessages = baseMessages,
                                model = model,
                                requirement = generativeUiWidgetRequirement,
                            )
                            onUpdateMessages(GenerationUpdate.full(messages))
                        }
                        return@runProviderCallWithRetry
                    }
                    if (!canUseVisionFallback || !shouldFallbackToVisionRecognition(error)) throw error
                    processingStatus.value = "正在改用视觉识别模型读取图片..."
                    streamWith(
                        providerMessages = prepareInternalMessages(forceImageToText = true),
                        streamParams = params,
                        guardReasoningOnly = shouldGuardGenerativeUiReasoningOnly,
                        widgetRequirement = generativeUiWidgetRequirement,
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
                    onUpdateMessages(GenerationUpdate.full(messages))
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
                suspend fun generateWith(
                    providerMessages: List<UIMessage>,
                    generateParams: TextGenerationParams = params,
                ) =
                    providerImpl.generateText(
                        providerSetting = provider,
                        messages = providerMessages,
                        params = generateParams,
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
                val widgetIssue = messages.visibleWidgetIssue(generativeUiWidgetRequirement)
                if (widgetIssue != null && !messages.hasPendingToolCalls()) {
                    processingStatus.value = if (generativeUiWidgetRequirement.expectSlides) {
                        "正在修复演示卡片..."
                    } else {
                        "正在切换为可见输出模式生成可视化..."
                    }
                    val retryChunk = generateWith(
                        providerMessages = internalMessages.withGenerativeUiVisibleFallbackPrompt(
                            requirement = generativeUiWidgetRequirement,
                            previousIssue = widgetIssue,
                        ),
                        generateParams = params.copy(
                            tools = emptyList(),
                            reasoningLevel = ReasoningLevel.OFF,
                        ),
                    )
                    messages = baseMessages.handleMessageChunk(chunk = retryChunk, model = model)
                    retryChunk.usage?.let { usage ->
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
                    if (
                        messages.visibleWidgetIssue(generativeUiWidgetRequirement) != null &&
                        !messages.hasPendingToolCalls()
                    ) {
                        messages = messages.withLocalGenerativeUiFallbackWidget(
                            baseMessages = baseMessages,
                            model = model,
                            requirement = generativeUiWidgetRequirement,
                        )
                    }
                }
                onUpdateMessages(GenerationUpdate.full(messages))
            }
        }
    }

    private suspend fun buildSystemPromptParts(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        messages: List<UIMessage>,
        tools: List<Tool>,
        memoryContextPrompt: String,
        loopBudgetPrompt: String,
    ): List<UIMessagePart> {
        val staticPrompt = listOf(
            buildAgentSoulPrompt(settings.agentRuntime.agentSoulMarkdown),
            assistant.systemPrompt,
        ).filter { it.isNotBlank() }.joinToString("\n\n")

        val dynamicPrompt = buildList {
            if (memoryContextPrompt.isNotBlank()) add(memoryContextPrompt)
            if (loopBudgetPrompt.isNotBlank()) add(loopBudgetPrompt)
            buildGenerativeUiPrompt(settings.agentRuntime.generativeUi, model).takeIf { it.isNotBlank() }?.let(::add)
            val hasImageGenTool = tools.any { it.name == "generate_image" }
            GenerativeUiPlanner.buildPrompt(
                setting = settings.agentRuntime.generativeUi,
                messages = messages,
                hasImageGenTool = hasImageGenTool,
            ).takeIf { it.isNotBlank() }?.let(::add)
            if (settings.agentRuntime.enableRecentChatsReference) add(buildRecentChatsPrompt(conversationRepo))
        }.joinToString("\n\n")

        val toolPrompt = tools.mapNotNull { tool ->
            tool.systemPrompt(model, messages).takeIf { it.isNotBlank() }
        }.joinToString("\n\n")

        return buildList {
            if (staticPrompt.isNotBlank()) {
                add(
                    UIMessagePart.Text(
                        text = staticPrompt,
                        metadata = buildJsonObject {
                            put(SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_EPHEMERAL)
                            put("system_prompt_block", "static")
                        },
                    )
                )
            }
            if (dynamicPrompt.isNotBlank()) {
                add(
                    UIMessagePart.Text(
                        text = dynamicPrompt,
                        metadata = buildJsonObject { put("system_prompt_block", "dynamic") },
                    )
                )
            }
            if (toolPrompt.isNotBlank()) {
                add(
                    UIMessagePart.Text(
                        text = toolPrompt,
                        metadata = buildJsonObject { put("system_prompt_block", "tool_prompts") },
                    )
                )
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

    private fun List<UIMessage>.hasPendingToolCalls(): Boolean =
        lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.getTools()
            ?.any { !it.isExecuted } == true

    private fun List<UIMessage>.visibleWidgetIssue(requirement: GenerativeUiWidgetRequirement): String? {
        if (!requirement.required) return null
        val content = lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            .orEmpty()
        return GenerativeWidgetParser.widgetQualityIssue(content, requirement)
    }

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

    private fun List<UIMessage>.withGenerativeUiVisibleFallbackPrompt(
        requirement: GenerativeUiWidgetRequirement,
        previousIssue: String?,
    ): List<UIMessage> {
        val instruction = UIMessage.system(prompt = buildGenerativeUiRetryPrompt(requirement, previousIssue))
        val first = firstOrNull()
        return if (first?.role == MessageRole.SYSTEM) {
            listOf(first, instruction) + drop(1)
        } else {
            listOf(instruction) + this
        }
    }

    private fun buildGenerativeUiRetryPrompt(
        requirement: GenerativeUiWidgetRequirement,
        previousIssue: String?,
    ): String {
        val issue = previousIssue?.takeIf { it.isNotBlank() } ?: "missing required show-widget"
        if (requirement.expectFullHtmlDeck) {
            return """
                **Visible Full HTML Deck Repair**
                The previous output did not produce a valid full_html deck: $issue
                Reply in visible content immediately with exactly one fenced `show-widget` JSON block, but do not begin the block until the full JSON can be completed in this response. Do not output a MiniApp, standalone webpage, generic HTML app, Markdown-only answer, or hidden-only reasoning.
                Required JSON shape:
                - `renderer` must be `"${GuizangHtmlDeckValidator.RENDERER}"`.
                - `widget_code` is only a static SVG cover preview.
                - `spec.html` is the full live deck HTML as one JSON string.
                - `spec.html` must contain `<div id="deck">` and one or more `<section class="slide ...">` pages.
                - Scripts may only use `${GuizangHtmlDeckValidator.LOCAL_MOTION_URL}` and `${GuizangHtmlDeckValidator.LOCAL_LUCIDE_URL}`. Do not use CDN script URLs.
                - Preserve the requested PPT/deck content and style; keep the JSON valid, complete, and compact enough to fit. Never emit partial spec.html.
            """.trimIndent()
        }
        if (requirement.expectSlides) {
            return """
                **Visible Slide Deck Repair**
                The previous output did not produce a valid deck card: $issue
                Reply in visible content immediately with exactly one fenced `show-widget` JSON block, but do not begin the block until the full JSON can be completed in this response. Do not output a MiniApp, standalone webpage, generic HTML app, Markdown-only answer, or hidden-only reasoning.
                Use renderer `"${GuizangHtmlDeckValidator.RENDERER}"`.
                Put a complete mobile-readable HTML deck in `spec.html` with `<div id="deck">` and `<section class="slide ...">` pages.
                Keep the deck concise and the JSON valid. Never emit partial spec.html.
            """.trimIndent()
        }
        return """
            **Visible Generative UI Retry**
            The previous stream did not produce a visible widget: $issue
            Reply in visible content immediately.
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
    }

    private fun List<UIMessage>.withLocalGenerativeUiFallbackWidget(
        baseMessages: List<UIMessage>,
        model: Model,
        requirement: GenerativeUiWidgetRequirement = GenerativeUiWidgetRequirement.None,
    ): List<UIMessage> {
        val labels = fallbackWidgetLabels(baseMessages)
        val fallbackText = when {
            requirement.expectFullHtmlDeck || requirement.expectSlides -> buildLocalFullHtmlDeckFallbackWidget(labels)
            else -> buildLocalGenerativeUiFallbackWidget(labels)
        }
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

    private fun buildLocalSlidesFallbackWidget(labels: List<String>): String {
        val safeLabels = labels.ifEmpty { listOf("需求", "结构", "要点", "结论") }.take(4)
        val slides = buildJsonArray {
            add(
                buildJsonObject {
                    put("layout", "cover")
                    put("title", safeLabels.first())
                    put("subtitle", "AmberAgent deck fallback")
                    put("content", buildJsonArray {
                        safeLabels.drop(1).forEach { add(JsonPrimitive(it)) }
                    })
                }
            )
            safeLabels.drop(1).forEach { label ->
                add(
                    buildJsonObject {
                        put("layout", "section")
                        put("title", label)
                        put("content", buildJsonArray { add(JsonPrimitive(label)) })
                    }
                )
            }
        }
        val widget = buildJsonObject {
            put("title", safeLabels.first().take(20).ifBlank { "演示预览" })
            put("renderer", "slides")
            put(
                "spec",
                buildJsonObject {
                    put("schemaVersion", 2)
                    put("style", "swiss")
                    put("accent", "#1F5EFF")
                    put("slides", slides)
                }
            )
        }
        return """
        ```show-widget
        $widget
        ```
        """.trimIndent()
    }

    private fun buildLocalFullHtmlDeckFallbackWidget(labels: List<String>): String {
        val safeLabels = labels.ifEmpty { listOf("演示预览", "结构", "要点", "结论") }.take(4)
        val title = safeLabels.first().take(24).ifBlank { "演示预览" }
        val html = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            appendLine("<style>")
            appendLine("html,body{margin:0;width:100%;height:100%;background:#f8fafc;color:#111827;font-family:-apple-system,BlinkMacSystemFont,'Noto Sans SC',sans-serif;overflow:hidden}")
            appendLine("#deck{width:100vw;height:100vh;display:flex;overflow-x:auto;scroll-snap-type:x mandatory;touch-action:pan-x}")
            appendLine(".slide{min-width:100vw;height:100vh;box-sizing:border-box;padding:9vh 8vw;scroll-snap-align:start;display:flex;flex-direction:column;justify-content:center;gap:22px}")
            appendLine(".slide:nth-child(odd){background:#0f172a;color:#f8fafc}.kicker{font-size:13px;letter-spacing:.18em;text-transform:uppercase;color:#38bdf8}.title{font-size:clamp(34px,9vw,84px);line-height:1.02;font-weight:800}.body{font-size:clamp(18px,4vw,34px);line-height:1.45;max-width:760px}.rule{width:88px;height:6px;background:#ef4444}")
            appendLine("</style></head><body><div id=\"deck\">")
            safeLabels.forEachIndexed { index, label ->
                val safe = escapeHtml(label)
                appendLine("<section class=\"slide ${if (index == 0) "dark" else "light"}\" data-slide=\"${index + 1}\">")
                appendLine("<div class=\"kicker\">FULL HTML DECK</div><div class=\"rule\"></div>")
                appendLine("<div class=\"title\">$safe</div>")
                appendLine("<div class=\"body\">${if (index == 0) "移动端可左右滑动浏览。" else safe}</div>")
                appendLine("</section>")
            }
            appendLine("</div><script>window.__pipeAdvance=function(){return false};window.__setLowPowerMode=function(){}</script></body></html>")
        }
        val cover = """
            <svg width="100%" viewBox="0 0 680 220" xmlns="http://www.w3.org/2000/svg">
              <rect width="680" height="220" rx="16" fill="#0f172a"/>
              <rect x="32" y="30" width="616" height="160" rx="10" fill="none" stroke="#38bdf8" stroke-width="2"/>
              <text x="54" y="72" font-size="13" letter-spacing="3" fill="#7dd3fc">FULL HTML DECK</text>
              <text x="54" y="126" font-size="30" font-weight="800" fill="#f8fafc">${escapeHtml(title)}</text>
              <text x="54" y="164" font-size="15" fill="#cbd5e1">fallback live deck preview</text>
            </svg>
        """.trimIndent()
        val widget = buildJsonObject {
            put("title", title)
            put("renderer", GuizangHtmlDeckValidator.RENDERER)
            put("widget_code", cover)
            put(
                "spec",
                buildJsonObject {
                    put("title", title)
                    put("html", html)
                    put("source", "amberagent-fallback")
                    put("allowRemoteImages", true)
                    put("allowRemoteFonts", true)
                }
            )
        }
        return """
        ```show-widget
        $widget
        ```
        """.trimIndent()
    }

    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

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

}
